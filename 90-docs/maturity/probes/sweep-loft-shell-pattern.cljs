(ns probe-sweep-loft-shell-pattern (:require [brep.feature :as f]))
;; 4 つの feature 構築子が評価器に届いているか。1 つでも「評価対象外」なら FAIL。
(try
  (let [sk (f/sketch-feature 1 (f/sketch-plane-xy)
                             [(f/sketch-line [0 0] [4 0]) (f/sketch-line [4 0] [4 4])
                              (f/sketch-line [4 4] [0 4]) (f/sketch-line [0 4] [0 0])])
        base (-> (f/feature-tree) (f/add-feature sk)
                 (f/add-feature (f/extrude-feature 2 1 [0 0 1] 3 :new)))
        [_ mb] (f/evaluate-mesh base)
        n0 (count (:positions mb))
        try1 (fn [label feat]
               (let [[s m] (f/evaluate-mesh (f/add-feature base feat))]
                 (cond (not= :ok s) [label :error (str (pr-str m))]
                       (= n0 (count (:positions m))) [label :ignored "頂点数が不変"]
                       :else [label :ok ""])))
        rs [(try1 "sweep" (f/sweep-feature 3 1 [[0 0 0] [0 0 5]] :add))
            (try1 "loft" (f/loft-feature 4 [1 1] :add))
            (try1 "shell" (f/shell-feature 5 [1] 0.5))
            (try1 "pattern" (f/pattern-feature 6 2 :linear {:count 3 :spacing [5 0 0]}))]
        bad (remove #(= :ok (second %)) rs)]
    (if (empty? bad)
      (println "PROBE sweep-loft-shell-pattern PASS" "4 feature とも幾何を変えた")
      (println "PROBE sweep-loft-shell-pattern FAIL"
               (str "評価器に届いていない: "
                    (clojure.string/join "; " (map (fn [[l k d]] (str l "=" (name k) " " d)) bad))))))
  (catch :default ex (println "PROBE sweep-loft-shell-pattern UNMEASURABLE" (.-message ex))))
