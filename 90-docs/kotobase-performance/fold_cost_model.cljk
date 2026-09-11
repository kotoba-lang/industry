(ns kotobase-performance.fold-cost-model
  "Pure cost model for write-triggered AppView fold strategies (app-aozora-pds
  / kotobase-peer), grounded in REAL measurements from a live production
  incident + a controlled 300-write burst (2026-07-19, ADR-2607199970 + its
  addendum). Used by `kotobase-performance.fold-cosci` (the co-scientist
  tournament, mirrors `cloud-murakumo.cosci`'s structure over
  `cloud-murakumo.sqrt-space`'s cost model — see that ns for the pattern this
  ns imitates; DO NOT reinvent the tournament shape, only this domain model
  is new).

  Ground truth this model is calibrated against (not invented constants):
    - FOLD_MAX_NOVELTY (cron) = 200 tx-blocks/tick, tick = 5min = 300s.
    - Observed: even a 200-bounded cron fold failed with `fold 503: error
      code 1102` (CPU time exceeded) under real load on 2026-07-19 — i.e.
      the SAFE fold size right now, under backend strain, is measurably
      SMALLER than 200, not just theoretically bounded by it.
    - Observed: 300 writes over ~1689s (0.178 writes/sec, itself abnormally
      slow — average 5.6s/write) at WRITE_FOLD_SAMPLE_RATE=0.1 produced 38
      \"waitUntil() cancelled\" warnings (most write-triggered folds never
      completed) AND `ConcurrentWriteConflict: head CAS lost the race 8
      times` on the CRON's own fold — i.e. concurrent fold attempts
      (write-triggered + cron) contend for one graph's head pointer.
    - The original incident (kawaraban+kouhou, 2026-07-19 earlier same day):
      ~675 writes over ~15min = ~0.75 writes/sec, cron-only (pre-fix), novelty
      grew unbounded for the whole burst, ~20min to recover once the fix's
      predecessor state (no write-trigger at all) let the cron catch up over
      ~4 ticks.
    - `kotobase-server/src/kotobase/server/handler.cljc`'s `do-transact`
      (the reference orchestration app-aozora-pds's actual — source-lost —
      backend was extracted from, per net-kotobase/kotobase-cf-wasm's own
      comments) returns `:novelty_size` in EVERY transact response, for
      free, no extra round-trip. `kotobase.client/transact` (the cljs client
      app-aozora-pds actually calls) returns the raw parsed body unmodified
      — so IF the live backend still matches this reference shape, a
      caller already holding a transact response can read the real novelty
      count at zero marginal cost. NOT YET CONFIRMED against the live
      (source-lost) backend — the tournament below treats this as a
      candidate hypothesis with a graceful degrade, not an assumed fact."
  (:require [clojure.string :as str]))

;; ---- portable math -----------------------------------------------------------

(defn- pow [base exp]
  #?(:clj  (Math/pow (double base) (double exp))
     :cljs (js/Math.pow base exp)))

;; ---- calibration constants (measured, not guessed) ---------------------------

(def measured
  "Every number here traces to a specific observation in ADR-2607199970 or its
  addendum — see this ns's docstring. Nothing here is a free-floating guess."
  {:cron-max-novelty 200
   :cron-interval-s 300
   ;; the largest fold size actually OBSERVED to succeed under current strain
   ;; is unknown (only failures were logged) -- conservatively assume the
   ;; cron's 200-bound itself is already past the safe line, and that the
   ;; deployed write-trigger bound (40) is close to a boundary too (it also
   ;; never completed once in the 300-write burst, though at a much smaller
   ;; per-item size than 200).
   :cpu-exceeded-at-novelty 200
   :original-incident-write-rate-per-s 0.75  ; kawaraban+kouhou, ~675 writes/~15min
   :measured-burst-write-rate-per-s 0.178    ; this ns's own 300-write test (~28min)
   :measured-burst-waituntil-cancel-count 38
   :measured-burst-sample-rate 0.1
   :measured-burst-total-writes 300
   :measured-burst-cas-conflicts-on-cron-fold true})

