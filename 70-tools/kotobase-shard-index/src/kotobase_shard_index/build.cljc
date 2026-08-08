(ns kotobase-shard-index.build
  "Build side: a corpus becomes immutable blocks plus one manifest.

  Everything here runs where the crawler runs, never where a query runs. The
  output is a set of objects that no longer change, so the read side needs no
  server, no lock, and no invalidation — a block that differs is a different
  address.

  Two decisions are load-bearing and both are visible in the block formats:

  1. **Impacts are precomputed and stored, not scored at query time.** BM25
     needs df, N and avgdl; a client that scored at query time would need the
     global statistics AND the term frequency of every candidate. Storing the
     finished contribution means a posting entry is `[doc-id impact]` and
     nothing else has to travel.

  2. **idf is global, not per shard.** Shards are built in one pass so df is
     counted across the whole corpus. Per-shard idf would make two shards
     disagree about what a rare word is, and the cross-shard merge would be
     comparing numbers that do not mean the same thing.

  Every value written into a block is an integer, a string or a keyword.
  Floating point is excluded on purpose: `pr-str` of an integer-valued double
  is \"10.0\" on the JVM and \"10\" in JavaScript, which would give the same
  logical block two different addresses on two runtimes."
  (:require [kotobase-shard-index.analyze :as analyze]
            [kotobase-shard-index.block :as block]))

(def default-opts
  {:shard-count 4
   :dict-fanout 32
   ;; 128 postings per chunk. Small chunks look attractive (stop sooner, read
   ;; less) but each one costs a round trip, and round trips are what bounds
   ;; this design — a 16-posting chunk turned a selective query into hundreds
   ;; of GETs when it was first measured.
   :chunk-size 128
   :meta-chunk-size 32
   :title-weight 3
   :k1-x100 120        ; BM25 k1 = 1.20
   :b-x100 75          ; BM25 b  = 0.75
   :impact-scale 1000
   ;; Write the routing dictionary. Costs blocks at build time and removes the
   ;; per-shard fan-out at query time; `false` reproduces the v1 index exactly,
   ;; which is what the A/B in `bench/route_scaling.cljs` compares against.
   :route? true})

