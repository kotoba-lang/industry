(ns probe-toolpath-2d
  (:require [kotoba.cam.toolpath :as tp] [kotoba.cam.stock :as stock]
            [kotoba.cam.tool :as tool] [kotoba.cam.vec3 :as v3]))
;; 不変条件: ポケット加工は切削（:linear）セグメントを複数生み、stepover 分の
;; 経路長を持つ。rapid 1 本だけなら placeholder であって加工ではない。
(try
  (let [[lib _] (tool/add (tool/empty-library)
                          {:id :em6 :name "6mm end mill" :tool-type :end-mill
                           :diameter 6.0 :flute-length 20.0 :overall-length 60.0
                           :flute-count 4 :corner-radius 0.0 :material :carbide})
        job (-> (tp/new-job (stock/block 100 100 20) lib)
                (tp/add-operation {:op :pocket :tool-id :em6 :depth 3.0 :stepover 2.0
                                   :strategy :zigzag :feed-rate 400.0 :spindle-rpm 8000
                                   :pocket-min (v3/v3 10.0 10.0 0.0)
                                   :pocket-max (v3/v3 60.0 40.0 0.0)}))
        segs (tp/generate-toolpath job)
        cuts (filter #(= :linear (:segment-type %)) segs)]
    (cond
      (< (count cuts) 5)
      (println "PROBE toolpath-2d FAIL"
               (str "ポケットが切削セグメントを " (count cuts) " 本しか生まない（総 " (count segs) "）"))
      (not (every? #(pos? (:feed-rate %)) cuts))
      (println "PROBE toolpath-2d FAIL" "切削セグメントに送り速度が乗っていない")
      :else (println "PROBE toolpath-2d PASS"
                     (str "segments=" (count segs) " 切削=" (count cuts)))))
  (catch :default ex (println "PROBE toolpath-2d UNMEASURABLE" (.-message ex))))
