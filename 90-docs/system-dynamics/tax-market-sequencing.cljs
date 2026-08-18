(ns tax-market-sequencing
  "日本を『学習市場』として始めたあと、どの順で地域を開けるか。

   問い（オーナー、2026-08-18）: 日本を学習として始めて、それ以降のシナリオも立てる。

   tax-market-need.cljs が『どこにニーズがあるか』の静的な比を出したのに対し、
   ここは『どの順に開けるか』を動かす。加わる機構は 3 つ:

     1. entry_c  = STEP(1, 参入月)   — 地域は同時には開かない
     2. learning = 日本での浸透率の飽和関数 — 学習市場としての日本の唯一の意味
     3. xfer_c   = 学習の移転率        — 日本で学んだことが各国でどれだけ効くか

   ■ 単位について（tax-market-need.cljs と同じ床）
   絶対台数は答えない。p_base は paid 0/200 の rule of three による**上界**で、
   serviceable / q_imitation も未計測。3 つとも全シナリオ共通なので**比では約分される**。
   固定費だけは実額（ADR-2607268000 の実測）なので、両者を混ぜないように
   『固定費効率 = 実額固定費 ÷ 模型単位』という**シナリオ間でだけ比較可能な指数**にして出す。"
  (:require [xmile.model :as m]
            [xmile.validate :as v]
            [xmile.execute :as x]
            [xmile.xml :as xml]
            [dynamics.core :as d]
            ["fs" :as fs]))

(defn r [n x] (let [k (js/Math.pow 10 n)] (/ (js/Math.round (* k x)) k)))
(defn pad [s n] (.padEnd (str s) n))
(defn lpad [s n] (.padStart (str s) n))

(def p-base-upper (d/upper-bound-rate-from-zero-events 200))  ; UPPER BOUND, not an estimate
(def q-imitation 0.03)
(def serviceable 0.02)
(def learn-half 0.05)   ; ASSUMPTION: 日本のプールの 5% に届いた時点で学習が半分終わる

;; ── 注意の希少性 ─────────────────────────────────────────────────────────────
;; 初版はこれを持っていなかった。結果、模型は「ドイツは早く開けるほど良い」と
;; 単調に答えた（m3=6.00 → m36=3.23）。学習の利得が待ちの損失を上回る領域が
;; 存在しなかったからで、これは**順序を決める機構が模型に無い**ということだった。
;; 順序が問題になるのは、同時に開ける市場の数に限りがあるときだけである。
;; attention = capacity / (capacity + live - 1)   （live = 開いている市場数）
;; capacity 1.0 = 1 チームが 1 市場ぶん。2 市場を開ければ各 0.5 になる。
(def capacity-default 1.0)  ; ASSUMPTION

;; ── プールの焼損（premature entry の不可逆な代償）─────────────────────────────
;; 注意の希少性を入れてもなお、模型は「早いほど良い」と答えた（cap=1.0 でも最良 m1）。
;; 当然で、注意は**再配分**されるだけで消えないからである。待つことが正しくなるには
;; 「早く入ると何かが不可逆に失われる」機構が要る。それがこれ:
;;   学習 L の状態で参入すると、その国のプールの burn*(1-L) が**永久に**失われる。
;; 意味: 訴求が固まる前に接触した見込み客は二度と戻らない（税制の説明を間違えた、
;; 投資計画の算定資料が無かった、など）。
;; burn = 0 なら初版と同じ。**この閾値こそが「日本を学習市場にする」判断の本体。**
(def burn-default 0.0)  ; ASSUMPTION（既定は 0 = 焼損しない。下で掃引する）

