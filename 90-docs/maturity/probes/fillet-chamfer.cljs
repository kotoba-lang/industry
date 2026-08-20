(ns probe-fillet-chamfer (:require [brep.feature :as f]))
;; 不変条件: 同じ押し出しに fillet を足したら、評価結果の幾何が変わる。
;; 変わらなければ fillet-feature は記録するだけのデータ構築子である。
(try
  (let [sk (f/sketch-feature 1 (f/sketch-plane-xy)
                             [(f/sketch-line [0 0] [10 0]) (f/sketch-line [10 0] [10 10])
                              (f/sketch-line [10 10] [0 10]) (f/sketch-line [0 10] [0 0])])
        base (-> (f/feature-tree) (f/add-feature sk)
                 (f/add-feature (f/extrude-feature 2 1 [0 0 1] 5 :new)))
        filleted (f/add-feature base (f/fillet-feature 3 [1 2 3 4] 2.0))
        [sb mb] (f/evaluate-mesh base)
        [sf mf] (f/evaluate-mesh filleted)]
    (cond
      (not= :ok sb) (println "PROBE fillet-chamfer UNMEASURABLE" (str "base の評価が通らない: " (pr-str mb)))
      (not= :ok sf) (println "PROBE fillet-chamfer FAIL"
                             (str "fillet を足すと評価が失敗する: " (pr-str mf)))
      (= (count (:positions mb)) (count (:positions mf)))
      (println "PROBE fillet-chamfer FAIL"
               (str "fillet r=2.0 を足しても幾何が同一（頂点 "
                    (count (:positions mb)) " 個のまま）— fillet-feature は "
                    "{:kind :fillet …} を記録するだけで、評価器が読んでいない"))
      :else (println "PROBE fillet-chamfer PASS"
                     (str "頂点 " (count (:positions mb)) " → " (count (:positions mf))))))
  (catch :default ex (println "PROBE fillet-chamfer UNMEASURABLE" (.-message ex))))
