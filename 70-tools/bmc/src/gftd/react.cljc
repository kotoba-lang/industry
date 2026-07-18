(ns gftd.react
  "進化成長 ReAct loop over the lean canvases (ADR-2607021600).

   CLAUDE.md Actors パターン準拠:
   - advisor（知能ノード、mock ‖ LLM=langchain.model を注入）は *proposal のみ* 返す
   - 独立した governor が全 proposal を検閲する — 単一不変条件:
     「governor が拒否する書込を actor は決して行わない」
   - 可決/拒否とも append-only ledger（gftd.ledger）に積む
   - 1 run = 1 tick（有界）。長期の進化は durable outer loop（tick の反復 + budget）

   ReAct: observe（canvas + hypotheses + metrics）→ think（advisor proposals）
          → act（governor 検閲 → events）→ 次 tick は folded canvas を観測。"
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn]
               :cljs [cljs.reader])
            [gftd.canvas :as canvas]
            [gftd.gate :as gate]))

;; ---- observe -----------------------------------------------------------------

(defn observe
  "Read-only view of one product's canvas for the advisor."
  [idx product metrics]
  (let [hyps (canvas/product-hyps idx product)]
    {:product     product
     :layer       (canvas/product-layer idx product)
     :blocks      (into {} (map (juxt :canvas/id (comp vec :canvas/items))
                                (canvas/product-blocks idx product)))
     :hyps        (mapv #(select-keys % [:hyp/id :hyp/risk :hyp/status :hyp/claim :hyp/gate]) hyps)
     :untested    (mapv :hyp/id (filter #(= :untested (:hyp/status %)) hyps))
     :refuted     (mapv :hyp/id (filter #(= :refuted (:hyp/status %)) hyps))
     :validated   (mapv :hyp/id (filter #(= :validated (:hyp/status %)) hyps))
     :metrics     (or metrics {})}))

;; ---- think (advisor — proposal のみ) --------------------------------------------

(defn- block-id [product block] (keyword (str (name product) "." block)))

(defn mock-advisor
  "Deterministic advisor. Rules are written to converge: each proposal is
   deduped against current canvas items, so a repeated loop goes dry.
   Swap point for an LLM advisor: any (fn [observation] proposals) works."
  [{:keys [product blocks hyps untested refuted metrics]}]
  (let [items-of (fn [suffix] (set (get blocks (block-id product suffix))))
        hyp-of   (fn [id] (some #(when (= id (:hyp/id %)) %) hyps))]
    (concat
     ;; untested riskiest hypothesis → その gate を key-metrics に「次の検証」として掲げる
     (for [hid untested
           :let [h (hyp-of hid)
                 text (str "次の検証 (" (name hid) "): " (:hyp/gate h))]
           :when (not (contains? (items-of "metrics") text))]
       {:proposal/action :canvas/add-item
        :canvas/id (block-id product "metrics")
        :event/value text
        :proposal/reason "riskiest 仮説が未検証 — gate を運用指標に昇格"})
     ;; refuted hypothesis → UVP block に pivot 検討 note
     (for [hid refuted
           :let [h (hyp-of hid)
                 text (str "pivot 検討 (" (name hid) " 棄却): " (:hyp/claim h))]
           :when (not (contains? (items-of "uvp") text))]
       {:proposal/action :canvas/add-item
        :canvas/id (block-id product "uvp")
        :event/value text
        :proposal/reason "仮説棄却 — UVP の前提を再設計"})
     ;; metrics に :signal があれば problem block に観測事実として提案
     (for [[k v] metrics
           :let [text (str "観測 (" (name k) "): " v)]
           :when (and (= k :signal) (not (contains? (items-of "problem") text)))]
       {:proposal/action :canvas/add-item
        :canvas/id (block-id product "problem")
        :event/value text
        :proposal/reason "実測 metric を課題仮説へ反映"})
     ;; :top-paths (user が実際にアクセスしている page の内訳、collect.cljs 2026-07-09)
     ;; は「どの導線が生きているか」の実測なので channels block へ反映
     (for [[k v] metrics
           :let [text (str "観測 (paths): " v)]
           :when (and (= k :top-paths) (not (contains? (items-of "channels") text)))]
       {:proposal/action :canvas/add-item
        :canvas/id (block-id product "channels")
        :event/value text
        :proposal/reason "実測 page アクセス内訳を獲得チャネルの観測として反映"}))))

(defn gate-aware-advisor
  "Default advisor: mock-advisor + gate evaluator (ADR-2607022100).
   The gate step is what makes the schedule cycle — it auto-advances validated
   hypotheses and surfaces each product's missing instrument as a 準備 proposal.
   Needs :idx + :product in the observation (tick supplies them)."
  [{:keys [idx product] :as obs}]
  (concat (mock-advisor obs)
          (when (and idx product) (gate/proposals idx product (:metrics obs)))))

(defn llm-advisor
  "LLM-backed advisor seam (ADR-2607022100). `complete` is an injected
   (fn [prompt-string] -> string) — e.g. langchain.model / murakumo text. The
   model is asked to return an EDN vector of proposals; malformed output yields
   no proposals (fail-safe, governor is the backstop anyway). Compose with
   gate-aware-advisor at the call site for both novelty and gate progression."
  [complete]
  (fn [obs]
    (let [prompt (str "あなたは lean canvas の advisor。以下の観測に対し、canvas を"
                      "前進させる proposal を EDN ベクタで *だけ* 返せ。各要素は"
                      " {:proposal/action :canvas/add-item :canvas/id <product>.<block>"
                      " :event/value \"...\" :proposal/reason \"...\"}。block は"
                      " problem/uvp/solution/channels/metrics/unfair 等。観測:\n"
                      (pr-str (select-keys obs [:product :layer :blocks :hyps :metrics])))
          out (try (complete prompt) (catch #?(:clj Exception :cljs :default) _ nil))]
      (try
        (let [v (#?(:clj clojure.edn/read-string :cljs cljs.reader/read-string) (str out))]
          (if (vector? v) (vec (filter map? v)) []))
        (catch #?(:clj Exception :cljs :default) _ [])))))

(defn compose-advisors
  "Concatenate proposals from multiple advisors (gate then LLM, etc.)."
  [& advisors]
  (fn [obs]
    (into [] (mapcat (fn [a] (or (a obs) [])) advisors))))

;; ---- act (governor — 検閲) ------------------------------------------------------

(def allowed-actions #{:canvas/add-item :canvas/retract-item :canvas/note :hyp/status})

(defn governor
  "Independent censor. Returns {:approved [...] :rejected [{:proposal … :reason …}]}.
   Invariants:
   - action は allowed-actions のみ
   - value は非空 ≤300 chars
   - 重複 item は追加しない / block の最後の 1 item は retract しない
   - :hyp/status 変更は :event/evidence 必須
   - etzhayyim（非営利）の Funding block へ営利項目（take rate/広告/carry 等)を入れない"
  [idx proposals]
  (reduce
   (fn [acc p]
     (let [reject #(update acc :rejected conj {:proposal p :reason %})
           approve #(update acc :approved conj p)
           id (:canvas/id p)
           block (get-in idx [:blocks id])
           items (set (:canvas/items block))
           v (:event/value p)]
       (cond
         (not (contains? allowed-actions (:proposal/action p)))
         (reject "action not allowed")

         (and (contains? #{:canvas/add-item :canvas/retract-item :canvas/note} (:proposal/action p))
              (nil? block))
         (reject (str "unknown canvas id " id))

         (and (string? v) (or (str/blank? v) (> (count v) 300)))
         (reject "value blank or >300 chars")

         (and (= :canvas/add-item (:proposal/action p)) (contains? items v))
         (reject "duplicate item")

         (and (= :canvas/retract-item (:proposal/action p)) (<= (count items) 1))
         (reject "cannot retract the last item of a block")

         (and (= :hyp/status (:proposal/action p)) (str/blank? (str (:event/evidence p))))
         (reject "hyp status change requires evidence")

         (and (= :canvas/add-item (:proposal/action p))
              (= :etzhayyim.revenue id)
              (re-find #"(?i)take ?rate|広告|ads?\b|carry|課金" (str v)))
         (reject "non-profit invariant: etzhayyim funding must stay 非営利")

         :else (approve))))
   {:approved [] :rejected []}
   proposals))

(defn proposal->event [tick actor p]
  (-> p
      (dissoc :proposal/action :proposal/reason)
      (assoc :event/type (:proposal/action p)
             :event/actor actor
             :event/reason (:proposal/reason p)
             :event/tick tick)))

;; ---- tick / durable outer loop ----------------------------------------------------

(defn tick
  "One bounded ReAct step for one product. Pure: returns
   {:observation … :proposals … :approved … :rejected … :events […] :idx folded-idx}.
   The caller persists :events (ledger) — this fn never does io."
  [{:keys [idx product metrics advisor actor tick-n]
    :or {advisor gate-aware-advisor actor "advisor:gate" tick-n 1}}]
  (let [obs (assoc (observe idx product metrics) :idx idx :product product)
        proposals (vec (advisor obs))
        {:keys [approved rejected]} (governor idx proposals)
        approved-events (mapv #(proposal->event tick-n actor %) approved)
        events (vec
                (concat
                 [{:event/type :react/observation :event/actor actor :event/tick tick-n
                   :event/value {:product product
                                 :hyp-status (frequencies (map :hyp/status (:hyps obs)))
                                 :proposals (count proposals)}}]
                 approved-events
                 (for [{:keys [proposal reason]} rejected]
                   {:event/type :governor/rejected :event/actor "governor" :event/tick tick-n
                    :event/value (select-keys proposal [:proposal/action :canvas/id :event/value])
                    :event/reason reason})))]
    {:observation obs :proposals proposals :approved approved :rejected rejected
     :events events
     :idx (canvas/fold idx approved-events)}))

(defn run-ticks
  "Durable outer loop, bounded by :max-ticks (budget). Stops early when a tick
   produces no proposals (dry). Returns {:ticks [tick-result…] :idx final-idx}."
  [{:keys [idx product metrics advisor actor max-ticks] :or {max-ticks 5} :as opts}]
  (loop [n 1 idx idx acc []]
    (if (> n max-ticks)
      {:ticks acc :idx idx}
      (let [r (tick (assoc opts :idx idx :tick-n n))]
        (if (empty? (:proposals r))
          {:ticks (conj acc r) :idx idx :dry? true}
          (recur (inc n) (:idx r) (conj acc r)))))))
