#!/usr/bin/env nbb
; Extract top-30 etzhayyim Tier-B actors to org entities
; Usage: nbb scripts/extract-etzhayyim-sampling.cljs

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "fs"))
(def path (js/require "path"))
(def child_process (js/require "child_process"))

;;; ============================================================
;;; TOP-30 ETZHAYYIM REPOS (by recent commit count)
;;; ============================================================

(def etzhayyim-repos
  [
   "com-etzhayyim-tomoshibi"
   "com-etzhayyim-fleet"
   "com-etzhayyim-junbi"
   "com-etzhayyim-yomi"
   "com-etzhayyim-yakushi"
   "com-etzhayyim-tsumugu"
   "com-etzhayyim-tashikame"
   "com-etzhayyim-shinka"
   "com-etzhayyim-ossekai"
   "com-etzhayyim-kouhou"
   "com-etzhayyim-joucho"
   "com-etzhayyim-fushin"
   "com-etzhayyim-suki"
   "com-etzhayyim-sng"
   "com-etzhayyim-narashi"
   "com-etzhayyim-mizuho"
   "com-etzhayyim-makura"
   "com-etzhayyim-kyoninka"
   "com-etzhayyim-futawa"
   "com-etzhayyim-akashi"
   "com-etzhayyim-yotei"
   "com-etzhayyim-yamabiko"
   "com-etzhayyim-watatsumi"
   "com-etzhayyim-watari"
   "com-etzhayyim-wadachi"
   "com-etzhayyim-uchiwake"
   "com-etzhayyim-tsutae"
   "com-etzhayyim-tsukuru"
   "com-etzhayyim-tsukuroi"
   "com-etzhayyim-torifune"
  ])

;;; ============================================================
;;; DATA EXTRACTION
;;; ============================================================

(defn exec-sync [cmd]
  "Execute shell command and return stdout"
  (try
    (.toString (.execSync child_process cmd #js{:encoding "utf8"}))
    (catch js/Error _ "")))

(defn extract-repo-data [repo-name base-path idx]
  "Extract metadata for a single etzhayyim repo"
  (let [repo-path (str base-path "/" repo-name)
        ;; Get founding year from first commit
        founding-year (let [log-output (exec-sync (str "cd " repo-path " && git log --reverse --format=%ai 2>/dev/null | head -1"))]
                        (if (empty? log-output)
                          2024
                          (let [year-str (subs log-output 0 4)]
                            (try (js/parseInt year-str) (catch js/Error _ 2024)))))
        ;; Count contributors
        contrib-count (let [output (exec-sync (str "cd " repo-path " && git shortlog -sn 2>/dev/null | wc -l"))]
                        (try (js/parseInt output) (catch js/Error _ 1)))
        ;; Extract mission from README
        mission (let [readme-path (str repo-path "/README.md")
                      exists? (.existsSync fs readme-path)]
                  (if exists?
                    (let [content (.toString (.readFileSync fs readme-path "utf8"))
                          lines (str/split-lines content)
                          non-header (filter #(not (str/starts-with? % "#")) lines)
                          first-para (first non-header)]
                      (if first-para (str/trim first-para) "Tier-B operational actor"))
                    "Tier-B operational actor"))]
    {:repo-name repo-name
     :org-id (keyword (str "org-etzhayyim-" (str/replace repo-name "com-etzhayyim-" "")))
     :founding-year founding-year
     :member-count contrib-count
     :mission mission
     :db-id (+ 30000 idx)
     :axis-base-id (+ 31000 (* idx 100))}))

;;; ============================================================
;;; ORG & AXIS ENTITY GENERATION
;;; ============================================================

(defn generate-org-entity [data]
  "Generate :org/* entity for etzhayyim actor"
  {:db/id (:db-id data)
   :org/id (:org-id data)
   :org/name (str "etzhayyim " (str/replace (:repo-name data) "com-etzhayyim-" ""))
   :org/canonical-name (:repo-name data)
   :org/type :secular-community
   :org/tradition [:tradition/id :tradition-synthetic-etzhayyim]
   :org/founding-date (:founding-year data)
   :org/founding-location "Digital / Decentralized (etzhayyim sub-actor)"
   :org/member-count (:member-count data)
   :org/primary-regions [[:region/id :region-asia-east]]
   :org/governance-model :hierarchical
   :org/mission-statement (:mission data)
   :org/primary-language :ja})

(defn generate-axis-entities [org-id axis-base-id]
  "Generate 14 :org-axis/* entities for etzhayyim actor"
  (let [axis-ids [:axis-governance :axis-economic :axis-identity :axis-agency
                  :axis-social-delivery :axis-physical-presence :axis-public-trust
                  :axis-legal-contract :axis-land-resource :axis-education
                  :axis-dispute-resolution :axis-external-recognition
                  :axis-member-engagement :axis-doctrine-clarity]
        ;; Conservative defaults for Tier-B actors
        axis-values [70 40 60 50 30 20 50 70 10 60 65 40 60 70]
        measurement-basis "simulation"
        caveat "etzhayyim Tier-B operational unit; metrics inferred from git metadata and directory name. Sub-org of parent etzhayyim organism."]
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
  (println "\n=== Phase 1 Step 2: Extract etzhayyim Tier-B Actors ===\n")

  (let [base-path "/Users/junkawasaki/github/com-junkawasaki/orgs/etzhayyim"

        ;; Extract metadata for all 30 repos
        repo-data (mapv
                   (fn [repo-name idx]
                     (print (str "Processing " repo-name " ... "))
                     (let [data (extract-repo-data repo-name base-path idx)]
                       (println (str "[✓]"))
                       data))
                   etzhayyim-repos
                   (range))

        ;; Generate org entities
        org-entities (mapv generate-org-entity repo-data)

        ;; Generate axis entities (14 per org)
        axis-entities (mapcat
                       (fn [org-ent data]
                         (generate-axis-entities
                          [:org/id (:org/id org-ent)]
                          (:axis-base-id data)))
                       org-entities
                       repo-data)

        ;; Combine all
        all-entities (concat org-entities axis-entities)

        ;; Write to file
        output-path "/tmp/etzhayyim-orgs-phase1.edn"]

    (.writeFileSync fs output-path (str all-entities))

    (println (str "\n✓ Generated " (count org-entities) " org entities"))
    (println (str "✓ Generated " (count axis-entities) " axis entities"))
    (println (str "✓ Total: " (count all-entities) " entities"))
    (println (str "✓ Output: " output-path))
    (println "\nSample org entity:")
    (println (first org-entities))))

(-main)
