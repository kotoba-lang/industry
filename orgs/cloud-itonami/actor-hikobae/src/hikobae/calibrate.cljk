(ns hikobae.calibrate
  "実観測系列からパラメータを出す。**当てはめであって、当て推量ではない。**

   ここが返す値は `hikobae.model/parameter-provenance` で :derived に分類される
   ものだけ。:assumed のパラメータをここで『校正した』ことにしない —— 校正の
   突合先が無いものは、当てはめても自由度を1つ増やすだけで、出所は増えない。

   当てはめの質は必ず :rmse と :n として返す。**当てはめたことと、
   当てはまったことは別の主張**なので、呼び出し側が後者を検査できるようにする。"
  (:require [hikobae.facts :as facts]
            [hikobae.model :as model]))

(defn- mean [xs] (if (seq xs) (/ (reduce + 0.0 xs) (count xs)) 0.0))

(defn rmse
  "観測点 [[day value] ...] と、日 → 予測値 の関数の二乗平均平方根誤差。"
  [points predict]
  (let [errs (for [[d v] points
                   :let [p (predict d)]
                   :when (number? p)]
               (let [e (- (double p) (double v))] (* e e)))]
    (when (seq errs) (Math/sqrt (mean errs)))))

(defn nrmse
  "観測値の範囲で正規化した RMSE。系列間で比較できるようにする。"
  [points predict]
  (let [vs (map second points)
        span (- (apply max vs) (apply min vs))
        e (rmse points predict)]
    (when (and e (pos? span)) (/ e span))))

;; ---------------------------------------------------------------------------
;; 直接導出できるもの
;; ---------------------------------------------------------------------------

