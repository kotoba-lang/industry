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
  (let [industry (get-industry isic)]
    {:isic (str isic)
     :business-id (:business-id industry)
     :industry (:name industry)
     :required-technologies (:required-technologies industry)
     :optional-technologies (:optional-technologies industry)
     :operating-states (:operating-states industry)
     :technology-stack (mapv #(select-keys % [:id :name :layer :capabilities :repos :contracts])
                             (technology-stack isic))}))
