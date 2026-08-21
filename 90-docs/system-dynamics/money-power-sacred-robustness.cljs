;; money-power-sacred-robustness.cljs — ADR-2608081800

(ns money-power-sacred-robustness
  "money-power-sacred.cljs のモデルに対するロバストネス解析。ADR-2608081800。

   なぜこれが要るか: パラメータが 1 つも測定されていないので、『値はいくつか』は
   答えられない。答えられるのは『どの結論が値に依存しないか』である。全パラメータを
   log-uniform ×[1/1.5, 1.5] で摂動し、結論ごとに成立率を数える。φ/θ/ψ は独立変数
   なので摂動しない。半飽和定数 K は設計則 K = kfrac·g/δ を摂動後の g,δ から再計算
   する（kfrac ~ U(0.30,0.60)）—— そうしないと大半の draw が不感帯に落ちて
   『結論が頑健か』ではなく『モデルが退化するか』を測ることになる。

   速度について: xmile.execute は汎用の**解釈**器で、1 run あたり 108,072 回の
   式木評価（1 回 23.9-35.2 µs、実測）を行う。2,000 run 規模では成立しないので、
   ここでは同じ式木から JS を生成して走らせる（定数はリテラルに畳み、stock は
   Float64Array の添字に束縛する）。実測 3,804 ms → 13.9 ms/run = 274 倍、
   出力は全 stock で相対差 0.000000000（完全一致）。速いのは表現であって
   ランタイムでも言語でもない。

   実行:
     NACD=400 NB=200 nbb \\
       --classpath \"90-docs/system-dynamics/nbb-shim:orgs/kotoba-lang/org-oasis-open-xmile/src:orgs/kotoba-lang/dynamics/src\" \\
       90-docs/system-dynamics/money-power-sacred-robustness.cljs

   seed 固定なので同じ引数なら同じ結果になる。"
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [xmile.model :as m]
            [xmile.expr :as expr]
            [xmile.validate :as validate]
            [xmile.execute :as execute]
            [xmile.xml :as xx]
            [dynamics.core :as d]))

;; ═════════════════════════════════════════════════════════════════════
;; 1. Stocks — 資源そのものではなく「結果を変える capacity」の蓄積
;; ═════════════════════════════════════════════════════════════════════

;; [stock-name  初期値  K（半飽和定数）  減衰率]
;; K は「この量で normalized 値が 0.5 になる」スケール。
;; 正規化 n_X = X / (K_X + X) ∈ [0,1) を経由することで、
;;  ・資源そのもの ≠ power（変換効率と access が要る）
;;  ・全 stock が有限の平衡へ収束する（v1 の発散を除去）
;; の両方を同時に満たす。
(def stock-spec
  [["Money"                 100.0  400.0  0.00]   ; 減衰は money_out が担う
   ["Attention"               1.0    9.0  0.45]   ; 最も速く散る
   ["Reputation"              1.0   18.0  0.10]
   ["Network_Power"           1.0   15.0  0.12]
   ["Institutional_Power"     1.0   22.5  0.06]   ; 最も粘る
   ["Political_Power"         1.0   18.0  0.10]
   ["Media_Power"             1.0   15.75 0.20]
   ["Narrative_Power"         1.0   12.86 0.14]
   ["Cultural_Power"          1.0   22.5  0.05]   ; 文化は最も遅い
   ["Technological_Power"     1.0   15.0  0.15]
   ["Data_Power"              1.0   22.5  0.10]
   ["Coordination_Power"      1.0   10.0  0.18]
   ["Moral_Authority"         1.0   10.0  0.09]
   ["Legitimacy"              1.0   16.875 0.08]
   ["Sacred_Elite"            0.0   30.0  0.06]
   ["Sacred_Mass"             0.0   30.0  0.06]
   ["Sacred_Institutional"    0.0   30.0  0.06]])

;; K の決め方（恣意的な当て推量ではない）:
;;   dX/dt = g·n̄ − δX,  n_X = X/(K+X) から閉ループ不動点は X* = g/δ − K、
;;   したがって n* = 1 − Kδ/g。K = 0.45·g/δ と置けば n* ≈ 0.55 となり、
;;   全 stock が飽和でも不感帯でもない「感度のある帯」で動く。
;;   v1/v2初回はここを外していたため全 stock が n≈0.05 に潰れていた。

(def power-stocks
  ["Money" "Attention" "Reputation" "Network_Power" "Institutional_Power"
   "Political_Power" "Media_Power" "Narrative_Power" "Cultural_Power"
   "Technological_Power" "Data_Power" "Coordination_Power"
   "Moral_Authority" "Legitimacy"])

