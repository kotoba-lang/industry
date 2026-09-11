(ns gpr.velocity-test
  (:require [clojure.test :refer [deftest is testing]]
            [gpr.math :as m]
            [gpr.result :as r]
            [gpr.velocity :as vel]))

(defn- close? [a b tol] (< (m/abs* (- a b)) tol))

(deftest permittivity-round-trip
  (doseq [er [1.0 4.0 9.0 25.0 81.0]]
    (let [v (r/value (vel/velocity-from-permittivity er))]
      (is (close? er (r/value (vel/permittivity-from-velocity v)) 1e-9)))))

(deftest rejects-impossible-inputs
  (testing "εr < 1 も、光速超えも、値ではなく err として返る"
    (is (r/err? (vel/velocity-from-permittivity 0.5)))
    (is (r/err? (vel/permittivity-from-velocity 0.4)))
    (is (r/err? (vel/permittivity-from-velocity 0.0)))))

(deftest depth-and-twt-are-inverses
  (let [v 0.1]
    (is (close? 0.8 (vel/depth-m (vel/twt-ns 0.8 v) v) 1e-12))))

(deftest hyperbola-apex-is-the-minimum
  (let [t0 16.0 v 0.1 x0 2.0
        ts (map #(vel/hyperbola-twt t0 v x0 %) (range 0.0 4.01 0.05))]
    (is (close? t0 (apply min ts) 1e-9))
    (is (close? t0 (vel/hyperbola-twt t0 v x0 x0) 1e-12))))
