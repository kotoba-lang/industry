(ns hikobae.hikobae-test
  "検査。

   方針（root CLAUDE.md『検査を書く前・緑を信じる前の6問』）:
     - 入力が無いときに pass しないこと
     - 実行できないときに pass と同じ値を返さないこと
     - **両方向を出したことがあること** —— 構造を壊した対照が実際に赤くなるまで、
       その検査が何かを識別しているとは言わない
     - 自分が名乗っている理由で落ちること"
  (:require [clojure.test :refer [deftest is testing]]
            [hikobae.facts :as facts]
            [hikobae.model :as model]
            [hikobae.calibrate :as cal]
            [hikobae.kumamoto :as kumamoto]
            [hikobae.loops :as loops]
            [hikobae.emit :as emit]))

;; 校正は重いので1回だけ回して使い回す。
(def ^:private F (delay (facts/kumamoto-2026)))
(def ^:private REP (delay (kumamoto/report @F)))
(def ^:private P (delay (:params @REP)))
(def ^:private R (delay (model/run @P)))

;; ---------------------------------------------------------------------------
;; 1. データの床 —— 入力が無いことを clean として通さない
;; ---------------------------------------------------------------------------

(deftest facts-load-and-are-not-empty
  (testing "観測が実在すること。0 件を『問題なし』として通さない"
    (let [f @F]
      (is (<= 15 (count (:event/observations f)))
          "観測が想定より少ない — ファイルが切れていないか")
      (is (<= 4 (count (:event/trajectories f))))
      (is (<= 10 (count (:event/interventions f))))
      (doseq [[id traj] (:event/trajectories f)]
        (is (<= 7 (count (:points traj)))
            (str "系列 " id " の点が少なすぎる"))
        (is (some? (:source-ref traj))
            (str "系列 " id " に出典が無い"))))))

(deftest every-observation-is-dated-and-sourced
  (testing "出典と時点を持たない観測を1つも許さない"
    (doseq [o (:event/observations @F)]
      (is (some? (:as-of o)) (str (:id o) " に時点が無い"))
      (is (some? (:source-ref o)) (str (:id o) " に出典が無い"))
      (is (contains? (:event/sources @F) (:source-ref o))
          (str (:id o) " の出典 " (:source-ref o) " が :event/sources に無い")))))

(deftest unmeasured-is-not-zero
  (testing "未確定を 0 に潰していないこと。このモデルで最も避けたい誤り"
    (is (= :under-investigation
           (facts/observed-value @F :deaths/confirmed-indirect))
        "災害関連死は発災1か月では確定しない。0 と書いてはならない")
    (is (nil? (facts/observed-number @F :deaths/confirmed-indirect))
        "数値として読もうとしたら nil が返ること — 呼び出し側に未測定が伝わる")
    (is (contains? (facts/unmeasured-ids @F) :displaced/outside-shelters)
        "避難所外避難者が未測定として宣言されていること")))

;; ---------------------------------------------------------------------------
;; 2. パラメータの出所 —— 宣言の無い数を通さない
;; ---------------------------------------------------------------------------

(deftest all-parameters-declare-provenance
  (let [r (facts/check-provenance @P model/parameter-provenance)]
    (is (:ok? r) (str "出所の宣言が無いパラメータ: " (pr-str (:undeclared r))))
    (is (pos? (get-in r [:counts :measured] 0)) "実測パラメータが1つも無い")
    (is (pos? (get-in r [:counts :derived] 0)) "導出パラメータが1つも無い")))

(deftest provenance-check-actually-rejects
  (testing "負の対照: 宣言の無いキーを足したら実際に落ちること"
    (let [bad (assoc @P :made-up/parameter 1.0)
          r (facts/check-provenance bad model/parameter-provenance)]
      (is (not (:ok? r)) "宣言の無いパラメータを通してしまっている")
      (is (contains? (:undeclared r) :made-up/parameter))
      (is (thrown? clojure.lang.ExceptionInfo
                   (facts/assert-provenance! bad model/parameter-provenance))))))