(defn shelter-longterm-fraction
  "避難所人口系列の『尾』をピークで割った比。

   尾 = `tail-from-day` 以降の観測値の平均。住まいを失って避難所に留まる層、
   すなわちライフラインが戻っても帰れない層の割合にあたる。
   この比が 0 だと、モデルは単なる指数減衰になり実観測の平坦部を再現できない
   —— それを確かめる負の対照が `hikobae-test` にある。"
  [facts & {:keys [tail-from-day] :or {tail-from-day 20}}]
  (let [pts (facts/trajectory facts :shelter/population)
        peak (apply max (map second pts))
        tail (map second (filter #(>= (first %) tail-from-day) pts))]
    (assert (seq tail) "hikobae.calibrate: no observations in the tail window")
    (/ (mean tail) (double peak))))

(defn linear-rate
  "系列の [day-a, day-b] 区間の平均日次変化率（絶対値）。"
  [points day-a day-b]
  (let [at (fn [d] (second (first (filter #(= d (first %)) points))))
        a (at day-a) b (at day-b)]
    (assert (and a b) (str "hikobae.calibrate: missing observation at day " day-a " or " day-b))
    (/ (Math/abs (double (- b a))) (double (- day-b day-a)))))

(defn water-capacities
  "上水の応急復旧パラメータを、実観測系列の3区間から出す。

   network  = peak から踊り場水準までを、そこに要した日数で割った日次戸数
              （本管・配水池の復旧で戻る層）
   base     = 踊り場区間の日次戸数（引込・漏水・井戸などの戸別作業の素の速度）
   surge    = 急落区間の日次から base を引いた増分
              （『井戸水対応チーム』設置後の増強分に対応する）

   区間の切り方は恣意的ではなく、系列そのものが持つ段差に合わせてある
   （day 13 付近の踊り場と day 17→20 の急落）。段差を平滑化すると、
   介入が系列に残した痕跡が消える。"
  [facts & {:keys [plateau-day base-to surge-from surge-to peak]
            :or {plateau-day 13 base-to 17 surge-from 17 surge-to 20}}]
  (let [pts (facts/trajectory facts :water/households-without-service)
        at (fn [d] (double (second (first (filter #(= d (first %)) pts)))))
        peak (double (or peak (apply max (map second pts))))
        plateau (at plateau-day)
        base (linear-rate pts plateau-day base-to)
        ;; ⚠ 二重計上を避ける。peak → 踊り場 の減少は本管復旧「だけ」の仕事では
        ;; なく、その間ずっと戸別作業も並行して流れている。差し引かないと本管の
        ;; 能力を過大に見積もり、両区画が同時に速く枯れて尾が消える。
        network-cleared (max 0.0 (- (- peak plateau) (* base (double plateau-day))))
        network-capacity (/ network-cleared (double plateau-day))
        network-fraction (max 0.0 (min 1.0 (/ network-cleared peak)))
        surge-total (linear-rate pts surge-from surge-to)]
    {:network-fraction network-fraction
     :network-capacity network-capacity
     :base base
     :surge (max 0.0 (- surge-total base))
     :surge-total surge-total}))

(defn temp-housing-start-rate
  "建設型応急住宅の着工累積系列の傾き（戸/日）。"
  [facts]
  (let [pts (facts/trajectory facts :temporary-housing/units-started-cumulative)
        [d0 v0] (first pts) [d1 v1] (last pts)]
    (/ (double (- v1 v0)) (double (- d1 d0)))))

;; ---------------------------------------------------------------------------
;; 1次元グリッド探索
;;
;; 多次元の最適化はしない。自由度を増やすほど『当てはまった』は安くなり、
;; :assumed のパラメータを当てはめで正当化する誘惑が生まれる。
;; ---------------------------------------------------------------------------

(defn fit-1d
  "`param-key` を `candidates` の範囲で振り、`series-name` の予測が
   観測点に最も当てはまる値を返す。{:value .. :rmse .. :nrmse .. :n ..}。

   ⚠ 探索は**観測がある区間だけ**を回す。当てはめに使わない先の期間まで
   積分するのは無駄で、grid の本数だけ倍になる。"
  [base-params param-key candidates points series-name]
  (let [horizon (+ 2.0 (double (apply max (map first points))))
        fit-params (assoc base-params :sim/stop-days horizon)
        scored (for [c candidates
                     :let [r (model/run (assoc fit-params param-key c))
                           predict (fn [d] (model/series-at r series-name d))
                           e (rmse points predict)]
                     :when e]
                 {:value c :rmse e :nrmse (nrmse points predict)})
        best (first (sort-by :rmse scored))]
    (assoc best :n (count points) :candidates (count candidates))))

(defn linspace [lo hi n]
  (let [step (/ (- (double hi) lo) (dec (double n)))]
    (mapv #(+ lo (* % step)) (range n))))

(defn fit-report
  "校正済みパラメータと、当てはまりの質を一緒に返す。
   質を返さない校正関数を作らないこと —— 呼び出し側が
   『当てはめた』と『当てはまった』を混同できてしまう。"
  [facts base-params]
  (let [water (water-capacities facts :peak (facts/observed-number facts :water/peak-outage))
        frac (shelter-longterm-fraction facts)
        start (temp-housing-start-rate facts)
        seeded (merge base-params
                      {:shelter/longterm-fraction frac
                       :water/network-fraction (:network-fraction water)
                       :water/network-capacity (:network-capacity water)
                       :water/base-restore-capacity (:base water)
                       :water/surge-restore-capacity (:surge water)
                       :temp-housing/start-rate start})
        shelter-pts (facts/trajectory facts :shelter/population)
        return-fit (fit-1d seeded :shelter/return-rate
                           (linspace 0.02 0.60 59)
                           shelter-pts "shelter_population")
        params (assoc seeded :shelter/return-rate (:value return-fit))
        water-pts (facts/trajectory facts :water/households-without-service)
        r (model/run params)]
    {:params params
     :derived {:shelter/longterm-fraction frac
               :water/network-fraction (:network-fraction water)
               :water/network-capacity (:network-capacity water)
               :water/base-restore-capacity (:base water)
               :water/surge-restore-capacity (:surge water)
               :temp-housing/start-rate start
               :shelter/return-rate (:value return-fit)}
     :fit {:shelter {:rmse (:rmse return-fit) :nrmse (:nrmse return-fit) :n (count shelter-pts)}
           :water {:rmse (rmse water-pts #(model/series-at r "households_without_water" %))
                   :nrmse (nrmse water-pts #(model/series-at r "households_without_water" %))
                   :n (count water-pts)}}}))
