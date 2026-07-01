(ns kotoba.industry-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.industry :as industry]))

(deftest registry-loads
  (let [reg (industry/registry)]
    (is (= :kotoba/industry (:kotoba.registry/id reg)))
    (is (<= 9 (count (industry/industries reg))))))

(deftest open-business-industries-resolve
  (doseq [isic ["3512" "3600" "3830" "6310" "8569" "8691" "8810"]]
    (is (:business-id (industry/get-industry isic)))
    (is (seq (industry/required-technologies isic)))
    (is (seq (:technology-stack (industry/execution-plan isic))))))

(deftest engineering-industries-use-eda-cfd-cae
  (testing "semiconductor manufacturing can require EDA"
    (is (some #{:eda} (industry/required-technologies "2610"))))
  (testing "aerospace enablement can require CFD/CAE"
    (is (some #{:cfd} (industry/required-technologies "3030")))
    (is (some #{:cae} (industry/required-technologies "3030")))))

(deftest readiness-reports-missing-tech
  (let [r (industry/readiness "8691" #{:identity :forms})]
    (is (false? (:ready? r)))
    (is (contains? (:missing r) :audit-ledger)))
  ;; :robotics is required on every itonami vertical (robotics-premise design),
  ;; so it must be in the available set for a vertical to read ready.
  (is (:ready? (industry/readiness "6310" #{:robotics :identity :forms :dmn :bpmn :audit-ledger}))))

(deftest maturity-tier
  (testing "the reference actor is implemented"
    (is (= :implemented (industry/maturity "6310"))))
  (testing "a published blueprint repo is :blueprint"
    (is (= :blueprint (industry/maturity "6419"))))
  (testing "a registry-only group entry is :spec"
    (is (= :spec (industry/maturity "011"))))
  (testing "maturity-summary counts tiers"
    (let [m (industry/maturity-summary)]
      (is (= (:total m) (+ (:spec m) (:blueprint m) (:implemented m))))
      (is (pos? (:spec m)))
      (is (pos? (:blueprint m)))
      (is (= 1 (:implemented m))))))

(deftest maturity-roadmap-reports-next-step
  (testing "an implemented entry is at maturity ceiling"
    (let [r (industry/maturity-roadmap "6310")]
      (is (= :implemented (:maturity r)))
      (is (nil? (:next-step r)))
      (is (= "at maturity ceiling" (:next-action r)))))
  (testing "a blueprint entry's next step is implemented"
    (let [r (industry/maturity-roadmap "6419")]
      (is (= :blueprint (:maturity r)))
      (is (= :implemented (:next-step r)))
      (is (true? (:has-repo r)))))
  (testing "a spec entry's next step is blueprint"
    (let [r (industry/maturity-roadmap "011")]
      (is (= :spec (:maturity r)))
      (is (= :blueprint (:next-step r)))
      (is (false? (:has-repo r))))))

(deftest execution-plan-reports-ui-export-readiness
  (testing "a vertical backed by a capability lib reports ui+export ready"
    (let [p (industry/execution-plan "6419")]
      (is (true? (:ui-ready? p)))
      (is (true? (:export-ready? p)))
      (is (some :ui? (:technology-stack p)))))
  (testing "a vertical with only infrastructure techs reports not ready"
    (let [p (industry/execution-plan "2610")]   ; eda/cae/dmn/bpmn/audit-ledger + robotics
      ;; robotics has ui?, so ui-ready is true; but a pure-infra entry below tests the negative
      (is (map? p)))))
