#!/usr/bin/env nbb
;; scripts/commodity-prices.cljs — pin real commodity prices, then derive a
;; per-gram cost basis the Valueflows rollup can use.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/commodity-prices.cljs --fetch
;;       (network; rewrites the observation file — run deliberately)
;;   nbb --classpath ".:scripts/nbb_compat" scripts/commodity-prices.cljs
;;   ... --check
;;
;; ADR-2608153000. `value-rollup` needs a value per resource and the workspace had
;; none. IMF Primary Commodity Prices, republished by FRED as CSV without a key,
;; cover three of Nutella's five declared ingredients.
;;
;; ## The units are measured, not assumed — and they differ
;;
;;   PSUGAISAUSDM  Global price of Sugar, No. 11, World   U.S. CENTS PER POUND
;;   PPOILUSDM     Global price of Palm Oil               U.S. DOLLARS PER METRIC TON
;;   PCOCOUSDM     Global price of Cocoa                  U.S. DOLLARS PER METRIC TON
;;
;; Read off each series page on 2026-08-15. Sugar is cents per pound while the
;; other two are dollars per tonne: multiplying them together without converting
;; is wrong by roughly five orders of magnitude, which is exactly why the unit is
;; carried on every observation and the conversion is recorded per row.
;;
;; Conversions use DEFINED values, not estimates: 1 international avoirdupois
;; pound = 0.45359237 kg exactly (1959 agreement), 1 metric ton = 1,000,000 g.
;;
;; ## A price is pinned, never fetched at check time
;;
;; These series update monthly. --fetch writes the observation file with the
;; series id, the published unit, the observation date and the fetch date; every
;; other mode reads that file. So --check is deterministic and a network outage
;; cannot make it fail, the same discipline the om-2 and ISIC mirrors use.
;;
;; ## Every row is a world commodity contract, not an ingredient price
;;
;; All three series price a WORLD COMMODITY CONTRACT: raw sugar (ICE No. 11),
;; crude palm oil, cocoa beans. Not one of them prices a food-grade processed
;; ingredient delivered to a factory — refined sugar, refined palm oil,
;; fat-reduced cocoa powder. The contract commodity is the INPUT to the
;; ingredient in all three cases, so the gap is uniform rather than a defect of
;; one row.
;;
;; That is why this is declared once as :price-basis :world-commodity-contract
;; and each row carries what its contract actually prices next to what the recipe
;; actually names, instead of a per-row boolean that would be true everywhere and
;; therefore say nothing. A processing gap always understates: refining,
;; transport and packaging are all downstream of the contract.
;;
;; It is also why `en:cocoa` and `en:fat-reduced-cocoa` both take the cocoa
;; series. Pricing the MORE processed of the two and leaving the less processed
;; one unpriced was the first version of this file, and it was incoherent: the
;; series is no closer to either.

(ns commodity-prices
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [clojure.edn :as edn]))

(def observed-path "90-docs/valueflows/commodity-prices.observed.edn")
(def out-path "90-docs/valueflows/uchiwake-costs-vf.datoms.edn")
(def recipes-path "90-docs/valueflows/uchiwake-recipes-vf.datoms.edn")
(def dataset "uchiwake-valueflows-costs")

;; Defined, not measured: the international avoirdupois pound and the metric ton.
(def grams-per-pound 453.59237)
(def grams-per-metric-ton 1000000)

;; :contract-prices says what the contract actually prices, so a row can show it
;; beside the ingredient the recipe actually names. :ingredients is a vector
;; because one contract legitimately covers several ingredient ids at different
;; processing stages — and picking only one of them is what made the first
;; version incoherent.
(def series
  [{:id "PSUGAISAUSDM" :title "Global price of Sugar, No. 11, World"
    :unit-published "U.S. Cents per Pound"
    :contract-prices "raw cane sugar, ICE No. 11 world contract"
    :ingredients ["en:sugar"]}
   {:id "PPOILUSDM" :title "Global price of Palm Oil"
    :unit-published "U.S. Dollars per Metric Ton"
    :contract-prices "crude palm oil"
    :ingredients ["en:palm-oil"]}
   {:id "PCOCOUSDM" :title "Global price of Cocoa"
    :unit-published "U.S. Dollars per Metric Ton"
    :contract-prices "cocoa beans"
    :ingredients ["en:cocoa" "en:fat-reduced-cocoa"]}])

(def price-basis :world-commodity-contract)

(defn- die [code msg] (println msg) (js/process.exit code))
(defn- slurp* [p] (str (fs/readFileSync p "utf8")))
(defn- exists? [p] (fs/existsSync p))

