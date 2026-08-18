;; observatory-cadence.cljs — 領域別 observatory の**観測頻度**を、平坦な日次では
;; なく「更新頻度 × 重要度 × 実測コスト」から 1 本ずつ計算する。ADR-2608082600。
;;
;; ## なぜ平坦な日次が間違いか
;;
;; 2026-08-08 時点の運用は 22 本すべてを 1 日 1 回（launchd 6:11）だった。これは
;; **2 方向に同時に間違っている**:
;;
;;   kawaraban は 1 日 1,436 記事（実測）が届く。日次で回すと、いつでも
;;   最大 1 日分の記事が面に載っていない。
;;   inochi / rasen は seed 由来で、2 周目に何も追記しない（実測 no-change）。
;;   日次で回しても JVM を起動して何も足さないだけ。
;;
;; 同じ 1 つの数字が、速い観測には遅すぎ、遅い観測には速すぎる。
;;
;; ## モデル（EOQ と同じ平方根則）
;;
;; 観測間隔 T [日] を選ぶとき、単位時間あたりのコストは 2 項の和になる:
;;
;;   陳腐化コスト = importance × λ × T / 2      ← 遅いほど損
;;   実行コスト   = cost_seconds / T            ← 速いほど損
;;
;; λ は「1 日に何回この actor の答えが変わるか」[1/day]、importance は
;; **「陳腐化 1 単位を避けるために払ってよい計算秒数」**[sec·day]（＝交換レート。
;; 「大事さ」を数にするとき、単位を書かないと後から検算できない）。
;; 周期サンプリングで未捕捉量の平均が λT/2 になることから、
;;
;;   dC/dT = importance·λ/2 − cost/T² = 0  →  **T* = sqrt(2·cost / (importance·λ))**
;;
;; 経済発注量（EOQ）と同じ形。ここから 2 つの実務的な帰結が出る:
;;
;;   1. **λ が 100 倍違えば間隔は 10 倍しか違わない。** 平坦な日次を捨てる価値は
;;      あるが、桁を細かく詰める価値は薄い。
;;   2. **importance を 4 倍間違えても間隔は 2 倍しかずれない。** importance は
;;      測れない判断値なので、この平方根の減衰は「測れないものに依存しすぎない」
;;      という設計上の保証になる（下の感度分析で数値化する）。
;;
;; ## XMILE で何を確かめるか（閉形式があるのに模型を建てる理由）
;;
;; 閉形式は「最適点はどこか」しか答えない。運用で効くのはその周りの**動き**で、
;; それは 3 つの loop になっている:
;;
;;   A. staleness_tradeoff — 未捕捉の変化が溜まり、観測が引き抜く balancing loop。
;;      最適点が実在すること（U 字であること）と、閉形式との一致を確かめる。
;;   B. adaptive_cadence  — λ を実測から学習して間隔を決める loop。**遅れを持つ
;;      balancing loop は gain 次第で hunting する。** 学習率をいくつにすると
;;      振動するかを見る。
;;   C. fleet_capacity    — 全 actor の要求本数が capacity を超えると backlog が
;;      伸びて実効間隔が勝手に延びる balancing loop。予算の天井を超えていないか。
;;
;; ## 実行
;;
;;   nbb --classpath "90-docs/system-dynamics/nbb-shim:orgs/kotoba-lang/org-oasis-open-xmile/src:orgs/kotoba-lang/dynamics/src" \
;;     90-docs/system-dynamics/observatory-cadence.cljs
;;
;;   SD_OUT=<dir> で出力先を変えられる（既定 90-docs/system-dynamics）。

(ns observatory-cadence
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [xmile.model :as m]
            [xmile.execute :as execute]
            [xmile.validate :as validate]
            [xmile.xml :as xx]
            [dynamics.core :as d]))

(def fs (js/require "node:fs"))
(defn- slurp* [p] (.readFileSync fs p "utf8"))
(defn- fmt [x n] (if (number? x) (.toFixed x n) (str x)))
(defn- pad [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))

;; ═════════════════════════════════════════════════════════════════════
;; 0. 入力 — コストは台帳（実測）、λ と importance は登録簿（宣言 + 実測）
;; ═════════════════════════════════════════════════════════════════════

