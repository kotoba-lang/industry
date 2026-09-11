(ns probe-poly-edit (:require [kami.modeling :as m]))
;; 不変条件: 押し出しは面数と頂点数を増やし、結果が valid-mesh? であり続ける。
;; 「頂点を足すが face を壊す」編集は Blender 相当ではない。
(try
  (let [quad (m/mesh [[0 0 0] [1 0 0] [1 1 0] [0 1 0]] [[0 1 2 3]])
        ex (m/extrude-face quad 0 [0 0 1])
        ins (m/inset-face quad 0 0.2)
        ;; bevel-face は [mesh face-index width depth]
        bev (m/bevel-face quad 0 0.2 0.05)]
    (cond
      (not (m/valid-mesh? ex)) (println "PROBE poly-edit FAIL" "押し出し結果が valid-mesh? でない")
      (not (> (count (:mesh/faces ex)) 1))
      (println "PROBE poly-edit FAIL" (str "押し出しで面が増えない: " (count (:mesh/faces ex))))
      (= (count (:mesh/vertices quad)) (count (:mesh/vertices ex)))
      (println "PROBE poly-edit FAIL" "押し出しで頂点が増えない")
      (not (and (m/valid-mesh? ins) (m/valid-mesh? bev)))
      (println "PROBE poly-edit FAIL" "inset / bevel の結果が valid-mesh? でない")
      :else (println "PROBE poly-edit PASS"
                     (str "extrude V " (count (:mesh/vertices quad)) "→" (count (:mesh/vertices ex))
                          " F 1→" (count (:mesh/faces ex))
                          " inset F=" (count (:mesh/faces ins)) " bevel F=" (count (:mesh/faces bev))))))
  (catch :default ex (println "PROBE poly-edit UNMEASURABLE" (.-message ex))))
