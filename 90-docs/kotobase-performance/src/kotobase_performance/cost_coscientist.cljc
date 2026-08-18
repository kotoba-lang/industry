(ns kotobase-performance.cost-coscientist
  "kotobase cost Co-Scientist — Generate -> Reflect -> Rank (Elo) -> Evolve -> Meta,
   ported from `design-quality.coscientist` (which was itself ported from
   isekai.ux.coscientist, ADR-0007) and pointed at a different question.

   The judge is `cost-per-million`, not an LLM. That is the property the pattern
   turns on: the ranking is reproducible from measured resource quantities and
   published prices, so re-running it after a measurement changes reorders the
   roadmap by itself rather than by anyone's recollection of which change felt
   biggest.

   Why this question needs it. The owner's criterion is cost against other graph
   databases. Every priority list produced today was ordered by intuition, and
   intuition was wrong at least twice: the engine work looked important until
   the engine turned out to be ~6 ms of a ~2,800 ms request, and the read-path
   fix looked like a latency fix until its byte reduction turned out to be the
   larger effect. A deterministic fitness function does not have opinions.

   Pure .cljc, no I/O beyond what the caller passes."
  (:require [clojure.string :as str]))

;; ── prices ───────────────────────────────────────────────────────────────────
;; Captured 2026-08-17 from developers.cloudflare.com. Prices are DATA, not
;; constants baked into logic: kotobase-graph-database/PROMOTION.md already
;; requires pricing to be captured at run time with its source, for the reason
;; that a price embedded in code silently becomes a lie.

(def prices
  {:source "developers.cloudflare.com, fetched 2026-08-17"
   :usd-per-million-requests 0.30
   :usd-per-million-cpu-ms 0.02
   :usd-per-million-class-b 0.36
   :r2-egress "free"})

(defn cost-per-million
  "THE JUDGE. Dollars per million served reads, from measured quantities.

  Deliberately excludes storage: it is the same under every hypothesis here,
  so including it would shrink every difference by a constant and flatter
  whichever change is being argued for."
  [{:keys [cpu-ms class-b-ops]}]
  (+ (:usd-per-million-requests prices)
     (* (:usd-per-million-cpu-ms prices) cpu-ms)
     (* (:usd-per-million-class-b prices) class-b-ops)))

;; ── the measured CPU term ────────────────────────────────────────────────────
;; iteration-01 closed with: "Close the CPU uncertainty first; it is the input
;; every ranking rests on and the only one that cannot be read from inside the
;; isolate." The second clause was right and the conclusion drawn from it was
;; wrong. Nothing INSIDE the isolate can read cpuTime — but `wrangler tail`
;; reads it from outside, per request, and always could.

