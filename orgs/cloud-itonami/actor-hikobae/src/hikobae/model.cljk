(ns hikobae.model
  "地震被害後の復旧を、OASIS XMILE 1.0 の stock-and-flow として書いた正本。

   置き場所の理由: XMILE の意味論・方程式言語・シミュレータは
   `kotoba-lang/org-oasis-open-xmile` が既に持っている（ADR-2607072350 が
   kotoba-lang における system-dynamics 計算の正本と定めている）。ここが
   足すのは**災害復旧という領域の構造**だけで、積分器も方程式パーサも
   書かない。`dynamics.xmile` が同じ境界を引いているのと同型。

   モデルが答える問い:
     「地震の後、何が何を待っているか。待ち行列を1本短くすると、
       どこで何人分の結果が変わるか。」

   モデルが答えない問い:
     「何人死ぬか」。間接死の係数は現時点で校正できる実データが無く
     （災害関連死の認定は市町村審査会を経るため発災1か月では確定しない）、
     :assumed のまま置いてある。絶対値ではなく**介入前後の差**だけを読むこと。

   単位: 時間=日、人=persons、住家=buildings、世帯=households、住戸=units。
   day 0 = 本震。"
  (:require [xmile.model :as m]
            [xmile.validate :as v]
            [xmile.execute :as ex]))

;; ---------------------------------------------------------------------------
;; パラメータ
;;
;; :measured — 実観測（`data/*.facts.edn` 由来。出典と時点がある）
;; :derived  — 実観測から算術で出したもの（`hikobae.calibrate`）
;; :assumed  — 出典が無い。**必ず :basis に「なぜその値なのか」を書く。**
;;             :assumed の値を根拠に絶対量を主張しない。
;; ---------------------------------------------------------------------------

(def parameter-provenance
  "各パラメータの出所。`hikobae.facts/check-provenance` がこの表と
   実際のパラメータ集合を突き合わせ、出所の宣言が無いパラメータを拒否する。
   宣言の無い数を黙って通さないための表であって、飾りではない。"
  {;; --- 曝露（実測） ---
   :exposure/shelter-peak                {:kind :measured :ref :shelter/peak-population}
   :exposure/water-peak-outage           {:kind :measured :ref :water/peak-outage}
   :exposure/power-peak-outage           {:kind :measured :ref :power/peak-outage}
   :exposure/buildings-severe            {:kind :measured :ref [:buildings/fully-destroyed :buildings/half-destroyed]}
   :exposure/buildings-damaged-total     {:kind :measured :ref :buildings/damaged-total}

   ;; --- 実測系列から導出 ---
   :shelter/longterm-fraction            {:kind :derived :from :shelter/population
                                          :how "観測系列の尾（発災20日以降の平坦部）をピーク値で割った比。住まいを失って避難所に留まる層の割合。"}
   :shelter/return-rate                  {:kind :derived :from :shelter/population
                                          :how "初期の急減部（day 2〜17）の指数回帰の傾き。ライフライン復旧で自宅に戻れる層の退所率。"}
   :water/network-fraction               {:kind :derived :from :water/households-without-service
                                          :how "速い塊が尽きた時点（踊り場の水準）を peak で割った残りの補数。本管復旧で戻る層の割合。"}
   :water/network-capacity               {:kind :derived :from :water/households-without-service
                                          :how "peak から踊り場水準までを、そこに要した日数で割った日次復旧戸数。"}
   :water/base-restore-capacity          {:kind :derived :from :water/households-without-service
                                          :how "踊り場区間（本管が繋がった後）の平均日次復旧戸数。戸別作業の素の速度。"}
   :water/surge-restore-capacity         {:kind :derived :from :water/households-without-service
                                          :how "day 17〜20 の日次復旧戸数から base を引いた増分。井戸水対応チーム設置(8/11)後の増強分に対応する。"}
   :temp-housing/start-rate              {:kind :derived :from :temporary-housing/units-started-cumulative
                                          :how "着工累積系列の傾き（戸/日）。"}

   ;; --- 仮定（出典なし。basis を必ず読むこと） ---
   :water/surge-day                      {:kind :assumed :basis "「井戸水対応チーム」設置日 8/11 = day 14（内閣府の記録にある日付）。増強がいつ効き始めたかは記録が無いので、遅れ :water/surge-lag で吸収する。"}
   :water/surge-lag                      {:kind :assumed :basis "組織を作ってから現場の復旧速度が変わるまでの遅れ。3日とした。系列上、8/14→8/17 で急落しているので 14+3=17 に整合する。"}
   :power/restore-capacity               {:kind :assumed :basis "電力は26日時点で復旧済みとしか記録が無く、途中の系列が公表されていない。架空線の復旧が数日規模であることから、ピークを7日で捌く能力とした。順位（電力が最速）だけを主張し、日数の絶対値は主張しない。"}
   :assessment/teams                     {:kind :assumed :basis "住家被害認定調査の投入人数は公表されていない。被災自治体の建築職＋応援職員の規模感として置いた値であり、感度分析の対象にする変数。"}
   :assessment/surveys-per-team-day      {:kind :assumed :basis "1班1日あたりの外観調査棟数。内閣府『災害に係る住家の被害認定基準運用指針』の第一次調査（外観目視）を前提とした規模感。実測に置き換えること。"}
   :certificate/processing-days          {:kind :assumed :basis "調査完了から証明書交付までの事務処理日数。公表系列が無い。"}
   :certificate/processing-days-digital  {:kind :assumed :basis "オンライン申請導入後の同上。宇城市・八代市で『滞留を解消』とあるが、前後の日数は公表されていない。"}
   :certificate/digital-day              {:kind :assumed :basis "オンライン申請の導入日が原典に無い（facts の :event/unmeasured に記録済み）。既定では導入なし(無限大)とし、反実仮想シナリオでのみ有限値を入れる。"}
   :temp-housing/build-lead-days         {:kind :assumed :basis "着工から入居可能までの日数。着工日は実測で取れているが完成日がまだ公表されていない。"}
   :temp-housing/persons-per-unit        {:kind :assumed :basis "応急仮設1戸あたりの入居人数。"}
   :health/indirect-death-rate           {:kind :assumed :basis "避難者1人1日あたりの災害関連死の発生率。2026年熊本では関連死が未確定のため校正できない。絶対値を主張せず、介入前後の差だけを読むこと。"}
   :health/heat-multiplier               {:kind :assumed :basis "猛暑下での上記の倍率。2026年熊本は真夏の発災で 8/23 予報 38℃、熱中症警戒下にある。倍率の値そのものに出典は無い。"}
   :health/shelter-quality-relief        {:kind :assumed :basis "エアコン・段ボールベッド・パーティションが行き渡ったときに上記倍率が下がる幅。"}
   :outside/initial-population           {:kind :assumed :basis "避難所外避難者（車中泊・親族宅）の人数。**構造的に観測されていない**（facts の :event/unmeasured）。避難所人口に対する比として置いてある。2016年熊本で車中泊が関連死の温床になったため 0 と置くことはしない。"}})

