(ns gftd.app
  "gftd 経営シム ダッシュボード (ClojureScript)。
   Gather.town 風の 2D オフィスで LLM エージェント社員が歩き・働く様子を描画し、
   kotoba-datomic 由来の intel (確度/離反・更新リスク/依存) を関係グラフ等で見せる。
   サーバ権威 (/api) + SSE。"
  (:require [clojure.string :as str]))

;; ---- ヘルパ -----------------------------------------------------------------

(defn $ [id] (.getElementById js/document id))
(defn oku [v] (str (.toFixed (/ v 100000000.0) 2) "億"))
(defn man [v] (str (.toLocaleString (js/Math.round (/ v 10000.0))) "万"))
(defn esc [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

;; ---- 状態 -------------------------------------------------------------------

(defonce app-data (atom nil))
(defonce ui (atom {:thinking false}))
(defonce positions (atom {}))   ; role -> {:x % :y %} 現在位置 (歩行アニメ)

;; 社員レイアウト: home(席) と meeting(会議卓近く) の座標(%)
;; 提案者(LLMエージェント): 吹き出しで提案、承認/却下対象
(def workers
  [{:role "sales"   :label "営業"    :emoji "🧑‍💼" :desk "📞" :hx 14 :hy 22 :mx 40 :my 52 :ph 0.0}
   {:role "eng"     :label "開発"    :emoji "🧑‍💻" :desk "💻" :hx 50 :hy 16 :mx 50 :my 48 :ph 1.2}
   {:role "finance" :label "財務"    :emoji "👩‍💼" :desk "💴" :hx 86 :hy 22 :mx 60 :my 52 :ph 2.4}
   {:role "legal"   :label "法務"    :emoji "🧑‍⚖️" :desk "📜" :hx 28 :hy 44 :mx 44 :my 58 :ph 3.6}
   {:role "ceo"     :label "CEO補佐" :emoji "🧑‍🏫" :desk "📊" :hx 72 :hy 44 :mx 58 :my 58 :ph 4.8}])

;; アンビエント社員: LLMは呼ばず、役割別のライブ情報を表示しながら働く
(def ambient
  [{:id "soumu" :label "総務"     :emoji "🗂️" :hx 11 :hy 72 :ph 0.5 :cap :renewal}
   {:id "shomu" :label "庶務"     :emoji "🧾" :hx 31 :hy 78 :ph 1.7 :cap :deps}
   {:id "phone" :label "電話番"   :emoji "☎️" :hx 50 :hy 73 :ph 2.9 :cap :calls}
   {:id "mail"  :label "メール担当" :emoji "📧" :hx 70 :hy 78 :ph 4.1 :cap :inbound}
   {:id "rep"   :label "営業担当"  :emoji "🕴️" :hx 89 :hy 70 :ph 5.3 :cap :chase}])

(defn prop-for [role props] (first (filter #(= (:role %) role) props)))

(defn ambient-caption [cap d]
  (let [it (:intel d) ra (or (:recent_activity d) [])]
    (case cap
      :renewal (str "更新確認 " (count (:renewal_risks it)) "件")
      :deps    (str "取引 " (count (:dependencies it)) "件")
      :calls   (str "会議 " (count (filter #(str/starts-with? % "📅") ra)) "件")
      :inbound (str "inbound " (count (filter #(str/starts-with? % "📨") ra)) "件")
      :chase   (str "追客: " (if-let [l (first (:latent_leads it))] (first (str/split (:subject l) #"\.")) "—"))
      "")))

;; ---- KPI HUD ----------------------------------------------------------------

(defn kpi-class [k name]
  (case name
    "runway" (cond (< (:runway_months k) 4) "bad" (< (:runway_months k) 8) "warn" :else "good")
    "cash"   (cond (< (:cash_jpy k) 0) "bad" (< (:cash_jpy k) 30000000) "warn" :else "good")
    "morale" (cond (< (:morale k) 30) "bad" (< (:morale k) 55) "warn" :else "good")
    ""))

(defn render-kpis! [k]
  (let [items [["現金残高" (str (oku (:cash_jpy k)) "円") (kpi-class k "cash")]
               ["ランウェイ" (str (.toFixed (:runway_months k) 1) "ヶ月") (kpi-class k "runway")]
               ["社内人員" (str (:headcount k) "名") ""]
               ["士気" (str (:morale k) " / 100") (kpi-class k "morale")]
               ["累計売上" (str (oku (:revenue_total_jpy k)) "円") ""]
               ["パイプライン" (str (oku (:pipeline_jpy k)) "円") ""]]]
    (set! (.-innerHTML ($ "kpis"))
          (str/join (for [[label value cls] items]
                      (str "<div class='kpi'><div class='label'>" label
                           "</div><div class='value " cls "'>" value "</div></div>"))))))

;; ---- 2D オフィス: 構築は一度だけ、位置は rAF、状態は paint で更新 ----------

(defn build-office! []
  (let [deco "<div class='deco' style='left:5%;top:7%'>🪴</div><div class='deco' style='left:95%;top:9%'>🪟</div><div class='deco' style='left:50%;top:90%'>☕️</div><div class='deco table' style='left:50%;top:53%'>🪑📋🪑</div>"
        you "<div class='worker you' style='left:50%;top:92%'><div class='avatar'>👑</div><div class='nameplate'>あなた (CEO)</div></div>"
        ws (str/join (for [w workers]
                       (str "<div class='worker " (:role w) "' id='w-" (:role w) "' style='left:" (:hx w) "%;top:" (:hy w) "%'>"
                            "<div class='bubble-host' id='bub-" (:role w) "'></div>"
                            "<div class='avatar'>" (:emoji w) "</div>"
                            "<div class='desk'>" (:desk w) "</div>"
                            "<div class='nameplate'>" (:label w) "</div></div>")))
        as (str/join (for [a ambient]
                       (str "<div class='worker ambient' id='a-" (:id a) "' style='left:" (:hx a) "%;top:" (:hy a) "%'>"
                            "<div class='avatar'>" (:emoji a) "</div>"
                            "<div class='nameplate'>" (:label a) "</div>"
                            "<div class='cap' id='cap-" (:id a) "'></div></div>")))]
    (set! (.-innerHTML ($ "office")) (str deco ws as you))
    (reset! positions (into {} (concat (for [w workers] [(:role w) {:x (:hx w) :y (:hy w)}])
                                       (for [a ambient] [(:id a) {:x (:hx a) :y (:hy a)}]))))))

(defn paint-office! [data thinking?]
  (let [props (:proposals data)]
    (doseq [w workers]
      (let [p (prop-for (:role w) props)
            status (:status p)
            state (cond thinking? "thinking"
                        (nil? p) "working"
                        (= status "pending") "proposing"
                        (= status "approved") "approved"
                        (= status "rejected") "rejected"
                        :else "working")
            el ($ (str "w-" (:role w)))
            bub ($ (str "bub-" (:role w)))]
        (when el
          (set! (.-className el) (str "worker " (:role w) " " state)))
        (when bub
          (set! (.-innerHTML bub)
                (cond
                  (= state "thinking") "<div class='bubble think'>💭…</div>"
                  (= state "proposing")
                  (str "<div class='bubble'><div class='act'>" (esc (:action p)) "</div>"
                       "<div class='hint'>" (esc (:effect_hint p)) "</div><div class='btns'>"
                       "<button class='approve' onclick=\"window.decide('" (:id p) "','approve')\">承認</button>"
                       "<button class='reject' onclick=\"window.decide('" (:id p) "','reject')\">却下</button></div></div>")
                  (= state "approved") "<div class='bubble ok'>✅ 承認</div>"
                  (= state "rejected") "<div class='bubble ng'>💢 却下</div>"
                  :else ""))))))
  ;; アンビエント社員のライブ情報を更新
  (doseq [a ambient]
    (when-let [el ($ (str "cap-" (:id a)))]
      (set! (.-textContent el) (ambient-caption (:cap a) data)))))

;; キャラ1体を目標座標へ ease 移動 + ゆらぎ。
(defn move! [key id tx ty ph t]
  (let [pos (get @positions key {:x tx :y ty})
        nx (+ (:x pos) (* 0.05 (- tx (:x pos))))
        ny (+ (:y pos) (* 0.05 (- ty (:y pos))))
        wob (* 1.3 (js/Math.sin (+ (/ t 600.0) ph)))
        el ($ id)]
    (swap! positions assoc key {:x nx :y ny})
    (when el
      (set! (.. el -style -left) (str (+ nx wob) "%"))
      (set! (.. el -style -top) (str ny "%"))
      (.toggle (.-classList el) "moving" (> (js/Math.abs (- tx nx)) 0.6)))))

;; 歩行ループ: 提案者は思考時に会議卓へ集合、アンビエントは自席で徘徊。
(defn tick! [t]
  (let [thinking (:thinking @ui)]
    (doseq [w workers]
      (move! (:role w) (str "w-" (:role w))
             (if thinking (:mx w) (:hx w)) (if thinking (:my w) (:hy w)) (:ph w) t))
    (doseq [a ambient]
      (move! (:id a) (str "a-" (:id a)) (:hx a) (:hy a) (:ph a) t)))
  (js/requestAnimationFrame tick!))

;; ---- 関係グラフ (SVG): gftd ↔ 潜在リード, 太さ=接触量 色=確度 リング=離反 ----

;; 市場セグメント → 色
(defn market-color [m]
  (case m
    "web3" "#a371f7" "finance" "#3fb950" "jp-corp" "#4f9dff"
    "global" "#58a6ff" "public" "#d29922" "academia" "#56d4dd" "#8b98a9"))
;; 関係種別 → リング色 (商流の性質)
(defn rel-ring [rel]
  (case rel "customer" "#3fb950" "vendor" "#f0883e" "partner" "#a371f7" nil))

(defn render-graph! [leads people]
  (let [top (vec (take 8 leads))
        n (count top)
        cx 120 cy 100
        max-pw (max 1 (apply max 1 (map :path_weight top)))
        ;; 各 org ノードの座標を先に確定
        placed (vec (map-indexed
                     (fn [i l]
                       (let [a (- (* (/ i (max 1 n)) 2 js/Math.PI) (/ js/Math.PI 2))]
                         (assoc l :idx i :gx (+ cx (* 78 (js/Math.cos a))) :gy (+ cy (* 70 (js/Math.sin a))))))
                     top))
        org-svg (for [l placed]
                  (let [sw (+ 0.6 (* 6 (/ (:path_weight l) max-pw)))
                        r (+ 6 (* 8 (/ (:path_weight l) 100.0)))
                        ring (or (when (= (:risk l) "churn") "#f85149") (rel-ring (:rel_type l)))
                        short (first (str/split (:subject l) #"\."))]
                    (str "<line x1='" cx "' y1='" cy "' x2='" (:gx l) "' y2='" (:gy l) "' stroke='#3a4452' stroke-width='" sw "'/>"
                         "<circle cx='" (:gx l) "' cy='" (:gy l) "' r='" r "' fill='" (market-color (:market l)) "' "
                         (when ring (str "stroke='" ring "' stroke-width='2.5' ")) "/>"
                         "<text x='" (:gx l) "' y='" (+ (:gy l) r 9) "' font-size='8' fill='#c7d0db' text-anchor='middle'>" (esc short) "</text>")))
        ;; 上位3社の担当者(people)を org ノードから枝分かれさせて描画
        ppl-svg (for [l (take 3 placed)
                      :let [ems (take 3 (get people (keyword (:subject l)) (get people (:subject l))))]
                      [k em] (map-indexed vector ems)]
                  (let [pa (+ (* 1.3 k) (* 1.7 (:idx l)))
                        px (+ (:gx l) (* 17 (js/Math.cos pa)))
                        py (+ (:gy l) (* 17 (js/Math.sin pa)))
                        loc (first (str/split (str em) #"@"))
                        short (subs loc 0 (min 6 (count loc)))]
                    (str "<line x1='" (:gx l) "' y1='" (:gy l) "' x2='" px "' y2='" py "' stroke='#2a3340' stroke-width='0.5'/>"
                         "<circle cx='" px "' cy='" py "' r='3' fill='#56d4dd'/>"
                         "<text x='" px "' y='" (- py 4) "' font-size='6' fill='#7f8c9b' text-anchor='middle'>" (esc short) "</text>")))]
    (set! (.-innerHTML ($ "graph"))
          (str "<svg viewBox='0 0 240 210' class='graphsvg'>"
               (str/join org-svg) (str/join ppl-svg)
               "<circle cx='" cx "' cy='" cy "' r='15' fill='#1f6feb'/>"
               "<text x='" cx "' y='" (+ cy 3) "' font-size='9' fill='#fff' text-anchor='middle' font-weight='700'>gftd</text>"
               "</svg>"))
    (set! (.-innerHTML ($ "graph-legend"))
          "<span>線=接触/商流(pw)</span> <span>色=市場</span> <span>水色=担当者</span> <span>リング:緑顧客/橙仕入/紫提携/赤離反</span>")))

;; ---- intel パネル -----------------------------------------------------------

(defn render-intel! [it]
  (when it
    (set! (.-textContent ($ "intel-depth")) (or (:intel_depth it) 0))
    (render-graph! (:latent_leads it) (:people it))
    (set! (.-innerHTML ($ "markets"))
          (str/join (for [m (take 6 (:markets it))]
                      (str "<li class='kv'><span><span class='dot' style='background:" (market-color (:segment m)) "'></span>"
                           (esc (:segment m)) "</span><span class='r'>" (:orgs m) "社 / " (:messages m) "通</span></li>"))))
    (let [f (:funnel it)
          rel-ja {"customer" "顧客" "vendor" "仕入先" "partner" "提携" "lead" "見込"}
          funnel-li (str "<li class='funnel'>商談ファネル: 新規 " (:new f 0) " ｜ 接触 " (:engaged f 0)
                         " ｜ 商談 " (:qualified f 0) " ｜ <b>受注 " (:won f 0) "</b></li>")
          leads (str/join (for [l (take 6 (:latent_leads it))]
                            (let [churn? (= (:risk l) "churn")
                                  open (:open_threads l)
                                  money (:money_jpy l)]
                              (str "<li class='kv'><span>" (esc (:subject l))
                                   (when churn? " <span class='risk'>離反</span>")
                                   "</span><span class='r'>"
                                   "<span class='rel " (:rel_type l) "'>" (rel-ja (:rel_type l) (:rel_type l)) "</span> "
                                   "pw" (:path_weight l)
                                   (when (and money (pos? money)) (str " ¥" (man money) "万"))
                                   (when (and open (pos? open)) (str " 📩" open))
                                   " <span class='stage " (:stage l) "'>" (:stage l) "</span></span></li>"))))]
      (set! (.-innerHTML ($ "leads")) (str funnel-li leads)))
    (set! (.-innerHTML ($ "renewal"))
          (str/join (for [r (take 4 (:renewal_risks it))]
                      (str "<li><span class='t'>⚠</span>" (esc (:subject r)) "</li>"))))
    (set! (.-innerHTML ($ "revival"))
          (str/join (for [r (take 4 (:revival it))]
                      (str "<li class='kv'><span>" (esc (:subject r)) "</span><span class='amt'>" (:score r) "件</span></li>"))))
    (set! (.-innerHTML ($ "deps"))
          (str/join (for [e (take 6 (:dependencies it))]
                      (str "<li class='kv'><span>" (esc (:to e)) "</span><span class='amt'>" (man (:value_jpy e)) "万</span></li>"))))))

(defn render-fin! [data]
  (set! (.-innerHTML ($ "fin"))
        (str "<div class='fin-row'><span>発行請求(売上累計)</span><strong class='good'>" (oku (:issued_total_jpy data)) "円</strong></div>"
             "<div class='fin-row'><span>受領請求(コスト累計)</span><strong class='bad'>" (oku (:received_total_jpy data)) "円</strong></div>"
             "<div class='fin-row'><span>差引</span><strong>" (oku (- (:issued_total_jpy data) (:received_total_jpy data))) "円</strong></div>")))

(defn render-ledger! [rows]
  (set! (.-innerHTML ($ "ledger"))
        (str/join (for [r rows]
                    (str "<li class='" (if (:approved r) "" "rej") "'><span class='t'>T" (:turn r) "</span>" (esc (:note r)) "</li>")))))

;; ---- 全体 -------------------------------------------------------------------

(defn render! []
  (when-let [d @app-data]
    (let [k (:kpis d)]
      (render-kpis! k)
      (paint-office! d (:thinking @ui))
      (render-intel! (:intel d))
      (render-fin! d)
      (render-ledger! (:ledger d))
      (set! (.-textContent ($ "turn-badge")) (str "ターン " (:turn k)))
      (let [b ($ "llm-badge")]
        (set! (.-textContent b) (if (:llm_live d) "実LLM gemma4" "スタブLLM"))
        (set! (.-className b) (str "badge " (if (:llm_live d) "live" "stub"))))
      (.toggle (.-classList ($ "gameover")) "hidden" (not= (:status k) "bankrupt"))
      (set! (.-disabled ($ "advance")) (= (:status k) "bankrupt")))))

(defn fetch-json [url opts] (-> (js/fetch url (clj->js opts)) (.then #(.json %))))

(defn apply-data! [d] (reset! app-data (js->clj d :keywordize-keys true)) (render!))

(defn refresh [] (-> (fetch-json "/api/state" {}) (.then apply-data!)))

(defn advance []
  (swap! ui assoc :thinking true) (render!)
  (set! (.-textContent ($ "advance")) "社員が会議中…")
  (-> (fetch-json "/api/turn/advance" {:method "POST"})
      (.then (fn [d]
               (swap! ui assoc :thinking false)
               (set! (.-textContent ($ "advance")) "次の四半期へ ▶")
               (apply-data! d)))))

(defn decide [id what]
  (-> (fetch-json (str "/api/proposal/" id "/" what) {:method "POST"}) (.then apply-data!)))

(set! (.-decide js/window) decide)

(defn start! []
  (build-office!)
  (.addEventListener ($ "advance") "click" advance)
  (let [es (js/EventSource. "/api/events")] (set! (.-onmessage es) (fn [_] (refresh))))
  (js/requestAnimationFrame tick!)
  (refresh))

(start!)
