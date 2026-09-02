(ns yui-xmile
  "結 (yui) — the five-domain shared participation model, OASIS XMILE 1.0
   via kotoba-lang/org-oasis-open-xmile. The model contract, measured-vs-
   assumed discipline, and the questions the run answers are documented in
   yui-run.cljs (the runnable entry) and the ADR that lands beside it.
   This namespace exposes the model builder for reuse by the bot profile's
   own tests (the profile must be able to re-run the model, not just cite it)."
  (:require [xmile.model :as m]
            [xmile.validate :as v]
            [xmile.execute :as x]
            [clojure.string :as str]
            ["fs" :as fs]))

(def weekly-reach-measured 3376)
(def conv-visit-signup-measured 0.0065263)

(defn build-model
  [{:keys [stop dt weekly-reach referral-rate conv-visit-signup
           conv-signup-active conv-active-contrib
           visitor-half-life signup-churn active-churn contributor-churn
           referral-half-life]
    :or {stop 104.0 dt 0.25
         weekly-reach 3376
         referral-rate 0.0
         conv-visit-signup 0.0065263
         conv-signup-active 0.5
         conv-active-contrib 0.1
         visitor-half-life 0.5
         signup-churn 0.02
         active-churn 0.05
         contributor-churn 0.02
         referral-half-life 2.0}}]
  (let [ss (m/sim-specs 0 stop {:xmile/dt dt :xmile/time-units "week"})]
    (-> (m/model "yui_five_domain_participation" {:xmile/sim-specs ss})
        (m/add-variable (m/stock "Unaware" "400000"
                                 {:xmile/outflows #{"Doors_Visit"}
                                  :xmile/non-negative? true}))
        (m/add-variable (m/stock "Door_Visitors" "0"
                                 {:xmile/inflows #{"Doors_Visit"}
                                  :xmile/outflows #{"Signup" "Visitor_Expire"}
                                  :xmile/non-negative? true}))
        (m/add-variable (m/stock "Signed_Up" "0"
                                 {:xmile/inflows #{"Signup"}
                                  :xmile/outflows #{"Activate" "Signup_Churn"}
                                  :xmile/non-negative? true}))
        (m/add-variable (m/stock "Active" "0"
                                 {:xmile/inflows #{"Activate"}
                                  :xmile/outflows #{"Contribute" "Active_Churn"}
                                  :xmile/non-negative? true}))
        (m/add-variable (m/stock "Contributors" "0"
                                 {:xmile/inflows #{"Contribute"}
                                  :xmile/outflows #{"Contributor_Churn"}
                                  :xmile/non-negative? true}))
        (m/add-variable (m/stock "Reputation" "0"
                                 {:xmile/inflows #{"Rep_Gain"}
                                  :xmile/outflows #{"Rep_Decay"}
                                  :xmile/non-negative? true}))
        (m/add-variable
         (m/flow "Doors_Visit"
                 (str "MIN(" weekly-reach " + " referral-rate
                      " * Contributors + rep_to_reach * Reputation, Unaware / DT)")))
        (m/add-variable (m/flow "Signup" "Door_Visitors * conv_visit_signup"))
        (m/add-variable (m/flow "Visitor_Expire" "Door_Visitors * visitor_half_life"))
        (m/add-variable (m/flow "Activate" "Signed_Up * conv_signup_active"))
        (m/add-variable (m/flow "Signup_Churn" "Signed_Up * signup_churn"))
        (m/add-variable (m/flow "Contribute" "Active * conv_active_contrib"))
        (m/add-variable (m/flow "Active_Churn" "Active * active_churn"))
        (m/add-variable (m/flow "Contributor_Churn" "Contributors * contributor_churn"))
        (m/add-variable (m/flow "Rep_Gain" "Contribute * rep_per_contrib"))
        (m/add-variable (m/flow "Rep_Decay" "Reputation / referral_half_life"))
        (m/add-variable (m/aux "conv_visit_signup" (str (double conv-visit-signup))))
        (m/add-variable (m/aux "conv_signup_active" (str (double conv-signup-active))))
        (m/add-variable (m/aux "conv_active_contrib" (str (double conv-active-contrib))))
        (m/add-variable (m/aux "visitor_half_life" (str (double visitor-half-life))))
        (m/add-variable (m/aux "signup_churn" (str (double signup-churn))))
        (m/add-variable (m/aux "active_churn" (str (double active-churn))))
        (m/add-variable (m/aux "contributor_churn" (str (double contributor-churn))))
        (m/add-variable (m/aux "referral_half_life" (str (double referral-half-life))))
        (m/add-variable (m/aux "rep_per_contrib" "0.2"))
        (m/add-variable (m/aux "rep_to_reach" "2.0")))))

(defn evaluate
  "Run one scenario. Returns final stocks + whether/when the R loop crosses
   budget-independence (organic reach alone >= measured weekly reach)."
  [{:keys [referral-half-life weekly-reach] :or {referral-half-life 2.0} :as scenario}]
  (let [mdl (build-model scenario)
        problems (v/validate mdl)
        valid? (v/valid? problems)
        result (when valid? (x/run mdl))
        series (:xmile/series result)
        times (:xmile/times result)
        contrib (get series "Contributors")
        rep (get series "Reputation")]
    (assoc (select-keys scenario [:name :note])
           :model mdl
           :valid? valid?
           :problems (count problems)
           :final-contributors (last contrib)
           :final-reputation (last rep)
           :final-visitors (last (get series "Door_Visitors"))
           :final-signed (last (get series "Signed_Up"))
           :final-active (last (get series "Active"))
           :crossing-week
           (some (fn [[t r]]
                   (when (>= (* r 2.0) (or weekly-reach 3376)) t))
                 (map vector times rep)))))

(def base
  {:name "S0-base-measured"
   :note "measured reach+conv, referral UNMEASURED => 0, assumed stage convs"
   :weekly-reach 3376 :referral-rate 0.0})

(def scenarios
  [base
   {:name "S1-double-visit-signup"
    :note "double the MEASURED visit->signup conversion (onboarding fix)"
    :weekly-reach 3376 :referral-rate 0.0
    :conv-visit-signup (* 2 conv-visit-signup-measured)}
   {:name "S2-double-active-contrib"
    :note "double assumed active->contributor (empowerment tooling)"
    :weekly-reach 3376 :referral-rate 0.0
    :conv-active-contrib 0.2}
   {:name "S3-halve-active-churn"
    :note "halve assumed active churn (retention)"
    :weekly-reach 3376 :referral-rate 0.0
    :active-churn 0.025}
   {:name "S4-referral-weak"
    :note "add a WEAK referral loop 0.05/week-contributor (UNMEASURED mechanism)"
    :weekly-reach 3376 :referral-rate 0.05}
   {:name "S5-referral-strong"
    :note "STRONG referral loop 0.25/week-contributor (UNMEASURED mechanism)"
    :weekly-reach 3376 :referral-rate 0.25}
   {:name "S6-combined-empowerment"
    :note "S1+S2+S4 together: fix onboarding, fund contribution, weak loop"
    :weekly-reach 3376 :referral-rate 0.05
    :conv-visit-signup (* 2 conv-visit-signup-measured)
    :conv-active-contrib 0.2}])