(def default-params
  "既定パラメータ。:assumed のものは上の parameter-provenance に basis がある。
   `hikobae.kumamoto/params` が :measured / :derived をここへ上書きする。"
  {:sim/stop-days 365
   :sim/dt 0.25
   :sim/method :euler

   :exposure/shelter-peak 10000.0
   :exposure/water-peak-outage 100000.0
   :exposure/power-peak-outage 50000.0
   :exposure/buildings-severe 4000.0
   :exposure/buildings-damaged-total 24000.0

   :shelter/longterm-fraction 0.28
   :shelter/return-rate 0.07

   :water/network-fraction 0.68
   :water/network-capacity 5700.0
   :water/base-restore-capacity 3000.0
   :water/surge-restore-capacity 6000.0
   :water/surge-day 14.0
   :water/surge-lag 3.0
   :power/restore-capacity 7000.0

   :assessment/teams 40.0
   :assessment/surveys-per-team-day 12.0
   :certificate/processing-days 14.0
   :certificate/processing-days-digital 3.0
   :certificate/digital-day 1.0e9          ; 既定では導入なし

   :temp-housing/start-rate 12.0
   :temp-housing/build-lead-days 60.0
   :temp-housing/persons-per-unit 2.3

   :health/indirect-death-rate 4.0e-6
   :health/heat-multiplier 2.5
   :health/shelter-quality-relief 0.0      ; 0=物資が届いていない, 1=行き渡った

   :outside/initial-population 3000.0})

(defn- n
  "パラメータを XMILE の方程式に埋める数値literal へ。"
  [params k]
  (let [v (get params k)]
    (assert (number? v) (str "hikobae.model: parameter " k " is missing or not a number"))
    (str (double v))))

;; ---------------------------------------------------------------------------
;; モデル本体
;; ---------------------------------------------------------------------------