(defn- args []
  (let [a (vec (drop 2 (js->clj js/process.argv)))]
    {:fetch? (boolean (some #{"--fetch"} a))
     :check? (boolean (some #{"--check"} a))}))

;; ── fetch ─────────────────────────────────────────────────────────────────

(defn- curl [url]
  (let [r (.spawnSync cp "curl" (clj->js ["-sS" "-L" "--max-time" "30" url])
                      #js {:encoding "utf8"})]
    (when (zero? (.-status r)) (str (.-stdout r)))))

(defn- latest-observation
  "FRED's keyless CSV is `observation_date,<id>`. Takes the last row that has a
   value; a series in a reporting gap prints `.` and must not become 0."
  [csv id]
  (let [rows (->> (str/split-lines (str csv))
                  (drop 1)
                  (map #(str/split % #","))
                  (filter #(= 2 (count %)))
                  (remove #(str/blank? (str/trim (second %))))
                  (remove #(= "." (str/trim (second %)))))]
    (when-let [[d v] (last rows)]
      {:date (str/trim d) :value (js/parseFloat (str/trim v)) :series id})))

(defn- fetch! []
  (let [obs (mapv (fn [s]
                    (let [csv (curl (str "https://fred.stlouisfed.org/graph/fredgraph.csv?id=" (:id s)))]
                      (when-not csv
                        (die 2 (str "CANNOT ANSWER: could not fetch " (:id s)
                                    ". Nothing written — a partial price file would"
                                    " silently value some ingredients and not others.")))
                      (let [o (latest-observation csv (:id s))]
                        (when-not (and o (number? (:value o)) (pos? (:value o)))
                          (die 2 (str "CANNOT ANSWER: " (:id s)
                                      " returned no usable observation.")))
                        (merge s o))))
                  series)
        content (str ";; PINNED commodity price observations. Written by\n"
                    ";;   nbb scripts/commodity-prices.cljs --fetch\n"
                    ";; and read by every other mode, so --check never touches the network.\n"
                    ";;\n"
                    ";; Source: IMF Primary Commodity Prices via FRED (keyless CSV).\n"
                    ";; UNITS DIFFER BETWEEN SERIES and are recorded per row as published:\n"
                    ";; sugar is cents per pound, palm oil and cocoa are dollars per metric\n"
                    ";; ton. Converting with the wrong one is wrong by five orders of\n"
                    ";; magnitude.\n"
                    (with-out-str
                      (pprint/pprint {:schema "valueflows.commodity-prices.v1"
                                      :source "https://fred.stlouisfed.org/graph/fredgraph.csv?id=<series>"
                                      :units-read-from "https://fred.stlouisfed.org/series/<series>"
                                      :units-read-at "2026-08-15"
                                      :fetched-at (subs (.toISOString (js/Date.)) 0 10)
                                      :observations obs})))]
    (fs/mkdirSync (path/dirname observed-path) #js {:recursive true})
    (fs/writeFileSync observed-path content)
    (println (str "wrote " observed-path))
    (doseq [o obs] (println (str "  " (:series o) " " (:date o) " " (:value o)
                                 " " (:unit-published o))))))

;; ── derive ────────────────────────────────────────────────────────────────

(defn- usd-per-gram [{:keys [unit-published value]}]
  (cond
    (= unit-published "U.S. Cents per Pound") (/ (/ value 100) grams-per-pound)
    (= unit-published "U.S. Dollars per Metric Ton") (/ value grams-per-metric-ton)
    :else nil))

(defn- build []
  (when-not (exists? observed-path)
    (die 2 (str "CANNOT ANSWER: " observed-path " is absent. Run --fetch once and"
                " commit it; prices are pinned, not fetched at derive time.")))
  (when-not (exists? recipes-path)
    (die 2 (str "CANNOT ANSWER: " recipes-path " is absent, so there is nothing to"
                " cost.")))
  (let [{:keys [observations fetched-at]} (edn/read-string (slurp* observed-path))
        recipes (filterv :vf.recipe/process-id (edn/read-string (slurp* recipes-path)))
        _ (when (< (count observations) 3)
            (die 2 (str "CANNOT ANSWER: only " (count observations)
                        " price observations; a partial price file values some"
                        " ingredients and not others, which reads as a cheaper"
                        " product rather than an unpriced one.")))
        ;; one series can cover several ingredient ids, so the index is built by
        ;; expanding :ingredients rather than keying on a single id
        priced (into {} (for [o observations
                              ing (:ingredients o)]
                          [ing (assoc o :usd-per-gram (usd-per-gram o))]))
        rows (vec (for [r recipes
                        i (:vf.recipe/inputs r)
                        :let [p (get priced (:resource-conforms-to i))
                              unit (:quantity-unit i)
                              qty (:quantity-value i)
                              ;; a price per GRAM cannot cost a volume
                              costable? (and p (= "g" unit) (number? qty))]]
                    (cond-> {:recipe (:vf.recipe/process-id r)
                             :resource (:resource-conforms-to i)
                             :quantity-value qty
                             :quantity-unit unit}
                      costable?
                      (assoc :usd-per-gram (:usd-per-gram p)
                             :cost-usd (* qty (:usd-per-gram p))
                             :price-series (:series p)
                             :price-observed (:date p)
                             :price-unit-published (:unit-published p)
                             ;; the gap, stated per row: what the contract prices
                             ;; against what the recipe names
                             :price-contract-prices (:contract-prices p)
                             :price-basis price-basis)
                      (and p (not= "g" unit))
                      (assoc :unpriced-because
                             (str "the quantity is in " unit
                                  " and the price is per gram; no density is recorded"))
                      (nil? p)
                      (assoc :unpriced-because "no commodity price series covers this ingredient"))))
        by-recipe (group-by :recipe rows)]
    {:fetched-at fetched-at :rows rows :by-recipe by-recipe :priced priced}))

(defn- entities [{:keys [rows by-recipe fetched-at]}]
  (let [recipe-ents
        (vec (map-indexed
              (fn [i [rid rs]]
                (let [costed (filter :cost-usd rs)
                      unpriced (remove :cost-usd rs)]
                  {:db/id (- (inc i))
                   :source/dataset dataset
                   :vf.cost/recipe rid
                   :vf.cost/inputs (count rs)
                   :vf.cost/priced-inputs (count costed)
                   :vf.cost/unpriced-inputs (mapv #(select-keys % [:resource :unpriced-because]) unpriced)
                   ;; the partial total, and the fact that it IS partial
                   :vf.cost/partial-cost-usd (reduce + 0 (map :cost-usd costed))
                   :vf.cost/complete? (empty? unpriced)
                   ;; a lower bound twice over: unpriced inputs contribute zero,
                   ;; and every priced input is at its upstream contract
                   :vf.cost/is-lower-bound? true
                   :vf.cost/price-basis price-basis
                   :vf.cost/rows (mapv #(select-keys % [:resource :quantity-value :quantity-unit
                                                        :usd-per-gram :cost-usd :price-series
                                                        :price-observed :price-contract-prices
                                                        :unpriced-because])
                                       rs)}))
              by-recipe))]
    (conj recipe-ents
          {:db/id (- (inc (count recipe-ents)))
           :source/dataset dataset
           :vf.coverage/recipes-costed (count recipe-ents)
           :vf.coverage/input-rows (count rows)
           :vf.coverage/priced-rows (count (filter :cost-usd rows))
           :vf.coverage/unpriced-rows (count (remove :cost-usd rows))
           :vf.coverage/prices-fetched-at fetched-at
           :vf.coverage/any-complete? (boolean (some :vf.cost/complete? recipe-ents))
           :vf.coverage/price-basis price-basis
           :vf.coverage/complete? false
           :vf.coverage/note
           (str "A PARTIAL cost basis, and every total here is a LOWER BOUND"
                " twice over. (1) Coverage: three commodity series exist and the"
                " remaining ingredients have none, so their rows carry"
                " :unpriced-because and contribute zero — an unpriced ingredient"
                " reads as a cheaper product unless that field is read."
                " (2) Basis: all three series price a WORLD COMMODITY CONTRACT"
                " (raw cane sugar No. 11, crude palm oil, cocoa beans), not the"
                " food-grade processed ingredient the recipe names. Refining,"
                " transport and packaging are all downstream of the contract, so"
                " the gap only ever understates. Each row carries"
                " :price-contract-prices beside its ingredient so the gap is"
                " visible where it applies. One unit refusal is also worth"
                " reading: a beverage's net content is in ml, and a price per"
                " gram cannot cost a volume without a density, so those rows are"
                " unpriced rather than silently treating ml as g. Finally the"
                " masses come from Open Food Facts percent estimates that do not"
                " sum to 100. This sizes an order of magnitude; it is not a cost"
                " of goods.")})))

(defn- render [ents]
  (str ";; GENERATED by scripts/commodity-prices.cljs. DO NOT EDIT BY HAND.\n"
       ";; ADR-2608153000. Prices come from " observed-path " (pinned), masses from\n"
       ";; " recipes-path ". Regenerate:\n"
       ";;   nbb --classpath \".:scripts/nbb_compat\" scripts/commodity-prices.cljs\n"
       ";;\n"
       ";; Read :vf.cost/complete? and :vf.cost/unpriced-inputs before citing any\n"
       ";; total: every one is a lower bound.\n"
       (with-out-str (pprint/pprint ents))))

(let [{:keys [fetch? check?]} (args)]
  (if fetch?
    (fetch!)
    (let [built (build)
          ents (entities built)
          content (render ents)
          cov (last ents)]
      (println (str "costed " (:vf.coverage/recipes-costed cov) " recipes · rows "
                    (:vf.coverage/input-rows cov) " · priced "
                    (:vf.coverage/priced-rows cov) " · unpriced "
                    (:vf.coverage/unpriced-rows cov)
                    " · prices from " (:vf.coverage/prices-fetched-at cov)))
      (if check?
        (let [have (when (exists? out-path) (slurp* out-path))]
          (cond
            (nil? have) (die 1 (str "STALE: " out-path " is absent"))
            (not= have content) (die 1 (str "STALE: " out-path " disagrees with the inputs"))
            :else (println (str "OK: " out-path " matches"))))
        (do (fs/mkdirSync (path/dirname out-path) #js {:recursive true})
            (fs/writeFileSync out-path content)
            (println (str "wrote " out-path " (" (count content) " bytes)")))))))
