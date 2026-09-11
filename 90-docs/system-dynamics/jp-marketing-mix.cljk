(ns jp-marketing-mix
  "MK-1 日本市場のマーケティング・ミックス。OASIS XMILE 1.0。

   問い（オーナー、2026-08-18）: 郵送DM・電柱・屋外・交通・Google/LINE/Meta/X/YouTube・
   ゴルフカート・雑誌・新聞・ローカルTV を比較する。XMILE で分析する。

   ■ なぜ『1 人あたり単価』の比較では誤るか
   チャネルはファネルの**別々の段**に効く。同じ物差しで並べると、認知しか作れない
   媒体と、引き合いまで作れる媒体が同じ列に並ぶ。

     未認知 --(認知を作る媒体)--> 認知 --(引き合いを作る媒体)--> 引き合い --> 受注
                                   |
                                 忘却（掲出を止めると減衰する）

   - ゴルフカート / 新聞 / ローカルTV : 未認知→認知 **のみ**（搭載量が低い）
   - 東商新聞 / 日経トップリーダー     : 認知 **かつ** 引き合い（読み物）
   - 郵送DM                            : 未認知→引き合いへ**直行**（名指し + 返信はがき）
                                         ただし **プールが有限（1,007 社）で減る**
   - Google 検索                        : 認知→引き合い **のみ**。
                                         **`認知` stock に比例する = 認知がゼロだと効かない**

   最後の 1 点がこの模型の要である。**検索広告だけに出すと、認知の在庫が無いので
   空回りする。**表計算では出ない構造で、stock を持って初めて見える。

   ■ 実測（2026-08-18 調査、すべて相場・公表値）
   - 東商新聞: 広告 **50,600円〜**（突出し2段モノクロ）/ **8万社超の経営者**が購読 / 月1回
               全会員へのチラシ同封 DM サービス **935,000円/回**
   - 日経トップリーダー: 4色1P **770,000円** / 発行 **35,600部**(2024) /
               **読者の 83.8% が経営者層、6割が社長、従業員100人未満が70%超**
   - Golfcart Vision: 最低 **300,000円** / 首都圏近郊 **95ゴルフ場** / 月間リーチ **42.7万人** /
               **ゴルファーの 40% が部長職以上**
   - 郵送DM: 150-170円/通 × 1,007社（住所は実測で 99.7% 保有）
   - 新聞: 全国紙 15段 数千万円（5段 767万円の例）/ 地方紙は半額以下
   - ローカルTV: スポット15秒 1本 1万円〜 / 制作+放映 30万円〜 / タイムCM 月50万円〜
   - ラジオ: スポット 1本2万円〜 / タイム 3ヶ月契約 最低10万円〜

   ■ 答えられないこと
   転換率は 1 つも実測していない（murakumo の funnel は 訪問→run→**paid 0**）。
   下の p-* は全部 ASSUMPTION で、**全シナリオ共通**。したがって
   **答えられるのはシナリオ間の順位だけ**であって、受注台数ではない。"
  (:require [xmile.model :as m]
            [xmile.validate :as v]
            [xmile.execute :as x]
            [xmile.xml :as xml]
            [clojure.string :as str]
            ["fs" :as fs]))

(defn r1 [x] (/ (js/Math.round (* 10 x)) 10))
(defn r2 [x] (/ (js/Math.round (* 100 x)) 100))
(defn yen [x] (str "¥" (.toLocaleString (js/Math.round x) "en-US")))
(defn pad [s n] (.padEnd (str s) n))
(defn lp [s n] (.padStart (str s) n))

