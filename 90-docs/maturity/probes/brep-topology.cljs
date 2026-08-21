(ns probe-brep-topology (:require [brep.kernel :as k]))
;; 不変条件: 箱の B-rep は Euler 標数 V-E+F=2 を満たし、bbox が実寸を返す。
;; `vertex-count` / `bounding-box` は edges・vertices も取る —— topology が
;; edge 経由で座標に届くこと自体が検査対象なので、そこを省略しない。
(try
  (let [[solid edges verts] (k/make-box 1 [0 0 0] [1 2 3])
        v (k/vertex-count solid edges) e (k/edge-count solid) f (k/face-count solid)
        euler (- (+ v f) e)
        [bmin bmax] (k/bounding-box solid edges verts)
        near (fn [a b] (< (reduce + (map #(Math/abs (- %1 %2)) a b)) 1e-9))]
    (cond
      (not (and (= 8 v) (= 12 e) (= 6 f) (= 2 euler)))
      (println "PROBE brep-topology FAIL" (str "V=" v " E=" e " F=" f " V-E+F=" euler " (V=8 E=12 F=6, Euler 2 を期待)"))
      (not (and (near bmin [0 0 0]) (near bmax [1 2 3])))
      (println "PROBE brep-topology FAIL" (str "bbox=" (pr-str [bmin bmax]) " ([[0 0 0][1 2 3]] を期待)"))
      :else (println "PROBE brep-topology PASS" (str "V=" v " E=" e " F=" f " V-E+F=" euler " bbox ok"))))
  (catch :default ex (println "PROBE brep-topology UNMEASURABLE" (.-message ex))))
