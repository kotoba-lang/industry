#!/usr/bin/env nbb
;; The pass, round-tripped through the real library: mint with the real signer,
;; verify with the real verifier, fold with `biscuit.kotoba/->delegated`.
;;
;; `x402_bot_pay_test.cljs` builds verified-pass MAPS and stays pure. That is
;; the right shape for the policy decisions and it cannot see the boundary
;; underneath — whether a token this issuer actually mints verifies, and
;; whether its facts survive encoding well enough to be folded. This is that
;; boundary, and it is the same reason `cacao_transfer_e2e_test` exists one
;; layer down: a decision test proves the decision, not the credential.

(ns x402-bot-pass-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [biscuit.wire :as wire]
            [biscuit.kotoba :as bk]
            [ed25519.core :as ed]
            [x402-bot-pass :as pass]
            [x402-bot-pay :as pay]
            ["crypto" :as crypto]))

(def ^:private root-seed (.digest (doto (crypto/createHash "sha256")
                                    (.update "x402-bot-pass-test-root"))))
(def ^:private root-pub (ed/pubkey-from-seed root-seed))

(defn- ->u8 [xs] (js/Uint8Array.from (clj->js (vec xs))))

(defn- mint-token
  "One authority block, minted with the same writer and signer the issuer uses."
  [facts]
  (let [next-secret (vec (crypto/randomBytes 32))
        next-public (vec (ed/pubkey-from-seed (->u8 next-secret)))]
    (wire/encode-authority-token
     {:facts facts :root-private-key root-seed
      :next-secret next-secret :next-public-key next-public
      :sign-fn pass/sign-bytes})))

(defn- verify+fold [token pub]
  (let [decoded (wire/decode-token token)
        v (wire/verify decoded (vec pub) pass/verify-bytes)
        model (wire/token->model decoded)]
    (when (:ok? v)
      (assoc (bk/->delegated model #{:credits-spend})
             :limits (pay/spend-limits model)))))

(def ^:private facts
  (pass/pass-facts {:sellers ["murakumo" "kotobase"]
                    :expires "2026-09-01T00:00:00Z"
                    :holder "did:key:zBot"
                    :per-call 1 :per-day 20}))

(deftest a-minted-pass-verifies-and-folds-to-the-grant-it-names
  (let [folded (verify+fold (mint-token facts) root-pub)
        grant (first (:grants folded))]
    (is (some? folded) "the writer and the verifier agree")
    (is (= :credits-spend (:grant/kind grant)))
    (is (= ["kotoba://credits/kotobase" "kotoba://credits/murakumo"]
           (:grant/resources grant)))
    (is (= "2026-09-01T00:00:00Z" (:grant/expires grant)))
    (is (= "did:key:zBot" (:grant/holder folded)))
    (is (= {:per-call 1 :per-day 20} (:limits folded))
        "the limit facts confer nothing to the library and survive for us to fold")
    (testing "and it becomes exactly the buyer policy the payer will use"
      (let [pol (pay/pass-policy (assoc folded :pass/verified? true)
                                 [["murakumo" "CREDITS"]])]
        (is (= #{"murakumo" "kotobase"} (:pay-tos pol)))
        (is (= {["murakumo" "CREDITS"] 1} (:caps pol)))))))

(deftest a-pass-signed-by-another-key-does-not-verify
  (testing "the root PUBLIC key is the whole check — a token from a key we did
            not issue confers nothing rather than folding to a smaller grant"
    (let [other (.digest (doto (crypto/createHash "sha256") (.update "not-our-root")))]
      (is (nil? (verify+fold (mint-token facts) (ed/pubkey-from-seed other)))))))

(deftest a-kind-this-authority-does-not-issue-is-rejected-not-ignored
  (testing "`->delegated` lands it in :grant/rejected — visible, conferring
            nothing, and not an exception"
    (let [folded (verify+fold (mint-token [['cap "graph-read" "kotoba://graph/x"]
                                           ['before "2026-09-01T00:00:00Z"]])
                              root-pub)]
      (is (empty? (:grants folded)))
      (is (= [{:kind :graph-read :resource "kotoba://graph/x"}] (:grant/rejected folded)))
      (is (nil? (pay/pass-policy (assoc folded :pass/verified? true)
                                 [["murakumo" "CREDITS"]]))))))

(deftest the-issuance-policy-refuses-a-bot-it-was-never-told-about
  (let [registry {:allowance/sellers #{"murakumo"}
                  :allowance/bots {"listed" {:credits/per-call 1 :credits/per-day 5}}}]
    (is (= {:sellers ["murakumo"] :per-call 1 :per-day 5}
           (pass/issuance-policy registry "listed")))
    (is (nil? (pass/issuance-policy registry "stranger"))
        "a pass that grants nothing and a bot nobody granted anything are
         different situations; only one of them is worth a token")))

(deftest a-resource-shape-this-issuer-did-not-mint-confers-no-seller
  (is (= "murakumo" (pass/resource->seller (pass/seller->resource "murakumo"))))
  (is (nil? (pass/resource->seller "kotoba://graph/murakumo")))
  (is (nil? (pass/resource->seller "kotoba://credits/UPPER"))))

(defmethod t/report [::t/default :end-run-tests] [m]
  (when-not (t/successful? m) (js/process.exit 1)))

(t/run-tests 'x402-bot-pass-test)
