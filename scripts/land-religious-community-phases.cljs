#!/usr/bin/env nbb
;; Land the phase2/phase3 religious-community generator output into
;; 90-docs/religious-community/ as separate, honestly-labeled EDN files.
;;
;; Why this exists (ADR-2607257200 iteration 2):
;;   scripts/generate-phase2-religious-variants.cljs and
;;   scripts/generate-phase3-indigenous-secular.cljs write to /tmp only, so their
;;   output (37 + 22 orgs) was never queryable. Landing them verbatim is NOT
;;   acceptable either: every :org-axis/* entity they emit claims
;;   :org-axis/measurement-basis "document-analysis" while the values are in fact
;;   hardcoded LLM priors inside the generator source. Presenting those as
;;   measurements is exactly what ADR-2607203000 forbids.
;;
;; This script therefore:
;;   1. runs nothing itself — it consumes the generators' /tmp output;
;;   2. drops orgs/traditions already present in sample-orgs.edn or
;;      religious-community.datoms.edn (no double counting on the query plane);
;;   3. rewrites every axis row to :org-axis/measurement-basis "llm-prior-unverified"
;;      with an explicit caveat naming the generator, so comparison queries can
;;      filter measured rows from priors;
;;   4. stamps :source/dataset so provenance is queryable (ADR-2607252000).
;;
;; Usage:
;;   nbb scripts/generate-phase2-religious-variants.cljs
;;   nbb scripts/generate-phase3-indigenous-secular.cljs
;;   nbb scripts/land-religious-community-phases.cljs

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "fs"))
(def path (js/require "path"))

(def root (or (.-LAND_ROOT js/process.env) (.cwd js/process)))
(def rc-dir (.join path root "90-docs" "religious-community"))

(defn slurp* [f] (.readFileSync fs f "utf8"))
(defn spit* [f s] (.writeFileSync fs f s))

(defn read-entities [f]
  (if (.existsSync fs f)
    (edn/read-string (slurp* f))
    (do (println (str "! missing: " f)) [])))

(def existing-org-ids
  (->> [(.join path rc-dir "sample-orgs.edn")
        (.join path rc-dir "religious-community.datoms.edn")]
       (mapcat read-entities)
       (keep :org/id)
       set))

(def existing-tradition-ids
  (->> [(.join path rc-dir "religious-community.datoms.edn")]
       (mapcat read-entities)
       (keep :tradition/id)
       set))

(defn org? [e] (contains? e :org/id))
(defn tradition? [e] (contains? e :tradition/id))
(defn axis-row? [e] (contains? e :org-axis/org))

(defn axis-org-id [e]
  (let [v (:org-axis/org e)]
    (if (vector? v) (second v) v)))

(defn relabel-axis [generator e]
  (assoc e
         :org-axis/measurement-basis "llm-prior-unverified"
         :org-axis/caveat (str "LLM-authored prior emitted by " generator
                               "; never measured. Do not read as a measurement "
                               "(ADR-2607203000). Superseded once a sourced value exists.")))

(defn process [{:keys [in out dataset generator]}]
  (let [entities (read-entities in)
        traditions (->> entities
                        (filter tradition?)
                        (remove #(contains? existing-tradition-ids (:tradition/id %))))
        orgs (->> entities
                  (filter org?)
                  (remove #(contains? existing-org-ids (:org/id %))))
        kept-org-ids (set (map :org/id orgs))
        axes (->> entities
                  (filter axis-row?)
                  (filter #(contains? kept-org-ids (axis-org-id %)))
                  (map (partial relabel-axis generator)))
        ;; The phase3 generator carries placeholder member counts (e.g. 999999999
        ;; for "Coursera/MOOC Ecosystem"). Landing a placeholder as a population
        ;; figure is worse than landing no figure.
        drop-placeholder (fn [e]
                           (if (and (:org/member-count e) (>= (:org/member-count e) 999999999))
                             (-> e
                                 (dissoc :org/member-count)
                                 (assoc :org/member-count-basis
                                        "unknown — generator carried a 999999999 placeholder, dropped on landing"))
                             e))
        stamp (fn [e] (-> e
                          drop-placeholder
                          (dissoc :db/id)
                          (assoc :source/dataset dataset
                                 :org/data-provenance "llm-recall-unverified")))
        final (map stamp (concat traditions orgs axes))
        header (str ";;; " dataset " — landed by scripts/land-religious-community-phases.cljs\n"
                    ";;; Source generator: " generator "\n"
                    ";;; ADR-2607257200 (iteration 2). Every value here is an LLM-authored\n"
                    ";;; estimate, not a measurement: org rows carry\n"
                    ";;; :org/data-provenance \"llm-recall-unverified\" and axis rows carry\n"
                    ";;; :org-axis/measurement-basis \"llm-prior-unverified\". Filter on those\n"
                    ";;; before using any number from this file.\n"
                    ";;; Dropped as duplicates of sample-orgs.edn / religious-community.datoms.edn: "
                    (str/join ", " (sort (map name (filter existing-org-ids
                                                           (keep :org/id (filter org? entities))))))
                    "\n")]
    (spit* out (str header "[\n" (str/join "\n" (map pr-str final)) "\n]\n"))
    (println (str "✓ " (.basename path out)
                  " — traditions " (count traditions)
                  ", orgs " (count orgs)
                  ", axis rows " (count axes)
                  ", dropped-dup-orgs " (- (count (filter org? entities)) (count orgs))))))

(process {:in "/tmp/phase2-religious-variants.edn"
          :out (.join path rc-dir "orgs-phase2-religious-variants.edn")
          :dataset "religious-community-phase2"
          :generator "scripts/generate-phase2-religious-variants.cljs"})

(process {:in "/tmp/phase3-indigenous-secular.edn"
          :out (.join path rc-dir "orgs-phase3-indigenous-secular.edn")
          :dataset "religious-community-phase3"
          :generator "scripts/generate-phase3-indigenous-secular.cljs"})
