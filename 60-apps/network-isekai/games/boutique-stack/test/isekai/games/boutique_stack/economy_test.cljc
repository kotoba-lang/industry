(ns isekai.games.boutique-stack.economy-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.boutique-stack.catalog :as catalog]
            [isekai.games.boutique-stack.economy :as econ]
            [isekai.games.boutique-stack.support :as sup]))

(deftest paying-more-than-you-have-fails-rather-than-going-negative
  (let [s {:money 100}]
    (is (= 40 (:money (econ/pay s 60))))
    (is (nil? (econ/pay s 101)) "a refusal the caller cannot ignore")
    (is (= 0 (:money (econ/pay s 100))) "exact change is affordable")))

(deftest the-shoe-department-is-the-end-of-a-chain
  (testing "服屋のアンロックで靴も — shoes are the far end of the clothing
            store, not a shop a rich player can buy first"
    (let [[w s] (sup/start)
          rich (assoc s :money 100000)]
      (is (not (econ/can-unlock? rich w :dept-shoes))
          "affordable, but outerwear is not open yet")
      (is (econ/can-unlock? rich w :dept-outerwear))
      (let [after (econ/unlock rich w :dept-outerwear)]
        (is (contains? (:unlocked after) :dept-outerwear))
        (is (econ/can-unlock? after w :dept-shoes) "now the chain permits it")))))

(deftest the-catalog-agrees-with-the-zone-chain
  (is (= #{} (:dept/requires (catalog/dept-by-id :tops))))
  (is (= #{:tops} (:dept/requires (catalog/dept-by-id :outerwear))))
  (is (= #{:tops :outerwear} (:dept/requires (catalog/dept-by-id :shoes))))
  (is (= [:tops :outerwear :shoes] (mapv :dept/id (catalog/unlock-chain)))))

(deftest an-unaffordable-unlock-changes-nothing
  (let [[w s] (sup/start)
        poor (assoc s :money 10)]
    (is (identical? poor (econ/unlock poor w :dept-outerwear)))))

(deftest unlockable-lists-only-what-the-chain-currently-permits
  (let [[w s] (sup/start)]
    (is (= [:dept-outerwear] (mapv :zone/id (econ/unlockable s w)))
        "shoes are not offered until outerwear is open")
    (let [after (econ/unlock (assoc s :money 1000) w :dept-outerwear)]
      (is (= [:dept-shoes] (mapv :zone/id (econ/unlockable after w)))))))

(deftest upgrades-move-their-value-and-then-cap
  (is (= 4 (econ/upgrade-value :carry 1)))
  (is (= 6 (econ/upgrade-value :carry 2)))
  (is (= 14 (econ/upgrade-value :carry 6)))
  (is (= 14 (econ/upgrade-value :carry 99)) "clamped at the cap, not extrapolated")
  (testing "the till upgrade counts down, because fewer ticks is better"
    (is (= 24 (econ/upgrade-value :service 1)))
    (is (= 12 (econ/upgrade-value :service 5)))))

(deftest upgrade-costs-grow-and-then-stop
  (is (= 150 (econ/upgrade-cost :carry 1)))
  (is (= 300 (econ/upgrade-cost :carry 2)))
  (is (= 2400 (econ/upgrade-cost :carry 5)) "150 * 2^4")
  (is (nil? (econ/upgrade-cost :carry 6)) "maxed")
  (is (econ/maxed? :carry 6)))

(deftest buying-an-upgrade-you-cannot-afford-changes-nothing
  (let [s (assoc (second (sup/start)) :money 10)]
    (is (identical? s (econ/buy-upgrade s :carry)))))

(deftest hiring-respects-the-cap
  (let [[w s] (sup/start)
        rich (assoc s :money 100000)
        hired (reduce (fn [st _] (econ/hire st w :restocker)) rich (range 10))]
    (is (= 3 (count (:staff hired))) "three stockers is the cap")
    (is (= (- 100000 (* 3 500)) (:money hired)) "and nobody paid for a fourth")))

(deftest new-staff-start-at-the-door
  (testing "spawning them at their post would let a player clear a queue
            instantly by buying a cashier mid-rush"
    (let [[w s] (sup/start)
          hired (econ/hire (assoc s :money 5000) w :cashier)]
      (is (= [200 700] (:pos (first (:staff hired))))))))
