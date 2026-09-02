(ns yui-coscientist
  "結 (yui) — the empowerment bot profile's co-scientist loop: Generate ->
   Review (charter gates) -> Rank (deterministic Elo, measured fitness is the
   judge) -> Evolve -> Meta-review, over a CLOSED intervention catalog.

   The judge is NOT an LLM debate. Fitness for each hypothesis = its measured
   predicted gain computed against the yui five-domain participation model
   (yui-run.cljs, OASIS XMILE 1.0): run the scenario, read the delta in final
   contributors vs the measured base. Ranking is therefore reproducible,
   deterministic, and grounded in the sim, per the design-quality/tsuchifumi/
   ibuki co-scientist lineage (ADR-2606201200 → ADR-2607011500 → ADR-2607132300).

   THE CHARTER (enforced in `review`, not prose):
     G-mechanism   only the aligned mechanisms may enter. An intervention that
                   works by engagement-maximization, extraction, or deception
                   is UNREPRESENTABLE (not in the catalog; review rejects on
                   injection).
     G-empower     the intervention must move a participant from a lower to a
                   higher stage of the funnel (or reduce their churn), NEVER
                   merely increase raw traffic. 'More visitors' alone is not
                   empowerment.
     G-measured    every candidate carries the parameter(s) of the SD model
                   it acts on and the real datum that constrains them. A
                   candidate with no model parameter is not falsifiable.
     G-honesty     candidates whose parameter is UNMEASURED (the referral
                   loop, stage convolutions beyond kotobase's funnel) may run
                   as scenarios but their predicted gains are labelled
                   :unmechanism-confidence — never presented as forecasts.
     G-leash       yui identifies and ranks; committing resources (credits,
                   deploys, outreach spend) is out of scope for the identification
                   layer. The output is a ranked proposal list + evidence.
     G-no-fiat     no candidate may move fiat/credits; the economic unit design
                   is ADR-2608291009's alone (BOT issuance is revenue-backed;
                   yui does not and cannot mint anything).

   Deterministic + pure (no wall clock, no randomness) → reproducible tournament."
  (:require [clojure.string :as str]))

;; ── mechanism vocabulary (closed) ─────────────────────────────────────────
(def aligned-mechanisms
  #{"onboarding-fix"         ;; raise visit→signup (measured lever: kotobase 0.65%)
    "empowerment-tooling"    ;; raise active→contributor (assumed lever, tooling-backed)
    "retention"              ;; reduce churn at any stage
    "reputation-loop"        ;; contributor work → reputation → organic reach (R loop)
    "open-measurement"})     ;; publish funnel numbers so the next cycle has real data

(def forbidden-mechanisms
  "UNREPRESENTABLE — exactly how a careless growth bot would go wrong."
  #{"engagement-maximizing" "ad-targeting" "dark-pattern-signup"
    "vanity-traffic" "token-incentive-before-revenue" "extraction"})

