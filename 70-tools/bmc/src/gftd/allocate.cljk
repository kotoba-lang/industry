(ns gftd.allocate
  "Optimal-transport ベースのポートフォリオ予算配分 (ADR-2607194500)。

   一言で: 予算プールを N product の需要スコアへ按分する道具。理論的な着想は
   Alessio Figalli の Monge–Ampère 正則性研究（連続最適輸送）だが、ここで
   実装しているのは離散・エントロピー正則化 OT（Sinkhorn 反復）であり、
   PDE ソルバーではない。

   誇張しない立ち位置: 現行運用（budget-supply.edn の :supply/total-amount
   1 個）は supply node が 1 個しかない退化ケース — mu が要素 1 個なので
   列制約だけで plan が nu（正規化した需要）に一意に決まり、cost 行列や
   epsilon は最終結果に数学的に影響しない（唯一の実行可能解がそのまま
   Sinkhorn の不動点になるため）。つまり実質「エントロピー正則化された
   需要比例配分」に還元される。それでも `sinkhorn` 自体は任意の n×m に対して
   正しく動く一般解法として実装した（n=1 に特化したハックではない）—
   将来 budget-supply.edn に複数 tranche（例: growth/infra/runway）を supply
   node として追加すれば、tranche×product の affinity/cost 行列を使った
   非自明な輸送構造にそのまま拡張できる（本 v1 では未実装 — README/ADR に
   follow-up として明記、中途半端な多 tranche 対応はしない）。

   需要指標の注意: caller（cli.cljc）は `gftd.score/score-all` の :yc（既定）
   または :bmc スコアを demand として渡す — これは「現状手に入る中で最良の
   数値プロキシ」であって opportunity size（市場機会の大きさ）の直接計測
   ではない。YC bench スコアは de-risked-ness（検証の進み具合）と opportunity
   size を混同しうる。より良い需要指標が出てきたら差し替えられるよう、
   score-key の選択は cli 側（呼び出し側）の責務にしてある — このモジュールは
   product→demand-score の map を受け取るだけで、その由来を知らない。

   Pure .cljc; io（budget-supply.edn / maturity-scores.edn の読み書き、
   ledger への write）は cli.cljc 側に置く。"
  (:require [clojure.string :as str]))

;; ---- numerics: log-domain stabilized Sinkhorn --------------------------------