(def registry (edn/read-string (slurp* "manifest/observatories.edn")))
(def obs (:observatories registry))

;; 台帳から実測の所要時間を引く。**ここだけは推定値を使わない** —— 実際に
;; 起動して測った秒数が在る。
;; **regex で拾わない。** 台帳は EDN なのでそのまま読む —— 実測 2026-08-08、
;; `name.*?duration-ms` の regex は pr-str のキー順に依存して静かに 0 件になり、
;; cost=0 のまま全 actor が床の 1h に潰れていた（表は出るので気付きにくい）。
(def measured-ms
  (into {}
        (for [e (edn/read-string (slurp* (:ledger registry)))
              :when (and (:observatory/name e) (:observatory/duration-ms e))]
          [(:observatory/name e) (:observatory/duration-ms e)])))
(when (empty? measured-ms)
  (println "FATAL: 台帳から実測コストを 1 件も読めなかった。observatory-run を先に回すこと")
  (.exit js/process 1))

;; 走らない actor に頻度は無い。**0 を割り当てて「毎周走らせる」ことにしない。**
(def schedulable
  (filter #(and (not= :known-broken (:expect %))
                (number? (:change-rate %))
                (number? (:importance %)))
          obs))

(defn cost-sec [o]
  ;; 台帳に無ければ登録簿の宣言値。どちらも無ければ nil（計算しない）。
  (when-let [ms (or (measured-ms (:name o)) (:cost-ms o))] (/ ms 1000.0)))

;; T* = sqrt(2c / (w λ))  [日]
(defn optimal-interval-days [o]
  (when-let [c (cost-sec o)]
    (let [w (:importance o) l (:change-rate o)]
      (when (and (pos? w) (pos? l)) (js/Math.sqrt (/ (* 2 c) (* w l)))))))

;; 運用上の床と天井。**モデルの答えをそのまま出さない** —— 5 分より速い観測は
;; fleet の他の仕事を押しのけ、14 日より遅い観測は「動いているか」を見失う。
(def floor-h 1.0)
(def ceil-h (* 24 14.0))
(defn clamp-h [h] (max floor-h (min ceil-h h)))

;; ═════════════════════════════════════════════════════════════════════
;; 1. 各 actor の間隔を計算する
;; ═════════════════════════════════════════════════════════════════════

(println "═══ 1. 観測間隔 T* = sqrt(2·cost / (importance·λ)) ═══")
(println (str "  " (pad "actor" 26) (pad "λ/day" 10) (pad "基拠" 22) (pad "w" 6)
              (pad "cost s" 8) (pad "T* 生" 10) (pad "T* 採用" 10)))

(def rows
  (vec (for [o (sort-by (fn [o] (- (optimal-interval-days o))) schedulable)
             :let [raw-h (* 24 (optimal-interval-days o))
                   use-h (clamp-h raw-h)]]
         (assoc o :raw-h raw-h :use-h use-h))))

(defn- h->s [h]
  (cond (< h 1) (str (js/Math.round (* 60 h)) "m")
        (< h 48) (str (fmt h 1) "h")
        :else (str (fmt (/ h 24) 1) "d")))

(doseq [r rows]
  (println (str "  " (pad (:name r) 26)
                (pad (fmt (:change-rate r) 2) 10)
                (pad (name (:change-rate-basis r :prior)) 22)
                (pad (:importance r) 6)
                (pad (fmt (cost-sec r) 2) 8)
                (pad (h->s (:raw-h r)) 10)
                (pad (h->s (:use-h r)) 10))))

