;; cloud-itonami — world electricity system dynamics estimate (nbb/cljs)
;;
;; Illustrative "試算" (trial estimate), NOT measured business data.
;; World-demand baseline is sourced from public IEA/Ember 2025 reports
;; (see 90-docs/business/cloud-itonami-energy-systemdynamics-estimate.md
;; for citations). All cloud-itonami-segment parameters (TAM fraction,
;; Bass p/q, churn, fee/TWh, onboarding-capacity growth) are ASSUMPTIONS
;; chosen to be order-of-magnitude plausible for a nascent B2B governance
;; SaaS, not sourced from any real cloud-itonami telemetry (none exists —
;; see the ADR this script is attached to). Run: `nbb 90-docs/business/
;; cloud-itonami-energy-systemdynamics-model.cljs`
;;
;; Model = Bass diffusion (per ISIC segment) nested inside a
;; capacity-constrained onboarding stock, compared across three
;; policies — this is the "Limits to Growth / Growth-and-Underinvestment"
;; SD archetype: aggressive booking beyond sustainable onboarding
;; throughput increases churn without necessarily improving long-run
;; cumulative revenue.

(ns cloud-itonami.energy-sd
  (:require ["fs" :as fs]))

(def start-year 2026)
(def horizon-years 25) ;; 2026..2050 inclusive

;; ---- World electricity demand baseline (TWh/yr) ----
;; 2024 actual: crossed 30,000 TWh, +4.0% y/y (Ember Global Electricity
;; Review 2025). IEA Electricity 2025: +3,500 TWh over 2025-2027 (~3.8%
;; CAGR), vs. 2010-2023 historical average 2.6%/yr. Growth rate modeled
;; as reverting from the near-term AI/data-center-driven rate toward the
;; long-run historical rate with an 8-year time constant.
(def d-2024 30000.0)
(def g-near 0.038)
(def g-long 0.026)
(def tau 8.0)

(defn demand-growth-rate [t-from-2024]
  (+ g-long (* (- g-near g-long) (Math/exp (- (/ t-from-2024 tau))))))

(defn world-demand-series []
  (loop [t 0 d d-2024 acc []]
    (if (> t (+ 2 horizon-years)) ;; extra headroom to reach start-year + horizon
      acc
      (recur (inc t) (* d (+ 1 (demand-growth-rate t))) (conj acc {:year (+ 2024 t) :demand-twh d})))))

(def world-demand
  (into {} (map (juxt :year :demand-twh) (world-demand-series))))

;; ---- cloud-itonami addressable segments (electricity-relevant ISIC only;
;; 3520 gas / 3530 steam excluded — different unit basis, out of scope for
;; a TWh-electricity coverage estimate) ----
(def segments
  {:isic-3510-td
   {:label "T&D grid operations"
    :tam-frac 0.15   ;; assumption: share of world electricity system realistically
                      ;; served by a 3rd-party AI-governed ops layer vs. in-house TSO/utility
    :p 0.010 :q 0.25 :churn 0.04
    :fee-usd-per-twh-yr 50000}
   :isic-3511-smr
   {:label "SMR generation operations"
    :tam-frac 0.03   ;; assumption: SMR stays a small slice of world generation for decades
    :p 0.005 :q 0.20 :churn 0.02
    :fee-usd-per-twh-yr 150000}
   :isic-3512-renew
   {:label "Community renewables operations"
    :tam-frac 0.10   ;; assumption: distributed/community solar+storage addressable slice
    :p 0.030 :q 0.40 :churn 0.05
    :fee-usd-per-twh-yr 30000}})

;; ---- Policies (the "optimal system dynamics" comparison) ----
(def policies
  {:aggressive       {:label "Aggressive (uncapped bookings)"
                       :cap-growth 0.35 :overshoot-churn-coef 0.50 :capped? false}
   :capacity-matched {:label "Capacity-matched (recommended)"
                       :cap-growth 0.35 :overshoot-churn-coef 0.0 :capped? true}
   :conservative     {:label "Conservative (slow capacity build)"
                       :cap-growth 0.15 :overshoot-churn-coef 0.0 :capped? true}})

(def c0 5.0) ;; TWh/yr onboarding-throughput capacity in start-year (illustrative)

