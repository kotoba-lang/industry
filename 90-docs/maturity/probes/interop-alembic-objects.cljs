(ns probe-interop-alembic-objects
  (:require [ogawa.core :as og] [alembic.objects :as abc] [alembic.properties :as ap]
            [clojure.string :as str]))
;; Alembic の object 層と property/sample 層。容器（Ogawa）の上で「このファイルに
;; 何が入っているか」と「その数はいくつか」を答える。
;;
;; 静かに間違う 3 つを見る:
;;   (1) **末尾 32 バイトはハッシュ** —— 飛ばさない reader は digest の中に
;;       名前長を見つけて、ハッシュからオブジェクトを捏造する
;;   (2) **0xff は inline であって index 255 ではない** —— index と誤ると
;;       別の schema を報告する = 下流では**型が違うオブジェクト**
;;   (3) **indexed metadata の先頭は空文字列** —— 抜くと全 index が 1 ずれ、
;;       **全オブジェクトが隣の schema を名乗る**
;; sample 層でも同じ形の 3 つ:
;;   (4) **size hint が header 内の全ての長さの幅を決める** —— 全部 uint32 で
;;       読むと短い header を踏み越え、余りから存在しない property を作る
;;   (5) **0x0200 が無いときの暗黙域は 0x800 の有無で変わる** —— 両方 [0,0] に
;;       畳むと**全てのアニメーション property が定数として読める**（sample 0 は
;;       正しいので「キャッシュが動いていない」ように見え、parse バグに見えない）
;;   (6) **sample block の先頭 16 バイトは鍵** —— 飛ばさないと点列は長さも
;;       型も合ったまま、全ての値がずれる
;; そして schema を解釈しないこと自体も検査する。
(try
  (let [u32 (fn [n] (mapv (fn [i] (mod (long (/ n (js/Math.pow 256 i))) 256)) (range 4)))
        s->b (fn [s] (mapv (fn [c] (.charCodeAt c 0)) s))
        ;; ハッシュ部は **valid な header として読めるバイト**にする。0 や 0xff だと
        ;; 偶然パーサが止まり、飛ばし忘れた reader も通ってしまう
        hb (vec (take 32 (concat (u32 4) (s->b "hash") [0] (repeat 0x41))))
        sch "schema=AbcGeom_PolyMesh_v1"
        table (vec (concat [(count sch)] (s->b sch)))
        hdr (fn [entries]
              (vec (concat (mapcat (fn [[nm idx inline]]
                                     (concat (u32 (count nm)) (s->b nm)
                                             (if inline
                                               (concat [0xff] (u32 (count inline)) (s->b inline))
                                               [idx])))
                                   entries)
                           hb)))
        leaf (og/group (og/group) (og/data (hdr [])))
        mid (og/group (og/group) leaf (og/data (hdr [["child" 1 nil]])))
        top (og/group (og/group) mid (og/data (hdr [["parent" 1 nil]])))
        mk (fn [{:keys [ver top table]}]
             (og/group (og/data [1 0 0 0]) (og/data (u32 (or ver 10000)))
                       (or top (og/group)) (og/data (s->b "application=kotoba"))
                       (og/data []) (og/data (or table []))))
        rd (fn [root] (let [[_ bs] (og/write-archive root)] (abc/read-archive bs)))
        [st a] (rd (mk {:top top :table table}))
        inline-top (og/group (og/group) leaf
                             (og/data (hdr [["xf" nil "schema=AbcGeom_Xform_v3"]])))
        [_ ia] (rd (mk {:top inline-top :table table}))
        hash-only (og/group (og/group) (og/data hb))
        [_ ha] (rd (mk {:top hash-only}))
        child (first (:object/children (:archive/top a)))
        ;; ── property / sample ────────────────────────────────────────────
        ;; WriteUtil.cpp の規則で組み、ReadUtil.cpp の規則で読む。別々に
        ;; 書き起こした 2 つなので、片方の誤りがもう片方で打ち消えない。
        f32 (fn [x] (let [b (js/ArrayBuffer. 4) d (js/DataView. b)]
                      (.setFloat32 d 0 x true) (mapv #(.getUint8 d %) (range 4))))
        pinfo (fn [{:keys [kind nm pod extent samples first-c last-c]
                    :or {extent 1 samples 1 first-c 0 last-c 0}}]
                (let [pods {:bool 0 :uint8 1 :int8 2 :uint16 3 :int16 4 :uint32 5
                            :int32 6 :uint64 7 :int64 8 :float16 9 :float32 10
                            :float64 11 :string 12 :wstring 13}
                      compound? (= :compound kind)
                      info (if compound?
                             0
                             (bit-or (if (= :scalar kind) 1 2)
                                     (bit-and 0x00f0 (bit-shift-left (pods pod) 4))
                                     (bit-and 0xff000 (bit-shift-left extent 12))
                                     (if (and (zero? first-c) (zero? last-c)) 0x800 0)
                                     (if (and (not (and (zero? first-c) (zero? last-c)))
                                              (or (not= first-c 1)
                                                  (not= last-c (dec samples))))
                                       0x0200 0)))]
                  (vec (concat (u32 info)
                               (when-not compound? [samples])
                               (when-not (zero? (bit-and info 0x0200)) [first-c last-c])
                               [(count nm)] (s->b nm)))))
        key16 (vec (repeat 16 0))
        sdata (fn [bs] (og/data (concat key16 bs)))
        geom (og/group
              (og/group (sdata (mapcat f32 [0.0 0.0 0.0 1.0 0.0 0.0 2.0 0.5 0.0]))
                        (og/data []))
              (og/data (pinfo {:kind :array :nm "P" :pod :float32 :extent 3})))
        obj (og/group (og/group geom (og/data (pinfo {:kind :compound :nm ".geom"})))
                      (og/data []))
        props (ap/properties obj [""])
        P (ap/find-property props "/.geom/P")
        [pst praw] (ap/read-elements P 0)
        pts (when (= :ok pst) praw)
        ;; **0x0200 を立てない** header —— first/last を明示すると暗黙域の分岐を
        ;; 一度も通らず、[0,0] へ畳む変異が緑のまま通る（実測 2026-08-24）。
        ;; 書き手は first=1 / last=n-1 のとき bit を立てないので、この形が
        ;; 実ファイルで最も多い animated property の形でもある。
        anim (first (ap/property-headers
                     (pinfo {:kind :array :nm "P" :pod :float32 :extent 3
                             :samples 5 :first-c 1 :last-c 4})
                     [""]))
        refuses? (fn [pod] (try (= :error (first (ap/read-sample
                                                  (assoc P :property/pod pod) 0)))
                                (catch :default _ false)))]
    (cond
      (not= :ok st)
      (println "PROBE interop-alembic-objects FAIL" (str "仕様から組んだ archive を読めない: " (pr-str a)))
      (not= [10000 1] [(:archive/version a) (:archive/ogawa-version a)])
      (println "PROBE interop-alembic-objects FAIL"
               (str "版が読めていない: " (pr-str [(:archive/version a) (:archive/ogawa-version a)])))
      (not= ["/parent" "/parent/child"] (abc/object-paths a))
      (println "PROBE interop-alembic-objects FAIL"
               (str "オブジェクトの入れ子が違う: " (pr-str (abc/object-paths a))))
      (not= ["" sch] (:archive/indexed-metadata a))
      (println "PROBE interop-alembic-objects FAIL"
               (str "indexed metadata の先頭が空文字列でない: " (pr-str (:archive/indexed-metadata a))
                    " —— 全 index が 1 ずれ、全オブジェクトが隣の schema を名乗る"))
      (not= sch (:object/metadata child))
      (println "PROBE interop-alembic-objects FAIL"
               (str "index 経由の metadata が解決しない: " (pr-str (:object/metadata child))))
      (not= "schema=AbcGeom_Xform_v3"
            (:object/metadata (first (:object/children (:archive/top ia)))))
      (println "PROBE interop-alembic-objects FAIL"
               (str "0xff を index として読んでいる（inline metadata が取れない）: "
                    (pr-str (:object/metadata (first (:object/children (:archive/top ia)))))))
      (seq (abc/object-paths ha))
      (println "PROBE interop-alembic-objects FAIL"
               (str "32 バイトのハッシュだけの block からオブジェクトを捏造している: "
                    (pr-str (abc/object-paths ha))))
      (not= {"application" "kotoba"} (abc/metadata-pairs (:archive/metadata a)))
      (println "PROBE interop-alembic-objects FAIL"
               (str "archive の metadata が読めない: " (pr-str (:archive/metadata a))))
      (not= :error (first (rd (mk {:top top :ver 9998}))))
      (println "PROBE interop-alembic-objects FAIL"
               "Alembic 自身が拒否する archive version（< 9999）を受理している")
      (not= :error (first (abc/read-archive (vec (repeat 40 0)))))
      (println "PROBE interop-alembic-objects FAIL" "Ogawa でないバイト列を受理している")
      (not= ["/.geom" "/.geom/P"] (ap/property-paths props))
      (println "PROBE interop-alembic-objects FAIL"
               (str "property の入れ子が違う: " (pr-str (ap/property-paths props))))
      (not= :ok pst)
      (println "PROBE interop-alembic-objects FAIL" (str "sample を読めない: " (pr-str pts)))
      (not= [[0.0 0.0 0.0] [1.0 0.0 0.0] [2.0 0.5 0.0]] pts)
      (println "PROBE interop-alembic-objects FAIL"
               (str "点が違う: " (pr-str pts) " —— 先頭 16 バイトの鍵を飛ばしていないか、"
                    "extent 3 の刻みがずれている"))
      (not= [0 1 2 3 4] (mapv #(ap/stored-index anim %) [0 1 2 3 4]))
      (println "PROBE interop-alembic-objects FAIL"
               (str "verifyIndex が違う: " (pr-str (mapv #(ap/stored-index anim %) (range 5)))
                    " —— 暗黙域を [0,0] に畳むと全ての animation が定数になる"))
      (not (every? refuses? [:string :wstring :float16]))
      (println "PROBE interop-alembic-objects FAIL"
               (str "復号を書き起こしていない POD を拒否できていない: "
                    (pr-str (remove refuses? [:string :wstring :float16]))
                    " —— 数を返すのも throw も同じく駄目で、**名指しで拒否**が要る"
                    "（推測した数は正しい数と見分けが付かない）"))
      (contains? (first props) :property/schema)
      (println "PROBE interop-alembic-objects FAIL"
               "schema を解釈したふりをしている —— `P` が点列であることは AbcGeom の面")
      :else
      (println "PROBE interop-alembic-objects PASS"
               (str "仕様から組んだ archive の入れ子 " (pr-str (abc/object-paths a))
                    " / index と inline の両方の metadata / ハッシュ 32 バイトを飛ばす / "
                    "版 < 9999 と非 Ogawa を拒否 / property " (pr-str (ap/property-paths props))
                    " から点 " (pr-str pts) " を取り出す / verifyIndex "
                    (pr-str (mapv #(ap/stored-index anim %) (range 5)))
                    " / string・wstring・float16 は拒否"
                    "（⚠ schema 解釈と時間サンプリングは AbcGeom の面で未実装）"))))
  (catch :default ex (println "PROBE interop-alembic-objects UNMEASURABLE" (.-message ex))))
