#!/usr/bin/env nbb
;; The pure half of scripts/x402_bot_pay.cljs: who may spend, how much is left,
;; and which refusal a caller is told. Everything here is a table — no seed, no
;; socket, no ledger file — which is the point of the payer keeping its
;; decisions out of its I/O.
;;
;;   nbb --classpath "<pay,cacao,ed25519,cbor,authority,local-murakumo>:scripts" \
;;       scripts/x402_bot_pay_test.cljs

(ns x402-bot-pay-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [pay.x402-buyer :as buyer]
            [x402-bot-pay :as p]))

;; A VERIFIED pass, in the shape `read-pass` returns. Built here rather than
;; minted so these stay pure; the mint→verify→fold round trip is exercised by
;; x402_bot_pass_test.cljs against the real library.
(defn- pass
  [& {:keys [sellers per-call per-day verified?]
      :or {sellers ["murakumo" "kotobase"] per-call 1 per-day 3 verified? true}}]
  {:pass/verified? verified?
   :holder "did:key:zBot"
   :grants [{:grant/kind :credits-spend
             :grant/resources (vec (for [s sellers] (str "kotoba://credits/" s)))}]
   :limits (cond-> {} per-call (assoc :per-call per-call)
                      per-day (assoc :per-day per-day))})

(def rails [["murakumo" "CREDITS"]])

(def registry
  {:allowance/version 1
   :allowance/ledger "https://api.murakumo.cloud"
   :allowance/sellers #{"murakumo" "kotobase"}
   :allowance/rails [["murakumo" "CREDITS"]]
   :allowance/bots {"scout" {:credits/per-call 1 :credits/per-day 3}}})

(defn- row [bot at amount & {:keys [phase] :or {phase :reserved}}]
  {:bot bot :at at :phase phase :amount amount})

(def credits-offer
  {:scheme "credits" :network "murakumo" :asset {:symbol "CREDITS"}
   :payTo "murakumo" :maxAmountRequired "1"})

(def usdc-offer
  {:scheme "transaction" :network "base" :payTo "0xA00366234D29d4F882088048c0B2fa0dB7302D4E"
   :asset "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913" :maxAmountRequired "10000"})

;; ── deny by default ─────────────────────────────────────────────────────────

(deftest a-bot-holding-no-pass-cannot-pay
  (testing "権限の正本は署名された pass。持っていない bot の policy は nil で、
            nil は buyer 側で :buyer/no-policy になる —— file を編集して
            広げられない床であることが、file から token へ移した理由"
    (is (nil? (p/pass-policy {:pass/verified? false} rails)))
    (is (nil? (p/pass-policy (pass :verified? false) rails))
        "grant を持っていても、検証されていない credential は権限を与えない")
    (is (= :buyer/no-policy
           (:refuse (buyer/plan {:accepts [credits-offer]}
                                (p/pass-policy {:pass/verified? false} rails)))))
    (is (= :allowance/no-pass
           (:reason (p/allowance-verdict nil [] "scout" "2026-08-31" 1))))))

(deftest a-grant-of-another-kind-confers-no-seller
  (testing "credits の口座でない resource を seller に切り出さない ——
            kotoba://graph/murakumo は口座ではない"
    (is (nil? (p/resource->seller "kotoba://graph/murakumo")))
    (is (= "murakumo" (p/resource->seller "kotoba://credits/murakumo")))
    (is (nil? (p/pass-policy {:pass/verified? true
                              :grants [{:grant/kind :credits-spend
                                        :grant/resources ["kotoba://graph/murakumo"]}]
                              :limits {:per-call 1}}
                             rails)))))

(deftest a-pass-that-says-nothing-about-how-much-authorises-nothing
  (testing "沈黙を「上限なし」と読むのが、fact を 1 つ落とした token を
            無制限の支出に変える経路"
    (is (nil? (p/pass-policy (pass :per-call nil) rails)))
    (is (= :allowance/no-per-call-limit
           (:reason (p/allowance-verdict {:per-day 3} [] "scout" "2026-08-31" 1))))
    (is (= :allowance/no-per-day-limit
           (:reason (p/allowance-verdict {:per-call 1} [] "scout" "2026-08-31" 1))))))

;; ── limits attenuate, and only downwards ────────────────────────────────────

