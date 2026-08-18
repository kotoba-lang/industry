#!/usr/bin/env nbb
;; scripts/itonami-valueflows-bind.cljs — bind every cloud-itonami business to
;; the Valueflows plane, and measure what that binding does NOT yet reach.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/itonami-valueflows-bind.cljs \
;;     --data-root $HOME/github/com-junkawasaki
;;   ... --check                       ; compare, write nothing
;;
;; ADR-2608153000 (Valueflows is the economic vocabulary of the 営み OS).
;;
;; ## What is derived, and what is refused
;;
;; A blueprint says what a business IS GOVERNED AS. It carries no resources, no
;; prices, no quantities and no flows — measured across all 1,398 of them. So
;; this script cannot and does not derive economic events. Deriving 1,398 sets
;; of flows from files that contain none would be fabrication.
;;
;; What IS derivable is the CLASSIFICATION, and it is derivable because the
;; classification is real external data:
;;
;;   blueprint :itonami.blueprint/isic-*  ->  a UN ISIC class (public domain,
;;   mirrored in orgs/cloud-itonami/org-un-isic/data/classes/*.json)
;;   ->  vf:ProcessSpecification with vf:classifiedAs that class's URI
;;
;; `vf:classifiedAs` is exactly for this: "references one or more uri's for a
;; concept in a common taxonomy". The URI prefix is not invented here either —
;; org-un-isic's own PROJECT.jsonld declares
;; isic: https://unstats.un.org/unsd/classifications/Econ/ISIC/
;;
;; ## A classified business is not a connected ledger
;;
;; The distinction this script exists to keep visible. A ProcessSpecification
;; says what KIND of activity a business is. It says nothing about whether any
;; activity was recorded. Two separate measurements are therefore emitted per
;; business and reported separately in coverage:
;;
;;   :vf.binding/state              can it be classified at all
;;   :vf.binding/consumes-vocabulary?  does its code actually use Valueflows
;;
;; The second is the one that matters for "connected", it is unfakeable (a
;; deps.edn reference), and today it is true for exactly one repository. Saying
;; 497 classified without saying 1 consuming would be the lie this file is
;; shaped to prevent.
;;
;; Exit codes: 0 written/identical · 1 STALE · 2 COULD NOT ANSWER (inputs
;; missing or below the evidence floor — never reported as a clean empty run).

(ns itonami-valueflows-bind
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

(def out-path "90-docs/valueflows/itonami-business-vf.datoms.edn")
(def dataset "itonami-valueflows")

;; Declared by orgs/cloud-itonami/org-un-isic/PROJECT.jsonld, not by this file.
(def isic-prefix "https://unstats.un.org/unsd/classifications/Econ/ISIC/")
(def isco-prefix "https://www.ilo.org/public/english/bureau/stat/isco/isco08/")

;; Measured 2026-08-15 against the workspace: 1,398 west-registered blueprints,
;; 428 UN ISIC Rev.4 classes. A run that sees materially fewer has lost an
;; input, and must not write a smaller file that looks complete.
(def floor {:blueprints 1300 :isic-classes 400 :registered 1700})

(defn- die [code msg] (println msg) (js/process.exit code))

(defn- args []
  (let [a (vec (drop 2 (js->clj js/process.argv)))]
    {:data-root (or (second (drop-while #(not= "--data-root" %) a)) (.cwd js/process))
     :check? (boolean (some #{"--check"} a))}))

(defn- slurp* [p] (str (fs/readFileSync p "utf8")))
(defn- exists? [p] (fs/existsSync p))

;; ── inputs ────────────────────────────────────────────────────────────────

(defn- registered-paths
  "cloud-itonami repos that west actually manages. The filesystem also holds
   scratch worktrees (`_wt*`, `.wt*`, `_w7b*`, `_salvage*`); 187 of them carry a
   blueprint.edn and none is a repository. west.yml is the authority."
  [root]
  (let [west (path/join root "manifest" "west.yml")]
    (when-not (exists? west) (die 2 (str "CANNOT ANSWER: " west " is absent.")))
    (into #{} (map second) (re-seq #"path: orgs/cloud-itonami/(\S+)" (slurp* west)))))

(defn- isic-rev4
  "code -> {:name :group :includes [...]}. UN ISIC Rev.4, public domain.
   Rev.4's per-class JSON carries `includes` — the activities the UN itself
   lists for the class."
  [root]
  (let [dir (path/join root "orgs" "cloud-itonami" "org-un-isic" "data" "classes")]
    (when-not (exists? dir)
      (die 2 (str "CANNOT ANSWER: the ISIC Rev.4 class data is absent at " dir
                  ". Without it no business can be classified, and emitting"
                  " every business as :unclassified would blame the businesses"
                  " for a missing input.")))
    (into {}
          (keep (fn [f]
                  (when (str/ends-with? f ".json")
                    (let [j (js->clj (js/JSON.parse (slurp* (path/join dir f))))]
                      [(get j "code") {:name (get j "nameEn") :group (get j "group")
                                       :includes (vec (get j "includes"))}]))))
          (js->clj (fs/readdirSync dir)))))

(defn- cofog-groups
  "3-digit COFOG group -> the mirrored class under it.

   The mirror is a SAMPLE, not the classification: measured 2026-08-15 it holds
   10 classes, one per division. So a blueprint declaring a real COFOG group is
   reported as declared-and-unresolved when that group is outside the sample,
   which is a fact about this mirror and not about the blueprint."
  [root]
  (let [dir (path/join root "orgs" "cloud-itonami" "org-un-cofog" "data" "classes")]
    (if-not (exists? dir)
      {}
      (into {}
            (keep (fn [f]
                    (when (str/ends-with? f ".json")
                      (let [j (js->clj (js/JSON.parse (slurp* (path/join dir f))))]
                        [(get j "group") {:code (get j "code")
                                          :nameEn (get j "nameEn")}]))))
            (js->clj (fs/readdirSync dir))))))

(defn- scope-of
  "What DELIMITS this business, when it is not an industry.

   Derived from the registered repository name, which is the only thing these
   blueprints say on the subject. Three of these are not classifications at all
   -- a country, a legal entity and a municipality are places and parties -- and
   grouping them with the businesses that simply forgot a code was what made 515
   look like one problem."
  [dir-name]
  (let [n (str/replace dir-name #"^cloud-itonami-" "")]
    (cond
      (re-matches #"^iso3166-.+" n)
      {:scope-kind :jurisdiction :scope-code (subs n (count "iso3166-"))}

      (re-matches #"^lei-.+" n)
      {:scope-kind :legal-entity :scope-code (subs n (count "lei-"))}

      (re-matches #"^municipality-.+" n)
      {:scope-kind :municipality :scope-code (subs n (count "municipality-"))}

      ;; these DO declare a classification, just not ISIC or ISCO
      (re-matches #"^cofog-.+" n)
      {:scope-kind :government-function :scope-code (subs n (count "cofog-"))
       :vocabulary :cofog}

      (re-matches #"^unspsc-.+" n)
      {:scope-kind :product :scope-code (subs n (count "unspsc-"))
       :vocabulary :unspsc}

      (re-matches #"^jsic-.+" n)
      {:scope-kind :national-industry :scope-code (subs n (count "jsic-"))
       :vocabulary :jsic}

      (re-matches #"^gtin-.+" n)
      {:scope-kind :trade-item :scope-code (subs n (count "gtin-"))
       :vocabulary :gtin}

      :else nil)))

(defn- isic-rev5-groups
  "3-digit group code -> {:name}. A blueprint may classify at group level and 27
   of them do; reading a coarser classification as a broken one was the bug."
  [root]
  (let [f (path/join root "orgs" "cloud-itonami" "org-un-isic" "data" "rev5" "groups.json")]
    (if-not (exists? f)
      {}                                  ; absent is reported by the caller, not guessed at
      (into {}
            (map (fn [[code j]] [code {:name (get j "nameEn")}]))
            (js->clj (js/JSON.parse (slurp* f)))))))

(defn- isic-rev5
  "code -> {:name :group}. UN ISIC Rev.5, public domain.

   NO `includes`: the Rev.5 explanatory notes are published only as PDF and
   XLSX, so the structure CSV carries code and title alone. That is a property
   of what the UN publishes, not a gap — see org-un-isic data/rev5/upstream.edn."
  [root]
  (let [f (path/join root "orgs" "cloud-itonami" "org-un-isic" "data" "rev5" "classes.json")]
    (when-not (exists? f)
      (die 2 (str "CANNOT ANSWER: the ISIC Rev.5 class data is absent at " f
                  ". Most blueprints declare Rev.5, and resolving them against"
                  " the Rev.4 table instead would be wrong in principle and"
                  " would silently attach a Rev.4 title to a Rev.5 code — 83 of"
                  " the 361 codes in both tables mean different things.")))
    (into {}
          (map (fn [[code j]] [code {:name (get j "nameEn") :group (get j "group")}]))
          (js->clj (js/JSON.parse (slurp* f))))))

(defn- blueprint-field [text k]
  (when-let [m (re-find (re-pattern (str ":itonami\\.blueprint/" k "\\s+\"([^\"]+)\"")) text)]
    (second m)))

(defn- blueprint-keyword-field [text k]
  (when-let [m (re-find (re-pattern (str ":itonami\\.blueprint/" k "\\s+:([a-zA-Z0-9/._-]+)")) text)]
    (second m)))

(defn- consumes-vocabulary?
  "Does this repository's deps.edn actually reference the Valueflows vocabulary?
   Unfakeable and the only honest answer to 'is it connected'."
  [dir]
  (let [d (path/join dir "deps.edn")]
    (boolean (and (exists? d) (str/includes? (slurp* d) "ws-valueflo-vocabulary")))))

(defn- ledger-file?
  "A weaker proxy: does the repository carry a file whose name says it holds a
   record series? Proxy, not proof — reported under its own key so it is never
   read as 'has economic events'."
  [dir]
  (let [names (when (exists? dir) (js->clj (fs/readdirSync dir)))]
    (boolean (some #(re-find #"(?i)ledger|journal" %) (or names [])))))

;; ── one business ──────────────────────────────────────────────────────────

(defn- level-of [code]
  (case (count (str code)) 4 "class" 3 "group" 2 "division" 1 "section" "unknown"))

(defn- resolve-code
  "Resolve against the revision the blueprint DECLARED. A bare :isic is tried
   Rev.4 first then Rev.5, and which one answered is recorded — guessing a
   revision and not saying so is how a wrong title ends up looking authoritative.

   Measured 2026-08-15: 361 codes exist in both tables and 83 of them mean
   different things (1104 is soft drinks in Rev.4 and malt in Rev.5). Resolving
   across revisions mislabelled 121 of 393 Rev.5-declared businesses.

   => {:hit {...} :revision \"isic-rev5\"} | nil"
  [rev4 rev5 groups5 code declared-as]
  (let [cls (fn [table rev] (when-let [h (get table code)]
                              {:hit h :revision rev :level "class"}))
        grp (fn [] (when-let [h (get groups5 code)]
                     ;; a group is a real ISIC level, not a degraded class
                     {:hit h :revision "isic-rev5" :level "group"}))]
    (case declared-as
      "isic-rev5" (or (cls rev5 "isic-rev5") (grp))
      ;; No Rev.4 group fallback: Rev.4 group titles are unavailable at any level
      ;; of confidence. org-un-isic data/PROVENANCE.edn measures the mirror's
      ;; Rev.4 half as unpinned and disputed against the UN's legacy structure
      ;; file (33 of 414 class titles differ). A group title taken from a
      ;; disputed source would look authoritative and might be Rev.5 wording.
      "isic-rev4" (cls rev4 "isic-rev4")
      "isic" (or (cls rev4 "isic-rev4") (cls rev5 "isic-rev5") (grp))
      nil)))

(defn- other-revision-has?
  "Diagnosis for a code that did not resolve: does it exist in the revision the
   blueprint did NOT declare? 50 of 84 do. This is REPORTED, never resolved —
   whether the code or the revision label is the mistake cannot be told from
   here, and picking one would re-introduce the cross-revision mislabelling this
   binder was corrected to avoid."
  [rev4 rev5 code declared-as]
  (cond
    (and (= "isic-rev5" declared-as) (get rev4 code)) "isic-rev4"
    (and (= "isic-rev4" declared-as) (get rev5 code)) "isic-rev5"
    :else nil))

(defn- bind-one [rev4 rev5 groups5 cofog dir-name dir text]
  (let [r5 (blueprint-field text "isic-rev5")
        r4 (blueprint-field text "isic-rev4")
        bare (blueprint-field text "isic")
        isco (blueprint-field text "isco-08")
        code (or r5 r4 bare)
        declared-as (cond r5 "isic-rev5" r4 "isic-rev4" bare "isic" :else nil)
        resolved (resolve-code rev4 rev5 groups5 code declared-as)
        hit (:hit resolved)
        base {:db/id nil
              :source/dataset dataset
              :repo/path (str "orgs/cloud-itonami/" dir-name)
              :vf.business/id (or (blueprint-field text "id") dir-name)
              :vf.business/name (blueprint-field text "name")
              :vf.binding/consumes-vocabulary? (consumes-vocabulary? dir)
              :vf.binding/has-record-series-file? (ledger-file? dir)}
        base (cond-> base
               (blueprint-keyword-field text "governor")
               (assoc :vf.business/governor (blueprint-keyword-field text "governor"))
               (blueprint-keyword-field text "maturity")
               (assoc :vf.business/maturity (blueprint-keyword-field text "maturity")))]
    (cond
      hit
      (assoc base
             :vf.binding/state "classified"
             :vf.spec/kind "ProcessSpecification"
             :vf.spec/name (:name hit)
             :vf.spec/classified-as (str isic-prefix code)
             :vf.spec/isic-code code
             :vf.spec/isic-level (:level resolved)
             :vf.spec/isic-group (or (:group hit)
                                     (when (= "group" (:level resolved)) code))
             ;; Honest about the revision skew: most blueprints declare Rev.5
             ;; and the mirrored table is Rev.4. A code that happens to exist in
             ;; both is resolved against Rev.4, and this says so.
             :vf.spec/declared-as declared-as
             :vf.spec/resolved-against (:revision resolved)
             ;; The UN's own enumeration of activities for this class, VERBATIM.
             ;; NOT parsed into resource specifications: `includes` is prose
             ;; and extracting specs out of it would be invention dressed as
             ;; derivation. Rev.4 only, because the UN publishes no
             ;; machine-readable includes for Rev.5.
             :vf.spec/includes-count (count (:includes hit))
             :vf.spec/includes (vec (:includes hit)))

      code
      (assoc base
             :vf.binding/state "classification-unresolvable"
             :vf.spec/isic-code code
             :vf.spec/declared-as declared-as
             :vf.spec/isic-level-declared (level-of code)
             :vf.binding/rev4-group-unavailable?
             (boolean (and (= "isic-rev4" declared-as) (= 3 (count code))))
             :vf.binding/exists-in-other-revision (other-revision-has? rev4 rev5 code declared-as)
             :vf.binding/why (str "declared " declared-as " " code
                                  " is in neither mirrored table (Rev.4 428"
                                  " classes, Rev.5 463); it was resolved against"
                                  " the revision it declared, not mapped across"))

      isco
      (assoc base
             :vf.binding/state "occupation-only"
             :vf.spec/isco-code isco
             :vf.spec/classified-as (str isco-prefix isco)
             :vf.binding/why "ISCO classifies an occupation, not an industry; a process specification needs the activity")

      :else
      (let [in-name (second (re-find #"isic[-_]?(\d{3,4})" dir-name))
            {:keys [scope-kind scope-code vocabulary]} (scope-of dir-name)]
        (cond-> (assoc base
                       ;; NOT "unclassified". Measured 2026-08-15: of the 515
                       ;; businesses with no ISIC or ISCO code, 470 are scoped by
                       ;; JURISDICTION OR LEGAL ENTITY -- 223 iso3166-*, 185
                       ;; lei-*, 62 municipality-* -- and for those an industry
                       ;; code is a category error rather than a missing value.
                       ;; "cloud-itonami-iso3166-JP" is the business of operating
                       ;; in a country; asking which industry it is in has no
                       ;; answer to find. Reporting all 515 as one gap invited
                       ;; exactly the wrong repair: generating 515 industry
                       ;; guesses, which was the plan until the names were
                       ;; actually counted.
                       :vf.binding/state (if scope-kind "not-industry-scoped" "unclassified")
                       :vf.binding/why
                       (if scope-kind
                         (str "scoped by " (name scope-kind)
                              ", not by industry; an ISIC class describes an"
                              " activity and this business is delimited by"
                              " something else")
                         "the blueprint declares no code in any vocabulary"))
          scope-kind (assoc :vf.binding/scope-kind (name scope-kind))
          scope-code (assoc :vf.binding/scope-code scope-code)
          ;; a business that declares a code in a vocabulary OTHER than
          ;; ISIC/ISCO. Whether it resolves is a fact about our mirrors, and it
          ;; is recorded separately from the declaration itself.
          vocabulary (assoc :vf.binding/declares-vocabulary (name vocabulary))
          (= :cofog vocabulary)
          (as-> e (let [g (str/replace (str scope-code) "." "")
                        hit (get cofog g)]
                    (if hit
                      (assoc e :vf.binding/vocabulary-resolved? true
                             :vf.spec/cofog-group g
                             :vf.spec/cofog-name (:nameEn hit)
                             :vf.spec/cofog-class (:code hit))
                      (assoc e :vf.binding/vocabulary-resolved? false
                             :vf.binding/vocabulary-gap
                             (str "COFOG group " g " is not in the mirror, which"
                                  " carries " (count cofog) " groups --"
                                  " one class per division, a sample rather than"
                                  " the full classification")))))
          (#{:unspsc :jsic :gtin} vocabulary)
          (assoc :vf.binding/vocabulary-resolved? false
                 :vf.binding/vocabulary-gap
                 (case vocabulary
                   :unspsc (str "no UNSPSC data is mirrored here. orgs/cloud-itonami/"
                                "org-unspsc is an application repository -- appview,"
                                " docs, a charter -- and carries no code table")
                   :jsic "no JSIC (Japan Standard Industrial Classification) mirror exists in this workspace"
                   :gtin "a GTIN identifies a trade item, not an industry"))
          ;; Reported, NOT used to classify. A repository name is a discovery
          ;; alias, not a declaration. Measured: 2 of the 515 carry an ISIC code
          ;; in their name.
          in-name (assoc :vf.binding/code-in-repo-name in-name))))))

;; ── all ───────────────────────────────────────────────────────────────────

(defn- build [root]
  (let [registered (registered-paths root)
        rev4 (isic-rev4 root)
        rev5 (isic-rev5 root)
        groups5 (isic-rev5-groups root)
        cofog (cofog-groups root)
        org-dir (path/join root "orgs" "cloud-itonami")]
    (when-not (exists? org-dir)
      (die 2 (str "CANNOT ANSWER: " org-dir " is absent. west checkouts are"
                  " gitignored, so a worktree must be given --data-root.")))
    (when (< (count registered) (:registered floor))
      (die 2 (str "CANNOT ANSWER: west.yml lists only " (count registered)
                  " cloud-itonami paths, floor " (:registered floor) ".")))
    (doseq [[label n] [["Rev.4" (count rev4)] ["Rev.5" (count rev5)]]]
      (when (< n (:isic-classes floor))
        (die 2 (str "CANNOT ANSWER: read " n " ISIC " label " classes, floor "
                    (:isic-classes floor) "."))))
    (let [consuming-in-org
          (vec (sort (keep (fn [d]
                             (when (and (contains? registered d)
                                        (consumes-vocabulary? (path/join org-dir d)))
                               (str "orgs/cloud-itonami/" d)))
                           (js->clj (fs/readdirSync org-dir)))))
          rows (->> (js->clj (fs/readdirSync org-dir))
                    sort
                    (keep (fn [d]
                            (let [dir (path/join org-dir d)
                                  bp (path/join dir "blueprint.edn")]
                              (when (and (contains? registered d) (exists? bp))
                                (bind-one rev4 rev5 groups5 cofog d dir (slurp* bp))))))
                    vec)]
      (when (< (count rows) (:blueprints floor))
        (die 2 (str "CANNOT ANSWER: bound only " (count rows)
                    " west-registered blueprints, floor " (:blueprints floor)
                    ". An input is missing; a smaller file would look complete.")))
      {:rows rows :registered (count registered)
       :isic-classes {:rev4 (count rev4) :rev5 (count rev5) :rev5-groups (count groups5)}
       :consuming-in-org consuming-in-org})))

(defn- coverage [{:keys [rows registered isic-classes consuming-in-org]}]
  (let [by-state (frequencies (map :vf.binding/state rows))
        consuming (filter :vf.binding/consumes-vocabulary? rows)]
    {:db/id nil
     :source/dataset dataset
     :vf.coverage/businesses (count rows)
     :vf.coverage/west-registered-paths registered
     :vf.coverage/blueprints-without-repo (- registered (count rows))
     :vf.coverage/isic-classes-available (:rev4 isic-classes)
     :vf.coverage/isic-rev5-classes-available (:rev5 isic-classes)
     :vf.coverage/resolved-by-revision (frequencies (keep :vf.spec/resolved-against rows))
     :vf.coverage/resolved-by-level (frequencies (keep :vf.spec/isic-level rows))
     :vf.coverage/isic-rev5-groups-available (:rev5-groups isic-classes)
     ;; actionable diagnoses, so 84 opaque failures become 84 fixable records
     :vf.coverage/unresolvable-but-in-other-revision
     (count (filter :vf.binding/exists-in-other-revision rows))
     ;; The 122 Rev.4 classifications rest on titles with NO upstream pin, 33 of
     ;; which are disputed against the UN's legacy structure file. Flagged here
     ;; because a name that came from an unprovenanced table reads exactly like
     ;; one that came from a pinned one.
     :vf.coverage/rev4-titles-unpinned? true
     :vf.coverage/rev4-titles-provenance "orgs/cloud-itonami/org-un-isic/data/PROVENANCE.edn"
     :vf.coverage/rev4-group-codes-unresolvable
     (count (filter :vf.binding/rev4-group-unavailable? rows))
     :vf.coverage/unclassified-with-code-in-repo-name
     (count (filter :vf.binding/code-in-repo-name rows))
     :vf.coverage/with-un-includes (count (filter #(pos? (or (:vf.spec/includes-count %) 0)) rows))
     :vf.coverage/classified (get by-state "classified" 0)
     :vf.coverage/classification-unresolvable (get by-state "classification-unresolvable" 0)
     :vf.coverage/occupation-only (get by-state "occupation-only" 0)
     :vf.coverage/unclassified (get by-state "unclassified" 0)
     ;; The 515 that were reported as one gap, decomposed. Three of these scope
     ;; kinds are not classifications at all -- a country, a legal entity and a
     ;; municipality are places and parties, and an ISIC class describes an
     ;; activity -- so for 470 of them there was never an industry code to find.
     ;; Reporting them together invited the wrong repair.
     :vf.coverage/not-industry-scoped (get by-state "not-industry-scoped" 0)
     :vf.coverage/by-scope-kind (frequencies (keep :vf.binding/scope-kind rows))
     ;; and the ones that DO declare a classification, just not ISIC or ISCO.
     ;; Whether each resolves is a fact about this workspace's mirrors, kept
     ;; separate from the declaration so a mirror gap cannot read as a blueprint
     ;; that said nothing.
     :vf.coverage/declares-other-vocabulary
     (frequencies (keep :vf.binding/declares-vocabulary rows))
     :vf.coverage/other-vocabulary-resolved
     (count (filter #(true? (:vf.binding/vocabulary-resolved? %)) rows))
     :vf.coverage/other-vocabulary-unresolved
     (count (filter #(false? (:vf.binding/vocabulary-resolved? %)) rows))
     :vf.coverage/other-vocabulary-gaps
     (vec (sort (distinct (keep :vf.binding/vocabulary-gap rows))))
     ;; The numbers that say whether anything is CONNECTED, as opposed to named.
     ;; TWO populations, because they answer different questions and collapsing
     ;; them misleads in both directions:
     ;;   …-in-population  of the blueprint businesses in this file
     ;;   …-in-org         of every west-registered cloud-itonami repo
     ;; cloud-itonami/credits consumes the vocabulary and carries NO blueprint,
     ;; so it is absent from the first count and present in the second. Reporting
     ;; only the first would read as "nothing in cloud-itonami uses Valueflows".
     :vf.coverage/consuming-in-population (count consuming)
     :vf.coverage/consuming-in-population-repos (mapv :repo/path consuming)
     :vf.coverage/consuming-in-org (count consuming-in-org)
     :vf.coverage/consuming-in-org-repos consuming-in-org
     :vf.coverage/has-record-series-file (count (filter :vf.binding/has-record-series-file? rows))
     :vf.coverage/complete? false
     :vf.coverage/note
     (str "A classified business is NOT a connected ledger. "
          (get by-state "classified" 0) " of " (count rows) " businesses have a"
          " vf:ProcessSpecification derived from the UN ISIC table, which says what"
          " KIND of activity each is and nothing about whether any activity was"
          " recorded. Of those " (count rows) " businesses, "
          (count consuming) " consume the Valueflows vocabulary in code; across all "
          registered " west-registered cloud-itonami repositories the figure is "
          (count consuming-in-org) " ("
          (if (seq consuming-in-org) (str/join ", " consuming-in-org) "none")
          "). Reading the classified count as adoption is the mistake this entity"
          " exists to prevent. :complete? is false and stays false until economic"
          " records exist for a business, which no blueprint contains: a blueprint"
          " declares governance and technology, never resources, prices, quantities"
          " or flows. One number in here was actively misleading until"
          " 2026-08-15: 515 businesses were reported as `unclassified`, which"
          " reads as 515 missing codes and very nearly produced 515 invented"
          " ones. Counting the names instead showed that "
          (get by-state "not-industry-scoped" 0) " are delimited by something"
          " other than an industry -- "
          (str/join ", " (map (fn [[k v]] (str v " " k))
                              (sort-by (comp - val)
                                       (frequencies (keep :vf.binding/scope-kind rows)))))
          " -- and for a country, a legal entity or a municipality there is no"
          " industry code to find. A further "
          (count (filter #(false? (:vf.binding/vocabulary-resolved? %)) rows))
          " declare a code in a vocabulary this workspace does not mirror at"
          " that level, which is a gap here and not in the blueprint. What is"
          " left genuinely says nothing: " (get by-state "unclassified" 0)
          " businesses, mostly payments and crowdfunding, that plausibly do have"
          " an ISIC class and simply do not declare one. Those are for their"
          " authors to declare, not for this projection to guess.")}))

(defn- render [{:keys [rows] :as built}]
  (let [entities (conj (vec rows) (coverage built))
        numbered (map-indexed (fn [i e] (assoc e :db/id (- (inc i)))) entities)]
    (str ";; GENERATED by scripts/itonami-valueflows-bind.cljs. DO NOT EDIT BY HAND.\n"
         ";; ADR-2608153000. Regenerate:\n"
         ";;   nbb --classpath \".:scripts/nbb_compat\" scripts/itonami-valueflows-bind.cljs \\\n"
         ";;     --data-root $HOME/github/com-junkawasaki\n"
         ";;\n"
         ";; Every entity is :source/dataset \"" dataset "\" and joins the rest of the\n"
         ";; datom plane by :repo/path. The LAST entity is coverage — read it before\n"
         ";; citing any count from this file.\n"
         (with-out-str (pprint/pprint (vec numbered))))))

(let [{:keys [data-root check?]} (args)
      built (build data-root)
      content (render built)
      cov (coverage built)]
  (println (str "bound " (:vf.coverage/businesses cov) " businesses"
                " · classified " (:vf.coverage/classified cov)
                " · unresolvable " (:vf.coverage/classification-unresolvable cov)
                " · occupation-only " (:vf.coverage/occupation-only cov)
                " · unclassified " (:vf.coverage/unclassified cov)
                " · consuming in population " (:vf.coverage/consuming-in-population cov)
                " · consuming in org " (:vf.coverage/consuming-in-org cov)))
  (if check?
    (let [have (when (exists? out-path) (slurp* out-path))]
      (cond
        (nil? have) (die 1 (str "STALE: " out-path " is absent"))
        (not= have content) (die 1 (str "STALE: " out-path " disagrees with the workspace"))
        :else (println (str "OK: " out-path " matches"))))
    (do (fs/mkdirSync (path/dirname out-path) #js {:recursive true})
        (fs/writeFileSync out-path content)
        (println (str "wrote " out-path " (" (count content) " bytes)")))))
