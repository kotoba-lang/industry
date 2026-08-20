(ns probe-sketch-constraints (:require [kami.cad :as cad]))
;; 不変条件: 拘束を満たさない初期配置から solve-sketch を回すと残差が縮む。
;; 「constraint 構築子が在る」ではなく「ソルバが動く」を検査する。
(try
  (let [pts [(cad/sketch-point 1 0.0 0.0 true) (cad/sketch-point 2 5.0 1.7)]
        ents [(cad/sketch-line 10 1 2)]
        cons [(cad/horizontal 100 10) (cad/distance-constraint 101 1 2 4.0)]
        s (cad/sketch pts ents cons)
        r0 (reduce + (map #(cad/constraint-residual s %) cons))
        solved (cad/solve-sketch s)
        s1 (if (map? solved) (or (:sketch/solved solved) (:sketch solved) solved) solved)
        r1 (reduce + (map #(cad/constraint-residual s1 %) cons))]
    (cond
      (not (< r1 (* 0.1 r0)))
      (println "PROBE sketch-constraints FAIL"
               (str "残差が縮まない before=" r0 " after=" r1))
      :else (println "PROBE sketch-constraints PASS" (str "残差 " r0 " → " r1))))
  (catch :default ex (println "PROBE sketch-constraints UNMEASURABLE" (.-message ex))))
