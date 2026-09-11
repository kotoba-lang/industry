(ns probe-feature-extrude (:require [brep.feature :as f]))
;; 不変条件: 押し出しは **スケッチの profile を使う**（単位正方形ではない）。
;; 6×6 のスケッチを高さ 3 で押し出したら bbox が [0,6]×[0,6]×[0,3] になる。
(try
  (let [sk (f/sketch-feature 1 (f/sketch-plane-xy)
             [(f/sketch-line [0 0] [6 0]) (f/sketch-line [6 0] [6 6])
              (f/sketch-line [6 6] [0 6]) (f/sketch-line [0 6] [0 0])])
        [st m] (f/evaluate-mesh (-> (f/feature-tree) (f/add-feature sk)
                                    (f/add-feature (f/extrude-feature 2 1 [0 0 1] 3 :new))))
        bb (fn [ps] [(mapv (fn [i] (apply min (map #(nth % i) ps))) [0 1 2])
                     (mapv (fn [i] (apply max (map #(nth % i) ps))) [0 1 2])])]
    (cond
      (not= :ok st) (println "PROBE feature-extrude FAIL" (str "評価が失敗: " (pr-str m)))
      (not= [[0.0 0.0 0.0] [6.0 6.0 3.0]] (mapv #(mapv double %) (bb (:positions m))))
      (println "PROBE feature-extrude FAIL"
               (str "bbox が " (pr-str (bb (:positions m))) "（[[0 0 0][6 6 3]] を期待）"
                    " —— スケッチ profile が使われていない疑い"))
      (not (every? #(< -1 % (count (:positions m))) (:indices m)))
      (println "PROBE feature-extrude FAIL" "面が存在しない頂点を参照する")
      :else (println "PROBE feature-extrude PASS"
                     (str "bbox " (pr-str (bb (:positions m)))
                          " tris=" (/ (count (:indices m)) 3)))))
  (catch :default ex (println "PROBE feature-extrude UNMEASURABLE" (.-message ex))))
