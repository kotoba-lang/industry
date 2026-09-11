(ns spotwork.match-test
  (:require [clojure.test :refer [deftest is testing]]
            [spotwork.fixtures :as fx]
            [spotwork.match :as match]))

(deftest isco-affinity-by-digit-depth
  (is (= 1.0 (match/isco-affinity "5246" "5246")))
  (is (= 0.6 (match/isco-affinity "5246" "5241")))
  (is (= 0.3 (match/isco-affinity "5246" "5212")))
  (is (= 0.1 (match/isco-affinity "5246" "5120")))
  (is (= 0.0 (match/isco-affinity "5246" "9412")))
  (testing "欠けた入力は 0.0 ではなく nil"
    (is (nil? (match/isco-affinity nil "5246")))
    (is (nil? (match/isco-affinity "5246" "")))))

(deftest availability-coverage-is-a-fraction-of-the-shift
  (is (= 1.0 (match/availability-coverage fx/lawful-offer fx/cohort-a))
      "09:00–22:00 は 10:00–19:00 を完全に覆う")
  (testing "部分的な重なり"
    (let [offer (assoc fx/lawful-offer :offer/start "06:00" :offer/end "12:00")]
      ;; 06:00–12:00 の 360 分のうち、09:00–22:00 と重なるのは 180 分
      (is (= 0.5 (match/availability-coverage offer fx/cohort-a)))))
  (testing "申告が無ければ nil（0.0 ではない）"
    (is (nil? (match/availability-coverage fx/lawful-offer
                                           (dissoc fx/cohort-a :cohort/availability))))))

(deftest score-is-nil-when-any-factor-is-unmeasured
  (testing "欠けた因子を 0 とみなして計算を通さない"
    (let [s (match/score fx/lawful-offer fx/cohort-unmeasured)]
      (is (nil? (:score s)))
      (is (= [:reliability] (:not-measured s)))))
  (testing "揃っていれば数が出る"
    (let [s (match/score fx/lawful-offer fx/cohort-a)]
      (is (some? (:score s)))
      (is (empty? (:not-measured s)))
      (is (< 0.99 (:score s) 1.0)))))

(deftest ranking-keeps-unscored-cohorts-visible
  (let [r (match/rank fx/lawful-offer [fx/cohort-b fx/cohort-a fx/cohort-unmeasured])]
    (is (= 2 (get-in r [:counts :ranked])))
    (is (= 1 (get-in r [:counts :unscored])))
    (is (= "ch-jp13-5246-day" (:cohort/id (match/top r))))
    (is (= ["ch-jp13-5246-unknown"] (mapv :cohort/id (:unscored r)))
        "測れなかった cohort を順位表から落とさない（落とすと『候補なし』と同じ顔になる）")))

(deftest ranking-is-deterministic
  (let [cs [fx/cohort-a fx/cohort-b]
        a (match/rank fx/lawful-offer cs)
        b (match/rank fx/lawful-offer (reverse cs))]
    (is (= (mapv :cohort/id (:ranked a)) (mapv :cohort/id (:ranked b)))
        "入力順で答えが変わらない")))

(deftest empty-cohorts-give-no-top
  (let [r (match/rank fx/lawful-offer [])]
    (is (nil? (match/top r)))
    (is (= {:ranked 0 :unscored 0} (:counts r)))))
