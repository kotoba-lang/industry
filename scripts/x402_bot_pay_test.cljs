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

(deftest a-bot-nobody-granted-anything-cannot-pay
  (testing "名簿に無い bot の policy は nil。nil は buyer 側で
            :buyer/no-policy になる —— 沈黙を自由と読まない"
    (is (nil? (p/policy-for registry "stranger")))
    (is (= :buyer/no-policy
           (:refuse (buyer/plan {:accepts [credits-offer]} (p/policy-for registry "stranger")))))
    (is (= :allowance/unlisted
           (:reason (p/allowance-verdict registry [] "stranger" "2026-08-31" 1))))))

(deftest an-unreadable-registry-is-not-an-empty-one
  (testing "読めなかった policy ファイルは『何も許さない policy』と
            同じ顔をしてはならない —— 同じ判定で別のバグ"
    (is (= :allowance/unreadable
           (:reason (p/allowance-verdict nil [] "scout" "2026-08-31" 1))))))

;; ── the caps ────────────────────────────────────────────────────────────────

(deftest a-per-call-cap-is-a-cap
  (is (:ok? (p/allowance-verdict registry [] "scout" "2026-08-31" 1)))
  (is (= :allowance/over-per-call
         (:reason (p/allowance-verdict registry [] "scout" "2026-08-31" 2)))))

(deftest the-day-limit-counts-reservations-not-settlements
  (testing "mint と seller の答えの間で死んだ支払いは、課金されている
            かもしれない。確定した分だけ数えると、crash のたびに
            その日の上限が上がる"
    (let [rows [(row "scout" "2026-08-31T01:00:00Z" 1)
                (row "scout" "2026-08-31T02:00:00Z" 1)]]
      (is (= 2 (p/spent-on rows "scout" "2026-08-31")))
      (is (:ok? (p/allowance-verdict registry rows "scout" "2026-08-31" 1))
          "3 件目までは通る")
      (is (= :allowance/daily-exhausted
             (:reason (p/allowance-verdict registry (conj rows (row "scout" "2026-08-31T03:00:00Z" 1))
                                           "scout" "2026-08-31" 1)))
          "4 件目は日次上限に当たる")
      (testing "settled だけを数えていたら 4 件目が通ってしまう"
        (let [settled-only (mapv #(assoc % :phase :settled) rows)]
          (is (= 0 (p/spent-on settled-only "scout" "2026-08-31"))
              "この test が守っているのは『reserved を数える』という選択そのもの"))))))

(deftest yesterdays-spend-is-not-todays
  (let [rows [(row "scout" "2026-08-30T23:59:59Z" 3)]]
    (is (= 0 (p/spent-on rows "scout" "2026-08-31")))
    (is (:ok? (p/allowance-verdict registry rows "scout" "2026-08-31" 1)))))

(deftest another-bots-spend-is-not-this-ones
  (let [rows [(row "other" "2026-08-31T01:00:00Z" 3)]]
    (is (= 0 (p/spent-on rows "scout" "2026-08-31")))))

;; ── which rail, and in whose units ──────────────────────────────────────────

(deftest the-policy-only-admits-the-credits-rail
  (let [pol (p/policy-for registry "scout")]
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

(deftest a-seller-outside-the-registry-is-not-payable
  (let [pol (p/policy-for registry "scout")]
    (is (= :buyer/payee-not-allowed
           (:refuse (buyer/plan {:accepts [(assoc credits-offer :payTo "stranger")]} pol))))))

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
  (let [pol (p/policy-for registry "scout")
        chosen (:pay (buyer/plan {:accepts [credits-offer]} pol))
        payment (buyer/credits-payment chosen {:payer "did:key:zBot" :cacao "eyJ"})]
    (is (= "credits" (:scheme payment)))
    (is (= {:payer "did:key:zBot" :amount 1 :to "murakumo" :cacao "eyJ"} (:payload payment)))))

;; ── a response that is not a challenge is not a payment problem ─────────────

(deftest a-200-is-not-something-to-pay-for
  (is (= :buyer/not-a-challenge
         (:refuse (p/plan-for registry "scout" {:status 200 :body {:ok true}}))))
  (testing "402 でない失敗を『払えば直る』と読まない"
    (is (= :buyer/not-a-challenge
           (:refuse (p/plan-for registry "scout" {:status 500 :body "boom"}))))))

(defmethod t/report [::t/default :end-run-tests] [m]
  (when-not (t/successful? m) (js/process.exit 1)))

(t/run-tests 'x402-bot-pay-test)
