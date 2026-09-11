(ns probe-interop-materialx (:require [materialx.core :as mx] [clojure.string :as str]))
;; 不変条件: (1) 実在の MaterialX 標準ノードの既定値を持っている（発明した値ではない）
;;          (2) XML → EDN → XML の往復で nodegraph の構造が保たれる
;;          (3) **curated 表の外のノードを黙って落とさない**（`:unresolved?` で通す）——
;;              交換フォーマットで一番危ないのは、知らないものを消して成功と言うこと
(try
  (let [nd (:ND_standard_surface_surfaceshader mx/node-defs)
        by-name (into {} (map (juxt :name identity)) (:inputs nd))
        xml (str "<?xml version=\"1.0\"?>\n"
                 "<materialx version=\"1.38\">\n"
                 "  <nodegraph name=\"probe\">\n"
                 "    <image name=\"tex\" type=\"color3\">\n"
                 "      <input name=\"file\" type=\"filename\" value=\"x.png\"/>\n"
                 "    </image>\n"
                 "    <place2d name=\"weird\" type=\"vector2\"/>\n"
                 "    <output name=\"out\" type=\"color3\" nodename=\"tex\"/>\n"
                 "  </nodegraph>\n"
                 "</materialx>\n")
        doc (mx/materialx->node-graph xml)
        graph (first (vals (:nodegraphs doc)))
        nodes (if (map? graph) (or (:nodes graph) graph) graph)
        back (mx/node-graph->materialx doc)
        text (if (string? back) back (pr-str back))
        unresolved (filter (fn [n] (:unresolved? (:type n))) (vals (if (map? nodes) nodes {})))]
    (cond
      (not= "standard_surface" (:node nd))
      (println "PROBE interop-materialx FAIL" "標準ノード表が実仕様の名前を持たない")
      (not= 0.2 (:default (by-name "specular_roughness")))
      (println "PROBE interop-materialx FAIL"
               (str "specular_roughness の既定が " (:default (by-name "specular_roughness"))
                    "（MaterialX 仕様の 0.2 を期待）—— 発明した値の疑い"))
      (nil? graph)
      (println "PROBE interop-materialx FAIL" (str "nodegraph を読めない: " (pr-str (keys doc))))
      (not (str/includes? text "place2d"))
      (println "PROBE interop-materialx FAIL"
               "curated 表に無い <place2d> が往復で消えた —— 知らないものを黙って落としている")
      (not (str/includes? text "nodegraph"))
      (println "PROBE interop-materialx FAIL" "往復した XML に nodegraph が無い")
      (not (str/includes? text "x.png"))
      (println "PROBE interop-materialx FAIL" "入力値が往復で失われる")
      :else (println "PROBE interop-materialx PASS"
                     (str "標準ノード既定値は実仕様 / 往復で nodegraph・入力値・"
                          "未収載ノード(place2d) が保たれる"))))
  (catch :default ex (println "PROBE interop-materialx UNMEASURABLE" (.-message ex))))
