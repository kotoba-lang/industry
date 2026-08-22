(ns probe-interop-vdb-file (:require [voxel.nvdb :as n] [clojure.string :as str]))
;; `.nvdb` の**容器**。中の grid buffer は不透明で、木は `voxel.vdb` の側にある。
;;
;; 見るのは 4 つ:
;;   (1) 手で組んだセグメント（writer が作ったのではなく、**宣言から byte offset を
;;       書き出した** 176 バイト）を読める —— writer と reader が互いに合意している
;;       だけでは配置を検査したことにならない
;;   (2) magic は**数ではなく 8 バイト**として比較される。0x3242… は 3.6e18 で
;;       2^53 を超えるので、double で組み直すと「近いが等しくない」値になる
;;   (3) 圧縮セグメントは**読み進めずに拒否**する —— 読み進めれば圧縮バイト列が
;;       「grid」として返り、下流ではエラーではなくノイズ入りの grid になる
;;   (4) grid buffer を木に変換する関数が**無い**（容器と木を繋いだふりをしない）
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
        bad (n/read-segments (vec (concat (s->b "NotAVDB!") (repeat 20 0))))]
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
      :else
      (println "PROBE interop-vdb-file PASS"
               (str "宣言から組んだ 176B metadata を field 位置どおりに解釈 / "
                    "magic は 8 バイト比較 / セグメント長 " (count seg)
                    " = 16+176+7+100 / grid が往復 / 負の index bounds と "
                    "version 32.6.0 / ZIP・BLOSC・非 NanoVDB は拒否"))))
  (catch :default ex (println "PROBE interop-vdb-file UNMEASURABLE" (.-message ex))))