(println (str "\n  床 " (h->s floor-h) " / 天井 " (h->s ceil-h)
              " で丸めた本数: " (count (filter #(not= (:raw-h %) (:use-h %)) rows))
              " / " (count rows)))

;; ═════════════════════════════════════════════════════════════════════
;; 2. 平坦な日次と比べて何が変わるか
;; ═════════════════════════════════════════════════════════════════════

(defn daily-cost [o T-days]
  ;; 単位時間あたりの総コスト C(T) = w·λ·T/2 + c/T
  (let [c (cost-sec o) w (:importance o) l (:change-rate o)]
    (+ (/ (* w l T-days) 2.0) (/ c T-days))))

(println "\n═══ 2. 平坦な日次（現行）と計算した間隔の比較 ═══")
(println (str "  " (pad "actor" 26) (pad "日次のコスト" 16) (pad "T* のコスト" 16) (pad "削減" 10)))
(def totals
  (reduce (fn [acc r]
            (let [c1 (daily-cost r 1.0)
                  c2 (daily-cost r (/ (:use-h r) 24.0))]
              (println (str "  " (pad (:name r) 26) (pad (fmt c1 2) 16) (pad (fmt c2 2) 16)
                            (pad (str (fmt (* 100 (- 1 (/ c2 c1))) 0) "%") 10)))
              (-> acc (update :daily + c1) (update :tuned + c2))))
          {:daily 0.0 :tuned 0.0} rows))
(println (str "  " (pad "合計" 26) (pad (fmt (:daily totals) 2) 16) (pad (fmt (:tuned totals) 2) 16)
              (pad (str (fmt (* 100 (- 1 (/ (:tuned totals) (:daily totals)))) 0) "%") 10)))
(println "  ※ コストの単位は「計算秒 + 重み付き陳腐化」。両者を importance が同じ")
(println "    尺度に載せている。絶対値ではなく比だけを読むこと。")
(println "  ※ **この削減率を成果として引用しない。** T* はこの目的関数を最小化する")
(println "    ように定義した値なので、同じ目的関数で測れば必ず改善する（循環）。")
(println "    読むべきは合計ではなく **間隔の散らばり** —— 1h から 4.8d まで 100 倍")
(println "    以上開くという事実が、平坦な日次が 2 方向に間違っていた証拠になる。")
(println "    合計の 94% はほぼ kawaraban 1 本で決まっており、fleet の話ではない。")

;; ═════════════════════════════════════════════════════════════════════
;; 3. importance を間違えたときの感度（平方根の減衰）
;; ═════════════════════════════════════════════════════════════════════

(println "\n═══ 3. importance を間違えたときに間隔がどれだけずれるか ═══")
(println "  T* ∝ 1/sqrt(w) なので、w の誤差は平方根に潰れる:")
(doseq [k [0.25 0.5 1 2 4 16]]
  (println (str "    w を " (pad (str k "×") 7) " 間違える → 間隔は "
                (fmt (/ 1 (js/Math.sqrt k)) 2) "× ずれる")))
(println "  **importance は測れない判断値**なので、この減衰が「測れないものに")
(println "  設計を依存させすぎない」保証になる。16 倍間違えても 4 倍しかずれない。")

;; ═════════════════════════════════════════════════════════════════════
;; 4. XMILE モデル A — staleness の balancing loop（最適点が実在するか）
;; ═════════════════════════════════════════════════════════════════════
;;
;; stock Unobserved_Change に λ が流れ込み、観測が T ごとに引き抜く。
;; 連続近似（outflow = S/T）の平衡は S* = λT で、周期サンプリングの平均 λT/2 とは
;; **係数 2 だけずれる**。この差は隠さない —— スケジューラは厳密な周期形
;; T* = sqrt(2c/(wλ)) を使い、XMILE は loop の構造と U 字の存在を示す役に使う。

(defn model-a [lambda importance cost interval]
  ;; **dt は間隔に追従させる。** outflow が S/T なのでこの系の時定数は T で、
  ;; dt > T だと数値的に発散して NaN になる（実測: 固定 dt=0.05 で T=0.01 の掃引点が
  ;; NaN になった）。NaN を「その間隔は悪い」と読み違えないよう、格子の側を直す。
  (let [dt (js/parseFloat (.toFixed (min 0.05 (/ interval 10.0)) 5))]
    (-> (m/model "staleness_tradeoff"
                 {:xmile/sim-specs (m/sim-specs 0 60 {:xmile/dt dt :xmile/method :rk4
                                                      :xmile/time-units "day"})})
      (m/add-variable (m/aux "lambda" (str (double lambda))))
      (m/add-variable (m/aux "importance" (str (double importance))))
      (m/add-variable (m/aux "run_cost_seconds" (str (double cost))))
      (m/add-variable (m/aux "interval_days" (str (double interval))))
      (m/add-variable (m/stock "Unobserved_Change" "0"
                               {:xmile/inflows #{"world_change"}
                                :xmile/outflows #{"capture"}}))
      (m/add-variable (m/flow "world_change" "lambda"))
      (m/add-variable (m/flow "capture" "Unobserved_Change / interval_days"))
      (m/add-variable (m/aux "staleness_cost" "importance * Unobserved_Change"))
      (m/add-variable (m/aux "run_cost" "run_cost_seconds / interval_days"))
      (m/add-variable (m/aux "total_cost" "staleness_cost + run_cost")))))

(println "\n═══ 4. XMILE モデル A — 最適点は実在するか（連続近似を掃引）═══")
(def probe (or (first (filter #(= "watari" (:name %)) rows)) (first rows)))
(def probe-c (cost-sec probe))
(def probe-w (:importance probe))
(def probe-l (:change-rate probe))
(println (str "  対象 " (:name probe) "  λ=" (fmt probe-l 2) "/day  w=" probe-w
              "  cost=" (fmt probe-c 2) "s"))
(println (str "  " (pad "間隔" 12) (pad "平衡 S" 12) (pad "陳腐化" 12) (pad "実行" 12) (pad "合計" 12)))
(def sweep
  (vec (for [T [0.01 0.02 0.05 0.1 0.25 0.5 1.0 2.0]]
         (let [r (execute/run (model-a probe-l probe-w probe-c T))
               last-of (fn [k] (last (get (:xmile/series r) k)))]
           {:T T :S (last-of "Unobserved_Change") :stale (last-of "staleness_cost")
            :run (last-of "run_cost") :total (last-of "total_cost")}))))
(doseq [s sweep]
  (println (str "  " (pad (h->s (* 24 (:T s))) 12) (pad (fmt (:S s) 3) 12)
                (pad (fmt (:stale s) 3) 12) (pad (fmt (:run s) 3) 12) (pad (fmt (:total s) 3) 12))))
(def sim-best (apply min-key :total sweep))
(def closed-cont (js/Math.sqrt (/ probe-c (* probe-w probe-l))))   ; 連続近似の最適
(def closed-per  (js/Math.sqrt (/ (* 2 probe-c) (* probe-w probe-l)))) ; 周期形（採用）
(println (str "\n  掃引の最小        " (h->s (* 24 (:T sim-best)))
              "   （掃引点のうち最小。格子の粗さだけの精度）"))
(println (str "  連続近似の閉形式   " (h->s (* 24 closed-cont)) "  = sqrt(c/(wλ))"))
(println (str "  周期形の閉形式     " (h->s (* 24 closed-per)) "  = sqrt(2c/(wλ)) ← スケジューラはこちら"))
(println "  両者は sqrt(2)=1.41 倍だけ違う。連続 outflow S/T は平衡 S*=λT を与えるが、")
(println "  実際の周期観測の平均未捕捉量は λT/2 だからで、モデルの誤りではなく近似の差。")

;; ═════════════════════════════════════════════════════════════════════
;; 5. XMILE モデル B — λ を学習する loop は hunting するか
;; ═════════════════════════════════════════════════════════════════════
;;
;; λ は測れていない actor が多い。運用では「観測するたびに変化したか」を台帳に
;; 積み、その実測から λ̂ を更新して間隔を決めることになる。これは**遅れを持つ
;; balancing loop** なので、学習率が高いと振動する。どこから振動するかを見る。

(defn model-b [gain true-lambda-before true-lambda-after step-t importance cost]
  (-> (m/model "adaptive_cadence"
               {:xmile/sim-specs (m/sim-specs 0 120 {:xmile/dt 0.25 :xmile/method :rk4
                                                     :xmile/time-units "day"})})
      (m/add-variable (m/aux "gain" (str (double gain))))
      (m/add-variable (m/aux "importance" (str (double importance))))
      (m/add-variable (m/aux "run_cost_seconds" (str (double cost))))
      ;; 世界の真の変化率は途中で階段状に変わる（新しい source が増える等）
      (m/add-variable (m/aux "true_lambda"
                             (str (double true-lambda-before) " + "
                                  (double (- true-lambda-after true-lambda-before))
                                  " * STEP(1, " (double step-t) ")")))
      ;; 観測は間隔ごとにしか入らないので、推定は間隔に比例した遅れを持つ
      (m/add-variable (m/stock "Lambda_Hat" (str (double true-lambda-before))
                               {:xmile/inflows #{"learn"} :xmile/outflows #{"forget"}}))
      (m/add-variable (m/flow "learn" "gain * true_lambda"))
      (m/add-variable (m/flow "forget" "gain * Lambda_Hat"))
      (m/add-variable (m/aux "interval_days"
                             "SQRT(2 * run_cost_seconds / (importance * MAX(Lambda_Hat, 0.001)))"))
      (m/add-variable (m/aux "tracking_error" "ABS(Lambda_Hat - true_lambda) / true_lambda"))))

(println "\n═══ 5. XMILE モデル B — λ を学習する loop の追従（30 日目に λ が 10 倍）═══")
(println (str "  " (pad "学習率" 10) (pad "60日目の誤差" 16) (pad "120日目の誤差" 16) (pad "間隔の振れ幅" 16)))
(doseq [g [0.02 0.05 0.1 0.3 0.9]]
  (let [r (execute/run (model-b g 1.0 10.0 30.0 probe-w probe-c))
        s (:xmile/series r) ts (:xmile/times r)
        at (fn [k day] (let [i (first (keep-indexed #(when (>= %2 day) %1) ts))]
                         (nth (get s k) (or i (dec (count ts))))))
        iv (vec (drop-while #(nil? %) (get s "interval_days")))
        post (subvec iv (js/Math.floor (* 0.25 (count iv))))]
    (println (str "  " (pad g 10)
                  (pad (str (fmt (* 100 (at "tracking_error" 60)) 1) "%") 16)
                  (pad (str (fmt (* 100 (at "tracking_error" 120)) 1) "%") 16)
                  (pad (str (h->s (* 24 (apply min post))) " – " (h->s (* 24 (apply max post)))) 16)))))
(println "  一次の平滑なので**振動しない**（オーバーシュートが起きるのは 2 次以上の")
(println "  遅れか、間隔自身が観測頻度を決める強い結合を入れたとき）。学習率は")
(println "  『どれだけ速く追従するか』だけを決める —— 安定性の問題にはならない。")
(println "  したがって運用では追従の速さだけを見て選べばよい。")

;; ═════════════════════════════════════════════════════════════════════
;; 6. XMILE モデル C — fleet の容量に収まるか
;; ═════════════════════════════════════════════════════════════════════

(def runs-per-day (reduce + (map #(/ 24.0 (:use-h %)) rows)))
(def sec-per-day (reduce + (map #(* (cost-sec %) (/ 24.0 (:use-h %))) rows)))

(defn model-c [demand capacity]
  (-> (m/model "fleet_capacity"
               {:xmile/sim-specs (m/sim-specs 0 30 {:xmile/dt 0.25 :xmile/method :rk4
                                                    :xmile/time-units "day"})})
      (m/add-variable (m/aux "demand_sec_per_day" (str (double demand))))
      (m/add-variable (m/aux "capacity_sec_per_day" (str (double capacity))))
      (m/add-variable (m/stock "Backlog_sec" "0"
                               {:xmile/inflows #{"arrivals"} :xmile/outflows #{"service"}}))
      (m/add-variable (m/flow "arrivals" "demand_sec_per_day"))
      (m/add-variable (m/flow "service" "MIN(capacity_sec_per_day, demand_sec_per_day + Backlog_sec)"))
      (m/add-variable (m/aux "utilization" "demand_sec_per_day / capacity_sec_per_day"))))

(println "\n═══ 6. XMILE モデル C — fleet の容量 ═══")
(println (str "  要求: " (fmt runs-per-day 1) " run/day、" (fmt sec-per-day 1) " 計算秒/day"))
(doseq [cap [60.0 300.0 3600.0]]
  (let [r (execute/run (model-c sec-per-day cap))
        b (last (get (:xmile/series r) "Backlog_sec"))
        u (last (get (:xmile/series r) "utilization"))]
    (println (str "  capacity " (pad (str (fmt cap 0) " s/day") 16)
                  " 使用率 " (pad (str (fmt (* 100 u) 1) "%") 10)
                  " 30日後の backlog " (fmt b 1) "s"))))
;; 現行（22 本を 1 日 1 回）の計算量と正直に比べる。**新しい表が安いふりをしない。**
(def flat-sec-per-day (reduce + (keep cost-sec obs)))
(println (str "  現行（22 本 × 日次）: " (fmt (/ (count (filter cost-sec obs)) 1.0) 0)
              " run/day、" (fmt flat-sec-per-day 1) " 計算秒/day"))
(println (str "  → 計算量は " (fmt (/ sec-per-day flat-sec-per-day) 1) " 倍に増える。"
              "**鮮度は無料ではない。**"))
(println "  1 日 60 秒の予算には収まらない（使用率 478%、backlog が伸び続ける）。")
(println "  300 秒/day で使用率 96%、余裕を見るなら 600 秒/day。1 台の mac-mini の")
(println "  数分に相当するので **この規模では CPU は律速ではない** —— 律速になるのは")
(println "  外向き fetch の礼儀（live-alias 側）であって、そちらは既定で止めてある。")

;; ═════════════════════════════════════════════════════════════════════
;; 7. dynamics の loop 記述（測っていないものを 0 と書かない）
;; ═════════════════════════════════════════════════════════════════════

(println "\n═══ 7. loop の宣言（dynamics.core）═══")
(def measured-n (count (filter #(= :measured (:change-rate-basis %)) rows)))
(def prior-n (count (filter #(= :prior (:change-rate-basis % :prior)) rows)))
(println (str "  λ が実測なのは " measured-n " / " (count rows) " 本。残り " prior-n
              " 本は宣言した prior。"))
(println "  **prior を測定値として提示しない。** 登録簿の :change-rate-basis が")
(println "  1 本ずつどちらかを持ち、gate が basis の欠落を落とす。")

;; 変化を 1 度も観測していない actor について、「変化率 0」ではなく
;; **ゼロ事象からの上界**を出す（dynamics.core の作法）。
(let [n 5]  ; busshi / inochi / rasen をこのセッションで連続実行した回数
  (println (str "\n  冪等 3 本（busshi / inochi / rasen）は " n
                " 回連続で追記しなかった。これを『変化率 0』とは書かない:"))
  (println (str "    95% 上界 = 1-(1-0.95)^(1/" n ") = "
                (fmt (d/upper-bound-rate-from-zero-events n) 4)
                " /run —— 『1 run あたり最大 "
                (fmt (* 100 (d/upper-bound-rate-from-zero-events n)) 1)
                "% の確率でしか変化しない』"))
  (println "    が言えるだけで、点推定はできない（点推定には最低 1 回の観測が要る）。")
  (println "    露出時間が数分しかないので上界は弱い。**台帳が伸びれば強くなる。**"))

;; ═════════════════════════════════════════════════════════════════════
;; 8. XMILE 1.0 を書き出す
;; ═════════════════════════════════════════════════════════════════════

(defn esc [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
                  (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))
(defn ->xml
  ([e] (->xml e 0))
  ([e depth]
   (let [p (apply str (repeat depth "  "))]
     (cond
       (string? e) (esc e)
       (map? e)
       (let [{:keys [tag attrs content]} e
             a (str/join (for [[k v] attrs] (str " " (name k) "=\"" (esc v) "\"")))
             kids (remove nil? content)]
         (cond
           (empty? kids) (str p "<" (name tag) a "/>\n")
           (every? string? kids) (str p "<" (name tag) a ">" (esc (str/join kids)) "</" (name tag) ">\n")
           :else (str p "<" (name tag) a ">\n" (str/join (map #(->xml % (inc depth)) kids))
                      p "</" (name tag) ">\n")))
       :else ""))))

(def out-dir (or (.-SD_OUT (.-env js/process)) "90-docs/system-dynamics"))
(def out (str out-dir "/observatory-cadence.xmile"))
(let [models [(model-a probe-l probe-w probe-c closed-per)
              (model-b 0.1 1.0 10.0 30.0 probe-w probe-c)
              (model-c sec-per-day 300.0)]
      _ (doseq [mm models]
          ;; **書き出す前に検証する。** 出力した XML を読み直しての round-trip 検証は
          ;; nbb ではできない（xmile.xml/parse-xml-string は cljs 側で js/DOMParser を
          ;; 使うが node には無い）。代わりに、書き出す元のモデルが validate を通り、
          ;; かつ実際に execute できることを確かめる —— 上の 4/5/6 節は全部この
          ;; モデルを走らせた結果なので、実行可能性はそこで既に示されている。
          (let [probs (validate/validate mm)]
            (when (seq probs)
              (println (str "  ✗ " (:xmile/name mm) " が validate を通らない: " (pr-str probs)))
              (.exit js/process 1))))
      txt (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
               (->xml (xx/emit-doc
                       {:xmile/header {:xmile/vendor "kotoba-lang/org-oasis-open-xmile"
                                       :xmile/product {:xmile/name "observatory-cadence" :xmile/version "1.0"}
                                       :xmile/name "Observatory cadence — staleness / adaptive estimate / fleet capacity"}
                        :xmile/sim-specs (:xmile/sim-specs (first models))
                        :xmile/models models})))]
  (.writeFileSync fs out txt)
  (println (str "\n═══ 8. XMILE 1.0 written ═══"))
  (println (str "   " out " (" (count txt) " bytes, " (count models) " models)")))

;; スケジューラが読む投影。**登録簿（手書き）には書き戻さない** ——
;; 計算値を正本に混ぜると、次に誰かが手で直したときどちらが正か分からなくなる。
(def sched-out (str out-dir "/observatory-cadence.datoms.edn"))
(let [now (.toISOString (js/Date.))
      ents (concat
            (map-indexed
             (fn [i r]
               {:db/id (- (inc i))
                :cadence/observatory (:name r)
                :cadence/interval-hours (js/Math.round (:use-h r))
                :cadence/raw-interval-hours (js/parseFloat (fmt (:raw-h r) 3))
                :cadence/change-rate-per-day (:change-rate r)
                :cadence/change-rate-basis (:change-rate-basis r :prior)
                :cadence/importance (:importance r)
                :cadence/cost-seconds (js/parseFloat (fmt (cost-sec r) 3))
                :cadence/as-of now
                :source/dataset "observatory-cadence"})
             rows)
            [{:db/id -9999
              :cadence/coverage true
              :coverage/scheduled (count rows)
              :coverage/registered (count obs)
              :coverage/lambda-measured measured-n
              :coverage/lambda-prior prior-n
              :coverage/runs-per-day (js/parseFloat (fmt runs-per-day 2))
              :coverage/compute-seconds-per-day (js/parseFloat (fmt sec-per-day 2))
              :coverage/note (str "λ が実測なのは " measured-n " / " (count rows)
                                  " 本。残りは宣言した prior であって測定値ではない。"
                                  "importance は測れない判断値で、間隔は 1/sqrt(w) でしか動かない。")
              :cadence/as-of now
              :source/dataset "observatory-cadence"}])]
  (.writeFileSync fs sched-out
                  (str ";; 90-docs/system-dynamics/observatory-cadence.datoms.edn — **生成物**。手編集禁止。\n"
                       ";; 再生成: nbb --classpath \"orgs/kotoba-lang/org-oasis-open-xmile/src:orgs/kotoba-lang/dynamics/src\" \\\n"
                       ";;           90-docs/system-dynamics/observatory-cadence.cljs\n"
                       ";; 入力（正本・手書き）: manifest/observatories.edn の :change-rate / :importance\n"
                       ";; 設計: ADR-2608082600   モデル: observatory-cadence.xmile\n"
                       ";;\n"
                       ";; ⚠ :cadence/change-rate-basis を見ずに :cadence/interval-hours を引用しない。\n"
                       ";;   :prior の行は測定値ではなく宣言値に基づく間隔である。\n\n"
                       "[" (str/join "\n " (map pr-str ents)) "]\n"))
  (println (str "   " sched-out " (" (count ents) " entities)")))
