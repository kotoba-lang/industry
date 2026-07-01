(ns faceid.core-test
  (:require [clojure.test :refer [deftest is]]
            [faceid.core :as c]
            [faceid.datom :as d]
            [faceid.model :as m]
            [faceid.ports :as p]))

(deftest accepts-attestation-metadata
  (let [req (m/request "f1" {:subject "did:web:example.com:alice"})
        port (reify p/IFaceID
               (attest! [_ r] (m/attestation r true {:device-id "iphone"})))]
    (is (true? (:faceid/ok? (c/attest port req))))))

(deftest emits-attestation-datoms
  (let [req (m/request "f1" {:subject "did:web:example.com:alice"})
        att (m/attestation req true {:device-id "iphone"})]
    (is (= [{:db/id "f1"
             :faceid/ok? true
             :faceid/purpose :step-up
             :faceid/subject "did:web:example.com:alice"
             :faceid/device-id "iphone"
             :faceid/credential-id nil
             :faceid/provider nil
             :faceid/evidence-ref nil
             :faceid/attested-at nil}]
           (d/attestation-datoms att)))))

(deftest rejects-face-template
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (c/attest (p/missing) (assoc (m/request "f2" {}) :face-template "no")))))

(deftest consumes-challenge-once-and-stores-device-bound-attestation
  (let [req (m/request "f3" {:subject "did:web:example.com:alice"
                             :challenge "challenge-1"})
        challenge-store (p/memory-challenge-store)
        attestation-store (p/memory-attestation-store)
        port (reify p/IFaceID
               (attest! [_ r] (m/attestation r true {:device-id "iphone"
                                                     :credential-id "cred-1"})))
        out (c/attest-once-and-store! challenge-store attestation-store port req)]
    (is (= "iphone" (:faceid/device-id out)))
    (is (= [out] (p/attestations-for-device attestation-store "iphone")))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (c/attest-once-and-store! challenge-store attestation-store port req)))))

(deftest rejects-storage-without-device-binding
  (let [req (m/request "f4" {:subject "did:web:example.com:alice"})
        att (m/attestation req true {})]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (c/attest-and-store! (p/memory-attestation-store) att)))))
