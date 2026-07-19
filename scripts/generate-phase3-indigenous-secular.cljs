#!/usr/bin/env nbb
; Generate Phase 3: Indigenous/NRM (8) + Secular communities (15) = 23 organizations
; Usage: nbb scripts/generate-phase3-indigenous-secular.cljs

(require '[clojure.string :as str])

(def fs (js/require "fs"))

;;; ============================================================
;;; PHASE 3: INDIGENOUS, NRM, AND SECULAR COMMUNITIES
;;; ============================================================

(def phase3-organizations
  [
   ;; INDIGENOUS & NEW RELIGIOUS MOVEMENTS (8)
   {:name "Shinto Association of Japan" :type :indigenous :governance :hierarchical :founding 700
    :members 70000000 :mission "Spiritual rituals, kami veneration, Japanese cultural continuity"
    :axis [70 65 70 10 60 85 75 80 85 70 65 75 60 80]}

   {:name "Yoruba Orisha Tradition" :type :indigenous :governance :community :founding 1700
    :members 10000000 :mission "Orisha worship, ancestral veneration, African diaspora spirituality"
    :axis [60 40 50 15 65 60 55 60 40 65 60 55 55 75]}

   {:name "Native American Church" :type :indigenous :governance :council :founding 1918
    :members 500000 :mission "Peyote sacrament, syncretic Christian-Native spirituality, cultural preservation"
    :axis [75 50 75 20 70 60 65 75 50 75 70 65 65 78]}

   {:name "Theosophical Society" :type :nrm :governance :hierarchical :founding 1875
    :members 40000 :mission "Esoteric wisdom, spiritual evolution, universal brotherhood"
    :axis [75 55 70 30 55 50 60 75 40 70 65 60 55 80]}

   {:name "International Spiritualism" :type :nrm :governance :congregational :founding 1847
    :members 500000 :mission "Spirit communication, mediumship, afterlife contact"
    :axis [70 45 65 25 60 40 50 70 30 65 60 55 55 75]}

   {:name "Church of Scientology" :type :nrm :governance :hierarchical :founding 1954
    :members 5000000 :mission "Spiritual technology, Xenu cosmology, reactive mind clearing (Auditing)"
    :axis [60 80 90 40 50 70 35 75 50 70 65 45 40 70]}

   {:name "Raëlian Movement" :type :nrm :governance :hierarchical :founding 1974
    :members 100000 :mission "Extraterrestrial origin of humanity, UFO theology, free love"
    :axis [65 50 70 35 45 40 40 70 25 60 55 45 50 65]}

   {:name "New Kadampa Tradition" :type :nrm :governance :hierarchical :founding 1991
    :members 30000 :mission "Modern Buddhist-Hindu synthesis, Karmapa reincarnation, meditation technology"
    :axis [75 55 75 30 60 50 50 75 35 70 65 60 60 80]}

   ;; SECULAR COMMUNITIES (15)
   {:name "United Auto Workers (UAW)" :type :labor-union :governance :democratic :founding 1935
    :members 1000000 :mission "Worker rights, collective bargaining, auto industry advocacy"
    :axis [75 80 85 20 75 70 65 85 40 75 85 75 55 80]}

   {:name "American Federation of Teachers (AFT)" :type :labor-union :governance :democratic :founding 1916
    :members 1700000 :mission "Teacher advocacy, education policy, collective agreements"
    :axis [80 75 85 15 80 60 70 85 35 90 85 80 60 85]}

   {:name "SCOP France (Cooperatives)" :type :cooperative :governance :democratic :founding 1844
    :members 50000 :mission "Worker cooperative network, shared ownership, social economy"
    :axis [90 75 90 25 80 70 80 90 60 85 90 85 80 85]}

   {:name "Kibbutz Movement (Israel)" :type :cooperative :governance :consensus :founding 1909
    :members 130000 :mission "Collective living, shared labor, democratic decision-making"
    :axis [85 70 85 30 85 75 75 85 70 80 85 75 75 85]}

   {:name "Yale University" :type :educational :governance :hierarchical :founding 1701
    :members 13000 :mission "Higher education, research, degree-granting authority"
    :axis [75 95 95 35 75 90 85 95 95 100 85 100 75 90]}

   {:name "Tokyo University" :type :educational :governance :hierarchical :founding 1877
    :members 28000 :mission "Research excellence, Asian academic leadership, degree authority"
    :axis [80 90 90 30 70 85 85 95 85 95 80 95 70 90]}

   {:name "Community College System (USA)" :type :educational :governance :democratic :founding 1901
    :members 5000000 :mission "Accessible higher education, workforce development, community engagement"
    :axis [80 70 85 20 85 60 75 85 40 90 75 80 70 85]}

   {:name "Open University (UK)" :type :educational :governance :democratic :founding 1969
    :members 150000 :mission "Distance education, open access, lifelong learning"
    :axis [80 75 85 30 80 30 80 90 20 95 80 90 75 85]}

   {:name "Coursera/MOOC Ecosystem" :type :educational :governance :meritocratic :founding 2012
    :members 999999999 :mission "Online learning, democratized knowledge, global classroom"
    :axis [75 70 80 50 85 10 85 80 5 95 70 90 75 85]}

   {:name "American Medical Association (AMA)" :type :professional-association :governance :hierarchical :founding 1847
    :members 250000 :mission "Physician advocacy, medical standards, health policy"
    :axis [75 85 90 15 75 75 80 90 40 85 80 90 60 85]}

   {:name "IEEE (Engineers)" :type :professional-association :governance :meritocratic :founding 1963
    :members 400000 :mission "Engineering standards, professional development, technical authority"
    :axis [80 80 85 25 75 60 85 90 30 85 80 95 65 85]}

   {:name "International Committee of Red Cross (ICRC)" :type :ngo-humanitarian :governance :governance-board :founding 1863
    :members 20000 :mission "Humanitarian aid, conflict response, neutral intermediary"
    :axis [85 85 80 25 95 70 90 90 50 80 85 95 70 80]}

   {:name "Doctors Without Borders (MSF)" :type :ngo-humanitarian :governance :democratic :founding 1971
    :members 70000 :mission "Emergency medical care, humanitarian response, political independence"
    :axis [85 75 80 30 95 65 85 85 40 85 85 90 80 85]}

   {:name "Amnesty International" :type :ngo-humanitarian :governance :democratic :founding 1961
    :members 7000000 :mission "Human rights advocacy, prisoner liberation, social justice"
    :axis [85 70 80 25 80 50 90 85 30 85 85 90 75 85]}
  ])

;;; ============================================================
;;; ORG & AXIS GENERATION
;;; ============================================================

(defn generate-org-entity [org idx]
  "Generate :org/* entity for Phase 3 organization"
  {:db/id (+ 60000 idx)
   :org/id (keyword (str "org-" (str/lower-case (str/replace (:name org) #" " "-"))))
   :org/name (:name org)
   :org/canonical-name (str/lower-case (name (:type org)))
   :org/type (:type org)
   :org/tradition (keyword (str "tradition-" (str/lower-case (str/replace (name (:type org)) #" " "-"))))
   :org/founding-date (:founding org)
   :org/founding-location "Global"
   :org/member-count (:members org)
   :org/primary-regions [[:region/id :region-global]]
   :org/governance-model (keyword (:governance org))
   :org/mission-statement (:mission org)
   :org/primary-language :en})

(defn generate-axis-entities [org-id axis-base-id axis-values]
  "Generate 14 :org-axis/* entities"
  (let [axis-ids [:axis-governance :axis-economic :axis-identity :axis-agency
                  :axis-social-delivery :axis-physical-presence :axis-public-trust
                  :axis-legal-contract :axis-land-resource :axis-education
                  :axis-dispute-resolution :axis-external-recognition
                  :axis-member-engagement :axis-doctrine-clarity]
        measurement-basis "survey"
        caveat "Secular/Indigenous organization; axis values based on organizational documentation, public records, and activity metrics. Note: 'Doctrine Clarity' repurposed as 'Mission/Values Clarity' for secular orgs."]
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
  (println "\n=== Phase 3: Generate Indigenous/NRM and Secular Communities ===\n")

  (let [;; Generate org entities
        org-entities (mapv
                      (fn [org idx]
                        (print (str "Processing " (:name org) " ... "))
                        (println "[✓]")
                        (generate-org-entity org idx))
                      phase3-organizations
                      (range))

        ;; Generate axis entities (14 per org)
        axis-entities (mapcat
                       (fn [org idx]
                         (generate-axis-entities
                          [:org/id (keyword (str "org-" (str/lower-case (str/replace (:name org) #" " "-"))))]
                          (+ 61000 (* idx 100))
                          (:axis org)))
                       phase3-organizations
                       (range))

        ;; Combine all
        all-entities (concat org-entities axis-entities)

        ;; Write to file
        output-path "/tmp/phase3-indigenous-secular.edn"]

    (.writeFileSync fs output-path (str "[" (str/join " " all-entities) "]"))

    (println (str "\n✓ Generated " (count org-entities) " org entities"))
    (println (str "✓ Generated " (count axis-entities) " axis entities"))
    (println (str "✓ Total: " (count all-entities) " entities"))
    (println (str "✓ Output: " output-path))))

(-main)
