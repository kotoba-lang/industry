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
(def workers
  [{:role "sales"   :label "営業"    :emoji "🧑‍💼" :desk "📞" :hx 20 :hy 26 :mx 40 :my 48 :ph 0.0}
   {:role "eng"     :label "開発"    :emoji "🧑‍💻" :desk "💻" :hx 66 :hy 26 :mx 52 :my 46 :ph 1.6}
   {:role "finance" :label "財務"    :emoji "👩‍💼" :desk "💴" :hx 20 :hy 64 :mx 44 :my 56 :ph 3.1}
   {:role "ceo"     :label "CEO補佐" :emoji "🧑‍🏫" :desk "📊" :hx 66 :hy 64 :mx 56 :my 54 :ph 4.7}])

(defn prop-for [role props] (first (filter #(= (:role %) role) props)))

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
  (let [deco "<div class='deco' style='left:6%;top:8%'>🪴</div><div class='deco' style='left:92%;top:10%'>🪟</div><div class='deco' style='left:92%;top:88%'>☕️</div><div class='deco' style='left:6%;top:88%'>🗄️</div><div class='deco table' style='left:48%;top:50%'>🪑📋🪑</div>"
        you "<div class='worker you' style='left:48%;top:84%'><div class='avatar'>👑</div><div class='nameplate'>あなた (CEO)</div></div>"
        ws (str/join (for [w workers]
                       (str "<div class='worker " (:role w) "' id='w-" (:role w) "' style='left:" (:hx w) "%;top:" (:hy w) "%'>"
                            "<div class='bubble-host' id='bub-" (:role w) "'></div>"
                            "<div class='avatar'>" (:emoji w) "</div>"
                            "<div class='desk'>" (:desk w) "</div>"
                            "<div class='nameplate'>" (:label w) "</div></div>")))]
    (set! (.-innerHTML ($ "office")) (str deco ws you))
    (reset! positions (into {} (for [w workers] [(:role w) {:x (:hx w) :y (:hy w)}])))))

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
                  :else "")))))))

;; 歩行: home↔meeting を ease で移動 + ゆらぎ。requestAnimationFrame ループ。
(defn tick! [t]
  (let [thinking (:thinking @ui)]
    (doseq [w workers]
      (let [pos (get @positions (:role w) {:x (:hx w) :y (:hy w)})
            tx (if thinking (:mx w) (:hx w))
            ty (if thinking (:my w) (:hy w))
            nx (+ (:x pos) (* 0.05 (- tx (:x pos))))
            ny (+ (:y pos) (* 0.05 (- ty (:y pos))))
            wob (* 1.3 (js/Math.sin (+ (/ t 600.0) (:ph w))))
            el ($ (str "w-" (:role w)))]
        (swap! positions assoc (:role w) {:x nx :y ny})
        (when el
          (set! (.. el -style -left) (str (+ nx wob) "%"))
          (set! (.. el -style -top) (str ny "%"))
          (.toggle (.-classList el) "moving" (> (js/Math.abs (- tx nx)) 0.6))))))
  (js/requestAnimationFrame tick!))

;; ---- 関係グラフ (SVG): gftd ↔ 潜在リード, 太さ=接触量 色=確度 リング=離反 ----

(defn lead-color [conf]
  (cond (>= conf 80) "#3fb950" (>= conf 55) "#d29922" :else "#f85149"))

(defn render-graph! [leads]
  (let [top (vec (take 7 leads))
        n (count top)
        cx 120 cy 96
        max-s (max 1 (apply max 1 (map :score top)))
        nodes (map-indexed
               (fn [i l]
                 (let [a (- (* (/ i (max 1 n)) 2 js/Math.PI) (/ js/Math.PI 2))
                       x (+ cx (* 74 (js/Math.cos a)))
                       y (+ cy (* 64 (js/Math.sin a)))
                       sw (+ 0.6 (* 5 (/ (:score l) max-s)))
                       r (+ 6 (* 8 (/ (:confidence l) 100.0)))
                       churn (= (:risk l) "churn")
                       short (first (str/split (:subject l) #"\."))]
                   (str "<line x1='" cx "' y1='" cy "' x2='" x "' y2='" y "' stroke='#3a4452' stroke-width='" sw "'/>"
                        "<circle cx='" x "' cy='" y "' r='" r "' fill='" (lead-color (:confidence l)) "' "
                        (when churn "stroke='#f85149' stroke-width='2.5' ") "/>"
                        "<text x='" x "' y='" (+ y r 9) "' font-size='8' fill='#c7d0db' text-anchor='middle'>" (esc short) "</text>")))
               top)]
    (set! (.-innerHTML ($ "graph"))
          (str "<svg viewBox='0 0 240 200' class='graphsvg'>"
               (str/join nodes)
               "<circle cx='" cx "' cy='" cy "' r='15' fill='#4f9dff'/>"
               "<text x='" cx "' y='" (+ cy 3) "' font-size='9' fill='#fff' text-anchor='middle' font-weight='700'>gftd</text>"
               "</svg>"))))

;; ---- intel パネル -----------------------------------------------------------

(defn render-intel! [it]
  (when it
    (set! (.-textContent ($ "intel-depth")) (or (:intel_depth it) 0))
    (render-graph! (:latent_leads it))
    (set! (.-innerHTML ($ "leads"))
          (str/join (for [l (take 6 (:latent_leads it))]
                      (let [eng? (= (:stage l) "engaged")
                            churn? (= (:risk l) "churn")]
                        (str "<li class='kv'><span>" (esc (:subject l))
                             (when churn? " <span class='risk'>離反</span>")
                             "</span><span class='r'>確度" (:confidence l)
                             " <span class='stage " (if eng? "eng" "new") "'>" (:stage l) "</span></span></li>")))))
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
