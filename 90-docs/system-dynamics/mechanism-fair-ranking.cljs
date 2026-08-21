;; mechanism-fair-ranking.cljs — 8 機序に余剰コスト経路を入れた公平な順位。ADR-2608090300。

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
   ["Customers"               1.0   20.0  0.10]
   ["Credit"                  1.0   25.0  0.00]
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
   "Moral_Authority" "Legitimacy" "Customers" "Credit"])

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

   "w_elite" 0.30 "w_mass" 0.45 "w_inst" 0.25

   ;; ══ 金くささの分解（ADR-2608082100）══════════════════════════════
   ;; 8 つの機序。すべて [0,1] の政策選択で、営利かどうかとは独立。
   "a_extract"     0.20   ; 取り分 — 創出した価値のうち自分が取る割合
   "a_asym"        0.20   ; 情報非対称の利用 — 相手が知らないことで儲ける
   "a_lockin"      0.20   ; 退出コスト — 相手が離れられない
   "a_extern"      0.20   ; 外部化 — 費用を第三者が払う
   "a_nonconsent"  0.20   ; 非同意 — 相手が選んでいない（監視・ダークパターン）
   "a_mismatch"    0.20   ; 目的と収益源のずれ — 「利用者が商品」（広告モデル）
   "a_commod"      0.20   ; 贈与の商品化 — 値段が付くと価値が壊れるものに値段を付ける
   "a_ask"         0.20   ; 訴求の可視量 — 表面のどれだけが「払え」か

   ;; 機序ごとの重み（Misalignment への寄与）
   "m_extract" 0.10 "m_asym" 0.15 "m_lockin" 0.20 "m_extern" 0.12
   "m_nonconsent" 0.15 "m_mismatch" 0.18 "m_commod" 0.06 "m_ask" 0.04

   ;; 各機序が「相手に残る余剰」を直接どれだけ削るか（ADR-2608090300）。
   ;; これを入れる前は a_extract だけが余剰経路を持ち、他 7 つは正統性経路しか
   ;; 持たなかったため、順位表が比較として成立していなかった（ADR-2608082100 が
   ;; 自ら「読んではいけない」と明記）。値は全部同じにしない —— 同じにすると
   ;; 「余剰コストは機序によらず等しい」という別の仮定を置くことになる。
   "s_extract"     1.00   ; 取り分は定義上そのまま余剰を減らす
   "s_asym"        0.50   ; 情報レント分だけ相手が過払いする
   "s_lockin"      0.40   ; 乗り換えられない分の逸失
   "s_extern"      0.15   ; 費用の大半は第三者だが一部は相手にも返る
   "s_nonconsent"  0.30   ; 監視の負効用
   "s_mismatch"    0.35   ; 広告負荷が受け取る製品の質を下げる
   "s_commod"      0.20   ; 贈与関係の喪失
   "s_ask"         0.15

   ;; 収益
   "rev_scale"        1.60   ; 規模（顧客あたりの収益強度）
   "g_customers"      5.0
   "cust_from_legit"  0.55 "cust_from_rec" 0.45
   "exit_base"        0.35   ; 離脱率の素の大きさ
   "contrib_delivery" 0.90   ; 整合した収益 → 実際の貢献 への変換
   "stink_to_detect"  0.60

   ;; ══ 金融（ADR-2608082300）═══════════════════════════════════════
   ;; 既定は「固定利息 = 直交」—— 成長にプラス、正統性には中立。
   ;; 金利を悪と決め打ちしない。トレードオフが出るかを計算で確かめる。
   "i_rate"          0.06   ; 名目金利
   "share_frac"      0.00   ; 損益分担の割合（0=純固定利息, 1=純ムダーラバ）
   "distress_mult"   1.50   ; 困窮時の実効金利の加速（延滞損害金・リボ）
   "monitor_cost"    0.35   ; 損益分担が要するモニタリング費用（劣後の理由）
   "g_credit"        6.0    ; 与信の規模係数
   "debt_burden_k"   8.0    ; 債務返済負担の尺度（営業能力と比較可能にする）
   "supply_base"     0.15   ; 金利ゼロでも貸す分（贈与・エクイティ・政策金融）
   "supply_slope"    6.0    ; 金利が呼び込む供給
   "credit_demand_e" 3.0    ; 金利に対する需要の弾力性
   "credit_to_cap"   0.85   ; 信用 → 制度能力（成長チャネル）
   "repay_rate"      0.18
   "lender_take_k"   2.0

   ;; ══ 証券・トレード（ADR-2608082400）════════════════════════════
   ;; 同じ活動に整合（流動性供給）と逆行（情報優位の搾取）が同居している。
   ;; ★ 構造的仮説: 流動性の便益は飽和するが、搾取は飽和しない。
   ;;   この非対称が最適回転率を生む。仮説であって導出ではない。
   "turnover"        1.0    ; 回転率（発行残高に対する売買代金）
   "K_liq"           1.2    ; 流動性便益の半飽和点
   "L_max"           1.0
   "liq_benefit"     1.40   ; 流動性 → 発行コスト低下
   "base_issue_cost" 1.0
   "g_primary"       5.0    ; 発行市場から実体へ入る資本
   "transparency"    0.50   ; ★ policy: 執行の透明性（0=暗い, 1=完全に見える）
   "info_asym"       0.80   ; 情報優位の素の大きさ
   "extract_k"       0.55   ; 搾取の係数
   "primary_to_cap"  0.90

   ;; ══ 高成長企業に共通する agent 要素（ADR-2608090200）═══════════
   ;; 企業ごとの数値は持っていないので、機序を一般形で入れて効き目だけを測る。
   "scale_econ"   0.55   ; 規模の経済: 規模が単位費用を下げる（Amazon/Netflix）
   "price_pass"   0.60   ; 費用低下を価格に還元する割合（フライホイールの要）
   "dq_k"         0.50   ; データ品質ループ: 利用×データ → 品質（Google/Meta）
   "learn_k"      0.45   ; 学習効果: 反復が単位費用を下げる（SpaceX 再利用）
   "net_k"        0.50   ; ネットワーク安全性: 参加 → 信頼（Bitcoin）
   "cap_loop_k"   0.50   ; 能力→収益→計算→能力（Anthropic 型）
   "integ_k"      0.45}) ; 統合による体験プレミアム（Apple）

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
                           (cond
                             (= s "Money") "spend_rate * Money"
                             (= s "Customers") "Exit_Rate * Customers"
                             (= s "Credit") "repay_rate * Credit"
                             :else
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
                                   "Operating_Capacity + contrib_delivery * Aligned_Delivery + Data_Quality + Learning + Capability_Loop"))

            ;; ── 不正・露見（B1 balancing loop）──────────────────────
            (m/add-variable (m/aux "Manipulation"
                                   "manip_gain * manip_frac * Financial_Capacity + manip_info * Information_Power"))
            (m/add-variable (m/aux "Detection_Probability"
                                   "MIN(1, d_base + d_ck * Common_Knowledge + d_data * n_Data_Power)"))
            (m/add-variable (m/aux "Scandal" "(Manipulation + stink_to_detect * Money_Stink) * Detection_Probability"))

            ;; ── 神聖性を削る 3 つの別々の力 ─────────────────────────
            ;; 商業化: 貢献に裏打ちされない可視性 + 露骨な操作
            (m/add-variable (m/aux "Commercialization" "Money_Stink"))
            ;; ── 金くささの分解 ─────────────────────────────────────
            ;; 逆行度 = 8 機序の重み付き和（重みの和で正規化）。[0,1]
            (m/add-variable (m/aux "Misalignment"
                                   (str "(m_extract*a_extract + m_asym*a_asym + m_lockin*a_lockin"
                                        " + m_extern*a_extern + m_nonconsent*a_nonconsent"
                                        " + m_mismatch*a_mismatch + m_commod*a_commod + m_ask*a_ask)"
                                        " / (m_extract + m_asym + m_lockin + m_extern"
                                        " + m_nonconsent + m_mismatch + m_commod + m_ask)")))
            ;; 整合度 [-1,+1]: +1 = 相手の価値と同方向、-1 = 逆方向
            (m/add-variable (m/aux "Alignment" "1 - 2 * Misalignment"))
            ;; 総価値・取り分・相手の取り分を分ける。取り分を増やすと自分の収益は
            ;; 増えるが相手に残る余剰は減る —— 両方を同じ量にすると「取り分を
            ;; 増やすほど相手に届く価値も増える」という辻褄の合わないモデルになる。
            (m/add-variable (m/aux "Value_Created" "rev_scale * n_Customers"))
            (m/add-variable (m/aux "Revenue_Intensity" "Value_Created * a_extract"))
            ;; 8 機序すべてが相手の余剰を削る（係数は機序ごとに違う）
            (m/add-variable (m/aux "Surplus_Loss"
                                   (str "s_extract*a_extract + s_asym*a_asym + s_lockin*a_lockin"
                                        " + s_extern*a_extern + s_nonconsent*a_nonconsent"
                                        " + s_mismatch*a_mismatch + s_commod*a_commod + s_ask*a_ask")))
            (m/add-variable (m/aux "Counterparty_Surplus"
                                   "Value_Created * MAX(0, 1 - Surplus_Loss)"))
            ;; ★ 金くささ = 自分の取り分 × 逆行度。収益そのものではない
            (m/add-variable (m/aux "Money_Stink"
                                   "psi * (Revenue_Intensity * MAX(0, -Alignment) + Lender_Take * MAX(0, -Finance_Alignment) + Trade_Extraction)"))
            ;; ★ 整合しているとき、相手に残った余剰が実際の貢献になる
            (m/add-variable (m/aux "Aligned_Delivery"
                                   "Counterparty_Surplus * MAX(0, Alignment) + Take_Share * MAX(0, Finance_Alignment) + Liquidity"))
            ;; ロックインは離脱を止める —— 正統性を壊しながら顧客を保持する
            (m/add-variable (m/aux "Exit_Rate"
                                   "exit_base * (1 - a_lockin) * (1 - n_Legitimacy)"))
            ;; ── 高成長の agent 要素 ─────────────────────────────────
            ;; ① 規模の経済 → 価格還元 → 需要（フライホイール）
            (m/add-variable (m/aux "Unit_Cost" "1 / (1 + scale_econ * n_Customers + learn_k * n_Institutional_Power)"))
            (m/add-variable (m/aux "Price_Cut" "price_pass * (1 - Unit_Cost)"))
            ;; ② データ品質ループ（利用 × データ）
            (m/add-variable (m/aux "Data_Quality" "dq_k * n_Data_Power * n_Attention"))
            ;; ③ 学習効果（累積の代理として制度能力）
            (m/add-variable (m/aux "Learning" "learn_k * n_Institutional_Power"))
            ;; ④ ネットワーク安全性（参加 → 信頼）
            (m/add-variable (m/aux "Network_Trust" "net_k * n_Coordination_Power^0.5 * n_Customers^0.5"))
            ;; ⑤ 能力→収益→計算→能力
            (m/add-variable (m/aux "Capability_Loop" "cap_loop_k * n_Technological_Power * n_Money"))
            ;; ⑥ 統合による体験プレミアム
            (m/add-variable (m/aux "Integration_Premium" "integ_k * n_Technological_Power^0.5 * n_Cultural_Power^0.5"))
            ;; ── 証券・トレード ──────────────────────────────────────
            ;; 整合の側: 流動性は「必要なときに取引できる」という便益。飽和する。
            (m/add-variable (m/aux "Liquidity" "L_max * turnover / (K_liq + turnover)"))
            ;; 流動性は発行コストを下げ、実体に届く資本を増やす
            (m/add-variable (m/aux "Issuance_Cost"
                                   "base_issue_cost / (1 + liq_benefit * Liquidity)"))
            (m/add-variable (m/aux "Primary_Issuance"
                                   "g_primary * n_Legitimacy / (0.2 + Issuance_Cost)"))
            ;; 逆行の側: 情報優位の搾取。回転率に比例して増え、飽和しない
            (m/add-variable (m/aux "Info_Edge" "info_asym * (1 - transparency)"))
            (m/add-variable (m/aux "Trade_Extraction" "extract_k * turnover * Info_Edge"))
            ;; 取引の正味の向き
            (m/add-variable (m/aux "Trade_Alignment" "Liquidity - Trade_Extraction"))
            ;; ── 金融 ────────────────────────────────────────────────
            ;; 営業能力（金融由来を含まない）。返済余力はこれで測る ——
            ;; Genuine_Contribution で測ると Coverage → Distress →
            ;; Finance_Alignment → Aligned_Delivery → Genuine_Contribution の
            ;; 代数ループになる（実測で踏んだ）。債務返済能力は本来
            ;; 営業キャッシュフローに対して測るものなので、これが正しい定義でもある。
            (m/add-variable (m/aux "Operating_Capacity"
                                   "c_inst * n_Institutional_Power + c_tech * n_Technological_Power + c_coord * n_Coordination_Power"))
            ;; 返済余力: 生み出す価値に対する債務の重さ
            (m/add-variable (m/aux "Debt_Service" "debt_burden_k * i_rate * n_Credit"))
            (m/add-variable (m/aux "Coverage"
                                   "Operating_Capacity / (0.05 + Debt_Service)"))
            (m/add-variable (m/aux "Distress" "MAX(0, 1 - Coverage)"))
            ;; ★ 逆行の核: 困窮するほど実効金利が上がる
            (m/add-variable (m/aux "Effective_Rate"
                                   "i_rate * (1 + distress_mult * Distress)"))
            ;; 貸手の取り分: 固定分 と 損益分担分
            (m/add-variable (m/aux "Take_Fixed"
                                   "lender_take_k * (1 - share_frac) * Effective_Rate * n_Credit"))
            (m/add-variable (m/aux "Take_Share"
                                   "lender_take_k * share_frac * Counterparty_Surplus"))
            (m/add-variable (m/aux "Lender_Take" "Take_Fixed + Take_Share"))
            ;; 損益分担はモニタリング費用を要する（ムダーラバが規模で劣る理由）
            (m/add-variable (m/aux "Monitoring_Drag"
                                   "monitor_cost * share_frac * n_Credit"))
            ;; ★ 金融の整合度: 損益分担は正、困窮加速は負、固定利息は 0（直交）
            (m/add-variable (m/aux "Finance_Alignment"
                                   "share_frac - distress_mult * Distress"))
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
                                    "g_institution * (a_pol_inst * n_Political_Power + a_coord_inst * n_Coordination_Power) + credit_to_cap * n_Credit - Monitoring_Drag + primary_to_cap * Primary_Issuance"))
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
            ;; 与信は供給と需要の積。金利は供給を呼び込み、需要を抑える。
            ;; 金利ゼロなら supply_base（贈与・エクイティ・政策金融）しか出てこない ——
            ;; これが「金利があるほど成長が大きい」の機構。
            (m/add-variable (m/aux "Credit_Supply"
                                   "supply_base + supply_slope * i_rate * (1 - share_frac * 0.3)"))
            (m/add-variable (m/aux "Credit_Demand"
                                   "1 / (1 + credit_demand_e * Effective_Rate)"))
            (m/add-variable (m/flow "credit_in"
                                    "g_credit * n_Legitimacy * MIN(Credit_Supply, 1.0) * Credit_Demand"))
            (m/add-variable (m/flow "customers_in"
                                    "g_customers * (cust_from_legit * n_Legitimacy + cust_from_rec * Recognition + Price_Cut + Integration_Premium)"))
            (m/add-variable (m/flow "legitimacy_in"
                                    "g_legitimacy * (a_inst_legit * n_Institutional_Power + a_ck_legit * Common_Knowledge + a_contrib_legit * Genuine_Contribution + Network_Trust)"))

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
               :neg (str "(-(" (jx (nth e 1)) "))")   ;; 内側が負リテラルだと (--1.0) になり JS として不正
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
                          (aset tmp i (max 0.0 (+ (aget y i) (* 0.5 dt (aget k i)))))))
        (let [k (derivs tmp (+ t (* 0.5 dt)) dt)]
          (dotimes [i nn] (aset acc i (+ (aget acc i) (* 2 (aget k i))))
                          (aset tmp i (max 0.0 (+ (aget y i) (* 0.5 dt (aget k i)))))))
        (let [k (derivs tmp (+ t (* 0.5 dt)) dt)]
          (dotimes [i nn] (aset acc i (+ (aget acc i) (* 2 (aget k i))))
                          (aset tmp i (max 0.0 (+ (aget y i) (* dt (aget k i)))))))
        (let [k (derivs tmp (+ t dt) dt)]
          (dotimes [i nn] (aset acc i (+ (aget acc i) (aget k i)))))
        (dotimes [i nn] (aset y i (max 0.0 (+ (aget y i) (* (/ dt 6.0) (aget acc i))))))
        (let [cur (stot)] (swap! area + (* 0.5 dt (+ @prev cur))) (reset! prev cur))))
    {:S (stot) :int @area
     :eq (into {} (map-indexed (fn [i s] [s (aget y i)]) stocks))})))