;; ── 国別入力（tax-market-need.cljs と同一。firms は total-base に揃えた）────────
(def countries
  [{:id "JP" :name "日本"     :firms 3360000  :ai 0.051 :benefit 1.00 :ease 0.35 :salience 0.90
    :urgency 0.85 :access 1.0 :fy 3  :xfer 0.0
    :xfer-src "学習の源。自分自身には移転しない"}
   {:id "DE" :name "ドイツ"   :firms 3200000  :ai 0.200 :benefit 0.89 :ease 1.00 :salience 0.45
    :urgency 0.60 :access 1.0 :fy 12 :xfer 0.8
    :xfer-src "ASSUMPTION 決算期末の型・投資計画の算定資料・自家利用構造がそのまま効く。制度名を差し替えるだけ"}
   {:id "US" :name "米国"     :firms 36200000 :ai 0.088 :benefit 0.85 :ease 1.00 :salience 0.25
    :urgency 0.35 :access 1.0 :fy 12 :xfer 0.5
    :xfer-src "ASSUMPTION 自家利用構造(§469 回避)と決算期末は効くが、salience が低く訴求本体は作り直し"}
   {:id "IT" :name "イタリア" :firms 4600000  :ai 0.200 :benefit 1.29 :ease 0.30 :salience 0.85
    :urgency 0.70 :access 1.0 :fy 12 :xfer 0.7
    :xfer-src "ASSUMPTION 申請制なので日本型の伴走がそのまま効く"}
   {:id "UK" :name "英国"     :firms 5700000  :ai 0.250 :benefit 0.74 :ease 1.00 :salience 0.25
    :urgency 0.35 :access 1.0 :fy 12 :xfer 0.5 :xfer-src "ASSUMPTION 米国と同型"}
   {:id "FR" :name "フランス" :firms 5300000  :ai 0.200 :benefit 0.05 :ease 0.60 :salience 0.30
    :urgency 0.20 :access 1.0 :fy 12 :xfer 0.3 :xfer-src "ASSUMPTION 節税ではなく研究税額控除という別の売り方"}
   {:id "CN" :name "中国"     :firms 52000000 :ai 0.200 :benefit 0.74 :ease 0.95 :salience 0.15
    :urgency 0.40 :access 1.0 :fy 12 :xfer 0.5
    :xfer-src "ASSUMPTION。access は ECCN 判定が非該当だった場合のみ 1"}])

(def by-id (into {} (map (juxt :id identity) countries)))

;; ── 固定費（ADR-2607268000 の実測。実額）──────────────────────────────────────
(def cm-per-unit 1899)          ; 貢献利益 $1,899 = JPY 311,109（固定円建て）
(def fixed
  {"JP" 6274      ; 実測
   "US" 12500     ; 実測
   "DE" 25108     ; 導出: EU 5か国 $28,292 から WEEE 4か国分($796 x 4)を引いた 1 か国分
   "IT" 796 "FR" 796 "UK" 796   ; 導出: 2 か国目以降は WEEE 登録の増分のみ
   "CN" 36000})   ; 実測
(def fixed-shared 55000)        ; engineering $25,000 + campaign $30,000（一度きり）

;; ── シナリオ = 参入月の表（999 = 開けない）──────────────────────────────────
(def scenarios
  [{:id "S0_JP_only"    :label "S0 日本のみ"              :entry {"JP" 0}}
   {:id "S1_JP_DE"      :label "S1 日本 → 独(12)"          :entry {"JP" 0 "DE" 12}}
   {:id "S2_JP_US"      :label "S2 日本 → 米(12)"          :entry {"JP" 0 "US" 12}}
   {:id "S3_JP_DE_US"   :label "S3 日本 → 独(12) → 米(24)" :entry {"JP" 0 "DE" 12 "US" 24}}
   {:id "S4_JP_US_DE"   :label "S4 日本 → 米(12) → 独(24)" :entry {"JP" 0 "US" 12 "DE" 24}}
   {:id "S5_JP_DEUS_par":label "S5 日本 → 独+米 同時(12)"  :entry {"JP" 0 "DE" 12 "US" 12}}
   {:id "S6_ADR_orig"   :label "S6 元ADR: 日→米(12)→EU5(24)"
    :entry {"JP" 0 "US" 12 "DE" 24 "IT" 24 "FR" 24 "UK" 24}}
   {:id "S7_S3_plus_CN" :label "S7 S3 + 中国(36、ECCN 非該当)"
    :entry {"JP" 0 "DE" 12 "US" 24 "CN" 36}}])

(def horizon 60)

(defn const [nm val doc] (m/aux nm (str val) {:xmile/doc doc}))

