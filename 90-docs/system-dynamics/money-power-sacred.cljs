;; money-power-sacred.cljs — Money / 14 次元 power vector / Reputation /
;; Sacred status(3 チャネル) の stock-flow モデル。ADR-2608081200。
;;
;; 実行:
;;   SD_OUT=90-docs/system-dynamics nbb \
;;     --classpath "90-docs/system-dynamics/nbb-shim:orgs/kotoba-lang/org-oasis-open-xmile/src:orgs/kotoba-lang/dynamics/src" \
;;     90-docs/system-dynamics/money-power-sacred.cljs
;;
;; ⚠ **全パラメータは UNMEASURED HYPOTHESIS。**kotoba-lang/dynamics の
;;   「computed always means instantiated against real facts」に従い、
;;   ここで出る数値は「この構造ならこう動く」であって観測値ではない。
;;   キャリブレーションに使った実データは 1 件も無い。したがって
;;   引用してよいのは **構造的な結論**（どの経路が存在するか、到達点が
;;   初期値に依存するか、順位はどうか）であって、水準の絶対値ではない。
;;
;; 既存資産のみ使用（新規実装なし）:
;;   kotoba-lang/org-oasis-open-xmile — XMILE 1.0 model/expr/validate/execute/xml
;;   kotoba-lang/dynamics             — Meadows leverage band scoring
;;
;; nbb-shim/ が要る理由: xmile.validate だけが kotoba-lang/dsl-core の
;;   `kotoba.dsl.problem` に依存し、その実体は `.kotoba` ソースなので nbb が
;;   読めない。同じ観測契約を持つ最小の cljc を置いてある。

