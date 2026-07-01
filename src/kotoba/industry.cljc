(ns kotoba.industry
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [kotoba.technology :as technology]))

(def registry-resource "kotoba/industry/registry.edn")

(defn registry []
  (edn/read-string (slurp (io/resource registry-resource))))

(defn industries
  ([] (:industries (registry)))
  ([reg] (:industries reg)))

(defn by-id
  ([] (by-id (registry)))
  ([reg] (into {} (map (juxt :id identity) (industries reg)))))

(defn get-industry
  ([isic] (get-industry (registry) isic))
  ([reg isic] (get (by-id reg) (str isic))))

(defn required-technologies
  ([isic] (required-technologies (registry) isic))
  ([reg isic] (:required-technologies (get-industry reg isic))))

(defn optional-technologies
  ([isic] (optional-technologies (registry) isic))
  ([reg isic] (:optional-technologies (get-industry reg isic))))

(defn technology-stack
  "Resolve the required technology records for an ISIC business."
  ([isic] (technology-stack (registry) isic))
  ([reg isic]
   (technology/stack (required-technologies reg isic))))

(defn readiness
  "Return an execution-readiness summary for an ISIC and available technology IDs."
  [isic available-tech-ids]
  (let [industry (get-industry isic)
        required (set (:required-technologies industry))
        available (set available-tech-ids)
        missing (set/difference required available)]
    {:isic (str isic)
     :business-id (:business-id industry)
     :ready? (empty? missing)
     :required required
     :available available
     :missing missing
     :operating-states (:operating-states industry)}))

(defn execution-plan
  "Data contract cloud-itonami can expose in business state."
  [isic]
  (let [industry (get-industry isic)
        stack (technology-stack isic)]
    {:isic (str isic)
     :business-id (:business-id industry)
     :industry (:name industry)
     :maturity (:maturity industry)
     :required-technologies (:required-technologies industry)
     :optional-technologies (:optional-technologies industry)
     :operating-states (:operating-states industry)
     :ui-ready? (some :ui? stack)
     :export-ready? (some :export? stack)
     :technology-stack (mapv #(select-keys % [:id :name :layer :capabilities :repos :contracts :ui? :export?])
                             stack)}))

(defn maturity
  "Return the maturity level of an ISIC entry: :spec (registry only),
  :blueprint (blueprint repo published), or :implemented (source actor exists).
  Defaults to :spec when unset."
  [isic]
  (let [industry (get-industry isic)]
    (or (:maturity industry)
        (cond
          (:implemented? industry) :implemented
          (:repo industry)         :blueprint
          :else                    :spec))))

(defn maturity-summary
  "Aggregate maturity counts across all industries."
  []
  (let [inds (industries)]
    {:total      (count inds)
     :spec       (count (filter #(= :spec (maturity (:id %))) inds))
     :blueprint  (count (filter #(= :blueprint (maturity (:id %))) inds))
     :implemented (count (filter #(= :implemented (maturity (:id %))) inds))}))

(defn maturity-roadmap
  "Return the next maturity step for an ISIC entry: :spec→:blueprint→:implemented,
  with the action required to advance and whether a capability lib with UI/export
  already backs it (so the spec entry can be promoted cheaply)."
  [isic]
  (let [industry (get-industry isic)
        level (maturity isic)
        stack (technology-stack isic)
        ui? (some :ui? stack)
        export? (some :export? stack)
        has-repo (boolean (:repo industry))]
    {:isic (str isic)
     :maturity level
     :next-step (condp = level
                  :spec       :blueprint
                  :blueprint  :implemented
                  :implemented nil)
     :next-action (condp = level
                    :spec       "publish a blueprint repo (scaffold + blueprint.edn + docs)"
                    :blueprint  "implement the actor (source + tests)"
                    :implemented "at maturity ceiling")
     :ui-ready? ui?
     :export-ready? export?
     :has-repo has-repo}))
