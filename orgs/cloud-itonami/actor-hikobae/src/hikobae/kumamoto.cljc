(ns hikobae.kumamoto
  "令和8年熊本地震 (2026-07-28) のインスタンス。

   実測を :measured に、実測から出した値を :derived に、残りを :assumed に置く。
   3つを1つの map に潰さない —— 潰すと、どの数が出典を持っているかが
   出力から読めなくなる。"
  (:require [hikobae.facts :as facts]
            [hikobae.calibrate :as cal]
            [hikobae.model :as model]))

(defn measured-params
  "観測事実からそのまま入る曝露パラメータ。"
  [f]
  {:exposure/shelter-peak      (double (facts/observed-number f :shelter/peak-population))
   :exposure/water-peak-outage (double (facts/observed-number f :water/peak-outage))
   :exposure/power-peak-outage (double (facts/observed-number f :power/peak-outage))
   :exposure/buildings-severe  (double (+ (facts/observed-number f :buildings/fully-destroyed)
                                          (facts/observed-number f :buildings/half-destroyed)))
   :exposure/buildings-damaged-total (double (facts/observed-number f :buildings/damaged-total))})

(defn params
  "熊本2026の校正済みパラメータ。副作用なし。"
  ([] (params (facts/kumamoto-2026)))
  ([f]
   (:params (cal/fit-report f (merge model/default-params
                                     (measured-params f)
                                     {:model/name "令和8年熊本地震 復旧ダイナミクス"
                                      ;; 発災は真夏。8/23 予報 38℃、熱中症警戒下。
                                      ;; 猛暑倍率を既定より重くしているのは実測の気象条件による
                                      ;; （倍率そのものの値には出典が無い —— :assumed）。
                                      :health/heat-multiplier 2.5
                                      :sim/stop-days 365})))))

(defn report
  "校正の中身と当てはまりの質。`params` と違い、質まで返す。"
  ([] (report (facts/kumamoto-2026)))
  ([f] (cal/fit-report f (merge model/default-params (measured-params f)
                                {:model/name "令和8年熊本地震 復旧ダイナミクス"
                                 :health/heat-multiplier 2.5
                                 :sim/stop-days 365}))))

(defn run
  ([] (run (params)))
  ([p] (model/run p)))

;; ---------------------------------------------------------------------------
;; 反実仮想シナリオ。**絶対値ではなく差を読むための道具**。
;; ---------------------------------------------------------------------------

(def scenarios
  "各シナリオは実際に記録された介入に対応する。想像上の施策を並べない。"
  {:baseline
   {:label "実際の経過に校正したベースライン"
    :overrides {}}

   :digital-certificates-day-10
   {:label "罹災証明のオンライン申請を day 10 に全市町村へ導入"
    :basis "宇城市・八代市で実際に導入され『滞留を解消』と記録されている（内閣府 2026-08-23）。導入日は原典に無いため day 10 は仮定。"
    :overrides {:certificate/digital-day 10.0}}

   :double-assessment-teams
   {:label "住家被害認定の調査班を2倍に（対口支援の増派）"
    :basis "石川県・岩手県・UR が実際に職員を派遣している。倍率は仮定。"
    :overrides {:assessment/teams-multiplier 2.0}}

   :shelter-quality-fully-supplied
   {:label "エアコン・段ボールベッド・パーティションが全避難所に行き渡る"
    :basis "プッシュ型支援でエアコン1,075台・段ボールベッド5,000個が到着済み（内閣府 2026-08-23）。『行き渡った』状態の定義は仮定。"
    :overrides {:health/shelter-quality-relief 1.0}}

   :faster-temporary-housing
   {:label "応急仮設の着工速度を2倍に"
    :basis "着工系列は実測で取れている。倍率は仮定。"
    :overrides {:temp-housing/start-rate-multiplier 2.0}}})

(defn- apply-overrides [p overrides]
  (cond-> (merge p (dissoc overrides :assessment/teams-multiplier :temp-housing/start-rate-multiplier))
    (:assessment/teams-multiplier overrides)
    (update :assessment/teams * (:assessment/teams-multiplier overrides))
    (:temp-housing/start-rate-multiplier overrides)
    (update :temp-housing/start-rate * (:temp-housing/start-rate-multiplier overrides))))

(defn scenario-params [base k]
  (apply-overrides base (get-in scenarios [k :overrides])))

(defn compare-scenarios
  "各シナリオの結果指標。**差だけを読むこと** —— 間接死の係数は :assumed で、
   絶対値には出典が無い。"
  ([] (compare-scenarios (params)))
  ([base]
   (into {}
         (for [[k spec] scenarios
               :let [r (model/run (scenario-params base k))]]
           [k {:label (:label spec)
               :basis (:basis spec)
               :person-days-displaced (model/final r "cumulative_person_days_displaced")
               :indirect-deaths (model/final r "cumulative_indirect_deaths")
               :shelter-at-day-90 (model/series-at r "shelter_population" 90)
               :certificates-at-day-60 (model/series-at r "certificates_issued" 60)
               :in-temp-at-day-180 (model/series-at r "in_temporary_housing" 180)}]))))
