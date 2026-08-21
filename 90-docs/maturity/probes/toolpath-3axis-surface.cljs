(ns probe-toolpath-3axis-surface
  (:require [kotoba.cam.toolpath :as tp] [kotoba.cam.stock :as stock]
            [kotoba.cam.tool :as tool]))
;; `surface-strategies` は #{:raster :spiral :waterline :pencil} を宣言している。
;; 不変条件: 宣言した 4 つの戦略は、互いに違う切削経路を生む。
;; 全部同じ（= placeholder の rapid 1 本）なら、その集合は嘘である。
(try
  (let [[lib _] (tool/add (tool/empty-library)
                          {:id :bn3 :name "3mm ball nose" :tool-type :ball-nose
                           :diameter 3.0 :flute-length 15.0 :overall-length 50.0
                           :flute-count 2 :corner-radius 1.5 :material :carbide})
        run (fn [strategy]
              (tp/generate-toolpath
               (-> (tp/new-job (stock/block 80 80 30) lib)
                   (tp/add-operation {:op :surface-3d :tool-id :bn3 :stepover 0.5
                                      :strategy strategy :feed-rate 1200.0 :spindle-rpm 12000}))))
        paths (into {} (map (juxt identity run) (sort tp/surface-strategies)))
        cuts (into {} (map (fn [[k v]] [k (count (filter #(= :linear (:segment-type %)) v))]) paths))
        distinct-paths (count (distinct (vals paths)))]
    (cond
      (every? zero? (vals cuts))
      (println "PROBE toolpath-3axis-surface FAIL"
               (str "宣言された 4 戦略 " (pr-str (sort tp/surface-strategies))
                    " が 1 本も切削セグメントを生まない（segments/戦略="
                    (pr-str (into {} (map (fn [[k v]] [k (count v)]) paths)))
                    "）—— gen-placeholder の rapid のみ"))
      (= 1 distinct-paths)
      (println "PROBE toolpath-3axis-surface FAIL" "4 戦略が全て同一の経路を返す")
      :else (println "PROBE toolpath-3axis-surface PASS" (str "切削本数/戦略=" (pr-str cuts)))))
  (catch :default ex (println "PROBE toolpath-3axis-surface UNMEASURABLE" (.-message ex))))
