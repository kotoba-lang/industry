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
  (is (:ready? (industry/readiness "6310" #{:identity :forms :dmn :bpmn :audit-ledger}))))
