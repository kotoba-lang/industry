;; query.clj — loads pricing-intelligence.datoms.edn + pricing-intelligence-ledger.edn into a real
;; datascript.core conn and runs example queries. JVM Clojure (last-resort runtime per CLAUDE.md,
;; used here only as a one-off verification/query tool, not an app runtime — the nbb path was tried
;; first and rejected: the npm `datascript` package ships a Closure-advanced-compiled JS bundle with
;; its own private copy of ClojureScript's keyword/comparator internals, which collides with nbb/SCI's
;; own cljs.core when round-tripping keyword values — every transact failed with
;; "Cannot compare :k to :k" even on trivial data, regardless of schema).
;;
;; Run (from anywhere — pass the superproject root as the one arg; the repo root's own `deps.edn` is
;; NOT a Clojure deps manifest, it's unrelated workspace-metadata EDN, so invoke `clojure` from outside
;; the repo tree to avoid clojure.tools.deps trying and failing to parse it):
;;   cd /tmp && clojure -Sdeps '{:deps {datascript/datascript {:mvn/version "1.7.1"}}}' \
;;     -M /path/to/com-junkawasaki/90-docs/pricing-intelligence/query.clj /path/to/com-junkawasaki
(require '[datascript.core :as d]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def root (or (first *command-line-args*) "."))
(def base-file (str root "/90-docs/pricing-intelligence/pricing-intelligence.datoms.edn"))
(def ledger-file (str root "/90-docs/pricing-intelligence/pricing-intelligence-ledger.edn"))

(defn read-edn-vector [path]
  (edn/read-string (slurp path)))

(defn read-ledger-lines
  "One EDN map per non-comment, non-blank line."
  [path]
  (with-open [r (io/reader path)]
    (->> (line-seq r)
         (map clojure.string/trim)
         (remove #(or (= "" %) (clojure.string/starts-with? % ";;")))
         (mapv edn/read-string))))

(def base-entries (read-edn-vector base-file))
(def schema-entries (filter :db/ident base-entries))
(def catalog-entries (remove :db/ident base-entries))
(def ledger-entries (read-ledger-lines ledger-file))

;; DataScript's create-conn schema is far narrower than Datomic's: it only recognizes
;; :db/valueType #{:db.type/ref :db.type/tuple}, :db/cardinality, and :db/unique — a scalar
;; :db/valueType like :db.type/string/:double/:long/:keyword (valid, Datomic-compatible EDN in the
;; base file) makes DataScript's own schema validator throw. Project down to what DataScript accepts;
;; the base file itself stays full Datomic-compatible EDN (unchanged) for real Datomic consumers.
(defn ds-schema-value [m]
  (cond-> (dissoc m :db/ident)
    (not= :db.type/ref (:db/valueType m)) (dissoc :db/valueType)))

(def schema
  (into {} (map (fn [m] [(:db/ident m) (ds-schema-value m)])) schema-entries))

(println "schema attrs:" (count schema))
(println "catalog entities:" (count catalog-entries))
(println "ledger events:" (count ledger-entries))

(def conn (d/create-conn schema))
(d/transact! conn catalog-entries)
(d/transact! conn ledger-entries)

(def db (d/db conn))

(println "\n-- total datoms:" (count (d/datoms db :eavt)))

(println "\n-- example 1: every competitor observed for ISIC 7912 (Tour operator activities), with disclosure tier --")
(doseq [[pname price tier] (sort (d/q '[:find ?pname ?price ?tier
                                          :where
                                          [?v :vertical/id :vertical/isic-7912]
                                          [?o :obs/vertical ?v]
                                          [?o :obs/product ?p]
                                          [?p :product/name ?pname]
                                          [?o :obs/price-summary ?price]
                                          [?o :obs/disclosure-tier ?tier]]
                                        db))]
  (println (format "  %-12s %-45s %s" tier pname price)))

(println "\n-- example 2: recommended price band per vertical, joined to cluster label (first 8, sorted) --")
(doseq [[clabel vname low high unit]
        (->> (d/q '[:find ?clabel ?vname ?low ?high ?unit
                    :where
                    [?v :vertical/name ?vname]
                    [?v :vertical/cluster ?c]
                    [?c :cluster/label ?clabel]
                    [?r :rec/vertical ?v]
                    [?r :rec/band-low ?low]
                    [?r :rec/band-high ?high]
                    [?r :rec/unit ?unit]]
                  db)
             (sort-by first)
             (take 8))]
  (println (format "  [%s] %-45s $%s-%s (%s)" clabel vname low high unit)))

(println "\n-- example 3: disclosure-tier breakdown across all observations (proves real aggregate query, not just lookup) --")
(doseq [[tier n] (->> (d/q '[:find ?tier (count ?o)
                              :where [?o :obs/disclosure-tier ?tier]]
                            db)
                       (sort-by second >))]
  (println (format "  %-12s %d" tier n)))

(println "\nOK — base + ledger loaded, transacted, and queried successfully.")
