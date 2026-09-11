(ns probe-interop-vdb-file
  (:require [voxel.nvdb :as n] [voxel.vdb-file :as vf] [clojure.string :as str]))
;; `.vdb`（OpenVDB）と `.nvdb`（NanoVDB）の**容器 2 つ**。名前が 1 文字違いの
;; 別形式で、どちらも中の grid buffer は不透明。木は `voxel.vdb` の側にある。
;;
;; `.nvdb` 側で見るのは 4 つ:
;;   (1) 手で組んだセグメント（writer が作ったのではなく、**宣言から byte offset を
;;       書き出した** 176 バイト）を読める —— writer と reader が互いに合意している
;;       だけでは配置を検査したことにならない
;;   (2) magic は**数ではなく 8 バイト**として比較される。0x3242… は 3.6e18 で
;;       2^53 を超えるので、double で組み直すと「近いが等しくない」値になる
;;   (3) 圧縮セグメントは**読み進めずに拒否**する —— 読み進めれば圧縮バイト列が
;;       「grid」として返り、下流ではエラーではなくノイズ入りの grid になる
;;   (4) grid buffer を木に変換する関数が**無い**（容器と木を繋いだふりをしない）
;; `.vdb` 側で見るのは 3 つ:
;;   (5) **uuid は 36 バイトの ASCII であって 16 バイトのバイナリではない** ——
;;       上流のコメントは「16-byte (128-bit) uuid」と書いていて、コードは
;;       16*2+4 を読む。コメントを信じると 20 バイト手前、metadata の個数の
;;       途中に着地し、uuid 文字列の末尾から**もっともらしい小さな数**を読む
;;   (6) **metadata の値は各自のバイト数で枠付けされている** —— 知らない型を
;;       正確に読み飛ばせるので、新しい OpenVDB が書いたファイルも開ける
;;   (7) grid buffer を復号したふりをしない（`:grid/tree` を生やさない）
(try
  (let [u (fn [n w] (mapv (fn [i] (mod (long (/ n (js/Math.pow 256 i))) 256)) (range w)))
        s->b (fn [s] (mapv (fn [c] (.charCodeAt c 0)) s))
        b->s (fn [bs] (apply str (map (fn [x] (js/String.fromCharCode x)) bs)))
        ;; 宣言から byte offset を書き出した FileMetaData
        meta (vec (concat (u 512 8) (u 512 8) (u 7 8) (u 999 8)
                          (u 1 4) (u 2 4)
                          (repeat 48 0)
                          (mapcat #(u % 4) [0 0 0 15 15 15])
                          (repeat 24 0)
                          (u 3 4)
                          (mapcat #(u % 4) [5 2 1 1])
                          (mapcat #(u % 4) [0 0 0])
                          (u 0 2) (u 0 2) (u (n/version 32 7 0) 4)))
        hand (vec (concat (s->b "NanoVDB2") (u (n/version 32 7 0) 4) (u 1 2) (u 0 2)
                          meta (s->b "vel") (repeat 512 0xcd)))
        [hst hsegs] (n/read-segments hand)
        grid (vec (range 100))
        seg (n/segment {:name "density" :grid grid
                        :metadata {:voxel-count 4242 :index-bbox [-8 -8 -8 7 7 7]
                                   :voxel-size [0.1 0.1 0.1] :node-count [12 3 1 1]}})
        [rst rsegs] (n/read-segments seg)
        d (when (vector? rsegs) (first (n/describe rsegs)))
        zip (n/read-segments (assoc seg 14 1))
        blosc (n/read-segments (assoc seg 14 2))
        bad (n/read-segments (vec (concat (s->b "NotAVDB!") (repeat 20 0))))
        ;; ── .vdb（OpenVDB）──────────────────────────────────────────────
        sb (fn [x] (mapv (fn [c] (.charCodeAt c 0)) x))
        vbytes (vf/write-archive
                {:archive/file-version 225 :archive/library-version [12 0]
                 :archive/uuid "d0f2b6a1-4c3e-4a5b-9f01-2233445566aa"
                 :archive/metadata [{:meta/name "future" :meta/type "quatd"
                                     :meta/raw (vec (range 32))}
                                    {:meta/name "creator" :meta/type "string"
                                     :meta/raw (sb "blender")}]
                 :archive/grids [{:grid/name "density" :grid/type "Tree_float_5_4_3"
                                  :grid/half-float? true
                                  :grid/pos 100 :grid/block-pos 140 :grid/end-pos 900}]})
        ;; **例外は UNMEASURABLE ではない。** 読み手が投げるのは「測れなかった」
        ;; ではなく「壊れている」で、UNMEASURABLE は pass にも fail にも数えない
        ;; バケツなので、そこへ落とすと**壊れた軸が失敗の集計から消える**。
        ;; ここで捕まえて :error に畳む（実測 2026-08-24、uuid 幅の変異が
        ;; Index out of bounds で UNMEASURABLE を出した）。
        safe (fn [f] (try (f) (catch :default ex [:error (str "投げた: " (.-message ex))])))
        [vst va] (safe #(vf/read-archive vbytes))
        vd (when (= :ok vst) (safe #(vf/describe va)))
        vmeta (if (map? vd) (:metadata vd) {})
        vgrids (if (map? vd) (:grids vd) [])]
    (cond
      (not= 176 (count meta))
      (println "PROBE interop-vdb-file FAIL" (str "FileMetaData が 176 バイトでない: " (count meta)))
      (not= :ok hst)
      (println "PROBE interop-vdb-file FAIL"
               (str "宣言から手で組んだセグメントを読めない: " (pr-str hsegs)))
      (not= ["vel" 999 [0 0 0 15 15 15] [5 2 1 1] 512]
            [(:name (first hsegs)) (:voxel-count (:metadata (first hsegs)))
             (:index-bbox (:metadata (first hsegs))) (:node-count (:metadata (first hsegs)))
             (count (:grid (first hsegs)))])
      (println "PROBE interop-vdb-file FAIL"
               (str "手組みセグメントの field 位置が違う: "
                    (pr-str (select-keys (:metadata (first hsegs))
                                         [:voxel-count :index-bbox :node-count :name-size]))))
      (not= "NanoVDB2" (b->s (subvec seg 0 8)))
      (println "PROBE interop-vdb-file FAIL"
               (str "magic が 8 バイトの ASCII になっていない: " (pr-str (b->s (subvec seg 0 8)))))
      (not= :ok rst)
      (println "PROBE interop-vdb-file FAIL" (str "自分の出力を読み戻せない: " (pr-str rsegs)))
      (not= (+ 16 176 7 100) (count seg))
      (println "PROBE interop-vdb-file FAIL"
               (str "セグメント長が header+metadata+name+grid にならない: " (count seg)))
      (not= grid (:grid (first rsegs)))
      (println "PROBE interop-vdb-file FAIL" "grid buffer がバイト単位で往復しない")
      (not= [-8 -8 -8 7 7 7] (:grid/index-bbox d))
      (println "PROBE interop-vdb-file FAIL"
               (str "負の index bounds が符号付きで読めない: " (pr-str (:grid/index-bbox d))))
      (not= {:major 32 :minor 6 :patch 0} (:grid/version d))
      (println "PROBE interop-vdb-file FAIL"
               (str "version の 11+11+10 bit 詰めが解けない: " (pr-str (:grid/version d))))
      (or (not= :error (first zip)) (not (str/includes? (second zip) "ZIP"))
          (not= :error (first blosc)) (not (str/includes? (second blosc) "BLOSC")))
      (println "PROBE interop-vdb-file FAIL"
               "圧縮セグメントを読み進めている —— 圧縮バイト列が「grid」として返る")
      (not= :error (first bad))
      (println "PROBE interop-vdb-file FAIL" "NanoVDB でないバイト列を受理している")
      (not= :ok vst)
      (println "PROBE interop-vdb-file FAIL" (str ".vdb の容器を読めない: " (pr-str va)))
      (not= 36 (count (str (:archive/uuid va))))
      (println "PROBE interop-vdb-file FAIL"
               (str "uuid が " (count (str (:archive/uuid va))) " バイト —— 36 の ASCII であって"
                    " 16 のバイナリではない。16 で読むと metadata の個数の途中に着地する"))
      (not= [:undecoded "quatd" 32] (get vmeta "future"))
      (println "PROBE interop-vdb-file FAIL"
               (str "知らない metadata 型を正確に読み飛ばせていない: "
                    (pr-str (get vmeta "future"))))
      (not= "blender" (get vmeta "creator"))
      (println "PROBE interop-vdb-file FAIL"
               "知らない型の**後ろ**の entry が壊れている —— バイト数の枠を使っていない")
      (not= [{:name "density" :type "Tree_float_5_4_3" :half-float? true :bytes 800}]
            vgrids)
      (println "PROBE interop-vdb-file FAIL"
               (str "grid descriptor が違う: " (pr-str vgrids)
                    " —— _HalfFloat は型名の一部ではなく flag"))
      (or (:grid/data-decoded? (first (:archive/grids va)))
          (contains? (or (first (:archive/grids va)) {}) :grid/tree))
      (println "PROBE interop-vdb-file FAIL"
               "grid buffer を復号したふりをしている —— 空の木を静かに返す reader は"
               "動いているように見える")
      (not= :error (first (safe #(vf/read-archive (vec (repeat 80 0))))))
      (println "PROBE interop-vdb-file FAIL" ".vdb でないバイト列を受理している")
      :else
      (println "PROBE interop-vdb-file PASS"
               (str "宣言から組んだ 176B metadata を field 位置どおりに解釈 / "
                    "magic は 8 バイト比較 / セグメント長 " (count seg)
                    " = 16+176+7+100 / grid が往復 / 負の index bounds と "
                    "version 32.6.0 / ZIP・BLOSC・非 NanoVDB は拒否 // .vdb は "
                    "uuid 36B ASCII / 知らない metadata 型 quatd を 32 バイトとして"
                    "読み飛ばし後続を保つ / grid "
                    (pr-str vgrids)
                    " / grid buffer は復号しないと明言"
                    "（⚠ どちらも ZIP・BLOSC の node data は未復号）"))))
  (catch :default ex (println "PROBE interop-vdb-file UNMEASURABLE" (.-message ex))))
