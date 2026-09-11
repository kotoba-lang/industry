(ns pricing
  "murakumo credits の最適価格を XMILE stock-flow で解く（ADR-2608026200 の検証）。

   ★ plan 制の構造: 顧客は月額を固定で払い、掲示価格(cr/出力秒)は『いくら受け取れるか』
     を決める。つまり価格を下げても売上は増えず、原価だけが増える —— 増えるのは
     顧客数（価値/ドルが上がるため）だけ。ここが従量課金の最適価格問題と違う。

   ループ:
     R1 (+) 低価格 → 秒/ドル↑ → 到達市場↑ → 顧客↑ → 需要↑ → 予約 GPU 稼働率↑ → 単価↓
     B1 (−) 需要↑ → 予約容量超過 → fal 従量へスピル（$0.05/秒）→ 単価↑ → 利益↓
     B2 (−) 容量調整に 7 日の遅れ → 成長局面ほどスピル比率が上がる
     B3 (−) 低価格 → 顧客あたり配布秒数↑ → 顧客あたり原価↑ → 利益率↓"
  (:require [xmile.model :as m]
            [xmile.execute :as execute]))

;; ---- 原価（2026-08-02、公開情報）--------------------------------------------
(def fal-per-out-sec 0.05)
(def pod-hourly {:runpod-community 2.39 :runpod-secure 2.99 :fal-serverless-list 3.99
                 :modal 4.29 :runpod-serverless 4.55})

;; ---- SCENARIO（未計測。実測値として扱わない）--------------------------------
(def sc {:p 0.008 :q 0.35          ; Bass 係数 — murakumo では未計測
         :base-market 4000         ; 参照価格 10cr/秒 での到達可能顧客数
         :ref-price-cr 10.0        ; 参照掲示価格 cr/出力秒
         :arpu-month 49.0          ; 顧客あたり月額（Plus 相当の混合 ARPU）
         :allowance-util 1.0       ; 配布 credits の消化率。1.0 = breakage ゼロ（最悪ケース）
         :adjust-days 7 :target-util 0.75 :rev-side-fee 0.044})

(defn build [{:keys [price-cr elasticity wall pod-rate days allowance-util]}]
  (let [{:keys [p q base-market ref-price-cr arpu-month adjust-days
                target-util rev-side-fee]} sc
        au (or allowance-util (:allowance-util sc))
        arpu-day (/ arpu-month 30.0)
        ;; 1 顧客が 1 日に受け取る出力秒 = (月額 × 100cr/$) / (cr/秒) / 30日 × 消化率
        sec-per-cust-day (* (/ (* arpu-month 100.0) price-cr) (/ 1.0 30.0) au)]
    (-> (m/model "murakumo-price")
        (m/set-sim-specs (m/sim-specs 0 days {:xmile/dt 1.0 :xmile/method :euler}))
        ;; 価値/ドル（= 秒/ドル）が上がるほど到達市場が広がる。elasticity は SCENARIO。
        (m/add-variable (m/aux "market_size"
                               (str base-market " * (" ref-price-cr " / " price-cr ") ^ " elasticity)))
        (m/add-variable (m/stock "Customers" "1" {:xmile/inflows #{"adoption"}}))
        (m/add-variable (m/flow "adoption"
                                (str "(" p " + " q " * Customers / market_size) * MAX(0, market_size - Customers)")))
        (m/add-variable (m/aux "demand" (str "Customers * " sec-per-cust-day)))
        ;; ★ 合理的な調達: 予約 GPU の実効単価が fal 従量より高いなら借りない。
        ;; これが無いと『買った方が安いのに借り続ける』非合理な運用を模型化してしまう。
        (m/add-variable (m/aux "desired_pods"
                               (str (if (< (/ (* (/ pod-rate 3600.0) wall) target-util) fal-per-out-sec) 1 0)
                                    " * demand * " wall " / 86400 / " target-util)))
        (m/add-variable (m/stock "Pods" "0" {:xmile/inflows #{"pod_adjust"}}))
        (m/add-variable (m/flow "pod_adjust" (str "(desired_pods - Pods) / " adjust-days)))
        (m/add-variable (m/aux "reserved_capacity" (str "Pods * 86400 / " wall)))
        (m/add-variable (m/aux "spill" "MAX(0, demand - reserved_capacity)"))
        (m/add-variable (m/aux "cost_reserved" (str "Pods * " pod-rate " * 24")))
        (m/add-variable (m/aux "cost_spill" (str "spill * " fal-per-out-sec)))
        ;; 売上は顧客数 × 月額（価格には依存しない）
        (m/add-variable (m/aux "revenue" (str "Customers * " arpu-day)))
        (m/add-variable (m/flow "profit"
                                (str "revenue * (1 - " rev-side-fee ") - cost_reserved - cost_spill")))
        (m/add-variable (m/stock "Cum_Profit" "0" {:xmile/inflows #{"profit"}})))))

(defn- lastv [s k] (peek (get s k)))

(defn run-one [opts]
  (let [{:xmile/keys [series]} (execute/run (build opts))
        cust (lastv series "Customers")
        rev  (lastv series "revenue")
        prof (lastv series "profit")]
    {:price-cr (:price-cr opts)
     :cum (lastv series "Cum_Profit")
     :customers cust
     :margin (if (pos? rev) (/ prof rev) 0.0)          ; 定常の寄与率
     :spill-frac (let [d (lastv series "demand") s (lastv series "spill")]
                   (if (pos? d) (/ s d) 0.0))}))

(defn sweep [opts prices]
  (mapv #(run-one (assoc opts :price-cr %)) prices))

(defn fmt [x] (/ (js/Math.round (* 10 x)) 10))
(defn pct [x] (str (/ (js/Math.round (* 1000 x)) 10) "%"))

(defn report [label opts prices]
  (let [rs (sweep opts prices)
        best (first (sort-by :cum > rs))]
    (println (str "\n=== " label " ==="))
    (println "cr/秒\t顧客数\t定常寄与率\tスピル率\t2年累積利益$")
    (doseq [r rs]
      (println (str "  " (:price-cr r) "\t" (fmt (:customers r))
                    "\t" (pct (:margin r)) "\t\t" (pct (:spill-frac r))
                    "\t\t" (fmt (:cum r))
                    (when (= r best) "   ← 最適"))))
    best))

(def prices [4 6 8 10 12 14 16 20 25 30])
(def days 730)

(println "murakumo 最適価格 — XMILE stock-flow（org-oasis-open-xmile、Euler dt=1、730 日）")
(println "原価: fal 従量 $0.05/出力秒、予約 GPU = RunPod Community $2.39/h")
(println "ARPU $49/月、breakage ゼロ（消化率 100% = 最悪ケース）")

(comment (doseq [[wl wall] [["A wall=73.3（gad 実測 Radeon APU 相当）" 73.3]
                   ["B wall=18.3（H100 が 4x 速い SCENARIO）" 18.3]]]
  (doseq [e [1.0 1.5 2.0 3.0]]
    (report (str wl " / 弾力性 " e)
            {:elasticity e :wall wall :pod-rate (:runpod-community pod-hourly) :days days}
            prices)))

)