(deftest measured-parameters-come-from-facts
  (testing "曝露パラメータが観測と一致すること（手打ちの数が紛れていない）"
    (let [f @F p @P]
      (is (== (:exposure/shelter-peak p)
              (facts/observed-number f :shelter/peak-population)))
      (is (== (:exposure/water-peak-outage p)
              (facts/observed-number f :water/peak-outage)))
      (is (== (:exposure/buildings-damaged-total p)
              (facts/observed-number f :buildings/damaged-total))))))

;; ---------------------------------------------------------------------------
;; 3. モデルの構造
;; ---------------------------------------------------------------------------

(deftest model-is-structurally-valid-xmile
  (let [ps (model/problems @P)]
    (is (empty? (filter #(= :error (:severity %)) ps))
        (str "XMILE として不正: " (pr-str ps)))))

(deftest model-runs-and-conserves-people
  (testing "避難者は消えず、住まいへ移るか、その場に残る"
    (let [r @R
          peak (:exposure/shelter-peak @P)
          final-shelter (model/final r "shelter_population")
          in-temp (model/final r "in_temporary_housing")]
      (is (pos? (count (:xmile/times r))))
      (is (<= 0 final-shelter peak))
      (is (<= 0 in-temp peak))
      (is (>= (+ final-shelter in-temp) 0.0)))))

(deftest stocks-never-go-negative
  (let [r @R]
    (doseq [nm ["shelter_returnable" "shelter_longterm" "water_out_network"
                "water_out_service" "assessment_queue" "temp_units_available"
                "displaced_outside" "cumulative_indirect_deaths"]]
      (is (every? #(>= (double %) -1e-9) (get (:xmile/series r) nm))
          (str nm " が負になっている")))))

;; ---------------------------------------------------------------------------
;; 4. 実観測への当てはまり —— と、その負の対照
;; ---------------------------------------------------------------------------

(def shelter-nrmse-ceiling 0.12)
(def water-nrmse-ceiling 0.25)

(deftest calibration-reproduces-observed-series
  (let [fit (:fit @REP)]
    (is (<= (get-in fit [:shelter :nrmse]) shelter-nrmse-ceiling)
        (str "避難者数の当てはまりが悪化した: " (get-in fit [:shelter :nrmse])))
    (is (<= (get-in fit [:water :nrmse]) water-nrmse-ceiling)
        (str "断水戸数の当てはまりが悪化した: " (get-in fit [:water :nrmse])))
    (is (= 11 (get-in fit [:shelter :n])) "突合した点の数が変わった")
    (is (= 9 (get-in fit [:water :n])))))

(deftest two-compartment-shelter-beats-one-compartment
  (testing "負の対照: 尾（住まいを失った層）を消すと当てはまりが**悪くなる**こと。
            これが赤くならないなら、2区画という構造は何もしていない"
    (let [pts (facts/trajectory @F :shelter/population)
          fitted (cal/nrmse pts #(model/series-at @R "shelter_population" %))
          flat (model/run (assoc @P :shelter/longterm-fraction 0.0))
          collapsed (cal/nrmse pts #(model/series-at flat "shelter_population" %))]
      (is (> collapsed fitted)
          (str "1区画のほうが当てはまってしまっている fitted=" fitted " collapsed=" collapsed))
      (is (> collapsed (* 1.5 fitted))
          "差が小さすぎる — 2区画構造が実質的に効いていない"))))

(deftest two-compartment-water-beats-one-compartment
  (testing "負の対照: 上水も同じ。本管/戸別を1本に潰すと悪くなること"
    (let [pts (facts/trajectory @F :water/households-without-service)
          fitted (cal/nrmse pts #(model/series-at @R "households_without_water" %))
          ;; 全部を『本管』に寄せる = 戸別の尾を消す
          flat (model/run (assoc @P :water/network-fraction 1.0))
          collapsed (cal/nrmse pts #(model/series-at flat "households_without_water" %))]
      (is (> collapsed fitted)
          (str "1区画のほうが当てはまってしまっている fitted=" fitted " collapsed=" collapsed)))))

(deftest calibration-fails-loudly-on-missing-observations
  (testing "実行できないときに pass と同じ値を返さないこと"
    (let [empty-facts (assoc-in @F [:event/trajectories :shelter/population :points] [])]
      (is (thrown? Throwable (cal/shelter-longterm-fraction empty-facts))
          "観測ゼロで黙って値を返している"))
    (is (thrown? Throwable
                 (cal/linear-rate (facts/trajectory @F :water/households-without-service) 1 999))
        "存在しない日を指定して黙って通っている")))

;; ---------------------------------------------------------------------------
;; 5. 構造的不変量 —— world-regimes.edn が主張していること
;; ---------------------------------------------------------------------------

(defn- day-cleared [r nm]
  (let [times (:xmile/times r) vals (get (:xmile/series r) nm)]
    (some (fn [[t v]] (when (< (double v) 1.0) t)) (map vector times vals))))

(deftest power-restores-before-water
  (testing ":inv/lifeline-restoration-order —— 電力が上水より先に戻ること"
    (let [r @R
          p (day-cleared r "households_without_power")
          w (day-cleared r "households_without_water")]
      (is (some? p) "電力が復旧しきらない")
      (is (some? w) "上水が復旧しきらない")
      (is (< p w) (str "順序が逆転している power=" p " water=" w)))))

(deftest every-invariant-states-how-to-falsify-it
  (testing "反証条件を書けない主張を載せない"
    (let [w (facts/world)]
      (is (<= 6 (count (:world/invariants w))))
      (doseq [i (:world/invariants w)]
        (is (some? (:falsified-by i)) (str (:id i) " に :falsified-by が無い"))
        (is (some? (:because i)) (str (:id i) " に理由が無い"))))))

(deftest world-instances-do-not-fabricate-quantities
  (testing "量を持っていないなら :unmeasured と書いてあり、測り方が添えてあること"
    (let [w (facts/world)]
      (doseq [inst (:world/instances w)]
        (doseq [[k v] (:parameters inst)]
          (when (= :unmeasured v)
            (is (some? (get-in inst [:how-to-measure k]))
                (str (:id inst) " / " k " が :unmeasured なのに測り方が無い"))))
        (is (some? (:regime inst)) (str (:id inst) " にレジーム分類が無い"))))))

;; ---------------------------------------------------------------------------
;; 6. 依存グラフ —— 「認定が律速」という主張そのもの
;; ---------------------------------------------------------------------------

(deftest assessment-gates-the-most-downstream-work
  (testing "被害認定調査が最も多くの下流業務をゲートしていること"
    (let [ops (facts/operations)
          down (facts/downstream-of ops :op/damage-assessment)
          counts (into {} (for [o (:taxonomy/operations ops)
                                :when (not (:synthetic o))]
                            [(:id o) (count (facts/downstream-of ops (:id o)))]))
          top (key (apply max-key val counts))]
      (is (<= 8 (count down))
          (str "被害認定調査の下流が想定より少ない: " (count down)))
      (is (contains? #{:op/damage-assessment :op/certificate-issuance} top)
          (str "最上流の律速が認定系でない: " top)))))

(deftest every-operation-names-its-binding-resource
  (let [ops (facts/operations)]
    (doseq [o (:taxonomy/operations ops)
            :when (not (:synthetic o))]
      (is (some? (:binding-resource o)) (str (:id o) " に律速資源が無い"))
      (is (some? (:scope o)) (str (:id o) " に :scope が無い")))
    (is (<= 35 (count (:taxonomy/operations ops))))
    (is (<= 45 (count (:taxonomy/resources ops))))))

(deftest gated-operations-reference-real-operations
  (testing "依存先が実在すること（綴り間違いを黙って通さない）"
    (let [ops (facts/operations)
          ids (set (map :id (:taxonomy/operations ops)))]
      (doseq [o (:taxonomy/operations ops)
              g (:gated-by o)]
        (is (contains? ids g) (str (:id o) " が存在しない業務 " g " を待っている"))))))

;; ---------------------------------------------------------------------------
;; 7. 介入の効き —— 効くこと と 効かないこと の両方
;; ---------------------------------------------------------------------------

(deftest digital-certificates-shorten-displacement
  (testing "罹災証明のオンライン化が避難者・日を減らすこと"
    (let [base (model/run @P)
          fast (model/run (kumamoto/scenario-params @P :digital-certificates-day-10))]
      (is (< (model/final fast "cumulative_person_days_displaced")
             (model/final base "cumulative_person_days_displaced"))
          "オンライン化が効いていない")
      (is (> (model/final fast "certificates_issued")
             (model/final base "certificates_issued"))))))

(deftest intervention-after-the-horizon-changes-nothing
  (testing "負の対照: 期間外に導入した介入が何も変えないこと。
            これが赤くなるなら、上の緑は介入以外の理由で出ている"
    (let [base (model/run @P)
          never (model/run (assoc @P :certificate/digital-day 1.0e9))]
      (is (== (model/final base "cumulative_person_days_displaced")
              (model/final never "cumulative_person_days_displaced"))))))

(deftest shelter-quality-reduces-indirect-deaths
  (testing "避難所環境の改善が関連死を減らすこと（差だけを読む）"
    (let [base (model/run @P)
          supplied (model/run (kumamoto/scenario-params @P :shelter-quality-fully-supplied))]
      (is (< (model/final supplied "cumulative_indirect_deaths")
             (model/final base "cumulative_indirect_deaths"))))))

(deftest unblocking-certificates-beats-adding-prefab-units
  (testing "『仮設を増やす』より『上流の詰まりを開ける』ほうが効くこと。
            leverage 順位の主張が、モデルの挙動でも成り立つかの検査。
            片方だけでなく両方を測り、大小を直接比べる（恒真にしない）"
    (let [pd (fn [p] (model/final (model/run p) "cumulative_person_days_displaced"))
          base (pd @P)
          prefab (pd (kumamoto/scenario-params @P :faster-temporary-housing))
          certs (pd (kumamoto/scenario-params @P :digital-certificates-day-10))
          gain-prefab (- base prefab)
          gain-certs (- base certs)]
      (is (pos? gain-certs) "証明のオンライン化が効いていない")
      (is (>= gain-certs gain-prefab)
          (str "上流を開けるより仮設増産のほうが効いている "
               "gain-certs=" gain-certs " gain-prefab=" gain-prefab
               " — leverage 順位の主張と矛盾する。順位かモデルのどちらかが誤り")))))

;; ---------------------------------------------------------------------------
;; 8. XMILE の書き出し
;; ---------------------------------------------------------------------------

(deftest emitted-xmile-round-trips
  (testing "書き出した XML を読み戻して変数集合が保たれること。
            書き出しだけの検査は『出力はしたが XMILE として読めない』を緑にする"
    (let [r (emit/round-trips? @P)]
      (is (:ok? r) (str "round-trip 失敗: " (pr-str r)))
      (is (<= 25 (:emitted r)) "変数が想定より少ない"))))

(deftest emitted-xmile-declares-the-standard
  (let [s (emit/xmile-string @P)]
    (is (re-find #"<xmile" s))
    (is (re-find #"version=\"1\.0\"" s))
    (is (re-find #"http://docs\.oasis-open\.org/xmile/ns/XMILE/v1\.0" s)
        "XMILE 1.0 の名前空間が入っていない")
    (is (re-find #"<stock" s))
    (is (re-find #"<flow" s))))

;; ---------------------------------------------------------------------------
;; 9. ループと leverage
;; ---------------------------------------------------------------------------

(deftest loops-carry-evidence-and-gaps-are-declared
  (is (<= 6 (count loops/loops)))
  (doseq [l loops/loops]
    (is (some? (:evidence l)) (str (:id l) " に根拠が無い"))
    (is (contains? #{:reinforcing :balancing} (:kind l)))
    (is (some? (:model-hook l)) (str (:id l) " がモデルのどこに対応するか不明")))
  (is (pos? (count (loops/loop-gaps)))
      "モデルが表現していないループが1つも宣言されていない — 本当に全部表現できているか"))

(deftest leverage-ranking-puts-structure-above-parameters
  (testing "Meadows の主張どおり、規則・情報の流れがパラメータ調整より上に来ること"
    (let [ranked (loops/ranked-interventions)
          score-of (fn [id] (:base-score (first (filter #(= id (:id %)) ranked))))]
      (is (> (score-of :iv/online-certificate-application)
             (score-of :iv/more-prefab-units))
          "情報の流れの介入がパラメータ介入より下に来ている"))))