(defn build-model
  "パラメータから XMILE の :xmile/model を組む。純粋関数。"
  [params]
  (let [p (merge default-params params)
        num (partial n p)]
    (-> (m/model "post_earthquake_recovery")
        (m/set-sim-specs (m/sim-specs 0.0 (double (:sim/stop-days p))
                                      {:xmile/dt (double (:sim/dt p))
                                       :xmile/method (:sim/method p)
                                       :xmile/time-units "days"}))

        ;; ===================================================================
        ;; ライフライン — 上水。**2区画**に分ける。
        ;;
        ;;   water_out_network : 本管・配水池の復旧で戻る層（速く、塊で戻る）
        ;;   water_out_service : 引込管・漏水・井戸など戸別の作業が要る層（遅い尾）
        ;;
        ;; 1区画で書くと単調な直線になり、実観測が持つ「初期の急減 → 踊り場 →
        ;; もう一度の急落」を再現できない。踊り場は怠慢ではなく、**残っている
        ;; 世帯の壊れ方が違う**ことの現れである（本管が繋がっても末端が出ない）。
        ;; 避難所人口を2区画に分けたのと同じ構造で、同じ理由による。
        ;;
        ;; ⚠ 個々の段差（ある日に本管が1本繋がって数万戸が同時に戻る）は
        ;; 連続レートのモデルでは再現しないし、しようとしない。合わせるのは
        ;; 形（速い塊 + 残る尾 + 中途の増強）であって、日ごとの塊ではない。
        ;; ===================================================================
        (m/add-variable
         (m/stock "water_out_network"
                  (str (num :exposure/water-peak-outage) " * " (num :water/network-fraction))
                  {:xmile/outflows #{"water_network_restoration"}
                   :xmile/non-negative? true :xmile/units "households"}))
        (m/add-variable
         (m/flow "water_network_restoration"
                 (str "MIN(water_out_network / DT, " (num :water/network-capacity) ")")
                 {:xmile/non-negative? true :xmile/units "households/day"}))

        (m/add-variable
         (m/stock "water_out_service"
                  (str (num :exposure/water-peak-outage) " * (1 - " (num :water/network-fraction) ")")
                  {:xmile/outflows #{"water_service_restoration"}
                   :xmile/non-negative? true :xmile/units "households"}))
        (m/add-variable
         (m/aux "water_service_capacity"
                (str (num :water/base-restore-capacity)
                     " + " (num :water/surge-restore-capacity)
                     " * SMTH1(STEP(1, " (num :water/surge-day) "), " (num :water/surge-lag) ", 0)")
                {:xmile/units "households/day"
                 :xmile/doc "戸別作業の日次能力。増強分は井戸水対応チーム設置(day 14)後に遅れて立ち上がる。"}))
        (m/add-variable
         (m/flow "water_service_restoration"
                 "MIN(water_out_service / DT, water_service_capacity)"
                 {:xmile/non-negative? true :xmile/units "households/day"}))

        (m/add-variable
         (m/aux "households_without_water"
                "water_out_network + water_out_service"
                {:xmile/units "households"
                 :xmile/doc "国土交通省が日次で公表する『断水中』に対応する量。校正の突合先。"}))

        ;; ライフライン — 電力（最速で戻る。順位だけを主張する）
        (m/add-variable
         (m/stock "households_without_power" (num :exposure/power-peak-outage)
                  {:xmile/outflows #{"power_restoration"}
                   :xmile/non-negative? true :xmile/units "households"}))
        (m/add-variable
         (m/flow "power_restoration"
                 (str "MIN(households_without_power / DT, " (num :power/restore-capacity) ")")
                 {:xmile/non-negative? true :xmile/units "households/day"}))

        ;; ライフラインの可用率（0..1）。自宅へ戻れるかを左右する。
        (m/add-variable
         (m/aux "lifeline_availability"
                (str "1 - 0.5 * households_without_water / " (num :exposure/water-peak-outage)
                     " - 0.5 * households_without_power / " (num :exposure/power-peak-outage))
                {:xmile/units "dimensionless"}))

        ;; ===================================================================
        ;; 行政 — 被害認定調査 → 罹災証明。復旧全体の律速。
        ;;
        ;; 調査は有資格者が1棟ずつ見る作業なので人数でしか速くならない。
        ;; 交付は事務処理なので、オンライン化で遅れを縮められる。
        ;; **この2つは別の資源で律速されている** — ここを分けないと
        ;; 「オンライン化すれば全部速くなる」という誤った結論が出る。
        ;; ===================================================================
        (m/add-variable
         (m/stock "assessment_queue" (num :exposure/buildings-damaged-total)
                  {:xmile/outflows #{"assessment_rate"}
                   :xmile/non-negative? true :xmile/units "buildings"}))
        (m/add-variable
         (m/aux "assessment_capacity"
                (str (num :assessment/teams) " * " (num :assessment/surveys-per-team-day))
                {:xmile/units "buildings/day"}))
        (m/add-variable
         (m/flow "assessment_rate"
                 "MIN(assessment_queue / DT, assessment_capacity)"
                 {:xmile/non-negative? true :xmile/units "buildings/day"}))

        (m/add-variable
         (m/aux "certificate_delay"
                (str "IF TIME >= " (num :certificate/digital-day)
                     " THEN " (num :certificate/processing-days-digital)
                     " ELSE " (num :certificate/processing-days))
                {:xmile/units "days"
                 :xmile/doc "調査完了から交付までの事務処理日数。オンライン申請導入で短縮する。"}))
        (m/add-variable
         (m/flow "certificate_rate"
                 "DELAY1(assessment_rate, certificate_delay, 0)"
                 {:xmile/non-negative? true :xmile/units "buildings/day"}))
        (m/add-variable
         (m/stock "certificates_issued" "0"
                  {:xmile/inflows #{"certificate_rate"}
                   :xmile/non-negative? true :xmile/units "buildings"}))
        (m/add-variable
         (m/aux "certificate_coverage"
                (str "MIN(1, certificates_issued / " (num :exposure/buildings-damaged-total) ")")
                {:xmile/units "dimensionless"
                 :xmile/doc "被害住家のうち罹災証明が出た割合。下流の全工程がこれに掛かる。"}))

        ;; ===================================================================
        ;; 住まい — 応急仮設の供給。着工は罹災証明の進捗にゲートされる。
        ;; ===================================================================
        (m/add-variable
         (m/flow "temp_start_rate"
                 (str (num :temp-housing/start-rate) " * certificate_coverage")
                 {:xmile/non-negative? true :xmile/units "units/day"}))
        (m/add-variable
         (m/stock "temp_units_pipeline" "0"
                  {:xmile/inflows #{"temp_start_rate"}
                   :xmile/outflows #{"temp_completion_rate"}
                   :xmile/non-negative? true :xmile/units "units"}))
        (m/add-variable
         (m/flow "temp_completion_rate"
                 (str "DELAY3(temp_start_rate, " (num :temp-housing/build-lead-days) ", 0)")
                 {:xmile/non-negative? true :xmile/units "units/day"}))
        (m/add-variable
         (m/stock "temp_units_available" "0"
                  {:xmile/inflows #{"temp_completion_rate"}
                   :xmile/outflows #{"temp_occupancy_rate"}
                   :xmile/non-negative? true :xmile/units "units"}))

        ;; ===================================================================
        ;; 避難者 — 2区画に分ける。
        ;;
        ;;   shelter_returnable : 自宅が住めるので、ライフラインが戻れば帰る
        ;;   shelter_longterm   : 自宅を失ったので、仮設が建つまで帰れない
        ;;
        ;; 1区画で書くと指数減衰しか出ず、実観測の「速い減少のあとに残る尾」を
        ;; 再現できない。尾は忍耐の問題ではなく、住宅在庫の問題である。
        ;; ===================================================================
        (m/add-variable
         (m/stock "shelter_returnable"
                  (str (num :exposure/shelter-peak) " * (1 - " (num :shelter/longterm-fraction) ")")
                  {:xmile/outflows #{"shelter_return_home"}
                   :xmile/non-negative? true :xmile/units "persons"}))
        (m/add-variable
         (m/flow "shelter_return_home"
                 (str "shelter_returnable * " (num :shelter/return-rate) " * lifeline_availability")
                 {:xmile/non-negative? true :xmile/units "persons/day"}))

        (m/add-variable
         (m/stock "shelter_longterm"
                  (str (num :exposure/shelter-peak) " * " (num :shelter/longterm-fraction))
                  {:xmile/outflows #{"shelter_to_temp"}
                   :xmile/non-negative? true :xmile/units "persons"}))
        (m/add-variable
         (m/flow "shelter_to_temp"
                 (str "MIN(shelter_longterm / DT, temp_units_available * "
                      (num :temp-housing/persons-per-unit) " / DT)")
                 {:xmile/non-negative? true :xmile/units "persons/day"}))
        (m/add-variable
         (m/flow "temp_occupancy_rate"
                 (str "shelter_to_temp / " (num :temp-housing/persons-per-unit))
                 {:xmile/non-negative? true :xmile/units "units/day"}))
        (m/add-variable
         (m/stock "in_temporary_housing" "0"
                  {:xmile/inflows #{"shelter_to_temp"}
                   :xmile/non-negative? true :xmile/units "persons"}))

        (m/add-variable
         (m/aux "shelter_population"
                "shelter_returnable + shelter_longterm"
                {:xmile/units "persons"
                 :xmile/doc "内閣府が日次で公表している『避難者数』に対応する量。校正の突合先。"}))

        ;; 避難所外避難者（車中泊等）。観測されないが存在する。
        ;; ライフラインが戻れば帰る点は returnable と同じだが、
        ;; 保健の巡回が届きにくいぶん健康の risk 荷重が重い。
        (m/add-variable
         (m/stock "displaced_outside" (num :outside/initial-population)
                  {:xmile/outflows #{"outside_return_home"}
                   :xmile/non-negative? true :xmile/units "persons"}))
        (m/add-variable
         (m/flow "outside_return_home"
                 (str "displaced_outside * " (num :shelter/return-rate) " * lifeline_availability")
                 {:xmile/non-negative? true :xmile/units "persons/day"}))

        ;; ===================================================================
        ;; 健康 — 災害関連死。**このモデルの要点**。
        ;;
        ;; 直接死は地震が決めるが、間接死は「何人が」×「何日」×「どんな環境で」
        ;; 避難したかで決まる。つまり上の全部の待ち行列の下流にある。
        ;; 係数は :assumed なので絶対値を読まず、シナリオ間の差だけを読むこと。
        ;; ===================================================================
        (m/add-variable
         (m/aux "displaced_population"
                "shelter_returnable + shelter_longterm + displaced_outside"
                {:xmile/units "persons"}))
        (m/add-variable
         (m/aux "environment_risk_multiplier"
                (str "1 + (" (num :health/heat-multiplier) " - 1) * (1 - "
                     (num :health/shelter-quality-relief) ")")
                {:xmile/units "dimensionless"
                 :xmile/doc "猛暑・寒冷等の環境荷重。エアコン/段ボールベッド/パーティションが行き渡ると下がる。"}))
        (m/add-variable
         (m/flow "indirect_death_rate"
                 (str "(shelter_returnable + shelter_longterm) * " (num :health/indirect-death-rate)
                      " * environment_risk_multiplier"
                      " + displaced_outside * " (num :health/indirect-death-rate)
                      " * environment_risk_multiplier * 1.5")
                 {:xmile/non-negative? true :xmile/units "persons/day"
                  :xmile/doc "避難所外は保健の巡回が届きにくいぶん係数を重くしている(1.5)。この 1.5 自体に出典は無い。"}))
        (m/add-variable
         (m/stock "cumulative_indirect_deaths" "0"
                  {:xmile/inflows #{"indirect_death_rate"}
                   :xmile/non-negative? true :xmile/units "persons"}))

        ;; 累積の避難者・日（person-days displaced）。
        ;; 復旧の「遅さ」を1つの数にする最も素直な指標。
        (m/add-variable
         (m/flow "displacement_accrual" "displaced_population"
                 {:xmile/non-negative? true :xmile/units "person-days/day"}))
        (m/add-variable
         (m/stock "cumulative_person_days_displaced" "0"
                  {:xmile/inflows #{"displacement_accrual"}
                   :xmile/non-negative? true :xmile/units "person-days"})))))

