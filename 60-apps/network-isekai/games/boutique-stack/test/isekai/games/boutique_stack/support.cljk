(ns isekai.games.boutique-stack.support
  "A tiny shop, built in code, for tests that want to assert on one mechanic.

  Small enough that every number in a test is traceable: one crate, one rack,
  one till, and a shoe department behind an unlock so the gating can be
  exercised without loading the flagship."
  (:require [isekai.games.boutique-stack.sim :as sim]))

(def tiny
  {:shop/id "bs-test"
   :shop/name "Test Shop"
   :shop/seed 7
   :shop/tick-rate 20
   :shop/starting-money 0
   :shop/spawn-interval 60
   :shop/max-customers 6
   :shop/zones
   [{:zone/id :front :zone/name "Front" :zone/cost 0 :zone/requires #{}
     :zone/entry [200 600]}
    {:zone/id :dept-tops :zone/name "Tops" :zone/cost 0 :zone/requires #{}
     :zone/entry [200 400]}
    {:zone/id :dept-outerwear :zone/name "Outerwear" :zone/cost 300
     :zone/requires #{:dept-tops} :zone/entry [200 250]}
    {:zone/id :dept-shoes :zone/name "Shoes" :zone/cost 800
     :zone/requires #{:dept-tops :dept-outerwear} :zone/entry [200 100]}]
   :shop/fixtures
   [{:fixture/id :entrance :fixture/kind :entrance :fixture/zone :front
     :fixture/pos [200 700]}
    {:fixture/id :exit :fixture/kind :exit :fixture/zone :front
     :fixture/pos [200 700]}
    {:fixture/id :till-1 :fixture/kind :checkout :fixture/zone :front
     :fixture/pos [200 600]}
    {:fixture/id :crate-tee :fixture/kind :crate :fixture/zone :dept-tops
     :fixture/item :tee :fixture/capacity 20 :fixture/refill-ticks 20
     :fixture/pos [100 400]}
    {:fixture/id :rack-tee :fixture/kind :rack :fixture/zone :dept-tops
     :fixture/item :tee :fixture/capacity 6 :fixture/pos [300 400]}
    {:fixture/id :crate-jacket :fixture/kind :crate :fixture/zone :dept-outerwear
     :fixture/item :jacket :fixture/capacity 10 :fixture/refill-ticks 30
     :fixture/pos [100 250]}
    {:fixture/id :rack-jacket :fixture/kind :rack :fixture/zone :dept-outerwear
     :fixture/item :jacket :fixture/capacity 4 :fixture/pos [300 250]}
    {:fixture/id :crate-sneaker :fixture/kind :crate :fixture/zone :dept-shoes
     :fixture/item :sneaker :fixture/capacity 10 :fixture/refill-ticks 30
     :fixture/pos [100 100]}
    {:fixture/id :rack-sneaker :fixture/kind :rack :fixture/zone :dept-shoes
     :fixture/item :sneaker :fixture/capacity 4 :fixture/pos [300 100]}]})

(defn start [] (sim/start tiny))

(defn quiet
  "The tiny shop with nobody walking in, so a test can drive one mechanic
  without customers perturbing it."
  []
  (sim/start (assoc tiny :shop/max-customers 0 :shop/spawn-interval 1000000)))

(defn- with-every-crate
  [shop f]
  (update shop :shop/fixtures
          (fn [fs] (mapv #(if (= :crate (:fixture/kind %)) (f %) %) fs))))

(defn sealed
  "The quiet shop with the supply line switched off as well.

  Two different taps: `quiet` stops customers, this also stops deliveries.
  A test about stock conservation needs both off, and a test about an empty
  shelf needs the deliveries off or the shelf refills itself."
  []
  (sim/start (-> tiny
                 (assoc :shop/max-customers 0 :shop/spawn-interval 1000000)
                 (with-every-crate #(assoc % :fixture/refill-ticks 1000000)))))

(defn no-deliveries
  "Customers, but no restock arriving — for testing what an empty shop does
  to the people in it."
  [overrides]
  (sim/start (-> tiny
                 (merge overrides)
                 (with-every-crate #(assoc % :fixture/refill-ticks 1000000)))))