;; derived: empirical P(a write-triggered fold call fails to complete before
;; Cloudflare kills its waitUntil), at the sample rate/backend-strain level
;; actually observed. This is a MEASURED rate, not modeled from queueing
;; theory -- 38 cancellations against an expected ~30 triggers (300 * 0.1)
;; means effectively ~100%+ of attempted write-triggered folds failed to
;; finish at that rate (some of the 38 may be re-tries/other sources, so we
;; floor at 1.0 rather than claim > 100%).
(def empirical-fold-completion-failure-rate-at-01
  "At WRITE_FOLD_SAMPLE_RATE=0.1 under the measured burst's conditions, the
  OBSERVED fraction of triggered write-fold attempts that did NOT complete
  (killed by Cloudflare's waitUntil deadline) before the request ended."
  1.0)

;; ---- Generation: strategy catalog + gene pool ---------------------------------

(def strategy-catalog
  "Closed set of write-fold coordination strategies. `:completion-model` says
  how strategy affects P(a triggered fold completes) relative to the
  measured baseline; `:trigger-precision` says whether a trigger corresponds
  to real backlog need (:none = cron only, :blind = sample regardless of
  actual novelty, :threshold = fires only when novelty_size crosses a real
  threshold IF the backend returns it, else degrades to :blind)."
  {:cron-only
   {:title "Passive cron only (pre-ADR-2607199970 baseline)"
    :trigger-precision :none
    :write-path-cost :zero
    :requires-shared-state? false}
   :sampled
   {:title "Blind probability sampling (ADR-2607199970 as first deployed)"
    :trigger-precision :blind
    :write-path-cost :zero  ; reads no extra field, just Math/random
    :requires-shared-state? false}
   :threshold-if-available
   {:title "novelty_size-threshold trigger, degrade to low-rate sampling if absent"
    :trigger-precision :threshold
    :write-path-cost :zero  ; novelty_size (if present) rides the SAME transact response already received
    :requires-shared-state? false}
   :sampled-with-kv-backoff
   {:title "Sampling + a KV soft-lock so at most one fold is in flight per graph"
    :trigger-precision :blind
    :write-path-cost :small ; one extra KV read/write per candidate trigger
    :requires-shared-state? true}}) ; NOT YET BUILT -- see reflect's hard gate

(def hypothesis-pool
  {:strategy (vec (keys strategy-catalog))
   ;; rate: for :sampled/:sampled-with-kv-backoff, the trigger probability
   ;; per write; for :threshold-if-available, the FALLBACK sampling rate used
   ;; only when novelty_size is absent from the response.
   :rate [0.02 0.05 0.1]
   ;; the fold's own :max-novelty bound (WRITE_FOLD_MAX_NOVELTY)
   :max-novelty [10 20 40 100]
   ;; the threshold at which :threshold-if-available decides to fire, when
   ;; novelty_size IS available (mirrors kotobase-peer's own
   ;; default-fold-threshold = 64, plus lower options given measured strain)
   :threshold [16 32 64]})

(defn generate-candidates
  ([] (generate-candidates hypothesis-pool))
  ([pool]
   (for [strat (:strategy pool)
         rate (:rate pool)
         mn (:max-novelty pool)
         th (:threshold pool)]
     {:strategy strat :rate rate :max-novelty mn :threshold th})))

;; ---- Reflection (hard gates) ---------------------------------------------------

(defn- unbuilt-primitive?
  "True iff the candidate's strategy needs infrastructure that does not exist
  yet in this codebase (mirrors cosci.cljc's :unknown-strategy gate — a
  candidate can be a GOOD idea and still be disqualified from THIS
  tournament's ranking because it isn't implementable today without first
  building something else)."
  [strategy]
  (:requires-shared-state? (get strategy-catalog strategy)))

(defn theoretical-completion-probability
  "Probability a triggered fold finishes, assuming a HEALTHY (not currently
  strained) backend — a function of :max-novelty relative to the known
  CPU-exceeded threshold ONLY. Used by `reflect` (the hard gate): the
  question reflect asks is 'is this design's throughput math sound in
  principle', mirroring cosci.cljc's reflect testing structural correctness
  independent of any one day's measured load — the ORIGINAL incident's
  actual root cause was `:cron-only` failing this even in principle, not a
  today-only degraded-backend artifact."
  [{:keys [max-novelty]}]
  (max 0.5 (- 1.0 (/ (double max-novelty) (double (:cpu-exceeded-at-novelty measured))))))