;; ═══════════════════════════════════════════════════════════════════════
;; 8 機序の公平な順位 —— 各機序が余剰も正統性も削る形にしてから測る
;; ═══════════════════════════════════════════════════════════════════════
(def T 200.0) (def DT 1.0)
(def MECH ["a_extract" "a_asym" "a_lockin" "a_extern" "a_nonconsent" "a_mismatch" "a_commod" "a_ask"])
(def JA {"a_extract" "取り分" "a_asym" "情報非対称" "a_lockin" "退出コスト" "a_extern" "費用の外部化"
         "a_nonconsent" "非同意・監視" "a_mismatch" "目的と収益源のずれ" "a_commod" "贈与の商品化" "a_ask" "訴求の可視量"})
(def MW ["m_extract" "m_asym" "m_lockin" "m_extern" "m_nonconsent" "m_mismatch" "m_commod" "m_ask"])
(def SW ["s_extract" "s_asym" "s_lockin" "s_extern" "s_nonconsent" "s_mismatch" "s_commod" "s_ask"])
(defn lcg [seed] (let [st (atom seed)] (fn [] (swap! st #(mod (+ (* 1103515245 %) 12345) 2147483648)) (/ @st 2147483648.0))))
(defn go [p over]
  (let [c (compile-model (assoc (merge p over) "sim_stop" T "sim_dt" DT))
        r (simulate c {} (:sidx c) T DT)]
    {:S (:S r) :G (get (:eq r) "Institutional_Power") :C (get (:eq r) "Customers")}))
(defn pad [s n] (.padEnd (str s) n))
(defn f2 [x] (.toFixed (js/Number x) 2))

(def base (go base-params {}))
(println "基準: 正統性" (f2 (:S base)) " 成長" (f2 (:G base)) " 顧客" (f2 (:C base)) "\n")
(println "═══ 各機序を 0.20 → 0.70 にしたときの代償（余剰経路を入れた後）═══")
(println (str "  " (pad "機序" 24) (pad "Δ正統性" 12) (pad "Δ成長" 12) (pad "Δ顧客" 12) "余剰係数"))
(doseq [m MECH]
  (let [r (go base-params {m 0.70})]
    (println (str "  " (pad (get JA m) 24) (pad (f2 (- (:S r) (:S base))) 12)
                  (pad (f2 (- (:G r) (:G base))) 12) (pad (f2 (- (:C r) (:C base))) 12)
                  (get base-params (str "s_" (subs m 2)))))))

;; ── 重み m_* と余剰係数 s_* の両方を振って順位の安定性を見る ────────────
(def N (js/parseInt (or (.-N (.-env js/process)) "40")))
(println "\n═══ 重み m_* と余剰係数 s_* を両方振る（N=" N "）═══")
(def rnd (lcg 20260904))
(def worst (atom (zipmap MECH (repeat 0))))
(def pays (atom (zipmap MECH (repeat 0))))
(def valid (atom 0))
(dotimes [_ N]
  (let [w (into {} (concat (for [k MW] [k (+ 0.02 (* 0.30 (rnd)))])
                           (for [k SW] [k (+ 0.05 (* 0.95 (rnd)))])))
        p (merge base-params w)
        b (go p {})]
    (when (> (:S b) 0.05)
      (swap! valid inc)
      (let [d (into {} (for [m MECH] [m (go p {m 0.70})]))
            wst (first (first (sort-by #(:S (second %)) d)))]
        (swap! worst update wst inc)
        (doseq [m MECH] (when (> (:C (d m)) (:C b)) (swap! pays update m inc)))))))
(println "  有効 draw:" @valid)
(println "\n  「最も正統性を壊す機序」に選ばれた回数:")
(doseq [[m c] (reverse (sort-by second @worst))]
  (println (str "    " (pad (get JA m) 24) c "/" @valid "  " (f2 (* 100.0 (/ c (max 1 @valid)))) "%")))
(println "\n  「顧客を増やした（＝儲かる）機序」の回数:")
(doseq [[m c] (reverse (sort-by second @pays))]
  (println (str "    " (pad (get JA m) 24) c "/" @valid "  " (f2 (* 100.0 (/ c (max 1 @valid)))) "%")))
