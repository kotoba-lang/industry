(ns probe-analytic-surfaces
  (:require [brep.kernel :as k] [brep.tessellate :as tess]))
;; 解析曲面は評価関数を持たない純データなので、幾何になるのは tessellate 経由。
;; 「構築子が在る」ではなく「その曲面が正しい半径の面を生む」を検査する。
(try
  (let [[solid edges verts] (k/make-cylinder 1 [0 0 0] [0 0 1] 2.0 5.0)
        [positions indices] (tess/tessellate-solid solid edges verts)
        pts (distinct positions)   ;; positions は [x y z] の列（flat float 列ではない）
        rad (fn [[x y _]] (Math/sqrt (+ (* x x) (* y y))))
        rim (filter #(> (rad %) 0.1) pts)
        worst (when (seq rim) (apply max (map #(Math/abs (- (rad %) 2.0)) rim)))
        zs (set (map #(nth % 2) rim))]
    (cond
      (empty? indices) (println "PROBE analytic-surfaces FAIL" "円筒が三角形を 1 枚も生まない")
      (< (count rim) 8) (println "PROBE analytic-surfaces FAIL" (str "円周上の点が " (count rim) " 個しかない"))
      (> worst 1e-6) (println "PROBE analytic-surfaces FAIL"
                              (str "円周上の点が半径 2.0 から最大 " worst " ずれる"))
      (< (count zs) 2) (println "PROBE analytic-surfaces FAIL"
                                (str "高さ方向の層が " (count zs) " 枚 —— 側面が生成されていない"))
      :else (println "PROBE analytic-surfaces PASS"
                     (str "tris=" (/ (count indices) 3) " 円周点=" (count rim)
                          " 層=" (count zs) " 半径誤差=" worst))))
  (catch :default ex (println "PROBE analytic-surfaces UNMEASURABLE" (.-message ex))))