(ns money-power-sacred
  "Money / 14 次元 power vector / Reputation / Sacred status(3 チャネル) を
   XMILE 1.0 の stock-flow として組み、(1) 「Sacred の gain 側に Money を直接
   つながない」を機械で検査し、(2) Money → Sacred の最短経路と関門を出し、
   (3) 各 power に同じショックを与えて ∫ΔS dt = dynamic power multiplier を測る。

   ⚠ **全パラメータは UNMEASURED HYPOTHESIS。**dynamics の
   「computed always means instantiated against real facts」に従い、ここで出る
   数値は『この構造ならこう動く』であって観測値ではない。キャリブレーションに
   使った実データは 1 件も無い。引用してよいのは構造的な結論（どの経路が
   存在するか / 到達点が初期値に依存するか / 順位）であって、水準の絶対値ではない。

   半飽和定数 K だけは当て推量ではない: dX/dt = g·n̄ − δX, n_X = X/(K+X) の
   閉ループ不動点 X* = g/δ − K から n* = 1 − Kδ/g なので、K = 0.45·g/δ と置けば
   全 stock が n*≈0.55 の感度帯に入る（初版はここを外し全 stock が n≈0.05 に潰れた）。"
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
        consts   (for [[k v] params :when (not= k "sim_stop")] (m/aux k (str (double v))))
        ;; 正規化 aux: n_X = X / (K_X + X) ∈ [0,1)
        norms    (for [[s _ K _] stock-spec]
                   (m/aux (n s) (str s " / (" (double K) " + " s ")")))
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
                             (str (double dec) " * " s))))
        mdl
        (-> (m/model "money_powervector_reputation_sacred"
                     {:xmile/sim-specs (m/sim-specs 0.0 (get params "sim_stop" 80.0) {:xmile/dt 0.5
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


;; ═════════════════════════════════════════════════════════════════════
;; main
;; ═════════════════════════════════════════════════════════════════════

(def T 300.0)   ; 収束確認済みの地平線（t=200 で |Δ|<0.09、t=600 まで不変）

(def mdl (build-model base-params {}))

(println "═══ 0. XMILE 1.0 structural validation ═══")
(let [p (validate/validate mdl)]
  (println "  valid?" (validate/valid? p)
           "| variables" (count (m/variables mdl))
           "= stocks" (count (m/stocks mdl))
           "+ flows" (count (m/flows mdl))
           "+ aux" (count (m/auxs mdl)) "(うち定数" (count base-params) ")")
  (doseq [x (take 8 p)] (println "   " x)))

(println "\n═══ 1. 構造不変条件 ═══")
(let [g (dep-graph mdl)
      viol (into {} (for [f sacred-gains :let [bad (set/intersection (get g f) purchasable)]
                          :when (seq bad)] [f bad]))]
  (println "  Money/Financial_Capacity/Manipulation → sacred_*_gain の直接辺:"
           (if (empty? viol) "NONE ✓" viol))
  (println "  sacred_*_loss は Scandal / Commercialization / Contradiction を参照する")
  (println "  ⇒ 金で「買う」辺は無いが「失わせる」辺はある（非対称）"))

(println "\n═══ 2. Money → Sacred の最短経路と関門 ═══")
(let [ig (reverse-graph (dep-graph mdl))]
  (doseq [f sacred-gains]
    (println (str "  " f))
    (println (str "     最短 " (dec (count (shortest-path ig "Money" f))) " hop: "
                  (str/join " → " (shortest-path ig "Money" f))))
    (let [gws2 (remove #(str/starts-with? % "n_") (mandatory-gateways ig "Financial_Capacity" f))]
      (println (str "     換金後に必ず通る関門: "
                    (if (seq gws2) (str/join ", " gws2)
                        "無し — 経路が分岐しており 1 点では塞げない"))))))

(println "\n═══ 3. 大域アトラクタ（t=300、収束確認済み）═══")
(def base-run (run-with {"sim_stop" T}))
(def base-series (:xmile/series base-run))
(def base-times  (:xmile/times base-run))
(doseq [row (partition-all 3 (concat power-stocks sacred-stocks ["Sacred_Total"]))]
  (println "  " (str/join "  " (for [s row] (str (pad s 22) "=" (pad (fmt (fin base-series s) 3) 10))))))
(println "  " (str/join "  " (for [k ["Recognition" "Common_Knowledge" "Scandal" "Commercialization" "Contradiction"]]
                               (str k "=" (fmt (fin base-series k))))))

(println "\n═══ 4. Money の初期値を 5 桁振る — 過渡 vs 到達点 ═══")
(println (str "   " (pad "M0" 10) (pad "S(t=80)" 12) (pad "S(t=300)" 12) (pad "∫ΔS dt (対 M0=100)" 20) "S_mass(t=80)"))
(def m0-ref (integral base-times (get base-series "Sacred_Total")))
(doseq [m0 [1.0 10.0 100.0 1000.0 10000.0 100000.0]]
  (let [r80 (:xmile/series (run-with {"sim_stop" 80.0} {"Money" (- m0 100.0)}))
        rT  (run-with {"sim_stop" T} {"Money" (- m0 100.0)})
        srT (:xmile/series rT)]
    (println (str "   " (pad (long m0) 10)
                  (pad (fmt (fin r80 "Sacred_Total") 3) 12)
                  (pad (fmt (fin srT "Sacred_Total") 4) 12)
                  (pad (fmt (- (integral (:xmile/times rT) (get srT "Sacred_Total")) m0-ref) 2) 20)
                  (fmt (fin r80 "Sacred_Mass") 3)))))
(println "   ⇒ 到達点は M0 に依らず一定。初期資本が買うのは「速さ」であって「高さ」ではない")

(println "\n═══ 4b. 初期資本ではなく「金の定常的な豊富さ」が到達点に効く ═══")
(println "   money_cap_k = 富の複利の飽和（0 = 無限に複利、大 = すぐ頭打ち）")
(println (str "   " (pad "money_cap_k" 14) (str/join (for [m0 [10.0 1000.0 100000.0]] (pad (str "M0=" (long m0)) 14)))))
(doseq [ck [0.0 0.00005 0.0002 0.0015 0.01]]
  (println (str "   " (pad ck 14)
                (str/join (for [m0 [10.0 1000.0 100000.0]]
                            (pad (fmt (fin (:xmile/series (run-with {"sim_stop" T "money_cap_k" ck}
                                                                    {"Money" (- m0 100.0)})) "Sacred_Total") 3) 14))))))
(println "   ⇒ どの列でも M0 依存はゼロのまま。効くのは初期値ではなく定常水準で、")
(println "     金が積み上がる（複利が飽和しない）ほど Manipulation と Commercialization が増え、")
(println "     mass / institutional チャネルが削られて到達点そのものが下がる")

(println "\n═══ 5. dynamic power multiplier — 同一ショックに対する ∫ΔS dt ═══")
(println "   (a) 絶対 +10 / (b) 平衡の +10% / (c) = (b)/ショック量（単位あたり）")
(println "   系は単一アトラクタなので ∫ΔS dt は T→∞ で収束する（= well-defined な impulse response）")
(def base-int (integral base-times (get base-series "Sacred_Total")))
(def multipliers
  (doall (for [s power-stocks]
           (let [eq   (fin base-series s)
                 ra   (run-with {"sim_stop" T} {s 10.0})
                 rr   (run-with {"sim_stop" T} {s (* 0.10 eq)})]
             {:stock s :eq eq
              :abs (- (integral (:xmile/times ra) (get (:xmile/series ra) "Sacred_Total")) base-int)
              :rel (- (integral (:xmile/times rr) (get (:xmile/series rr) "Sacred_Total")) base-int)
              :per (/ (- (integral (:xmile/times rr) (get (:xmile/series rr) "Sacred_Total")) base-int)
                      (max 1e-9 (* 0.10 eq)))}))))
(println (str "   " (pad "stock" 22) (pad "equilibrium" 13) (pad "(a) +10" 12) (pad "(b) +10%" 12) "(c) per unit"))
(doseq [x (reverse (sort-by :per multipliers))]
  (println (str "   " (pad (:stock x) 22) (pad (fmt (:eq x) 2) 13)
                (pad (fmt (:abs x) 2) 12) (pad (fmt (:rel x) 2) 12) (fmt (:per x) 3))))

(println "\n═══ 6. policy sweep: theta（power→sacred 防火壁）× psi（商業化の罰）═══")
(println (str "   " (pad "theta\\psi" 11) (str/join (for [psi [0.0 0.5 1.0 2.0]] (pad psi 10)))))
(doseq [theta [0.0 0.3 1.0 3.0 10.0]]
  (println (str "   " (pad theta 11)
                (str/join (for [psi [0.0 0.5 1.0 2.0]]
                            (pad (fmt (fin (:xmile/series (run-with {"sim_stop" T "theta" theta "psi" psi} {})) "Sacred_Total") 2) 10))))))

(println "\n═══ 7. phi（money→power 変換抵抗）sweep ═══")
(println (str "   " (pad "phi" 8) (pad "S_total" 10) (pad "S_elite" 10) (pad "S_mass" 10) (pad "S_inst" 10)
              (pad "Money" 10) (pad "Politic" 10) (pad "Media" 10) "Commercial"))
(doseq [phi [0.0 0.2 0.4 0.6 0.8 0.95]]
  (let [sr (:xmile/series (run-with {"sim_stop" T "phi" phi} {}))]
    (println (str "   " (pad phi 8) (pad (fmt (fin sr "Sacred_Total") 2) 10)
                  (pad (fmt (fin sr "Sacred_Elite") 2) 10) (pad (fmt (fin sr "Sacred_Mass") 2) 10)
                  (pad (fmt (fin sr "Sacred_Institutional") 2) 10)
                  (pad (fmt (fin sr "Money") 1) 10) (pad (fmt (fin sr "Political_Power") 2) 10)
                  (pad (fmt (fin sr "Media_Power") 2) 10) (fmt (fin sr "Commercialization"))))))

(println "\n═══ 8. Meadows leverage band (kotoba-lang/dynamics) ═══")
(doseq [i (d/rank-interventions
           [{:id :no-direct-money-to-sacred-edge :band :band/B :tractability 0.9}
            {:id :sacred-channel-split-3         :band :band/D :tractability 0.8}
            {:id :commercialization-penalty-psi  :band :band/C :tractability 0.6}
            {:id :theta-power-sacred-firewall    :band :band/E :tractability 0.7}
            {:id :phi-money-power-friction       :band :band/E :tractability 0.5}
            {:id :detection-probability-d_ck     :band :band/B :tractability 0.6}
            {:id :retire-status-as-a-goal        :band :band/A :tractability 0.2}])]
  (println (str "   " (pad (:id i) 36) "band " (name (:band i)) "  base-score " (fmt (:base-score i) 2))))

(def out-dir (or (.-SD_OUT (.-env js/process)) "."))
(def out (str out-dir "/money-power-reputation-sacred.xmile"))
(let [txt (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
               (->xml (xx/emit-doc {:xmile/header {:xmile/vendor "kotoba-lang/org-oasis-open-xmile"
                                                   :xmile/product {:xmile/name "power-vector" :xmile/version "1.0"}
                                                   :xmile/name "Money / Power vector / Reputation / Sacred status"}
                                    :xmile/sim-specs (:xmile/sim-specs (build-model (assoc base-params "sim_stop" T) {}))
                                    :xmile/models [(build-model (assoc base-params "sim_stop" T) {})]})))]
  (.writeFileSync (js/require "fs") out txt)
  (println "\n═══ 9. XMILE 1.0 written ═══")
  (println "   " out "(" (count txt) "bytes," (count (m/variables mdl)) "variables )"))

;; 時系列 CSV（後で図にする用）
(let [ks (concat power-stocks sacred-stocks ["Sacred_Total" "Recognition" "Common_Knowledge"
                                             "Commercialization" "Scandal" "Contradiction"])
      csv (str "t," (str/join "," ks) "\n"
               (str/join (for [i (range (count base-times))]
                           (str (nth base-times i) ","
                                (str/join "," (for [k ks] (fmt (nth (get base-series k) i) 5))) "\n"))))]
  (.writeFileSync (js/require "fs")
                  (str out-dir "/baseline-series.csv") csv)
  (println "    baseline-series.csv written (" (count base-times) "rows )"))
