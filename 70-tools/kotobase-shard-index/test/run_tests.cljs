(ns run-tests
  "Portable assertions for the shard index. Run:

    npx nbb --classpath 70-tools/kotobase-shard-index/src \\
      70-tools/kotobase-shard-index/test/run_tests.cljs

  The load-bearing test is `exactness`: it checks the bounded scan against a
  full scan of the same index over synthetic corpora and queries. The stopping
  bound is an argument; this is the check on it."
  (:require [kotobase-shard-index.analyze :as analyze]
            [kotobase-shard-index.block :as block]
            [kotobase-shard-index.build :as build]
            [kotobase-shard-index.codec :as codec]
            [kotobase-shard-index.node :as node]
            [kotobase-shard-index.query :as query]
            [kotobase-shard-index.route :as route]
            [kotobase-shard-index.synth :as synth]))

(def failures (atom []))
(def passes (atom 0))

(defn check! [label ok? detail]
  (if ok? (swap! passes inc) (swap! failures conj {:label label :detail detail})))

(defn is= [label expected actual]
  (check! label (= expected actual) {:expected expected :actual actual}))

(def vocab-size 5000)
(def queries (synth/probe-queries vocab-size))

(defn build-mem [docs opts]
  (let [store (block/memory-store)
        {:keys [manifest-cid stats]} (build/build! store node/sha256-hex docs opts)]
    {:store store :manifest-cid manifest-cid :stats stats}))

;; ── 1. canonical encoding ───────────────────────────────────────────

