(ns identity.adapters.ledger
  (:require [identity.datom :as d]
            [identity.validate :as v]))

(defprotocol ILedger
  (transact! [ledger datoms opts]))

(defn persist-subject! [ledger subject opts]
  (v/valid! subject)
  (transact! ledger (d/subject-datoms subject) opts))

(defn persist-evidence! [ledger evidence opts]
  (v/valid! evidence)
  (transact! ledger (d/evidence-datoms evidence) opts))

(defn persist-attestation! [ledger attestation opts]
  (v/valid! attestation)
  (transact! ledger (d/attestation-datoms attestation) opts))
