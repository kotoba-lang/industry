(ns webauthn.core-test
  (:require [clojure.test :refer [deftest is]]
            [webauthn.core :as c]
            [webauthn.model :as m]
            [webauthn.ports :as p]))

(deftest authenticates-through-port
  (let [ch (m/challenge "ch1" :authentication {:rp-id "example.com" :challenge "n"})
        port (reify p/IWebAuthn
               (register! [_ _ _] nil)
               (authenticate! [_ challenge assertion]
                 (m/assertion challenge (:credential-id assertion) true
                              {:user-present? true :user-verified? true}))
               (derive-prf! [_ _] nil))]
    (is (true? (:webauthn.assertion/ok? (c/authenticate port ch {:credential-id "cred1"}))))))

(deftest rejects-ok-assertion-without-user-verification
  (let [ch (m/challenge "ch2" :authentication {:rp-id "example.com" :challenge "n"})
        port (reify p/IWebAuthn
               (register! [_ _ _] nil)
               (authenticate! [_ challenge assertion]
                 (m/assertion challenge (:credential-id assertion) true
                              {:user-present? true :user-verified? false}))
               (derive-prf! [_ _] nil))]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (c/authenticate port ch {:credential-id "cred1"})))))

(deftest rejects-challenge-replay
  (let [ch (m/challenge "ch3" :authentication {:rp-id "example.com" :challenge "n"})
        store (p/memory-challenge-store)
        port (reify p/IWebAuthn
               (register! [_ _ _] nil)
               (authenticate! [_ challenge assertion]
                 (m/assertion challenge (:credential-id assertion) true
                              {:user-present? true :user-verified? true}))
               (derive-prf! [_ _] nil))]
    (is (:webauthn.assertion/ok? (c/authenticate-once port store ch {:credential-id "cred1"})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (c/authenticate-once port store ch {:credential-id "cred1"})))))
