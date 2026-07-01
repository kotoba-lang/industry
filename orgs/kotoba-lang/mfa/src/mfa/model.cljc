(ns mfa.model
  (:require [clojure.set :as set]))

(defn policy [id required opts]
  {:mfa.policy/id id
   :mfa.policy/required (set required)
   :mfa.policy/phishing-resistant? (boolean (:phishing-resistant? opts))
   :mfa.policy/purpose (:purpose opts)
   :mfa.policy/preferences (vec (:preferences opts))
   :mfa.policy/fallbacks (vec (:fallbacks opts))
   :mfa.policy/fallback-for (:fallback-for opts)})

(defn- satisfies-required? [fallback-for ok-types required]
  (or (contains? ok-types required)
      (boolean (some ok-types (get fallback-for required)))))

(defn result [policy factors]
  (let [ok-types (set (map :authn.factor/type (filter :authn.factor/ok? factors)))
        fallback-for (:mfa.policy/fallback-for policy)]
    {:mfa.result/policy-id (:mfa.policy/id policy)
     :mfa.result/factors (vec factors)
     :mfa.result/ok? (every? #(satisfies-required? fallback-for ok-types %)
                             (:mfa.policy/required policy))}))
