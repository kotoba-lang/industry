(ns authentication.core-test
  (:require [clojure.test :refer [deftest is]]
            [authentication.core :as c]
            [authentication.model :as m]
            [authentication.ports :as p]))

(deftest combines-factors
  (let [req (m/request "r1" "did:web:example.com:alice" {:required-level :multi-factor})
        fs [(m/factor "f1" :totp true {})
            (m/factor "f2" :touchid true {})]
        out (c/decide req fs)]
    (is (= :authenticated (:authn.decision/decision out)))
    (is (= :multi-factor (:authn.decision/level out)))))

(deftest verifies-factor-requests-through-host-verifiers
  (let [req (m/request "r2" "did:web:example.com:alice" {:required-level :multi-factor})
        frs [(m/factor-request "fr1" :totp {:subject "did:web:example.com:alice"})
             (m/factor-request "fr2" :webauthn {:subject "did:web:example.com:alice"})]
        responses [{:authn.factor-response/request-id "fr1" :code "123456"}
                   {:authn.factor-response/request-id "fr2" :assertion-ref "kagi://assertion"}]
        ok-verifier (fn [factor-type]
                      (reify p/IFactorVerifier
                        (verify-factor! [_ factor-request _]
                          (m/factor (:authn.factor-request/id factor-request)
                                    factor-type
                                    true
                                    {:subject (:authn.factor-request/subject factor-request)}))))
        verifiers (p/verifier-map :totp (ok-verifier :totp)
                                  :webauthn (ok-verifier :webauthn))
        out (c/authenticate verifiers req frs responses)]
    (is (= :authenticated (:authn.decision/decision out)))
    (is (= :phishing-resistant (:authn.decision/level out)))))
