(ns gftd.gate
  "Gate evaluator (ADR-2607022100): turn collected metrics into hypothesis
   progression so each BMC actually *cycles* on a schedule instead of going dry.

   Per riskiest hypothesis, a gate-spec is either:
   - machine-measurable — {:metric [path...] :op :>= :threshold N} over the
     product's metrics edn. When satisfied → propose `hyp/status :validated`
     with the measured value as evidence (auto-advance). When measurable but
     not yet met → emit a 'gate distance' observation (measuring), hyp stays untested.
   - instrument-blocked — {:needs [\"missing instrument\" ...]}. The gate can't be
     measured until those are prepared, so propose a concrete '準備:' item into
     the solution block (actionable to-do) and record the gate as :blocked.

   This is what makes the daily schedule a real kaizen cycle: validation moves
   when real data crosses a gate, and the preparation gap for each product is
   surfaced as an actionable proposal (through the governor) rather than the loop
   silently going dry. Pure .cljc; io stays in cli/collect."
  (:require [clojure.string :as str]
            [gftd.canvas :as canvas]))

;; ---- per-hypothesis gate specs ----------------------------------------------
;; Grounded in what collect.bb can measure today (Cloudflare zone / Stripe /
;; health / product-supplied metrics edn) vs. the product-repo instruments still
;; missing (ADR-2607021900/2607022000 の診断)。

(def gate-specs
  {:hyp/murakumo-tok-price
   ;; measurable via GET /infer/cost emitter (ADR-2607022200): fleet ¥/Mtok <= spot ¥/Mtok
   {:compare {:lhs [:cost :fleet-yen-per-mtok] :op :<= :rhs [:cost :spot-yen-per-mtok]}
    :evidence-label "fleet ¥/Mtok vs spot"
    :needs-when-unmeasurable ["run ledger の原価/tok export (node別 tok/s × 電力)"
                              "社内3アプリ推論の fleet 移管で原価比較"]}

   :hyp/apex-privacy-premium
   {:needs ["tier 価格定義" "Stripe product 作成" "Free→Plus 転換テレメトリ"]}

   :hyp/kotobase-graph-arpu
   ;; machine-measurable: first paid tenant = Stripe active subscription >= 1
   {:metric [:stripe :active-subscriptions] :op :>= :threshold 1
    :evidence-label "Stripe active subscriptions"
    :needs-when-unmeasurable ["signup→checkout 配線 (kotobase price 既存)" "tenant 従量計測"]}

   :hyp/itonami-smb-pay
   {:needs ["初期 vertical の絞り込み" "外部オンボーディング導線" "per-seat billing"]}

   :hyp/aozora-organism-content
   {:needs ["engagement テレメトリ (DAU / post engagement / feed 計測)"]}

   :hyp/yoro-aozora-funnel
   {:needs ["yoro child repo 分離" "MAU テレメトリ"]}

   :hyp/manimani-ledger-pay
   ;; measurable via /telemetry/install + signups emitter (ADR-2607022200): 転換率 >= Obsidian Sync 水準
   {:metric [:conversion :pct] :op :>= :threshold 0.04
    :evidence-label "OSS→cloud 転換率"
    :needs-when-unmeasurable ["OSS install テレメトリ" "cloud signup funnel" "価格設計"]}

   :hyp/etzhayyim-registry-value
   {:needs ["itonami 契約の RAD attestation 参照フック" "資金チャネル (寄付/助成)"]}

   :hyp/isekai-fork-viral
   ;; measurable via public/feed/fork-stats.edn emitter (network-isekai PR#15): viral 係数 >= 1.0
   {:metric [:fork :viral-coefficient] :op :>= :threshold 1.0
    :evidence-label "fork viral 係数"
    :needs-when-unmeasurable ["fork イベントテレメトリ (週次 fork 数 / fork 由来新規作品比率)"]}

   :hyp/club-shinshi-creator-take
   ;; measurable via creator_billing_daily emitter (ADR-2607022200): creator GMV > ad 収益
   {:compare {:lhs [:revenue :creator-gmv-jpy] :op :> :rhs [:revenue :ad-revenue-jpy]}
    :evidence-label "creator GMV vs ad 収益"
    :needs-when-unmeasurable ["PSP/crypto rail 解禁 (ADR-2605220000 凍結)" "creator billing" "creator GMV 計測"]}

   :hyp/yukkuri-ypp-then-rpm
   ;; machine-measurable: YPP = subscribers>=1000 AND watch-hours>=4000
   {:all [{:metric [:channel :subscribers] :op :>= :threshold 1000}
          {:metric [:channel :watch-hours] :op :>= :threshold 4000}]
    :evidence-label "YouTube 登録者/総再生"
    :needs-when-unmeasurable ["YouTube Data API OAuth 復活" "投稿本数の量産"]}})

;; ---- predicate evaluation ---------------------------------------------------

(defn- num [x] (cond (number? x) x (string? x) (parse-double x) :else nil))

(defn- op-fn [op] (case op :>= >= :> > :<= <= :< < := == :== ==))

(defn- eval-clause
  "→ {:measurable bool :met bool :value v} for one {:metric :op :threshold} clause."
  [metrics {:keys [metric op threshold]}]
  (let [v (num (get-in metrics metric))]
    (if (nil? v)
      {:measurable false}
      {:measurable true :met ((op-fn op) v threshold) :value v})))

(defn evaluate-hyp
  "Evaluate one hypothesis' gate against the product's metrics.
   → {:status :validated|:measuring|:blocked :evidence str :needs [..] :distance str}"
  [metrics spec]
  (cond
    (nil? spec) {:status :blocked :needs ["gate-spec 未定義"]}

    ;; conjunction of clauses (:all)
    (:all spec)
    (let [rs (map #(eval-clause metrics %) (:all spec))]
      (cond
        (some #(not (:measurable %)) rs)
        {:status :blocked :needs (:needs-when-unmeasurable spec)}
        (every? :met rs)
        {:status :validated
         :evidence (str (:evidence-label spec) " gate 到達: "
                        (str/join " / " (map :value rs)))}
        :else
        {:status :measuring
         :distance (str (:evidence-label spec) " 現在 "
                        (str/join " / " (map :value rs)) " (gate 未到達)")}))

    ;; cross-metric comparison (lhs op rhs)
    (:compare spec)
    (let [{:keys [lhs op rhs]} (:compare spec)
          l (num (get-in metrics lhs)) r (num (get-in metrics rhs))]
      (cond
        (or (nil? l) (nil? r)) {:status :blocked :needs (:needs-when-unmeasurable spec)}
        ((op-fn op) l r) {:status :validated
                          :evidence (str (:evidence-label spec) " gate 到達: " l " vs " r)}
        :else {:status :measuring
               :distance (str (:evidence-label spec) " 現在 " l " vs " r " (gate 未到達)")}))

    ;; single measurable clause
    (:metric spec)
    (let [r (eval-clause metrics spec)]
      (cond
        (not (:measurable r)) {:status :blocked :needs (:needs-when-unmeasurable spec)}
        (:met r) {:status :validated
                  :evidence (str (:evidence-label spec) " = " (:value r) " (gate 到達)")}
        :else {:status :measuring
               :distance (str (:evidence-label spec) " = " (:value r) " (gate 未到達)")}))

    ;; instrument-blocked
    (:needs spec) {:status :blocked :needs (:needs spec)}

    :else {:status :blocked :needs ["gate-spec 不明"]}))

;; ---- proposals ---------------------------------------------------------------

(defn- block-id [product suffix] (keyword (str (name product) "." suffix)))

(defn proposals
  "Given the folded index, a product, and its metrics, return governor-ready
   proposals that advance the BMC cycle:
   - :validated → hyp/status :validated (evidence attached)
   - :blocked   → 準備 to-do item into the solution block (dedup'd)
   - :measuring → gate-distance observation into key-metrics (dedup'd)"
  [idx product metrics]
  (let [hyps (canvas/product-hyps idx product)]
    (mapcat
     (fn [h]
       (let [hid (:hyp/id h)
             spec (get gate-specs hid)]
        (if (nil? spec)
          []                                  ; gate-spec の無い仮説はスキップ（no-op）
          (let [r (evaluate-hyp metrics spec)]
           (case (:status r)
           :validated
           [{:proposal/action :hyp/status :hyp/id hid :event/value :validated
             :event/evidence (:evidence r)
             :proposal/reason "gate 到達 (機械測定) — 仮説を validated に昇格"}]
           :blocked
           (for [need (:needs r)
                 :let [txt (str "準備 (" (name hid) "): " need)]]
             {:proposal/action :canvas/add-item
              :canvas/id (block-id product "solution")
              :event/value txt
              :proposal/reason "gate 測定に必要な計器/前提が未整備 — 準備項目として提案"})
           :measuring
           [{:proposal/action :canvas/add-item
             :canvas/id (block-id product "metrics")
             :event/value (str "gate 距離 (" (name hid) "): " (:distance r))
             :proposal/reason "gate は機械測定可能・未到達 — 距離を運用指標に反映"}]
           [])))))
     hyps)))
