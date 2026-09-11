(ns probe-step-ap203 (:require [brep.kernel :as k] [brep.step :as step]))
;; 不変条件: write-step → read-step の往復で topology と座標が保存される。
;; 「read-step / write-step という名前が在る」では何も言えない。
(try
  (let [[solid edges verts] (k/make-box 1 [0 0 0] [1 2 3])
        text (step/write-step solid edges verts)
        [s2 e2 v2] (step/read-step text)
        pt (fn [vs] (sort (map :point vs)))
        near (fn [a b] (< (reduce + (map (fn [p q] (reduce + (map #(Math/abs (- %1 %2)) p q))) a b)) 1e-6))]
    (cond
      (not (re-find #"ISO-10303-21" text))
      (println "PROBE step-ap203 FAIL" "出力が ISO-10303-21 ヘッダを持たない")
      (not= [(k/face-count solid) (k/edge-count solid)] [(k/face-count s2) (k/edge-count s2)])
      (println "PROBE step-ap203 FAIL"
               (str "往復で topology が変わる F/E " [(k/face-count solid) (k/edge-count solid)]
                    " → " [(k/face-count s2) (k/edge-count s2)]))
      (not (near (pt verts) (pt v2)))
      (println "PROBE step-ap203 FAIL" "往復で頂点座標が一致しない")
      :else (println "PROBE step-ap203 PASS"
                     (str "round-trip ok " (count text) " bytes F=" (k/face-count s2)
                          " E=" (k/edge-count s2) " V=" (count v2)))))
  (catch :default ex (println "PROBE step-ap203 UNMEASURABLE" (.-message ex))))
