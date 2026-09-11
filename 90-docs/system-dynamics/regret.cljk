(ns regret
  "最適価格の minimax-regret 分析。未計測パラメタ（H100 速度 × 弾力性）の
   全組み合わせに対し、各価格の『最適から何%劣るか』を出し、最悪後悔を最小化する
   価格を選ぶ。単一シナリオの最適値ではなく、どのシナリオでも大崩れしない価格を探す。"
  (:require [pricing :as p]))

(def prices [2 3 4 6 8 10 12 14 16 20 25 30])
(def walls {"A: gad実測 73.3" 73.3 "B: H100 2x 36.7" 36.7 "C: H100 4x 18.3" 18.3})
(def elasticities [1.0 1.5 2.0 3.0])
(def pods {"RunPod Community 2.39" 2.39 "Modal 4.29" 4.29})

(defn cell [wall e pod]
  (let [rs (p/sweep {:elasticity e :wall wall :pod-rate pod :days 730} prices)
        best (apply max (map :cum rs))]
    {:best best
     :by-price (into {} (map (juxt :price-cr identity) rs))
     ;; regret = (最適 - この価格) / |最適|。最適が負なら全滅シナリオ。
     :regret (into {} (for [r rs]
                        [(:price-cr r)
                         (if (pos? best) (/ (- best (:cum r)) best) 0.0)]))}))

(println "=== シナリオ別の最適価格（cr/出力秒）と、10cr/秒 の後悔 ===")
(println "wall\t\t\tpod\t\t\tε\t最適cr/秒\t10cr/秒の後悔\t定常寄与率@10")
(def cells
  (doall
   (for [[wl wall] walls [pl pod] pods e elasticities]
     (let [c (cell wall e pod)
           bestp (key (apply max-key #(:cum (val %)) (:by-price c)))
           r10 (get (:regret c) 10)
           m10 (:margin (get (:by-price c) 10))]
       (println (str wl "\t" pl "\t" e "\t" bestp
                     "\t\t" (p/pct r10) "\t\t" (p/pct m10)))
       (assoc c :wall wall :pod pod :e e :best-price bestp)))))

(println "\n=== 各価格の最悪後悔（全 " (count cells) " シナリオ横断）===")
(println "cr/秒\t最悪後悔\t寄与率が30%を割るシナリオ数\t赤字シナリオ数")
(def rows
  (for [pr prices]
    (let [regrets (map #(get (:regret %) pr) cells)
          margins (map #(:margin (get (:by-price %) pr)) cells)]
      {:price pr
       :worst-regret (apply max regrets)
       :below-floor (count (filter #(< % 0.30) margins))
       :negative (count (filter neg? margins))})))
(doseq [r (sort-by :price rows)]
  (println (str "  " (:price r) "\t" (p/pct (:worst-regret r))
                "\t\t" (:below-floor r) " / " (count cells)
                "\t\t\t" (:negative r))))

(let [safe (filter #(zero? (:below-floor %)) rows)
      pick (when (seq safe) (apply min-key :worst-regret safe))]
  (println (str "\n→ 寄与率 30% floor を全シナリオで満たす価格: "
                (pr-str (mapv :price safe))))
  (println (str "→ そのうち最悪後悔が最小 = **" (:price pick) " cr/出力秒**"
                "（最悪後悔 " (p/pct (:worst-regret pick)) "）")))
