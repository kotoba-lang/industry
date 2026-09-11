(ns probe-feature-tree (:require [brep.feature :as f]))
;; 不変条件: suppress した feature は評価から消え、unsuppress で戻る。
(try
  (let [sk (f/sketch-feature 1 (f/sketch-plane-xy)
                             [(f/sketch-line [0 0] [4 0]) (f/sketch-line [4 0] [4 4])
                              (f/sketch-line [4 4] [0 4]) (f/sketch-line [0 4] [0 0])])
        t (-> (f/feature-tree) (f/add-feature sk)
              (f/add-feature (f/extrude-feature 2 1 [0 0 1] 3 :new)))
        [s1 m1] (f/evaluate-mesh t)
        [s2 m2] (f/evaluate-mesh (f/suppress t 2))
        [s3 m3] (f/evaluate-mesh (f/unsuppress (f/suppress t 2) 2))]
    (cond
      (not= :ok s1) (println "PROBE feature-tree UNMEASURABLE" (str "評価不可: " (pr-str m1)))
      (and (= :ok s2) (= (count (:positions m1)) (count (:positions m2))))
      (println "PROBE feature-tree FAIL" "suppress しても評価結果が変わらない")
      (not= (count (:positions m1)) (count (:positions m3)))
      (println "PROBE feature-tree FAIL" "unsuppress で元に戻らない")
      :else (println "PROBE feature-tree PASS"
                     (str "len=" (f/tree-len t) " suppress→" (if (= :ok s2) (count (:positions m2)) (str "error " (pr-str m2)))
                          " unsuppress→" (count (:positions m3))))))
  (catch :default ex (println "PROBE feature-tree UNMEASURABLE" (.-message ex))))
