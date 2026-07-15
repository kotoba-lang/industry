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
;; Grounded in what collect.cljs can measure today (Cloudflare zone / Stripe /
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
   ;; measurable via subscription telemetry emitter (apex): Free→Plus 転換率 >= Proton 水準
   ;; Stripe product は既存 (Gftd AI Pro prod_SdVVTqTHT1Z206 / price_1RiEU5… $20)。
   {:metric [:conversion :pct] :op :>= :threshold 0.02
    :evidence-label "Free→Plus 転換率"
    :needs-when-unmeasurable ["Stripe checkout 配線 (product 既存)" "Free→Plus 転換テレメトリ"]}

   :hyp/kotobase-graph-arpu
   ;; machine-measurable: first paid tenant = Stripe active subscription >= 1
   {:metric [:stripe :active-subscriptions] :op :>= :threshold 1
    :evidence-label "Stripe active subscriptions"
    :needs-when-unmeasurable ["signup→checkout 配線 (kotobase price 既存)" "tenant 従量計測"]}

   :hyp/itonami-smb-pay
   ;; measurable via tenant metrics emitter (itonami): 外部有償 org >= 1
   {:metric [:tenants :external-paid] :op :>= :threshold 1
    :evidence-label "外部有償 org 数"
    :needs-when-unmeasurable ["初期 vertical の絞り込み" "外部オンボーディング導線" "per-seat billing"]}

   :hyp/aozora-organism-content
   ;; measurable via engagement telemetry emitter (aozora): organism-engagement-ratio が閾値超え
   {:metric [:engagement :organism-engagement-ratio] :op :>= :threshold 0.3
    :evidence-label "organism engagement 比率"
    :needs-when-unmeasurable ["engagement テレメトリ (DAU / post engagement / feed 計測)"
                              "organism/human actorType 属性 + agent DID allow-list"]}

   :hyp/yoro-aozora-funnel
   ;; measurable via yoro MAU emitter: aozora→yoro MAU 転換率が閾値超え
   {:metric [:mau :conversion-pct] :op :>= :threshold 0.1
    :evidence-label "aozora→yoro MAU 転換率"
    :needs-when-unmeasurable ["yoro child repo 分離" "MAU テレメトリ"]}

   :hyp/manimani-ledger-pay
   ;; measurable via /telemetry/install + signups emitter (ADR-2607022200): 転換率 >= Obsidian Sync 水準
   {:metric [:conversion :pct] :op :>= :threshold 0.04
    :evidence-label "OSS→cloud 転換率"
    :needs-when-unmeasurable ["OSS install テレメトリ" "cloud signup funnel" "価格設計"]}

   :hyp/etzhayyim-registry-value
   ;; 非営利・主観 gate: 自動 validate しない (needs-only)。
   ;; etzhayyim RAD emitter (PR#2840) の :rad {:attestation-refs 320 ...} は「既存
   ;; attestation(sigref)数」であって gate 意図の「itonami が契約要件として参照した回数」
   ;; ではない — これで auto-pass させると未達成 gate を誤 validate する。最終「価値を
   ;; 認める」判断は評議=人間 (scout/PR#2840 明記)。itonami-contract-ref の実計測が
   ;; 入るまで needs-only を維持し、通過は人手で hyp pass する。
   {:needs ["itonami 契約の RAD attestation 参照フック (rad_attestation_ref scaffold の live 化)"
            "資金チャネル (寄付/助成)"]}

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
    :needs-when-unmeasurable ["YouTube Data API OAuth 復活" "投稿本数の量産"]}

   :hyp/nexus-x402-adoption
   ;; machine-measurable via public GET /catalog census (collect.cljs :nexus-x402
   ;; gate-emitter, no admin auth needed): 最低1 seller が nexus 経由で live 稼働
   ;; (「最低1つが…移行」の :hyp/gate 文言に忠実な proxy — 個別ゲート「廃止」まで
   ;; は問わない。廃止の有無は README/ADR に別途明記し続ける)。
   {:metric [:catalog :count] :op :>= :threshold 1
    :evidence-label "nexus /catalog 登録 seller 数"
    :needs-when-unmeasurable ["seller registry 実装 (ADR-0001 landed)" "seller 登録 (SELLERS_JSON/KV)"]}

   :hyp/nexus-x402-agent-demand
   ;; machine-measurable since nexus-x402 shipped GET /stats (2026-07-10):
   ;; aggregate-only settlement census, :agent-hint classified by
   ;; nexus.settlements/classify-user-agent (a HONEST HEURISTIC, not proof --
   ;; User-Agent is spoofable by either side; this gate tracks "settlements
   ;; that didn't present a browser fingerprint", not "verified autonomous
   ;; agents"). collect.bb's :nexus-x402 emitter now fetches /stats (superset
   ;; of /catalog) into :catalog, so :catalog :settlements :agent-hint :agent
   ;; is the real path.
   {:metric [:catalog :settlements :agent-hint :agent] :op :> :threshold 0
    :evidence-label "nexus /stats agent-hint 決済件数 (heuristic)"
    :needs-when-unmeasurable ["SETTLEMENTS_KV に実決済が記録される運用開始 (現状 GMV 0 — 未計測)"]}

   :hyp/nexus-x402-external-seller
   ;; machine-measurable via the same /catalog census: known internal family is
   ;; exactly {murakumo, kotobase, shinshi} (3) — count > 3 necessarily means a
   ;; non-family (external) seller registered.
   {:metric [:catalog :count] :op :> :threshold 3
    :evidence-label "nexus /catalog 登録 seller 数 (内部3社超)"
    :needs-when-unmeasurable ["self-serve onboarding (docs/adr/0002 ステップ2)" "外部 seller 1件以上の登録"]}})

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

(defn- block-items-set [idx block-id]
  "Get current canvas items for a block as a set for dedup."
  (let [block (get-in idx [:blocks block-id])]
    (if block (set (:canvas/items block)) #{})))

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
           ;; 既に :validated の仮説は再提案しない — これが無いと gate 到達後の
           ;; 毎 tick/毎日 run が同じ昇格 event を ledger に積み続け、loop が
           ;; 収束 (dry) しない (2026-07-15 cloud-murakumo 実測で発見)。
           (when (not= :validated (:hyp/status h))
             [{:proposal/action :hyp/status :hyp/id hid :event/value :validated
               :event/evidence (:evidence r)
               :proposal/reason "gate 到達 (機械測定) — 仮説を validated に昇格"}])
           :blocked
           (let [solution-items (block-items-set idx (block-id product "solution"))]
             (for [need (:needs r)
                   :let [txt (str "準備 (" (name hid) "): " need)]
                   :when (not (contains? solution-items txt))]
               {:proposal/action :canvas/add-item
                :canvas/id (block-id product "solution")
                :event/value txt
                :proposal/reason "gate 測定に必要な計器/前提が未整備 — 準備項目として提案"}))
           :measuring
           (let [metrics-items (block-items-set idx (block-id product "metrics"))
                 txt (str "gate 距離 (" (name hid) "): " (:distance r))]
             (if (contains? metrics-items txt)
               []
               [{:proposal/action :canvas/add-item
                 :canvas/id (block-id product "metrics")
                 :event/value txt
                 :proposal/reason "gate は機械測定可能・未到達 — 距離を運用指標に反映"}]))
           [])))))
     hyps)))
