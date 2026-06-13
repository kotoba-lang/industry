(ns gftd.app
  "gftd 経営シム ダッシュボード (ClojureScript)。
   Gather.town 風の 2D オフィスで LLM エージェント社員が働く様子を描画し、
   サーバ権威 (/api) + SSE から状態を取得する。"
  (:require [clojure.string :as str]))

;; ---- DOM / 整形ヘルパ -------------------------------------------------------

(defn $ [id] (.getElementById js/document id))
(defn oku [v] (str (.toFixed (/ v 100000000.0) 2) "億"))
(defn man [v] (str (.toLocaleString (js/Math.round (/ v 10000.0))) "万"))

(defn esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

;; ---- 状態 -------------------------------------------------------------------

(defonce app-data (atom nil))            ; /api/state を keywordize した clj マップ
(defonce ui (atom {:thinking false}))    ; UI 一時状態

;; 社員エージェントの席レイアウト (Gather風オフィス座標 %)
(def workers
  [{:role "sales"   :label "営業"     :emoji "🧑‍💼" :desk "📞" :x 18 :y 24}
   {:role "eng"     :label "開発"     :emoji "🧑‍💻" :desk "💻" :x 64 :y 24}
   {:role "finance" :label "財務"     :emoji "👩‍💼" :desk "💴" :x 18 :y 62}
   {:role "ceo"     :label "CEO補佐"  :emoji "🧑‍🏫" :desk "📊" :x 64 :y 62}])

(defn prop-for [role props]
  (first (filter #(= (:role %) role) props)))

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

;; ---- 2D オフィス (社員キャラクター) -----------------------------------------

(defn worker-html [w props thinking?]
  (let [p       (prop-for (:role w) props)
        status  (:status p)
        state   (cond thinking?              "thinking"
                      (nil? p)               "working"
                      (= status "pending")   "proposing"
                      (= status "approved")  "approved"
                      (= status "rejected")  "rejected"
                      :else "working")
        bubble  (cond
                  (= state "thinking")  "<div class='bubble think'>💭…</div>"
                  (= state "proposing")
                  (str "<div class='bubble'>"
                       "<div class='act'>" (esc (:action p)) "</div>"
                       "<div class='hint'>" (esc (:effect_hint p)) "</div>"
                       "<div class='btns'>"
                       "<button class='approve' onclick=\"window.decide('" (:id p) "','approve')\">承認</button>"
                       "<button class='reject' onclick=\"window.decide('" (:id p) "','reject')\">却下</button>"
                       "</div></div>")
                  (= state "approved")  "<div class='bubble ok'>✅ 承認されました</div>"
                  (= state "rejected")  "<div class='bubble ng'>💢 却下</div>"
                  :else "")]
    (str "<div class='worker " (:role w) " " state "' style='left:" (:x w) "%;top:" (:y w) "%'>"
         bubble
         "<div class='avatar'>" (:emoji w) "</div>"
         "<div class='desk'>" (:desk w) "</div>"
         "<div class='nameplate'>" (:label w) "</div>"
         "</div>")))

(defn render-office! [data thinking?]
  (let [props (:proposals data)
        ws    (str/join (for [w workers] (worker-html w props thinking?)))
        ;; 意思決定者(あなた)アバター
        you   "<div class='worker you' style='left:41%;top:84%'><div class='avatar'>👑</div><div class='nameplate'>あなた (CEO)</div></div>"
        ;; 観葉植物などの装飾
        deco  "<div class='deco' style='left:6%;top:8%'>🪴</div><div class='deco' style='left:90%;top:8%'>🪟</div><div class='deco' style='left:90%;top:86%'>☕️</div><div class='deco' style='left:6%;top:86%'>🗄️</div>"]
    (set! (.-innerHTML ($ "office")) (str deco ws you))))

;; ---- サイドパネル -----------------------------------------------------------

(defn render-intel! [it]
  (when it
    (set! (.-textContent ($ "intel-depth")) (or (:intel_depth it) 0))
    (set! (.-innerHTML ($ "leads"))
          (str/join (for [l (take 6 (:latent_leads it))]
                      (let [eng? (= (:stage l) "engaged")]
                        (str "<li class='kv'><span>" (esc (:subject l))
                             "</span><span class='r'>" (:score l)
                             " <span class='stage " (if eng? "eng" "new") "'>" (:stage l) "</span></span></li>")))))
    (set! (.-innerHTML ($ "revival"))
          (str/join (for [r (take 5 (:revival it))]
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

;; ---- 全体描画 ---------------------------------------------------------------

(defn render! []
  (when-let [d @app-data]
    (let [k (:kpis d) thinking? (:thinking @ui)]
      (render-kpis! k)
      (render-office! d thinking?)
      (render-intel! (:intel d))
      (render-fin! d)
      (render-ledger! (:ledger d))
      (set! (.-textContent ($ "turn-badge")) (str "ターン " (:turn k)))
      (let [b ($ "llm-badge")]
        (set! (.-textContent b) (if (:llm_live d) "実LLM gemma4" "スタブLLM"))
        (set! (.-className b) (str "badge " (if (:llm_live d) "live" "stub"))))
      (.toggle (.-classList ($ "gameover")) "hidden" (not= (:status k) "bankrupt"))
      (set! (.-disabled ($ "advance")) (= (:status k) "bankrupt")))))

;; ---- 通信 -------------------------------------------------------------------

(defn fetch-json [url opts]
  (-> (js/fetch url (clj->js opts)) (.then #(.json %))))

(defn refresh []
  (-> (fetch-json "/api/state" {})
      (.then (fn [d] (reset! app-data (js->clj d :keywordize-keys true)) (render!)))))

(defn advance []
  (swap! ui assoc :thinking true)
  (render!)
  (set! (.-textContent ($ "advance")) "社員が思考中…")
  (-> (fetch-json "/api/turn/advance" {:method "POST"})
      (.then (fn [d]
               (reset! app-data (js->clj d :keywordize-keys true))
               (swap! ui assoc :thinking false)
               (set! (.-textContent ($ "advance")) "次の四半期へ ▶")
               (render!)))))

(defn decide [id what]
  (-> (fetch-json (str "/api/proposal/" id "/" what) {:method "POST"})
      (.then (fn [d] (reset! app-data (js->clj d :keywordize-keys true)) (render!)))))

;; inline onclick から呼べるようグローバルに公開
(set! (.-decide js/window) decide)

(defn start! []
  (.addEventListener ($ "advance") "click" advance)
  (let [es (js/EventSource. "/api/events")]
    (set! (.-onmessage es) (fn [_] (refresh))))
  (refresh))

(start!)
