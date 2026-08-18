#!/usr/bin/env nbb
; Extract BMC products into religious-community org entities
; Usage: nbb scripts/extract-bmc-to-orgs.cljs

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "fs"))

;;; ============================================================
;;; BMC PRODUCTS METADATA
;;; ============================================================

(def bmc-products
  [
   {:repo-name "cloud-itonami"
    :product-id :itonami
    :org-id :org-itonami-l3-operator
    :org-name "itonami (L3 Business Operator)"
    :layer :l3-business-operations
    :tradition :tradition-economic-cooperative
    :governance :democratic
    :mission "Cloud-based ops layer: business operator governance, AI-driven decision layer, attestation verification"}

   {:repo-name "cloud-manimani"
    :product-id :manimani
    :org-id :org-manimani-l5-os
    :org-name "manimani (L5 Personal OS)"
    :layer :l5-personal-wellbecoming
    :tradition :tradition-technical-meritocratic
    :governance :meritocratic
    :mission "Personal operating system: lifecycle data ownership, wellbecoming metrics, user-controlled identity"}

   {:repo-name "cloud-murakumo"
    :product-id :murakumo
    :org-id :org-murakumo-l1-infra
    :org-name "murakumo (L1 LLM Inference Infrastructure)"
    :layer :l1-llm-inference
    :tradition :tradition-technical-meritocratic
    :governance :meritocratic
    :mission "Distributed LLM inference: model serving, inference optimization, multi-user scheduling"}

   {:repo-name "net-kotobase"
    :product-id :kotoba
    :org-id :org-kotobase-l2-storage
    :org-name "net-kotobase (L2 Storage Hosting)"
    :layer :l2-storage-hosting
    :tradition :tradition-technical-meritocratic
    :governance :democratic
    :mission "Decentralized storage: datagram persistence, IPFS interop, immutable audit ledger"}

   {:repo-name "app-aozora"
    :product-id :aozora
    :org-id :org-aozora-l4-sns
    :org-name "aozora (L4 SNS + Messenger)"
    :layer :l4-social-communication
    :tradition :tradition-technical-meritocratic
    :governance :consensus
    :mission "Decentralized social network: peer-to-peer messaging, public timelines, identity federation"}

   {:repo-name "etzhayyim"
    :product-id :etzhayyim
    :org-id :org-etzhayyim-l0-platform
    :org-name "etzhayyim (L0 Artificial Organism Platform)"
    :layer :l0-organism-platform
    :tradition :tradition-synthetic-etzhayyim
    :governance :council-based
    :mission "Non-profit public AI organism identity layer: RAD attestation, governance ledger, organism registry"}

   {:repo-name "gftd-portfolio"
    :product-id :gftd
    :org-id :org-gftd-portfolio
    :org-name "gftd (Portfolio & Market Vertical)"
    :layer :portfolio-management
    :tradition :tradition-economic-cooperative
    :governance :hierarchical
    :mission "Market vertical umbrella: cross-product coordination, portfolio lean canvas, lean growth loops"}
  ])

;;; ============================================================
;;; DATA EXTRACTION FROM BUSINESS-MODEL.EDN FILES
;;; ============================================================

(defn extract-bmc-data [bmc-file-path]
  "Extract metadata from a business-model.edn file"
  (try
    (let [file-content (.toString (.readFileSync fs bmc-file-path "utf8"))
          content (edn/read-string file-content)
          doc (first content)]
      {:product (:doc/product doc)
       :title (:doc/title doc)
       :layer (:doc/layer doc)
       :as-of (:doc/as-of doc)
       :body (:doc/body doc)
       :success true})
    (catch js/Error e
      {:error (str "Failed to parse " bmc-file-path)
       :success false})))

(defn extract-founding-date [product-id]
  "Estimate founding date from product"
  (case product-id
    :itonami 2024
    :manimani 2025
    :murakumo 2023
    :kotoba 2023
    :aozora 2024
    :etzhayyim 2026
    :gftd 2024
    2024))

(defn extract-member-count [product-id]
  "Estimate member/contributor count for BMC product"
  (case product-id
    :itonami 5
    :manimani 3
    :murakumo 8
    :kotoba 10
    :aozora 6
    :etzhayyim 1
    :gftd 20
    1))

;;; ============================================================
;;; ORG ENTITY GENERATION
;;; ============================================================

(defn generate-org-entity [bmc-meta idx]
  "Generate a :org/* EDN entity from BMC metadata"
  (let [db-id (- 20001 idx)
        founding-date (extract-founding-date (:product-id bmc-meta))
        member-count (extract-member-count (:product-id bmc-meta))]
    {:db/id db-id
     :org/id (:org-id bmc-meta)
     :org/name (:org-name bmc-meta)
     :org/canonical-name (str "cloud-" (name (:product-id bmc-meta)))
     :org/type :secular-community
     :org/tradition [:tradition/id (:tradition bmc-meta)]
     :org/founding-date founding-date
     :org/founding-location "Digital / Decentralized"
     :org/member-count member-count
     :org/primary-regions [[:region/id :region-asia-east] [:region/id :region-north-america]]
     :org/governance-model (:governance bmc-meta)
     :org/mission-statement (:mission bmc-meta)
     :org/primary-language :en}))

(defn generate-axis-entities [org-id db-id-base]
  "Generate 14 :org-axis/* entities for a new org (14 per org)"
  (let [axis-ids [:axis-governance :axis-economic :axis-identity :axis-agency
                  :axis-social-delivery :axis-physical-presence :axis-public-trust
                  :axis-legal-contract :axis-land-resource :axis-education
                  :axis-dispute-resolution :axis-external-recognition
                  :axis-member-engagement :axis-doctrine-clarity]
        axis-values [75 70 80 60 50 40 60 85 30 70 70 60 65 70]  ; default BMC product estimates
        measurement-basis "simulation"
        caveat "BMC product org; metrics inferred from project metadata and activity"]
    (mapv (fn [idx axis-id value]
            {:db/id (+ db-id-base idx)
             :org-axis/org org-id
             :org-axis/axis [:axis/id axis-id]
             :org-axis/current-value value
             :org-axis/measurement-date "2026-07-18"
             :org-axis/measurement-basis measurement-basis
             :org-axis/caveat caveat})
          (range 14)
          axis-ids
          axis-values)))

;;; ============================================================
;;; MAIN PIPELINE
;;; ============================================================

(defn -main []
  (println "\n=== Phase 1: Extract BMC Products to Orgs ===\n")

  ;; 1. Generate org entities
  (let [org-entities (mapv
                      (fn [bmc-meta idx]
                        (generate-org-entity bmc-meta idx))
                      bmc-products
                      (range))

        ;; 2. Generate axis entities (14 per org)
        axis-entities (mapcat
                       (fn [org-ent idx]
                         (generate-axis-entities
                          [:org/id (:org/id org-ent)]
                          (+ 21000 (* idx 100))))
                       org-entities
                       (range))

        ;; 3. Combine all
        all-entities (concat org-entities axis-entities)

        ;; 4. Write to temp file
        output-path "/tmp/bmc-orgs-phase1.edn"]

    ;; Write EDN
    (.writeFileSync fs output-path (str all-entities))

    (println (str "✓ Generated " (count org-entities) " org entities"))
    (println (str "✓ Generated " (count axis-entities) " axis entities"))
    (println (str "✓ Total: " (count all-entities) " entities"))
    (println (str "✓ Output: " output-path))
    (println "\nNext: Review /tmp/bmc-orgs-phase1.edn and merge into sample-orgs.edn")
    (println "Entities generated (sample):")
    (println (first org-entities))))

(-main)
