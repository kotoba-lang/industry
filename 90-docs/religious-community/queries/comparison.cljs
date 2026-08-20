#!/usr/bin/env nbb
; Religious-Community Framework Query Runner
; Usage: nbb comparison.cljs [query-1|query-2|query-3|query-4|query-5|all] [--format csv|table]

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[datascript.core :as d])

;;; ============================================================
;;; SETUP: Load EDN data and transact
;;; ============================================================

(def schema
  {:org/id {:db/unique :db.unique/identity}
   :tradition/id {:db/unique :db.unique/identity}
   :axis/id {:db/unique :db.unique/identity}
   :doctrine/id {:db/unique :db.unique/identity}
   :actor/id {:db/unique :db.unique/identity}
   :charter/id {:db/unique :db.unique/identity}
   :lexicon/id {:db/unique :db.unique/identity}
   :org-axis/org {:db/valueType :db.type/ref}
   :org-axis/axis {:db/valueType :db.type/ref}
   :org/tradition {:db/valueType :db.type/ref}
   :org/charter {:db/valueType :db.type/ref}
   :org/core-beliefs {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :org/primary-lexicons {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :org/tier-a-actors {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :org/tier-b-actors {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :doctrine/tradition {:db/valueType :db.type/ref}
   :doctrine/conflicting-with {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :actor/belongs-to-org {:db/valueType :db.type/ref}
   :actor/reporting-to {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :charter/org {:db/valueType :db.type/ref}
   :charter/key-sections {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :lexicon/tradition {:db/valueType :db.type/ref}
   :lexicon/entries {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :tradition/parent-tradition {:db/valueType :db.type/ref}
   :tradition/major-doctrines {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :region/parent-region {:db/valueType :db.type/ref}
   :org/primary-regions {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}})

(def conn (d/create-conn schema))

;; Load and transact schema + data
(let [schema-data (edn/read-string (slurp "religious-community.datoms.edn"))
      sample-data (edn/read-string (slurp "sample-orgs.edn"))]
  (d/transact conn schema-data)
  (d/transact conn sample-data))

(println "[✓] Data loaded into DataScript")

;;; ============================================================
;;; QUERY 1: Mental Model Axis Distribution by Tradition
;;; ============================================================

(defn query-1 []
  (println "\n=== QUERY 1: Axis Distribution by Tradition ===\n")
  (let [results (d/q '[:find ?tradition ?axis-name (avg ?value)
                       :where
                       [?org :org/tradition ?trad-ref]
                       [?trad-ref :tradition/name ?tradition]
                       [?org-axis :org-axis/org ?org]
                       [?org-axis :org-axis/axis ?axis]
                       [?axis :axis/name ?axis-name]
                       [?org-axis :org-axis/current-value ?value]]
                    @conn)]
    (print-table ["Tradition" "Axis" "Avg Value"]
                 (sort-by (juxt first second)
                          (map (fn [[t a v]] [t a (Math/round v)])
                               results)))))

;;; ============================================================
;;; QUERY 2: Doctrinal Conflict Graph
;;; ============================================================

(defn query-2 []
  (println "\n=== QUERY 2: Doctrinal Conflicts (Cross-Tradition) ===\n")
  (let [results (d/q '[:find ?doctrine-a ?tradition-a ?doctrine-b ?tradition-b
                       :where
                       [?doc-a :doctrine/id ?id-a]
                       [?doc-a :doctrine/name ?doctrine-a]
                       [?doc-a :doctrine/tradition ?trad-a]
                       [?trad-a :tradition/name ?tradition-a]
                       [?doc-a :doctrine/conflicting-with ?doc-b]
                       [?doc-b :doctrine/name ?doctrine-b]
                       [?doc-b :doctrine/tradition ?trad-b]
                       [?trad-b :tradition/name ?tradition-b]
                       [(not= ?trad-a ?trad-b)]]
                    @conn)]
    (if (empty? results)
      (println "No conflicts found (or conflicts not yet populated).")
      (print-table ["Doctrine A" "Tradition A" "Doctrine B" "Tradition B"]
                   (sort-by (juxt first second third) results)))))

;;; ============================================================
;;; QUERY 3: Actor Structure Patterns
;;; ============================================================

(defn query-3 []
  (println "\n=== QUERY 3: Actor Structure by Org (Tier & Authority) ===\n")
  (let [results (d/q '[:find ?org-name ?tier ?authority (count ?actor)
                       :where
                       [?org :org/name ?org-name]
                       [?actor :actor/belongs-to-org ?org]
                       [?actor :actor/tier ?tier]
                       [?actor :actor/authority ?authority]]
                    @conn)]
    (if (empty? results)
      (println "No actor data found.")
      (print-table ["Organization" "Tier" "Authority" "Count"]
                   (sort-by (juxt first second third)
                            (map (fn [[o t a c]] [o (name t) (name a) c])
                                 results))))))

;;; ============================================================
;;; QUERY 4: Charter Amendment Authority
;;; ============================================================

(defn query-4 []
  (println "\n=== QUERY 4: Charter Amendment Authority Comparison ===\n")
  (let [results (d/q '[:find ?org-name ?charter-name ?authority-level
                       :where
                       [?org :org/name ?org-name]
                       [?charter :charter/org ?org]
                       [?charter :charter/name ?charter-name]
                       [?charter :charter/authority-level ?authority-level]]
                    @conn)]
    (if (empty? results)
      (println "No charter data found.")
      (print-table ["Organization" "Charter Name" "Amendment Authority"]
                   (sort-by first
                            (map (fn [[o c a]] [o c (name a)])
                                 results))))))

;;; ============================================================
;;; QUERY 5: Lexicon Language Coverage
;;; ============================================================

(defn query-5 []
  (println "\n=== QUERY 5: Lexicon Language Coverage by Tradition ===\n")
  (let [results (d/q '[:find ?tradition-name ?languages
                       :where
                       [?lex :lexicon/tradition ?trad]
                       [?trad :tradition/name ?tradition-name]
                       [?lex :lexicon/language ?lang]
                       :with [?trad ?lang]]
                    @conn)]
    (if (empty? results)
      (println "No lexicon data found.")
      (let [grouped (group-by first results)
            summary (map (fn [[trad rows]]
                           [trad (str/join ", " (sort (distinct (map second rows))))])
                         grouped)]
        (print-table ["Tradition" "Languages"]
                     (sort-by first summary))))))

;;; ============================================================
;;; TABLE PRINTING UTILITY
;;; ============================================================

(defn print-table [headers rows]
  (let [col-widths (map (fn [col-idx]
                          (max (count (get headers col-idx))
                               (apply max 0 (map #(count (str (get % col-idx)))
                                                 rows))))
                        (range (count headers)))
        format-row (fn [row]
                     (str/join " | "
                               (map (fn [val width]
                                      (let [s (str val)]
                                        (str s (str/repeat (- width (count s)) " "))))
                                    row col-widths)))]
    ;; Print header
    (println (format-row headers))
    (println (str/repeat (reduce + (map #(+ % 3) col-widths)) "-"))
    ;; Print rows
    (doseq [row rows]
      (println (format-row row)))))

;;; ============================================================
;;; MAIN: CLI ARGUMENT HANDLING
;;; ============================================================

(defn -main [& args]
  (let [query-arg (or (first args) "all")
        format-arg (if (some #(str/starts-with? % "--format") args)
                     (second (filter #(str/starts-with? % "--format") args))
                     "table")]
    (case query-arg
      "query-1" (query-1)
      "query-2" (query-2)
      "query-3" (query-3)
      "query-4" (query-4)
      "query-5" (query-5)
      "all" (do (query-1) (query-2) (query-3) (query-4) (query-5))
      (do (println "Usage: nbb comparison.cljs [query-1|query-2|query-3|query-4|query-5|all] [--format csv|table]")
          (println "")
          (println "Running all queries by default...")
          (query-1) (query-2) (query-3) (query-4) (query-5))))
  (println "\n[✓] Queries complete."))

(-main (aget js/process.argv 2) (aget js/process.argv 3))
