(ns spotwork.governor-test
  "governor の負テストは **理由の literal を pin する**。「拒否された」だけを
  assert すると、別の原因で落ちた実行を「その規則が discriminate した」として
  数えてしまう（CLAUDE.md が 2026-08-22 の 4 件で記録した形）。

  各テストは `lawful-offer` を **1 箇所だけ**壊して、報告された rule id が
  壊した箇所と一致することを見る。"
  (:require [clojure.test :refer [deftest is testing]]
            [spotwork.facts :as facts]
            [spotwork.fixtures :as fx]
            [spotwork.governor :as gov]
            [spotwork.time :as t]
            [clojure.set]))

(defn- review [offer & [{:keys [cohort operator]}]]
  (gov/review {:offer offer
               :cohort (or cohort fx/cohort-a)
               :operator (or operator fx/operator)
               :proposal {:offer/id (:offer/id offer)}}))

(defn- finding [r rule-id]
  (first (filter #(= rule-id (:rule/id %)) (:findings r))))

(defn- status-of [r rule-id] (:status (finding r rule-id)))

;; ---------------------------------------------------------------- 床

(deftest catalog-is-well-formed
  (is (= :ok (first (facts/assert-catalog)))
      "全 block 規則が条文の根拠を持ち、id が自分自身と一致する"))

(deftest every-declared-rule-is-actually-checked
  (testing "カタログに載っている規則と、governor が走らせる検査が 1 対 1"
    (is (= [:ok (count (facts/rule-ids :jp))] (gov/assert-coverage :jp))
        "片方だけ増えると『規則は在るが誰も見ていない』が緑のまま成立する")))

(deftest basis-urls-are-counted-not-guessed
  (testing "URL は数えられる形で未記録。オペレータが入れると :recorded に変わる"
    (is (pos? (facts/unrecorded-basis-count :jp))
        "記憶から書いた URL を根拠として載せていない")
    (let [filled (facts/with-basis-urls
                   (facts/rules :jp)
                   {["労働基準法" "第三十四条"] "https://example.invalid/34"})
          statuses (->> (vals filled) (mapcat :rule/basis)
                        (filter #(= "第三十四条" (:basis/article %)))
                        (map :basis/url-status) set)]
      (is (= #{:recorded} statuses) "渡した 1 条だけが :recorded になる"))))

;; ---------------------------------------------------------------- 正の対照

(deftest lawful-offer-passes
  (let [r (review fx/lawful-offer)]
    (is (= :pass (:verdict r)) (str "why=" (gov/why-not-pass r)))
    (is (zero? (:not-measured (:counts r))))
    (is (zero? (:violated (:counts r))))))

;; ---------------------------------------------------------------- 未測定

(deftest missing-minimum-wage-holds-never-passes
  (testing "額はオペレータ維持データ。渡されないなら :not-measured であって :pass ではない"
    (let [r (review fx/lawful-offer {:operator {}})]
      (is (= :hold (:verdict r)))
      (is (= :not-measured (status-of r :wage/below-minimum)))
      (is (= :operator/minimum-wage-not-supplied
             (get-in (finding r :wage/below-minimum) [:detail :why]))))))

(deftest undeclared-break-holds
  (let [r (review (dissoc fx/lawful-offer :offer/break-minutes))]
    (is (= :hold (:verdict r)))
    (is (= :not-measured (status-of r :break/insufficient)))))

(deftest unreadable-hours-hold
  (testing "start == end は 0 分とも 24 時間とも決められない"
    (let [r (review (assoc fx/lawful-offer :offer/end "10:00"))]
      (is (= :hold (:verdict r)))
      (is (= :offer/hours-unreadable
             (get-in (finding r :break/insufficient) [:detail :why]))))))

;; ---------------------------------------------------------------- 違反（理由を pin）

(deftest wage-below-regional-floor-blocks
  (let [r (review (assoc fx/lawful-offer :offer/hourly-wage 1000))]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :wage/below-minimum)))
    (is (= {:wage 1000 :floor 1163 :region "JP-13"}
           (:detail (finding r :wage/below-minimum))))))

(deftest insufficient-break-blocks
  (testing "労働時間 8 時間超には 60 分の休憩が要る（労基法 34 条）"
    (let [r (review (assoc fx/lawful-offer :offer/end "20:00" :offer/break-minutes 45))]
      (is (= :block (:verdict r)))
      (is (= :violated (status-of r :break/insufficient)))
      (is (= 60 (get-in (finding r :break/insufficient) [:detail :required]))))))

(deftest over-eight-hours-without-36-agreement-blocks
  (let [r (review (assoc fx/lawful-offer :offer/end "21:00" :offer/break-minutes 60
                         :offer/agreement-36? false))]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :hours/over-daily-cap-without-36)))))

(deftest overtime-without-premium-blocks
  (let [r (review (assoc fx/lawful-offer :offer/end "21:00" :offer/break-minutes 60
                         :offer/overtime-premium-rate 1.0))]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :hours/overtime-premium-missing)))))

