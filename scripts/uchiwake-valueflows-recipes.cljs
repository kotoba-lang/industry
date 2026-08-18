#!/usr/bin/env nbb
;; scripts/uchiwake-valueflows-recipes.cljs — the first Valueflows recipes with
;; real QUANTITIES, and therefore the first input the algorithms can actually run on.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/uchiwake-valueflows-recipes.cljs \
;;     --data-root $HOME/github/com-junkawasaki
;;   ... --check
;;
;; ADR-2608153000. Two committed files in cloud-itonami/uchiwake, joined:
;;
;;   data/ingest/openfoodfacts.sample.edn   ingredient `percent_estimate` per GTIN
;;   data/products.merged.kotoba.edn        that GTIN's net content and unit
;;
;; percent x net-content gives a mass per ingredient, so a product becomes a
;; vf:RecipeProcess with vf:hasRecipeInput flows that carry quantities. Nothing
;; is invented: both numbers come out of files, and Open Food Facts is a source
;; this repository already names in its README and CLAUDE.md.
;;
;; ## The derived masses are NOT a mass balance
;;
;; `percent_estimate` is Open Food Facts' ESTIMATE, and measured on this sample the
;; per-product sums are 114, 100.5 and 92 — none of them 100. Nested ingredients
;; are double-counted and incomplete declarations under-count. So the masses here
;; are indicative per-ingredient figures, the sum is reported per recipe, and
;; :mass-balanced? is asserted false. Reading them as a balance would turn an
;; estimate into an accounting identity.
;;
;; ## The bad GTIN is skipped, and says why
;;
;; The sample deliberately contains 5449000000997, a Coca-Cola code with a wrong
;; check digit, labelled "must be skipped". GTIN check digits are computable, so
;; this validates mod-10 and reports the rejection rather than trusting the label
;; — a fixture that is only skipped because a comment said so tests nothing.
;;
;; Exit codes: 0 written/identical · 1 STALE · 2 COULD NOT ANSWER.

(ns uchiwake-valueflows-recipes
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [clojure.edn :as edn]))

(def out-path "90-docs/valueflows/uchiwake-recipes-vf.datoms.edn")
(def dataset "uchiwake-valueflows-recipes")
(def off-rel "orgs/cloud-itonami/uchiwake/data/ingest/openfoodfacts.sample.edn")
(def graph-rel "orgs/cloud-itonami/uchiwake/data/products.merged.kotoba.edn")

;; Measured 2026-08-15: 4 OFF records, 3 of which join a graph product with a net
;; content, and 19 graph products carry a GTIN.
(def floor {:off-records 4 :graph-products-with-gtin 15 :recipes 3})

(defn- die [code msg] (println msg) (js/process.exit code))
(defn- slurp* [p] (str (fs/readFileSync p "utf8")))
(defn- exists? [p] (fs/existsSync p))

