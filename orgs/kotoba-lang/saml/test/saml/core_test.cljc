(ns saml.core-test
  (:require [clojure.test :refer [deftest is]]
            [saml.core :as c]
            [saml.model :as m]
            [saml.ports :as p]))

(deftest verifies-through-port
  (let [req (m/authn-request "s1" {:issuer "sp" :acs-url "https://sp.example/acs"})
        port (reify p/ISaml
               (verify-assertion! [_ _ _] (m/assertion-result true {:issuer "idp"
                                                                    :audience "sp"
                                                                    :subject "alice"})))]
    (is (:saml.assertion/ok? (c/verify port req "kagi://saml/assertion")))))

(deftest rejects-unverified-assertion
  (let [req (m/authn-request "s2" {:issuer "sp"})
        port (reify p/ISaml
               (verify-assertion! [_ _ _] (m/assertion-result false {:issuer "idp"})))]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (c/verify port req "kagi://saml/assertion")))))

(deftest relay-state-is-consumed-once
  (let [req (m/authn-request "s3" {:issuer "sp" :relay-state "relay-1"})
        store (p/memory-relay-state-store #{"relay-1"})
        port (reify p/ISaml
               (verify-assertion! [_ _ _] (m/assertion-result true {:issuer "idp"
                                                                    :audience "sp"
                                                                    :subject "alice"})))]
    (is (:saml.assertion/ok? (c/verify-once store port req "kagi://saml/assertion")))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (c/verify-once store port req "kagi://saml/assertion")))))
