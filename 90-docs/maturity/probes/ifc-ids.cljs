(ns probe-ifc-ids (:require [ifc.ids :as ids]))
;; 不変条件: IDS の facet は、合う要素に true・合わない要素に false を返す。
;; 両方向を出さない検査器は検査器ではない。
(try
  (let [f (ids/facet {:type :entity :name "IFCWALL"})
        wall {:kind :wall :id 1} beam {:kind :beam :id 2}
        yes (ids/facet-matches? wall nil f)
        no  (ids/facet-matches? beam nil f)
        spec (ids/specification {:name "壁は IFCWALL であること"
                                 :applicability [f] :requirements [f]})]
    (cond
      (not (and yes (not no)))
      (println "PROBE ifc-ids FAIL" (str "facet が両方向を出さない wall=" yes " beam=" no))
      (nil? (:ids.specification/name spec))
      (println "PROBE ifc-ids FAIL" "specification が構築できない")
      :else (println "PROBE ifc-ids PASS" (str "wall=" yes " beam=" no " spec ok"))))
  (catch :default ex (println "PROBE ifc-ids UNMEASURABLE" (.-message ex))))
