(ns identity.adapters.ledger-test
  (:require [clojure.test :refer [deftest is]]
            [identity.adapters.ledger :as a]
            [identity.model :as m]))

(deftest persists-identity-records-as-datoms
  (let [txs (atom [])
        ledger (reify a/ILedger
                 (transact! [_ datoms opts]
                   (swap! txs conj [datoms opts])
                   {:tx/id "tx1"}))
        subject (m/subject "did:web:example.com:alice" :person {:labels #{:member}})
        evidence (m/evidence-ref "e1" :credential {:ref "kagi://vc/1"})
        attestation (m/attestation "a1" "did:web:example.com:alice" :verified {:evidence ["e1"]})]
    (is (= {:tx/id "tx1"} (a/persist-subject! ledger subject {:case-ref "case-1"})))
    (a/persist-evidence! ledger evidence {})
    (a/persist-attestation! ledger attestation {})
    (is (= ["did:web:example.com:alice" "e1" "a1"]
           (mapv #(-> % first first :db/id) @txs)))))
