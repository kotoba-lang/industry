(ns gftd.funnel
  "Sales/marketing funnel process (ADR-2607022600): the layer above the gate
   evaluator. A gate measures the single riskiest *revenue hypothesis*; the funnel
   measures the whole acquisition→revenue path (AARRR-style stages) so the daily
   cycle can drive *growth* (a sales/marketing motion), not just validate one
   hypothesis.

   Per product a funnel-spec is an ordered vector of stages
   [{:key :label :metric [path] :benchmark r} ...]. Consecutive stages form
   conversion steps; `:benchmark` on a stage is the expected conversion FROM the
   previous stage TO this one. `evaluate-funnel` computes each stage count, each
   step's conversion rate, the gap vs benchmark, and the bottleneck step (measured,
   furthest below benchmark). `proposals` turns the bottleneck into a governor-ready
   GTM action into the product's channels block, records the funnel snapshot into
   the metrics block, and surfaces any unmeasured stage as an instrument to-do.

   Grounded in collectable metrics (same emitters as gate/collect — funnel counts
   come from the product's metrics edn). Pure .cljc; io stays in cli/collect."
  (:require [clojure.string :as str]
            [gftd.canvas :as canvas]))

;; ---- per-product funnel specs -----------------------------------------------
;; Stages are collectable via each product's funnel emitter (ADR-2607022200 と同じ
;; 経路)。benchmark は業界水準の下限目安（超えていれば健全、下回れば bottleneck）。
(def funnel-specs
  {:net-kotobase
   [{:key :awareness   :label "landing 訪問"       :metric [:funnel :visitors]}
    {:key :acquisition :label "signup"            :metric [:funnel :signups]   :benchmark 0.03}
    {:key :activation  :label "checkout 開始"      :metric [:funnel :checkouts] :benchmark 0.25}
    {:key :revenue     :label "paid(active sub)"   :metric [:stripe :active-subscriptions] :benchmark 0.50}]

   :ai-gftd-apex
   [{:key :awareness   :label "訪問"               :metric [:funnel :visitors]}
    {:key :acquisition :label "signup(Free)"       :metric [:funnel :signups]   :benchmark 0.04}
    {:key :revenue     :label "Plus 転換"          :metric [:conversion :plus]  :benchmark 0.02}]

   :cloud-manimani
   [{:key :awareness   :label "OSS install"        :metric [:oss :installs]}
    {:key :acquisition :label "cloud signup"       :metric [:cloud :signups]    :benchmark 0.10}
    {:key :revenue     :label "cloud paid"         :metric [:conversion :cloud-paid] :benchmark 0.04}]

   :cloud-itonami
   [{:key :awareness   :label "trial org"          :metric [:funnel :trials]}
    {:key :acquisition :label "onboarded org"      :metric [:tenants :active]   :benchmark 0.40}
    {:key :revenue     :label "外部有償 org"        :metric [:tenants :external-paid] :benchmark 0.20}]

   :club-shinshi
   [{:key :awareness   :label "訪問"               :metric [:funnel :visitors]}
    {:key :acquisition :label "登録"               :metric [:funnel :signups]   :benchmark 0.02}
    {:key :revenue     :label "課金/creator GMV"    :metric [:funnel :paying]    :benchmark 0.03}]

   ;; cloud-murakumo (2026-07-15): awareness = zone uniques (collect 済み)、
   ;; activation = /infer/cost emitter が数える実推論 run(ring 上限 200 —
   ;; 絶対数でなく「API が実際に使われているか」の生存信号)、revenue = Stripe。
   ;; Stripe は未配線(canvas 2026-07-06 観測: STRIPE_SECRET_KEY 未設定で
   ;; checkout 503)なので revenue 段が bottleneck として surface されるのが正。
   :cloud-murakumo
   [{:key :awareness   :label "murakumo.cloud 訪問" :metric [:zone :uniques-7d-sum]}
    {:key :activation  :label "実推論 run (記録済)"  :metric [:cost :runs-count]  :benchmark 0.02}
    {:key :revenue     :label "paid (credits 購入)"  :metric [:stripe :murakumo-paid-charges] :benchmark 0.01}]})

;; ---- evaluation -------------------------------------------------------------

(defn- num [x] (cond (number? x) x (string? x) (parse-double x) :else nil))