(def measured-cpu
  "Production cpuTime for a hydrating request, read from `wrangler tail`.

  The probe is a public-read surface, so this needs no credential: atproto is
  in `core/public-read-surfaces`, and
  `GET atproto.kotobase.net/xrpc/com.atproto.repo.describeRepo?repo=x`
  performs the full shared-graph hydrate.

  It answers 501 MethodNotImplemented, and that is expected: the hydrate runs in
  the router BEFORE nsid dispatch, so the request does the full hydrate and then
  declines the method. Verified 2026-08-18 on ONE connection (curl --next):
  probe 501 in 3.9 s, then /_diag/hydrate reporting sampled true, block_gets 84,
  bytes 11462579. Separate connections land on different isolates and report
  sampled false, which is not evidence of anything. That matters — the sparql and cypher
  surfaces return 401 to a self-issued CACAO, so a measurement that needed
  them would have needed an operator credential, and would have stalled here.

  Taken 2026-08-18 against the deployed worker, which by then carried the
  read-path hoist and the fetch coalescing. All ten outcomes `ok`."
  {:cpu-ms [2411 2539 2133 2410 2491 2178 2384 2114 2267 3689]
   :wall-ms [4003 3810 3215 3379 3355 2714 2858 2656 2759 5313]
   :n 10 :median 2397 :mean 2462 :min 2114 :max 3689
   :cpu-over-wall "60-83%"
   :prior "2026-08-17, pre-fix, n=14: 2329-4416"
   :superseded-by
   "2026-08-18, AFTER the native outer-string decode was deployed (version
    120f3e08): n=10, median 2058, mean 2026, min 1422, max 2583. Same probe,
    same method. That is the number `baseline` now carries."
   :how "wrangler tail kotobase-protocols-worker --format json, matched to each
         request by a ?probe=<uuid> marker in the URL."})

(def workerd-scale
  "measured median / Node proxy = 2397/1976.

  The harness measures the DECOMPOSITION reliably — which half of the hydrate
  costs what — but its absolute level is a Node proxy for a quantity Cloudflare
  bills. So the total is anchored to the measurement and the measured
  proportions are preserved.

  This makes every `:after` a MODELLED PROJECTION, not a measurement, and each
  hypothesis keeps its raw `:after-harness` so the correction stays auditable.
  The proxy understated by 21%, not the several-fold iteration-01 feared."
  (/ 2397 1976))

;; ── the measured baseline ────────────────────────────────────────────────────

(def cold-isolate-rate
  "How often a HYDRATING request finds L1 empty. Measured, and it is not 1.

  Ten paired probes on 2026-08-18, each on its own connection
  (`describeRepo?repo=x` to hydrate, then `/_diag/hydrate` on the SAME
  connection so the diagnostic reports the isolate the probe actually used):

      cold  84 gets -> R2   n=6   wall 3.09 3.11 3.14 3.54 3.82 4.92  median 3.34
      warm  84 gets -> L1   n=4   wall 2.24 2.29 2.47 3.11             median 2.38

  6/10. Two things follow, and they point opposite ways.

  **The baseline's `:class-b-ops 84` came from ONE sample and is the COLD
  case.** The measured mix is 84 * 0.6 = 50.4 gets per served read, so the
  class-B term was overstated by 40% and every hypothesis that removes gets was
  credited with removing more than exist.

  **And the rate is confounded by the probe.** Ten requests in ~35 seconds warm
  the isolates that the next request may land on, so 0.6 is an upper bound on
  coldness under back-to-back traffic and a LOWER bound on what an idle graph
  would show. This graph has no organic traffic at all (twelve consecutive
  `/_diag/hydrate` reads on the same day found twelve isolates that had never
  hydrated), so its real regime is nearer 1.0 than 0.6.

  Carried as a parameter rather than folded into the numbers, because the two
  regimes give different answers and the loop should say which one it priced.

  The wall-clock difference is the same 84 gets seen from outside: ~1.0 s of
  the ~3.3 s cold request. It is not in the judge -- Cloudflare bills CPU, not
  wall -- and it is the reason a user-visible latency argument and a cost
  argument can disagree about this tier."
  0.6)

(def baseline
  "What one served read costs, as of 2026-08-18.

  COUNTS are live from GET /_diag/hydrate against production. CPU is now live
  too, from `wrangler tail` — the term iteration-01 called the loop's dominant
  uncertainty is measured, and this loop now prices as well as it ranks.

  What remains unmeasured is not the rate but the TRAFFIC. Twelve consecutive
  reads of /_diag/hydrate on 2026-08-18 returned `sampled: false` — twelve
  isolates, none of which had ever hydrated. There are no organic served reads
  on this graph. Every figure below is therefore unit economics at hypothetical
  volume, and the crossover is the honest headline, not saved CPU."
  {:cpu-ms 2058                    ; LIVE, wrangler tail, n=10 median, post-120f3e08
   ;; DELIBERATELY ABSENT. The A/B split above was measured against the OLD
   ;; decode2. The native outer-string decode took ~28% off B, so the old split
   ;; no longer describes this baseline, and scaling it would invent a
   ;; decomposition nothing measured. The hypotheses' :after-harness values have
   ;; the same problem and are stale by the same amount -- see :roadmap-caveat.
   :cpu-ms-parts :needs-re-measurement
   :cpu-ms-harness 1976            ; what iteration-01 priced
   ;; MEASURED MIX, not the single sample. 84 is what ONE hydrate costs when it
   ;; finds L1 empty; `cold-isolate-rate` says 6 in 10 do. Pricing every read at
   ;; 84 credits every gets-removing hypothesis with 40% more than is there.
   :class-b-ops (* 84 cold-isolate-rate)
   :class-b-ops-cold-case 84       ; live, one sample, the cold case
   :bytes 11462579                 ; live
   :unfolded-txs 63                ; live
   :seed-bytes 4930593
   :seed-bytes-in-91-inline-docs 4198531
   :cpu-uncertainty
   "CLOSED 2026-08-18. Read per-request from wrangler tail (n=10, median 2397,
    range 2114-3689), and re-read after the outer-layer change (n=10, median
    2058). The residual uncertainty is variance, not method: the max is 1.8x the
    min across ten consecutive requests on one worker."
   :roadmap-caveat
   "Every hypothesis's :after-harness was measured against the OLD decode2. The
    landed change realised part of h7, so those numbers -- and the savings
    derived from them -- OVERSTATE the headroom that is left. Re-run the phase
    bench before treating this ranking as current. Recorded rather than
    silently rescaled: a projection built on a decomposition that no longer
    holds is the thing this loop exists to avoid."
   :sources {:cpu-ms :live-workerd :class-b-ops :live :bytes :live}})

