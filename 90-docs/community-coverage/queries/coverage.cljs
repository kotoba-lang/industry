#!/usr/bin/env nbb
;; Coverage reporter for 90-docs/community-coverage/ (ADR-2607257200).
;;
;; Answers the question the corpus exists to answer honestly: how much of it is
;; actually verified, and where are the holes? Reads the EDN files directly
;; (clojure.edn, no DataScript) so it works without the unified query plane and
;; stays fast enough to run on every iteration.
;;
;; Usage:
;;   nbb 90-docs/community-coverage/queries/coverage.cljs            ; full report
;;   nbb 90-docs/community-coverage/queries/coverage.cljs unpinned   ; list unpinned orgs
;;   nbb 90-docs/community-coverage/queries/coverage.cljs regions    ; region histogram
;;
;; The report deliberately prints the UNKNOWNS, not just the totals: a coverage
;; corpus that only reports its size is measuring the wrong thing.

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "fs"))
(def path (js/require "path"))

(def dir (.join path (or (.-COVERAGE_ROOT js/process.env) (.cwd js/process))
                "90-docs" "community-coverage"))

(defn read-edn [f]
  (try (edn/read-string (.readFileSync fs f "utf8"))
       (catch :default e
         (println (str "!! parse failure: " (.basename path f) " — " (.-message e)))
         [])))

(def entities
  (->> (.readdirSync fs dir)
       (filter #(str/ends-with? % ".edn"))
       sort
       (mapcat #(read-edn (.join path dir %)))
       (filter map?)))

(def orgs (filter :org/id entities))
(def categories (filter :community-category/id entities))

(defn tally [f coll]
  (->> coll (map f) (remove nil?) frequencies (sort-by (comp - val))))

(defn pct [n total] (if (zero? total) 0 (js/Math.round (* 100 (/ n total)))))

(defn bar [n total]
  (apply str (repeat (js/Math.round (* 24 (/ n (max 1 total)))) "█")))

(defn line [label n total]
  (println (str "  " (subs (str label "                              ") 0 26)
                (subs (str "   " n) (- (count (str n)) 1))
                "  " (bar n total) " " (pct n total) "%")))

(defn report []
  (let [n (count orgs)
        pinned (count (filter #(= "source-pinned" (:org/data-provenance %)) orgs))
        with-count (count (filter :org/member-count orgs))
        with-basis (count (filter :org/member-count-basis orgs))
        unknown-basis (count (filter #(str/starts-with? (str (:org/member-count-basis %)) "unknown") orgs))]
    (println (str "\n=== community-coverage: " n " organizations, "
                  (count categories) " categories ===\n"))

    (println "VERIFICATION")
    (line "source-pinned" pinned n)
    (line "llm-recall-unverified" (- n pinned) n)
    (println (str "    primary tier: " (count (filter #(= :primary (:org/source-tier %)) orgs))
                  "   secondary tier: " (count (filter #(= :secondary (:org/source-tier %)) orgs))))

    (println "\nLEGAL REGISTRATION  (the reason this corpus exists)")
    (doseq [[k v] (tally :org/legal-registration orgs)] (line (name k) v n))
    (let [invisible (count (filter #(#{:unregistered :mostly-unregistered} (:org/legal-registration %)) orgs))]
      (println (str "    -> " invisible "/" n " (" (pct invisible n)
                    "%) are invisible to company/LEI registries")))

    (println "\nENTITY KIND")
    (doseq [[k v] (tally :org/entity-kind orgs)] (line (name k) v n))

    (println "\nCATEGORY")
    (doseq [[k v] (tally :org/category orgs)] (line (name k) v n))

    (println "\nREGION (primary)")
    (doseq [[k v] (tally :org/region-primary orgs)] (line (name k) v n))

    (println "\nHONESTY OF COUNTS")
    (println (str "    " with-count "/" n " assert :org/member-count"))
    (println (str "    " with-basis "/" n " state a :org/member-count-basis"))
    (println (str "    " unknown-basis "/" n " explicitly say the count is unknown"))
    (when (< with-basis n)
      (println (str "    !! " (- n with-basis) " org(s) carry no basis at all — fix these first")))

    (println "\nTOP UNPINNED BY ASSERTED SIZE  (pin these next)")
    (doseq [o (->> orgs
                   (remove #(= "source-pinned" (:org/data-provenance %)))
                   (filter :org/member-count)
                   (sort-by (comp - :org/member-count))
                   (take 10))]
      (println (str "    " (.toLocaleString (:org/member-count o)) "  " (:org/name o))))
    (println)))

(defn unpinned []
  (doseq [o (->> orgs (remove #(= "source-pinned" (:org/data-provenance %))) (sort-by :org/category))]
    (println (str (name (:org/category o)) "  " (:org/name o)))))

(defn regions []
  (doseq [[k v] (tally :org/region-primary orgs)]
    (println (str (name k) " " v))
    (doseq [o (filter #(= k (:org/region-primary %)) orgs)]
      (println (str "    " (:org/name o))))))

(case (first *command-line-args*)
  "unpinned" (unpinned)
  "regions" (regions)
  (report))