(defn- round-int [x]
  #?(:clj (long (Math/round (double x))) :cljs (js/Math.round x)))

(defn- ln [x]
  #?(:clj (Math/log (double x)) :cljs (js/Math.log x)))

;; ── scoring ─────────────────────────────────────────────────────────

(defn- idf
  "BM25 probabilistic idf with the +1 that keeps it non-negative for terms
  present in more than half the corpus."
  [n df]
  (ln (+ 1.0 (/ (+ (- n df) 0.5) (+ df 0.5)))))

(defn- impact
  "The finished per-(term,doc) contribution, quantised to an integer."
  [{:keys [k1-x100 b-x100 impact-scale]} idf-t f dl avgdl]
  (let [k1 (/ k1-x100 100.0)
        b (/ b-x100 100.0)
        norm (+ (- 1.0 b) (* b (/ (double dl) (max 1.0 avgdl))))]
    (round-int (* impact-scale idf-t (/ (* f (+ k1 1.0)) (+ f (* k1 norm)))))))

;; ── dictionary tree ─────────────────────────────────────────────────

(defn- build-dict-tree
  "Sorted `[term entry]` pairs -> a fanout-B search tree of blocks.

  Height is logarithmic in the term count, and height is exactly the number of
  round trips a client spends to locate one term. That is the whole reason the
  dictionary is a tree and not one sorted object."
  [sink hash-fn fanout entries]
  (if (empty? entries)
    {:root (block/put-block! sink hash-fn {:kind :dict-leaf :entries []}) :height 1}
    (let [leaves (mapv (fn [grp]
                         {:cid (block/put-block! sink hash-fn
                                                 {:kind :dict-leaf :entries (vec grp)})
                          :first-term (ffirst grp)})
                       (partition-all fanout entries))]
      (loop [level leaves height 1]
        (if (<= (count level) 1)
          {:root (:cid (first level)) :height height}
          (recur (mapv (fn [grp]
                         {:cid (block/put-block!
                                sink hash-fn
                                {:kind :dict-internal
                                 :entries (mapv (juxt :first-term :cid) grp)})
                          :first-term (:first-term (first grp))})
                       (partition-all fanout level))
                 (inc height)))))))

;; ── postings ────────────────────────────────────────────────────────

(defn- build-postings
  "One term's postings for one shard.

  Sorted by impact descending, ties broken by doc-id ascending so a rebuild
  produces byte-identical blocks. The head block carries each chunk's
  max-impact, which is what lets a client stop reading.

  Returns `{:cid :df :max-impact}`. The `:max-impact` is the first chunk's,
  i.e. the largest contribution this term can make to any document in this
  shard — the quantity the routing dictionary is built out of."
  [sink hash-fn chunk-size term postings]
  (let [ordered (vec (sort-by (juxt (comp - second) first) postings))
        chunks (mapv (fn [grp]
                       (let [grp (vec grp)]
                         {:cid (block/put-block! sink hash-fn
                                                 {:kind :posting-chunk :postings grp})
                          :max-impact (second (first grp))
                          :count (count grp)}))
                     (partition-all chunk-size ordered))]
    {:cid (block/put-block! sink hash-fn
                            {:kind :posting-head
                             :term term
                             :df (count ordered)
                             :chunks chunks})
     :df (count ordered)
     :max-impact (if (seq chunks) (:max-impact (first chunks)) 0)}))

;; ── metadata ────────────────────────────────────────────────────────

(defn- build-meta
  "Doc metadata, chunked so the final display fetch is one GET for a run of
  neighbouring doc-ids rather than one per hit."
  [sink hash-fn chunk-size base docs]
  (let [chunks (mapv (fn [grp]
                       (block/put-block! sink hash-fn
                                         {:kind :meta-chunk
                                          :docs (mapv #(select-keys % [:id :url :title]) grp)}))
                     (partition-all chunk-size docs))]
    (block/put-block! sink hash-fn
                      {:kind :meta-dir
                       :chunk-size chunk-size
                       :base base
                       :chunks (vec chunks)})))

;; ── driver ──────────────────────────────────────────────────────────

(defn- shard-ranges
  "Contiguous doc-id ranges. Contiguous rather than hashed so a metadata
  lookup resolves shard and offset by arithmetic instead of a directory."
  [n shard-count]
  (let [per (max 1 (quot (+ n shard-count -1) shard-count))]
    (->> (range 0 n per)
         (mapv (fn [start] [start (min n (+ start per))])))))

(defn build!
  "`docs` : seq of `{:url :title :text}`. Returns `{:manifest-cid :stats}`.

  Doc-ids are assigned by position, so the caller controls sharding by
  controlling order — documents from the same host land in the same shard if
  the caller groups them, which is what makes host-scoped queries cheap."
  ([sink hash-fn docs] (build! sink hash-fn docs {}))
  ([sink hash-fn docs opts]
   (let [{:keys [shard-count dict-fanout chunk-size meta-chunk-size title-weight route?]
          :as opts} (merge default-opts opts)
         docs (vec (map-indexed (fn [i d] (assoc d :id i)) docs))
         n (count docs)
         tfs (mapv #(analyze/doc-terms % title-weight) docs)
         dls (mapv #(reduce + 0 (vals %)) tfs)
         avgdl (if (zero? n) 1.0 (/ (double (reduce + 0 dls)) n))
         df (reduce (fn [acc tf] (reduce (fn [a t] (update a t (fnil inc 0))) acc (keys tf)))
                    {} tfs)
         idfs (reduce-kv (fn [m t d] (assoc m t (idf n d))) {} df)
         ranges (if (zero? n) [] (shard-ranges n shard-count))
         built-shards
         (vec (map-indexed
               (fn [sid [start end]]
                 (let [local (subvec docs start end)
                       ;; term -> [[doc-id impact] ...] for this shard only
                       postings (reduce
                                 (fn [acc i]
                                   (let [tf (nth tfs i) dl (nth dls i)]
                                     (reduce-kv
                                      (fn [a t f]
                                        (let [imp (impact opts (get idfs t) f dl avgdl)]
                                          (if (pos? imp)
                                            (update a t (fnil conj []) [i imp])
                                            a)))
                                      acc tf)))
                                 {} (range start end))
                       built (mapv (fn [t]
                                     [t (build-postings sink hash-fn chunk-size
                                                        t (get postings t))])
                                   (sort (keys postings)))
                       entries (mapv (fn [[t b]]
                                       [t {:df (:df b) :postings (:cid b)}])
                                     built)
                       {:keys [root height]} (build-dict-tree sink hash-fn dict-fanout entries)]
                   {:shard {:id sid
                            :doc-base start
                            :doc-count (- end start)
                            :dict-root root
                            :dict-height height
                            :term-count (count entries)
                            :meta-dir (build-meta sink hash-fn meta-chunk-size start local)}
                    ;; What the routing dictionary is assembled from. Emitted
                    ;; here because the max-impact is known exactly once, while
                    ;; this shard's postings are in hand.
                    :route (mapv (fn [[t b]]
                                   [t {:shard sid :max-impact (:max-impact b)
                                       :df (:df b) :postings (:cid b)}])
                                 built)}))
               ranges))
         shards (mapv :shard built-shards)
         ;; ── routing dictionary ──────────────────────────────────────
         ;; term -> every shard that holds it, with that shard's largest
         ;; possible contribution for the term. One tree for the whole index,
         ;; so locating a term costs log(global vocabulary) round trips ONCE
         ;; instead of once per shard. See `route.cljc` for why that is the
         ;; fan-out term and why pruning on these bounds stays exact.
         route-entries (when route?
                         (->> (mapcat :route built-shards)
                              (group-by first)
                              (map (fn [[t es]]
                                     [t (vec (sort-by :shard (map second es)))]))
                              (sort-by first)
                              vec))
         route-tree (when route?
                      (build-dict-tree sink hash-fn dict-fanout route-entries))
         manifest (cond->
                   {:kind :manifest
                    :version 1
                    :analyzer {:form :ascii+cjk-bigram :title-weight title-weight}
                    :scorer (select-keys opts [:k1-x100 :b-x100 :impact-scale])
                    :layout (select-keys opts [:dict-fanout :chunk-size :meta-chunk-size])
                    :stats {:doc-count n
                            :term-count (count df)
                            :avgdl-x1000 (round-int (* 1000 avgdl))
                            :shard-count (count shards)}
                    :shards shards}
                    route? (assoc :route-root (:root route-tree)
                                  :route-height (:height route-tree)))]
     {:manifest-cid (block/put-block! sink hash-fn manifest)
      :stats (:stats manifest)})))
