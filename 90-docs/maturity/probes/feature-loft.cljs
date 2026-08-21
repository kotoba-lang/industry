(ns probe-feature-loft (:require [brep.feature :as f] [clojure.string :as str]))
;; 不変条件: (1) 2 つの profile 間に実体ができ、両方の平面に跨る
;;          (2) 面が存在する頂点だけを参照する
;;          (3) **頂点数の違う profile は拒否される**（黙って resample しない）——
;;              対応規則は表面形状を変えるので、既定ではなく決定であるべき。
(try
  (let [sq (fn [id plane s]
             (f/sketch-feature id plane
               [(f/sketch-line [0 0] [s 0]) (f/sketch-line [s 0] [s s])
                (f/sketch-line [s s] [0 s]) (f/sketch-line [0 s] [0 0])]))
        [st m] (f/evaluate-mesh
                (-> (f/feature-tree)
                    (f/add-feature (sq 1 (f/sketch-plane-xy) 6))
                    (f/add-feature (sq 2 (f/sketch-plane-custom [1.0 1.0 6.0] [0 0 1]) 4))
                    (f/add-feature (f/loft-feature 3 [1 2] :new))))
        zs (when (= :ok st) (map #(nth % 2) (:positions m)))
        tri (f/sketch-feature 4 (f/sketch-plane-custom [0.0 0.0 5.0] [0 0 1])
              [(f/sketch-line [0 0] [4 0]) (f/sketch-line [4 0] [2 4]) (f/sketch-line [2 4] [0 0])])
        [st2 msg2] (f/evaluate-mesh
                    (-> (f/feature-tree)
                        (f/add-feature (sq 1 (f/sketch-plane-xy) 6))
                        (f/add-feature tri)
                        (f/add-feature (f/loft-feature 5 [1 4] :new))))]
    (cond
      (not= :ok st) (println "PROBE feature-loft FAIL" (str "評価が失敗: " (pr-str m)))
      (not (and (= 0.0 (double (apply min zs))) (= 6.0 (double (apply max zs)))))
      (println "PROBE feature-loft FAIL"
               (str "z 範囲が " [(apply min zs) (apply max zs)] "（0〜6 を期待）"))
      (not (every? #(< -1 % (count (:positions m))) (:indices m)))
      (println "PROBE feature-loft FAIL" "面が存在しない頂点を参照する")
      (not (and (= :error st2) (str/includes? msg2 "resampling is not implemented")))
      (println "PROBE feature-loft FAIL"
               (str "頂点数の違う profile を拒否しない: " (pr-str [st2 msg2])))
      :else (println "PROBE feature-loft PASS"
                     (str "z 0→6 tris=" (/ (count (:indices m)) 3)
                          " / 頂点数不一致は拒否"))))
  (catch :default ex (println "PROBE feature-loft UNMEASURABLE" (.-message ex))))
