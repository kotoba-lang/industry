(ns authentication.adapters.decision-ledger
  (:require [authentication.datom :as datom]))

(defprotocol IDecisionLedger
  (transact! [ledger datoms opts]))

(defn persist-factor!
  ([ledger factor] (persist-factor! ledger factor {}))
  ([ledger factor opts]
   (transact! ledger (datom/factor-datoms factor) opts)))

(defn persist-decision!
  ([ledger decision] (persist-decision! ledger decision {}))
  ([ledger decision opts]
   (let [factor-datoms (mapcat datom/factor-datoms (:authn.decision/factors decision))
         decision-datoms (datom/decision-datoms decision)]
     (transact! ledger (vec (concat factor-datoms decision-datoms)) opts))))