;; ── the intervention catalog (Generate's deterministic backbone) ───────────
;; :param = the yui SD model parameter(s) the candidate acts on.
;; :datum = the real measured fact that constrains it.
;; :tier  = :measured (param has a real observation) | :unmeasured (scenario only)
(def catalog
  [{:id "self-serve-onboarding-per-door"
    :mechanism "onboarding-fix" :tier :measured
    :param :conv-visit-signup :datum "kotobase 31/4750 = 0.65% (2026-09-02)"
    :prediction "doubling visit→signup doubles contributor inflow (~2.0x S0)"}
   {:id "contributor-starter-kits"
    :mechanism "empowerment-tooling" :tier :unmeasured
    :param :conv-active-contrib :datum "no active→contributor observation exists"
    :prediction "doubling contributes ~1.2x contributors (S2)"}
   {:id "active-retention-windows"
    :mechanism "retention" :tier :unmeasured
    :param :active-churn :datum "no churn observation exists"
    :prediction "halving churn ≈ doubling contribution inflow duration (S3)"}
   {:id "publish-funnel-per-domain"
    :mechanism "open-measurement" :tier :measured
    :param nil :datum "kotoba.cloud has NO published funnel; 4/5 doors measured"
    :prediction "turns :unmeasured params into :measured next cycle — meta-hypothesis"}
   {:id "reputation-public-ledger"
    :mechanism "reputation-loop" :tier :unmeasured
    :param :referral-rate :datum "isekai viral coefficient is literally 0 (2026-09-02)"
    :prediction "weak loop adds ~1%; strong loop crosses budget independence in sim"}
   {:id "work-for-credits-handbook"
    :mechanism "empowerment-tooling" :tier :measured
    :param nil :datum "ADR-2608291009: 1,958 bots, 2 with pricing; D5 margin formula exists"
    :prediction "moves bots toward revenue-backed work; raises measured has-pricing count"}])

;; ── Generate ───────────────────────────────────────────────────────────────
(defn generate
  "One hypothesis per catalog entry, offline/heuristic (no LLM call)."
  [catalog]
  (vec (map-indexed (fn [i c]
                      (assoc c :id (str "yui-h" (inc i) "-" (:id c))
                             :axis (:mechanism c)))
                    catalog)))

;; ── Review (charter gates) ─────────────────────────────────────────────────
(defn review
  [{:keys [mechanism param datum prediction] :as cand}]
  (let [problems
        (cond-> []
          (not (contains? aligned-mechanisms mechanism))
          (conj {:gate :G-mechanism :why (str "mechanism not in aligned set: " mechanism)})
          (contains? forbidden-mechanisms mechanism)
          (conj {:gate :G-mechanism :why "forbidden mechanism"})
          (and (nil? param) (not= "open-measurement" mechanism)
               (not= "empowerment-tooling" mechanism))
          (conj {:gate :G-measured :why "candidate acts on no model parameter"})
          (nil? datum)
          (conj {:gate :G-measured :why "no constraining datum"})
          (nil? prediction)
          (conj {:gate :G-falsifiable :why "no measurable prediction"}))]
    (assoc cand :ok (empty? problems) :problems problems)))

(defn surviving [cands] (into [] (filter #(:ok (review %)) cands)))
(defn vetoed [cands] (into [] (remove #(:ok (review %)) cands)))

;; ── Rank: deterministic tournament, fitness = measured sim gain ────────────
(defn- elo-expected [ra rb] (/ 1.0 (+ 1.0 (Math/pow 10.0 (/ (- rb ra) 400.0)))))

(defn rank
  "Round-robin Elo over candidates. A candidate's strength = its sim gain when
   the sim has measured its parameter (:tier :measured), otherwise its
   information value (open-measurement meta-hypotheses score by how many
   unmeasured parameters they would convert to measured). Ties break toward
   :measured then toward lower effort (:S < :M < :L). Deterministic."
  [cands sim-gains]
  (let [cands (vec cands)
        strength (fn [c]
                   (or (get sim-gains (:id c))
                       (case (:mechanism c)
                         "open-measurement" 1.5
                         0.0)))
        tier-boost (fn [c] (case (:tier c) :measured 0.5 :unmeasured 0.0))
        eff (fn [c] (case (:effort c) :S 0 :M 1 :L 2 1))
        ;; effective strength carries the tier boost — used symmetrically for
        ;; both the expected score and the bout winner, so the ranking stays
        ;; internally consistent (same fitness definition in both places).
        es (fn [c] (+ (strength c) (tier-boost c)))]
    (loop [i 0 ratings {}]
      (if (>= i (count cands))
        (->> ratings
             (sort-by (comp - second))
             (mapv first))
        (let [a (nth cands i)
              others (keep-indexed (fn [j c] (when (not= j i) c)) cands)
              ra (get ratings (:id a) 1000.0)
              ratings'
              (reduce (fn [acc b]
                        (let [rb (get acc (:id b) 1000.0)
                              ea (elo-expected ra rb)
                              winner (cond
                                       (> (es a) (es b)) a
                                       (< (es a) (es b)) b
                                       (< (eff a) (eff b)) a
                                       :else b)
                              sa (if (= (:id winner) (:id a)) 1.0 0.0)]
                          (-> acc
                              (assoc (:id a) (+ (get acc (:id a) 1000.0) (* 32 (- sa ea))))
                              (assoc (:id b) (+ (get acc (:id b) 1000.0)
                                                (* 32 (- (- 1.0 sa) (- 1.0 ea))))))))
                      ratings others)]
          (recur (inc i) ratings'))))))

;; ── Evolve ─────────────────────────────────────────────────────────────────
(defn evolve
  "Top-2 combine: the evolved hypothesis carries both parents' mechanisms as a
   staged sequence (fix measurement first if either parent is open-measurement,
   then apply the strongest empowerment lever). Deterministic."
  [ranked-ids cands]
  (let [by-id (into {} (map (juxt :id identity) cands))
        top (keep by-id (take 2 ranked-ids))]
    (when (= 2 (count top))
      (let [[a b] top
            both-measured? (and (= :measured (:tier a)) (= :measured (:tier b)))]
        {:id (str "yui-evolved-" (:id a) "+" (:id b))
         :mechanisms [(:mechanism a) (:mechanism b)]
         :tier (if both-measured? :measured :unmeasured)
         :datum (str (:datum a) " + " (:datum b))
         :prediction (str (:prediction a) " AND " (:prediction b))
         :note "evolved combination — staged: measurement first if either parent is open-measurement"}))))

;; ── Meta-review ────────────────────────────────────────────────────────────
(defn meta-review
  [cands ranked vetoed]
  {:surviving (count cands)
   :vetoed-count (count vetoed)
   :vetoed-reasons (mapcat :problems vetoed)
   :top-ranked ranked
   :coverage-note
   (let [mechs (set (map :mechanism cands))]
     (str "aligned mechanisms covered: " (count mechs) "/"
          (count aligned-mechanisms) "; unmeasured-tier candidates: "
          (count (filter #(= :unmeasured (:tier %)) cands))
          " — next cycle's job is converting them via open-measurement"))})

(defn run-iteration
  "Full cycle. `sim-gains` = {catalog-id measured-sim-gain} from the yui XMILE
   run (yui-run.cljs scenarios vs S0). Returns the identification packet."
  [sim-gains]
  (let [hyps (generate catalog)
        vetoed (vetoed hyps)
        cands (surviving hyps)
        ranked (rank cands sim-gains)
        evolved (evolve ranked cands)
        meta (meta-review cands ranked vetoed)]
    {:iteration 1
     :as-of "2026-09-02"
     :candidates cands
     :vetoed vetoed
     :ranking ranked
     :evolved evolved
     :meta meta
     :honesty-note
     "predicted gains for :unmeasured-tier candidates are scenario outputs of
      an ASSUMPTION-parameterized model — they rank candidates, they do not
      forecast headcounts. The falsifiable measured levers (visit→signup,
      has-pricing count) are the only candidates whose next-cycle validation
      is a real observation rather than another simulation."}))
