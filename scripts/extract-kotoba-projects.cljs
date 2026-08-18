#!/usr/bin/env nbb
; Extract kotoba-lang top-20 projects to org entities
; Usage: nbb scripts/extract-kotoba-projects.cljs

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "fs"))
(def child_process (js/require "child_process"))

;;; ============================================================
;;; KOTOBA-LANG PROJECT METADATA
;;; Canonical (11) + Supporting (9) = 20 total
;;; ============================================================

(def kotoba-projects
  [
   ;; Canonical infrastructure (11)
   {:name "kotoba" :type :core-language :governance :hierarchical
    :mission "Capability-safe Lisp/EDN runtime; WASM compiler"}
   {:name "kotobase" :type :core-database :governance :hierarchical
    :mission "Persistent datom store (Datomic equivalent); immutable data"}
   {:name "kami-engine" :type :core-3d :governance :hierarchical
    :mission "3D asset/contract repository; WebGPU/WGSL rendering engine"}
   {:name "kotoba-ui" :type :ui-framework :governance :democratic
    :mission "Default UI/UX single-require point; design-system wrapper"}
   {:name "shitsuke" :type :ui-design :governance :democratic
    :mission "UI design system (tokens, semantic contracts); light-dark theme"}
   {:name "html" :type :utility :governance :consensus
    :mission "EDN to HTML Hiccup renderer; web templating"}
   {:name "css" :type :utility :governance :consensus
    :mission "CSS as EDN data structures; style composition"}
   {:name "browser" :type :framework :governance :hierarchical
    :mission "Kotoba-native WASM browser engine orchestrator"}
   {:name "character" :type :core-text :governance :hierarchical
    :mission "Portable character/glyph rendering; CJK support"}
   {:name "murakumo" :type :infrastructure :governance :meritocratic
    :mission "Control plane for kotoba WASM lattice/fleet; distributed ops"}
   {:name "langgraph-clj" :type :agent-framework :governance :democratic
    :mission "StateGraph agent framework (LangGraph analogue)"}

   ;; Supporting projects (9)
   {:name "arrangement" :type :core-algorithm :governance :meritocratic
    :mission "Shared CLJC for in-memory CEAVT indexing; data-structure fundamental"}
   {:name "prolly-tree" :type :core-algorithm :governance :meritocratic
    :mission "Content-addressed persistent tree (IPFS-native); merkle-based versioning"}
   {:name "mise" :type :domain-app :governance :meritocratic
    :mission "E-commerce system (cart, checkout, inventory); business logic"}
   {:name "atprotocol" :type :interop-library :governance :consensus
    :mission "AT Protocol compatibility layer; federation/social interop"}
   {:name "org-signal" :type :utility :governance :consensus
    :mission "Signals (reactive streams) collection; reactive programming"}
   {:name "annotation" :type :domain-library :governance :consensus
    :mission "Document/text annotation model; NLP/markup infrastructure"}
   {:name "manga-viewer" :type :domain-app :governance :meritocratic
    :mission "RTL-aware manga reader; CJK cultural content"}
   {:name "appkit" :type :platform-binding :governance :consensus
    :mission "Desktop/dense-data platform binding; thin wrapper"}
   {:name "uikit" :type :platform-binding :governance :consensus
    :mission "Mobile/touch/card-first platform binding; thin wrapper"}
  ])

;;; ============================================================
;;; DATA EXTRACTION
;;; ============================================================

