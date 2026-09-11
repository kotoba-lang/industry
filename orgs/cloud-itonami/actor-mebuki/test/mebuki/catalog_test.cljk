(ns mebuki.catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [mebuki.catalog :as cat]
            [mebuki.model :as model]))

(def catalog (cat/load-catalog))
(def global-profile (cat/load-profile :global))
(def scenario (cat/load-scenario :illustrative-medium-town))
(def role-ids (map :instrument/id (:profile/instrument-roles global-profile)))

(deftest catalog-is-well-formed
  (is (pos? (count (cat/items catalog))))
  (testing "no duplicate ids"
    (is (= (count (cat/items catalog))
           (count (set (map :id (cat/items catalog)))))))
  (testing "every item declares kind, phase, sector and both labels"
    (doseq [i (cat/items catalog)]
      (is (contains? #{:resource :task :operation} (:kind i)) (str (:id i) " kind"))
      (is (some? (:phase i)) (str (:id i) " phase"))
      (is (some? (:sector i)) (str (:id i) " sector"))
      (is (string? (:ja i)) (str (:id i) " ja"))
      (is (string? (:en i)) (str (:id i) " en"))))
  (testing "phases and sectors referenced by items are declared"
    (let [phases (set (map :phase/id (:catalog/phases catalog)))
          sectors (set (map :sector/id (:catalog/sectors catalog)))]
      (is (empty? (remove phases (map :phase (cat/items catalog)))))
      (is (empty? (remove sectors (map :sector (cat/items catalog))))))))

(deftest no-dangling-references
  (let [d (cat/dangling-references catalog role-ids)]
    (is (= [] (:needs d)) "every :needs target is a catalog item")
    (is (= [] (:gated-by d)) "every :gated-by target is a declared instrument role")))

(deftest requirement-closure-terminates-and-is-real
  (let [needed (cat/requirements catalog #{:task/mud-removal})]
    (is (contains? needed :resource/ppe-boots))
    (is (contains? needed :resource/volunteer-labour))
    (is (not (contains? needed :resource/waste-processing-plant))
        "the closure is the actual dependency set, not the whole catalog")))

(deftest ordering-is-monotone-in-phase
  (let [po (cat/phase-order catalog)
        orders (map #(get po (:phase %)) (cat/in-order catalog))]
    (is (= orders (sort orders)))))

(deftest every-claimed-model-var-exists
  ;; A catalog item that says it parameterises a model variable is making a
  ;; checkable claim. If it names something the model does not have, the link
  ;; is decoration -- and decoration that looks like wiring is worse than none.
  (let [built (model/build (:profile/parameters scenario))]
    (is (= [] (cat/unknown-model-vars catalog built)))))

(deftest coverage-is-reported-not-implied
  (let [c (cat/coverage catalog)]
    (is (pos? (:items c)))
    (is (= (:items c) (reduce + (vals (:by-kind c)))))
    (is (= (:items c) (reduce + (vals (:by-phase c)))))))