(defn realistic-completion-probability
  "Probability a triggered fold finishes RIGHT NOW, under the backend strain
  actually measured on 2026-07-19. Calibrated at ONE real data point
  (rate=0.1, max-novelty=40 -> ~0% observed completion, `measured-burst-*`)
  and extrapolated by two levers that point differ on:

    - concurrency pressure: MORE candidate configurations fire MORE often
      (higher :rate) or target a strategy that fires independent of real
      need (:blind) -> more simultaneous in-flight fold attempts -> more
      losing CAS races (the actual measured failure mode, not raw fold
      size — the one calibration point ITSELF used a small max-novelty=40
      and still failed near-100% of the time, which is evidence AGAINST
      size alone being the dominant variable).
    - size pressure: bigger :max-novelty still costs more CPU per attempt,
      a secondary but real factor (the cron's own 200-bound fold failing
      with literal CPU-1102 confirms size matters too, just not alone).

  This is deliberately more pessimistic than `theoretical-completion-
  probability` — it is the ranking signal (`candidate-work`), never the
  hard gate, precisely so a strained-TODAY backend doesn't make every
  candidate structurally 'unsafe' (that would conflate 'this design is
  wrong' with 'the shared backend is currently having a bad day', two
  different problems this ADR chain has already shown are entangled but
  distinct — see the addendum's open questions)."
  [{:keys [strategy rate max-novelty]}]
  (let [precision (:trigger-precision (get strategy-catalog strategy))
        ;; calibration anchor: :sampled, rate=0.1, max-novelty=40 -> ~0.02
        ;; (near-total failure, floored so it's not literally impossible)
        concurrency-pressure (/ rate 0.1)
        size-pressure (/ (double max-novelty) 40.0)
        precision-relief (case precision :threshold 1.4 :blind 1.0 :none 1.0)
        raw (* 0.02 precision-relief
               (/ 1.0 (max 0.3 (Math/sqrt (* concurrency-pressure size-pressure)))))]
    (max 0.01 (min 0.95 raw))))

(defn reflect
  "Hard pass/fail — mirrors cosci.cljc's reflect shape exactly (gates, not
  scores). A failure is disqualified and never enters ranking.

  Gates:
  1. Strategy must exist in the catalog.
  2. Strategy must not require unbuilt shared-state infra (KV soft-lock —
     candidate).
  3. Backlog-growth-safe at the ORIGINAL INCIDENT's write rate (0.75/s):
     effective successful-fold throughput (fires/s * max-novelty *
     completion-probability) must be >= write-rate, for EVERY problem size
     probed, or the graph regresses to the exact failure mode this whole ADR
     chain exists to fix. `:cron-only` fails this at high write rate BY
     DESIGN (it's the documented pre-fix baseline, kept in the pool as the
     honest control)."
  ([candidate] (reflect candidate {}))
  ([{:keys [strategy] :as candidate} {:keys [write-rates] :or {write-rates [0.05 0.2 0.75 2.0]}}]
   (cond
     (nil? (get strategy-catalog strategy))
     {:pass? false :reason :unknown-strategy :candidate candidate}

     (unbuilt-primitive? strategy)
     {:pass? false :reason :requires-unbuilt-infra
      :detail "needs a KV/DO soft-lock this codebase does not have yet — good idea, not runnable today"
      :candidate candidate}

     :else
     (let [p-complete (theoretical-completion-probability candidate)
           checks
           (for [w write-rates
                 :let [trigger-rate-per-s
                       (case (:strategy candidate)
                         :cron-only (/ 1.0 (:cron-interval-s measured))
                         ;; sampled / threshold-if-available: a trigger CAN
                         ;; fire on every write, at :rate probability
                         (* w (:rate candidate)))
                       cron-throughput (/ (double (:cron-max-novelty measured))
                                          (double (:cron-interval-s measured)))
                       write-throughput (* trigger-rate-per-s (:max-novelty candidate) p-complete)
                       total-throughput (+ cron-throughput
                                           (if (= (:strategy candidate) :cron-only) 0.0 write-throughput))]]
             {:w w :throughput total-throughput :safe? (>= total-throughput w)})
           bad (remove :safe? checks)]
       (if (seq bad)
         {:pass? false :reason :backlog-growth-unsafe :failures (vec bad) :candidate candidate
          :p-complete p-complete}
         {:pass? true :candidate candidate :checks (vec checks) :p-complete p-complete})))))

(defn reflect-all
  ([candidates] (reflect-all candidates {}))
  ([candidates opts]
   (let [results (mapv #(reflect % opts) candidates)]
     {:passed (mapv :candidate (filter :pass? results))
      :failed (vec (remove :pass? results))
      :results results})))

;; ---- Ranking (Elo on work-cost) --------------------------------------------

(defn candidate-work
  "Lower is better. Combines:
   - contention-cost: expected wasted CPU from concurrent-fold CAS races,
     proportional to (1 - p-complete) * trigger-rate (more failed-in-flight
     attempts == more collisions for the cron's own fold to lose against,
     the EXACT symptom measured on 2026-07-19).
   - waste-cost: for blind triggers, fold calls fired when there was no real
     backlog need (precision inefficiency) — 0 for :threshold-if-available
     when novelty_size is available, small residual for its sampling
     fallback, 0 by construction for :cron-only (it never over-fires, it
     under-fires, which is already penalized via reflect's hard gate)."
  [{:keys [strategy rate max-novelty] :as candidate} write-rates]
  (let [p-complete (realistic-completion-probability candidate)
        precision (:trigger-precision (get strategy-catalog strategy))]
    (reduce +
            (map (fn [w]
                   (let [trigger-rate (case strategy
                                         :cron-only 0.0
                                         (* w rate))
                         contention-cost (* trigger-rate (- 1.0 p-complete) 10.0)
                         waste-cost (case precision
                                      :blind (* trigger-rate 1.0)
                                      :threshold (* trigger-rate 0.15) ; residual = fallback-sampling share
                                      :none 0.0)]
                     (+ contention-cost waste-cost)))
                 write-rates))))

(defn elo-update
  [ra rb score-a & {:keys [k] :or {k 24}}]
  (let [expected-a (/ 1.0 (+ 1.0 (pow 10 (/ (- rb ra) 400.0))))]
    [(+ ra (* k (- score-a expected-a)))
     (+ rb (* k (- (- 1.0 score-a) (- 1.0 expected-a))))]))

(defn rank
  ([candidates] (rank candidates {}))
  ([candidates {:keys [write-rates draw-tol prior-ratings]
                :or {write-rates [0.05 0.2 0.75 2.0] draw-tol 0.02 prior-ratings {}}}]
   (let [scored (mapv (fn [c] {:candidate c :work (candidate-work c write-rates)}) candidates)
         n (count scored)
         ratings (atom (mapv #(get prior-ratings (:candidate %) 1000.0) scored))]
     (doseq [i (range n) j (range (inc i) n)]
       (let [wi (:work (scored i)) wj (:work (scored j))
             score-i (cond (< wi (* (- 1.0 draw-tol) wj)) 1.0
                           (> wi (* (+ 1.0 draw-tol) wj)) 0.0
                           :else 0.5)
             [ri' rj'] (elo-update (@ratings i) (@ratings j) score-i)]
         (swap! ratings assoc i ri' j rj')))
     (->> (map #(assoc %1 :elo %2) scored @ratings) (sort-by :elo >) vec))))

(defn cluster-by-proximity
  ([ranked] (cluster-by-proximity ranked 0.05))
  ([ranked tolerance]
   (reduce (fn [clusters {:keys [work] :as r}]
             (if-let [cur (peek clusters)]
               (if (<= (Math/abs (- work (:work (first cur))))
                       (* tolerance (max 1.0 (:work (first cur)))))
                 (conj (pop clusters) (conj cur r))
                 (conj clusters [r]))
               [[r]]))
           [] ranked)))

;; ---- Evolution ---------------------------------------------------------------

(defn evolve-round
  ([ranked] (evolve-round ranked hypothesis-pool 4))
  ([ranked pool elite-n]
   (let [elites (mapv :candidate (take elite-n ranked))
         strategies (distinct (map :strategy elites))
         rates (distinct (map :rate elites))
         mns (distinct (map :max-novelty elites))
         ths (distinct (map :threshold elites))
         crossed (for [s strategies r rates m mns t ths] {:strategy s :rate r :max-novelty m :threshold t})
         top (first elites)
         mutants (concat
                  (for [s (remove (set strategies) (:strategy pool))] (assoc top :strategy s))
                  (for [r (remove (set rates) (:rate pool))] (assoc top :rate r))
                  (for [m (remove (set mns) (:max-novelty pool))] (assoc top :max-novelty m))
                  (for [t (remove (set ths) (:threshold pool))] (assoc top :threshold t)))]
     (vec (distinct (concat elites crossed mutants))))))