(defn exec-sync [cmd]
  "Execute shell command and return stdout"
  (try
    (.toString (.execSync child_process cmd #js{:encoding "utf8"}))
    (catch js/Error _ "")))

(defn extract-project-data [proj-meta base-path idx]
  "Extract metadata for a single kotoba-lang project"
  (let [proj-path (str base-path "/" (:name proj-meta))
        ;; Get founding year from first commit
        founding-year (let [log-output (exec-sync (str "cd " proj-path " && git log --reverse --format=%ai 2>/dev/null | head -1"))]
                        (if (empty? log-output)
                          2023
                          (let [year-str (subs log-output 0 4)]
                            (try (js/parseInt year-str) (catch js/Error _ 2023)))))
        ;; Count contributors
        contrib-count (let [output (exec-sync (str "cd " proj-path " && git shortlog -sn 2>/dev/null | wc -l"))]
                        (try (js/parseInt output) (catch js/Error _ 1)))
        ;; Read README for mission if provided
        readme-mission (let [readme-path (str proj-path "/README.md")
                             exists? (.existsSync fs readme-path)]
                         (if exists?
                           (let [content (.toString (.readFileSync fs readme-path "utf8"))
                                 lines (str/split-lines content)
                                 non-header (filter #(not (str/starts-with? % "#")) lines)
                                 first-para (first non-header)]
                             (if first-para (str/trim first-para) nil))
                           nil))]
    {:name (:name proj-meta)
     :type (:type proj-meta)
     :governance (:governance proj-meta)
     :org-id (keyword (str "org-kotoba-" (:name proj-meta)))
     :mission (or readme-mission (:mission proj-meta))
     :founding-year founding-year
     :member-count contrib-count
     :db-id (+ 40000 idx)
     :axis-base-id (+ 41000 (* idx 100))}))

;;; ============================================================
;;; ORG & AXIS ENTITY GENERATION
;;; ============================================================

(defn generate-org-entity [data]
  "Generate :org/* entity for kotoba-lang project"
  {:db/id (:db-id data)
   :org/id (:org-id data)
   :org/name (str "kotoba-lang/" (:name data))
   :org/canonical-name (:name data)
   :org/type :secular-community
   :org/tradition [:tradition/id :tradition-technical-meritocratic]
   :org/founding-date (:founding-year data)
   :org/founding-location "Digital / Decentralized (kotoba-lang ecosystem)"
   :org/member-count (:member-count data)
   :org/primary-regions [[:region/id :region-north-america] [:region/id :region-europe] [:region/id :region-asia-east]]
   :org/governance-model (:governance data)
   :org/mission-statement (:mission data)
   :org/primary-language :en})

(defn governance-axis-defaults [governance-model]
  "Return default axis values based on governance model"
  (case governance-model
    :hierarchical [80 70 75 60 40 30 70 85 30 80 75 80 70 75]  ; core infra, high governance maturity
    :democratic [75 65 70 65 50 35 75 80 30 75 70 75 75 75]  ; frameworks, consensus on changes
    :meritocratic [70 60 65 70 45 35 75 75 35 70 60 70 65 70]  ; domain/algorithm, activity-based authority
    :consensus [65 50 60 65 40 30 70 70 30 60 55 65 60 60]   ; utilities, lightweight governance
    [70 60 65 65 45 35 70 75 35 70 65 70 65 70]))           ; default

(defn generate-axis-entities [org-id axis-base-id governance-model]
  "Generate 14 :org-axis/* entities for kotoba-lang project"
  (let [axis-ids [:axis-governance :axis-economic :axis-identity :axis-agency
                  :axis-social-delivery :axis-physical-presence :axis-public-trust
                  :axis-legal-contract :axis-land-resource :axis-education
                  :axis-dispute-resolution :axis-external-recognition
                  :axis-member-engagement :axis-doctrine-clarity]
        axis-values (governance-axis-defaults governance-model)
        measurement-basis "document-analysis"
        caveat "kotoba-lang sub-project; governance inferred from project structure and repo activity. Social delivery = 0 (technical library, no direct services)."]
    (mapv (fn [idx axis-id value]
            {:db/id (+ axis-base-id idx)
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
  (println "\n=== Phase 1 Step 3: Extract kotoba-lang Top-20 Projects ===\n")

  (let [base-path "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang"

        ;; Extract metadata for all 20 projects
        proj-data (mapv
                   (fn [proj-meta idx]
                     (print (str "Processing " (:name proj-meta) " ... "))
                     (let [data (extract-project-data proj-meta base-path idx)]
                       (println (str "[✓]"))
                       data))
                   kotoba-projects
                   (range))

        ;; Generate org entities
        org-entities (mapv generate-org-entity proj-data)

        ;; Generate axis entities (14 per org)
        axis-entities (mapcat
                       (fn [org-ent data]
                         (generate-axis-entities
                          [:org/id (:org/id org-ent)]
                          (:axis-base-id data)
                          (:governance data)))
                       org-entities
                       proj-data)

        ;; Combine all
        all-entities (concat org-entities axis-entities)

        ;; Write to file
        output-path "/tmp/kotoba-orgs-phase1.edn"]

    (.writeFileSync fs output-path (str all-entities))

    (println (str "\n✓ Generated " (count org-entities) " org entities"))
    (println (str "✓ Generated " (count axis-entities) " axis entities"))
    (println (str "✓ Total: " (count all-entities) " entities"))
    (println (str "✓ Output: " output-path))
    (println "\nSample org entity:")
    (println (first org-entities))))

(-main)
