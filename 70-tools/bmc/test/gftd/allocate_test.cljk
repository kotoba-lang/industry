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

;; ---- pool-aware allocation (ADR-2608062300) --------------------------------
;;
;; ここで守りたい不変条件は「合計が合う」ではなく **「配れない金を配れる金と
;; 同じ数値に潰さない」**。だから合計より先に、4 クラスの分割が全 tranche を
;; ちょうど覆っていること（重複なし・取りこぼしなし）を検査する。

(def ^:private live-pools
  "budget-supply.edn の :supply/pools と同型（growth-2026H2、ADR-2607246100 §2）。
   実ファイルを読まずに同じ形をここに置くのは、test が I/O に依存しないため
   （実ファイル側の値が変わっても test は構造を検査し続ける）。"
  [{:pool/id :growth-2026H2
    :pool/authority "adr-2607246100-revenue-agent-loop-capital-allocation-governor"
    :pool/total-amount 3000000
    :pool/contested-by {:adr "adr-2607269000-murakumo-mk1-minimax-capital-allocation"
                        :status "proposed" :claim 3000000}
    :pool/tranches
    [{:tranche/id :T1 :tranche/max 300000 :tranche/state :released
      :tranche/committed 0 :tranche/spent 0
      :tranche/eligible-products [:cloud-itonami]}
     {:tranche/id :T2 :tranche/max 700000 :tranche/state :held
      :tranche/eligible-products :undetermined}
     {:tranche/id :T3 :tranche/max 1000000 :tranche/state :held
      :tranche/eligible-products :undetermined}
     {:tranche/id :T4 :tranche/max 1000000 :tranche/state :held
      :tranche/eligible-products :undetermined}]}])

(deftest pool-classes-partition-every-tranche
  (testing "4 クラスは全 tranche をちょうど 1 回ずつ覆う（silent drop なし）"
    (let [demand {:cloud-itonami 78.0 :net-kotobase 82.0 :cloud-murakumo 60.0}
          r (allocate/allocate-pools live-pools demand {})
          {:keys [allocating stranded held exhausted]} (:tranche-classes r)
          all (concat allocating stranded held exhausted)
          declared (for [p live-pools t (:pool/tranches p)] [(:pool/id p) (:tranche/id t)])]
      (is (= 4 (count declared)))
      (is (= (count declared) (count all)) "分類の総数が tranche 総数と一致する")
      (is (= (set declared) (set all)) "どの tranche も落ちていない")
      (is (= (count all) (count (set all))) "同じ tranche が 2 クラスに入っていない")
      (is (= [[:growth-2026H2 :T1]] allocating))
      (is (= 3 (count held))))))

