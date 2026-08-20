(ns probe-boolean-solid (:require [brep.feature :as f]))
;; 不変条件: 大きい箱から小さい箱を :cut すると、頂点数が cut 無しと変わる。
(try
  (let [outer (f/sketch-feature 1 (f/sketch-plane-xy)
                                [(f/sketch-line [0 0] [10 0]) (f/sketch-line [10 0] [10 10])
                                 (f/sketch-line [10 10] [0 10]) (f/sketch-line [0 10] [0 0])])
        inner (f/sketch-feature 2 (f/sketch-plane-xy)
                                [(f/sketch-line [2 2] [5 2]) (f/sketch-line [5 2] [5 5])
                                 (f/sketch-line [5 5] [2 5]) (f/sketch-line [2 5] [2 2])])
        base (-> (f/feature-tree) (f/add-feature outer)
                 (f/add-feature (f/extrude-feature 3 1 [0 0 1] 5 :new)))
        cut  (-> base (f/add-feature inner)
                 (f/add-feature (f/extrude-feature 4 2 [0 0 1] 9 :cut)))
        [sb mb] (f/evaluate-mesh base)
        [sc mc] (f/evaluate-mesh cut)]
    (cond
      (not= :ok sb) (println "PROBE boolean-solid UNMEASURABLE" (str "base 評価不可: " (pr-str mb)))
      (not= :ok sc) (println "PROBE boolean-solid FAIL" (str ":cut の評価が失敗: " (pr-str mc)))
      (= (count (:indices mb)) (count (:indices mc)))
      (println "PROBE boolean-solid FAIL"
               (str ":cut しても三角形数が同じ（" (count (:indices mb)) "）— boolean が効いていない"))
      :else (println "PROBE boolean-solid PASS"
                     (str "indices " (count (:indices mb)) " → " (count (:indices mc))))))
  (catch :default ex (println "PROBE boolean-solid UNMEASURABLE" (.-message ex))))
