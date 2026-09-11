(ns isekai.games.boutique-stack.sim-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.boutique-stack.economy :as econ]
            [isekai.games.boutique-stack.manager :as mgr]
            [isekai.games.boutique-stack.sim :as sim]
            [isekai.games.boutique-stack.support :as sup]
            [isekai.games.boutique-stack.world :as world]))

(deftest a-new-shop-opens-with-full-crates-and-bare-racks
  (testing "the first thing a player does is carry the shop into existence"
    (let [[w s] (sup/start)]
      (is (= 20 (get-in s [:stock :crate-tee])))
      (is (= 0 (get-in s [:stock :rack-tee])))
      (is (= 0 (:money s)))
      (is (= #{:front :dept-tops} (:unlocked s))))))

(deftest the-player-fills-the-rack-without-being-told-to
  (let [[w s] (sup/sealed)
        after (sim/run s w 400)]
    (is (= 6 (get-in after [:stock :rack-tee])) "rack full")
    (is (= 14 (get-in after [:stock :crate-tee]))
        "and the crate is down by exactly six, with no delivery to hide it")))

(deftest a-carry-never-exceeds-its-capacity
  (let [[w s] (sup/quiet)]
    (doseq [tick-count [30 60 120 240]]
      (let [after (sim/run s w tick-count)
            carried (:count (get-in after [:player :carry]) 0)]
        (is (<= carried (econ/carry-capacity after))
            (str "over capacity after " tick-count " ticks"))))))

(deftest stock-is-conserved-between-crate-and-rack
  (testing "the only source of new stock is a delivery and the only sink is a
            sale — moving a garment must never mint or lose one.

            Both taps have to be off for this to mean anything: with
            customers in the shop the total legitimately falls, and with
            deliveries on it legitimately rises."
    (let [[w s] (sup/sealed)
          total (fn [st] (+ (get-in st [:stock :crate-tee])
                            (get-in st [:stock :rack-tee])
                            (:count (get-in st [:player :carry]) 0)))]
      (doseq [n [1 7 30 60 200 400]]
        (is (= 20 (total (sim/run s w n))) (str "after " n " ticks"))))))

(deftest a-locked-department-is-never-restocked
  (let [[w s] (sup/quiet)
        after (sim/run s w 2000)]
    (is (= 0 (get-in after [:stock :rack-jacket])) "outerwear is not open")
    (is (= 0 (get-in after [:stock :rack-sneaker])) "and shoes certainly are not")
    (is (= 10 (get-in after [:stock :crate-jacket])) "its crate is untouched")))

(deftest a-customer-buys-and-the-shop-takes-the-money
  (let [[w s] (sup/start)
        after (sim/run s w 2000)]
    (is (pos? (get-in after [:stats :served])))
    (is (pos? (get-in after [:stats :revenue])))
    (is (= (:money after) (get-in after [:stats :revenue]))
        "nothing was spent, so cash and revenue agree")))

(deftest a-customer-who-finds-a-bare-shelf-eventually-leaves
  (let [[w s] (sup/no-deliveries {:shop/spawn-interval 10})
        ;; Empty the crates too, so there is genuinely nothing to sell.
        starved (update s :stock (fn [m] (into {} (map (fn [[k _]] [k 0]) m))))
        after (sim/run starved w 3000)]
    (is (zero? (get-in after [:stats :served])))
    (is (pos? (get-in after [:stats :walkouts-empty])))
    (is (zero? (get-in after [:stats :walkouts-queue]))
        "they never reached a queue, so the loss is attributed correctly")))

(deftest a-shopper-holding-something-pays-for-it-rather-than-walking-out
  (testing "giving up on the second item should send them to the till, not
            out of the door with an armful"
    (let [[w s] (sup/quiet)
          stocked (assoc-in s [:stock :rack-tee] 1)
          ;; One customer who wants two tees; only one is on the shelf.
          seeded (-> stocked
                     (update :customers conj
                             {:id 1 :pos [200 700] :state :browsing
                              :want [:tee :tee] :basket [] :goal nil
                              :checkout nil :patience 30})
                     (assoc :next-id 2))
          after (sim/run seeded w 400)]
      (is (pos? (get-in after [:stats :served])) "they paid for the one tee")
      (is (zero? (get-in after [:stats :lost]))))))

(deftest patience-does-not-run-out-mid-transaction
  (testing "a customer walking out of a sale that is about to complete is a
            bug — and the stale till entry it left behind used to index a
            vector with nil and crash the tick"
    (let [[w s] (sim/start (assoc sup/tiny :shop/spawn-interval 12
                                  :shop/max-customers 6))
          after (sim/run s w 6000 mgr/policy)]
      (is (pos? (get-in after [:stats :served])))
      (is (empty? (filter (fn [[_ svc]]
                            (not (some #(= (:cust svc) (:id %)) (:customers after))))
                          (:service after)))
          "no till is serving a customer who is not in the shop"))))

(deftest queues-only-move-when-somebody-is-behind-the-till
  (let [[w s] (sim/start (assoc sup/tiny :shop/spawn-interval 20))
        ;; Park the player far away and keep the shelf stocked by hand, so the
        ;; only thing missing is a cashier.
        stocked (-> s
                    (assoc-in [:stock :rack-tee] 6)
                    (assoc-in [:player :pos] [5000 5000]))
        after (loop [st stocked, n 0]
                (if (>= n 1500)
                  st
                  ;; Re-pin the player every tick; the autopilot would walk back.
                  (recur (assoc-in (sim/tick st w) [:player :pos] [5000 5000]) (inc n))))]
    (is (zero? (get-in after [:stats :served])) "no cashier, no sales")
    (is (pos? (get-in after [:stats :walkouts-queue])) "the line gave up")))

(deftest the-same-seed-gives-the-same-shop
  (let [[w s] (sup/start)
        a (sim/run s w 1200 mgr/policy)
        b (sim/run s w 1200 mgr/policy)]
    (is (= (sim/digest a) (sim/digest b)))
    (is (= (:stats a) (:stats b)))))

(deftest a-different-seed-gives-a-different-shop
  (let [[w1 s1] (sim/start (assoc sup/tiny :shop/seed 1))
        [w2 s2] (sim/start (assoc sup/tiny :shop/seed 2))]
    (is (not= (sim/digest (sim/run s1 w1 1200))
              (sim/digest (sim/run s2 w2 1200))))))

(deftest running-in-one-go-matches-running-in-pieces
  (testing "the tick has no hidden per-call state, which is what lets the
            browser run it a frame at a time and the gate run it in a loop"
    (let [[w s] (sup/start)
          whole (sim/run s w 600)
          pieces (sim/run (sim/run (sim/run s w 200) w 200) w 200)]
      (is (= (sim/digest whole) (sim/digest pieces))))))

(deftest direct-control-moves-the-player-and-works-what-they-stand-next-to
  (let [[w s] (sup/quiet)
        at-crate (assoc-in s [:player :pos] [100 400])
        after (sim/tick at-crate w {:move [0 0]})]
    (is (= {:item :tee :count 1} (get-in after [:player :carry]))
        "no second button to press")))

(deftest the-shelf-report-flags-full-and-empty-racks
  (let [[w s] (sup/quiet)
        report (sim/shelf-report (sim/run s w 400) w)]
    (is (= 1 (count report)) "only the open department")
    (is (:full? (first report)))
    (is (not (:empty? (first report))))))

(deftest traffic-rises-as-departments-open
  (testing "a department that did not bring shoppers with it would make
            unlocking feel like a cost"
    (let [[w s] (sup/start)
          one (sim/run s w 40)
          three (sim/run (update s :unlocked into [:dept-outerwear :dept-shoes]) w 40)]
      (is (< (:spawn-timer three) (:spawn-timer one))
          "three departments refill the spawn timer to a shorter interval"))))