(def sacred-stocks ["Sacred_Elite" "Sacred_Mass" "Sacred_Institutional"])
(def sacred-gains  ["sacred_elite_gain" "sacred_mass_gain" "sacred_inst_gain"])

;; ═════════════════════════════════════════════════════════════════════
;; 2. パラメータ（すべて仮説）
;; ═════════════════════════════════════════════════════════════════════

(def base-params
  {;; ── policy levers（★ 実験で振る） ────────────────────────────────
   "phi"      0.30   ; ★ money → power 変換抵抗（0 = 摩擦なし, 1 = 完全遮断）
   "theta"    0.30   ; ★ power → sacred 制度的防火壁
   "psi"      1.00   ; ★ commercialization の罰の強さ

   ;; ── money ────────────────────────────────────────────────────────
   "income_rate"    0.14  "money_cap_k"  0.0015
   "rent_coef"      8.0                    ; 権力 → レント捕捉
   "spend_rate"     0.12
   "invest_frac"    0.35  "manip_frac"   0.12

   ;; ── conversion gains（正規化入力 → stock inflow の係数）─────────
   "g_attention"    9.0   "g_reputation"  4.0   "g_network"     4.0
   "g_institution"  3.0   "g_political"   4.0   "g_media"       7.0
   "g_narrative"    4.0   "g_cultural"    2.5   "g_technology"  5.0
   "g_data"         5.0   "g_coordination" 4.0
   "g_moral"        2.0   "g_legitimacy"  3.0

   ;; ── 変換ネットワークの重み ────────────────────────────────────────
   "a_money_media"   0.75 "a_network_media"  0.25
   "a_money_pol"     0.55 "a_network_pol"    0.45
   "a_money_tech"    0.80 "a_data_tech"      0.20
   "a_money_data"    0.60 "a_tech_data"      0.40
   "a_media_att"     0.55 "a_sacred_att"     0.45
   "a_media_nar"     0.55 "a_rep_nar"        0.45
   "a_nar_cul"       0.60 "a_inst_cul"       0.40
   "a_att_net"       0.35 "a_reward_net"     0.65
   "a_pol_inst"      0.60 "a_coord_inst"     0.40
   "a_follow_coord"  0.50 "a_inst_coord"     0.30 "a_nar_coord"  0.20
   "a_contrib_moral" 0.55 "a_legit_moral"    0.45
   "a_inst_legit"    0.45 "a_ck_legit"       0.25 "a_contrib_legit" 0.30

   ;; ── reputation ────────────────────────────────────────────────────
   "b_contribution" 0.50 "b_attention" 0.50  "scandal_effect" 2.0

   ;; ── contribution ──────────────────────────────────────────────────
   "c_inst" 0.40 "c_tech" 0.35 "c_coord" 0.25

   ;; ── manipulation / detection（B1 balancing loop）──────────────────
   "manip_gain" 1.20 "manip_info" 0.60
   "d_base" 0.10 "d_ck" 0.70 "d_data" 0.20

   ;; ── common knowledge ──────────────────────────────────────────────
   "k_ck" 3.0

   ;; ── recognition = Hill(Reputation) ───────────────────────────────
   "hill_n" 3.0 "K_hill" 0.45

   ;; ── information power ────────────────────────────────────────────
   "exclusivity" 0.60

   ;; ── commercialization / contradiction ────────────────────────────
   "comm_media" 0.60 "comm_manip" 0.40 "coercion_vis" 0.80

   ;; ── sacred gains ─────────────────────────────────────────────────
   "e_powerpath" 1.30 "e_cultural" 0.50 "e_legit"   0.60   ; elite
   "m_rec"       1.40 "m_cultural" 0.80 "m_moral"   0.70   ; mass
   "i_legit"     1.10 "i_contrib"  0.90 "i_rec"     0.40   ; institutional

   ;; ── sacred losses ────────────────────────────────────────────────
   "lam_scandal_e" 0.60 "lam_comm_e" 0.50 "lam_contra_e" 0.40
   "lam_scandal_m" 1.80 "lam_comm_m" 1.60 "lam_contra_m" 1.20 ; 大衆が最も厳しい
   "lam_scandal_i" 1.00 "lam_comm_i" 0.40 "lam_contra_i" 0.90

   "w_elite" 0.30 "w_mass" 0.45 "w_inst" 0.25})

;; ═════════════════════════════════════════════════════════════════════
;; 3. モデル構築
;; ═════════════════════════════════════════════════════════════════════

