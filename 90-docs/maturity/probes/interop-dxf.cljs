(ns probe-interop-dxf
  (:require [kami.modeling.drawing :as d] [kami.modeling.document :as doc]
            [clojure.string :as str]))
;; 不変条件: DXF は **AutoCAD が読める形**でなければ交換に使えない。
;;   (1) 単位が $INSUNITS で宣言される（無いと受け手が縮尺を推測する）
;;   (2) LINE エンティティが SVG と同じ本数だけ出る（同じ幾何から出ている証拠）
;;   (3) 注記のテキストが失われない
;;   (4) 単位を変えれば $INSUNITS の値が変わる（両方向）
(try
  (let [uid #(doc/stable-uuid "probe-dxf" %)
        geom (d/box-view-geometry {:min [0 0 0] :max [20 10 8]} :front)
        base (d/view (uid "v") :front geom {:origin [10 10] :scale 1})
        fcf (d/annotation (uid "fcf") (:view/id base) :feature-control-frame [30 15]
                          {:text "POS 0.1 | A"})
        mk (fn [units] (-> (d/sheet (uid (str "s" units)) "r1" {:paper :A3 :units units})
                           (d/add-view base) (d/add-annotation fcf)))
        mm (mk :mm) inch (mk :in)
        dxf (d/export-dxf mm)
        dxf-in (d/export-dxf inch)
        svg (d/export-svg mm)
        n-dxf (count (re-seq #"(?m)^LINE$" dxf))
        n-svg (count (re-seq #"<line " svg))]
    (cond
      (not (str/includes? dxf "$INSUNITS"))
      (println "PROBE interop-dxf FAIL" "$INSUNITS が無い —— 受け手が縮尺を推測することになる")
      (= (re-find #"\$INSUNITS\n\s*70\n\s*\d+" dxf) (re-find #"\$INSUNITS\n\s*70\n\s*\d+" dxf-in))
      (println "PROBE interop-dxf FAIL" "単位を mm→in に変えても $INSUNITS が変わらない")
      (zero? n-dxf)
      (println "PROBE interop-dxf FAIL" "LINE エンティティが 1 本も出ない")
      (not= n-dxf n-svg)
      (println "PROBE interop-dxf FAIL"
               (str "DXF の LINE " n-dxf " 本 vs SVG の line " n-svg
                    " 本 —— 同じ幾何から出ていない"))
      (not (str/includes? dxf "POS 0.1 | A"))
      (println "PROBE interop-dxf FAIL" "注記テキストが DXF で失われる")
      :else (println "PROBE interop-dxf PASS"
                     (str "$INSUNITS が単位に追従 / LINE " n-dxf " 本 = SVG と一致 / 注記保持"))))
  (catch :default ex (println "PROBE interop-dxf UNMEASURABLE" (.-message ex))))
