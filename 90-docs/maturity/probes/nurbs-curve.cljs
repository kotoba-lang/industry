(ns probe-nurbs-curve (:require [brep.kernel :as k]))
;; clamped B-spline は端点を補間する。評価器（de Boor）が実在するかはそこで分かる。
(try
  (let [c (k/bspline-curve 2 [[0 0 0] [1 2 0] [3 0 0]] [0 0 0 1 1 1])
        p0 (k/curve-evaluate c 0.0)
        p1 (k/curve-evaluate c 1.0)
        pm (k/curve-evaluate c 0.5)
        near (fn [a b] (< (reduce + (map #(Math/abs (- %1 %2)) a b)) 1e-6))]
    (if (and (near p0 [0 0 0]) (near p1 [3 0 0]) (not (near pm [0 0 0])) (> (second pm) 0.4))
      (println "PROBE nurbs-curve PASS" (str "p(0)=" (pr-str p0) " p(.5)=" (pr-str pm) " p(1)=" (pr-str p1)))
      (println "PROBE nurbs-curve FAIL"
               (str "de Boor 評価が期待どおりでない p(0)=" (pr-str p0) " p(.5)=" (pr-str pm) " p(1)=" (pr-str p1)))))
  (catch :default ex (println "PROBE nurbs-curve UNMEASURABLE" (.-message ex))))