(deftest pool-products-partition-every-demand-key
  (testing "eligible / not-eligible は demand の全 product をちょうど覆う"
    (let [demand {:cloud-itonami 78.0 :net-kotobase 82.0 :cloud-murakumo 60.0}
          r (allocate/allocate-pools live-pools demand {})
          {:keys [eligible not-eligible]} (:product-classes r)]
      (is (= (set (keys demand)) (set (concat eligible not-eligible))))
      (is (empty? (filter (set eligible) not-eligible)))
      ;; T1 が名指ししているのは cloud-itonami だけ。demand が高い net-kotobase も
      ;; 「配れない」— 需要ではなく ADR の eligibility が決める。
      (is (= [:cloud-itonami] eligible))
      (is (= #{:net-kotobase :cloud-murakumo} (set not-eligible))))))

(deftest pool-allocatable-is-released-only
  (testing "配分されるのは released tranche の質量だけ。held は総額に混ぜない"
    (let [demand {:cloud-itonami 78.0 :net-kotobase 82.0 :cloud-murakumo 60.0}
          r (allocate/allocate-pools live-pools demand {})
          by (into {} (for [a (:allocations r)] [(:product a) (:allocated-amount a)]))]
      (is (close? (:allocatable-total r) 300000.0 1e-6))
      (is (close? (:held-total r) 2700000.0 1e-6))
      (is (close? (:declared-total r) 3000000.0 1e-6))
      (is (:feasible? r))
      ;; 300,000 全額が唯一の eligible product へ行く
      (is (close? (:cloud-itonami by) 300000.0 1.0))
      (is (close? (:net-kotobase by) 0.0 1e-6))
      (is (close? (:cloud-murakumo by) 0.0 1e-6))
      ;; 出所が pool/tranche まで辿れる
      (is (= #{[:growth-2026H2 :T1]}
             (set (keys (:by-tranche (first (filter #(= :cloud-itonami (:product %))
                                                    (:allocations r)))))))))))

(deftest pool-undetermined-eligibility-is-not-guessed
  (testing ":undetermined を released にしても、行き先を推測せず :stranded にする
            （『書いていない = 全 product に出せる』と読み替えない）"
    (let [pools [{:pool/id :p :pool/total-amount 500000
                  :pool/tranches [{:tranche/id :X :tranche/max 500000
                                   :tranche/state :released
                                   :tranche/eligible-products :undetermined}]}]
          r (allocate/allocate-pools pools {:a 10.0 :b 10.0} {})]
      (is (= [[:p :X]] (:stranded (:tranche-classes r))))
      (is (empty? (:allocating (:tranche-classes r))))
      (is (close? (:allocatable-total r) 0.0 1e-6))
      (is (close? (:stranded-total r) 500000.0 1e-6))
      (is (every? #(close? (:allocated-amount %) 0.0 1e-9) (:allocations r))))))

(deftest pool-committed-and-spent-reduce-available-mass
  (testing ":tranche/max は上限であって支出目標ではない — committed/spent を引く"
    (let [pools [{:pool/id :p :pool/total-amount 300000
                  :pool/tranches [{:tranche/id :X :tranche/max 300000
                                   :tranche/state :released
                                   :tranche/committed 100000 :tranche/spent 50000
                                   :tranche/eligible-products [:a]}]}]
          r (allocate/allocate-pools pools {:a 10.0} {})]
      (is (close? (:allocatable-total r) 150000.0 1e-6))
      (is (close? (:allocated-amount (first (:allocations r))) 150000.0 1.0))))

  (testing "使い切った released tranche は :exhausted であって :allocating ではない"
    (let [pools [{:pool/id :p :pool/total-amount 300000
                  :pool/tranches [{:tranche/id :X :tranche/max 300000
                                   :tranche/state :released
                                   :tranche/spent 300000
                                   :tranche/eligible-products [:a]}]}]
          r (allocate/allocate-pools pools {:a 10.0} {})]
      (is (= [[:p :X]] (:exhausted (:tranche-classes r))))
      (is (close? (:allocatable-total r) 0.0 1e-6)))))

(deftest pool-disjoint-eligibility-keeps-money-in-its-component
  (testing "eligibility が分断されていたら成分ごとに解く — 需要比例を全体で取ると
            transport が実行不能になり、質量がどこかへ消える"
    (let [pools [{:pool/id :p :pool/total-amount 300000
                  :pool/tranches
                  [{:tranche/id :A :tranche/max 100000 :tranche/state :released
                    :tranche/eligible-products [:x]}
                   {:tranche/id :B :tranche/max 200000 :tranche/state :released
                    :tranche/eligible-products [:y :z]}]}]
          ;; x の需要は小さいが、A は x にしか出せないので 100,000 全額が x へ行く
          demand {:x 1.0 :y 30.0 :z 10.0}
          r (allocate/allocate-pools pools demand {})
          by (into {} (for [a (:allocations r)] [(:product a) (:allocated-amount a)]))]
      (is (= 2 (:components r)))
      (is (:feasible? r))
      (is (close? (:x by) 100000.0 1.0))
      ;; B の 200,000 は y:z = 30:10 で分かれる
      (is (close? (:y by) 150000.0 1.0))
      (is (close? (:z by) 50000.0 1.0))
      (is (close? (reduce + (map :allocated-amount (:allocations r))) 300000.0 1.0)))))

(deftest pool-contested-is-surfaced-not-resolved
  (testing "係争中の pool は報告されるだけ。額を勝手に半分にしたりしない"
    (let [r (allocate/allocate-pools live-pools {:cloud-itonami 78.0} {})
          c (first (:contested r))]
      (is (= 1 (count (:contested r))))
      (is (= :growth-2026H2 (:pool-id c)))
      (is (= "proposed" (:status (:contested-by c))))
      ;; 争われていても declared-total は 3,000,000 のまま（6,000,000 でも
      ;; 1,500,000 でもない）— 実額の判断は owner の入力を要する
      (is (close? (:declared-total r) 3000000.0 1e-6)))))

(deftest pool-empty-input-is-not-a-crash
  (testing "pool が 1 つも無くても NaN も例外も出さない"
    (let [r (allocate/allocate-pools [] {:a 10.0 :b 20.0} {})]
      (is (close? (:allocatable-total r) 0.0 1e-9))
      (is (= 0 (:components r)))
      (is (:feasible? r))
      (is (= [:a :b] (:not-eligible (:product-classes r))))
      (is (every? #(= (:allocated-amount %) (:allocated-amount %)) (:allocations r))))))

(deftest pool-render-shows-held-not-only-allocatable
  (testing "render は 4 クラス全部を印字する — :allocating だけ出すと
            『配れる額 = 予算』に見える"
    (let [r (allocate/allocate-pools live-pools {:cloud-itonami 78.0 :net-kotobase 82.0} {})
          s (allocate/render-pools-table r)]
      (is (re-find #"growth-2026H2/T1" s))
      (is (re-find #"growth-2026H2/T4" s) "held な tranche も表に出る")
      (is (re-find #"held" s))
      (is (re-find #"2700000" s) "held 合計が数値として見える")
      (is (re-find #"係争中の pool" s)))))

(deftest placeholder-budget-is-disclosed-in-the-generated-doc
  (testing ":supply/placeholder? true なら render-md が『これは pool ではない』と書く
            — 生成物が『owner が決めた予算を配分した結果』に見えてはならない
            （ADR-2608062200 決定 4）"
    (let [r (allocate/allocate {:a 10.0 :b 20.0} 10000000 {})
          with (allocate/render-md r {:as-of "2026-08-06" :supply/currency "JPY"
                                      :supply/placeholder? true} :yc)
          without (allocate/render-md r {:as-of "2026-08-06" :supply/currency "JPY"} :yc)]
      (is (re-find #"placeholder であって pool ではない" with))
      (is (re-find #"capital-pools\.edn" with))
      (is (nil? (re-find #"placeholder であって pool ではない" without))
          "placeholder でない予算にこの警告を出すと、本物の予算まで疑わしく見える"))))

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
