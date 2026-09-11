(ns probe-ifc-spf (:require [ifc.core :as ifc] [clojure.string :as str]))
;; 不変条件: (1) 出力が **native な IFC entity** を持つ（EDN を包んだだけではない）
;;          (2) 押し出し形状が SPF に出る (3) 読み戻して kind が保存される
(try
  (let [doc (ifc/exchange-document
             {:project {:global-id "p1" :name "Probe Project"}
              :elements [{:id 10 :global-id "cw-10" :kind :curtain :name "South Curtain"
                          :type-object {:global-id "cwt-1" :name "Unitized 1500"}
                          :placement {:location [0 0 0]}
                          :geometry {:kind :extruded-area-solid
                                     :profile {:kind :rectangle :x-dim 6.0 :y-dim 0.15}
                                     :direction [0 0 1] :depth 3.0}}]})
        text (ifc/write-spf doc)
        back (ifc/read-document text)]
    (cond
      (not (str/starts-with? text "ISO-10303-21;"))
      (println "PROBE ifc-spf FAIL" "SPF ヘッダが無い")
      (not (str/includes? text "IFCCURTAINWALL"))
      (println "PROBE ifc-spf FAIL" "native な IFC entity が出ない（EDN を包んだだけ）")
      (not (str/includes? text "IFCEXTRUDEDAREASOLID"))
      (println "PROBE ifc-spf FAIL" "形状が IFC の幾何 entity として出ない")
      (not= :curtain (get-in back [:ifc/elements 0 :kind]))
      (println "PROBE ifc-spf FAIL" (str "往復で kind が保存されない: "
                                         (pr-str (get-in back [:ifc/elements 0 :kind]))))
      :else (println "PROBE ifc-spf PASS"
                     (str "SPF " (count text) " bytes / native entity + extruded solid + round-trip ok"))))
  (catch :default ex (println "PROBE ifc-spf UNMEASURABLE" (.-message ex))))
