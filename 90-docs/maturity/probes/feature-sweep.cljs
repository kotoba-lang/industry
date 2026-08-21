(ns probe-feature-sweep (:require [brep.feature :as f] [clojure.string :as str]))
;; 不変条件は **1 つの数字**に集約する。2 幅の断面を +z に 10、続いて +x に 8 掃引した
;; とき、frame を運んでいれば外側の角が z=11（10 + 半幅）に来る。平行移動だけの
;; sweep は zmax が 10 のままで、角で断面がねじれて実体がくびれる。
;; 「掃引できた」ではなく「掃引として正しい」をここで分ける。
;; さらに、決められないものを拒否することも検査する（斜めの profile / 閉じた経路）。
(try
  (let [prof (fn [id plane]
               (f/sketch-feature id plane
                 [(f/sketch-line [-1 -1] [1 -1]) (f/sketch-line [1 -1] [1 1])
                  (f/sketch-line [1 1] [-1 1]) (f/sketch-line [-1 1] [-1 -1])]))
        run (fn [profile-plane path-ents]
              (f/evaluate-mesh
               (-> (f/feature-tree)
                   (f/add-feature (prof 1 profile-plane))
                   (f/add-feature (f/sketch-feature 2 (f/sketch-plane-xz) path-ents))
                   (f/add-feature (f/sweep-feature 3 1 2 :new)))))
        bb (fn [m] [(mapv (fn [i] (apply min (map #(nth % i) (:positions m)))) [0 1 2])
                    (mapv (fn [i] (apply max (map #(nth % i) (:positions m)))) [0 1 2])])
        [s1 straight] (run (f/sketch-plane-xy) [(f/sketch-line [0 0] [0 10])])
        [s2 corner]   (run (f/sketch-plane-xy) [(f/sketch-line [0 0] [0 10])
                                                (f/sketch-line [0 10] [8 10])])
        [s3 obl]      (run (f/sketch-plane-xz) [(f/sketch-line [0 0] [0 10])])
        [s4 closed]   (f/evaluate-mesh
                       (-> (f/feature-tree)
                           (f/add-feature (prof 1 (f/sketch-plane-xy)))
                           (f/add-feature (prof 2 (f/sketch-plane-xz)))
                           (f/add-feature (f/sweep-feature 3 1 2 :new))))]
    (cond
      (not= :ok s1) (println "PROBE feature-sweep FAIL" (str "直線経路が失敗: " (pr-str straight)))
      (not= [[-1.0 -1.0 0.0] [1.0 1.0 10.0]] (mapv #(mapv double %) (bb straight)))
      (println "PROBE feature-sweep FAIL"
               (str "直線 sweep が押し出しと一致しない bbox=" (pr-str (bb straight))))
      (not= :ok s2) (println "PROBE feature-sweep FAIL" (str "L 字経路が失敗: " (pr-str corner)))
      (not= 11.0 (double (nth (second (bb corner)) 2)))
      (println "PROBE feature-sweep FAIL"
               (str "角で zmax=" (nth (second (bb corner)) 2)
                    "（11.0 を期待）—— 断面が回転しておらず平行移動しかしていない"))
      (not (every? #(< -1 % (count (:positions corner))) (:indices corner)))
      (println "PROBE feature-sweep FAIL" "面が存在しない頂点を参照する")
      (not (and (= :error s3) (str/includes? obl "perpendicular to the start")))
      (println "PROBE feature-sweep FAIL" (str "斜めの profile を拒否しない: " (pr-str [s3 obl])))
      (not (and (= :error s4) (str/includes? closed "single OPEN chain")))
      (println "PROBE feature-sweep FAIL" (str "閉じた経路を拒否しない: " (pr-str [s4 closed])))
      :else (println "PROBE feature-sweep PASS"
                     (str "直線=押し出し一致 / 角 zmax=" (nth (second (bb corner)) 2)
                          "（frame 搬送）/ 斜め profile と閉経路は拒否"))))
  (catch :default ex (println "PROBE feature-sweep UNMEASURABLE" (.-message ex))))
