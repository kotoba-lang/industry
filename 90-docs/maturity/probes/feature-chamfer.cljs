(ns probe-feature-chamfer (:require [brep.feature :as f] [brep.topology :as t] [clojure.string :as str]))
;; 面取りは **閉じた形の解析解**で測る。1 稜を距離 d で面取りすると三角柱
;; 1/2 d^2 L がちょうど取れる —— 「三角形が増えた」ではなく「その体積が消えた」。
;; 最初の実装は結果を union で戻しており、体積 600 のまま二面角も全部 90 度なのに
;; 三角形だけ 12 → 52 に増えていた。**何も変えずに三角形を増やす切削**が一番
;; もっともらしい間違いなので、体積を見る。
(try
  (let [vol (fn [m] (Math/abs (/ (reduce + (map (fn [[a b c]]
                (let [p (nth (:positions m) a) q (nth (:positions m) b) r (nth (:positions m) c)]
                  (reduce + (map * p [(- (* (q 1) (r 2)) (* (q 2) (r 1)))
                                      (- (* (q 2) (r 0)) (* (q 0) (r 2)))
                                      (- (* (q 0) (r 1)) (* (q 1) (r 0)))]))))
                (partition 3 (:indices m)))) 6.0)))
        base (-> (f/feature-tree)
                 (f/add-feature (f/sketch-feature 1 (f/sketch-plane-xy)
                   [(f/sketch-line [0 0] [10 0]) (f/sketch-line [10 0] [10 10])
                    (f/sketch-line [10 10] [0 10]) (f/sketch-line [0 10] [0 0])]))
                 (f/add-feature (f/extrude-feature 2 1 [0 0 1] 6 :new)))
        [_ m0] (f/evaluate-mesh base)
        tp0 (t/topology m0)
        e (first (filter (fn [[i j]] (let [a (nth (:vertices tp0) i) b (nth (:vertices tp0) j)]
                                       (and (zero? (double (a 1))) (zero? (double (b 1)))
                                            (zero? (double (a 2))) (zero? (double (b 2))))))
                         (t/sharp-edges tp0 :convex 0.2)))
        one (fn [d] (let [[s m] (f/evaluate-mesh (f/add-feature base (f/chamfer-feature 3 [e] d)))]
                      (when (= :ok s) [(vol m) (count (t/boundary-edges (t/topology m)))])))
        [v1 b1] (one 1.0) [v2 b2] (one 2.0)
        [sa ma] (f/evaluate-mesh (f/add-feature base (f/chamfer-feature 3 :all-convex 1.0)))
        angs (when (= :ok sa)
               (frequencies (map #(Math/round (* 180.0 (/ (:dihedral %) Math/PI)))
                                 (filter #(= :manifold (:kind %)) (vals (:edges (t/topology ma)))))))
        refused (second (f/evaluate-mesh (f/add-feature base (f/chamfer-feature 3 [1 2 3] 1.0))))]
    (cond
      (nil? v1) (println "PROBE feature-chamfer FAIL" "1 稜の面取りが評価できない")
      (> (Math/abs (- v1 595.0)) 1e-9)
      (println "PROBE feature-chamfer FAIL"
               (str "d=1 の体積が " v1 "（解析解 595 = 600 - 0.5*1*1*10）"
                    (when (= 600.0 v1) " —— 何も削れていない。union で戻していないか")))
      (> (Math/abs (- v2 580.0)) 1e-9)
      (println "PROBE feature-chamfer FAIL" (str "d=2 の体積が " v2 "（解析解 580）"))
      (pos? (+ b1 b2)) (println "PROBE feature-chamfer FAIL" "面取り後に開いている")
      (not= :ok sa) (println "PROBE feature-chamfer FAIL" (str "全稜の面取りが失敗: " (pr-str ma)))
      (some? (get angs 90))
      (println "PROBE feature-chamfer FAIL"
               (str "全稜を面取りしたのに 90 度の稜が " (get angs 90) " 本残っている"))
      (not= [24 24] [(get angs 45) (get angs 60)])
      (println "PROBE feature-chamfer FAIL"
               (str "二面角の分布が " (pr-str angs) "（45度24本=稜あたり2本、60度24本=角8つ×3 を期待）"))
      (not (str/includes? (str refused) ":all-convex"))
      (println "PROBE feature-chamfer FAIL" (str "BREP エッジ id を名指しで拒否しない: " (pr-str refused)))
      :else (println "PROBE feature-chamfer PASS"
                     (str "1 稜 d=1→595 d=2→580（解析解と一致）/ 全稜面取りで閉・90度の稜ゼロ・"
                          "45度24本+60度24本 / BREP id は拒否"))))
  (catch :default ex (println "PROBE feature-chamfer UNMEASURABLE" (.-message ex))))
