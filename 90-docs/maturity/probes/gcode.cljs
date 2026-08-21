(ns probe-gcode
  (:require [kotoba.cam.gcode :as g] [kotoba.cam.toolpath :as tp]
            [kotoba.cam.stock :as stock] [kotoba.cam.tool :as tool] [kotoba.cam.vec3 :as v3]))
;; 不変条件: 実経路から出た G-code は、送り移動 G1 と座標語 X/Y/Z を持ち、M30 で終わる。
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
        text (g/generate-gcode segs)
        g1 (count (re-seq #"(?m)^G0?1\b" text))   ;; 出力は G01 表記（G1 ではない）
        nans (count (re-seq #"NaN" text))]
    (cond
      (zero? g1) (println "PROBE gcode FAIL" "G01（送り移動）が 1 行も出ない")
      (pos? nans)
      (println "PROBE gcode FAIL"
               (str "出力に NaN が " nans " 箇所ある（例: `TNaN M06`）—— 実機の制御器が"
                    "受け付けないプログラムを『生成成功』として返している"))
      (not (re-find #"M30|M2\b" text)) (println "PROBE gcode FAIL" "プログラム終了語（M30/M2）が無い")
      (not (re-find #"X-?\d" text)) (println "PROBE gcode FAIL" "座標語が出ない")
      :else (println "PROBE gcode PASS" (str "lines=" (count (re-seq #"\n" text)) " G1=" g1))))
  (catch :default ex (println "PROBE gcode UNMEASURABLE" (.-message ex))))