(let [big (into {} (map (fn [i] [(keyword (str "k" i)) i])) (range 20))
      shuffled (into {} (reverse (seq big)))]
  (is= "canonical: >8-entry maps encode identically regardless of insertion order"
       (codec/encode big) (codec/encode shuffled))
  (check! "canonical: sets are rejected rather than silently reordered"
          (try (codec/encode #{1 2 3}) false (catch :default _ true))
          nil))

;; ── 2. analyzer ─────────────────────────────────────────────────────

(is= "analyze: ascii runs" ["hello" "world" "42"] (analyze/tokenize "Hello, World 42!"))
(is= "analyze: cjk bigrams" ["東京" "京都"] (analyze/tokenize "東京都"))
(is= "analyze: single cjk char survives" ["猫"] (analyze/tokenize "猫"))
(is= "analyze: mixed script" ["ai" "検索" "索エ" "エン"] (analyze/tokenize "AI 検索エン"))
(is= "analyze: title weighting is baked in"
     {"cat" 3 "dog" 1} (analyze/doc-terms {:title "cat" :text "dog"} 3))
;; The defect that made the first benchmark meaningless: a hyphenated term is
;; two terms. Pinned so a future corpus cannot reintroduce it unnoticed.
(is= "analyze: a hyphen splits, it does not join"
     ["alpha" "one"] (analyze/tokenize "alpha-one"))

;; ── 3. build determinism and block integrity ────────────────────────

(let [docs (synth/corpus 300 7 vocab-size)
      a (build-mem docs {})
      b (build-mem docs {})]
  (is= "build: rebuilding the same corpus yields the same manifest cid"
       (:manifest-cid a) (:manifest-cid b))
  (is= "build: rebuilding yields the same block set"
       (block/block-count (:store a)) (block/block-count (:store b)))
  (check! "build: every block re-hashes to its own address"
          (every? #(block/verify-block (:store a) node/sha256-hex %)
                  (keys @(:state (:store a))))
          nil)
  (is= "build: doc count recorded" 300 (get-in a [:stats :doc-count])))

;; ── 4. relevance sanity ─────────────────────────────────────────────

(let [docs [{:url "u0" :title "cats" :text "a page about cats and more cats"}
            {:url "u1" :title "dogs" :text "a page about dogs"}
            {:url "u2" :title "birds" :text "a page about birds and cats"}]
      {:keys [store manifest-cid]} (build-mem docs {:shard-count 1})
      hits (:hits (query/search store manifest-cid "cats" {:k 3}))]
  (is= "search: the title match with the highest term frequency ranks first"
       "u0" (:url (first hits)))
  (check! "search: a document without the term is not returned"
          (not (some #(= "u1" (:url %)) hits)) hits)
  (is= "search: absent term returns nothing"
       [] (:hits (query/search store manifest-cid "zebra" {:k 3}))))

;; ── 5. the claim: early termination does not change the answer ───────

;; Configurations are chosen so the STOPPING BOUND is actually exercised.
;; Mutation testing found that the first version was not testing it at all: at
;; 200-2000 documents with the default 128-posting chunks, one prefetch wave
;; consumed every posting list, so every query ended `:exhausted` and three
;; separate mutations of the bound (dropping the unseen-document term,
;; understating thresholds 4x) all passed. A test that cannot fail is not
;; evidence. Small chunks + few shards + narrow prefetch make lists long
;; enough that stopping early is a real decision.
(doseq [[n seed shards chunk pf] [[200 11 1 8 2]
                                  [800 23 4 8 2]
                                  [2000 31 1 16 4]
                                  [2000 31 8 128 8]]]
  (let [{:keys [store manifest-cid]} (build-mem (synth/corpus n seed vocab-size)
                                                {:shard-count shards :chunk-size chunk})
        proofs (mapv #(get-in (query/search store manifest-cid % {:k 10 :prefetch pf})
                              [:stats :proof])
                     queries)
        mismatches
        (vec (for [q queries
                   :let [fast (query/search store manifest-cid q {:k 10 :prefetch pf})
                         slow (query/top-k-exhaustive store manifest-cid q {:k 10})
                         ;; the claim, stated exactly: the k scores are the k
                         ;; largest, and each reported score is that document's
                         ;; complete score — no partial sum leaks out of an
                         ;; early stop.
                         score-vec-ok? (= (mapv :score (:hits fast))
                                          (mapv :score (:hits slow)))
                         complete? (every? (fn [h] (= (:score h)
                                                      (get (:scores slow) (:doc-id h))))
                                           (:hits fast))]
                   :when (not (and score-vec-ok? complete?))]
               {:q q
                :fast (mapv (juxt :doc-id :score) (:hits fast))
                :slow (mapv (juxt :doc-id :score) (:hits slow))
                :score-vec-ok? score-vec-ok?
                :complete? complete?}))]
    (check! (str "exactness: bounded scan scores == full scan scores, n=" n
                 " shards=" shards " chunk=" chunk " prefetch=" pf)
            (empty? mismatches) (first mismatches))
    ;; Without this the suite above can pass while testing nothing: if every
    ;; query exhausts its lists, the bound is never consulted.
    (check! (str "exactness: the bound is actually exercised, n=" n " chunk=" chunk
                 " " (pr-str (frequencies proofs)))
            (some #{:bounded} proofs) proofs)))

;; ── 5d. the routing read path ───────────────────────────────────────
;; `route/search` skips shards whose bound cannot reach the k-th score. That
;; is a second early-termination argument stacked on the first, so it gets the
;; same treatment: checked against the full scan, and checked that the thing
;; being tested actually happens.
;;
;; The multi-shard configurations are the point. With one shard there is
;; nothing to prune and the test would pass while exercising none of the code
;; the namespace exists for — the exact failure mode mutation testing found in
;; §5a.

;; `sb` (shard-batch) is part of the configuration because it decides whether
;; pruning can happen at all: a batch at least as large as the shard count
;; opens everything in one wave and there is nothing left to skip. That is the
;; GETs-versus-waves dial working as intended, and both ends of it are pinned
;; below rather than left to whichever default happens to be set.
(doseq [[n seed shards chunk pf sb] [[800 23 4 8 2 2]
                                     [2000 31 8 16 4 2]
                                     [4000 41 16 128 8 4]
                                     [2000 31 1 16 4 8]
                                     [2000 31 8 16 4 8]]]
  (let [{:keys [store manifest-cid]} (build-mem (synth/corpus n seed vocab-size)
                                                {:shard-count shards :chunk-size chunk})
        runs (mapv (fn [q]
                     {:q q
                      :routed (route/search store manifest-cid q
                                            {:k 10 :prefetch pf :shard-batch sb})
                      :v1 (query/search store manifest-cid q {:k 10 :prefetch pf})
                      :oracle (query/top-k-exhaustive store manifest-cid q {:k 10})})
                   queries)
        vs-oracle (vec (for [{:keys [q routed oracle]} runs
                             :let [scores-ok? (= (mapv :score (:hits routed))
                                                 (mapv :score (:hits oracle)))
                                   complete? (every? (fn [h] (= (:score h)
                                                                (get (:scores oracle) (:doc-id h))))
                                                     (:hits routed))]
                             :when (not (and scores-ok? complete?))]
                         {:q q
                          :routed (mapv (juxt :doc-id :score) (:hits routed))
                          :oracle (mapv (juxt :doc-id :score) (:hits oracle))}))
        vs-v1 (vec (for [{:keys [q routed v1]} runs
                         :when (not= (mapv :score (:hits routed)) (mapv :score (:hits v1)))]
                     {:q q
                      :routed (mapv :score (:hits routed))
                      :v1 (mapv :score (:hits v1))}))
        pruned (mapv #(get-in % [:routed :stats :shards-pruned]) runs)]
    (check! (str "route: scores == full scan, n=" n " shards=" shards
                 " chunk=" chunk " prefetch=" pf " batch=" sb)
            (empty? vs-oracle) (first vs-oracle))
    (check! (str "route: agrees with the v1 read path, n=" n " shards=" shards
                 " batch=" sb)
            (empty? vs-v1) (first vs-v1))
    ;; Bound pruning has to actually happen somewhere or it is dead code that
    ;; every assertion above passes over — the failure mode §5a was written
    ;; against. It bites on a HOMOGENEOUS corpus with many shards: the shards
    ;; are small, so which query terms each one holds and how strongly varies,
    ;; and the weakest bounds fall under the k-th score. (The first version of
    ;; this test asserted the OPPOSITE, on the theory that uniform shards are
    ;; indistinguishable. Measurement said otherwise: the variance grows as
    ;; shards shrink, so pruning gets better in the regime where fan-out hurts
    ;; most. §5e covers the other corpus shape.)
    (when (and (>= shards 8) (< sb shards))
      (check! (str "route: bound pruning fires, n=" n " shards=" shards " batch=" sb
                   " pruned=" (pr-str (frequencies pruned)))
              (some pos? pruned) pruned))
    ;; The other end of the dial: a batch that covers every shard opens them
    ;; all in one wave, so nothing can be pruned. Pinned so that a future
    ;; change to the default cannot silently turn pruning off everywhere
    ;; while the assertion above still passes on some other configuration.
    (when (>= sb shards)
      (check! (str "route: a batch >= shard count prunes nothing, shards=" shards
                   " batch=" sb " " (pr-str (frequencies pruned)))
              (every? zero? pruned) pruned))))

;; ── 5e. the other half of the fan-out: shards that hold no query term ──
;; `build!` assigns doc-ids by position, so a crawl grouped by host produces
;; shards with different vocabularies. On such a corpus a topical query is not
;; pruned by a bound at all — the routing entry never lists the shards that do
;; not contain the term, so they are never candidates. That is a bigger and
;; cheaper reduction than pruning, and it is invisible in `:shards-pruned`,
;; which is why the stats report both. Measured, not assumed: the first
;; version of this block asserted pruning here and got zero.

(let [clusters 8
      head-frac 0.3
      docs (synth/clustered-corpus 4000 91 clusters vocab-size head-frac)
      cqs (synth/cluster-queries clusters vocab-size head-frac)
      {:keys [store manifest-cid]} (build-mem docs {:shard-count clusters :chunk-size 16})
      runs (mapv (fn [q]
                   {:q q
                    :routed (route/search store manifest-cid q {:k 10 :prefetch 4})
                    :oracle (query/top-k-exhaustive store manifest-cid q {:k 10})})
                 cqs)
      absent (mapv #(get-in % [:routed :stats :shards-absent]) runs)
      opened (mapv #(get-in % [:routed :stats :shards-opened]) runs)
      mismatches (vec (for [{:keys [q routed oracle]} runs
                            :when (not= (mapv :score (:hits routed))
                                        (mapv :score (:hits oracle)))]
                        {:q q
                         :routed (mapv (juxt :doc-id :score) (:hits routed))
                         :oracle (mapv (juxt :doc-id :score) (:hits oracle))}))]
  (check! (str "route/clustered: a topical query never considers most shards, absent="
               (pr-str (frequencies absent)) " of " clusters)
          (every? pos? absent) absent)
  (check! (str "route/clustered: it opens far fewer than it would fan out to, opened="
               (pr-str (frequencies opened)))
          (every? #(< % clusters) opened) opened)
  ;; Skipping that changed an answer is not skipping. This is the assertion
  ;; the whole optimisation rests on.
  (check! "route/clustered: scores == full scan even where shards were skipped"
          (empty? mismatches) (first mismatches)))

;; The routing path must not depend on read order either.
(let [{:keys [store manifest-cid]} (build-mem (synth/corpus 3000 71 vocab-size)
                                              {:shard-count 8 :chunk-size 8})
      disagreements
      (vec (for [q queries
                 :let [runs (mapv (fn [pf]
                                    [pf (mapv :score (:hits (route/search store manifest-cid q
                                                                          {:k 10 :prefetch pf})))])
                                  [1 2 8 64])]
                 :when (not (apply = (map second runs)))]
             {:q q :runs runs}))]
  (check! "route: scores are identical for prefetch 1 / 2 / 8 / 64"
          (empty? disagreements) (first disagreements)))

;; `shard-batch` trades GETs for waves: a bigger batch opens shards that a
;; risen k-th score would have excluded. It must not move the ANSWER — if it
;; does, the bound is being applied to shards it has not actually accounted
;; for. Same argument as the prefetch check above, one level up.
(let [{:keys [store manifest-cid]} (build-mem (synth/corpus 4000 71 vocab-size)
                                              {:shard-count 16 :chunk-size 16})
      disagreements
      (vec (for [q queries
                 :let [runs (mapv (fn [sb]
                                    [sb (mapv :score (:hits (route/search store manifest-cid q
                                                                          {:k 10 :shard-batch sb})))])
                                  [1 2 8 64])]
                 :when (not (apply = (map second runs)))]
             {:q q :runs runs}))]
  (check! "route: scores are identical for shard-batch 1 / 2 / 8 / 64"
          (empty? disagreements) (first disagreements)))

;; An index built without the routing dictionary must say so rather than
;; silently fall back to reading everything — a fallback here would be a
;; performance cliff with no signal.
(let [{:keys [store manifest-cid]} (build-mem (synth/corpus 200 13 vocab-size)
                                              {:shard-count 2 :route? false})]
  (check! "route: an index without a routing dictionary is refused loudly"
          (try (route/search store manifest-cid (first queries) {:k 5}) false
               (catch :default _ true))
          nil))

;; ── 5c. the read ORDER must not change the answer ───────────────────
;; `prefetch` changes which chunks are fetched in which wave. If any result
;; depends on it, the bound is wrong in a way a single-configuration test
;; would not show.

(let [{:keys [store manifest-cid]} (build-mem (synth/corpus 3000 71 vocab-size)
                                              {:shard-count 2 :chunk-size 8})
      disagreements
      (vec (for [q queries
                 :let [runs (mapv (fn [pf]
                                    [pf (mapv :score (:hits (query/search store manifest-cid q
                                                                          {:k 10 :prefetch pf})))])
                                  [1 2 8 64])]
                 :when (not (apply = (map second runs)))]
             {:q q :runs runs}))]
  (check! "read order: scores are identical for prefetch 1 / 2 / 8 / 64"
          (empty? disagreements) (first disagreements)))

;; ── 5b. the documented limit: ties are unspecified, scores are not ──

(let [docs (vec (for [i (range 400)] {:url (str "u" i) :title "tie" :text "tie"}))
      {:keys [store manifest-cid]} (build-mem docs {:shard-count 4})
      fast (query/search store manifest-cid "tie" {:k 10})
      slow (query/top-k-exhaustive store manifest-cid "tie" {:k 10})]
  (is= "ties: an all-tie corpus still returns exactly k hits" 10 (count (:hits fast)))
  (is= "ties: the scores are right even when which documents win is not"
       (mapv :score (:hits slow)) (mapv :score (:hits fast)))
  (check! "ties: every reported score is the document's complete score"
          (every? (fn [h] (= (:score h) (get (:scores slow) (:doc-id h)))) (:hits fast))
          (:hits fast)))

;; ── 6. early termination actually saves reads ───────────────────────

(let [{:keys [store manifest-cid]} (build-mem (synth/corpus 2000 47 vocab-size)
                                              {:shard-count 4})
      totals (reduce (fn [acc q]
                       (-> acc
                           (update :fast + (get-in (query/search store manifest-cid q {:k 10})
                                                   [:stats :chunk-reads]))
                           (update :slow + (get-in (query/top-k-exhaustive store manifest-cid q {:k 10})
                                                   [:stats :chunk-reads]))))
                     {:fast 0 :slow 0} queries)]
  (check! (str "early termination reads fewer chunks than a full scan " (pr-str totals))
          (< (:fast totals) (:slow totals)) totals))

;; ── 7. dictionary descent is logarithmic ────────────────────────────

(let [heights (vec (for [n [100 1000 4000]]
                     (let [{:keys [store manifest-cid]}
                           (build-mem (synth/corpus n (+ 5 n) vocab-size) {:shard-count 1})
                           m (block/get-block store manifest-cid)]
                       [(get-in m [:stats :term-count])
                        (:dict-height (first (:shards m)))])))]
  (check! (str "dict height grows logarithmically, not linearly " (pr-str heights))
          (and (apply <= (map second heights)) (<= (second (last heights)) 3))
          heights))

;; ── 8. GETs per query are bounded by the answer, not the corpus ──────

(let [sizes [500 2000 8000]
      probe (fn [n]
              (let [{:keys [store manifest-cid]}
                    (build-mem (synth/corpus n (+ 100 n) vocab-size) {:shard-count 4})
                    [counted tally] (block/counting store)
                    gets (mapv (fn [q]
                                 (block/reset-tally! tally)
                                 (query/search counted manifest-cid q {:k 10})
                                 (:gets @tally))
                               queries)]
                {:n n :gets (reduce + 0 gets) :blocks (block/block-count store)}))
      results (mapv probe sizes)
      growth (/ (double (:gets (last results))) (:gets (first results)))
      corpus-growth (/ (double (last sizes)) (first sizes))]
  (check! (str "GETs grow far slower than the corpus " (pr-str results))
          (< growth (/ corpus-growth 2))
          {:results results :get-growth growth :corpus-growth corpus-growth}))

;; ── 9. filesystem (S3-shaped) store round-trips ─────────────────────

(let [dir (str "/tmp/ksi-test-" (.getTime (js/Date.)))
      store (node/fs-store dir)
      docs (synth/corpus 120 3 vocab-size)
      {:keys [manifest-cid]} (build/build! store node/sha256-hex docs {:shard-count 2})
      hits (:hits (query/search store manifest-cid (first queries) {:k 5}))]
  (check! "fs store: build and query round-trip through one-object-per-CID"
          (pos? (count hits)) hits)
  (check! "fs store: range GET returns the requested slice"
          (= (subs (block/-get store manifest-cid) 0 10)
             (block/-get-range store manifest-cid 0 10))
          nil))

;; ── report ──────────────────────────────────────────────────────────

(println (str "\npassed: " @passes "   failed: " (count @failures)))
(doseq [f @failures]
  (println "FAIL:" (:label f))
  (when (:detail f) (println "      " (pr-str (:detail f)))))
(when (seq @failures) (set! (.-exitCode js/process) 1))
