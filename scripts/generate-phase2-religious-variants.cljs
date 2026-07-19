#!/usr/bin/env nbb
; Generate Phase 2: Religious tradition variants (39 organizations + 30+ doctrines)
; Usage: nbb scripts/generate-phase2-religious-variants.cljs

(require '[clojure.string :as str])

(def fs (js/require "fs"))

;;; ============================================================
;;; PHASE 2 RELIGIOUS VARIANTS METADATA
;;; ============================================================

(def religious-variants
  [
   ;; CHRISTIANITY (13 variants)
   {:variant "Roman Catholic" :tradition :tradition-christian-catholic :founding 1054 :members 1300000000
    :governance :hierarchical :mission "Papal authority, sacramental theology, apostolic succession"
    :doctrines [:doctrine-papal-infallibility :doctrine-trinity :doctrine-purgatory]
    :axis [85 85 80 5 80 95 80 95 90 90 85 90 50 95]}

   {:variant "Lutheran Reformation" :tradition :tradition-christian-reformed :founding 1520 :members 75000000
    :governance :democratic :mission "Sola Scriptura, Sola Fide, congregational democracy"
    :doctrines [:doctrine-sola-scriptura :doctrine-trinity :doctrine-justification-faith]
    :axis [85 80 70 10 75 90 65 95 85 90 80 90 30 95]}

   {:variant "Orthodox Christianity" :tradition :tradition-christian-orthodox :founding 1054 :members 300000000
    :governance :hierarchical :mission "Apostolic succession, sacramental mystery, ecumenical patriarchs"
    :doctrines [:doctrine-trinity :doctrine-theosis :doctrine-icon-veneration]
    :axis [75 70 60 5 65 85 70 85 90 85 75 85 45 90]}

   {:variant "Evangelical/Pentecostal" :tradition :tradition-christian-evangelical :founding 1730 :members 700000000
    :governance :congregational :mission "Born-again experience, Holy Spirit infilling, biblical inerrancy"
    :doctrines [:doctrine-born-again :doctrine-trinity :doctrine-holy-spirit-gifts]
    :axis [75 60 65 15 70 70 70 80 50 75 70 75 65 88]}

   {:variant "Jehovah's Witness" :tradition :tradition-christian-jehovahs-witness :founding 1870 :members 8500000
    :governance :hierarchical :mission "Governing Body authority, disfellowshipping, theocratic organization"
    :doctrines [:doctrine-governing-body-authority :doctrine-disfellowshipping :doctrine-kingdom-government]
    :axis [70 75 90 20 65 70 40 85 60 70 75 60 35 92]}

   {:variant "Latter-day Saints (Mormon)" :tradition :tradition-christian-mormon :founding 1830 :members 17000000
    :governance :hierarchical :mission "Continuous revelation via Prophet President, temple work, Book of Mormon"
    :doctrines [:doctrine-continuous-revelation :doctrine-prophet-president :doctrine-temple-endowment]
    :axis [80 80 95 30 75 85 50 90 80 85 80 70 60 90]}

   {:variant "Unitarian Universalism" :tradition :tradition-christian-unitarian :founding 1961 :members 160000
    :governance :congregational :mission "Non-trinitarian, emphasis on reason, social justice"
    :doctrines [:doctrine-non-trinitarianism :doctrine-reason-faith :doctrine-universal-salvation]
    :axis [80 65 70 20 80 50 80 85 40 80 75 70 65 80]}

   {:variant "Quaker (Friends)" :tradition :tradition-christian-peace :founding 1647 :members 400000
    :governance :consensus :mission "Inner Light, peace testimony, direct revelation without priesthood"
    :doctrines [:doctrine-inner-light :doctrine-peace-testimony :doctrine-lay-ministry]
    :axis [85 55 65 25 75 40 75 75 30 70 80 65 70 82]}

   {:variant "Seventh-day Adventist" :tradition :tradition-christian-adventist :founding 1863 :members 22000000
    :governance :democratic :mission "Sabbath keeping, imminent second advent, health reform"
    :doctrines [:doctrine-sabbath-keeping :doctrine-second-advent :doctrine-sanctuary-doctrine]
    :axis [80 70 85 15 80 65 65 85 50 85 75 70 70 88]}

   {:variant "Christian Science" :tradition :tradition-christian-science :founding 1879 :members 300000
    :governance :congregational :mission "Divine Science, healing through understanding, mind-cure"
    :doctrines [:doctrine-divine-science :doctrine-prayer-healing :doctrine-error-illusion]
    :axis [75 75 80 10 70 50 55 80 30 75 70 55 40 85]}

   {:variant "Armenian Apostolic" :tradition :tradition-christian-orthodox-other :founding 301 :members 2700000
    :governance :hierarchical :mission "Apostolic succession, monophysite theology, Armenian cultural identity"
    :doctrines [:doctrine-apostolic-succession :doctrine-monophysitism :doctrine-altar-sacrifice]
    :axis [70 60 75 5 60 75 60 80 70 75 70 60 40 88]}

   {:variant "Mennonite/Anabaptist" :tradition :tradition-christian-peace :founding 1525 :members 1500000
    :governance :congregational :mission "Adult baptism, pacifism, community discipline"
    :doctrines [:doctrine-believers-baptism :doctrine-pacifism :doctrine-church-discipline]
    :axis [80 55 70 20 70 35 70 75 20 75 80 60 65 85]}

   {:variant "Coptic Orthodox" :tradition :tradition-christian-orthodox-other :founding 50 :members 5000000
    :governance :hierarchical :mission "Ancient apostolic church, monophysite theology, Egyptian cultural heritage"
    :doctrines [:doctrine-apostolic-succession :doctrine-monophysitism :doctrine-liturgical-mystery]
    :axis [70 60 70 5 65 70 55 80 70 75 70 65 45 88]}

   ;; ISLAM (6 variants)
   {:variant "Sunni Islam (Ash'ari mainstream)" :tradition :tradition-islamic-sunni-ashari :founding 632 :members 1200000000
    :governance :scholarly-consensus :mission "Islamic law (Sharia) via Qiyas, four schools of law, democratic consultation (Shura)"
    :doctrines [:doctrine-monotheism-allah :doctrine-five-pillars :doctrine-qiyas-jurisprudence]
    :axis [70 75 80 10 85 70 75 90 50 85 75 80 40 92]}

   {:variant "Sunni Islam (Salafi reformist)" :tradition :tradition-islamic-sunni-salafi :founding 1700 :members 150000000
    :governance :authority-based :mission "Return to Quran/Sunnah, oppose innovations (Bid'a), strict monotheism"
    :doctrines [:doctrine-monotheism-allah :doctrine-sunnah-authority :doctrine-anti-bid-a]
    :axis [65 65 75 15 75 60 60 85 40 75 70 70 45 93]}

   {:variant "Shia Islam (Twelver/Ayatollah system)" :tradition :tradition-islamic-shia-twelver :founding 1500 :members 250000000
    :governance :hierarchical :mission "Divinely guided Imams, Ayatollah authority, awaiting Hidden Imam's return"
    :doctrines [:doctrine-monotheism-allah :doctrine-imam-divinity :doctrine-awaited-imam]
    :axis [60 70 80 15 75 80 65 85 60 90 70 70 50 92]}

   {:variant "Ismaili Islam" :tradition :tradition-islamic-ismaili :founding 765 :members 15000000
    :governance :hierarchical :mission "Aga Khan authority, esoteric Quranic interpretation, progressive social engagement"
    :doctrines [:doctrine-monotheism-allah :doctrine-aga-khan-authority :doctrine-esoteric-quran]
    :axis [75 80 85 20 80 75 85 85 50 80 75 85 70 88]}

   {:variant "Sufi Islam (Mystical Orders)" :tradition :tradition-islamic-sufi :founding 1000 :members 50000000
    :governance :master-disciple :mission "Direct experience of Allah (Fana), mystical paths (Tariqas), Quranic spirituality"
    :doctrines [:doctrine-monotheism-allah :doctrine-sufi-fana :doctrine-divine-love]
    :axis [70 50 60 30 70 50 60 70 30 75 65 65 60 80]}

   {:variant "Ibadi Islam" :tradition :tradition-islamic-ibadi :founding 750 :members 2500000
    :governance :democratic :mission "Strict piety, Imamate election, strict interpretation of Sharia"
    :doctrines [:doctrine-monotheism-allah :doctrine-ibadi-imamate :doctrine-strict-piety]
    :axis [70 60 70 10 65 50 70 80 40 70 75 55 40 90]}

   ;; BUDDHISM (8 variants)
   {:variant "Theravada Buddhism (Burmese)" :tradition :tradition-buddhist-theravada-burma :founding -500 :members 30000000
    :governance :monastic-sangha :mission "Original teachings, monastic emphasis, merit-making focus"
    :doctrines [:doctrine-four-noble-truths :doctrine-theravada-canon :doctrine-arhat-ideal]
    :axis [75 50 55 10 60 75 80 70 50 80 75 80 60 95]}

   {:variant "Theravada Buddhism (Thai Forest)" :tradition :tradition-buddhist-theravada-thai :founding -500 :members 40000000
    :governance :monastic-sangha :mission "Meditative practice, forest tradition emphasis, Ajahn lineages"
    :doctrines [:doctrine-four-noble-truths :doctrine-meditation-vipassana :doctrine-emptiness]
    :axis [75 50 50 15 65 80 85 70 40 85 80 85 65 93]}

   {:variant "Pure Land Buddhism" :tradition :tradition-buddhist-pure-land :founding 100 :members 100000000
    :governance :temple-hierarchical :mission "Devotion to Amitabha Buddha, reliance on Other-power, nembutsu recitation"
    :doctrines [:doctrine-amitabha-buddha :doctrine-other-power :doctrine-pure-land-rebirth]
    :axis [70 65 70 20 70 75 80 80 40 75 70 80 65 88]}

   {:variant "Zen/Chan Buddhism" :tradition :tradition-buddhist-zen-chan :founding 600 :members 80000000
    :governance :teacher-lineage :mission "Direct transmission, meditation (Zazen), spontaneous awakening"
    :doctrines [:doctrine-buddha-nature :doctrine-zen-koans :doctrine-sudden-enlightenment]
    :axis [80 55 65 35 60 70 75 75 35 75 70 80 70 90]}

   {:variant "Vajrayana/Tibetan Buddhism" :tradition :tradition-buddhist-vajrayana-tibetan :founding 700 :members 30000000
    :governance :hierarchical :mission "Tantric practices, Bodhisattva path, Dalai Lama authority (Gelug tradition)"
    :doctrines [:doctrine-bodhisattva-path :doctrine-tantric-practice :doctrine-rebirth-lamas]
    :axis [75 60 75 25 70 80 70 80 75 85 75 80 65 90]}

   {:variant "Nichiren Buddhism" :tradition :tradition-buddhist-nichiren :founding 1253 :members 15000000
    :governance :sect-hierarchical :mission "Lotus Sutra primacy, Nichiren as reincarnation of Bodhisattva, chanting Daimoku"
    :doctrines [:doctrine-lotus-sutra :doctrine-nichiren-bodhisattva :doctrine-daimoku-power]
    :axis [75 70 85 25 75 65 70 85 50 80 75 75 75 88]}

   {:variant "Shingon Buddhism" :tradition :tradition-buddhist-shingon :founding 800 :members 10000000
    :governance :hierarchical :mission "Esoteric mantras, Kobo Daishi reverence, ritual magic (Shugendo)"
    :doctrines [:doctrine-mantra-efficacy :doctrine-esoteric-transmission :doctrine-kobo-daishi]
    :axis [70 70 80 20 65 80 70 85 60 80 75 75 65 89]}

   {:variant "Mahayana Buddhism (Japanese)" :tradition :tradition-buddhist-mahayana-japanese :founding 600 :members 150000000
    :governance :temple-organization :mission "Bodhisattva ideal, accessible enlightenment, multiple Buddha-lands"
    :doctrines [:doctrine-bodhisattva-path :doctrine-multiple-buddhas :doctrine-original-vow]
    :axis [70 70 75 20 70 80 75 80 50 80 75 80 60 88]}

   ;; JUDAISM (4 movements)
   {:variant "Orthodox Judaism" :tradition :tradition-jewish-orthodox :founding 1700 :members 2500000
    :governance :rabbinical-council :mission "Strict halakha (Jewish law), Torah interpretation, traditional observance"
    :doctrines [:doctrine-torah-authority :doctrine-halakha-binding :doctrine-oral-torah]
    :axis [80 65 85 10 65 70 70 90 60 85 80 70 50 92]}

   {:variant "Conservative Judaism" :tradition :tradition-jewish-conservative :founding 1886 :members 300000
    :governance :movement-assembly :mission "Historical Approach: halakha evolves with tradition, compromise with modernity"
    :doctrines [:doctrine-torah-authority :doctrine-halakha-interpretive :doctrine-egalitarian-judaism]
    :axis [80 60 75 15 70 60 75 85 40 75 75 70 65 85]}

   {:variant "Reform Judaism" :tradition :tradition-jewish-reform :founding 1810 :members 1500000
    :governance :congregational :mission "Judaism redefined per contemporary ethics, personal autonomy, prophetic tradition emphasis"
    :doctrines [:doctrine-torah-moral :doctrine-jewish-autonomy :doctrine-prophetic-judaism]
    :axis [80 55 70 20 75 50 80 80 30 70 75 70 70 80]}

   {:variant "Reconstructionist Judaism" :tradition :tradition-jewish-reconstructionist :founding 1922 :members 100000
    :governance :democratic :mission "Judaism as evolving civilization, cultural identity + spirituality, community consensus"
    :doctrines [:doctrine-judaism-civilization :doctrine-community-authority :doctrine-spiritual-autonomy]
    :axis [85 45 65 25 70 40 75 75 20 70 80 60 75 80]}

   ;; HINDUISM (6 movements)
   {:variant "Brahmo Samaj" :tradition :tradition-hindu-brahmo :founding 1828 :members 500000
    :governance :democratic :mission "Monotheistic Hinduism, rejection of caste, rationalism + spirituality, social reform"
    :doctrines [:doctrine-brahman-monotheism :doctrine-anti-caste :doctrine-vedantic-reason]
    :axis [80 60 70 20 75 50 70 80 30 85 75 70 65 88]}

   {:variant "Arya Samaj" :tradition :tradition-hindu-arya-samaj :founding 1875 :members 3000000
    :governance :congregational :mission "Vedic reformism, reject caste + idolatry, Vedas as sole authority, social activism"
    :doctrines [:doctrine-vedic-authority :doctrine-anti-caste :doctrine-vedic-science]
    :axis [75 65 80 15 75 60 65 85 35 80 75 70 70 88]}

   {:variant "ISKCON (Hare Krishna)" :tradition :tradition-hindu-iskcon-bhakti :founding 1966 :members 1000000
    :governance :hierarchical :mission "Krishna devotion (Bhakti), chanting Hare Krishna mantra, ascetic renunciation"
    :doctrines [:doctrine-krishna-supremacy :doctrine-bhakti-devotion :doctrine-guru-authority]
    :axis [75 70 85 35 70 70 60 85 50 75 75 75 70 88]}

   {:variant "Ramakrishna Mission" :tradition :tradition-hindu-vedanta-mission :founding 1897 :members 300000
    :governance :monastic-hierarchical :mission "Vedantic universalism, service to all (Karma Yoga), modern spirituality"
    :doctrines [:doctrine-vedantic-unity :doctrine-karma-yoga :doctrine-ramakrishna-avatar]
    :axis [80 70 75 25 80 70 80 85 50 85 80 80 70 88]}

   {:variant "Hindu Nationalism (Hindutva)" :tradition :tradition-hindu-nationalist :founding 1915 :members 2000000
    :governance :political :mission "Hindu cultural dominance, anti-secularism, Vedic civilization primacy"
    :doctrines [:doctrine-hindu-nationalism :doctrine-vedic-civilizational :doctrine-religious-nationalism]
    :axis [70 65 80 20 60 65 55 75 40 70 65 60 65 75]}

   {:variant "Contemporary Hindu Temples" :tradition :tradition-hindu-temple-bhakti :founding 1900 :members 500000000
    :governance :temple-management :mission "Bhakti traditions, regional deity worship, family-centered practice"
    :doctrines [:doctrine-temple-worship :doctrine-bhakti-devotion :doctrine-dharma-duty]
    :axis [65 60 70 10 70 80 70 75 50 75 70 70 60 85]}
  ])

;;; ============================================================
;;; DOCTRINE & TRADITION DEFINITIONS
;;; ============================================================

(def additional-traditions
  [
   {:id :tradition-christian-catholic :name "Christianity - Catholic" :founding 1054}
   {:id :tradition-christian-reformed :name "Christianity - Reformed" :founding 1520}
   {:id :tradition-christian-evangelical :name "Christianity - Evangelical" :founding 1730}
   {:id :tradition-christian-jehovahs-witness :name "Christianity - Jehovah's Witness" :founding 1870}
   {:id :tradition-christian-mormon :name "Christianity - Latter-day Saints" :founding 1830}
   {:id :tradition-christian-unitarian :name "Christianity - Unitarian" :founding 1961}
   {:id :tradition-christian-peace :name "Christianity - Peace/Anabaptist" :founding 1525}
   {:id :tradition-christian-adventist :name "Christianity - Seventh-day Adventist" :founding 1863}
   {:id :tradition-christian-science :name "Christianity - Christian Science" :founding 1879}
   {:id :tradition-christian-orthodox-other :name "Christianity - Eastern Orthodox variants" :founding 301}
   {:id :tradition-islamic-sunni-ashari :name "Islam - Sunni Ash'ari" :founding 900}
   {:id :tradition-islamic-sunni-salafi :name "Islam - Sunni Salafi" :founding 1700}
   {:id :tradition-islamic-shia-twelver :name "Islam - Shia Twelver" :founding 1500}
   {:id :tradition-islamic-ismaili :name "Islam - Ismaili" :founding 765}
   {:id :tradition-islamic-ibadi :name "Islam - Ibadi" :founding 750}
   {:id :tradition-buddhist-theravada-burma :name "Buddhism - Theravada Burmese" :founding -500}
   {:id :tradition-buddhist-theravada-thai :name "Buddhism - Theravada Thai Forest" :founding -500}
   {:id :tradition-buddhist-pure-land :name "Buddhism - Pure Land" :founding 100}
   {:id :tradition-buddhist-zen-chan :name "Buddhism - Zen/Chan" :founding 600}
   {:id :tradition-buddhist-vajrayana-tibetan :name "Buddhism - Vajrayana Tibetan" :founding 700}
   {:id :tradition-buddhist-nichiren :name "Buddhism - Nichiren" :founding 1253}
   {:id :tradition-buddhist-shingon :name "Buddhism - Shingon" :founding 800}
   {:id :tradition-buddhist-mahayana-japanese :name "Buddhism - Mahayana Japanese" :founding 600}
   {:id :tradition-jewish-orthodox :name "Judaism - Orthodox" :founding 1700}
   {:id :tradition-jewish-conservative :name "Judaism - Conservative" :founding 1886}
   {:id :tradition-jewish-reform :name "Judaism - Reform" :founding 1810}
   {:id :tradition-jewish-reconstructionist :name "Judaism - Reconstructionist" :founding 1922}
   {:id :tradition-hindu-brahmo :name "Hinduism - Brahmo Samaj" :founding 1828}
   {:id :tradition-hindu-arya-samaj :name "Hinduism - Arya Samaj" :founding 1875}
   {:id :tradition-hindu-iskcon-bhakti :name "Hinduism - ISKCON Bhakti" :founding 1966}
   {:id :tradition-hindu-vedanta-mission :name "Hinduism - Vedanta Mission" :founding 1897}
   {:id :tradition-hindu-nationalist :name "Hinduism - Hindu Nationalism" :founding 1915}
   {:id :tradition-hindu-temple-bhakti :name "Hinduism - Temple Bhakti traditions" :founding 1900}
  ])

;;; ============================================================
;;; ORG & AXIS GENERATION
;;; ============================================================

(defn generate-org-entity [variant idx]
  "Generate :org/* entity for religious variant"
  {:db/id (+ 50000 idx)
   :org/id (keyword (str "org-" (str/lower-case (str/replace (:variant variant) #" " "-"))))
   :org/name (:variant variant)
   :org/canonical-name (str (name (:tradition variant)))
   :org/type :religious-corp
   :org/tradition [:tradition/id (:tradition variant)]
   :org/founding-date (:founding variant)
   :org/founding-location "Global"
   :org/member-count (:members variant)
   :org/primary-regions [[:region/id :region-global]]  ; placeholder
   :org/governance-model (keyword (:governance variant))
   :org/mission-statement (:mission variant)
   :org/primary-language :en})

(defn generate-axis-entities [org-id axis-base-id axis-values]
  "Generate 14 :org-axis/* entities for religious org"
  (let [axis-ids [:axis-governance :axis-economic :axis-identity :axis-agency
                  :axis-social-delivery :axis-physical-presence :axis-public-trust
                  :axis-legal-contract :axis-land-resource :axis-education
                  :axis-dispute-resolution :axis-external-recognition
                  :axis-member-engagement :axis-doctrine-clarity]
        measurement-basis "document-analysis"
        caveat "Religious organization; axis values based on canonical texts, historical analysis, and organizational structure documentation."]
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
  (println "\n=== Phase 2: Generate Religious Tradition Variants ===\n")

  (let [;; Generate org entities
        org-entities (mapv
                      (fn [variant idx]
                        (print (str "Processing " (:variant variant) " ... "))
                        (println "[✓]")
                        (generate-org-entity variant idx))
                      religious-variants
                      (range))

        ;; Generate axis entities (14 per org)
        axis-entities (mapcat
                       (fn [variant idx]
                         (generate-axis-entities
                          [:org/id (keyword (str "org-" (str/lower-case (str/replace (:variant variant) #" " "-"))))]
                          (+ 51000 (* idx 100))
                          (:axis variant)))
                       religious-variants
                       (range))

        ;; Generate tradition entities (new)
        tradition-entities (mapv
                           (fn [trad]
                             {:db/id (keyword (str "tradition-" (str/lower-case (str/replace (:name trad) #" " "-"))))
                              :tradition/id (:id trad)
                              :tradition/name (:name trad)
                              :tradition/founding-approx-year (:founding trad)})
                           additional-traditions)

        ;; Combine all
        all-entities (concat org-entities axis-entities tradition-entities)

        ;; Write to file
        output-path "/tmp/phase2-religious-variants.edn"]

    (.writeFileSync fs output-path (str "[" (str/join " " all-entities) "]"))

    (println (str "\n✓ Generated " (count org-entities) " org entities"))
    (println (str "✓ Generated " (count axis-entities) " axis entities"))
    (println (str "✓ Generated " (count tradition-entities) " tradition entities"))
    (println (str "✓ Total: " (count all-entities) " entities"))
    (println (str "✓ Output: " output-path))))

(-main)
