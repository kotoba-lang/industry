(ns business
  "murakumo.cloud の business / marketing / support / produce / consume を実測から計算する。

   実測の出所（90-docs/business/ の BMC 既存システム、捏造ゼロ）:
   - maturity-scores.edn note (2026-07-29): murakumo.cloud zone 536 req/7d・uniques 1
     = 外部利用ゼロ。容量天井 = head 1台で約 40M tok/日。x402 出品 $0.01/req ≒ $37/1M tok。
   - canvas funnel（18 tick 以上）: 訪問 549–728 → 実推論 run 200 → paid **0**（毎回）
   - :hyp/murakumo-tok-price [validated] 2026-07-23: fleet ¥20.04/Mtok vs spot ¥40.55"
  (:require [dynamics.core :as d]))

(defn r2 [x] (/ (js/Math.round (* 100 x)) 100))
(defn r4 [x] (/ (js/Math.round (* 10000 x)) 10000))
(defn pct [x] (str (r2 (* 100 x)) "%"))

;; ---- 1. 転換率の上界（0 件観測。点推定は存在しない）-------------------------
(def runs-observed 200)        ; canvas funnel の実推論 run（プラトー）
(def visits-observed 671)      ; 同 訪問（直近 tick）
(def paid-observed 0)

(println "=== 1. 転換率の上界 — 0 件観測から言えること（95% 信頼）===")
(println "  点推定は作れない。成功が 1 件も無い以上『上界』しか言えない。")
(let [b-run   (d/upper-bound-rate-from-zero-events runs-observed)
      b-visit (d/upper-bound-rate-from-zero-events visits-observed)]
  (println (str "  run→paid   : " runs-observed " 試行 0 件 → 真の転換率 ≤ " (pct b-run)))
  (println (str "  訪問→paid : " visits-observed " 試行 0 件 → 真の転換率 ≤ " (pct b-visit)))
  (def bound-visit-paid b-visit)
  (def bound-run-paid b-run))

;; ---- 2. produce（供給）— 自前 fleet の容量天井 -------------------------------
(println "\n=== 2. produce — 自前 fleet の収益天井（実測）===")
(def head-tok-per-day 40e6)          ; 実測: head 1台 (llama-server + ComfyUI 同居)
(def fleet-cost-yen-per-mtok 20.04)  ; 実測 validated 2026-07-23
(def fx 157.0)
(def sellable-usd-per-mtok 0.30)     ; 市場混合価格（GPT-4o-mini $0.15-0.60 の帯）
(let [cost-usd-mtok (/ fleet-cost-yen-per-mtok fx)
      mtok-day      (/ head-tok-per-day 1e6)
      rev-day       (* mtok-day sellable-usd-per-mtok)
      cost-day      (* mtok-day cost-usd-mtok)]
  (println (str "  head 容量        : " (r2 mtok-day) " Mtok/日"))
  (println (str "  fleet 原価       : $" (r4 cost-usd-mtok) "/Mtok（¥" fleet-cost-yen-per-mtok "）"))
  (println (str "  100% 完売の売上   : $" (r2 rev-day) "/日 = $" (r2 (* 30 rev-day)) "/月"))
  (println (str "  100% 完売の粗利   : $" (r2 (- rev-day cost-day)) "/日 = $"
                (r2 (* 30 (- rev-day cost-day))) "/月"))
  (println "  → 自前 fleet だけを供給源にする限り、これが構造的な収益天井。")
  (def fleet-ceiling-month (* 30 rev-day)))

;; ---- 3. consume（需要）— aggregator に切り替えた時の天井 ---------------------
(println "\n=== 3. consume — aggregator（fal から買う）の場合 ===")
(def arpu 49.0)
(def contribution-rate 0.405)        ; ADR-2608026200 Plus の 100% 消化時寄与率
(println (str "  1 顧客の月次寄与 : $" (r2 (* arpu contribution-rate))))
(println (str "  fleet 天井 $" (r2 fleet-ceiling-month) "/月 に並ぶ顧客数 = "
              (js/Math.ceil (/ fleet-ceiling-month (* arpu contribution-rate))) " 人"))
(println "  → 有料顧客 30 人で自前 fleet の理論天井を超える。容量制約は消える。")

;; ---- 4. marketing — 上界から出せる CAC の上限 -------------------------------
(println "\n=== 4. marketing — 許容 CAC（転換率の上界を使うので、これも上界）===")
(doseq [churn [0.03 0.05 0.10]]
  (let [lifetime-mo (/ 1.0 churn)
        ltv (* arpu contribution-rate lifetime-mo)
        max-cac (/ ltv 3.0)                       ; LTV/CAC = 3
        visitors-per-paid (/ 1.0 bound-visit-paid)
        max-cpc (/ max-cac visitors-per-paid)]
    (println (str "  churn " (pct churn) "/月 → 寿命 " (r2 lifetime-mo) " ヶ月"
                  " / LTV $" (r2 ltv)
                  " / 許容 CAC $" (r2 max-cac)
                  " / 必要訪問 " (js/Math.ceil visitors-per-paid) " 人"
                  " → 許容 CPC ≤ $" (r4 max-cpc)))))
(println "  ⚠ 転換率が上界なので CPC も上界。真の転換率が 1/10 なら CPC 上限も 1/10。")

;; ---- 5. support — 未計測であることを明示する --------------------------------
(println "\n=== 5. support ===")
(println "  顧客 0 人なので support 単価は 1 件も測っていない。")
(println (str "  寄与 $" (r2 (* arpu contribution-rate)) "/顧客月 のうち、"
              "support が食える上限を仮に 20% とすると $"
              (r2 (* 0.2 arpu contribution-rate)) "/顧客月。"))
(println "  → API 事業の実務では『月 1 回の問い合わせ』でこれを超える。self-serve が前提条件であって選択肢ではない。")

;; ---- 6. Meadows leverage — 実測を入れた上での順位 ---------------------------
(println "\n=== 6. 介入の leverage（実測反映）===")
(def interventions
  [{:id :first-paying-customer :band :band/B :tractability 1.0
    :what "有料顧客を 1 人作る（転換率の点推定が初めて存在するようになる）"}
   {:id :aggregator-supply :band :band/D :tractability 0.9
    :what "供給を自前 fleet から fal へ移す（容量天井 $360/月 を外す）"}
   {:id :procurement-floor :band :band/B :tractability 0.9
    :what "調達の床（fal より高い予約 GPU を借りない）"}
   {:id :fix-x402-price :band :band/E :tractability 1.0
    :what "x402 出品 $0.01/req（≒$37/1M tok、GPT-4o-mini の約100倍）を実勢へ直す"}
   {:id :paid-acquisition :band :band/E :tractability 0.8
    :pool-size visits-observed :conversion-rate nil
    :what "広告で訪問を買う"}])
(doseq [i (d/rank-interventions interventions)]
  (println (str "  " (r2 (:base-score i)) "\t" (name (:band i)) "\t" (:what i)
                (when-let [y (:expected-yield i)] (str "\n\t\t\t期待収穫: " y)))))
