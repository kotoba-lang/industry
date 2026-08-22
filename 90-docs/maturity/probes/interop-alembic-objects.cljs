(ns probe-interop-alembic-objects
  (:require [ogawa.core :as og] [alembic.objects :as abc] [clojure.string :as str]))
;; Alembic の object 層。容器（Ogawa）の上で「このファイルに何が入っているか」を
;; 答える層で、**幾何は答えない**。
;;
;; 静かに間違う 3 つを見る:
;;   (1) **末尾 32 バイトはハッシュ** —— 飛ばさない reader は digest の中に
;;       名前長を見つけて、ハッシュからオブジェクトを捏造する
;;   (2) **0xff は inline であって index 255 ではない** —— index と誤ると
;;       別の schema を報告する = 下流では**型が違うオブジェクト**
;;   (3) **indexed metadata の先頭は空文字列** —— 抜くと全 index が 1 ずれ、
;;       **全オブジェクトが隣の schema を名乗る**
;; そして幾何を返さないこと自体も検査する。
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
        child (first (:object/children (:archive/top a)))]
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
      (contains? child :object/property-values)
      (println "PROBE interop-alembic-objects FAIL"
               "幾何と読み違えられる key を返している —— property/sample 層は未実装")
      :else
      (println "PROBE interop-alembic-objects PASS"
               (str "仕様から組んだ archive の入れ子 " (pr-str (abc/object-paths a))
                    " / index と inline の両方の metadata / ハッシュ 32 バイトを飛ばす / "
                    "版 < 9999 と非 Ogawa を拒否 / 幾何を名乗る key は返さない"))))
  (catch :default ex (println "PROBE interop-alembic-objects UNMEASURABLE" (.-message ex))))