;; ── チャネル定義（費用は実測、係数は ASSUMPTION）─────────────────────────
;; reach       : 月あたり到達する「人」
;; purity      : そのうち我々の対象（中小の製造・建設・専門サービスの決裁者）の割合
;; to-aware    : 到達した対象のうち認知に変わる割合（搭載量が低いほど小さい）
;; to-lead     : 到達した対象のうち **直接** 引き合いに変わる割合（読み物・名指しのみ >0）
;; pool-limited: 名簿を消費するか
(def channels
  {"toshoshimbun"  {:label "東商新聞 広告"        :cost 50600   :reach 80000  :purity 0.35
                    :to-aware 0.05 :to-lead 0.001 :pool-limited false
                    :src "実測: 50,600円〜 / 8万社超の経営者 / 月1回"}
   "toshoshimbun-dm" {:label "東商新聞 DM同封"     :cost 935000  :reach 80000  :purity 0.35
                    :to-aware 0.25 :to-lead 0.006 :pool-limited false
                    :src "実測: 935,000円/回 で全会員へチラシ同封。**搭載量が広告より高い**"}
   "nikkei-tl"     {:label "日経トップリーダー 1P" :cost 770000  :reach 35600  :purity 0.60
                    :to-aware 0.20 :to-lead 0.005 :pool-limited false
                    :src "実測: 770,000円 / 35,600部 / 読者の83.8%が経営者・6割が社長・70%超が従業員100人未満"}
   "golfcart"      {:label "ゴルフカート広告"      :cost 300000  :reach 427000 :purity 0.12
                    :to-aware 0.03 :to-lead 0.0 :pool-limited false
                    :src "実測: 最低30万円 / 月42.7万人 / 40%が部長職以上（うち我々の業種は一部）"}
   "local-tv"      {:label "ローカルTV スポット"   :cost 500000  :reach 300000 :purity 0.03
                    :to-aware 0.02 :to-lead 0.0 :pool-limited false
                    :src "実測: タイムCM 月50万円〜。純度は一般視聴者なので低い"}
   "newspaper"     {:label "地方紙 5段"           :cost 1500000 :reach 500000 :purity 0.04
                    :to-aware 0.03 :to-lead 0.0 :pool-limited false
                    :src "実測: 全国紙5段 767万円の例。地方紙は半額以下"}
   "senryaku"      {:label "TKC 戦略経営者 1/3P"  :cost 250000  :reach 140000 :purity 0.32
                    :to-aware 0.12 :to-lead 0.003 :pool-limited false
                    :src "実測: 1/3P 250,000円（記事中1P 600,000 / 表4 900,000）/ 月刊 14万部 / **会長・社長・役員 90.9%** / 年間予約購読で**経営者の自宅に直送** / 読者は TKC 会員税理士の関与先 —— **顧問税理士がゲートキーパーという我々の仮説と完全一致**"}
   "senryaku-1p"   {:label "TKC 戦略経営者 記事中1P" :cost 600000 :reach 140000 :purity 0.32
                    :to-aware 0.22 :to-lead 0.006 :pool-limited false
                    :src "実測: 記事中1ページ4色 600,000円。同上"}
   "hojinkai"      {:label "法人会 会報（首都圏5会）" :cost 500000 :reach 100000 :purity 0.35
                    :to-aware 0.10 :to-lead 0.003 :pool-limited false
                    :src "実測: 全国441会・**会員約80万社**。媒体は単位会ごとで**料金は要見積**。首都圏主要5会で会員10万社・各10万円と仮定（ASSUMPTION）。**税務の文脈＝即時償却の話と直結**"}
   "doyukai"       {:label "中小企業家しんぶん"      :cost 150000  :reach 50000  :purity 0.40
                    :to-aware 0.10 :to-lead 0.003 :pool-limited false
                    :src "実測: 中同協 会員 4.7万社超・月3回発行・約5万部。**料金は要見積**（15万円と仮定、ASSUMPTION）"}
   "nikkan-kogyo"  {:label "日刊工業新聞 5段"       :cost 1451250 :reach 422607 :purity 0.10
                    :to-aware 0.04 :to-lead 0.0005 :pool-limited false
                    :src "実測: 1段 290,250円 × 5 / 422,607部（2021-02）/ 製造業中心の産業紙。**読者は製造業関係者だが経営者比率は低い**"}
   "google"        {:label "Google 検索広告"      :cost 50000   :reach 0      :purity 0
                    :to-aware 0 :to-lead 0 :pool-limited false :intent 0.0005
                    :src "CPC 300-2,000円。**認知 stock からしか引けない**"}
   "dm"            {:label "郵送DM（名簿）"        :cost 113410  :reach 373    :purity 1.0
                    :to-aware 0.6 :to-lead 0.012 :pool-limited true
                    :src "実測: 150-170円/通 × B 373社。返信率 0.5-2% の中央 1.2% を to-lead に使用"}})