(defn- logsumexp
  "log(sum(exp(xs))) を桁溢れ・アンダーフローなしで計算する。全要素が ##-Inf
   （= その行/列が完全にマスクされている＝周辺質量ゼロの列/行）のときだけ
   ##-Inf を返す（NaN にしない）。max=##-Inf のとき素朴な
   `max + log(sum(exp(x-max)))` は `##-Inf - ##-Inf` = NaN になるので、
   ここで明示的に特別扱いする。"
  [xs]
  (let [mx (reduce max ##-Inf xs)]
    (if (= mx ##-Inf)
      ##-Inf
      (+ mx (Math/log (reduce + 0.0 (map #(Math/exp (- (double %) mx)) xs)))))))

(defn- safe-log [x] (Math/log (double x)))

(defn- finite?
  "portable NaN/Infinite チェック（java.lang.Double は cljs に無いので使わない
   — `(not= x x)` は NaN だけ true、##Inf/##-Inf との比較は clj/cljs 両対応）。"
  [x]
  (and (= x x) (not= x ##Inf) (not= x ##-Inf)))

(defn sinkhorn
  "エントロピー正則化 OT の log-domain stabilized Sinkhorn-Knopp。任意の n×m
   に対して正しく動く一般解法（n=1 の退化ケース専用のハックではない）。

   cost-matrix: n 行 (supply) × m 列 (demand) の non-negative コスト
   (vector of vector)。mu: 長さ n の供給質量、nu: 長さ m の需要質量
   (sum(mu) と sum(nu) が一致していること — transport problem が実行可能
   である前提。呼び出し側で保証する)。

   ナイーブな `exp(-C/epsilon)` は epsilon が小さいと桁溢れ/アンダーフロー
   するので、双対ポテンシャル u（行）/v（列）を log 領域で交互更新する
   (Peyré & Cuturi, *Computational Optimal Transport* §4.4 の stabilized
   log-domain Sinkhorn と同型の更新式):

     u_i ← log(mu_i) - logsumexp_j(M_ij + v_j)
     v_j ← log(nu_j) - logsumexp_i(M_ij + u_i)     (M_ij = -C_ij/epsilon)

   を max-iters 回、または周辺分布の残差が tol 未満に収束するまで繰り返す。
   plan_ij = exp(u_i + M_ij + v_j)。

   tol は sum(mu) に対する相対許容量として扱う（絶対値ではない — 予算の桁が
   変わっても既定値 1e-6 が同じ意味を持つようにするため）。

   → {:plan [[...]] :u [...] :v [...] :iterations n :converged? bool
       :row-marginal-error e :col-marginal-error e}"
  [cost-matrix mu nu {:keys [epsilon max-iters tol] :or {epsilon 0.05 max-iters 200 tol 1e-6}}]
  (let [n (count mu)
        m (count nu)]
    (when-not (= n (count cost-matrix))
      (throw (ex-info "cost-matrix の行数は mu の長さと一致しなければならない"
                       {:mu-length n :cost-rows (count cost-matrix)})))
    (when-not (every? #(= m (count %)) cost-matrix)
      (throw (ex-info "cost-matrix の列数は nu の長さと一致しなければならない"
                       {:nu-length m})))
    (let [total (reduce + 0.0 mu)
          err-budget (* (double tol) (max 1.0 total))
          log-mu (mapv safe-log mu)
          log-nu (mapv safe-log nu)
          M (mapv (fn [row] (mapv (fn [c] (/ (- (double c)) (double epsilon))) row)) cost-matrix)
          row-vec (fn [i] (nth M i))
          col-vec (fn [j] (mapv #(nth % j) M))
          idxs-n (vec (range n))
          idxs-m (vec (range m))]
      (loop [iter 1 u (vec (repeat n 0.0)) v (vec (repeat m 0.0))]
        (let [u' (mapv (fn [i lmu] (- lmu (logsumexp (map + (row-vec i) v)))) idxs-n log-mu)
              v' (mapv (fn [j lnu] (- lnu (logsumexp (map + (col-vec j) u')))) idxs-m log-nu)
              plan (mapv (fn [i] (mapv (fn [j] (Math/exp (+ (nth u' i) (nth (row-vec i) j) (nth v' j)))) idxs-m))
                         idxs-n)
              row-sums (mapv #(reduce + 0.0 %) plan)
              col-sums (reduce (fn [acc row] (mapv + acc row)) (vec (repeat m 0.0)) plan)
              row-err (reduce max 0.0 (map (fn [a b] (Math/abs (- a b))) row-sums mu))
              col-err (reduce max 0.0 (map (fn [a b] (Math/abs (- a b))) col-sums nu))
              converged? (and (<= row-err err-budget) (<= col-err err-budget))]
          (if (or converged? (>= iter max-iters))
            {:plan plan :u u' :v v' :iterations iter :converged? converged?
             :row-marginal-error row-err :col-marginal-error col-err}
            (recur (inc iter) u' v')))))))

;; ---- allocation (single/multi supply node → per-product amount) -------------

(defn- proportional-split
  "products（有効集合）へ budget を demand 比例で按分する 1 回分の OT 解。
   1 supply node（mu=[budget]）× len(products) demand node の問題を組み立てて
   `sinkhorn` を呼ぶ。cost は -demand（需要が高いほど「輸送コストが低い」と
   いう向きの記録用ラベルに過ぎず、n=1 なので数学的な結果には影響しない —
   ns docstring 参照）。demand が全員 0 のときは等分にフォールバックする
   （0 除算で NaN にしない、これも正直な「情報が無いので等分」という扱い）。"
  [products demand budget {:keys [epsilon max-iters]}]
  (if (empty? products)
    {:amounts {} :iterations 0 :converged? true
     :row-marginal-error 0.0 :col-marginal-error 0.0}
    (let [demands (mapv #(max 0.0 (double (get demand % 0.0))) products)
          total-demand (reduce + 0.0 demands)
          nu (if (pos? total-demand)
               (mapv #(* budget (/ % total-demand)) demands)
               (vec (repeat (count products) (/ budget (count products)))))
          mu [budget]
          cost [(mapv #(- %) demands)]
          r (sinkhorn cost mu nu {:epsilon epsilon :max-iters max-iters})
          amounts (zipmap products (first (:plan r)))]
      (assoc (select-keys r [:iterations :converged? :row-marginal-error :col-marginal-error])
             :amounts amounts))))

(defn- resolve-floors-caps
  "floor/cap 制約つきの配分。標準的な water-filling: `proportional-split` の
   raw 結果で floor/cap に触れた product を「fix」して active 集合から除外し、
   残った budget を残りの product へ比例再配分…を違反が無くなるまで繰り返す
   （毎回 active から最低 1 product が抜けるので、高々 (count products) 回で
   必ず停止する）。floor 合計が budget を超える等で解けない場合は
   :feasible? false を返す（質量をどこかへ黙って捨てない — honest failure。
   その回の raw amounts はそのまま返す点は呼び出し側で活用可能）。"
  [products demand budget floors caps opts]
  (let [inf-tol (* 1e-6 (max 1.0 (double budget)))]
    (loop [active (vec products) remaining budget fixed {} bound {}
           round 0 iters 0 all-converged? true]
      (if (empty? active)
        {:amounts fixed :bound bound :rounds round :iterations iters
         :converged? all-converged? :feasible? true}
        (let [{:keys [amounts iterations converged?]} (proportional-split active demand remaining opts)
              violations (keep (fn [p]
                                  (let [a (get amounts p)
                                        f (get floors p)
                                        c (get caps p)]
                                    (cond
                                      (and f (< a f)) [p f :floor]
                                      (and c (> a c)) [p c :cap]
                                      :else nil)))
                                active)]
          (cond
            (empty? violations)
            {:amounts (merge fixed amounts) :bound bound :rounds (inc round)
             :iterations (+ iters iterations) :converged? (and all-converged? converged?)
             :feasible? true}

            :else
            (let [fix-amt (into {} (map (fn [[p v _]] [p v]) violations))
                  fix-bound (into {} (map (fn [[p _ b]] [p b]) violations))
                  fixed-sum (reduce + 0.0 (vals fix-amt))]
              (if (> fixed-sum (+ remaining inf-tol))
                {:amounts (merge fixed fix-amt) :bound (merge bound fix-bound) :rounds (inc round)
                 :iterations (+ iters iterations) :converged? false :feasible? false}
                (recur (vec (remove (set (map first violations)) active))
                       (- remaining fixed-sum)
                       (merge fixed fix-amt)
                       (merge bound fix-bound)
                       (inc round)
                       (+ iters iterations)
                       (and all-converged? converged?))))))))))

(defn allocate
  "demand: {product demand-score, ...} — demand vector を product-keyword で
   索引した map（由来は問わない。cli.cljc は gftd.score のスコアを渡す）。
   total-budget: 単一の供給質量（budget-supply.edn の :supply/total-amount）。
   floors/caps: 任意、{product amount} map（budget-supply.edn の
   :supply/floors / :supply/caps）。

   単一 supply node（mu が要素 1 個）なので OT としては退化ケースだが、
   `sinkhorn` は一般 n×m 解法をそのまま使う。floor/cap は `resolve-floors-caps`
   の water-filling で解く。

   → {:total-budget n :total-allocated n :balanced? bool :feasible? bool
       :converged? bool :rounds n :iterations n :epsilon e
       :allocations [{:product :demand-score :allocated-amount :share-pct
                       :floor :cap :bound} ...]}   ; bound は :floor|:cap|nil"
  [demand total-budget {:keys [epsilon max-iters floors caps]
                         :or {epsilon 0.05 max-iters 200 floors {} caps {}}}]
  (doseq [p (keys demand)]
    (let [f (get floors p) c (get caps p)]
      (when (and f c (> f c))
        (throw (ex-info (str "floor > cap for " p) {:product p :floor f :cap c})))))
  (let [products (vec (sort (keys demand)))
        budget (double total-budget)
        {:keys [amounts bound rounds iterations converged? feasible?]}
        (resolve-floors-caps products demand budget floors caps {:epsilon epsilon :max-iters max-iters})
        total-allocated (reduce + 0.0 (vals amounts))
        tol-abs (* 1e-6 (max 1.0 budget))
        balanced? (<= (Math/abs (- total-allocated budget)) tol-abs)]
    {:total-budget budget
     :total-allocated total-allocated
     :balanced? balanced?
     :feasible? feasible?
     :converged? converged?
     :rounds rounds
     :iterations iterations
     :epsilon epsilon
     :allocations
     (vec (for [p products]
            {:product p
             :demand-score (double (get demand p 0.0))
             :allocated-amount (get amounts p 0.0)
             :share-pct (if (pos? budget) (* 100.0 (/ (get amounts p 0.0) budget)) 0.0)
             :floor (get floors p)
             :cap (get caps p)
             :bound (get bound p)}))}))

;; ---- pool-aware allocation (multi supply node) -------------------------------
;;
;; ADR-2608062200（資本 pool は id を持ち、正本 ADR をちょうど 1 つ持つ）/
;; ADR-2608062300（budget-supply.edn の tranche 化）。
;;
;; `allocate` との違いは「supply node が 1 個か n 個か」だけではない。**配れない
;; 金を配れる金と同じ数値に潰さない**ことがこの経路の目的で、そのために tranche を
;; 4 クラスに分け、sinkhorn へ渡すのは :allocating だけにする。残り 3 つを質量ゼロの
;; supply node として渡さないのは意味論と数値の両方の理由による — 質量 0 の行は
;; log(0) - logsumexp(全マスク行) = -Inf - (-Inf) = NaN を作る（`logsumexp` が
;; 全マスクを ##-Inf で返すのは列マスクを安全にするためで、行の質量 0 を救わない）。

(defn- tranche-mass
  "その tranche から**今**配れる額。released でなければ 0。released なら
   max - committed - spent（ADR-2607246100 §2『JPY 3,000,000 is a maximum
   envelope』— :tranche/max は上限であって支出目標ではないので、既に commit /
   spend した分は引く）。"
  [t]
  (if (= :released (:tranche/state t))
    (max 0.0 (- (double (or (:tranche/max t) 0))
                (double (or (:tranche/committed t) 0))
                (double (or (:tranche/spent t) 0))))
    0.0))

(defn- tranche-eligible
  "その tranche が資金を出せる product 集合。:undetermined と未記載は**空集合**
   にする — ADR に書かれていない eligibility をコードで推測しない
   （ADR-2608062200 決定 1）。「書いていない = 全 product に出せる」と読み替えると、
   ADR が決めていない配分を allocator が勝手に決めることになる。"
  [t]
  (let [e (:tranche/eligible-products t)]
    (if (or (nil? e) (= e :undetermined)) #{} (set e))))

(defn pool-nodes
  "pools（budget-supply.edn の :supply/pools）→ 全 tranche を 1 行に展開し 4 クラス
   に分類する。**ここで行を落とさない。**

     :allocating  released・質量 > 0・demand に居る eligible product が 1 つ以上
     :stranded    released・質量 > 0 だが行き先が宣言されていない（:undetermined、
                  または宣言された product が demand に居ない）
     :exhausted   released だが max - committed - spent ≤ 0
     :held        まだ release されていない"
  [pools demand]
  (let [product-set (set (keys demand))]
    (vec (for [p pools t (:pool/tranches p)
               :let [mass (tranche-mass t)
                     declared (tranche-eligible t)
                     eligible (into (sorted-set) (filter product-set declared))
                     released? (= :released (:tranche/state t))]]
           {:pool-id (:pool/id p)
            :pool-authority (:pool/authority p)
            :tranche-id (:tranche/id t)
            :state (:tranche/state t)
            :max (double (or (:tranche/max t) 0))
            :mass mass
            :eligible eligible
            :eligible-declared declared
            :release-condition (:tranche/release-condition t)
            :purpose (:tranche/purpose t)
            :class (cond
                     (not released?) :held
                     (not (pos? mass)) :exhausted
                     (empty? eligible) :stranded
                     :else :allocating)}))))

(defn- components
  "supply node × column の二部グラフを連結成分に分ける（不動点まで閉じるだけの
   素朴な実装。tranche 数は 1 桁なので十分）。

   成分をまたいで資金は動かせないので、需要比例の正規化は**成分ごとに**行う。
   全体比例にすると、ある成分の供給質量が別の成分の需要に引っぱられて transport
   自体が実行不能になる（例: T1 が cloud-itonami にしか出せないのに、nu を全
   product の需要比で決めると T1 の 300,000 を受け取れる列質量が足りない）。

   → [{:supply-idx [i...] :column-idx [j...]} ...]"
  [supply columns]
  (let [n (count supply)
        col-idx (into {} (map-indexed (fn [j p] [p j]) columns))
        adj (mapv (fn [nd] (into #{} (keep col-idx) (:eligible nd))) supply)]
    (loop [remaining (set (range n)) out []]
      (if (empty? remaining)
        out
        (let [seed (apply min remaining)
              [ss cs] (loop [ss #{seed} cs (nth adj seed)]
                        (let [ss' (into ss (filter (fn [i] (some cs (nth adj i))) remaining))
                              cs' (into cs (mapcat #(nth adj %) ss'))]
                          (if (and (= ss ss') (= cs cs'))
                            [ss cs]
                            (recur ss' cs'))))]
          (recur (reduce disj remaining ss)
                 (conj out {:supply-idx (vec (sort ss)) :column-idx (vec (sort cs))})))))))

(defn- solve-component
  "1 連結成分の masked OT。cost は eligible なら -demand、そうでなければ ##Inf
   （= M_ij が ##-Inf になり `logsumexp` の全マスク処理に乗る）。"
  [supply columns demand {:keys [supply-idx column-idx]} {:keys [epsilon max-iters]}]
  (let [rows (mapv #(nth supply %) supply-idx)
        cols (mapv #(nth columns %) column-idx)
        mu (mapv :mass rows)
        mass (reduce + 0.0 mu)
        ds (mapv #(max 0.0 (double (get demand % 0.0))) cols)
        td (reduce + 0.0 ds)
        nu (if (pos? td)
             (mapv #(* mass (/ % td)) ds)
             (vec (repeat (count cols) (/ mass (count cols)))))
        cost (mapv (fn [nd]
                     (mapv (fn [p] (if (contains? (:eligible nd) p)
                                     (- (double (get demand p 0.0)))
                                     ##Inf))
                           cols))
                   rows)]
    {:rows rows :cols cols :mass mass
     :result (sinkhorn cost mu nu {:epsilon epsilon :max-iters max-iters})}))

(defn allocate-pools
  "pools（budget-supply.edn の :supply/pools）× demand（{product demand-score}）
   → tranche を supply node とした n×m 配分。

   `allocate` が答えるのは「予算を需要比でどう割るか」だが、こちらが答えるのは
   **「今いくら配れて、それはどこへ出せるのか」**である。配れない金（held /
   exhausted / 行き先未宣言）は総額から静かに引かず、別の列として残す。

   → {:allocatable-total n :held-total n :stranded-total n :declared-total n
       :tranche-classes {:allocating [...] :stranded [...] :held [...] :exhausted [...]}
       :product-classes {:eligible [...] :not-eligible [...]}
       :nodes [...]                 ; pool-nodes の全行（4 クラス込み）
       :allocations [{:product :demand-score :allocated-amount :share-pct :by-tranche}]
       :contested [{:pool-id :authority :contested-by}]
       :components n :converged? bool :feasible? bool
       :marginal-error e :epsilon e}

   :feasible? が false になるのは、宣言された eligibility では需要比例の目標を
   満たせない場合（連結成分内でも Gale–Hoffman 条件は自動では満たされない）。
   その場合も数値を捏造せず、marginal-error をそのまま報告する。"
  [pools demand {:keys [epsilon max-iters] :or {epsilon 0.05 max-iters 200}}]
  (let [nodes (pool-nodes pools demand)
        by-class (group-by :class nodes)
        supply (vec (get by-class :allocating []))
        columns (vec (into (sorted-set) (mapcat :eligible supply)))
        comps (components supply columns)
        solved (mapv #(solve-component supply columns demand % {:epsilon epsilon :max-iters max-iters})
                     comps)
        ;; product → 総額 / product → {[pool tranche] 額}
        [by-product by-tranche]
        (reduce (fn [[bp bt] {:keys [rows cols result]}]
                  (reduce (fn [[bp bt] [ri row]]
                            (let [nd (nth rows ri)
                                  k [(:pool-id nd) (:tranche-id nd)]]
                              (reduce (fn [[bp bt] [ci amt]]
                                        (let [p (nth cols ci)]
                                          [(update bp p (fnil + 0.0) amt)
                                           (if (> amt 1e-9)
                                             (assoc-in bt [p k] amt)
                                             bt)]))
                                      [bp bt] (map-indexed vector row))))
                          [bp bt] (map-indexed vector (:plan result))))
                [{} {}] solved)
        allocatable (reduce + 0.0 (map :mass supply))
        held-total (reduce + 0.0 (map :max (get by-class :held [])))
        stranded-total (reduce + 0.0 (map :mass (get by-class :stranded [])))
        declared-total (reduce + 0.0 (map (comp double #(or % 0) :pool/total-amount) pools))
        converged? (every? (comp :converged? :result) solved)
        marg-err (reduce max 0.0
                         (mapcat (fn [{:keys [result]}]
                                   [(:row-marginal-error result) (:col-marginal-error result)])
                                 solved))
        products (vec (sort (keys demand)))
        col-set (set columns)
        ids (fn [ns] (vec (sort-by (juxt (comp str :pool-id) (comp str :tranche-id))
                                   (map (fn [n] [(:pool-id n) (:tranche-id n)]) ns))))]
    {:allocatable-total allocatable
     :held-total held-total
     :stranded-total stranded-total
     :declared-total declared-total
     :tranche-classes {:allocating (ids (get by-class :allocating []))
                       :stranded (ids (get by-class :stranded []))
                       :held (ids (get by-class :held []))
                       :exhausted (ids (get by-class :exhausted []))}
     :product-classes {:eligible (vec (filter col-set products))
                       :not-eligible (vec (remove col-set products))}
     :nodes nodes
     :components (count comps)
     :converged? converged?
     :feasible? (and converged? (<= marg-err (* 1e-6 (max 1.0 allocatable))))
     :marginal-error marg-err
     :epsilon epsilon
     :contested (vec (for [p pools :when (:pool/contested-by p)]
                       {:pool-id (:pool/id p)
                        :authority (:pool/authority p)
                        :contested-by (:pool/contested-by p)}))
     :allocations
     (vec (for [p products]
            {:product p
             :demand-score (double (get demand p 0.0))
             :allocated-amount (get by-product p 0.0)
             :share-pct (if (pos? allocatable)
                          (* 100.0 (/ (get by-product p 0.0) allocatable))
                          0.0)
             :by-tranche (get by-tranche p {})}))}))

;; ---- governed-write proposals (optional ledger visibility) -------------------

(defn- block-id [product suffix] (keyword (str (name product) "." suffix)))

(defn proposals
  "allocate の結果 → governor 経由 ledger 書込用 proposal（product ごとに
   metrics block へスナップショット item を1件追加。gftd.funnel/proposals の
   funnel-snapshot と同じ dedup 挙動 — governor 側の変更は不要、
   :canvas/add-item は既に allowed-actions に入っている）。"
  [{:keys [allocations]}]
  (for [{:keys [product demand-score allocated-amount share-pct]} allocations]
    {:proposal/action :canvas/add-item
     :canvas/id (block-id product "metrics")
     :event/value (str "配分 (OT/Sinkhorn, gftd allocate): " (Math/round allocated-amount)
                       " (" (/ (Math/round (* 10.0 share-pct)) 10.0) "%) — demand-score="
                       (/ (Math/round (* 10.0 demand-score)) 10.0))
     :proposal/reason "gftd allocate write — portfolio budget allocation snapshot"}))

;; ---- render ------------------------------------------------------------------

(defn- fmt1 [x] (str (/ (Math/round (* 10.0 (double x))) 10.0)))
(defn- fmt0 [x] (str (Math/round (double x))))

(defn render-table
  "terminal / md 共用の markdown table + 合計診断行。"
  [{:keys [allocations total-budget total-allocated balanced? feasible? converged? iterations rounds epsilon]}]
  (str "| product | demand-score | allocated | share% | floor | cap | bound |\n"
       "|---|---|---|---|---|---|---|\n"
       (apply str
              (for [{:keys [product demand-score allocated-amount share-pct floor cap bound]}
                    (sort-by (comp - :allocated-amount) allocations)]
                (str "| " (name product) " | " (fmt1 demand-score) " | " (fmt0 allocated-amount)
                     " | " (fmt1 share-pct) " | " (if floor (fmt0 floor) "—")
                     " | " (if cap (fmt0 cap) "—") " | " (if bound (name bound) "—") " |\n")))
       "\n合計: allocated=" (fmt0 total-allocated) " / budget=" (fmt0 total-budget)
       " | balanced=" balanced? " feasible=" feasible? " converged=" converged?
       " | iterations=" iterations " rounds=" rounds " epsilon=" epsilon "\n"))

(defn render-md
  [result budget-supply score-key]
  (str "# portfolio budget allocation — entropic OT (Sinkhorn)\n\n"
       "<!-- generated by gftd cli `allocate md` (70-tools/bmc, ADR-2607194500).\n"
       "     入力 = maturity-scores.edn (:score-key " score-key ") + budget-supply.edn。手編集禁止。 -->\n\n"
       "**As-of (budget)**: " (:as-of budget-supply) "  \n"
       "**Currency**: " (:supply/currency budget-supply) "  \n"
       (when (:supply/placeholder? budget-supply)
         (str "\n> ⚠ **この表の総額は owner 未確認の placeholder であって pool ではない**"
              "（ADR-2608062200 決定 4）。budget-supply.edn の `:supply/total-amount` は"
              "『実額が決まるまでの仮置き』と自ファイルに明記されており、これを配分した結果は"
              "**「owner が決めた予算を配分した結果」ではない**。owner が ADR で実際に決めた額は"
              "`:supply/pools`（現在 1 本、JPY 3,000,000 / ADR-2607246100）にあり、そのうち"
              "今配れるのは released tranche だけ — それは `capital-pools.edn`"
              "（`gftd allocate pools md`）が計算する。\n>\n"
              "> 数字の桁が違うだけでなく**答えている問いが違う**: この表は「予算があるとしたら"
              "需要比でどう割るか」、`capital-pools.edn` は「今いくら配れて、それはどこへ出せるか」。\n\n"))
       "**手法**: log-domain stabilized Sinkhorn（entropic OT）。現行は単一 supply node"
       "（予算プール1個）× N product（demand node）の退化ケース — 実行可能解が一意"
       "（列制約だけで plan=nu に決まる）なので、実質「需要比例配分」に還元される。"
       "将来、複数 tranche（例: growth/infra/runway）を supply node として追加すれば、"
       "tranche×product の affinity/cost 行列を使った非自明な輸送構造にそのまま拡張"
       "できる一般 n×m ソルバー（`gftd.allocate/sinkhorn`）を使っている（未実装、follow-up）。\n\n"
       "**需要指標**: :score-key=" (name score-key) "（`gftd.score` の成熟度スコア）。"
       "opportunity size の直接計測ではなく、現状手に入る中で最良の数値プロキシ — "
       "de-risked-ness（検証の進み具合）と opportunity size を混同しうる注意点がある。\n\n"
       (render-table result)
       "\n"))

;; ---- pool render -------------------------------------------------------------

(defn- pair-str [[pool tranche]] (str (name pool) "/" (name tranche)))

(defn render-pools-table
  "terminal / md 共用。**4 クラスすべてを印字する** — :allocating だけを出すと
   『配れる額 = 予算』に見えるので、held / stranded / exhausted を同じ表に残す。"
  [{:keys [nodes allocations allocatable-total held-total stranded-total declared-total
           tranche-classes product-classes components converged? feasible? marginal-error
           epsilon contested]}]
  (str "| pool/tranche | state | class | max | 配分可能 | eligible |\n"
       "|---|---|---|---|---|---|\n"
       (apply str
              (for [n (sort-by (juxt (comp str :pool-id) (comp str :tranche-id)) nodes)]
                (str "| " (pair-str [(:pool-id n) (:tranche-id n)])
                     " | " (name (:state n))
                     " | " (name (:class n))
                     " | " (fmt0 (:max n))
                     " | " (if (= :allocating (:class n)) (fmt0 (:mass n)) "—")
                     " | " (if (seq (:eligible n))
                             (str/join ", " (map name (:eligible n)))
                             (if (empty? (:eligible-declared n)) "(未宣言)" "(demand 外)"))
                     " |\n")))
       "\n| product | demand-score | 配分額 | share% | 出所 |\n"
       "|---|---|---|---|---|\n"
       (apply str
              (for [{:keys [product demand-score allocated-amount share-pct by-tranche]}
                    (sort-by (juxt (comp - :allocated-amount) (comp str :product)) allocations)]
                (str "| " (name product) " | " (fmt1 demand-score)
                     " | " (fmt0 allocated-amount) " | " (fmt1 share-pct)
                     " | " (if (seq by-tranche)
                             (str/join ", " (map pair-str (sort-by (comp str first) (keys by-tranche))))
                             "—")
                     " |\n")))
       "\n配分可能 (released): " (fmt0 allocatable-total)
       " | held: " (fmt0 held-total)
       " | 行き先未宣言 (stranded): " (fmt0 stranded-total)
       " | 宣言済 pool 総額: " (fmt0 declared-total) "\n"
       "tranche クラス: allocating=" (count (:allocating tranche-classes))
       " stranded=" (count (:stranded tranche-classes))
       " held=" (count (:held tranche-classes))
       " exhausted=" (count (:exhausted tranche-classes)) "\n"
       "product: eligible=" (count (:eligible product-classes))
       " (" (str/join ", " (map name (:eligible product-classes))) ")"
       " / not-eligible=" (count (:not-eligible product-classes)) "\n"
       "components=" components " converged=" converged? " feasible=" feasible?
       " marginal-error=" marginal-error " epsilon=" epsilon "\n"
       (when (seq contested)
         (apply str "\n⚠ 係争中の pool:\n"
                (for [c contested]
                  (str "  " (name (:pool-id c)) " — 正本 " (:authority c)
                       " に対し " (:adr (:contested-by c)) " (" (:status (:contested-by c)) ") が "
                       (fmt0 (:claim (:contested-by c))) " を主張\n"))))))

(defn render-pools-md
  [result budget-supply score-key]
  (str "# capital pools — released tranche だけを配分する (ADR-2608062300)\n\n"
       "<!-- generated by gftd cli `allocate pools md` (70-tools/bmc).\n"
       "     入力 = maturity-scores.edn (:score-key " score-key ") + budget-supply.edn の\n"
       "     :supply/pools。手編集禁止。 -->\n\n"
       "**As-of (budget)**: " (:as-of budget-supply) "  \n"
       "**Currency**: " (:supply/currency budget-supply) "  \n\n"
       "この表が答えるのは「予算を需要比でどう割るか」ではなく、**「今いくら配れて、"
       "それはどこへ出せるのか」**である。`gftd allocate`（`portfolio-allocation.edn`）"
       "との違いは supply node の数だけではない — あちらの入力 `:supply/total-amount` は "
       "owner 未確認の placeholder であり pool ではない（ADR-2608062200 決定 4）。"
       "こちらは owner が ADR で決めた実額だけを読む。\n\n"
       "配れない金は総額から静かに引かず、別クラスとして残す:\n\n"
       "- `:held` — release 条件未達。**未割当ではなく意図された hold**。\n"
       "- `:stranded` — released だが行き先 product が ADR に書かれていない"
       "（`:undetermined`）。書かれていない eligibility をコードで推測しない。\n"
       "- `:exhausted` — max - committed - spent ≤ 0。\n\n"
       (render-pools-table result)
       "\n"))