(deftest night-work-without-premium-blocks
  (let [r (review (assoc fx/lawful-offer :offer/start "18:00" :offer/end "23:30"
                         :offer/break-minutes 0 :offer/night-premium-rate 1.0))]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :hours/night-premium-missing)))
    (is (= 90 (get-in (finding r :hours/night-premium-missing) [:detail :night-minutes]))
        "22:00–23:30 の 90 分")))

(deftest minor-on-night-shift-blocks
  (let [r (review (assoc fx/lawful-offer :offer/start "18:00" :offer/end "23:30"
                         :offer/break-minutes 0 :offer/min-age 17))]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :minor/night-work)))))

(deftest under-minimum-age-blocks
  (let [r (review (assoc fx/lawful-offer :offer/min-age 14))]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :minor/under-minimum-age)))))

(deftest jobseeker-fee-blocks
  (testing "求職者からの手数料徴収は職安法 32 条の 3 の原則禁止"
    (let [r (review (assoc-in fx/lawful-offer [:offer/fee :fee/payer] :jobseeker))]
      (is (= :block (:verdict r)))
      (is (= :violated (status-of r :fee/charged-to-jobseeker))))))

(deftest wage-deduction-blocks
  (let [r (review (assoc fx/lawful-offer :offer/wage-deductions
                         [{:kind :cancellation-fee :amount 3000}]))]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :wage/deduction-from-pay)))))

(deftest predetermined-penalty-blocks
  (let [r (review (assoc fx/lawful-offer :offer/penalty-predetermined? true))]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :wage/penalty-predetermined)))))

(deftest unverified-work-authorization-blocks
  (let [r (review fx/lawful-offer
                  {:cohort (assoc fx/cohort-a :cohort/work-authorization-verified? false)})]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :authorization/unverified)))))

(deftest cohort-below-k-blocks
  (let [r (review fx/lawful-offer {:cohort (assoc fx/cohort-a :cohort/size 3)})]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :cohort/below-k-anonymity)))))

(deftest short-cohort-holds-not-blocks
  (testing "人数不足は法違反ではないので :hold（:block に混ぜない）"
    (let [r (review (assoc fx/lawful-offer :offer/headcount 30)
                    {:cohort (assoc fx/cohort-a :cohort/size 10)})]
      (is (= :hold (:verdict r)))
      (is (= :violated (status-of r :supply/insufficient-cohort)))
      (is (= :hold (:severity (finding r :supply/insufficient-cohort)))))))

(deftest pii-in-proposal-blocks
  (let [r (gov/review {:offer fx/lawful-offer
                       :cohort fx/cohort-a
                       :operator fx/operator
                       :proposal {:worker/name "山田" :worker/email "a@example.invalid"}})]
    (is (= :block (:verdict r)))
    (is (= :violated (status-of r :privacy/pii-in-proposal)))
    (is (= #{:key-name :email-shaped}
           (set (map :why (get-in (finding r :privacy/pii-in-proposal) [:detail :hits]))))
        "key 名と値の形の両方で拾う")))