(defn- n [s] (str "n_" s))

(defn build-model [params shocks]
  (let [num  (fn [k] (str (double (get params k))))
        init (fn [s v] (str (double (+ v (get shocks s 0.0)))))
        consts   (for [[k v] params :when (not (or (contains? #{"sim_stop" "sim_dt"} k)
                                   (str/starts-with? k "stockK_") (str/starts-with? k "stockD_")))] (m/aux k (str (double v))))
        ;; 正規化 aux: n_X = X / (K_X + X) ∈ [0,1)
        norms    (for [[s _ K _] stock-spec]
                   (m/aux (n s) (str s " / (" (double (get params (str "stockK_" s) K)) " + " s ")")))
        stocks   (for [[s v0 _ _] stock-spec]
                   (m/stock s (init s v0)
                            {:xmile/inflows  #{(str (str/lower-case s) "_in")}
                             :xmile/outflows #{(str (str/lower-case s) "_out")}
                             :xmile/non-negative? true}))
        ;; 減衰 outflow（Money だけ支出モデル）
        decays   (for [[s _ _ dec] stock-spec]
                   (m/flow (str (str/lower-case s) "_out")
                           (if (= s "Money")
                             "spend_rate * Money"
                             (str (double (get params (str "stockD_" s) dec)) " * " s))))
        mdl
        (-> (m/model "money_powervector_reputation_sacred"
                     {:xmile/sim-specs (m/sim-specs 0.0 (get params "sim_stop" 80.0) {:xmile/dt (get params "sim_dt" 0.5)
                                                              :xmile/method :rk4
                                                              :xmile/time-units "period"})})

            ;; ── 派生 power（資源 ≠ power: Cobb–Douglas / 積形）──────
            ;; Power_i = Resources_i × Access_i × ConversionEfficiency_i
            (m/add-variable (m/aux "Conversion_Friction" "1 - phi"))
            (m/add-variable (m/aux "Financial_Capacity"
                                   "n_Money * Conversion_Friction"))
            (m/add-variable (m/aux "Analytical_Capability" "n_Technological_Power"))
            (m/add-variable (m/aux "Information_Power"
                                   "n_Data_Power * Analytical_Capability * exclusivity"))
            (m/add-variable (m/aux "Political_Influence"   ; ユーザ指定の Cobb–Douglas
                                   "Financial_Capacity^0.4 * n_Network_Power^0.3 * n_Reputation^0.2 * n_Institutional_Power^0.1"))
            (m/add-variable (m/aux "Expert_Power"
                                   "n_Data_Power^0.5 * n_Technological_Power^0.5"))
            (m/add-variable (m/aux "Platform_Power"
                                   "n_Technological_Power^0.5 * n_Network_Power^0.5"))
            (m/add-variable (m/aux "Gatekeeping_Power"
                                   "Platform_Power^0.5 * n_Media_Power^0.5"))
            (m/add-variable (m/aux "Coercive_Power"
                                   "n_Political_Power^0.6 * n_Institutional_Power^0.4"))
            (m/add-variable (m/aux "Reward_Power"
                                   "Financial_Capacity^0.5 * n_Institutional_Power^0.5"))
            (m/add-variable (m/aux "Followers"
                                   "n_Attention^0.5 * n_Cultural_Power^0.5"))

            ;; ── 観測・認識系 ────────────────────────────────────────
            (m/add-variable (m/aux "Common_Knowledge" "1 - EXP(-k_ck * n_Attention)"))
            (m/add-variable (m/aux "Recognition"
                                   "n_Reputation^hill_n / (K_hill^hill_n + n_Reputation^hill_n)"))
            (m/add-variable (m/aux "Genuine_Contribution"
                                   "c_inst * n_Institutional_Power + c_tech * n_Technological_Power + c_coord * n_Coordination_Power"))

            ;; ── 不正・露見（B1 balancing loop）──────────────────────
            (m/add-variable (m/aux "Manipulation"
                                   "manip_gain * manip_frac * Financial_Capacity + manip_info * Information_Power"))
            (m/add-variable (m/aux "Detection_Probability"
                                   "MIN(1, d_base + d_ck * Common_Knowledge + d_data * n_Data_Power)"))
            (m/add-variable (m/aux "Scandal" "Manipulation * Detection_Probability"))

            ;; ── 神聖性を削る 3 つの別々の力 ─────────────────────────
            ;; 商業化: 貢献に裏打ちされない可視性 + 露骨な操作
            (m/add-variable (m/aux "Commercialization"
                                   "psi * (comm_media * n_Media_Power / (1 + Genuine_Contribution) + comm_manip * Manipulation)"))
            (m/add-variable (m/aux "Coercion_Visibility"
                                   "coercion_vis * Coercive_Power * Common_Knowledge"))
            ;; 矛盾: 道徳的権威を主張しながら強制が可視である度合い
            (m/add-variable (m/aux "Contradiction"
                                   "n_Moral_Authority * Coercion_Visibility"))
            (m/add-variable (m/aux "Sacred_Total"
                                   "w_elite * Sacred_Elite + w_mass * Sacred_Mass + w_inst * Sacred_Institutional"))
            (m/add-variable (m/aux "n_Sacred_Total"
                                   "Sacred_Total / (30.0 + Sacred_Total)"))

            ;; ── power conversion network（inflow）───────────────────
            (m/add-variable (m/flow "money_in"
                                    "income_rate * Money / (1 + money_cap_k * Money) + rent_coef * (Reward_Power + Political_Influence) / 2"))
            (m/add-variable (m/flow "media_power_in"
                                    "g_media * (a_money_media * Financial_Capacity + a_network_media * n_Network_Power)"))
            (m/add-variable (m/flow "attention_in"
                                    "g_attention * (a_media_att * n_Media_Power + a_sacred_att * n_Sacred_Total)"))
            (m/add-variable (m/flow "reputation_in"
                                    "g_reputation * (b_contribution * Genuine_Contribution + b_attention * n_Attention * Recognition)"))
            (m/add-variable (m/flow "network_power_in"
                                    "g_network * (a_att_net * n_Attention + a_reward_net * Reward_Power)"))
            (m/add-variable (m/flow "political_power_in"
                                    "g_political * (a_money_pol * Financial_Capacity + a_network_pol * n_Network_Power) * Conversion_Friction"))
            (m/add-variable (m/flow "institutional_power_in"
                                    "g_institution * (a_pol_inst * n_Political_Power + a_coord_inst * n_Coordination_Power)"))
            (m/add-variable (m/flow "technological_power_in"
                                    "g_technology * (a_money_tech * Financial_Capacity + a_data_tech * n_Data_Power)"))
            (m/add-variable (m/flow "data_power_in"
                                    "g_data * (a_money_data * Financial_Capacity + a_tech_data * n_Technological_Power)"))
            (m/add-variable (m/flow "narrative_power_in"
                                    "g_narrative * (a_media_nar * Gatekeeping_Power + a_rep_nar * Recognition)"))
            (m/add-variable (m/flow "cultural_power_in"
                                    "g_cultural * (a_nar_cul * n_Narrative_Power + a_inst_cul * n_Institutional_Power)"))
            (m/add-variable (m/flow "coordination_power_in"
                                    "g_coordination * (a_follow_coord * Followers + a_inst_coord * n_Institutional_Power + a_nar_coord * n_Narrative_Power)"))
            (m/add-variable (m/flow "moral_authority_in"
                                    "g_moral * (a_contrib_moral * Genuine_Contribution + a_legit_moral * n_Legitimacy)"))
            (m/add-variable (m/flow "legitimacy_in"
                                    "g_legitimacy * (a_inst_legit * n_Institutional_Power + a_ck_legit * Common_Knowledge + a_contrib_legit * Genuine_Contribution)"))

            ;; ── ★ Sacred の gain — Money / Financial_Capacity /
            ;;    Manipulation を一文字も書かない（構造不変条件）────
            (m/add-variable (m/flow "sacred_elite_gain"
                                    "e_powerpath * (n_Political_Power + n_Institutional_Power) / 2 / (1 + theta * (n_Political_Power + n_Institutional_Power) / 2) + e_cultural * n_Cultural_Power + e_legit * n_Legitimacy"))
            (m/add-variable (m/flow "sacred_mass_gain"
                                    "m_rec * Recognition * Common_Knowledge + m_cultural * n_Cultural_Power + m_moral * n_Moral_Authority"))
            (m/add-variable (m/flow "sacred_inst_gain"
                                    "i_legit * n_Legitimacy + i_contrib * Genuine_Contribution + i_rec * Recognition"))

            ;; ── Sacred の loss — ここには Scandal / Commercialization /
            ;;    Contradiction が入る（金臭さが神聖性を削る経路）────
            (m/add-variable (m/flow "sacred_elite_loss"
                                    "0.06 * Sacred_Elite + lam_scandal_e * Scandal + lam_comm_e * Commercialization + lam_contra_e * Contradiction"))
            (m/add-variable (m/flow "sacred_mass_loss"
                                    "0.06 * Sacred_Mass + lam_scandal_m * Scandal + lam_comm_m * Commercialization + lam_contra_m * Contradiction"))
            (m/add-variable (m/flow "sacred_inst_loss"
                                    "0.06 * Sacred_Institutional + lam_scandal_i * Scandal + lam_comm_i * Commercialization + lam_contra_i * Contradiction"))

            ;; ── reputation の loss ─────────────────────────────────
            (m/add-variable (m/flow "reputation_out" "0.10 * Reputation + scandal_effect * Scandal")))]
    (as-> mdl $
      (reduce m/add-variable $ stocks)
      (reduce m/add-variable $ (remove #(contains? #{"reputation_out" "sacred_elite_out"
                                                     "sacred_mass_out" "sacred_institutional_out"}
                                                   (:xmile/name %))
                                       decays))
      ;; sacred の outflow 名を gain/loss 命名に合わせる
      (reduce m/add-variable $ (for [[s gain loss] [["Sacred_Elite" "sacred_elite_gain" "sacred_elite_loss"]
                                                    ["Sacred_Mass" "sacred_mass_gain" "sacred_mass_loss"]
                                                    ["Sacred_Institutional" "sacred_inst_gain" "sacred_inst_loss"]]]
                                 (m/stock s (init s 0.0)
                                          {:xmile/inflows #{gain} :xmile/outflows #{loss}
                                           :xmile/non-negative? true})))
      (reduce m/add-variable $ norms)
      (reduce m/add-variable $ consts))))

