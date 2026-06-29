(ns ongakuka.compose-test
  (:require [clojure.test :refer [deftest is testing]]
            [ongakuka.catalog :as catalog]
            [ongakuka.compose :as compose]
            [ongakuka.policy :as policy]))

(deftest catalog-loads
  (let [cat (catalog/load-catalog)]
    (is (= 2 (:ongakuka.catalog/version cat)))
    (is (= 30 (count (catalog/assets cat))))))

(deftest compose-selects-render-only-plan
  (let [plan (compose/compose {:channel-id "cyber"
                               :duration/sec 120
                               :mood :calm-tech})]
    (is (= :plan/background-music (:ongakuka/plan plan)))
    (is (:asset/path plan))
    (is (:credit/text plan))
    (is (true? (:policy/render-only? plan)))
    (is (false? (:policy/raw-public-access? plan)))))

(deftest policy-rejects-raw-file-exposure
  (let [asset (catalog/by-id "dova-12420-10deg")]
    (is (seq (policy/policy-errors asset {:channel-id "cyber"
                                          :usage-context :youtube-background
                                          :expose-raw? true})))))
