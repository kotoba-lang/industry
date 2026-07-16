;; repo-reality query proof — loads repo-reality.datoms.edn + repo-reality-ledger.edn into a
;; real DataScript db (same nbb + npm "datascript" runtime as
;; orgs/kotoba-lang/property/src/kotoba/property/datascript_runtime.cljs) and runs a join
;; query across claims + verification events, proving the "queryable via DataScript" part of
;; the design is not just aspirational EDN-shape-compatibility (as documented in
;; 90-docs/design-quality/design-quality.datoms.edn's header comments) but an actually
;; runnable pipeline.
;;
;; Run (root package.json already declares "datascript" as a dependency — `npm ci` once,
;; same as any other nbb script in this superproject; no NODE_PATH borrowing needed):
;;   npm ci && nbb 90-docs/repo-reality/query.cljs

(ns repo-reality.query
  (:require ["fs" :as fs]
            [cljs.reader :as reader]
            [clojure.string :as str]
            ["datascript" :as datascript]))

(def ds (.-default datascript))

(defn record->ds [record]
  (let [result #js {}]
    (doseq [[k v] record]
      (aset result
            (if (keyword? k) (str (namespace k) "/" (name k)) (str k))
            (cond (keyword? v) (name v) :else v)))
    result))

(defn slurp-ledger-lines [path]
  (->> (str/split-lines (.readFileSync fs path "utf8"))
       (remove #(or (str/blank? %) (str/starts-with? (str/trim %) ";")))
       (map reader/read-string)))

(def base (reader/read-string (.readFileSync fs "90-docs/repo-reality/repo-reality.datoms.edn" "utf8")))
(def catalog (remove #(contains? % :db/valueType) base))            ; drop schema-attr defs, keep :project/:axis/:claim entities
(def events (slurp-ledger-lines "90-docs/repo-reality/repo-reality-ledger.edn"))

(def db (.db_with ds (.empty_db ds) (to-array (map record->ds (concat catalog events)))))

(println "--- claim text + latest verification score/note, joined via DataScript ---")
(def join-query
  "[:find ?text ?axis ?score ?note
    :where
    [?e \"eval/claim\" ?cid]
    [?e \"eval/axis\" ?axis]
    [?e \"eval/score\" ?score]
    [?e \"eval/note\" ?note]
    [?claim \"claim/id\" ?cid]
    [?claim \"claim/text\" ?text]]")
(doseq [[text axis score note] (js->clj (.q ds join-query db))]
  (println (str "\n[" axis " score=" score "] " text "\n  -> " note)))

;; The ledger is append-only — a fixed claim's OLD low score never disappears. Naively
;; averaging/filtering over every historical event (as an earlier version of this script did)
;; drags down means and re-flags already-fixed drift forever. Reduce to the LATEST event per
;; (claim, axis) pair (max :eval/at) before any current-state reporting below; DataScript can
;; express single-hop joins/aggregates cleanly but "latest per group" needs a plain reduce.
(println "\n--- reducing to latest event per (claim, axis) pair (append-only ledger has full history) ---")
(def latest-query
  "[:find ?cid ?axis ?score ?at ?pname
    :where
    [?e \"eval/claim\" ?cid] [?e \"eval/axis\" ?axis] [?e \"eval/score\" ?score] [?e \"eval/at\" ?at]
    [?claim \"claim/id\" ?cid] [?claim \"claim/project\" ?pid]
    [?p \"project/id\" ?pid] [?p \"project/name\" ?pname]]")
(def latest
  (->> (js->clj (.q ds latest-query db))
       (group-by (fn [[cid axis _ _ _]] [cid axis]))
       (map (fn [[_ rows]] (apply max-key (fn [[_ _ _ at _]] at) rows)))
       (map (fn [[cid axis score at pname]] {:cid cid :axis axis :score score :at at :pname pname}))))
(println (str "  " (count latest) " distinct (claim, axis) pairs, reduced from " (count (js->clj (.q ds latest-query db))) " raw events."))

(println "\n--- mean CURRENT score per axis ---")
(doseq [[axis rows] (sort-by first (group-by :axis latest))]
  (println (str axis ": " (/ (Math/round (* (/ (reduce + (map :score rows)) (count rows)) 1000)) 1000.0))))

(println "\n--- mean CURRENT score per project x axis (cross-project comparison) ---")
(doseq [[[pname axis] rows] (sort-by first (group-by (juxt :pname :axis) latest))]
  (println (str pname " / " axis ": " (/ (Math/round (* (/ (reduce + (map :score rows)) (count rows)) 1000)) 1000.0))))

(println "\n--- claims CURRENTLY flagged with drift risk (doc-code-drift score < 0.6, latest event only) ---")
(def cid->text (into {} (map (fn [c] [(name (:claim/id c)) (:claim/text c)])
                              (filter :claim/id catalog))))
(let [flagged (filter #(and (= (:axis %) "doc-code-drift") (< (:score %) 0.6)) latest)]
  (if (empty? flagged)
    (println "(none -- all doc-code-drift claims currently score >= 0.6 as of their LATEST run, i.e. no live silent/undisclosed drift right now)")
    (doseq [{:keys [cid score]} flagged] (println (str "[score=" score "] " (get cid->text cid))))))

;; ---- org-wide generic events (:eval/repo, no hand-curated :claim/*, see orgwide_scan.cljs) ----
;; Same append-only-ledger "reduce to latest per key" discipline as the claim-based section
;; above, keyed by (repo, axis) instead of (claim, axis).
(println "\n--- org-wide: latest event per (repo, axis) pair ---")
(def orgwide-latest-query
  "[:find ?repo ?axis ?score ?at ?note
    :where [?e \"eval/repo\" ?repo] [?e \"eval/axis\" ?axis] [?e \"eval/score\" ?score]
           [?e \"eval/at\" ?at] [?e \"eval/note\" ?note]]")
(def orgwide-raw (js->clj (.q ds orgwide-latest-query db)))
(def orgwide-latest
  (->> orgwide-raw
       (group-by (fn [[repo axis _ _ _]] [repo axis]))
       (map (fn [[_ rows]] (apply max-key (fn [[_ _ _ at _]] at) rows)))
       (map (fn [[repo axis score at note]] {:repo repo :axis axis :score score :at at :note note}))))
(println (str "  " (count orgwide-latest) " distinct (repo, axis) pairs, reduced from " (count orgwide-raw) " raw events, across "
              (count (distinct (map :repo orgwide-latest))) " repos."))

(when (seq orgwide-latest)
  (println "\n--- org-wide: score distribution per axis ---")
  (doseq [[axis rows] (sort-by first (group-by :axis orgwide-latest))]
    (let [buckets (group-by (fn [{:keys [score]}] (/ (Math/round (* score 4)) 4.0)) rows)]
      (println (str axis " (n=" (count rows) ", mean=" (/ (Math/round (* (/ (reduce + (map :score rows)) (count rows)) 1000)) 1000.0) "):"))
      (doseq [[bucket rs] (sort-by first > buckets)]
        (println (str "    " bucket ": " (count rs) " repos")))))

  (println "\n--- org-wide: worst repo-hygiene (score < 0.5) ---")
  (let [worst (->> orgwide-latest (filter #(and (= (:axis %) "repo-hygiene") (< (:score %) 0.5))) (sort-by :score))]
    (if (empty? worst)
      (println "(none)")
      (doseq [{:keys [repo score note]} (take 20 worst)]
        (println (str "  [" score "] " repo " -- " note)))))
  (when (> (count (filter #(and (= (:axis %) "repo-hygiene") (< (:score %) 0.5)) orgwide-latest)) 20)
    (println (str "  ... and " (- (count (filter #(and (= (:axis %) "repo-hygiene") (< (:score %) 0.5)) orgwide-latest)) 20) " more (truncated to 20)")))

  (println "\n--- org-wide: doc-internal-coherence < 0.6 (candidates for the kotobase-M5-style pattern) ---")
  (let [flagged (->> orgwide-latest (filter #(and (= (:axis %) "doc-internal-coherence") (< (:score %) 0.6))) (sort-by :score))]
    (if (empty? flagged)
      (println "(none)")
      (doseq [{:keys [repo score note]} flagged]
        (println (str "  [" score "] " repo " -- " note))))))
