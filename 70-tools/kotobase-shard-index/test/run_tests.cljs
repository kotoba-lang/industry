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
