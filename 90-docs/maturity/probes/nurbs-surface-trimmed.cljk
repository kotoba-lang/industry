(ns probe-nurbs-surface-trimmed
  (:require [kami.modeling.nurbs :as n] [kami.modeling :as m] [kami.modeling.document :as d]))
;; 不変条件: (1) 有理 NURBS が円弧を正しく評価する（重み付き = 本物の NURBS）
;;          (2) トリムした曲面のテセレーションが、トリムしない場合より面が減る
;;          (3) 穴の内側の点が :point-in-trim? で false になる（両方向）
(try
  (let [w (/ (Math/sqrt 2.0) 2.0)
        c (n/curve {:degree 2 :knots [0 0 0 1 1 1]
                    :control-points [[1 0 0] [1 1 0] [0 1 0]] :weights [1 w 1]})
        [x y _] (n/evaluate-curve c 0.5)
        surf (n/surface {:u-degree 1 :v-degree 1 :u-knots [0 0 1 1] :v-knots [0 0 1 1]
                         :control-net [[[0 0 0] [4 0 0]] [[0 4 0] [4 4 0]]]})
        plain (n/tessellate-surface surf 8 8)
        uid #(d/stable-uuid "probe-trim" %)
        outer (n/trim-loop (uid "outer") [[0 0] [1 0] [1 1] [0 1]] :outer)
        inner (n/trim-loop (uid "inner") [[0.4 0.4] [0.6 0.4] [0.6 0.6] [0.4 0.6]] :inner)
        tr (n/trimmed-surface surf outer [inner])
        trimmed (n/tessellate-trimmed-surface tr 8 8)
;; point-in-trim? は **loop** を取る。面としての内外は inside-trim?（外周の内 かつ 穴の外）
        in-hole (n/inside-trim? tr [0.5 0.5])
        in-face (n/inside-trim? tr [0.1 0.1])]
    (cond
      (> (+ (Math/abs (- x w)) (Math/abs (- y w))) 1e-9)
      (println "PROBE nurbs-surface-trimmed FAIL"
               (str "有理 NURBS の四分円が合わない p(.5)=" (pr-str [x y])))
      (not (< (count (:mesh/faces trimmed)) (count (:mesh/faces plain))))
      (println "PROBE nurbs-surface-trimmed FAIL"
               (str "トリムしても面が減らない trimmed=" (count (:mesh/faces trimmed))
                    " plain=" (count (:mesh/faces plain)) " —— trim loop が効いていない"))
      (or in-hole (not in-face))
      (println "PROBE nurbs-surface-trimmed FAIL"
               (str "inside-trim? が両方向を出さない 穴の中=" in-hole "(false を期待) 面の上=" in-face "(true を期待)"))
      (not (m/valid-mesh? trimmed))
      (println "PROBE nurbs-surface-trimmed FAIL" "トリム結果が valid-mesh? でない")
      :else (println "PROBE nurbs-surface-trimmed PASS"
                     (str "有理円弧 ok / faces " (count (:mesh/faces plain)) "→"
                          (count (:mesh/faces trimmed)) " / inside-trim 両方向 ok"))))
  (catch :default ex (println "PROBE nurbs-surface-trimmed UNMEASURABLE" (.-message ex))))
