(ns mfa.adapters.orchestrator
  (:require [mfa.adapters.planner :as planner]
            [mfa.core :as core]))

(defprotocol IFactorProvider
  (verify-factor! [provider factor-request response opts]))

(defn provider-map [& kvs]
  (apply hash-map kvs))

(defn provider [f]
  (reify IFactorProvider
    (verify-factor! [_ factor-request response opts]
      (f factor-request response opts))))

(defn- response-for [responses factor-request]
  (or (get responses (:authn.factor-request/id factor-request))
      (get responses (:authn.factor-request/type factor-request))
      {}))

(defn- try-factor [providers factor-request responses opts]
  (let [factor-type (:authn.factor-request/type factor-request)
        p (get providers factor-type)]
    (if p
      (try
        (verify-factor! p factor-request (response-for responses factor-request) opts)
        (catch #?(:clj Exception :cljs :default) e
          {:authn.factor/id (:authn.factor-request/id factor-request)
           :authn.factor/type factor-type
           :authn.factor/ok? false
           :authn.factor/error (ex-message e)}))
      {:authn.factor/id (:authn.factor-request/id factor-request)
       :authn.factor/type factor-type
       :authn.factor/ok? false
       :authn.factor/error :missing-provider})))

(defn- fallback-request [subject factor-type]
  {:authn.factor-request/type factor-type
   :authn.factor-request/subject subject})

(defn orchestrate [planner providers policy subject context responses opts]
  (let [plan (planner/plan planner policy subject context opts)
        primary-requests (:mfa.plan/factor-requests plan)
        primary-factors (mapv #(try-factor providers % responses opts) primary-requests)
        primary-result (core/evaluate policy primary-factors)]
    (if (:mfa.result/ok? primary-result)
      (assoc primary-result
             :mfa.result/plan plan
             :mfa.result/fallback-used? false)
      (let [fallback-requests (mapv #(fallback-request subject %) (:mfa.plan/fallbacks plan))
            fallback-factors (mapv #(try-factor providers % responses opts) fallback-requests)
            result (core/evaluate policy (vec (concat primary-factors fallback-factors)))]
        (assoc result
               :mfa.result/plan plan
               :mfa.result/fallback-used? (boolean (seq fallback-factors)))))))
