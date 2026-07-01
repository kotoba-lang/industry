(ns authentication.adapters.cacao
  (:require [authentication.model :as m]
            [authentication.ports :as p]))

(defprotocol ICacaoVerifier
  (verify-cacao! [verifier payload opts]))

(defn cacao-factor-verifier
  ([verifier] (cacao-factor-verifier verifier {}))
  ([verifier opts]
   (reify p/IFactorVerifier
     (verify-factor! [_ factor-request response]
       (let [claims (verify-cacao! verifier {:request factor-request
                                             :response response}
                                   opts)]
         (m/factor (:authn.factor-request/id factor-request)
                   :cacao
                   (true? (:ok? claims))
                   {:subject (or (:subject claims)
                                 (:authn.factor-request/subject factor-request))
                    :evidence-ref (:evidence-ref claims)
                    :assurance (:assurance claims)
                    :at (:at claims)}))))))

(defn static-cacao-verifier [claims]
  (reify ICacaoVerifier
    (verify-cacao! [_ _payload _opts] claims)))
