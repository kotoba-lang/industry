(ns kotobase-shard-index.synth
  "Deterministic synthetic corpora for tests and benches.

  It lives in `src/` rather than being copied into each caller because the
  first version was copied, and the copies drifted into a corpus that made the
  measurements meaningless: the vocabulary was built from hyphenated words like
  `alpha-one`, and the analyzer splits on the hyphen. Every query was silently
  a two-term OR over the two commonest tokens in the corpus, so a benchmark
  meant to show selective lookups measured the least selective query possible
  (1,399 GETs against a 3,705-block index).

  What a corpus has to get right for these measurements to mean anything:

  - **Terms must survive the analyzer.** Generate what the tokenizer produces.
  - **Zipf, over a vocabulary far larger than the queries.** Real text has a
    long tail; a small vocabulary makes every posting list the whole database,
    which is the one condition under which no index can beat a scan.
  - **Varying document length**, or BM25's length normalisation is a constant
    and every score ties.
  - **No randomness the caller cannot reproduce.** Same seed, same bytes."
  (:require [clojure.string :as str]))

(defn lcg [seed] (atom (mod seed 2147483648)))

(defn nxt! [r]
  (swap! r (fn [s] (mod (+ (* 1103515245 s) 12345) 2147483648))))

(defn- unit [r] (/ (double (nxt! r)) 2147483648.0))

(defn zipf-rank
  "Rank in `[0, vocab-size)` under a power law: rank ≈ vocab-size^u. Common
  words are common, and the tail is long enough that a mid-rank term is
  genuinely selective."
  [r vocab-size]
  (let [u (unit r)]
    (min (dec vocab-size)
         (int (Math/pow vocab-size u)))))

(defn term
  "Vocabulary is `w<rank>` — one ASCII run, so the analyzer returns it intact."
  [rank]
  (str "w" rank))

(defn corpus
  "`n` documents over a `vocab-size` vocabulary. Returns `{:url :title :text}`."
  ([n seed] (corpus n seed 20000))
  ([n seed vocab-size]
   (let [r (lcg seed)]
     (vec (for [i (range n)]
            (let [len (+ 20 (mod (nxt! r) 120))]
              {:url (str "https://example.test/" i)
               :title (str/join " " (repeatedly (inc (mod (nxt! r) 3))
                                                #(term (zipf-rank r vocab-size))))
               :text (str/join " " (repeatedly len
                                               #(term (zipf-rank r vocab-size))))}))))))

(defn probe-queries
  "Query terms spanning the frequency range, so a measurement is not secretly
  all-common or all-rare. Ranks are fixed, not sampled: a benchmark whose
  queries move between runs cannot detect a regression."
  [vocab-size]
  (let [r (fn [frac] (term (max 1 (int (* frac vocab-size)))))]
    [(r 0.0005)                            ; very common
     (r 0.01)                              ; common
     (r 0.15)                              ; mid
     (str (r 0.01) " " (r 0.15))           ; two-term, mixed selectivity
     (str (r 0.15) " " (r 0.5) " " (r 0.8))]))