(deftest a-later-block-may-lower-a-limit-and-never-raise-one
  (testing "block fold が narrowing 専用なのと同じ理由で、数値も最小勝ち。
            広げられる attenuation は attenuation ではない"
    (let [model {:biscuit/blocks
                 [{:block/facts [['limit "per-call" 5] ['limit "per-day" 20]]}
                  {:block/facts [['limit "per-call" 1]]}
                  {:block/facts [['limit "per-day" 99] ['limit "per-call" 50]]}]}]
      (is (= {:per-call 1 :per-day 20} (p/spend-limits model))
          "後の block の 99 も 50 も上限を上げない"))))

(deftest limits-a-token-never-carried-are-absent-not-zero
  (is (= {} (p/spend-limits {:biscuit/blocks [{:block/facts [['holder "did:key:zBot"]]}]}))))

;; ── the caps ────────────────────────────────────────────────────────────────

(deftest a-per-call-cap-is-a-cap
  (is (:ok? (p/allowance-verdict {:per-call 1 :per-day 3} [] "scout" "2026-08-31" 1)))
  (is (= :allowance/over-per-call
         (:reason (p/allowance-verdict {:per-call 1 :per-day 3} [] "scout" "2026-08-31" 2)))))

(deftest the-day-limit-counts-reservations-not-settlements
  (testing "mint と seller の答えの間で死んだ支払いは、課金されている
            かもしれない。確定した分だけ数えると、crash のたびに
            その日の上限が上がる"
    (let [lim {:per-call 1 :per-day 3}
          rows [(row "scout" "2026-08-31T01:00:00Z" 1)
                (row "scout" "2026-08-31T02:00:00Z" 1)]]
      (is (= 2 (p/spent-on rows "scout" "2026-08-31")))
      (is (:ok? (p/allowance-verdict lim rows "scout" "2026-08-31" 1)) "3 件目までは通る")
      (is (= :allowance/daily-exhausted
             (:reason (p/allowance-verdict lim (conj rows (row "scout" "2026-08-31T03:00:00Z" 1))
                                           "scout" "2026-08-31" 1)))
          "4 件目は日次上限に当たる")
      (testing "settled だけを数えていたら 4 件目が通ってしまう"
        (let [settled-only (mapv #(assoc % :phase :settled) rows)]
          (is (= 0 (p/spent-on settled-only "scout" "2026-08-31"))))))))

(deftest yesterdays-spend-is-not-todays
  (let [rows [(row "scout" "2026-08-30T23:59:59Z" 3)]]
    (is (= 0 (p/spent-on rows "scout" "2026-08-31")))
    (is (:ok? (p/allowance-verdict {:per-call 1 :per-day 3} rows "scout" "2026-08-31" 1)))))

(deftest another-bots-spend-is-not-this-ones
  (let [rows [(row "other" "2026-08-31T01:00:00Z" 3)]]
    (is (= 0 (p/spent-on rows "scout" "2026-08-31")))))

;; ── which rail, and in whose units ──────────────────────────────────────────

(deftest the-policy-only-admits-the-credits-rail
  (let [pol (p/pass-policy (pass) rails)]
    (testing "USDC の offer しか無い challenge では支払わない。
              理由は『高い』ではなく『そのレールは許されていない』"
      (is (= :buyer/scheme-not-allowed
             (:refuse (buyer/plan {:accepts [usdc-offer]} pol)))))
    (testing "両方来たら credits を取る —— 数の大小ではなく policy の順序で"
      (let [r (buyer/plan {:accepts [usdc-offer credits-offer]} pol)]
        (is (buyer/payable? r))
        (is (= ["murakumo" "CREDITS"] (buyer/rail (:pay r))))))
    (testing "cap は rail の単位で置かれる —— 1 credit の上限を
              10000 micro-USDC の数字で代用しない"
      (is (= {["murakumo" "CREDITS"] 1} (:caps pol))))))

(deftest a-seller-the-pass-does-not-name-is-not-payable
  (let [pol (p/pass-policy (pass :sellers ["murakumo"]) rails)]
    (is (= :buyer/payee-not-allowed
           (:refuse (buyer/plan {:accepts [(assoc credits-offer :payTo "kotobase")]} pol)))
        "grant が kotobase を含まなければ、名簿に書いてあっても払えない")))

;; ── the CACAO names one payment ─────────────────────────────────────────────

(deftest the-authorization-names-this-transfer-and-no-other
  (testing "scope の無い CACAO は残高全体に対する権限になる。
            誰にいくら払うかを署名対象の中に書く"
    (let [c (p/transfer-cacao "test-bot" {:to "murakumo" :amount 1})]
      (is (string? (:cacao-b64 c)))
      (is (clojure.string/starts-with? (:iss c) "did:key:"))
      (is (clojure.string/includes? (:siwe c) "murakumo:transfer?to=murakumo&credits=1"))
      (testing "iss は seed から導出される —— 呼び出し側が名乗るのではない"
        (is (= (:iss c) (p/account "test-bot"))))
      (testing "別の bot は別の口座になる"
        (is (not= (p/account "test-bot") (p/account "test-bot-2")))))))

(deftest an-envelope-is-only-built-for-the-rail-it-belongs-to
  (let [pol (p/pass-policy (pass) rails)
        chosen (:pay (buyer/plan {:accepts [credits-offer]} pol))
        payment (buyer/credits-payment chosen {:payer "did:key:zBot" :cacao "eyJ"})]
    (is (= "credits" (:scheme payment)))
    (is (= {:payer "did:key:zBot" :amount 1 :to "murakumo" :cacao "eyJ"} (:payload payment)))))

;; ── a response that is not a challenge is not a payment problem ─────────────

(deftest a-200-is-not-something-to-pay-for
  (is (= :buyer/not-a-challenge
         (:refuse (p/plan-for (pass) registry "scout" {:status 200 :body {:ok true}}))))
  (testing "402 でない失敗を『払えば直る』と読まない"
    (is (= :buyer/not-a-challenge
           (:refuse (p/plan-for (pass) registry "scout" {:status 500 :body "boom"}))))))

(defmethod t/report [::t/default :end-run-tests] [m]
  (when-not (t/successful? m) (js/process.exit 1)))

(t/run-tests 'x402-bot-pay-test)
