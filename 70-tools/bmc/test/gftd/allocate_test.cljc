(ns gftd.allocate-test
  (:require [clojure.test :refer [deftest is testing]]
            [gftd.allocate :as allocate]
            [gftd.react :as react]))

(defn- close? [a b tol] (< (Math/abs (- (double a) (double b))) tol))

;; ---- sinkhorn: general n×m marginal-constraint tests -------------------------

(deftest sinkhorn-respects-marginals
  (testing "generic 2x3 problem: row/col sums ≈ mu/nu within tol, plan non-negative
            (epsilon=1.0 vs cost range 0-2 is a moderate temperature ratio —
            converges in well under max-iters; see the separate 'small epsilon'
            test below for the numerical-stability-at-harsh-ratios case, where
            we deliberately do NOT require full convergence within a bounded
            iteration budget — only that it stays finite/non-NaN, since Sinkhorn's
            convergence rate is genuinely slower at small epsilon relative to the
            cost range, which is an expected property of entropic OT, not a bug)"
    (let [cost [[0.0 1.0 2.0]
                [2.0 1.0 0.0]]
          mu [6.0 4.0]
          nu [3.0 3.0 4.0]
          {:keys [plan converged? iterations row-marginal-error col-marginal-error]}
          (allocate/sinkhorn cost mu nu {:epsilon 1.0 :max-iters 300 :tol 1e-6})]
      (is converged?)
      (is (< iterations 300))
      (is (every? (fn [row] (every? #(>= % -1e-9) row)) plan))
      (let [row-sums (mapv #(reduce + %) plan)
            col-sums (reduce (fn [acc row] (mapv + acc row)) [0.0 0.0 0.0] plan)]
        (is (every? true? (map #(close? %1 %2 1e-4) row-sums mu)))
        (is (every? true? (map #(close? %1 %2 1e-4) col-sums nu))))
      (is (< row-marginal-error 1e-4))
      (is (< col-marginal-error 1e-4))))

  (testing "3x3 uniform-cost problem: plan should split proportionally to nu regardless of row"
    (let [cost [[0.0 0.0 0.0] [0.0 0.0 0.0] [0.0 0.0 0.0]]
          mu [10.0 20.0 30.0]
          nu [15.0 15.0 30.0]
          {:keys [plan converged?]} (allocate/sinkhorn cost mu nu {:epsilon 0.5 :max-iters 300 :tol 1e-8})]
      (is converged?)
      (let [row-sums (mapv #(reduce + %) plan)
            col-sums (reduce (fn [acc row] (mapv + acc row)) [0.0 0.0 0.0] plan)]
        (is (every? true? (map #(close? %1 %2 1e-4) row-sums mu)))
        (is (every? true? (map #(close? %1 %2 1e-4) col-sums nu))))))

  (testing "small epsilon does not overflow/underflow (log-domain stabilization holds)"
    (let [cost [[0.0 100.0] [100.0 0.0]]
          mu [5.0 5.0]
          nu [5.0 5.0]
          {:keys [plan converged?]} (allocate/sinkhorn cost mu nu {:epsilon 0.001 :max-iters 2000 :tol 1e-6})]
      (is converged?)
      ;; every entry must be an ordinary finite double — no NaN/Infinity leaking
      ;; through the naive exp(-C/epsilon) path this implementation avoids.
      (is (every? (fn [row] (every? (fn [x] (and (= x x) (< x 1.0e300) (> x -1.0e300))) row)) plan))
      ;; near-zero epsilon + large cost asymmetry ⇒ mass should concentrate on
      ;; the cheap diagonal (0,0) and (1,1), not the expensive off-diagonal.
      (is (> (get-in plan [0 0]) (get-in plan [0 1])))
      (is (> (get-in plan [1 1]) (get-in plan [1 0])))))

  (testing "large epsilon (near-uniform kernel) still converges without NaN"
    (let [cost [[1.0 5.0] [5.0 1.0]]
          mu [8.0 8.0]
          nu [8.0 8.0]
          {:keys [plan converged?]} (allocate/sinkhorn cost mu nu {:epsilon 50.0 :max-iters 300 :tol 1e-6})]
      (is converged?)
      (is (every? (fn [row] (every? (fn [x] (= x x)) row)) plan)))))

;; ---- allocate: single-supply-node degenerate OT (portfolio split) -----------

(def products [:cloud-itonami :net-kotobase :cloud-murakumo :etzhayyim])

(deftest allocate-conserves-total-budget
  (testing "sum of allocated amounts ≈ total-budget within tolerance, no floors/caps"
    (let [demand {:cloud-itonami 78.0 :net-kotobase 82.0 :cloud-murakumo 60.0 :etzhayyim 40.0}
          r (allocate/allocate demand 1000000 {})]
      (is (:balanced? r))
      (is (:feasible? r))
      (is (close? (:total-allocated r) (:total-budget r) 1.0))
      (is (close? (reduce + (map :allocated-amount (:allocations r))) 1000000 1.0)))))

(deftest allocate-equal-demand-equal-split
  (testing "equal demand across all products → equal allocation"
    (let [demand (into {} (for [p products] [p 50.0]))
          r (allocate/allocate demand 400000 {})
          amounts (map :allocated-amount (:allocations r))]
      (is (every? #(close? % 100000.0 1.0) amounts)))))

(deftest allocate-proportional-to-demand
  (testing "2x demand → ~2x allocation (no floors/caps to distort the split)"
    (let [demand {:a 10.0 :b 20.0}
          r (allocate/allocate demand 300000 {})
          by (into {} (for [a (:allocations r)] [(:product a) (:allocated-amount a)]))]
      (is (close? (:b by) (* 2.0 (:a by)) 1.0))
      (is (close? (+ (:a by) (:b by)) 300000 1.0)))))

(deftest allocate-zero-demand-gets-near-zero
  (testing "product with demand=0 and no floor gets ≈0 allocation"
    (let [demand {:a 0.0 :b 10.0 :c 10.0}
          r (allocate/allocate demand 200000 {})
          by (into {} (for [a (:allocations r)] [(:product a) (:allocated-amount a)]))]
      (is (close? (:a by) 0.0 1e-6))
      (is (close? (:b by) 100000.0 1.0))
      (is (close? (:c by) 100000.0 1.0))))

  (testing "product with demand=0 but an explicit floor gets its floor, not 0"
    (let [demand {:a 0.0 :b 10.0 :c 10.0}
          r (allocate/allocate demand 200000 {:floors {:a 20000.0}})
          by (into {} (for [a (:allocations r)] [(:product a) (:allocated-amount a)]))]
      (is (close? (:a by) 20000.0 1.0))
      (is (= :floor (:bound (first (filter #(= :a (:product %)) (:allocations r))))))
      (is (close? (+ (:a by) (:b by) (:c by)) 200000 1.0))
      ;; remaining 180000 split proportionally 50/50 between b and c
      (is (close? (:b by) 90000.0 1.0))
      (is (close? (:c by) 90000.0 1.0)))))

(deftest allocate-cap-respected
  (testing "a dominant-demand product hitting its cap redistributes the remainder to the rest"
    (let [demand {:a 90.0 :b 5.0 :c 5.0}
          r (allocate/allocate demand 100000 {:caps {:a 30000.0}})
          by (into {} (for [a (:allocations r)] [(:product a) (:allocated-amount a)]))
          bound-of (fn [p] (:bound (first (filter #(= p (:product %)) (:allocations r)))))]
      (is (close? (:a by) 30000.0 1.0))
      (is (= :cap (bound-of :a)))
      (is (close? (+ (:a by) (:b by) (:c by)) 100000 1.0))
      ;; remaining 70000 split proportionally 50/50 between b and c (equal demand)
      (is (close? (:b by) 35000.0 1.0))
      (is (close? (:c by) 35000.0 1.0)))))

(deftest allocate-infeasible-floors-reported-honestly
  (testing "floors summing above budget → feasible? false, not a silently wrong split"
    (let [demand {:a 50.0 :b 50.0}
          r (allocate/allocate demand 100000 {:floors {:a 80000.0 :b 80000.0}})]
      (is (false? (:feasible? r))))))

(deftest allocate-rejects-floor-above-cap
  (testing "floor > cap for the same product is a caller error, not silently resolved"
    (is (thrown? #?(:clj Exception :cljs :default)
                 (allocate/allocate {:a 10.0} 1000 {:floors {:a 500.0} :caps {:a 100.0}})))))

;; ---- governed-write proposals (optional ledger visibility) ------------------

(deftest allocate-proposals-are-governor-clean
  (testing "allocate/proposals produce :canvas/add-item into <product>.metrics and the governor accepts them"
    (let [idx {:blocks {:cloud-itonami.metrics {:canvas/id :cloud-itonami.metrics
                                                :canvas/items ["m1"]}
                        :net-kotobase.metrics {:canvas/id :net-kotobase.metrics
                                               :canvas/items ["m1"]}}}
          demand {:cloud-itonami 78.0 :net-kotobase 82.0}
          r (allocate/allocate demand 500000 {})
          props (allocate/proposals r)
          {:keys [approved rejected]} (react/governor idx props)]
      (is (= 2 (count props)))
      (is (every? #(= :canvas/add-item (:proposal/action %)) props))
      (is (= #{:cloud-itonami.metrics :net-kotobase.metrics} (set (map :canvas/id props))))
      (is (= 2 (count approved)))
      (is (empty? rejected)))))
