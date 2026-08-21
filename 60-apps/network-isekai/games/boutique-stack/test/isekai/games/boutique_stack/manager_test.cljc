(ns isekai.games.boutique-stack.manager-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.boutique-stack.economy :as econ]
            [isekai.games.boutique-stack.manager :as mgr]
            [isekai.games.boutique-stack.sim :as sim]
            [isekai.games.boutique-stack.support :as sup]
            [isekai.games.boutique-stack.world :as world]))

(defn- with-losses [s empty-n queue-n]
  (-> s
      (assoc-in [:stats :walkouts-empty] empty-n)
      (assoc-in [:stats :walkouts-queue] queue-n)))

(deftest bottlenecks-are-ranked-not-collapsed
  (let [[_ s] (sup/start)]
    (is (= [] (mgr/bottlenecks (with-losses s 0 0))) "nothing to fix yet")
    (is (= [] (mgr/bottlenecks (with-losses s 2 2))) "below the threshold")
    (is (= [:shelves] (mgr/bottlenecks (with-losses s 9 1))))
    (is (= [:queue] (mgr/bottlenecks (with-losses s 1 9))))
    (is (= [:shelves :queue] (mgr/bottlenecks (with-losses s 20 5)))
        "both leak, shelves worse")
    (is (= [:queue :shelves] (mgr/bottlenecks (with-losses s 5 20))))))

(deftest an-affordable-department-outranks-everything
  (testing "new stock raises the ceiling; everything else chases one already
            there"
    (let [[w s] (sup/start)
          rich (with-losses (assoc s :money 5000) 50 50)]
      (is (= [:unlock :dept-outerwear] (mgr/decide rich w))))))

(deftest a-hire-that-fixes-a-live-leak-ignores-the-unlock-reserve
  (testing "nobody saves for a new department while customers are walking out
            of the one they already have"
    (let [[w s] (sup/start)
          ;; Outerwear already open, so the next department is shoes at 800.
          ;; 550 cannot buy that but can buy a stocker at 500.
          opened (update s :unlocked conj :dept-outerwear)
          leaking (with-losses (assoc opened :money 550) 9 0)]
      (is (= 800 (:zone/cost (first (econ/unlockable leaking w)))))
      (is (= [:hire :restocker] (mgr/decide leaking w))))))

(deftest the-manager-falls-through-to-the-secondary-leak
  (testing "this is the bug the balance runner found: acting only on the
            dominant leak hires stockers to the cap and then stops, leaving
            the cashier role permanently unsold"
    (let [[w s] (sup/start)
          capped (-> (assoc s :money 5000 :unlocked #{:front :dept-tops
                                                      :dept-outerwear :dept-shoes})
                     (assoc :staff (vec (repeat 3 {:role :restocker :pos [0 0]})))
                     (with-losses 40 10))]
      (is (= [:hire :cashier] (mgr/decide capped w))
          "shelves still lose more, but no fourth stocker can be hired"))))

(deftest upgrades-keep-something-back-for-the-next-department
  (let [[w s] (sup/start)]
    (testing "with nothing left to unlock there is nothing to reserve"
      (let [done (assoc s :money 200
                        :unlocked #{:front :dept-tops :dept-outerwear :dept-shoes})]
        (is (= [:upgrade :carry] (mgr/decide done w)) "a carry upgrade costs 150")))
    ;; Shoes cost 800, so half of it — 400 — is held back and a 150 upgrade
    ;; needs 550 in hand. Between 550 and 799 the manager upgrades *while*
    ;; saving, which is the behaviour reserving in full would forbid.
    (let [saving (update s :unlocked conj :dept-outerwear)]
      (testing "short of the reserve plus the upgrade, it saves"
        (is (nil? (mgr/decide (assoc saving :money 500) w))))
      (testing "past it, it upgrades without waiting for the department"
        (is (= [:upgrade :carry] (mgr/decide (assoc saving :money 600) w))))
      (testing "and the department still wins once it is affordable"
        (is (= [:unlock :dept-shoes] (mgr/decide (assoc saving :money 800) w)))))))

(deftest a-broke-shop-decides-nothing
  (let [[w s] (sup/start)]
    (is (nil? (mgr/decide s w)))
    (is (identical? s (mgr/policy s w)))))

(deftest the-manager-drives-the-test-shop-to-shoes
  (testing "the question the balance gate exists to answer"
    (let [[w s] (sup/start)
          after (sim/run s w 12000 mgr/policy)]
      (is (contains? (:unlocked after) :dept-shoes))
      (is (= [:tops :outerwear :shoes] (world/open-departments w (:unlocked after))))
      (is (pos? (get-in after [:stats :served]))))))

(deftest every-hireable-role-is-actually-worth-hiring
  (testing "a role the manager never buys is content nobody will ever see.

            A one-till shop is not such a shop: the player alone covers a
            single till at every traffic level measured, so the cashier role
            only becomes worth its price once a second till exists that the
            player cannot also stand at."
    (let [two-tills (update sup/tiny :shop/fixtures conj
                            {:fixture/id :till-2 :fixture/kind :checkout
                             :fixture/zone :front :fixture/pos [400 600]})
          [w s] (sim/start (assoc two-tills :shop/spawn-interval 20
                                  :shop/max-customers 8))
          after (sim/run s w 20000 mgr/policy)
          roles (set (map :role (:staff after)))]
      (is (contains? roles :restocker))
      (is (contains? roles :cashier)))))

(deftest progress-reports-what-the-runner-prints
  (let [[w s] (sup/start)
        p (mgr/progress (sim/run s w 600 mgr/policy) w)]
    (is (= [:tops] (:departments p)))
    (is (= "Outerwear" (:next-unlock p)))
    (is (= 300 (:next-unlock-cost p)))))