(defn build-doc
  "XMILE 1.0 の完全なドキュメント（header + sim_specs + model）。
   `xmile.xml/emit-string` にそのまま渡せる。"
  [params]
  (let [mdl (build-model params)]
    {:xmile/header {:xmile/vendor "cloud-itonami"
                    :xmile/product {:xmile/name "hikobae" :xmile/version "1"}
                    :xmile/name (or (:model/name params) "post-earthquake recovery")}
     :xmile/sim-specs (:xmile/sim-specs mdl)
     :xmile/models [mdl]}))

(defn problems
  "構造検証。`:error` があればモデルは XMILE として不正。"
  [params]
  (v/validate (build-model params)))

(defn valid? [params] (v/valid? (problems params)))

(defn run
  "シミュレーション実行。{:xmile/times [..] :xmile/series {name [..]}}。"
  [params]
  (ex/run (build-model params)))

(defn series-at
  "系列 `nm` の day `d` における値。dt が 1 未満でも日で引ける。"
  [result nm d]
  (let [times (:xmile/times result)
        vals (get (:xmile/series result) nm)
        idx (first (keep-indexed (fn [i t] (when (>= t (- d 1e-9)) i)) times))]
    (when (and idx vals) (nth vals idx nil))))

(defn final
  "系列 `nm` の最終値。"
  [result nm]
  (last (get (:xmile/series result) nm)))