(def named-pool 1007)      ; 名簿の総数（実測）
(def broad-pool 300000)    ; 首都圏の対象決裁者の粗い母集団（ASSUMPTION）
;; ⚠ 初版はこれを定義して**使っていなかった**。結果、認知が毎月ゼロから積み上がり、
;; 36 か月で受注 2,884 件 —— **市場より多い**という値を返した。同じ 8 万人の読者を
;; 毎月「新規に認知した」と数えていたためである。**飽和 (1 - aware/pool) を掛ける。**
(def p-forget 0.05)        ; ASSUMPTION 認知の月次減衰（掲出を止めれば忘れられる）
(def p-close 0.05)         ; ASSUMPTION 引き合い→受注
;; ⚠ 初版は Google の intent を「認知の 1%/月が検索して引き合いになる」と置いた。
;; 過大だった —— 36 か月で受注 2,787 件、**murakumo が作れる台数も市場も超える**値を
;; 返した。0.05%/月 に下げた。**それでも絶対値は引用しない。**下の表は S1 を 1.00 と
;; した指数で読む。
(def horizon 36)

(defn const [nm v doc] (m/aux nm (str v) {:xmile/doc doc}))

;; ⚠ XMILE の式パーサはハイフンを**引き算**として読む。`reach_nikkei-tl` は
;; `reach_nikkei` - `tl` になり dangling-ref で落ちる（実際に落ちた）。
;; 変数名に使うキーは必ずこれを通す。
(defn vn [k] (str/replace (str k) "-" "_"))

