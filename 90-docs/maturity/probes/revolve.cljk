(ns probe-revolve (:require [brep.feature :as f] [brep.kernel :as k]))
;; 不変条件: 軸平行の線分を 2π 回すと、その半径の円筒 solid になる。
;; `:axis` は 3 要素ベクタ（`k/v-normalize` に渡る）。[:ok nil] を成功と読まない。
(try
  (let [sk (f/sketch-feature 1 (f/sketch-plane-xz) [(f/sketch-line [2 0] [2 5])])
        t (-> (f/feature-tree) (f/add-feature sk)
              (f/add-feature (f/revolve-feature 2 1 [0 0 1] (* 2 Math/PI) :new)))
        [s r] (f/evaluate t)
        [solid edges verts] (when (vector? r) r)
        faces (when (map? solid) (k/face-count solid))
        pts (when verts (map :point verts))
        finite? (fn [p] (every? #(and (number? %) (== % %)) p))]
    (cond
      (not= :ok s) (println "PROBE revolve FAIL" (str "評価が失敗: " (subs (pr-str r) 0 (min 220 (count (pr-str r))))))
      (nil? faces) (println "PROBE revolve FAIL" ":ok だが BREP triple が返らない")
      (zero? faces) (println "PROBE revolve FAIL" ":ok だが face が 0 枚")
      (not (every? finite? pts))
      (println "PROBE revolve FAIL"
               (str "face=" faces " だが頂点座標が非有限（NaN/Inf）—— topology だけ在って幾何が無い"))
      :else (println "PROBE revolve PASS" (str "faces=" faces " verts=" (count verts)))))
  (catch :default ex (println "PROBE revolve UNMEASURABLE" (.-message ex))))
