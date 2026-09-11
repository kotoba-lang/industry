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
;;   nbb 90-docs/community-coverage/queries/coverage.cljs freshness  ; age of pinned sources
;;   nbb 90-docs/community-coverage/queries/coverage.cljs countries  ; country coverage + absences
;;   nbb 90-docs/community-coverage/queries/coverage.cljs depth      ; form families per country
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

;; A pin is not binary. FIFA's participation figure is pinned to a 2006 survey and
;; WIR's to a 2013 report; Buy Nothing's membership tripled between two recalls.
;; Age of the underlying source is therefore reported alongside the pin count.
(defn source-year [o]
  (when-let [d (:org/source-date o)]
    (let [m (re-find #"^(\d{4})" (str d))]
      (when m (js/parseInt (second m) 10)))))

(defn staleness []
  (let [now (.getFullYear (js/Date.))
        pinned (filter #(= "source-pinned" (:org/data-provenance %)) orgs)
        aged (keep (fn [o] (when-let [y (source-year o)] [o (- now y)])) pinned)
        bucket (fn [a] (cond (<= a 1) "current (0-1y)"
                             (<= a 3) "recent (2-3y)"
                             (<= a 6) "ageing (4-6y)"
                             :else "stale (7y+)"))
        by-bucket (frequencies (map (comp bucket second) aged))
        n (count pinned)]
    (println "\nPIN FRESHNESS  (a pin fixes WHEN something was counted, so age matters)")
    (doseq [b ["current (0-1y)" "recent (2-3y)" "ageing (4-6y)" "stale (7y+)"]
            :let [v (get by-bucket b 0)]
            :when (pos? v)]
      (line b v n))
    (when-let [no-date (seq (remove source-year pinned))]
      (println (str "    " (count no-date) " pinned org(s) carry no parseable :org/source-date")))
    (println "    stalest pins:")
    (doseq [[o a] (take 5 (sort-by (comp - second) aged))]
      (println (str "      " (:org/source-date o) "  (" a "y)  " (:org/name o))))))

;; Country coverage. A corpus that calls itself worldwide should be able to say
;; which countries it has actually touched and which it has not.
;; The embedded list is the 193 UN member states; it is hand-entered, and an
;; earlier revision omitted Tanzania, which the report then mislabelled as a
;; non-member. Codes outside it (HK, TW, PS, PR, PF, AS) are reported separately
;; rather than silently dropped.
(def un-members
  (set (str/split "AF AL DZ AD AO AG AR AM AU AT AZ BS BH BD BB BY BE BZ BJ BT BO BA BW BR BN BG BF BI CV KH CM CA CF TD CL CN CO KM CG CD CR CI HR CU CY CZ DK DJ DM DO EC EG SV GQ ER EE SZ ET FJ FI FR GA GM GE DE GH GR GD GT GN GW GY HT HN HU IS IN ID IR IQ IE IL IT JM JP JO KZ KE KI KP KR KW KG LA LV LB LS LR LY LI LT LU MG MW MY MV ML MT MH MR MU MX FM MD MC MN ME MA MZ MM NA NR NP NL NZ NI NE NG MK NO OM PK PW PA PG PY PE PH PL PT QA RO RU RW KN LC VC WS SM ST SA SN RS SC SL SG SK SI SB SO ZA SS ES LK SD SR SE CH SY TJ TH TL TG TO TT TN TR TM TV UG UA AE GB US UY UZ VU VE VN YE ZM ZW TZ" #"\s+")))

(defn countries []
  (let [freq (frequencies (mapcat :org/countries orgs))
        seen (set (keys freq))
        seen-un (filter un-members seen)
        missing (sort (remove seen un-members))]
    (println "\nCOUNTRY COVERAGE  (a worldwide corpus should say which countries it has touched)")
    (println (str "  " (count seen-un) "/" (count un-members) " UN member states appear in at least one entry ("
                  (pct (count seen-un) (count un-members)) "%)"))
    (when-let [extra (seq (remove un-members seen))]
      (println (str "  plus non-UN-member codes: " (str/join " " (sort extra)))))
    (println "  most-referenced:")
    (println (str "    " (str/join "  " (map (fn [[k v]] (str k ":" v))
                                             (take 12 (sort-by (comp - val) freq))))))
    (println (str "  absent (" (count missing) "):"))
    (doseq [chunk (partition-all 24 missing)]
      (println (str "    " (str/join " " chunk))))))

;; Depth, not presence. Every UN member state is referenced by at least one entry,
;; which says nothing about how much of that country's community life is
;; described. This counts DISTINCT form families per country.
(defn depth []
  (let [per-country (reduce (fn [acc o]
                              (reduce (fn [a c]
                                        (update a c (fnil conj #{}) (:org/form-family o)))
                                      acc (:org/countries o)))
                            {} orgs)
        fams (into {} (map (fn [[c s]] [c (count (disj s nil))])) per-country)
        un (filter (comp un-members key) fams)
        bucket (fn [k] (cond (<= k 1) "1 family" (<= k 3) "2-3 families"
                             (<= k 6) "4-6 families" :else "7+ families"))
        dist (frequencies (map (comp bucket val) un))
        n (count un)]
    (println "\nDEPTH PER COUNTRY  (distinct form families described, not just referenced)")
    (println "  CAVEAT: :org/countries lists are illustrative, not exhaustive — an entry naming")
    (println "  six countries does not claim the form is absent from the seventh. Depth is a")
    (println "  LOWER BOUND on what is described, and country-to-country comparison is unsafe.")
    (println "  Do not raise this metric by lengthening country lists; that manufactures depth.")
    (doseq [b ["7+ families" "4-6 families" "2-3 families" "1 family"]
            :let [v (get dist b 0)] :when (pos? v)]
      (line b v n))
    (println "  deepest:")
    (println (str "    " (str/join "  " (map (fn [[k v]] (str k ":" v))
                                             (take 10 (sort-by (comp - val) un))))))
    (let [thin (sort (map key (filter #(<= (val %) 1) un)))]
      (println (str "  described by a single form family (" (count thin) "):"))
      (doseq [chunk (partition-all 24 thin)]
        (println (str "    " (str/join " " chunk)))))))

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

    (staleness)

    (countries)

    (depth)

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
  "freshness" (staleness)
  "countries" (countries)
  "depth" (depth)
  (do (report) (integrity)))
