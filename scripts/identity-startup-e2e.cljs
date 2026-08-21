#!/usr/bin/env nbb
;; 起動時 identity の決定経路を、実 Ed25519 で端から端まで走らせる（ADR-2608197300）。
;;
;;   nbb --classpath \
;;     "orgs/kotoba-lang/org-biscuitsec/src:orgs/kotoba-lang/org-biscuitsec/test:\
;; orgs/kotoba-lang/authority/src:orgs/kotoba-lang/org-w3-did/src:\
;; orgs/kotoba-lang/identity/src" \
;;     scripts/identity-startup-e2e.cljs
;;
;; JVM は要らない（biscuit.ed25519 の cljs 分岐が node:crypto を使う）。
;; org-biscuitsec の test/ を classpath に入れるのは、この repo が crypto を
;; 持たない設計で、実 Ed25519 が test-only の注入だから（deps.edn がそう書いている）。
;; 測った値は 90-docs/evidence/260819-identity-startup-biscuit-authority.edn。
(ns startup-e2e
  "起動時 identity flow を端から端まで: 実 Ed25519 → biscuit 委譲 → authority 判定。"
  (:require [biscuit.ed25519 :as e]
            [biscuit.token :as bt]
            [biscuit.kotoba :as bk]
            [authority.grant :as grant]
            [authority.chain :as chain]
            [did.core :as did]
            [identity.startup :as startup]))

(def root   (e/keypair (vec (range 32))))
(def device (e/keypair (vec (range 32 64))))
(def thief  (e/keypair (vec (map #(+ 100 %) (range 32)))))

(def root-did   (did/public-key->did-key (:public root)))
(def device-did (did/public-key->did-key (:public device)))
(def kinds #{:graph-read :graph-write})
(def now "2026-08-19T00:00:00Z")

(defn line [& xs] (println (apply str xs)))

;; ── 1. root が device を名指しして委譲する ────────────────────────────
(def issued
  (bt/authority {:facts [['cap "graph-read" "kotoba://graph/acme"]
                         ['cap "graph-read" "kotoba://graph/notes"]
                         ['before "2026-09-01T00:00:00Z"]]
                 :next-public-key (:public device)
                 :root-private-key (:private root) :sign-fn e/sign-fn}))

;; ── 2. device が root の知らない鍵で自分を減衰させる ──────────────────
(def narrowed
  (bt/append issued {:facts [['cap "graph-read" "kotoba://graph/acme"]]
                     :next-public-key (:public device)
                     :private-key (:private device) :sign-fn e/sign-fn}))

;; ── 3. 検証は root の *公開鍵* だけ ───────────────────────────────────
(def ok?    (:ok? (bt/verify narrowed (:public root) e/verify-fn)))
(def thief? (:ok? (bt/verify narrowed (:public thief) e/verify-fn)))

;; ── 4. 権限を増やそうとした block ─────────────────────────────────────
(def escalated
  (bt/append narrowed {:facts [['cap "graph-write" "kotoba://graph/everything"]]
                       :next-public-key (:public device)
                       :private-key (:private device) :sign-fn e/sign-fn}))

(defn ->grants [t]
  (let [{:keys [grants] :grant/keys [rejected]} (bk/->delegated t kinds)]
    {:grants (mapv #(grant/grant {:scopes (:grant/resources %)
                                  :expires (:grant/expires %)
                                  :holder device-did})
                   grants)
     :rejected rejected
     :raw grants}))

(def d  (->grants narrowed))
(def de (->grants escalated))

(def decision (startup/resolve-state {:device-did device-did :grants (:grants d) :now now}))
(def no-key   (startup/resolve-state {:device-did nil :grants [] :now now}))

(defn ask [gs requested]
  (:authority/allowed? (chain/authorize {:chain gs :requested requested
                                         :holder device-did :now now})))

(line "root   did = " root-did)
(line "device did = " device-did)
(line)
(line "[1] chain verifies with root PUBLIC key only ... " ok?)
(line "[2] same token under an attacker's root key  ... " thief?)
(line "[3] delegated grants        = " (pr-str (:raw d)))
(line "[4] escalation block result = " (pr-str (:raw de)))
(line "    rejected                = " (pr-str (:rejected de)))
(line)
(line "[5] startup state (key + live delegation) = "
      (:identity.startup/state decision) " / serve=" (:identity.startup/serve decision))
(line "[6] startup state (no key, unanswered)    = "
      (:identity.startup/state no-key) " / serve=" (:identity.startup/serve no-key)
      " / ask=" (:identity.startup/ask no-key))
(line)
(line "[7] authorize kotoba://graph/acme  (in scope)      = " (ask (:grants d) "kotoba://graph/acme"))
(line "[8] authorize kotoba://graph/notes (dropped by [2] block) = " (ask (:grants d) "kotoba://graph/notes"))
(line "[9] authorize kotoba://graph/everything (escalated) = " (ask (:grants de) "kotoba://graph/everything"))

;; ── 10. 閉じた kind 集合の外は「無視」ではなく reject ────────────────
(def foreign
  (bt/authority {:facts [['cap "kernel/format-disk" "/dev/sda"]
                         ['cap "graph-read" "kotoba://graph/acme"]]
                 :next-public-key (:public device)
                 :root-private-key (:private root) :sign-fn e/sign-fn}))
(def f (bk/->delegated foreign kinds))
(line)
(line "[10] unknown kind -> granted = " (pr-str (mapv :grant/kind (:grants f)))
      " / rejected = " (pr-str (:grant/rejected f)))