(deftest unknown-jurisdiction-blocks
  (testing "知らない法域の提案は通さない（規則 0 件を『違反なし』にしない）"
    (let [r (review (assoc fx/lawful-offer :offer/jurisdiction :zz))]
      (is (= :block (:verdict r)))
      (is (= :jurisdiction/unknown (:rule/id (first (:findings r))))))))

;; ---------------------------------------------------------------- 作動

(deftest confirmation-is-never-automatic-in-any-phase
  ;; ⚠ 集合そのものを pin する。`never-auto` が空になっても下の doseq は
  ;; 0 回まわって**空振りで通る**（このリポジトリが繰り返し記録している
  ;; 「走査対象が空でも合格と同じ値を返す」形。実際に変異テストで踏んだ）。
  (is (= #{:actuation/confirm-match :actuation/place-worker} gov/never-auto)
      "確定系がこの集合から外れていない")
  (doseq [phase (keys gov/phase-table)
          act gov/never-auto]
    (is (false? (gov/auto-allowed? phase act))
        (str phase " / " act " は自動化されてはならない")))
  (testing "2 つの層が独立していること自体を見る"
    (let [declared (into #{} (mapcat :auto) (vals gov/phase-table))]
      (is (empty? (clojure.set/intersection declared gov/never-auto))
          "phase 表そのものにも確定系が入っていない（層 1）")
      (is (every? #(false? (gov/auto-allowed? :phase/operating %)) gov/never-auto)
          "表に入れられたとしても never-auto が止める（層 2）"))))

(deftest advisory-actions-are-allowed-where-declared
  (is (true? (gov/auto-allowed? :phase/advisory :actuation/draft-proposal)))
  (is (false? (gov/auto-allowed? :phase/shadow :actuation/draft-proposal))))

;; ---------------------------------------------------------------- 時刻

(deftest time-helpers
  (is (= 600 (t/parse-hhmm "10:00")))
  (is (nil? (t/parse-hhmm "24:00")))
  (is (nil? (t/parse-hhmm "10abc")) "js/parseInt の緩さを通さない")
  (is (= [[1320 1440] [0 300]] (t/segments "22:00" "05:00")) "日跨ぎを 2 区間に割る")
  (is (nil? (t/segments "10:00" "10:00")) "0 分とも 24 時間とも決めない")
  (is (= 420 (t/span-minutes "22:00" "05:00")))
  (is (= 480 (t/working-minutes "10:00" "19:00" 60)))
  (is (nil? (t/working-minutes "10:00" "19:00" nil)) "未申告の休憩を 0 と読まない"))

(deftest unreadable-hours-and-undeclared-break-are-different-reasons
  (testing "差し戻す先が違うので、同じ理由名に畳まない"
    (let [no-hours (review (assoc fx/lawful-offer :offer/end "10:00"))
          no-break (review (dissoc fx/lawful-offer :offer/break-minutes))]
      (is (= :offer/hours-unreadable
             (get-in (finding no-hours :hours/over-daily-cap-without-36) [:detail :why])))
      (is (= :offer/break-undeclared
             (get-in (finding no-break :hours/over-daily-cap-without-36) [:detail :why]))))))

(deftest break-longer-than-the-shift-is-not-a-pass
  (testing "労働時間が負になる矛盾した申告を『休憩は足りている』として通さない"
    (let [r (review (assoc fx/lawful-offer :offer/break-minutes 600))]  ; 拘束 540 分
      (is (= :hold (:verdict r)))
      (is (= :not-measured (status-of r :break/insufficient)))
      (is (= :offer/break-exceeds-span
             (get-in (finding r :break/insufficient) [:detail :why]))))))
