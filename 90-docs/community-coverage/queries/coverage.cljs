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
;;   nbb 90-docs/community-coverage/queries/coverage.cljs integrity  ; referential checks only
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

;; Referential integrity. Cross-dataset refs are legitimate — several entries
;; point at orgs held in 90-docs/religious-community/ — so resolve against both.
(def sibling-ids
  (let [d (.join path (.dirname path dir) "religious-community")]
    (if (.existsSync fs d)
      (->> (.readdirSync fs d)
           (filter #(str/ends-with? % ".edn"))
           (mapcat #(read-edn (.join path d %)))
           (filter map?)
           (keep :org/id)
           (map name)
           set)
      #{})))

(defn integrity []
  (let [ids (into sibling-ids (map (comp name :org/id) orgs))
        cat-ids (set (map (comp name :community-category/id) categories))
        dangling (for [o orgs :let [r (:org/related-org o)]
                       :when (and r (not (ids (name r))))]
                   [(name (:org/id o)) (name r)])
        bad-cat (for [o orgs :when (not (cat-ids (name (:org/category o))))]
                  [(name (:org/id o)) (str (:org/category o))])
        dupes (for [[k v] (frequencies (map (comp name :org/id) orgs)) :when (> v 1)] [k v])
        no-basis (for [o orgs :when (not (:org/member-count-basis o))] [(name (:org/id o)) "no member-count-basis"])
        problems (concat dangling bad-cat dupes no-basis)]
    (println "\nINTEGRITY")
    (if (empty? problems)
      (println (str "  clean — " (count orgs) " orgs, no dangling refs, no unknown categories,"
                    " no duplicate ids, every org states a count basis"))
      (doseq [[a b] problems] (println (str "  !! " a " -> " b))))))

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

    (println "\nFORM FAMILY  (cross-national analogues: what is this the local version OF?)")
    (let [fam (tally :org/form-family orgs)
          in-family (reduce + 0 (map second fam))]
      (doseq [[k v] fam] (line (name k) v n))
      (println (str "    -> " in-family "/" n " have a cross-national analogue recorded; "
                    (- n in-family) " stand alone so far")))

    (println "\nISIC CLASS via category hint  (join key into cloud-itonami actors)")
    (let [by-cat (into {} (map (juxt :community-category/id identity)) categories)
          isic-of (fn [o] (get-in by-cat [(:org/category o) :community-category/isic-hint]))
          residual (count (filter #(= "9499" (isic-of %)) orgs))]
      (doseq [[k v] (tally isic-of orgs)] (line k v n))
      (println (str "    -> " residual "/" n " (" (pct residual n)
                    "%) fall into ISIC 9499 'other membership organizations n.e.c.',"))
      (println "       i.e. the international standard has one residual bucket for them"))

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
  "integrity" (integrity)
  (do (report) (integrity)))