(defn build-model
  ([sc] (build-model sc capacity-default burn-default))
  ([sc capacity] (build-model sc capacity burn-default))
  ([{:keys [id label entry]} capacity burn]
  (let [live (filter #(contains? entry (:id %)) countries)
        base (-> (m/model id {:xmile/doc label})
                 (m/set-sim-specs (m/sim-specs 0 horizon {:xmile/dt 1.0
                                                          :xmile/time-units "month"
                                                          :xmile/method :euler}))
                 (m/add-variable (const "p_base" (r 5 p-base-upper)
                                        "UPPER BOUND ONLY — paid 0/200 の rule of three。全シナリオ共通なので比では約分される"))
                 (m/add-variable (const "q_imitation" q-imitation "ASSUMPTION Bass 模倣係数。全シナリオ共通"))
                 (m/add-variable (const "serviceable" serviceable "ASSUMPTION 購買可能割合。全シナリオ共通"))
                 (m/add-variable (const "learn_half" learn-half
                                        "ASSUMPTION 日本のプールの 5% に届いた時点で学習が半分終わる"))
                 (m/add-variable (const "burn" burn
                                        "ASSUMPTION 学習前に参入したとき永久に失うプールの割合。0 なら焼損しない"))
                 (m/add-variable (const "capacity" capacity
                                        "ASSUMPTION 同時に持てる市場の数（1 チーム = 1.0）。順序問題はこの希少性からしか生じない"))
                 (m/add-variable
                  (m/aux "live_markets"
                         (clojure.string/join " + " (map #(str "entry_" (:id %)) live))
                         {:xmile/doc "いま開いている市場の数"}))
                 (m/add-variable
                  (m/aux "attention" "capacity / (capacity + live_markets - 1)"
                         {:xmile/doc "1 市場あたりの営業注意。p 側にだけ掛かる（口コミはチームを要さない）"}))
                 (m/add-variable
                  (m/aux "learning"
                         "(adopters_JP / pool0_JP) / ((adopters_JP / pool0_JP) + learn_half)"
                         {:xmile/doc "日本での浸透率の飽和関数 0..1。学習市場としての日本の唯一の出力"})))]
    (reduce
     (fn [mdl {:keys [id benefit ease salience urgency access fy xfer xfer-src firms ai]}]
       (let [P (str "pool_" id) A (str "adopters_" id)
             pool0 (* firms ai serviceable)
             em (get entry id)]
         (-> mdl
             (m/add-variable (const (str "benefit_" id) benefit "初年度税効果 / 日本 = 1.00"))
             (m/add-variable (const (str "ease_" id) ease "節税の行いやすさ = 控除に到達する確率"))
             (m/add-variable (const (str "salience_" id) salience "その制度が『今この箱を買う理由』になるか"))
             (m/add-variable (const (str "urgency_" id) urgency "決算期末 PULSE の増幅"))
             (m/add-variable (const (str "access_" id) access "市場アクセス"))
             (m/add-variable (const (str "fy_" id) fy "決算期末の月"))
             (m/add-variable (const (str "xfer_" id) xfer xfer-src))
             (m/add-variable (const (str "entry_month_" id) em "このシナリオでの参入月"))
             (m/add-variable (const (str "pool0_" id) (r 4 pool0) "初期プール = 企業数 x AI利用率 x 購買可能割合"))
             (m/add-variable
              (m/aux (str "entry_" id) (str "STEP(1, entry_month_" id ")")
                     {:xmile/doc "参入ゲート。開くまで一切の流れを止める"}))
             (m/add-variable
              (m/aux (str "pull_" id)
                     (str "benefit_" id " * ease_" id " * salience_" id " * access_" id)
                     {:xmile/doc "節税が購入を引く力 = 金額 x 行いやすさ x 希少性 x アクセス"}))
             (m/add-variable
              (m/flow (str "spoil_" id)
                     (str P " * burn * (1 - learning) * PULSE(1, entry_month_" id ")")
                     {:xmile/doc "参入の瞬間に、学習が足りないぶんだけプールを永久に焼く"}))
             (m/add-variable
              (m/stock P (str (r 4 pool0)) {:xmile/outflows #{(str "adopt_" id) (str "spoil_" id)}
                                            :xmile/non-negative? true
                                            :xmile/doc "未購入の到達可能企業"}))
             (m/add-variable
              (m/stock A "0" {:xmile/inflows #{(str "adopt_" id)}
                              :xmile/non-negative? true
                              :xmile/doc "累積購入企業"}))
             (m/add-variable
              (m/flow (str "adopt_" id)
                      (str P " * entry_" id " * ( p_base * pull_" id
                           " * (1 + urgency_" id " * PULSE(1, fy_" id ", 12))"
                           " * (1 + xfer_" id " * learning) * attention"
                           " + q_imitation * " A " / (pool0_" id " + 1) )")
                      {:xmile/doc "Bass 拡散。税制レバーは外部係数 p、決算期末は PULSE、日本の学習は xfer で乗る"})))))
     base live))))

(defn doc-of [sc]
  {:xmile/header {:xmile/vendor "com-junkawasaki/root"
                  :xmile/product {:xmile/name "tax-market-sequencing" :xmile/version "1"}
                  :xmile/name (:label sc)}
   :xmile/sim-specs (m/sim-specs 0 horizon {:xmile/dt 1.0 :xmile/time-units "month" :xmile/method :euler})
   :xmile/models [(build-model sc)]})

(defn run-scenario
  ([sc] (run-scenario sc capacity-default burn-default))
  ([sc capacity] (run-scenario sc capacity burn-default))
  ([sc capacity burn]
  (let [mdl (build-model sc capacity burn)
        probs (v/validate mdl)]
    (when (seq (v/errors probs))
      (doseq [e (v/errors probs)] (println "  ERROR" (pr-str e)))
      (throw (ex-info "invalid xmile" {:scenario (:id sc)})))
    (let [{:keys [xmile/series xmile/times]} (x/run mdl)
          cum (fn [t-idx] (reduce + (for [c (keys (:entry sc))]
                                      (nth (get series (str "adopters_" c)) t-idx))))
          idx (fn [month] (first (keep-indexed #(when (= %2 month) %1) times)))]
      {:id (:id sc) :label (:label sc)
       :m12 (cum (idx 12)) :m24 (cum (idx 24)) :m36 (cum (idx 36)) :m60 (cum (idx horizon))
       :learning (last (get series "learning"))
       :fixed (+ fixed-shared (reduce + (map #(get fixed %) (keys (:entry sc)))))}))))

(println "=== 日本を学習市場として始めたあとの参入順シナリオ — XMILE ===\n")
(println "⚠ 台数として引用しない。p_base は paid 0/200 の上界(" (r 5 p-base-upper) ")で点推定ではない。")
(println "  serviceable / q_imitation も未計測。全シナリオ共通なので**比だけ**が答えられる。\n")

(println "--- 実額の算術（模型ではない。ADR-2607268000 の実測固定費 ÷ 貢献利益 $1,899）---")
(println (str (pad "区分" 24) (lpad "固定費 $" 12) (lpad "分岐台数" 10)))
(doseq [[k label] [["shared" "engineering+campaign"] ["JP" "日本"] ["DE" "ドイツ(1か国目)"]
                   ["US" "米国"] ["IT" "EU 2か国目以降/国"] ["CN" "中国"]]]
  (let [f (if (= k "shared") fixed-shared (get fixed k))]
    (println (str (pad label 24) (lpad f 12) (lpad (r 1 (/ f cm-per-unit)) 10)))))

(def results (mapv run-scenario scenarios))
(def s0 (first (filter #(= "S0_JP_only" (:id %)) results)))

(println "\n--- 模型: 60か月の累積採用（S0 日本のみ = 1.00）と 固定費効率 ---")
(println "  固定費効率 = 実額固定費 ÷ 60か月の模型単位。**低いほど良い**。S0 = 1.00 に正規化。")
(println "  p_base が共通なので、この指数はシナリオ間でだけ比較してよい。")
(println (str (pad "シナリオ" 30) (lpad "m12" 8) (lpad "m24" 8) (lpad "m36" 8)
              (lpad "m60" 8) (lpad "固定費$" 10) (lpad "固定費効率" 12)))
(let [eff0 (/ (:fixed s0) (:m60 s0))]
  (doseq [{:keys [label m12 m24 m36 m60 fixed]} results]
    (println (str (pad label 30)
                  (lpad (r 2 (/ m12 (:m60 s0))) 8)
                  (lpad (r 2 (/ m24 (:m60 s0))) 8)
                  (lpad (r 2 (/ m36 (:m60 s0))) 8)
                  (lpad (r 2 (/ m60 (:m60 s0))) 8)
                  (lpad fixed 10)
                  (lpad (r 2 (/ (/ fixed m60) eff0)) 12)))))

(println (str "\n--- 日本の学習 stock（60か月時点）: " (r 3 (:learning s0))
              "  = 移転可能な学習の " (r 1 (* 100 (:learning s0))) "% ---"))
(println "  学習が飽和する前に次を開けると xfer が効かない。これが『日本を先に』の唯一の機構的な理由。")

;; 学習飽和の感度: 参入月を変えたときの S1 の 60か月累積
(println "\n--- 感度: ドイツの参入月 x 注意の容量（S1 系。各 capacity の S0 = 1.00）---")
(println "  capacity 1.0 = 1 チームが 1 市場ぶん / 99 = 注意は希少でない（初版の暗黙の前提）")
(println (str (pad "DE 参入月" 12)
              (lpad "cap=1.0" 10) (lpad "cap=1.5" 10) (lpad "cap=2.0" 10) (lpad "cap=99" 10)))
(let [caps [1.0 1.5 2.0 99.0]
      base (into {} (for [c caps] [c (:m60 (run-scenario {:id "S0" :label "" :entry {"JP" 0}} c))]))]
  (doseq [em [3 6 9 12 18 24 30 36 48]]
    (println (str (pad (str "m" em) 12)
                  (apply str (for [c caps]
                               (lpad (r 2 (/ (:m60 (run-scenario {:id (str "S1_" em) :label ""
                                                                  :entry {"JP" 0 "DE" em}} c))
                                             (get base c))) 10))))))
  (println "\n  各 capacity で最良の参入月（burn=0）:")
  (doseq [c caps]
    (let [best (apply max-key second
                      (for [em (range 1 49)]
                        [em (/ (:m60 (run-scenario {:id "x" :label "" :entry {"JP" 0 "DE" em}} c))
                               (get base c))]))]
      (println (str "    capacity " (lpad c 5) " → 最良 m" (first best)
                    "（S0 比 " (r 2 (second best)) "）")))))

(println "\n--- 決定的な掃引: プールの焼損 burn を動かす（capacity=1.0）---")
(println "  『訴求が固まる前に接触した見込み客のうち、永久に失う割合』を burn とする。")
(println "  待つことが正しくなる閾値を探す。内点解が出れば『日本を学習市場に』は機構として成立する。")
(println (str (pad "burn" 10) (lpad "最良の参入月" 14) (lpad "S0 比" 10)
              (lpad "m1 の値" 10) (lpad "m24 の値" 10)))
(doseq [b [0.0 0.2 0.4 0.6 0.8 0.9 0.95]]
  (let [b0 (:m60 (run-scenario {:id "S0" :label "" :entry {"JP" 0}} 1.0 b))
        curve (for [em (range 1 49)]
                [em (/ (:m60 (run-scenario {:id "x" :label "" :entry {"JP" 0 "DE" em}} 1.0 b)) b0)])
        best (apply max-key second curve)]
    (println (str (pad b 10) (lpad (str "m" (first best)) 14) (lpad (r 2 (second best)) 10)
                  (lpad (r 2 (second (first curve))) 10)
                  (lpad (r 2 (second (nth curve 23))) 10)))))

(doseq [sc scenarios]
  (let [path (str "90-docs/system-dynamics/tax-market-sequencing-" (:id sc) ".xmile")]
    (fs/writeFileSync path (xml/emit-string (doc-of sc)))))
(println (str "\nwrote " (count scenarios) " xmile files: 90-docs/system-dynamics/tax-market-sequencing-*.xmile"))
(println "再現: nbb --classpath \"orgs/kotoba-lang/org-oasis-open-xmile/src:orgs/kotoba-lang/dsl-core/src:orgs/kotoba-lang/dynamics/src\" 90-docs/system-dynamics/tax-market-sequencing.cljs")
