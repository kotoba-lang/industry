(ns authorization.adapters.decision-ledger-test
  (:require [authorization.adapters.decision-ledger :as ledger]
            [authorization.adapters.edn-decision-ledger :as edn-ledger]
            [authorization.model :as m]
            [clojure.test :refer [deftest is]]))

(deftest persists-request-and-decision-datoms
  (let [file (java.io.File/createTempFile "kotoba-authz-decision-ledger" ".edn")]
    (try
      (.delete file)
      (let [l (edn-ledger/edn-decision-ledger (.getPath file))
            req (m/request "authz-r1" "did:web:example.com:alice" "read" "doc:1"
                           {:context {:tenant "t1"}})
            decision (m/decision req :allow {:by "rules"
                                             :policy-ref "kagi://policy/doc-access"
                                             :policy-version "2026-07-01"
                                             :effect-trace [{:rule-id "allow-reader"
                                                             :decision :allow
                                                             :matched? true}]
                                             :obligations [{:type :audit}]
                                             :issued-at "2026-07-01T00:00:00Z"})]
        (is (= {:tx/id "tx-1" :tx/datoms 2 :tx/request-id "authz-r1" :tx/at nil}
               (ledger/persist-decision! l req decision {:request-id "authz-r1"})))
        (is (= ["tx-1"] (mapv :tx/id (edn-ledger/transactions (.getPath file)))))
        (is (= ["authz-r1" "authz-r1"]
               (mapv :db/id (edn-ledger/all-datoms (.getPath file)))))
        (is (= ["kagi://policy/doc-access"]
               (into [] (keep :authz.decision/policy-ref)
                     (edn-ledger/all-datoms (.getPath file))))))
      (finally
        (.delete file)))))

(deftest rejects-replayed-decision-request-id
  (let [file (java.io.File/createTempFile "kotoba-authz-decision-ledger" ".edn")]
    (try
      (.delete file)
      (let [l (edn-ledger/edn-decision-ledger (.getPath file))
            req (m/request "authz-r2" "did:web:example.com:alice" "read" "doc:1" {})
            decision (m/decision req :deny {:by "rules" :reason :no-matching-rule})]
        (ledger/persist-decision! l req decision {:request-id "authz-r2"})
        (is (= :authz.decision/replay
               (:error (ex-data (try
                                  (ledger/persist-decision! l req decision {:request-id "authz-r2"})
                                  (catch clojure.lang.ExceptionInfo e e)))))))
      (finally
        (.delete file)))))