;; ── Generate ─────────────────────────────────────────────────────────────────
;; One hypothesis per candidate mechanism. Each states the resource quantities
;; it would leave behind, and where that number comes from -- a hypothesis whose
;; predicted effect has no provenance cannot be ranked honestly against one that
;; has.

(def ^:private hypotheses
  [{:id "h6-retract-inline-documents"
    :title "Retract the 91 documents carrying inline object bodies"
    :change "91 documents hold 4,198,531 of the seed's 4,930,593 bytes -- 85%.
             They predate ADR-2608061200, and since 2026-08-10 routing sends
             every s3 request for that bucket to its own graph, so on THIS graph
             only the four credential-gated query surfaces can reach them. Every
             request parses them anyway."
    :after-harness {:cpu-ms 810 :class-b-ops :unchanged}
    :basis :measured
    :basis-note "byte accounting of the real seed, 2026-08-17. The cpu figure
                 assumes B falls with the share of values parsed; that
                 proportionality is NOT separately measured."
    :effort :S
    :reversible? false
    :depends-on-write-rate? false
    :note "Retracting production documents is an owner judgement. Not executed."}

   {:id "h7-parse-only-addressable-values"
    :title "Decode the seed without parsing values nobody asked for"
    :change "Keep values as their stored EDN strings and parse only the
             collections the request's surface can address."
    :after-harness {:cpu-ms 519 :class-b-ops :unchanged}
    :basis :measured
    :basis-note "Same CBOR decoded without parsing values: 2.5-6.8 ms against
                 887-919 ms parsed. Removes B; A remains."
    :effort :M
    :reversible? true
    :depends-on-write-rate? false}

   {:id "h1-seed-materialisation"
    :title "Materialise the hydrated seed, keyed by chain CID"
    :change "Serve the seed as one content-addressed object instead of
             rebuilding it from 84 blocks."
    :after-harness {:cpu-ms 1430 :class-b-ops (* 1 cold-isolate-rate)}
    :basis :measured
    :basis-note "Removes A (514 ms) and the 84 gets. B survives ANY encoding,
                 because the consumer is a synchronous LocalStore over a plain
                 Clojure map. The first run of this loop credited h1 with the
                 whole hydrate and ranked it at 98.4%; the decomposition says
                 23% of the CPU."
    :effort :L
    :reversible? true
    :depends-on-write-rate? true}

   {:id "h2-fold"
    :title "Fold the novelty backlog"
    :change "63 of 84 blocks are one tx-block per unfolded transaction."
    :after-harness {:cpu-ms 1738 :class-b-ops (* 21 cold-isolate-rate)}
    :basis :measured
    :basis-note "Fold reduces the NOVELTY part of A, not B. The first run of
                 this loop credited it with 57.5% by using an offline harness
                 row that measured a different quantity."
    :effort :S
    :reversible? true
    :depends-on-write-rate? false
    :note "Deployed and dormant; fires on the next write."}

   {:id "h4-carv2-pack"
    :title "Pack commit-local blocks into CARv2 archives"
    :change "One range GET per commit's blocks instead of one per block."
    :after-harness {:cpu-ms :unchanged :class-b-ops (* 64 cold-isolate-rate)}
    :basis :measured-and-refuted-for-this-shape
    :basis-note "Only the 21 snapshot blocks coalesce; each novelty cell is its
                 own commit and therefore its own pack."
    :effort :L
    :reversible? true
    :depends-on-write-rate? false}

   {:id "h3-engine-materialisation"
    :title "Stop materialising query intermediates"
    :change "~16x of headroom inside the query engine."
    :after-harness {:cpu-ms :unchanged :class-b-ops :unchanged}
    :basis :measured
    :basis-note "The engine is ~6 ms of this request. CPU is :unchanged rather
                 than 1970: a hypothesis that moves ~6 ms of a 2,058 ms request
                 moves nothing this judge can resolve."
    :effort :L
    :reversible? false
    :depends-on-write-rate? false}

   {:id "h5-engine-pin"
    :title "Ship the benchmarked query engine"
    :change "Production ran datalog 14 commits behind the benchmarked build."
    :after-harness {:cpu-ms :unchanged :class-b-ops :unchanged}
    :basis :measured
    :basis-note "Landed 2026-08-17; changed no served number, as predicted.
                 Kept as a control -- a hypothesis with a measured zero."
    :effort :S
    :reversible? true
    :depends-on-write-rate? false}

   {:id "h8-l2-cache-api"
    :title "Give this Worker the colo-shared block cache it never had"
    :change "kotobase_r2.cljs caches immutable blocks in ISOLATE MEMORY only.
             The sibling Worker serving datoms.kotobase.net has had two tiers
             since its block cache landed -- L1 isolate memory AND L2 the
             Cloudflare Cache API, per-colo and outliving the isolate."
    ;; DEPLOYED AND REFUTED, 2026-08-18. Receipt:
    ;; 90-docs/kotobase-performance/2026-08-18-h8-deployed-and-refuted.edn
    :after-harness {:cpu-ms :unchanged :class-b-ops :unchanged}
    :basis :measured-and-refuted-for-this-shape
    :basis-note "Landed, deployed as version e36410a5, MEASURED, and rolled back
                 within minutes. Before: 3.34 s cold / 2.38 s warm. After: twelve
                 probes at 14.2-41.9 s, none completing a hydrate, then a 503 at
                 31.3 s. After rollback: 3.51 s, all four query surfaces 200.

                 The tier adds one `cache.match` per block and one awaited
                 `cache.put` per durable answer. The hydrate touches 84 blocks
                 ONE AT A TIME -- the novelty chain's next CID does not exist
                 until the previous block is decoded -- so it inserted up to 168
                 sequential Cache API operations and the request stopped fitting
                 in a Worker.

                 The finding is structural, not a tuning miss: ANY per-block
                 auxiliary tier pays 84x here, and it cannot be amortised because
                 there is no batch. Same fact that limits h4, from the other
                 side."
    :effort :S
    :reversible? true
    :depends-on-write-rate? false
    :the-judge-cannot-price-this
    "$0.00 above is wrong in a specific way worth stating: the deployed change
     did not cost nothing, it cost a 503. `cost-per-million` prices CPU, class-B
     and requests, and has no term for `the request does not complete`. A
     hypothesis that breaks the service scores the same here as one that changes
     nothing, so the ranking must never be the only gate before a deploy."}])

(defn- scale-after
  "Harness cpu-ms -> the workerd level the judge prices in. Class-B counts are
  live and pass through untouched; only the proxied term is corrected.

  `:cpu-ms :unchanged` resolves to the LIVE baseline, and that is a bug fix, not
  a convenience. `workerd-scale` was calibrated as 2397/1976 -- the measured
  level of 2026-08-18 morning over the harness level that produced it. The
  outer-string change then moved the measured level to 2058 without anyone
  re-running the harness, so scaling a stale 1976 produced 2397: iteration-02
  priced every CPU-neutral hypothesis as a **+339 ms regression it does not
  cause**. `h5-engine-pin` is the proof, and it was sitting in the output --
  a hypothesis kept deliberately as a MEASURED ZERO was ranked last at
  -$6.78/million. A control that reads as a loss is the loop telling you its
  own arithmetic is wrong.

  A hypothesis that does not claim to move CPU is therefore priced at the CPU
  that was actually measured, with no proxy in the path at all."
  [{:keys [after-harness] :as h}]
  (let [cpu (:cpu-ms after-harness)
        ops (:class-b-ops after-harness)]
    (assoc h :after {:cpu-ms (if (= :unchanged cpu)
                               (:cpu-ms baseline)
                               (Math/round (* workerd-scale cpu)))
                     ;; Same rule on the other axis, and it was needed for the
                     ;; same reason twice in one session: the FIRST fix made
                     ;; CPU-neutral hypotheses resolve to the live baseline, and
                     ;; then the class-B baseline moved (84 -> the measured mix)
                     ;; while the hypotheses still carried the cold-case 84.
                     ;; h5-engine-pin -- the control -- immediately read
                     ;; -$12.10. A stale literal on EITHER axis reappears as a
                     ;; regression the hypothesis does not cause, and the only
                     ;; reason it was caught both times is that a measured zero
                     ;; is sitting in the roadmap where it can be read.
                     :class-b-ops (if (= :unchanged ops)
                                    (:class-b-ops baseline)
                                    ops)})))

(defn generate [] (mapv scale-after hypotheses))

;; ── Reflect ──────────────────────────────────────────────────────────────────

(defn reflect
  "Annotate each hypothesis with its judged saving and its risk.

  Risk is not effort. A large, reversible change that is already measured is
  less risky than a small irreversible one resting on an analogy."
  [hyps]
  (let [base (cost-per-million baseline)]
    (mapv (fn [h]
            (let [after (cost-per-million (:after h))]
              (assoc h
                     :cost-before base
                     :cost-after after
                     :saving (- base after)
                     :saving-share (/ (- base after) base)
                     :reflection
                     {:risk (cond
                              (= :measured-and-refuted-for-this-shape (:basis h)) :high
                              (and (= :measured (:basis h)) (:reversible? h)) :low
                              (not (:reversible? h)) :high
                              :else :medium)
                       :note (case (:basis h)
                               :measured "effect measured on this graph's own blocks"
                               :measured-elsewhere "effect measured on a DIFFERENT graph; the number is an analogy until re-measured here"
                               :measured-and-refuted-for-this-shape "the mechanism is real but was measured NOT to help the dominant term"
                               "unbasis")})))
          hyps)))

;; ── Rank: Elo, judged by the fitness function ────────────────────────────────

(defn- expected [ra rb] (/ 1.0 (+ 1.0 (Math/pow 10.0 (/ (- rb ra) 400.0)))))

(defn- bout
  "Larger measured saving wins. Ties break to the hypothesis whose basis is a
  measurement of THIS graph rather than an analogy, then to lower effort.

  Deliberately NOT breaking ties on 'already deployed' or on how recently
  someone argued for it -- those are the inputs a deterministic judge exists to
  exclude."
  [a b]
  (let [ea {:S 0 :M 1 :L 2}
        basis-rank {:measured 0 :measured-elsewhere 1 :measured-and-refuted-for-this-shape 2}]
    (cond
      (> (:saving a) (:saving b)) :a
      (< (:saving a) (:saving b)) :b
      (< (basis-rank (:basis a) 3) (basis-rank (:basis b) 3)) :a
      (> (basis-rank (:basis a) 3) (basis-rank (:basis b) 3)) :b
      (< (ea (:effort a) 1) (ea (:effort b) 1)) :a
      (> (ea (:effort a) 1) (ea (:effort b) 1)) :b
      :else :a)))

(defn rank
  "Round-robin Elo, K=32, base 1200 -- the same convention as the two loops this
  is ported from, so iterations stay comparable across all three."
  [hyps]
  (let [ids (mapv :id hyps)
        by-id (into {} (map (juxt :id identity) hyps))
        ratings (reduce
                 (fn [rt [i j]]
                   (let [a (by-id i) b (by-id j)
                         ra (rt i) rb (rt j)
                         sa (if (= :a (bout a b)) 1.0 0.0)]
                     (-> rt
                         (update i + (* 32 (- sa (expected ra rb))))
                         (update j + (* 32 (- (- 1.0 sa) (expected rb ra)))))))
                 (zipmap ids (repeat 1200.0))
                 (for [i ids j ids :when (neg? (compare i j))] [i j]))]
    (->> hyps
         (map #(assoc % :elo (Math/round ^double (ratings (:id %)))))
         (sort-by (juxt (comp - :elo) (comp - :saving)))
         vec)))

;; ── Evolve ───────────────────────────────────────────────────────────────────

(defn evolve
  "Hypotheses are not independent, and the tournament ranks them as if they
  were. This is where that is repaired.

  A served read has two priced resources. CPU is $41.16 of the $59.60. The
  gets are $18.14, and that number moved TODAY: `cold-isolate-rate` measured
  that 6 hydrating requests in 10 find L1 empty, not 10 in 10, so the class-B
  term is 40% smaller than iteration-03 priced it.

    h7 removes the CPU half (parse), leaves the gets.
    h8 removes most of the gets L1 did not already remove, leaves the CPU.
    h1 removes the gets AND half the CPU, and is the only one whose cost
       depends on a write rate nothing has measured.

  h7 and h8 are orthogonal, so their savings add. h1 is NOT additive with h8 --
  it subsumes it.

  **The order changed, and the measurement changed it.** iteration-03 put h8
  first: :S, reversible, no credential, and its premise -- that L1 never helps
  a served read -- was inferred from twelve `sampled: false` readings. Those
  readings answered `has this isolate ever hydrated`, which is a different
  question from `when a request DOES hydrate, is L1 warm`. Measured directly,
  the answer is warm 4 times in 10, and h8's saving fell from $22.68 to $13.61.

  h7 is now first on the number as well as being the larger half of the request.
  h8 is not withdrawn -- it is landed, it is still worth $13.61, and it is the
  only one of the three that needs neither a credential nor a write. It is
  simply no longer the biggest thing available."
  [ranked]
  (let [by-id (into {} (map (juxt :id identity) ranked))
        h1 (by-id "h1-seed-materialisation")
        h7 (by-id "h7-parse-only-addressable-values")
        h8 (by-id "h8-l2-cache-api")
        pair-cost (cost-per-million {:cpu-ms (get-in h7 [:after :cpu-ms])
                                     :class-b-ops (get-in h8 [:after :class-b-ops])})]
    {:batch-id "kotobase-cost-kaizen-4"
     :members ["h7-parse-only-addressable-values"]
     :order "h7 next; h8 was deployed and refuted"
     :why "h7 is the larger half of a served read, is reversible, is :M, and is
           independent of the read:write ratio. It is also the half neither h8
           nor any cache can touch: a warm isolate still pays it in full, which
           is exactly what the 2.38 s warm requests show against 3.34 s cold."
     :pair-cost-per-million pair-cost
     :pair-saving (- (cost-per-million baseline) pair-cost)
     :h8-status
     {:landed "net-kotobase/control-plane main 57b1f350f"
      :deployed "version e36410a5, 2026-08-18"
      :outcome :refuted-in-production
      :reverted "main aa714b887; production rolled back to 120f3e08"
      :arc "ranked 3rd and placed FIRST in the batch (iteration-03) -> 4th once
            cold-isolate-rate was measured (iteration-04) -> last, refuted, once
            it was deployed and measured (iteration-05)"
      :what-each-step-cost
      "Each demotion came from a measurement that could have been taken earlier.
       The premise (does L1 ever help?) was measurable before landing. The cost
       of 168 Cache API operations was measurable before deploying -- not by the
       test suite, which used an in-memory fake, but by counting them."}
     :additive-not-subsumed
     {:h8-alone (:cost-after h8) :h7-alone (:cost-after h7) :pair pair-cost
      :h1-alone (:cost-after h1)
      :note "h1 vs h8 is a CHOICE, not an order: both remove the gets."}
     :not-in-the-batch
     "h6 (retracting 91 documents) is now THIRD at $21.50 and is still an owner
      judgement: it is irreversible deletion of production data. h2 (fold) is
      one write from firing. h4 (pack) is $4.32 on this graph -- same gets as
      h8, fewer of them removed -- and earns its place on the OTHER Worker's
      write path, which this judge does not price."
     :how-this-batch-can-fail
     "h7's :after-harness was measured against the OLD decode2 and is still
      stale by the ~28% the outer-string change already took off phase B. Its
      $28.56 is therefore an UPPER bound. The phase bench that would fix it has
      been blocked by the repo-wide build governor all session."
     :unmeasured-dependency
     "h1's maintenance is a seed rebuild per commit. NOTHING HERE MEASURES THE
      READ:WRITE RATIO."}))

;; ── Meta ─────────────────────────────────────────────────────────────────────

(defn meta-review
  [ranked evolved]
  (let [base (cost-per-million baseline)
        top (first ranked)
        aura-monthly 65.0]
    {:baseline-cost-per-million base
     :top-pick (:id top)
     :top-saving (:saving top)
     :crossover-queries-per-month
     {:today (/ aura-monthly (max 0.001 base))
      :after-top (/ aura-monthly (max 0.001 (:cost-after top)))
      :note "queries per month at which one Neo4j Aura instance ($65/mo flat)
             becomes cheaper than kotobase for a single graph. Higher is better.
             Aura's price is second-hand -- its vendor page returned 403."}
     :roadmap ranked
     :batch evolved
     :what-the-judge-cannot-see
     ["THE TRAFFIC. Twelve consecutive reads of /_diag/hydrate on 2026-08-18
       returned `sampled: false` -- twelve isolates, none of which had ever
       hydrated. This loop prices a served read on a graph that currently
       serves none, so every saving here is per-request unit economics at
       volume that does not yet exist. That is the honest headline; it is not
       an argument against the ranking, but it decides what the ranking is FOR."
      "Storage, deliberately excluded as constant across hypotheses."
      "The read:write ratio, which decides whether h1 is a win at all."
      "Memory. The fold-cost receipt could not determine whether a fold fits the
       128 MB isolate limit, and a hypothesis that fails on memory scores the
       same here as one that succeeds."
      "Correctness. Every hypothesis is assumed to return the same answers; the
       judge only prices them."
      "FAILURE. There is no term for `the request does not complete`. h8 was
       deployed and returned 503; it scores $0.00 here, identical to a
       hypothesis that changes nothing. The ranking is an ordering of
       CANDIDATES and must never be the only gate before a deploy."
      "Per-operation cost of anything the hypothesis adds. h8 added 168
       sequential Cache API calls to a request and the judge has no way to see
       them: they are neither CPU it models nor class-B it counts."]}))

(defn run []
  (let [g (generate)
        r (reflect g)
        k (rank r)
        e (evolve k)]
    (assoc (meta-review k e) :prices prices :baseline baseline)))