;; ═════════════════════════════════════════════════════════════════════
;; 4. 構造検査
;; ═════════════════════════════════════════════════════════════════════

(defn idents-of [model eqn]
  (let [names (m/variable-names model)]
    (into #{} (filter names (expr/free-vars (expr/parse eqn))))))

(defn dep-graph [model]
  (into {} (for [v (m/variables model) :let [nm (:xmile/name v)]]
             [nm (if (m/stock? v)
                   (into (m/inflows-of model nm) (m/outflows-of model nm))
                   (idents-of model (:xmile/eqn v)))])))

(defn reverse-graph [g]
  (reduce (fn [acc [nd deps]] (reduce (fn [a dp] (update a dp (fnil conj #{}) nd)) acc deps))
          {} g))

(defn reachable
  "from から到達できる節点集合（blocked の節点は通らない）。"
  [g from blocked]
  (loop [frontier [from] seen #{from}]
    (if (empty? frontier)
      seen
      (let [nxt (for [x frontier, y (get g x #{})
                      :when (and (not (seen y)) (not (blocked y)))] y)]
        (recur (vec (distinct nxt)) (into seen nxt))))))

(defn shortest-path [g from to]
  (loop [frontier [from] parent {from nil} seen #{from}]
    (cond
      (contains? parent to) (reverse (take-while some? (iterate parent to)))
      (empty? frontier) nil
      :else
      (let [nxt (for [x frontier, y (sort (get g x #{})) :when (not (seen y))] [x y])
            parent' (reduce (fn [p [x y]] (if (contains? p y) p (assoc p y x))) parent nxt)]
        (recur (vec (distinct (map second nxt))) parent' (into seen (map second nxt)))))))

(defn mandatory-gateways
  "その1点を落とすと from → to が到達不能になる節点＝「金が神聖性へ届くのに
  必ず通らねばならない関門」。単純経路の全列挙（組合せ爆発する）ではなく
  |V| 回の到達可能性判定で出す。"
  [g from to]
  (->> (keys g)
       (remove #{from to})
       (filter #(not (contains? (reachable g from #{%}) to)))
       sort vec))

(def purchasable #{"Money" "Financial_Capacity" "Manipulation" "n_Money"})

;; ═════════════════════════════════════════════════════════════════════
;; 5. 実行ヘルパ
;; ═════════════════════════════════════════════════════════════════════

;; 注: 初版はここで `expr/parse` をメモ化していた。実測すると効果はゼロで
;; （`execute/run` は先頭の `desugar-delays` で全式を一度だけ木に落とす）、
;; 本当の無駄は「定数 aux を RK4 の substep ごとに評価し直すこと」だった。
;; これは上流 kotoba-lang/org-oasis-open-xmile#2 に constant folding として
;; 入れたので（160 non-stock 中 88 = 55% を run 開始時に 1 回だけ評価）、
;; ここには何も要らない。

(defn run-with
  ([overrides] (run-with overrides {}))
  ([overrides shocks]
   (execute/run (build-model (merge base-params overrides) shocks))))

(defn fin [series k] (last (get series k)))
(defn fmt ([x] (fmt x 3)) ([x dp] (if (nil? x) "nil" (.toFixed (js/Number x) dp))))
(defn pad [s w] (.padEnd (str s) w))

(defn integral [times ys]
  (reduce + (for [i (range 1 (count times))]
              (* 0.5 (+ (nth ys i) (nth ys (dec i)))
                 (- (nth times i) (nth times (dec i)))))))

;; ═════════════════════════════════════════════════════════════════════
;; 6. XMILE テキスト出力
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




;; ── モデル → 専用 JS 評価器 ────────────────────────────────────────────
(defn compile-model [params]
  (let [mdl (build-model params {})
        des (:xmile/model (execute/desugar-delays mdl))
        order (execute/topo-order des)
        cnames (execute/constant-names des order)
        cenv (execute/constant-env des order cnames)
        dyn-order (vec (remove cnames order))
        stocks (mapv :xmile/name (m/stocks des))
        sidx (into {} (map-indexed (fn [i s] [s i]) stocks))
        parsed (fn [nm] (let [q (:xmile/eqn (m/lookup des nm))] (if (string? q) (expr/parse q) q)))
        jx (fn jx [e]
             (case (first e)
               :num (str (double (second e)))
               :ref (let [nm (second e)]
                      (cond (= nm "TIME") "t" (= nm "DT") "dt"
                            (contains? cenv nm) (str (double (get cenv nm)))
                            (contains? sidx nm) (str "y[" (get sidx nm) "]")
                            :else (str "v_" nm)))
               :neg (str "(-" (jx (nth e 1)) ")")
               :add (str "(" (jx (nth e 1)) "+" (jx (nth e 2)) ")")
               :sub (str "(" (jx (nth e 1)) "-" (jx (nth e 2)) ")")
               :mul (str "(" (jx (nth e 1)) "*" (jx (nth e 2)) ")")
               :div (str "(" (jx (nth e 1)) "/" (jx (nth e 2)) ")")
               :pow (str "Math.pow(" (jx (nth e 1)) "," (jx (nth e 2)) ")")
               :call (let [f (second e) as (mapv jx (nth e 2))]
                       (case f "MIN" (str "Math.min(" (str/join "," as) ")")
                               "MAX" (str "Math.max(" (str/join "," as) ")")
                               "EXP" (str "Math.exp(" (first as) ")")
                               "ABS" (str "Math.abs(" (first as) ")")
                               "SQRT" (str "Math.sqrt(" (first as) ")")
                               (throw (ex-info "unsupported builtin" {:fn f}))))
               (throw (ex-info "unsupported node" {:n (first e)}))))
        src (str "const out=new Float64Array(" (count stocks) ");\nreturn function(y,t,dt){\n"
                 (str/join (for [nm dyn-order]
                             (let [v (m/lookup des nm) b (jx (parsed nm))]
                               (str "const v_" nm "=" (if (:xmile/non-negative? v) (str "Math.max(0," b ")") b) ";\n"))))
                 (str/join (for [[i s] (map-indexed vector stocks)]
                             (let [tm (fn [x] (if (seq x) (str/join "+" (map #(str "v_" %) x)) "0"))]
                               (str "out[" i "]=(" (tm (m/inflows-of des s)) ")-(" (tm (m/outflows-of des s)) ");\n"))))
                 "return out;};")
        is (execute/initial-stocks des)]
    {:derivs (.call (js/Function src)) :stocks stocks :sidx sidx :y0 (mapv #(get is %) stocks)
     :sw [(get params "w_elite") (get params "w_mass") (get params "w_inst")]
     :si [(get sidx "Sacred_Elite") (get sidx "Sacred_Mass") (get sidx "Sacred_Institutional")]}))

(defn simulate
  "最終 stock 値と ∫Sacred_Total dt（台形則）を返す。単一アトラクタなので
   最終値の差は 0 に潰れる —— multiplier の指標は軌道積分でなければならない。"
  [{:keys [derivs stocks y0 sw si]} shocks sidx T dt]
  (let [nn (count stocks)
        y (js/Float64Array. nn) tmp (js/Float64Array. nn) acc (js/Float64Array. nn)
        n (long (/ T dt))]
    (dotimes [i nn] (aset y i (+ (nth y0 i) (get shocks (nth stocks i) 0.0))))
    (dotimes [i nn] (when (neg? (aget y i)) (aset y i 0.0)))
    (let [[we wm wi] sw [ie im ii] si
          stot (fn [] (+ (* we (aget y ie)) (* wm (aget y im)) (* wi (aget y ii))))
          area (atom 0.0) prev (atom (stot))]
    (dotimes [step n]
      (let [t (* step dt)]
        (.fill acc 0)
        (let [k (derivs y t dt)]
          (dotimes [i nn] (aset acc i (+ (aget acc i) (aget k i)))
                          (aset tmp i (+ (aget y i) (* 0.5 dt (aget k i))))))
        (let [k (derivs tmp (+ t (* 0.5 dt)) dt)]
          (dotimes [i nn] (aset acc i (+ (aget acc i) (* 2 (aget k i))))
                          (aset tmp i (+ (aget y i) (* 0.5 dt (aget k i))))))
        (let [k (derivs tmp (+ t (* 0.5 dt)) dt)]
          (dotimes [i nn] (aset acc i (+ (aget acc i) (* 2 (aget k i))))
                          (aset tmp i (+ (aget y i) (* dt (aget k i))))))
        (let [k (derivs tmp (+ t dt) dt)]
          (dotimes [i nn] (aset acc i (+ (aget acc i) (aget k i)))))
        (dotimes [i nn] (aset y i (max 0.0 (+ (aget y i) (* (/ dt 6.0) (aget acc i))))))
        (let [cur (stot)] (swap! area + (* 0.5 dt (+ @prev cur))) (reset! prev cur))))
    {:S (stot) :int @area
     :eq (into {} (map-indexed (fn [i s] [s (aget y i)]) stocks))})))
;; ═════════════════════════════════════════════════════════════════════
;; ロバストネス解析 — パラメータが未測定なら、問うべきは「値」ではなく
;; 「どの結論がパラメータ空間のどれだけの範囲で生き残るか」である。
;; ═════════════════════════════════════════════════════════════════════

(def T 300.0) (def DT 1.0)   ; dt=1.0 は dt=0.5 と 4 桁一致・半コスト（実測）。
;; T=150 では未収束（漸近値の 1.8% 下）で、テスト A が過渡差を「M0 依存」と誤判定する。
;; T=300 は T=600 と 0.002 差（実測）なので収束後の比較になる。
(defn sim [p] (assoc p "sim_stop" T "sim_dt" DT))
;; 解釈ではなくコンパイルして走らせる（結果は 12 桁一致を実測済み）
(defn st [p shocks] (let [c (compile-model (sim p))] (:S (simulate c shocks (:sidx c) T DT))))

;; 再現可能な LCG（Math/random を使わない — 同じ seed で同じ結果を出すため）
(defn lcg [seed] (let [st (atom seed)]
                   (fn [] (swap! st #(mod (+ (* 1103515245 %) 12345) 2147483648))
                          (/ @st 2147483648.0))))

;; g パラメータの対応（K = kfrac·g/δ の設計則を摂動後も保つため）
(def gmap {"Attention" "g_attention" "Reputation" "g_reputation" "Network_Power" "g_network"
           "Institutional_Power" "g_institution" "Political_Power" "g_political"
           "Media_Power" "g_media" "Narrative_Power" "g_narrative" "Cultural_Power" "g_cultural"
           "Technological_Power" "g_technology" "Data_Power" "g_data"
           "Coordination_Power" "g_coordination" "Moral_Authority" "g_moral"
           "Legitimacy" "g_legitimacy"})

(def held #{"phi" "theta" "psi"})   ; 独立変数として振るので摂動しない

(defn draw [rnd spread]
  (let [pert (fn [v] (* v (js/Math.exp (* (js/Math.log spread) (- (* 2 (rnd)) 1)))))
        base (into {} (for [[k v] base-params] [k (if (held k) v (pert v))]))
        kfrac (+ 0.30 (* 0.30 (rnd)))
        extra (into {} (mapcat (fn [[s _ K dec]]
                                 (let [d (pert dec) g (get gmap s)]
                                   (if g
                                     [[(str "stockD_" s) d] [(str "stockK_" s) (* kfrac (/ (get base g) d))]]
                                     [[(str "stockD_" s) d] [(str "stockK_" s) (pert K)]])))
                               stock-spec))]
    (merge base extra)))



(def N-ACD (js/parseInt (or (.-NACD (.-env js/process)) "200")))
(def N-B   (js/parseInt (or (.-NB (.-env js/process)) "60")))
(def SPREAD (js/parseFloat (or (.-SPREAD (.-env js/process)) "1.5")))

(println "ロバストネス解析: 全パラメータを log-uniform ×[1/" SPREAD "," SPREAD "] で摂動")
(println "  K は設計則 K = kfrac·g/δ を摂動後の g,δ から再計算（kfrac ~ U(0.30,0.60)）")
(println "  φ/θ/ψ は独立変数なので摂動しない。T=" T " dt=" DT " seed=20260808")
(println "  draws: A/C/D =" N-ACD ", B =" N-B)

(def rnd (lcg 20260808))
(def acc (atom {:A 0 :C 0 :D 0 :degenerate 0 :n 0}))

(doseq [i (range N-ACD)]
  (let [p (draw rnd SPREAD)
        s-lo (st p {"Money" -90.0})      ; M0 = 10
        s-hi (st p {"Money" 9900.0})     ; M0 = 10000
        base (st p {})]
    (if (< base 0.05)
      (swap! acc update :degenerate inc)
      (let [;; A: 到達点が M0 に依らない
            a? (< (/ (js/Math.abs (- s-hi s-lo)) (max base 1e-9)) 0.02)
            ;; C: φ 応答が内部最大を持つ
            p0 (st (assoc p "phi" 0.0) {}) p4 (st (assoc p "phi" 0.4) {}) p9 (st (assoc p "phi" 0.95) {})
            c? (and (> p4 p0) (> p4 p9))
            ;; D: ψ のほうが θ より効く
            r0 (st (assoc p "theta" 0.0 "psi" 0.0) {})
            rt (st (assoc p "theta" 10.0 "psi" 0.0) {})
            rp (st (assoc p "theta" 0.0 "psi" 2.0) {})
            d? (> (js/Math.abs (- rp r0)) (js/Math.abs (- rt r0)))]
        (swap! acc #(-> % (update :n inc) (update :A + (if a? 1 0))
                        (update :C + (if c? 1 0)) (update :D + (if d? 1 0))))))
    (when (zero? (mod (inc i) 20)) (println "  ACD" (inc i) "/" N-ACD @acc))))

(println "\n=== A/C/D 結果 ===")
(let [{:keys [A C D n degenerate]} @acc]
  (println "  有効 draw:" n " / 退化して除外:" degenerate)
  (println (str "  A 到達点は M0 に依らない        : " A "/" n " = " (fmt (* 100.0 (/ A n)) 1) "%"))
  (println (str "  C φ 応答が内部最大を持つ        : " C "/" n " = " (fmt (* 100.0 (/ C n)) 1) "%"))
  (println (str "  D ψ のほうが θ より効く         : " D "/" n " = " (fmt (* 100.0 (/ D n)) 1) "%")))

;; B: Money の power-multiplier 順位
(def rnd2 (lcg 777333))
(def ranks (atom []))
(doseq [i (range N-B)]
  (let [p (draw rnd2 SPREAD)
        c (compile-model (sim p))
        r0 (simulate c {} (:sidx c) T DT)
        base (:S r0) b0 (:int r0) eqs (:eq r0)]
    (when (>= base 0.05)
      (let [per (for [nm power-stocks]
                  (let [eq (get eqs nm) sh (* 0.10 eq)]
                    (if (<= sh 1e-9) [nm 0.0]
                      [nm (/ (- (:int (simulate c {nm sh} (:sidx c) T DT)) b0) sh)])))
            sorted (map first (reverse (sort-by second per)))
            rk (inc (count (take-while #(not= % "Money") sorted)))]
        (swap! ranks conj rk)))
    (when (zero? (mod (inc i) 10)) (println "  B" (inc i) "/" N-B "ranks so far" (count @ranks)))))

(println "\n=== B 結果: Money の順位（14 stock 中）===")
(let [rs @ranks n (count rs)
      _ (when (zero? n) (println "  有効 draw が 0 件。全 draw が退化した。") (js/process.exit 1))
      sorted (sort rs)
      med (nth sorted (quot n 2))
      bot3 (count (filter #(>= % 12) rs))
      bot-half (count (filter #(>= % 8) rs))]
  (println "  有効 draw:" n)
  (println "  中央値順位:" med " 最良:" (first sorted) " 最悪:" (last sorted))
  (println (str "  下位 3 位以内(>=12)  : " bot3 "/" n " = " (fmt (* 100.0 (/ bot3 n)) 1) "%"))
  (println (str "  下位半分(>=8)        : " bot-half "/" n " = " (fmt (* 100.0 (/ bot-half n)) 1) "%"))
  (println "  順位の分布:" (into (sorted-map) (frequencies rs))))