(defn- args []
  (let [a (vec (drop 2 (js->clj js/process.argv)))]
    {:data-root (or (second (drop-while #(not= "--data-root" %) a)) (.cwd js/process))
     :check? (boolean (some #{"--check"} a))}))

(defn- read-edn! [root rel]
  (let [f (path/join root rel)]
    (when-not (exists? f)
      (die 2 (str "CANNOT ANSWER: " f " is absent. west checkouts are gitignored,"
                  " so a worktree must be given --data-root.")))
    (edn/read-string (slurp* f))))

;; ── GTIN ──────────────────────────────────────────────────────────────────

(defn gtin-check-digit-valid?
  "GS1 mod-10. Digits are weighted 3,1,3,1,… from the right of the payload, and
   the check digit is what brings the total to the next multiple of ten.
   Computed rather than trusted: the sample's bad record is only labelled bad in
   a comment, and a fixture skipped because of a comment tests nothing."
  [s]
  ;; NB: (str/split "549" #"") is ["" "5" "4" "9"] in ClojureScript — the leading
  ;; empty string parses to NaN and made a first version of this reject every
  ;; code, good and bad alike. A check that rejects everything is as useless as
  ;; one that accepts everything, which is why it is tested against known-good
  ;; GTINs and not only against the bad one.
  (let [ds (mapv #(js/parseInt % 10) (remove str/blank? (str/split (str s) #"")))]
    (and (>= (count ds) 8)
         (every? #(not (js/isNaN %)) ds)
         (let [payload (vec (butlast ds))
               check (last ds)
               weighted (map-indexed (fn [i d]
                                       ;; rightmost payload digit gets 3
                                       (* d (if (even? (- (count payload) 1 i)) 3 1)))
                                     payload)
               total (reduce + 0 weighted)]
           (= check (mod (- 10 (mod total 10)) 10))))))

(defn- normalise-gtin [s] (str/replace (str s) #"^0+" ""))

;; ── recipes ───────────────────────────────────────────────────────────────

(defn- ingredient-flow [product-unit net-content ing]
  (let [pct (get ing "percent_estimate")]
    (when (and (number? pct) (number? net-content))
      {:resource-conforms-to (get ing "id")
       :name (get ing "text")
       :action "consume"
       :percent pct
       ;; percent x net content. Both numbers come from committed files.
       :quantity-value (/ (* pct net-content) 100)
       :quantity-unit product-unit})))

(defn- recipe [i off graph-product]
  (let [unit (:product/net-content-unit graph-product)
        net (:product/net-content graph-product)
        flows (vec (keep #(ingredient-flow unit net %) (get off "ingredients")))
        pct-sum (reduce + 0 (keep :percent flows))
        derived-sum (reduce + 0 (keep :quantity-value flows))]
    {:db/id (- (inc i))
     :source/dataset dataset
     :vf.recipe/process-id (str "make." (:product/id graph-product))
     :vf.recipe/name (str "Make " (:product/name graph-product))
     :vf.recipe/gtin (get off "code")
     :repo/path "orgs/cloud-itonami/uchiwake"
     ;; vf:hasRecipeOutput — one unit of the product at its stated net content
     :vf.recipe/output-resource (:product/id graph-product)
     :vf.recipe/output-quantity-value net
     :vf.recipe/output-quantity-unit unit
     :vf.recipe/output-action "produce"
     ;; vf:hasRecipeInput — one flow per declared ingredient
     :vf.recipe/input-count (count flows)
     :vf.recipe/inputs (mapv #(select-keys % [:resource-conforms-to :name :action
                                              :percent :quantity-value :quantity-unit])
                             flows)
     ;; the honesty pair: what the percentages sum to, and that it is not a balance
     :vf.recipe/percent-sum pct-sum
     :vf.recipe/derived-input-mass derived-sum
     :vf.recipe/mass-balanced? false
     :vf.recipe/quantity-derivation "percent_estimate / 100 * product net content"
     :vf.recipe/quantity-sourcing "open-food-facts-percent-estimate"}))

(defn- coverage [i recipes skipped]
  {:db/id (- (inc i))
   :source/dataset dataset
   :vf.coverage/recipes (count recipes)
   :vf.coverage/skipped (count skipped)
   :vf.coverage/skipped-detail (mapv #(select-keys % [:code :why]) skipped)
   :vf.coverage/input-flows (reduce + 0 (map :vf.recipe/input-count recipes))
   :vf.coverage/percent-sums (mapv (juxt :vf.recipe/gtin :vf.recipe/percent-sum) recipes)
   :vf.coverage/any-mass-balanced? false
   :vf.coverage/complete? false
   :vf.coverage/note
   (str "The first Valueflows recipes with quantities, and the first input the"
        " algorithms can run on. Every number comes from a committed file:"
        " Open Food Facts percent_estimate times the product graph's net content."
        " BUT THE MASSES ARE NOT A MASS BALANCE. percent_estimate is Open Food"
        " Facts' estimate and the per-product sums here are "
        (str/join ", " (map (comp str second)
                            (mapv (juxt :vf.recipe/gtin :vf.recipe/percent-sum) recipes)))
        " — none of them 100, because nested ingredients are double-counted and"
        " incomplete declarations under-count. Use them to size a requirement,"
        " not to reconcile one. :mass-balanced? is false on every recipe.")})

;; ── build ─────────────────────────────────────────────────────────────────

(let [{:keys [data-root check?]} (args)
      off (read-edn! data-root off-rel)
      graph (read-edn! data-root graph-rel)
      products (filterv :product/gtin graph)
      by-gtin (into {} (map (fn [p] [(normalise-gtin (:product/gtin p)) p])) products)]
  (when (< (count off) (:off-records floor))
    (die 2 (str "CANNOT ANSWER: read " (count off) " Open Food Facts records, floor "
                (:off-records floor) ".")))
  (when (< (count products) (:graph-products-with-gtin floor))
    (die 2 (str "CANNOT ANSWER: read " (count products) " graph products with a GTIN,"
                " floor " (:graph-products-with-gtin floor) ".")))
  (let [{:keys [ok skipped]}
        (reduce (fn [acc rec]
                  (let [code (get rec "code")
                        hit (get by-gtin (normalise-gtin code))]
                    (cond
                      (not (gtin-check-digit-valid? code))
                      (update acc :skipped conj
                              {:code code :why "GTIN check digit is wrong (computed mod-10)"})

                      (nil? hit)
                      (update acc :skipped conj
                              {:code code :why "no product with this GTIN in the graph"})

                      (not (number? (:product/net-content hit)))
                      (update acc :skipped conj
                              {:code code :why "the graph product has no net content, so a percentage cannot become a mass"})

                      :else (update acc :ok conj [rec hit]))))
                {:ok [] :skipped []} off)
        recipes (vec (map-indexed (fn [i [rec hit]] (recipe i rec hit)) ok))]
    (when (< (count recipes) (:recipes floor))
      (die 2 (str "CANNOT ANSWER: built " (count recipes) " recipes, floor "
                  (:recipes floor) ". An input is missing; a smaller file would"
                  " look complete.")))
    (let [cov (coverage (count recipes) recipes skipped)
          content (str ";; GENERATED by scripts/uchiwake-valueflows-recipes.cljs."
                       " DO NOT EDIT BY HAND.\n"
                       ";; ADR-2608153000. Regenerate:\n"
                       ";;   nbb --classpath \".:scripts/nbb_compat\" \\\n"
                       ";;     scripts/uchiwake-valueflows-recipes.cljs"
                       " --data-root $HOME/github/com-junkawasaki\n"
                       ";;\n"
                       ";; Sources: " off-rel "\n"
                       ";;          " graph-rel "\n"
                       ";; The LAST entity is coverage. Read :vf.recipe/mass-balanced? before\n"
                       ";; treating any derived mass as an accounting figure.\n"
                       (with-out-str (pprint/pprint (conj recipes cov))))]
      (println (str "built " (count recipes) " recipes · "
                    (:vf.coverage/input-flows cov) " input flows · skipped "
                    (count skipped) " " (pr-str (mapv :code skipped))
                    " · percent sums " (pr-str (:vf.coverage/percent-sums cov))))
      (if check?
        (let [have (when (exists? out-path) (slurp* out-path))]
          (cond
            (nil? have) (die 1 (str "STALE: " out-path " is absent"))
            (not= have content) (die 1 (str "STALE: " out-path " disagrees with the workspace"))
            :else (println (str "OK: " out-path " matches"))))
        (do (fs/mkdirSync (path/dirname out-path) #js {:recursive true})
            (fs/writeFileSync out-path content)
            (println (str "wrote " out-path " (" (count content) " bytes)")))))))