(defn evaluate-funnel
  "→ {:stages [{:key :label :count n|nil}]
      :steps  [{:from :to :from-count :to-count :rate r|nil :benchmark b :gap g|nil :measurable bool}]
      :bottleneck step|nil  ; measured step furthest below its benchmark
      :missing [stage-key ...]}  ; stages with no measurable count"
  [metrics spec]
  (let [stages (mapv (fn [s] (assoc s :count (num (get-in metrics (:metric s))))) spec)
        steps (mapv (fn [[a b]]
                      (let [fc (:count a) tc (:count b)
                            rate (when (and fc tc (pos? fc)) (/ (double tc) fc))
                            bench (:benchmark b)]
                        {:from (:key a) :to (:key b) :from-label (:label a) :to-label (:label b)
                         :from-count fc :to-count tc :rate rate :benchmark bench
                         :gap (when (and rate bench) (- rate bench))
                         :measurable (and (some? fc) (some? tc))}))
                    (partition 2 1 stages))
        measured-below (filter #(and (:measurable %) (:gap %) (neg? (:gap %))) steps)
        bottleneck (when (seq measured-below) (apply min-key :gap measured-below))
        missing (mapv :key (filter #(nil? (:count %)) stages))]
    {:stages stages :steps steps :bottleneck bottleneck :missing missing}))

;; ---- GTM playbook (bottleneck → 具体アクション) -----------------------------
(def ^:private gtm-playbook
  {:acquisition "landing の価値提案/CTA と価格ページの A/B、SEO・技術コンテンツ、既存導線からの招待"
   :activation  "onboarding の摩擦削減（signup→checkout 最短化）・価格/tier の明確化・空状態の初期価値提示"
   :revenue     "trial→paid の nudge（使用量到達通知）・価格 tier 見直し・年額/上位 tier の提示"})

(defn- pct [r] (when r (str (Math/round (* 100.0 (double r))) "%")))
(defn- block-id [product suffix] (keyword (str (name product) "." suffix)))

(defn- block-items-set
  "Current canvas items for a block as a set (advisor-side dedup).
   gate.cljc と同一 shape — fresh idx / 未定義 block は #{}。"
  [idx block-id]
  (let [block (get-in idx [:blocks block-id])]
    (if block (set (:canvas/items block)) #{})))

(defn proposals
  "Governor-ready proposals that advance the sales/marketing motion:
   - bottleneck step below benchmark → GTM action into the channels block (dedup'd)
   - funnel snapshot → observation into the metrics block (dedup'd)
   - unmeasured stage → 計器 to-do into the solution block (dedup'd)"
  [idx product metrics]
  (let [spec (get funnel-specs product)]
    (if-not spec
      []
      (let [{:keys [stages steps bottleneck missing]} (evaluate-funnel metrics spec)
            metrics-items (block-items-set idx (block-id product "metrics"))
            channels-items (block-items-set idx (block-id product "channels"))
            solution-items (block-items-set idx (block-id product "solution"))
            snapshot (str "funnel (" (name product) "): "
                          (str/join " → " (map (fn [s] (str (:label s) "=" (or (:count s) "?"))) stages))
                          (when (seq steps)
                            (str " | 転換 "
                                 (str/join " / " (keep (fn [st] (when (:rate st)
                                                                  (str (:from-label st) "→" (:to-label st) " " (pct (:rate st)))))
                                                       steps)))))]
        (concat
         ;; funnel snapshot (when measurable) — dedup'd against metrics block
         (when (and (some :count stages) (not (contains? metrics-items snapshot)))
           [{:proposal/action :canvas/add-item
             :canvas/id (block-id product "metrics")
             :event/value snapshot
             :proposal/reason "sales/marketing funnel スナップショット — 運用指標に反映"}])
         ;; bottleneck → GTM action — dedup'd against channels block
         (let [gtm-txt (when bottleneck
                         (str "GTM (" (name (:from bottleneck)) "→" (name (:to bottleneck)) "): "
                              (:from-label bottleneck) "→" (:to-label bottleneck) " 転換 "
                              (pct (:rate bottleneck)) " < 目標 " (pct (:benchmark bottleneck))
                              " — " (get gtm-playbook (:to bottleneck) "獲得施策を検討")))]
           (when (and bottleneck (not (contains? channels-items gtm-txt)))
             [{:proposal/action :canvas/add-item
               :canvas/id (block-id product "channels")
               :event/value gtm-txt
               :proposal/reason "funnel bottleneck（benchmark 未達の転換段）に GTM アクションを提案"}]))
         ;; unmeasured stages → instrument to-do — dedup'd against solution block
         (for [k missing
               :let [st (first (filter #(= (:key %) k) stages))
                     kiage-txt (str "計器 (funnel): " (:label st) " の計測（funnel emitter で " (str/join "/" (map name (:metric st))) " を出力）")]
               :when (not (contains? solution-items kiage-txt))]
           {:proposal/action :canvas/add-item
            :canvas/id (block-id product "solution")
            :event/value kiage-txt
            :proposal/reason "funnel 段の計測が未整備 — 計器を準備項目として提案"}))))))

(defn render-text
  "Human-readable funnel report for `funnel show`."
  [product metrics]
  (let [spec (get funnel-specs product)]
    (if-not spec
      (str (name product) ": funnel-spec 未定義")
      (let [{:keys [stages steps bottleneck missing]} (evaluate-funnel metrics spec)]
        (str/join "\n"
          (concat
           [(str "── " (name product) " sales/marketing funnel ──")]
           (map (fn [s] (str "  " (:label s) ": " (or (:count s) "(未計測)"))) stages)
           ["  ─ 転換 ─"]
           (map (fn [st]
                  (str "  " (:from-label st) " → " (:to-label st) ": "
                       (if (:rate st) (pct (:rate st)) "(未計測)")
                       (when (:benchmark st) (str " (目標 " (pct (:benchmark st)) ")"))
                       (when (and (:gap st) (neg? (:gap st))) " ⚠ bottleneck 候補")))
                steps)
           (when bottleneck
             [(str "  ▸ bottleneck: " (:from-label bottleneck) "→" (:to-label bottleneck)
                   " " (pct (:rate bottleneck)) " < 目標 " (pct (:benchmark bottleneck)))])
           (when (seq missing)
             [(str "  ▸ 未計測段: " (str/join ", " (map name missing)))])))))))
