(ns probe-vdb-tree (:require [voxel.vdb :as v] [clojure.string :as str]))
;; VDB 木は**数で測れる**: 活性ボクセル数・境界箱・トポロジ演算・tile への畳み込み。
;; 静かに壊れる 2 つを特に見る:
;;   (1) **tile への書き込み** —— 512 ボクセルを代表するノードに書くと、展開せずに
;;       書けば 512 個全部が変わる。書いた座標では正しく見える
;;   (2) **中身を見ない prune** —— 「満杯だから畳む」は、2 値を持つ葉を
;;       512 中 511 個で誤答する非常にコンパクトなグリッドにする
;; そして **これは容器ではない**: `.vdb` / `.nvdb` を読み書きしないことを、
;; from-edn が名指しで言うことまで検査する。
(try
  (let [g (-> (v/grid 0) (v/fill [0 0 0] [7 7 7] 1) (v/set-voxel [100 5 5] 2))
        p (v/prune g)
        neg (v/fill (v/grid 0) [-9 -9 -9] [-2 -2 -2] 7)
        straddle (-> (v/grid 0) (v/set-voxel [-1 0 0] 1) (v/set-voxel [1 0 0] 2))
        a (v/fill (v/grid 0) [0 0 0] [3 3 3] 1)
        b (v/fill (v/grid 0) [2 2 2] [5 5 5] 2)
        mixed (v/prune (-> (v/grid 0) (v/fill [0 0 0] [7 7 7] 1) (v/set-voxel [3 3 3] 9)))
        written (v/set-voxel (v/prune (v/fill (v/grid 0) [0 0 0] [7 7 7] 1)) [3 3 3] 9)
        round (v/from-edn (v/to-edn neg))
        foreign-msg (try (v/from-edn {:vdb/format :openvdb/v9 :vdb/voxels []}) nil
                         (catch :default e (ex-message e)))]
    (cond
      (not= [8 128 4096] [v/leaf-span v/internal-span v/upper-span])
      (println "PROBE vdb-tree FAIL"
               (str "分岐が Tree4<T,5,4,3> でない: " (pr-str [v/leaf-span v/internal-span v/upper-span])))
      (or (not= 513 (v/active-count g)) (not= [[0 0 0] [100 7 7]] (v/bounds g)))
      (println "PROBE vdb-tree FAIL"
               (str "活性数/境界箱が合わない: " (v/active-count g) " " (pr-str (v/bounds g))))
      (or (not= 512 (v/active-count neg))
          (not= [[-9 -9 -9] [-2 -2 -2]] (v/bounds neg))
          (not= 2 (v/active-count straddle))
          (not= 1 (v/get-voxel straddle [-1 0 0]))
          (not= 2 (v/get-voxel straddle [1 0 0])))
      (println "PROBE vdb-tree FAIL"
               (str "負座標が壊れている（quot 切り捨てで x=-1 と x=+1 が同じノードへ）: "
                    (v/active-count neg) " / " (pr-str (v/bounds neg))
                    " / 原点跨ぎ " (v/active-count straddle)))
      (or (not= 1 (v/tile-count p)) (not= 1 (v/leaf-count p)) (not= 513 (v/active-count p))
          (some (fn [c] (not= (v/get-voxel g c) (v/get-voxel p c)))
                [[0 0 0] [3 3 3] [7 7 7] [100 5 5] [8 0 0]]))
      (println "PROBE vdb-tree FAIL"
               (str "prune が畳めていないか答えを変えている: tiles " (v/tile-count p)
                    " leaves " (v/leaf-count p) " active " (v/active-count p)))
      (pos? (v/tile-count mixed))
      (println "PROBE vdb-tree FAIL"
               "2 値を持つ満杯の葉を tile に畳んでいる —— 512 中 511 個で誤答するグリッド")
      (or (not= 9 (v/get-voxel written [3 3 3]))
          (not= 512 (v/active-count written))
          (some (fn [c] (not= 1 (v/get-voxel written c))) [[0 0 0] [1 1 1] [7 7 7]]))
      (println "PROBE vdb-tree FAIL"
               (str "tile へ書き込むと他の 511 ボクセルまで変わる: [0 0 0]="
                    (v/get-voxel written [0 0 0]) " active=" (v/active-count written)))
      (not= [120 8 56] [(v/active-count (v/topology-op :union a b))
                        (v/active-count (v/topology-op :intersection a b))
                        (v/active-count (v/topology-op :difference a b))])
      (println "PROBE vdb-tree FAIL"
               (str "トポロジ演算の個数が合わない: "
                    (pr-str [(v/active-count (v/topology-op :union a b))
                             (v/active-count (v/topology-op :intersection a b))
                             (v/active-count (v/topology-op :difference a b))])
                    "（期待 [120 8 56]）"))
      (not= (v/active-count neg) (v/active-count round))
      (println "PROBE vdb-tree FAIL" "EDN が往復しない")
      (or (nil? foreign-msg) (not (str/includes? foreign-msg "neither read nor written")))
      (println "PROBE vdb-tree FAIL"
               (str "他形式を渡されても「.vdb は読み書きしない」と言わない: " (pr-str foreign-msg)))
      :else
      (println "PROBE vdb-tree PASS"
               (str "Tree4<T,5,4,3> の span 8/128/4096 / 活性 513・境界箱一致 / "
                    "負座標と原点跨ぎ / prune が 2 葉→1 tile+1 葉で答え不変 / "
                    "2 値の満杯葉は畳まない / tile 書き込みが隣を壊さない / "
                    "union 120・∩ 8・− 56 / EDN 往復 / 他形式は名指しで拒否"))))
  (catch :default ex (println "PROBE vdb-tree UNMEASURABLE" (.-message ex))))
