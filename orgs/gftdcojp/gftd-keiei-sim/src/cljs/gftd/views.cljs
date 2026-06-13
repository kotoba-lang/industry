(ns gftd.views
  "reagent ビュー (hiccup)。旧 app.cljs の imperative DOM 構築を宣言的に置換。
   状態は re-frame subscription、歩行位置は gftd.anim の ratom から引く。"
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [gftd.db :as db]
            [gftd.anim :as anim]
            [gftd.priority :as prio]))

;; ---- 表示ヘルパ -------------------------------------------------------------

(defn- oku [v] (str (.toFixed (/ v 100000000.0) 2) "億"))
(defn- man [v] (str (.toLocaleString (js/Math.round (/ v 10000.0))) "万"))
(defn- prop-for [role props] (first (filter #(= (:role %) role) props)))

(defn- market-color [m]
  (case m
    "web3" "#a371f7" "finance" "#3fb950" "jp-corp" "#4f9dff"
    "global" "#58a6ff" "public" "#d29922" "academia" "#56d4dd" "#8b98a9"))

(defn- rel-ring [rel]
  (case rel "customer" "#3fb950" "vendor" "#f0883e" "partner" "#a371f7" nil))

(defn- kpi-class [k name]
  (case name
    "runway" (cond (< (:runway_months k) 4) "bad" (< (:runway_months k) 8) "warn" :else "good")
    "cash"   (cond (< (:cash_jpy k) 0) "bad" (< (:cash_jpy k) 30000000) "warn" :else "good")
    "morale" (cond (< (:morale k) 30) "bad" (< (:morale k) 55) "warn" :else "good")
    ""))

(defn- ambient-caption [cap d]
  (let [it (:intel d) ra (or (:recent_activity d) []) lm (:live_m365 d)]
    (case cap
      :renewal (str "更新確認 " (count (:renewal_risks it)) "件")
      :deps    (str "取引 " (count (:dependencies it)) "件")
      ;; 電話番/メール担当はライブM365があればそれを優先(実Outlook連動)
      :calls   (if lm (str "予定 " (count (:events lm)) "件") (str "会議 " (count (filter #(str/starts-with? % "📅") ra)) "件"))
      :inbound (if lm (str "未読 " (:unread lm) "件") (str "inbound " (count (filter #(str/starts-with? % "📨") ra)) "件"))
      :chase   (str "追客: " (if-let [l (first (:latent_leads it))]
                              (first (str/split (:subject l) #"\.")) "—"))
      "")))

;; ---- ヘッダ -----------------------------------------------------------------

(defn header []
  (let [llm-live @(rf/subscribe [:llm-live])
        turn     @(rf/subscribe [:turn])
        status   @(rf/subscribe [:status])
        thinking @(rf/subscribe [:thinking])]
    [:header
     [:div.brand
      [:span.logo "🏢"]
      [:div
       [:h1 "gftdcojp バーチャルオフィス経営シム"]
       [:p.sub "意思決定者: " [:strong "あなた 👑"]
        " ／ 社員: LLMエージェント（ClojureScript + kotoba-datomic）"]]]
     [:div.head-right
      [:span {:class (str "badge " (case llm-live true "live" false "stub" nil))}
       (case llm-live true "実LLM gemma4" false "スタブLLM" "LLM")]
      [:span.badge.turn (str "ターン " (or turn 0))]
      [:button.primary.alt {:on-click #(rf/dispatch [:m365-sync])} "📡 M365同期"]
      [:button.primary.alt {:on-click #(rf/dispatch [:gen-report])} "📊 経営レポート"]
      [:button.primary {:on-click #(rf/dispatch [:advance])
                        :disabled (= status "bankrupt")}
       (if thinking "社員が会議中…" "次の四半期へ ▶")]]]))

;; ---- KPI HUD ----------------------------------------------------------------

(defn kpis-hud []
  (when-let [k @(rf/subscribe [:kpis])]
    [:section.kpis
     (for [[label value cls]
           [["現金残高"   (str (oku (:cash_jpy k)) "円") (kpi-class k "cash")]
            ["ランウェイ" (str (.toFixed (:runway_months k) 1) "ヶ月") (kpi-class k "runway")]
            ["社内人員"   (str (:headcount k) "名") ""]
            ["士気"       (str (:morale k) " / 100") (kpi-class k "morale")]
            ["累計売上"   (str (oku (:revenue_total_jpy k)) "円") ""]
            ["パイプライン" (str (oku (:pipeline_jpy k)) "円") ""]]]
       ^{:key label}
       [:div.kpi [:div.label label] [:div.value {:class cls} value]])]))

;; ---- 2D オフィス ------------------------------------------------------------

(defn- worker-state [thinking? p]
  (cond thinking?                  "thinking"
        (nil? p)                   "working"
        (= (:status p) "pending")  "proposing"
        (= (:status p) "approved") "approved"
        (= (:status p) "rejected") "rejected"
        :else                      "working"))

(defn- bubble [state p]
  (case state
    "thinking"  [:div.bubble.think "💭…"]
    "proposing" [:div.bubble
                 [:div.act (:action p)]
                 [:div.hint (:effect_hint p)]
                 [:div.btns
                  [:button.approve {:on-click #(rf/dispatch [:decide (:id p) "approve"])} "承認"]
                  [:button.reject  {:on-click #(rf/dispatch [:decide (:id p) "reject"])} "却下"]]]
    "approved"  [:div.bubble.ok "✅ 承認"]
    "rejected"  [:div.bubble.ng "💢 却下"]
    nil))

(defn- worker-view [w]
  (let [props    @(rf/subscribe [:proposals])
        thinking @(rf/subscribe [:thinking])
        pos      (get @anim/positions (:role w))
        p        (prop-for (:role w) props)
        state    (worker-state thinking p)]
    [:div {:class (str "worker " (:role w) " " state (when (:moving pos) " moving"))
           :style {:left (str (or (:left pos) (:hx w)) "%")
                   :top  (str (or (:top pos) (:hy w)) "%")}}
     [:div.bubble-host (bubble state p)]
     [:div.avatar (:emoji w)]
     [:div.desk (:desk w)]
     [:div.nameplate (:label w)]]))

(defn- ambient-view [a]
  (let [data @(rf/subscribe [:data])
        pos  (get @anim/positions (:id a))]
    [:div.worker.ambient
     {:style {:left (str (or (:left pos) (:hx a)) "%")
              :top  (str (or (:top pos) (:hy a)) "%")}}
     [:div.avatar (:emoji a)]
     [:div.nameplate (:label a)]
     [:div.cap (ambient-caption (:cap a) data)]]))

(defn office []
  [:div.office
   [:div.deco {:style {:left "5%" :top "7%"}} "🪴"]
   [:div.deco {:style {:left "95%" :top "9%"}} "🪟"]
   [:div.deco {:style {:left "50%" :top "90%"}} "☕️"]
   [:div.deco.table {:style {:left "50%" :top "53%"}} "🪑📋🪑"]
   (for [w db/workers] ^{:key (:role w)} [worker-view w])
   (for [a db/ambient] ^{:key (:id a)} [ambient-view a])
   [:div.worker.you {:style {:left "50%" :top "92%"}}
    [:div.avatar "👑"]
    [:div.nameplate "あなた (CEO)"]]])

;; ---- 関係グラフ (SVG) -------------------------------------------------------

(defn- graph [leads people]
  (let [top    (vec (take 8 leads))
        n      (count top)
        cx 120 cy 100
        max-pw (max 1 (apply max 1 (map :path_weight top)))
        placed (vec (map-indexed
                     (fn [i l]
                       (let [a (- (* (/ i (max 1 n)) 2 js/Math.PI) (/ js/Math.PI 2))]
                         (assoc l :idx i
                                :gx (+ cx (* 78 (js/Math.cos a)))
                                :gy (+ cy (* 70 (js/Math.sin a))))))
                     top))]
    [:svg.graphsvg {:viewBox "0 0 240 210"}
     ;; org ノード
     (for [l placed]
       (let [sw    (+ 0.6 (* 6 (/ (:path_weight l) max-pw)))
             r     (+ 6 (* 8 (/ (:path_weight l) 100.0)))
             ring  (or (when (= (:risk l) "churn") "#f85149") (rel-ring (:rel_type l)))
             short (first (str/split (:subject l) #"\."))]
         ^{:key (str "o" (:idx l))}
         [:g
          [:line {:x1 cx :y1 cy :x2 (:gx l) :y2 (:gy l) :stroke "#3a4452" :stroke-width sw}]
          [:circle (cond-> {:cx (:gx l) :cy (:gy l) :r r :fill (market-color (:market l))}
                     ring (assoc :stroke ring :stroke-width 2.5))]
          [:text {:x (:gx l) :y (+ (:gy l) r 9) :font-size 8 :fill "#c7d0db" :text-anchor "middle"}
           short]]))
     ;; 上位3社の担当者 (people) を枝分かれ
     (for [l (take 3 placed)
           :let [ems (take 3 (get people (keyword (:subject l)) (get people (:subject l))))]
           [k em] (map-indexed vector ems)]
       (let [pa    (+ (* 1.3 k) (* 1.7 (:idx l)))
             px    (+ (:gx l) (* 17 (js/Math.cos pa)))
             py    (+ (:gy l) (* 17 (js/Math.sin pa)))
             loc   (first (str/split (str em) #"@"))
             short (subs loc 0 (min 6 (count loc)))]
         ^{:key (str "p" (:idx l) "-" k)}
         [:g
          [:line {:x1 (:gx l) :y1 (:gy l) :x2 px :y2 py :stroke "#2a3340" :stroke-width 0.5}]
          [:circle {:cx px :cy py :r 3 :fill "#56d4dd"}]
          [:text {:x px :y (- py 4) :font-size 6 :fill "#7f8c9b" :text-anchor "middle"} short]]))
     ;; 中央 gftd ノード
     [:circle {:cx cx :cy cy :r 15 :fill "#1f6feb"}]
     [:text {:x cx :y (+ cy 3) :font-size 9 :fill "#fff" :text-anchor "middle" :font-weight 700}
      "gftd"]]))

;; ---- intel パネル -----------------------------------------------------------

(def ^:private rel-ja
  {"customer" "顧客" "vendor" "仕入先" "partner" "提携" "lead" "見込"})

(defn intel-panel []
  (let [it @(rf/subscribe [:intel])]
    [:section.panel.intel
     [:h2 "🧠 インテリジェンス "
      [:span.hint "clj導出 · datomic · 深度 " [:b (or (:intel_depth it) 0)]]]
     (when it
       [:<>
        [:div#graph [graph (:latent_leads it) (:people it)]]
        [:div.legend
         [:span "線=接触/商流(pw)"] [:span "色=市場"] [:span "水色=担当者"]
         [:span "リング:緑顧客/橙仕入/紫提携/赤離反"]]
        [:h3 "市場分析 " [:span.hint "セグメント別"]]
        [:ul.mini
         (for [m (take 6 (:markets it))]
           ^{:key (:segment m)}
           [:li.kv
            [:span [:span.dot {:style {:background (market-color (:segment m))}}] (:segment m)]
            [:span.r (str (:orgs m) "社 / " (:messages m) "通")]])]
        [:h3 "潜在リード " [:span.hint "path-weight順 · 商流"]]
        [:ul.mini
         (let [f (:funnel it)]
           ^{:key "funnel"}
           [:li.funnel "商談ファネル: 新規 " (:new f 0) " ｜ 接触 " (:engaged f 0)
            " ｜ 商談 " (:qualified f 0) " ｜ " [:b (str "受注 " (:won f 0))]])
         (for [l (take 6 (:latent_leads it))]
           (let [churn? (= (:risk l) "churn")
                 open   (:open_threads l)
                 money  (:money_jpy l)]
             ^{:key (:subject l)}
             [:li.kv
              [:span (:subject l) (when churn? [:span.risk " 離反"])]
              [:span.r
               [:span {:class (str "rel " (:rel_type l))} (rel-ja (:rel_type l) (:rel_type l))] " "
               (str "pw" (:path_weight l))
               (when (and money (pos? money)) (str " ¥" (man money) "万"))
               (when (and open (pos? open)) (str " 📩" open)) " "
               [:span {:class (str "stage " (:stage l))} (:stage l)]]]))]
        [:h3 "契約更新リスク " [:span.hint "要フォロー"]]
        [:ul.mini
         (for [r (take 4 (:renewal_risks it))]
           ^{:key (:subject r)} [:li [:span.t "⚠"] (:subject r)])]
        [:h3 "再生候補 " [:span.hint "休眠・資産大"]]
        [:ul.mini
         (for [r (take 4 (:revival it))]
           ^{:key (:subject r)}
           [:li.kv [:span (:subject r)] [:span.amt (str (:score r) "件")]])]
        [:h3 "売上集中(依存)"]
        [:ul.mini
         (for [e (take 6 (:dependencies it))]
           ^{:key (:to e)}
           [:li.kv [:span (:to e)] [:span.amt (str (man (:value_jpy e)) "万")]])]
        [:h3 "商談ダイジェスト " [:span.hint "件名履歴→gemma4要約"]]
        [:ul.mini
         (for [g (:deal_digests it)]
           ^{:key (:subject g)}
           [:li.kv
            [:span (:subject g)]
            [:button.mini-btn {:on-click #(rf/dispatch [:summarize (:subject g)])} "要約"]])]])]))

;; ---- 財務 / 台帳 ------------------------------------------------------------

(defn fin-panel []
  (let [d  @(rf/subscribe [:data])
        it @(rf/subscribe [:intel])
        bdt (:bad_debt_total_jpy it 0)]
    [:section.panel
     [:h2 "実財務 " [:span.hint "m365 請求書実額"]]
     (when d
       [:div.fin
        [:div.fin-row [:span "発行請求(売上累計)"]
         [:strong.good (str (oku (:issued_total_jpy d)) "円")]]
        [:div.fin-row [:span "受領請求(コスト累計)"]
         [:strong.bad (str (oku (:received_total_jpy d)) "円")]]
        [:div.fin-row [:span "差引"]
         [:strong (str (oku (- (:issued_total_jpy d) (:received_total_jpy d))) "円")]]
        [:div.fin-row [:span "不良債権(回収懸念)"]
         [:strong.bad (str (oku bdt) "円")]]])
     [:h3 "不良債権 " [:span.hint "売掛金・回収要確認"]]
     [:ul.mini
      (for [b (take 6 (:bad_debts it))]
        ^{:key (:subject b)}
        [:li.kv [:span (:subject b)] [:span.amt.bad (str (man (:amount_jpy b)) "万")]])]]))

(defn ledger-panel []
  (let [rows @(rf/subscribe [:ledger])]
    [:section.panel
     [:h2 "意思決定台帳 " [:span.hint "kotoba-datomic"]]
     [:ul.mini
      (for [[i r] (map-indexed vector rows)]
        ^{:key i}
        [:li {:class (when-not (:approved r) "rej")}
         [:span.t (str "T" (:turn r))] (:note r)])]]))

;; ---- 全体 -------------------------------------------------------------------

;; ---- #1 時系列チャート (受注/KPI推移) --------------------------------------

(defn- spark [label color pts max-v]
  ;; pts: [[turn val]...] を 0..1 正規化して折れ線 SVG に
  (let [n (count pts)
        w 240 h 46
        xs (fn [i] (if (<= n 1) 0 (* w (/ i (dec n)))))
        ys (fn [v] (- h (* (- h 6) (/ v (max 1 max-v)))))
        path (->> pts
                  (map-indexed (fn [i [_ v]] (str (if (zero? i) "M" "L") (.toFixed (xs i) 1) " " (.toFixed (ys v) 1))))
                  (str/join " "))]
    [:div.spark
     [:div.spark-label [:span {:style {:color color}} "●"] " " label]
     [:svg {:viewBox (str "0 0 " w " " h) :class "sparksvg"}
      [:path {:d path :fill "none" :stroke color :stroke-width 2}]
      (when (pos? n)
        (let [[_ lv] (last pts)]
          [:circle {:cx (xs (dec n)) :cy (ys lv) :r 3 :fill color}]))]]))

(defn trend-panel []
  (let [hist @(rf/subscribe [:turn-history])]
    [:section.panel
     [:h2 "📈 KPI推移 " [:span.hint "datomic 時系列"]]
     (if (seq hist)
       (let [oku-pts (fn [k] (mapv (fn [h] [(:turn h) (/ (get h k 0) 1e8)]) hist))
             cash (oku-pts :cash) rev (oku-pts :revenue) pl (oku-pts :pipeline)
             mx (apply max 1 (concat (map second cash) (map second rev) (map second pl)))]
         [:div
          [spark "現金残高(億)" "#4f9dff" cash mx]
          [spark "累計売上(億)" "#3fb950" rev mx]
          [spark "パイプライン(億)" "#a371f7" pl mx]])
       [:p.empty "ターンを進めると推移が表示されます"])]))

;; ---- #3 社員間ディスカッション議事録 ----------------------------------------

;; ---- #1 観測ログ: 各社員が ReAct で引いた Act 種別を可視化 (cljs集計) ----------

(def ^:private act-names ["要約" "詳細" "商談履歴" "予定" "前回結果"])

(defn obs-log-panel []
  (let [props @(rf/subscribe [:proposals])]
    (when (seq props)
      [:section.panel
       [:h2 "🔍 観測ログ " [:span.hint "各社員が ReAct で引いた Act 種別(kqe観測)"]]
       [:ul.mini
        (for [p props]
          (let [n (or (:rounds p) 0)
                acts (take n act-names)]
            ^{:key (:id p)}
            [:li.kv
             [:span (:role_label p)]
             [:span.r
              (if (pos? n)
                [:span.acts
                 (for [[i a] (map-indexed vector acts)]
                   ^{:key i} [:span.act-chip a])]
                "観測なし")]]))]])))

(defn discussion-panel []
  (let [log @(rf/subscribe [:discussion])]
    [:section.panel
     [:h2 "🗣️ 議事録 " [:span.hint "社員間ディスカッション"]]
     (if (seq log)
       [:ul.chat
        (for [[i m] (map-indexed vector log)]
          ^{:key i}
          [:li {:class (str "chat-" (:role m))}
           [:span.speaker (:speaker m)] [:span.line (:text m)]])]
       [:p.empty "ターンを進めると会議の議事録が表示されます"])]))

;; ---- 実 M365 (Outlook) ライブパネル -----------------------------------------

(defn live-m365-panel []
  (let [lm @(rf/subscribe [:live-m365])]
    [:section.panel.live
     [:h2 "📡 ライブ M365 "
      [:span.hint (if lm (str "未読 " (:unread lm) " 件") "「📡 M365同期」で取得")]]
     (when lm
       [:<>
        [:div.live-actions
         [:button.mini-btn {:on-click #(rf/dispatch [:m365-triage])} "📨 メールトリアージ"]
         [:button.mini-btn {:on-click #(rf/dispatch [:m365-prep])} "📅 会議準備"]]
        [:h3 "受信トレイ 直近"]
        [:ul.mini
         (for [[i m] (map-indexed vector (take 6 (:inbox lm)))]
           ^{:key i}
           [:li.kv
            [:span (when (:unread m) [:span.risk "● "]) (:subject m)]
            [:span.r (last (str/split (str (:from m)) #"@"))]])]
        [:h3 "今後の予定 (14日)"]
        [:ul.mini
         (for [[i e] (map-indexed vector (take 6 (:events lm)))]
           ^{:key i}
           [:li.kv [:span (:subject e)] [:span.r (subs (str (:start e)) 5 16)]])]])]))

;; ---- 📅 カレンダー(アジェンダ) ----------------------------------------------

(def ^:private wdays ["日" "月" "火" "水" "木" "金" "土"])

(defn- date-label [ymd]
  ;; "2026-06-13" → "6/13(金)"
  (let [d (js/Date. (str ymd "T00:00:00"))]
    (str (inc (.getMonth d)) "/" (.getDate d) "(" (nth wdays (.getDay d)) ")")))

(defn calendar-pane []
  (let [lm @(rf/subscribe [:live-m365])
        events (:events lm)
        by-day (when (seq events) (sort-by key (group-by #(subs (str (:start %)) 0 10) events)))]
    [:section.panel
     [:h2 "📅 カレンダー "
      [:span.hint "実 M365 予定 (今後14日)"]
      [:button.mini-btn {:on-click #(rf/dispatch [:m365-sync])} "🔄 更新"]
      [:button.mini-btn {:on-click #(rf/dispatch [:m365-prep])} "📝 会議準備サマリ"]]
     (cond
       (nil? lm) [:p.empty "「📡 M365同期」で予定表を取得してください"]
       (empty? events) [:p.empty "今後14日の予定はありません"]
       :else
       [:div.agenda
        (for [[ymd evs] by-day]
          ^{:key ymd}
          [:div.agenda-day
           [:div.agenda-date (date-label ymd)]
           [:ul.agenda-list
            (for [[i e] (map-indexed vector (sort-by :start evs))]
              ^{:key i}
              [:li.agenda-item
               [:span.agenda-time (subs (str (:start e)) 11 16)]
               [:span.agenda-subj (:subject e)]
               (when (seq (str (:organizer e))) [:span.agenda-org (str "主催: " (:organizer e))])])]])])]))

;; ---- 📨 受信トレイ ----------------------------------------------------------

(defn inbox-pane []
  (let [lm @(rf/subscribe [:live-m365])]
    [:section.panel
     [:h2 "📨 受信トレイ "
      [:span.hint (if lm (str "未読 " (:unread lm) " 件") "未取得")]
      [:button.mini-btn {:on-click #(rf/dispatch [:m365-sync])} "🔄 更新"]
      [:button.mini-btn {:on-click #(rf/dispatch [:m365-triage])} "🤖 トリアージ"]]
     (cond
       (nil? lm) [:p.empty "「📡 M365同期」で受信トレイを取得してください"]
       :else
       [:ul.mail-list
        (for [[i m] (map-indexed vector (:inbox lm))]
          ^{:key i}
          [:li {:class (when (:unread m) "unread")}
           [:span.mail-from (:from m)]
           [:span.mail-subj (:subject m)]
           [:span.mail-date (subs (str (:received m)) 5 16)]])])]))

;; ---- 🎯 優先順位キュー (WSJF + MCDA内訳 + minimax最悪ケース) -----------------

(def ^:private kind-class
  {:proposal "k-prop" :renewal "k-renew" :baddebt "k-bd"
   :churn "k-churn" :lead "k-lead" :inbox "k-inbox"})

(defn- mcda-bars [a]
  ;; ⑤ MCDA: 影響/緊急/リスク低減/工数 を小バーで内訳表示
  [:div.mcda
   (for [[label v color]
         [["影響" (:impact a) "#4f9dff"] ["緊急" (:urgency a) "#f0883e"]
          ["リスク低減" (:risk a) "#3fb950"] ["工数" (:effort a) "#8b98a9"]]]
     ^{:key label}
     [:div.mcda-row
      [:span.mcda-label label]
      [:div.mcda-track [:div.mcda-fill {:style {:width (str (* 10 v) "%") :background color}}]]])])

(defn priority-pane []
  (let [data @(rf/subscribe [:data])
        q (when data (prio/queue data))]
    [:section.panel
     [:h2 "🎯 意思決定キュー "
      [:span.hint "WSJF優先順 · 提案/リスク/不良債権/リード/メールを統合"]]
     (cond
       (nil? data) [:p.empty "読み込み中…"]
       (empty? q)  [:p.empty "対応待ちの項目はありません。「次の四半期へ」で社員が提案します。"]
       :else
       [:ul.pq
        (for [[i a] (map-indexed vector q)]
          ^{:key (:id a)}
          [:li.pq-item {:class (kind-class (:kind a))}
           [:div.pq-rank (inc i)]
           [:div.pq-main
            [:div.pq-head
             [:span.pq-badge (:badge a)]
             [:span.pq-title (:title a)]
             [:span.pq-score (str "WSJF " (.toFixed (:score a) 1))]]
            [:div.pq-detail (:detail a)]
            [mcda-bars a]
            [:div.pq-worst "⚠ " (:worst a)]
            [:div.pq-actions
             (for [[j act] (map-indexed vector (:actions a))]
               ^{:key j}
               [:button.pq-btn {:on-click #(rf/dispatch (:ev act))} (:label act)])
             [:button.pq-btn.chat {:on-click #(rf/dispatch [:open-chat a])} "💬 相談"]]]])])]))

;; ---- レポート/要約モーダル --------------------------------------------------

(defn chat-box [_m]
  (let [input (r/atom "")
        send! (fn [] (when (seq (str/trim @input))
                       (rf/dispatch [:send-chat @input]) (reset! input "")))]
    (fn [m]
      [:div.chat
       [:div.chat-thread
        (for [[i msg] (map-indexed vector (:chat m))]
          ^{:key i} [:div {:class (str "cmsg " (:role msg))} (:text msg)])
        (when (:loading m) [:div.cmsg.ai "…"])
        (when (empty? (:chat m)) [:p.empty "承認/却下の前に相談できます。値引き余地・代替案・リスク・優先度などを質問してください。"])]
       [:div.chat-input
        [:textarea {:value @input
                    :placeholder "指示や質問 (例: この商談の値引き余地は？ 法務リスクは？ 後回しでよい？) ⌘+Enterで送信"
                    :on-change #(reset! input (.. % -target -value))
                    :on-key-down #(when (and (= "Enter" (.-key %)) (.-metaKey %)) (send!))}]
        [:button {:on-click send!} "送信"]]])))

(defn modal []
  (when-let [m @(rf/subscribe [:modal])]
    [:div.overlay {:on-click #(rf/dispatch [:close-modal])}
     [:div.modal-box {:on-click (fn [e] (.stopPropagation e))}
      [:button.modal-close {:on-click #(rf/dispatch [:close-modal])} "×"]
      [:h2 (:title m)]
      (cond
        (contains? m :chat) [chat-box m]
        (:loading m)        [:p.empty "gemma4 が生成中…"]
        :else               [:div.report-body (:body m)])]]))

(defn gameover []
  (let [bankrupt? (= @(rf/subscribe [:status]) "bankrupt")]
    [:div {:class (str "overlay" (when-not bankrupt? " hidden"))}
     [:div.overlay-box
      [:h2 "💸 資金ショート — 倒産"]
      [:p "現金残高がマイナスになりました。経営判断を見直してリスタートしてください。"]]]))

(def ^:private tabs
  [[:priority "🎯 優先順位"] [:office "🏢 オフィス"] [:calendar "📅 カレンダー"]
   [:inbox "📨 受信トレイ"] [:intel "🧠 インテリジェンス"] [:mgmt "📊 経営"]])

(defn nav-tabs []
  (let [active @(rf/subscribe [:tab])]
    [:nav.tabs
     (for [[k label] tabs]
       ^{:key k}
       [:button {:class (when (= active k) "active")
                 :on-click #(rf/dispatch [:set-tab k])} label])]))

(defn tab-content []
  (case @(rf/subscribe [:tab])
    :priority [:div.pane.single [priority-pane]]
    :office   [:div.pane [office] [obs-log-panel] [discussion-panel]]
    :calendar [:div.pane.single [calendar-pane]]
    :inbox    [:div.pane.single [inbox-pane]]
    :intel    [:div.pane.single [intel-panel]]
    :mgmt     [:div.pane.cols [trend-panel] [fin-panel] [ledger-panel]]
    [:div.pane [office]]))

(defn dashboard []
  [:<>
   [header]
   [kpis-hud]
   [nav-tabs]
   [:main.tabbed [tab-content]]
   [modal]
   [gameover]])
