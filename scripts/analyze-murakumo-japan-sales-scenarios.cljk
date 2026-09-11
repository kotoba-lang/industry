#!/usr/bin/env nbb

(ns analyze-murakumo-japan-sales-scenarios
  (:require ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def default-model
  "90-docs/business/murakumo-japan-sales-entity-scenarios.edn")

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- approx= [a b]
  (< (js/Math.abs (- (double a) (double b))) 1.0e-9))

(defn- read-model [path]
  (edn/read-string (str (fs/readFileSync path "utf8"))))

(defn- round-yen [n]
  (js/Math.round n))

(defn- weighted-score [weights scores]
  (reduce-kv
   (fn [total key weight]
     (+ total (* weight (get scores key 0.0))))
   0.0
   weights))

(defn- validate-model! [model]
  (let [weights (:score-weights model)
        scenarios (:scenarios model)
        selected (:selected model)
        fact-ids (map :id (:facts model))
        entity-ids (set (map :id (:entities model)))
        scenario-ids (map :id scenarios)]
    (when-not (= "cloud.itonami.decision-frame.v1" (:decision-frame/schema model))
      (fail! "unsupported decision-frame schema" {:schema (:decision-frame/schema model)}))
    (when-not (= (:scope model) (:decision-frame/scope model))
      (fail! "decision-method scope and record scope must agree"
             {:scope (:scope model) :record-scope (:decision-frame/scope model)}))
    (when-not (approx= 1.0 (reduce + (vals weights)))
      (fail! "score weights must sum to 1.0" {:weights weights}))
    (when-not (= (count fact-ids) (count (set fact-ids)))
      (fail! "fact ids must be unique" {:fact-ids fact-ids}))
    (when-not (= (count scenario-ids) (count (set scenario-ids)))
      (fail! "scenario ids must be unique" {:scenario-ids scenario-ids}))
    (when-not (some #{selected} scenario-ids)
      (fail! "selected scenario is not declared" {:selected selected}))
    (doseq [{:keys [from to] :as relation} (:relations model)]
      (when-not (and (contains? entity-ids from) (contains? entity-ids to))
        (fail! "relation references an undeclared entity" {:relation relation})))
    (doseq [{:keys [id scores]} scenarios]
      (when-not (= (set (keys weights)) (set (keys scores)))
        (fail! "scenario score keys must match score weights" {:scenario id :scores scores}))
      (when-not (every? #(and (number? %) (<= 0.0 % 1.0)) (vals scores))
        (fail! "scenario scores must be between 0 and 1" {:scenario id :scores scores})))
    model))

(defn- bracket-tax [profit brackets]
  (loop [remaining (max 0 profit)
         lower 0
         [{:keys [up-to-jpy rate]} & more] brackets
         total 0.0]
    (if (or (zero? remaining) (nil? rate))
      total
      (let [capacity (if up-to-jpy (- up-to-jpy lower) remaining)
            portion (min remaining capacity)]
        (recur (- remaining portion)
               (or up-to-jpy lower)
               more
               (+ total (* portion rate)))))))

(defn- consumption-tax-due [method output-tax import-tax]
  (case method
    :general (max 0 (- output-tax import-tax))
    :simplified-wholesale (* output-tax 0.10)
    :simplified-retail (* output-tax 0.20)
    (fail! "unknown consumption-tax method" {:method method})))

(defn- economics
  [inputs sale-price units method]
  (let [tax-rate (:consumption-tax-rate inputs)
        purchase (:purchase-price-jpy inputs)
        freight (:freight-and-insurance-per-unit-jpy inputs)
        duty-rate (:customs-duty-rate inputs)
        customs-value (+ purchase freight)
        duty (* customs-value duty-rate)
        import-tax (* (+ customs-value duty) tax-rate)
        net-sale (/ sale-price (+ 1.0 tax-rate))
        output-tax (- sale-price net-sale)
        gross-profit-per-unit (- net-sale purchase freight duty)
        annual-profit-before-income-tax
        (- (* gross-profit-per-unit units)
           (:illustrative-operating-cost-jpy inputs))
        japan-tax (bracket-tax annual-profit-before-income-tax
                               (:japan-effective-corporate-brackets inputs))
        us-rate (+ (:us-federal-corporate-rate inputs)
                   (:us-state-corporate-rate inputs))
        us-tax (* (max 0 annual-profit-before-income-tax) us-rate)]
    {:sale-price-tax-inclusive-jpy sale-price
     :annual-units units
     :consumption-tax-method method
     :customs-duty-per-unit-jpy (round-yen duty)
     :import-consumption-tax-per-unit-jpy (round-yen import-tax)
     :net-sale-per-unit-jpy (round-yen net-sale)
     :gross-profit-per-unit-before-operations-jpy (round-yen gross-profit-per-unit)
     :consumption-tax-due-per-unit-jpy
     (round-yen (consumption-tax-due method output-tax import-tax))
     :annual-profit-before-income-tax-jpy (round-yen annual-profit-before-income-tax)
     :illustrative-japan-corporate-tax-jpy (round-yen japan-tax)
     :illustrative-us-federal-plus-state-tax-jpy (round-yen us-tax)
     :illustrative-japan-after-tax-jpy
     (round-yen (- annual-profit-before-income-tax japan-tax))
     :illustrative-us-after-tax-before-extraction-jpy
     (round-yen (- annual-profit-before-income-tax us-tax))}))

(defn- rank-scenarios [model]
  (->> (:scenarios model)
       (map (fn [{:keys [id label scores]}]
              {:id id
               :label label
               :weighted-score (weighted-score (:score-weights model) scores)}))
       (sort-by (comp - :weighted-score))
       vec))

(defn- report [model]
  (let [inputs (:model-inputs model)
        sensitivity (:sensitivity model)
        results (for [sale-price (:sale-prices-tax-inclusive-jpy sensitivity)
                      units (:annual-units sensitivity)
                      method (:consumption-tax-methods sensitivity)]
                  (economics inputs sale-price units method))]
    {:analysis/schema "murakumo.japan-sales-entity-analysis.v1"
     :analysis/as-of (:decision-frame/as-of model)
     :analysis/model-id (:decision-frame/id model)
     :analysis/selected (:selected model)
     :analysis/ranking (rank-scenarios model)
     :analysis/baseline
     (economics inputs
                (:illustrative-sale-price-tax-inclusive-jpy inputs)
                (:illustrative-annual-units inputs)
                :general)
     :analysis/sensitivity (vec results)
     :analysis/open-gates
     (mapv :id (filter #(not= :closed (:status %)) (:hard-gates model)))
     :analysis/evidence-boundary
     "Income-tax values are internal illustrations before omitted compliance, shareholder extraction, foreign-tax-credit, per-capita, and intercompany effects. A score is a decision judgment, not a tax fact or authority."}))

(defn- self-test! []
  (let [inputs {:purchase-price-jpy 99000
                :freight-and-insurance-per-unit-jpy 0
                :customs-duty-rate 0.0
                :consumption-tax-rate 0.10
                :illustrative-operating-cost-jpy 0
                :us-federal-corporate-rate 0.21
                :us-state-corporate-rate 0.0
                :japan-effective-corporate-brackets
                [{:up-to-jpy 4000000 :rate 0.2137}
                 {:up-to-jpy 8000000 :rate 0.2317}
                 {:rate 0.3358}]}
        one (economics inputs 165000 1 :general)
        hundred (economics inputs 165000 100 :general)
        wholesale (economics inputs 165000 1 :simplified-wholesale)]
    (when-not (= 51000 (:gross-profit-per-unit-before-operations-jpy one))
      (fail! "gross-profit control failed" {:actual one}))
    (when-not (= 5100 (:consumption-tax-due-per-unit-jpy one))
      (fail! "general consumption-tax control failed" {:actual one}))
    (when-not (= 1500 (:consumption-tax-due-per-unit-jpy wholesale))
      (fail! "wholesale consumption-tax control failed" {:actual wholesale}))
    (when-not (= 1109670 (:illustrative-japan-corporate-tax-jpy hundred))
      (fail! "Japanese bracket-tax control failed" {:actual hundred}))
    (when-not (= 1071000 (:illustrative-us-federal-plus-state-tax-jpy hundred))
      (fail! "US corporate-tax control failed" {:actual hundred}))
    (println "SELF_TEST_OK")
    true))

(def argv
  (let [args (vec (drop 2 (js->clj js/process.argv)))]
    (if (and (seq args) (str/ends-with? (str (first args)) ".cljs"))
      (vec (rest args))
      args)))

(defn -main []
  (try
    (if (some #{"--self-test"} argv)
      (self-test!)
      (let [path (or (first (remove #(str/starts-with? % "--") argv)) default-model)
            model (validate-model! (read-model path))]
        (println (pr-str (report model)))))
    (catch :default e
      (println (str "ANALYSIS_FAILED " (.-message e)))
      (when-let [data (ex-data e)] (println (pr-str data)))
      (set! (.-exitCode js/process) 1))))

(-main)