(defn build [scenario-id spend]   ; spend = {channel-key 月額の係数 0..1}
  (let [live (filter #(pos? (get spend (key %) 0)) channels)
        base (-> (m/model scenario-id)
                 (m/set-sim-specs (m/sim-specs 0 horizon {:xmile/dt 1.0 :xmile/time-units "month"
                                                          :xmile/method :euler}))
                 (m/add-variable (const "p_forget" p-forget "ASSUMPTION 認知の月次減衰"))
                 (m/add-variable (const "pool" broad-pool "ASSUMPTION 首都圏の対象決裁者の母集団。認知はここで飽和する"))
                 (m/add-variable (const "p_close" p-close "ASSUMPTION 引き合い→受注"))
                 (m/add-variable (m/stock "aware" "0" {:xmile/inflows #{"gain_aware"}
                                                       :xmile/outflows #{"forget"}
                                                       :xmile/non-negative? true
                                                       :xmile/doc "認知している対象者"}))
                 (m/add-variable (m/stock "lead" "0" {:xmile/inflows #{"gain_lead"}
                                                      :xmile/outflows #{"convert"}
                                                      :xmile/non-negative? true
                                                      :xmile/doc "連絡先が取れた引き合い"}))
                 (m/add-variable (m/stock "customer" "0" {:xmile/inflows #{"convert"}
                                                          :xmile/non-negative? true
                                                          :xmile/doc "受注"}))
                 (m/add-variable (m/stock "named_pool" (str named-pool)
                                          {:xmile/outflows #{"dm_use"} :xmile/non-negative? true
                                           :xmile/doc "未接触の名簿。DM は**これを消費する**"}))
                 (m/add-variable (m/flow "forget" "aware * p_forget" {:xmile/doc "認知の減衰"}))
                 (m/add-variable (m/flow "convert" "lead * p_close" {:xmile/doc "引き合い→受注"})))
        ;; チャネル定数
        withc (reduce (fn [mdl [k c]]
                        (let [s (get spend k 0)]
                          (-> mdl
                              (m/add-variable (const (str "spend_" (vn k)) (* s (:cost c)) (:src c)))
                              (m/add-variable (const (str "reach_" (vn k)) (* s (:reach c)) "月間到達"))
                              (m/add-variable (const (str "purity_" (vn k)) (:purity c) "対象者の割合"))
                              (m/add-variable (const (str "toaware_" (vn k)) (:to-aware c) "到達→認知"))
                              (m/add-variable (const (str "tolead_" (vn k)) (:to-lead c) "到達→引き合い（直接）")))))
                      base live)
        ;; DM は名簿を消費する（1 回きり: 最初の 1 か月で使い切る）
        dm? (pos? (get spend "dm" 0))
        google? (pos? (get spend "google" 0))
        aware-terms (for [[k _] live :when (and (not= k "google") (not= k "dm"))]
                      (str "reach_" (vn k) " * purity_" (vn k) " * toaware_" (vn k)))
        lead-terms  (for [[k _] live :when (and (not= k "google") (not= k "dm"))]
                      (str "reach_" (vn k) " * purity_" (vn k) " * tolead_" (vn k)))]
    (cond-> withc
      true (m/add-variable
            (m/flow "gain_aware"
                    (str "((" (if (seq aware-terms) (str/join " + " aware-terms) "0") ")"
                         (when dm? " + dm_use * 0.6")
                         ") * (1 - aware / pool)")
                    {:xmile/doc "認知の獲得。broadcast と読み物から"}))
      true (m/add-variable
            (m/flow "dm_use"
                    (if dm? (str "PULSE(" (min named-pool 373) ", 1)") "0")
                    {:xmile/doc "DM の発送。**名簿を 1 度だけ消費する**（同じ相手に何度も送らない）"}))
      true (m/add-variable
            (m/flow "gain_lead"
                    (str "((" (if (seq lead-terms) (str/join " + " lead-terms) "0") ")"
                         (when dm? " + dm_use * tolead_dm")
                         ") * (1 - aware / pool)"
                         (when google? " + aware * 0.0005"))
                    {:xmile/doc "引き合いの獲得。読み物と DM は直接、Google は**認知 stock から**引く"})))))

(defn run! [id spend]
  (let [mdl (build id spend)
        probs (v/validate mdl)]
    (when (seq (v/errors probs))
      (doseq [e (v/errors probs)] (println "ERR" (pr-str e)))
      (throw (ex-info "invalid" {:id id})))
    (let [{:keys [xmile/series]} (x/run mdl)
          cost (reduce + (for [[k s] spend] (* s (:cost (get channels k)) horizon)))
          ;; DM は 1 回きりなので horizon 倍しない
          cost (if (pos? (get spend "dm" 0))
                 (+ (- cost (* (get spend "dm" 0) (:cost (get channels "dm")) horizon))
                    (* (get spend "dm" 0) (:cost (get channels "dm"))))
                 cost)]
      {:id id :cost cost
       :aware (last (get series "aware"))
       :lead (last (get series "lead"))
       :customer (last (get series "customer"))})))

(def scenarios
  [["S1 郵送DM のみ（B 373通、1回）"        {"dm" 1}]
   ["S2 東商新聞 広告のみ（毎月）"           {"toshoshimbun" 1}]
   ["S3 東商新聞 DM同封（年2回相当）"        {"toshoshimbun-dm" 0.167}]
   ["S4 日経トップリーダー（年2回相当）"      {"nikkei-tl" 0.167}]
   ["S5 ゴルフカートのみ"                   {"golfcart" 1}]
   ["S6 ローカルTV のみ"                    {"local-tv" 1}]
   ["S7 地方紙のみ"                         {"newspaper" 1}]
   ["S8 Google 検索のみ"                    {"google" 1}]
   ["S9 ゴルフカート + Google"              {"golfcart" 1 "google" 1}]
   ["S10 東商新聞広告 + Google"             {"toshoshimbun" 1 "google" 1}]
   ["S11 DM + 東商新聞広告 + Google"        {"dm" 1 "toshoshimbun" 1 "google" 1}]
   ["S12 TKC戦略経営者 1/3P（毎月）"        {"senryaku" 1}]
   ["S13 TKC戦略経営者 記事中1P（毎月）"     {"senryaku-1p" 1}]
   ["S14 法人会 会報（首都圏5会・年2回相当）"  {"hojinkai" 0.167}]
   ["S15 中小企業家しんぶん（毎月）"          {"doyukai" 1}]
   ["S16 日刊工業新聞 5段（年2回相当）"       {"nikkan-kogyo" 0.167}]
   ["S17 東商新聞 + TKC戦略経営者1/3P"      {"toshoshimbun" 1 "senryaku" 1}]
   ["S18 東商 + TKC + DM + Google"        {"toshoshimbun" 1 "senryaku" 1 "dm" 1 "google" 1}]])

(println "=== MK-1 日本：マーケティング・ミックス（XMILE、36か月）===\n")
(println "⚠ 転換率は 1 つも実測していない（murakumo の funnel は paid 0）。")
(println "  p-* は全て ASSUMPTION で全シナリオ共通。**答えられるのは順位だけ。**\n")
(println (str (pad "シナリオ" 34) (lp "36か月費用" 13) (lp "認知" 10) (lp "引き合い" 10)
              (lp "受注指数" 9) (lp "受注1件単価" 14)))
(def results (mapv (fn [[label sp]] (assoc (run! (re-find #"^S[0-9]+" label) sp) :label label)) scenarios))
(def base-c (max 0.001 (:customer (first (filter #(str/starts-with? (:label %) "S1 ") results)))))
(doseq [{:keys [label cost aware lead customer]} (sort-by :customer > results)]
  (println (str (pad label 34) (lp (yen cost) 13) (lp (r1 aware) 10) (lp (r1 lead) 10)
                (lp (r1 (/ customer base-c)) 8)
                (lp (if (pos? customer) (yen (/ cost customer)) "—") 14))))
(println "  ※ 『受注』列は **S1 郵送DM を 1.00 とした指数**。台数ではない。")
(println "     絶対値を出さないのは、転換率が 1 つも実測されていないため（paid 0）。")

(println "\n--- 構造から出る 3 つ ---")
(let [g (first (filter #(= "S8 Google 検索のみ" (:label %)) results))
      gc (first (filter #(= "S9 ゴルフカート + Google" (:label %)) results))
      tg (first (filter #(= "S10 東商新聞広告 + Google" (:label %)) results))]
  (println (str "① **Google 単独は空回りする。**受注 " (r2 (:customer g))
                " —— 認知 stock がゼロなので引く相手が居ない。"))
  (println (str "   ゴルフカートを足すと " (r2 (:customer gc)) "、東商新聞を足すと " (r2 (:customer tg)) "。"))
  (println "   **認知を作る媒体は、検索広告の生産性を上げる形で効く。**単価比較では出ない。"))
(println "② **DM は 1 回きり。**名簿 1,007 を消費したら次が無い。継続的な媒体とは性質が違う。")
(println "③ **認知は減衰する**（月 5%）。掲出を止めると在庫が減る。broadcast は続けないと消える。")

(doseq [[label sp] scenarios]
  (let [id (re-find #"^S[0-9]+" label)
        doc {:xmile/header {:xmile/vendor "com-junkawasaki/root"
                            :xmile/product {:xmile/name "jp-marketing-mix" :xmile/version "1"}
                            :xmile/name label}
             :xmile/sim-specs (m/sim-specs 0 horizon {:xmile/dt 1.0 :xmile/time-units "month" :xmile/method :euler})
             :xmile/models [(build id sp)]}]
    (fs/writeFileSync (str "90-docs/system-dynamics/jp-marketing-mix-" id ".xmile") (xml/emit-string doc))))
(println (str "\nwrote " (count scenarios) " xmile files"))
