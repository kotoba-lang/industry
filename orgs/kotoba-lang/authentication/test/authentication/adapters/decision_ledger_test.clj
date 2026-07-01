(ns authentication.adapters.decision-ledger-test
  (:require [authentication.adapters.decision-ledger :as ledger]
            [authentication.adapters.edn-decision-ledger :as edn-ledger]
            [authentication.model :as m]
            [clojure.test :refer [deftest is]]))

(deftest persists-decision-with-factor-datoms
  (let [file (java.io.File/createTempFile "kotoba-authn-decision-ledger" ".edn")]
    (try
      (.delete file)
      (let [l (edn-ledger/edn-decision-ledger (.getPath file))
            req (m/request "authn-r1" "did:web:example.com:alice" {:required-level :multi-factor})
            factors [(m/factor "factor-1" :totp true {:subject "did:web:example.com:alice"})
                     (m/factor "factor-2" :webauthn true {:subject "did:web:example.com:alice"})]
            decision (m/decision req :authenticated factors {:level :phishing-resistant
                                                             :issued-at "2026-07-01T00:00:00Z"})]
        (is (= {:tx/id "tx-1" :tx/datoms 3 :tx/request-id "authn-r1" :tx/at nil}
               (ledger/persist-decision! l decision {:request-id "authn-r1"})))
        (is (= ["tx-1"] (mapv :tx/id (edn-ledger/transactions (.getPath file)))))
        (is (= ["factor-1" "factor-2" "authn-r1"]
               (mapv :db/id (edn-ledger/all-datoms (.getPath file))))))
      (finally
        (.delete file)))))

(deftest rejects-replayed-decision-request-id
  (let [file (java.io.File/createTempFile "kotoba-authn-decision-ledger" ".edn")]
    (try
      (.delete file)
      (let [l (edn-ledger/edn-decision-ledger (.getPath file))
            req (m/request "authn-r2" "did:web:example.com:alice" {})
            factor (m/factor "factor-3" :touchid true {:subject "did:web:example.com:alice"})
            decision (m/decision req :authenticated [factor] {:level :single-factor})]
        (ledger/persist-decision! l decision {:request-id "authn-r2"})
        (is (= :authn.decision/replay
               (:error (ex-data (try
                                  (ledger/persist-decision! l decision {:request-id "authn-r2"})
                                  (catch clojure.lang.ExceptionInfo e e)))))))
      (finally
        (.delete file)))))
