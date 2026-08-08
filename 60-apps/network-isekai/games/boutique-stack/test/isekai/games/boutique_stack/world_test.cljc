(ns isekai.games.boutique-stack.world-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.boutique-stack.support :as sup]
            [isekai.games.boutique-stack.world :as world]))

(deftest step-toward-arrives-and-then-stops
  (is (= [10 0] (world/step-toward [0 0] [100 0] 10)))
  (is (= [100 0] (world/step-toward [95 0] [100 0] 10)) "snaps rather than overshooting")
  (is (= [100 0] (world/step-toward [100 0] [100 0] 10)) "and stays put once there"))

(deftest step-toward-always-makes-progress
  (testing "the naive per-axis quotient stalls one unit short forever when it
            truncates to zero on both axes — that is a hang, not a slowdown"
    (loop [pos [0 0], n 0]
      (cond
        (= pos [3 3]) (is (< n 20) "arrived")
        (> n 100) (is false (str "never arrived, stuck at " pos))
        :else (recur (world/step-toward pos [3 3] 1) (inc n))))))

(deftest step-toward-handles-every-direction
  (doseq [target [[50 50] [-50 50] [50 -50] [-50 -50] [0 -70] [70 0]]]
    (loop [pos [0 0], n 0]
      (cond
        (= pos target) (is true)
        (> n 500) (is false (str "never reached " target))
        :else (recur (world/step-toward pos target 7) (inc n))))))

(deftest distance-is-squared-and-integral
  (is (= 25 (world/dist2 [0 0] [3 4])))
  (is (world/near? [0 0] [3 4] 5))
  (is (not (world/near? [0 0] [3 4] 4))))

(deftest a-locked-zone-hides-its-fixtures-entirely
  (testing "not merely invisible: a locked department must not be restockable,
            shoppable, or countable"
    (let [[w s] (sup/start)]
      (is (= [:tops] (world/open-departments w (:unlocked s))))
      (is (= [:tee] (world/sellable-items w (:unlocked s))))
      (is (= 1 (count (world/open-fixtures w (:unlocked s) :rack))))
      (let [opened (conj (:unlocked s) :dept-outerwear)]
        (is (= [:tops :outerwear] (world/open-departments w opened)))
        (is (= [:jacket :tee] (world/sellable-items w opened)))))))

(deftest departments-come-back-in-progression-order
  (let [[w s] (sup/start)
        all (into (:unlocked s) [:dept-shoes :dept-outerwear])]
    (is (= [:tops :outerwear :shoes] (world/open-departments w all))
        "shoes last, however the set happens to iterate")))

(deftest building-a-shop-rejects-a-fixture-in-an-unknown-zone
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (world/build (update sup/tiny :shop/fixtures conj
                                    {:fixture/id :orphan :fixture/kind :rack
                                     :fixture/zone :nowhere :fixture/pos [0 0]
                                     :fixture/item :tee :fixture/capacity 1})))))

(deftest building-a-shop-rejects-an-item-that-is-not-in-the-catalog
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (world/build (update sup/tiny :shop/fixtures conj
                                    {:fixture/id :odd :fixture/kind :rack
                                     :fixture/zone :front :fixture/pos [0 0]
                                     :fixture/item :sombrero :fixture/capacity 1})))))

(deftest the-test-shop-validates-clean
  (is (empty? (world/validate sup/tiny))))

(deftest validation-catches-a-rack-no-crate-can-fill
  (testing "the shop runs, sells nothing from that rack, and reports no error"
    (let [broken (update sup/tiny :shop/fixtures conj
                         {:fixture/id :rack-boot :fixture/kind :rack
                          :fixture/zone :front :fixture/pos [500 600]
                          :fixture/item :boot :fixture/capacity 4})]
      (is (= [:rack-without-a-crate] (mapv :problem (world/validate broken)))))))

(deftest validation-catches-a-zone-requiring-a-zone-that-does-not-exist
  (let [broken (update sup/tiny :shop/zones conj
                       {:zone/id :dept-hats :zone/cost 100
                        :zone/requires #{:dept-nonexistent} :zone/entry [0 0]})]
    (is (some #{:zone-requires-unknown-zone} (map :problem (world/validate broken))))))

(deftest validation-catches-a-shop-with-no-till
  (let [broken (update sup/tiny :shop/fixtures
                       (fn [fs] (vec (remove #(= :checkout (:fixture/kind %)) fs))))]
    (is (some #{:no-checkout} (map :problem (world/validate broken))))))
