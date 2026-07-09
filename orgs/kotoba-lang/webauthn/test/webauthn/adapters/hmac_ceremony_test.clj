(ns webauthn.adapters.hmac-ceremony-test
  (:require [clojure.test :refer [deftest is]]
            [webauthn.adapters.ceremony :as ceremony]
            [webauthn.adapters.hmac-ceremony :as hmac]
            [webauthn.core :as c]
            [webauthn.model :as m]))

(deftest verifies-hmac-registration-and-authentication
  (let [verifier (hmac/verifier "secret" {})
        port (ceremony/ceremony-port verifier {:origin "https://rp.example"
                                               :verified-at "2026-07-01T00:00:00Z"})
        reg-challenge (m/challenge "ch1" :registration {:rp-id "rp.example"
                                                        :user-handle "alice"})
        credential (c/register port reg-challenge {:credential-id "cred-1"
                                                   :transports #{:internal}})
        auth-challenge (m/challenge "ch2" :authentication {:rp-id "rp.example"
                                                           :challenge "challenge"})
        sig (hmac/signature "secret" "challenge" "cred-1")
        assertion (c/authenticate port auth-challenge {:credential-id "cred-1"
                                                       :signature sig
                                                       :sign-count 1})]
    (is (= "cred-1" (:webauthn.credential/id credential)))
    (is (:webauthn.assertion/ok? assertion))
    (is (:webauthn.assertion/user-verified? assertion))))

(deftest rejects-invalid-hmac-authentication-signature
  (let [verifier (hmac/verifier "secret" {})
        port (ceremony/ceremony-port verifier {})
        challenge (m/challenge "ch1" :authentication {:challenge "challenge"})
        assertion (c/authenticate port challenge {:credential-id "cred-1"
                                                  :signature "bad"})]
    (is (false? (:webauthn.assertion/ok? assertion)))))