(defn bass-desired-flow [tam m p q]
  (let [remaining (max 0.0 (- tam m))
        frac (if (pos? tam) (/ m tam) 0.0)]
    (max 0.0 (* (+ p (* q frac)) remaining))))

(defn simulate-policy [policy-key]
  (let [{:keys [cap-growth overshoot-churn-coef capped?]} (get policies policy-key)]
    (loop [t 0
           cap c0
           m (into {} (map (fn [[k _]] [k 0.0])) segments)
           rows []]
      (if (> t horizon-years)
        rows
        (let [year (+ start-year t)
              d (get world-demand year)
              desired (into {}
                            (map (fn [[k {:keys [tam-frac p q]}]]
                                   [k (bass-desired-flow (* tam-frac d) (get m k) p q)]))
                            segments)
              total-desired (reduce + (vals desired))
              scale (if (and capped? (pos? total-desired) (> total-desired cap))
                      (/ cap total-desired)
                      1.0)
              overshoot-ratio (if (pos? cap) (/ total-desired cap) 0.0)
              churn-mult (if capped?
                           1.0
                           (+ 1.0 (* overshoot-churn-coef (max 0.0 (dec overshoot-ratio)))))
              next-m (into {}
                           (map (fn [[k {:keys [churn]}]]
                                  (let [flow (* scale (get desired k))
                                        prev (get m k)
                                        churned (* prev churn churn-mult)]
                                    [k (max 0.0 (+ prev flow (- churned)))])))
                           segments)
              revenue (reduce + (map (fn [[k v]] (* v (get-in segments [k :fee-usd-per-twh-yr]))) next-m))
              total-m (reduce + (vals next-m))
              coverage-pct (if (pos? d) (* 100.0 (/ total-m d)) 0.0)]
          (recur (inc t)
                 (* cap (+ 1 cap-growth))
                 next-m
                 (conj rows {:year year
                             :world-demand-twh d
                             :segment-m next-m
                             :total-managed-twh total-m
                             :coverage-pct coverage-pct
                             :onboarding-capacity-twh-yr cap
                             :overshoot-ratio overshoot-ratio
                             :annual-revenue-usd revenue})))))))

(def results
  (into {} (map (fn [k] [k (simulate-policy k)])) (keys policies)))

(defn cumulative-revenue [rows]
  (reduce + (map :annual-revenue-usd rows)))

(defn fmt-num [n]
  (.toLocaleString (js/Math.round n) "en-US"))

(println "=== cloud-itonami world-electricity system-dynamics estimate ===")
(println (str "World demand " start-year ": " (fmt-num (get world-demand start-year)) " TWh/yr, "
              (+ start-year horizon-years) ": " (fmt-num (get world-demand (+ start-year horizon-years))) " TWh/yr"))
(println)
(doseq [[k {:keys [label]}] policies]
  (let [rows (get results k)
        checkpoints (filter #(zero? (mod (- (:year %) start-year) 5)) rows)]
    (println (str "--- Policy: " label " ---"))
    (doseq [{:keys [year total-managed-twh coverage-pct annual-revenue-usd onboarding-capacity-twh-yr overshoot-ratio]} checkpoints]
      (println (str "  " year
                     "  managed=" (fmt-num total-managed-twh) " TWh/yr"
                     "  coverage=" (.toFixed coverage-pct 4) "%"
                     "  cap=" (fmt-num onboarding-capacity-twh-yr) " TWh/yr"
                     "  overshoot=" (.toFixed overshoot-ratio 2) "x"
                     "  revenue=$" (fmt-num annual-revenue-usd) "/yr")))
    (println (str "  cumulative revenue " start-year "-" (+ start-year horizon-years) ": $"
                   (fmt-num (cumulative-revenue rows))))
    (println)))

(def output-edn
  (pr-str
   {:model/start-year start-year
    :model/horizon-years horizon-years
    :model/world-demand world-demand
    :model/segments segments
    :model/policies policies
    :model/results results
    :model/cumulative-revenue (into {} (map (fn [[k rows]] [k (cumulative-revenue rows)])) results)}))

(fs/writeFileSync "90-docs/business/cloud-itonami-energy-systemdynamics-results.edn" output-edn)
(println "Wrote 90-docs/business/cloud-itonami-energy-systemdynamics-results.edn")
