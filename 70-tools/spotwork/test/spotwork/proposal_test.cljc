(ns spotwork.proposal-test
  (:require [clojure.test :refer [deftest is testing]]
            [spotwork.fixtures :as fx]
            [spotwork.proposal :as prop]))

(def ^:private at "2026-08-29T00:00:00.000Z")

(defn- build [offer cohorts]
  (prop/build offer cohorts {:at at :operator fx/operator}))

(deftest lawful-offer-produces-a-recordable-pass
  (let [e (build fx/lawful-offer [fx/cohort-a fx/cohort-b])
        r (prop/validate e)]
    (is (prop/ok? r) (str "validate=" (pr-str (take 2 r))))
    (is (= :pass (:proposal/verdict e)))
    (is (= "ch-jp13-5246-day" (:proposal/cohort-id e)))
    (is (= :actuation/draft-proposal (:proposal/actuation e)))
    (is (empty? (:proposal/findings e)) ":pass の行に未解消の finding は残らない")))

(deftest blocked-offers-are-still-recorded
  (testing "止めた提案こそ台帳に残す（何が止まっているか見えなければ規則は説明できない）"
    (let [e (build (assoc fx/lawful-offer :offer/hourly-wage 900) [fx/cohort-a])]
      (is (prop/ok? (prop/validate e)))
      (is (= :block (:proposal/verdict e)))
      (is (= #{:wage/below-minimum} (set (map :rule/id (:proposal/findings e)))))
      (is (some? (:proposal/why e))))))

(deftest findings-carry-their-statutory-basis
  (let [e (build (assoc fx/lawful-offer :offer/hourly-wage 900) [fx/cohort-a])
        b (first (:basis (first (:proposal/findings e))))]
    (is (= "最低賃金法" (:basis/law b)))
    (is (= "第四条" (:basis/article b)))
    (is (= :not-recorded (:basis/url-status b)) "URL は推測で埋めていない")))

(deftest validate-rejects-with-a-literal-reason
  (testing "拒否は真偽値ではなく理由の literal で返る"
    (let [e (build fx/lawful-offer [fx/cohort-a])]
      (is (= :entry/missing-keys (second (prop/validate (dissoc e :proposal/verdict)))))
      (is (= :entry/schema-mismatch
             (second (prop/validate (assoc e :proposal/schema "spotwork.proposal/v0")))))
      (is (= :entry/unknown-verdict
             (second (prop/validate (assoc e :proposal/verdict :approved)))))
      (is (= :entry/actuation-never-auto
             (second (prop/validate (assoc e :proposal/actuation :actuation/place-worker)))))
      (is (= :entry/pii-present
             (second (prop/validate (assoc e :worker/email "a@example.invalid"))))))))

(deftest validate-rejects-a-pass-that-still-has-unmeasured
  (testing "governor が畳んでいないかを台帳側でも突き合わせる"
    (let [e (-> (build fx/lawful-offer [fx/cohort-a])
                (assoc-in [:proposal/verdict-counts :not-measured] 1))]
      (is (= :entry/pass-with-unmeasured (second (prop/validate e)))))))

(deftest ok?-is-not-a-truthy-check
  (testing "[:error …] は空でないベクタなので、そのまま truthy 判定すると通ってしまう"
    (let [err (prop/validate {})]
      (is (seq err) "拒否そのものは truthy")
      (is (false? (prop/ok? err)) "ok? だけが可否を答える"))))

(deftest missing-operator-data-holds
  (let [e (prop/build fx/lawful-offer [fx/cohort-a] {:at at})]
    (is (= :hold (:proposal/verdict e)))
    (is (prop/ok? (prop/validate e)))))

(deftest summary-counts-unreadable-lines
  (let [pass (build fx/lawful-offer [fx/cohort-a])
        blocked (build (assoc fx/lawful-offer :offer/hourly-wage 900) [fx/cohort-a])
        entries [pass blocked ::unreadable]]
    (is (= {:pass 1 :block 1 :unreadable 1} (prop/counts-by-verdict entries))
        "読めなかった行を落とすと台帳が実際より綺麗に見える")
    (is (= "proposals=3 pass=1 block=1 unreadable=1" (prop/summary-line entries)))))

(deftest ids-are-stable-for-a-given-timestamp
  (is (= (:proposal/id (build fx/lawful-offer [fx/cohort-a]))
         (:proposal/id (build fx/lawful-offer [fx/cohort-a])))))

(deftest fingerprint-detects-content-change-under-a-stable-id
  (let [a (build fx/lawful-offer [fx/cohort-a])
        same (build (assoc fx/lawful-offer :offer/employer-did "did:web:example.invalid")
                    [fx/cohort-a])
        changed (build (assoc fx/lawful-offer :offer/hourly-wage 1400) [fx/cohort-a])]
    (is (= (:proposal/offer-fingerprint a) (:proposal/offer-fingerprint same))
        "内容が同じなら key の順序に依らず同じ指紋")
    (is (not= (:proposal/offer-fingerprint a) (:proposal/offer-fingerprint changed))
        "id が同じまま賃金が変われば再審査が要る")))

(deftest fingerprint-ignores-the-id-itself
  (is (= (:proposal/offer-fingerprint (build fx/lawful-offer [fx/cohort-a]))
         (:proposal/offer-fingerprint
          (build (assoc fx/lawful-offer :offer/id "of-renamed") [fx/cohort-a])))
      "id は内容ではない"))

(deftest fingerprint-sees-non-ascii-changes
  (testing "コードユニットを 1 バイトに切り詰めると あ(U+3042) と B(U+0042) が衝突する"
    (let [a (build (assoc fx/lawful-offer :offer/employer-label "あ") [fx/cohort-a])
          b (build (assoc fx/lawful-offer :offer/employer-label "B") [fx/cohort-a])]
      (is (not= (:proposal/offer-fingerprint a) (:proposal/offer-fingerprint b))
          "指紋が動かないことは tick にとって『再審査は要らない』と同義")))
  (testing "日本語だけが違う 2 件も区別する"
    (let [a (build (assoc fx/lawful-offer :offer/employer-label "都内カフェ") [fx/cohort-a])
          b (build (assoc fx/lawful-offer :offer/employer-label "都内バー") [fx/cohort-a])]
      (is (not= (:proposal/offer-fingerprint a) (:proposal/offer-fingerprint b))))))
