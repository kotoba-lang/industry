(ns probe-post-processor
  (:require [kotoba.cam.gcode :as g] [kotoba.cam.toolpath :as tp]
            [kotoba.cam.stock :as stock] [kotoba.cam.tool :as tool] [kotoba.cam.vec3 :as v3]))
;; `post-processors` は制御器を 7 つ名指しする。不変条件は「7 通りの方言が出ること」
;; **ではない** —— それは実装の要求であって真実の要求ではない。真実の要求はこう:
;;
;;   名指しした各 post は、**別々の出力を返す** か、**明示的に拒否される** かのどちらか。
;;
;; 同じ Fanuc 出力を別の機械の名前で返すのが最悪で、それは「動くように見えて
;; 間違った制御器に届くプログラム」になる。エラーより悪い —— 機械まで行く。
(try
  (let [[lib _] (tool/add (tool/empty-library)
                          {:id :em6 :name "6mm" :tool-type :end-mill :diameter 6.0
                           :flute-length 20.0 :overall-length 60.0 :flute-count 4
                           :corner-radius 0.0 :material :carbide})
        segs (tp/generate-toolpath
              (-> (tp/new-job (stock/block 100 100 20) lib)
                  (tp/add-operation {:op :pocket :tool-id :em6 :depth 3.0 :stepover 2.0
                                     :strategy :zigzag :feed-rate 400.0 :spindle-rpm 8000
                                     :pocket-min (v3/v3 10.0 10.0 0.0)
                                     :pocket-max (v3/v3 60.0 40.0 0.0)})))
        results (into {} (map (fn [p]
                                [p (try {:out (g/generate-gcode segs (assoc g/default-config :post-processor p))}
                                        (catch :default e {:refused (.-message e)}))])
                              (sort g/post-processors)))
        emitted (into {} (filter (comp :out val) results))
        refused (into {} (filter (comp :refused val) results))
        distinct-out (count (distinct (map (comp :out val) emitted)))]
    (cond
      (empty? emitted)
      (println "PROBE post-processor FAIL" "どの post も出力を返さない（全部拒否）")
      (not= distinct-out (count emitted))
      (println "PROBE post-processor FAIL"
               (str "出力を返す post " (count emitted) " 種のうち、異なる出力は " distinct-out
                    " 通りしかない —— 同じ G-code を別の機械の名前で返している: "
                    (pr-str (keys emitted))))
      :else
      (println "PROBE post-processor PASS"
               (str "実装 " (count emitted) " 種 " (pr-str (keys emitted))
                    " は互いに異なる出力、未実装 " (count refused) " 種 " (pr-str (keys refused))
                    " は明示的に拒否される"))))
  (catch :default ex (println "PROBE post-processor UNMEASURABLE" (.-message ex))))
