(ns ui
  "Browser shell for the ISIC 9601 idle tycoon.

  This file is COMPILED BY SQUINT, not run by nbb -- it is the only part of
  the game allowed to touch the DOM, and it holds no rules of its own. Every
  decision it draws comes out of `itonami.isic-9601.logic/summary`, so the
  shop cannot behave one way here and another way in the test suite or in the
  network-isekai guest.

  It is written in cljs rather than hand-written JS on purpose: this
  workspace's rule is that new browser-side driver code is cljs compiled to
  JS, not a hand-authored `.mjs` (CLAUDE.md, 2026-07-14)."
  (:require [itonami.isic-9601.logic :as l]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.district :as district]
            [street :as street]))

;; --------------------------------------------------------------------------
;; state -- one mutable cell, the whole shop, replaced wholesale each event
;; --------------------------------------------------------------------------

(def app (atom {:view "street"
                :world (world/init)
                :district "isic-9601"
                :st (l/init 20260808 "isic-9601")
                :speed 300}))

;; The street is the first thing shown. A player who lands on one shop's board has no way to
;; learn that the other seven exist, and the eight-independent-confirmations argument — the
;; reason this game has a map at all — is invisible from inside a single laundry.

(def speeds
  "Tick intervals in ms. An idle game that only runs at one speed is a bad
  idle game, and the browser smoke test needs the fast setting to play a whole
  run inside a timeout."
  [["×1" 300] ["×4" 75] ["×16" 20]])

(defn- esc [s]
  (-> (str s)
      (.replaceAll "&" "&amp;") (.replaceAll "<" "&lt;") (.replaceAll ">" "&gt;")))

(defn- nm
  "Keyword -> its name. Under squint a keyword IS its name string, and
  `clojure.core/name` does not exist, so `str` is both correct and portable
  here. The same call in nbb would need `name`; this file only ever runs
  under squint."
  [x] (str x))

(defn- pct [n d]
  (str (js/Math.round (* 100 (/ n (max 1 d)))) "%"))

;; --------------------------------------------------------------------------
;; view
;; --------------------------------------------------------------------------

(defn- last-n [n coll]
  (let [v (vec coll)]
    (vec (drop (max 0 (- (count v) n)) v))))

(defn- garment-row [g]
  (let [conflict? (:label-conflict? g)
        risk (:risk g)]
    (str "<div class='g" (when (or conflict? risk) " g--risk") "'>"
         "<div class='g__top'><span class='g__name'>" (esc (:desc g)) "</span>"
         "<span class='g__id'>" (esc (:id g)) "</span></div>"
         "<div class='g__meta'>"
         "<span class='tag'>提案 " (esc (:process g)) "</span>"
         "<span class='tag'>書類 " (:evidence g) "/4</span>"
         "<span class='tag" (when (< (:confidence g) l/confidence-floor) " tag--warn") "'>確度 "
         (js/Math.round (* 100 (:confidence g))) "%</span>"
         (when-not (:cited? g) "<span class='tag tag--bad'>根拠なし</span>")
         "</div>"
         (when (seq (:forbidden g))
           (str "<div class='g__label'>洗濯表示 禁止: " (esc (apply str (interpose " / " (:forbidden g)))) "</div>"))
         (when conflict?
           "<div class='g__flag'>⚠ 提案が洗濯表示に反する — 差し戻さないと洗浄でHOLDになる</div>")
         (when risk
           (str "<div class='g__flag'>⛔ " (esc (nm risk)) "</div>"))
         "<div class='bar'><i style='width:" (pct (:work g) (:need g)) "'></i></div>"
         "</div>")))

(defn- station-card [s costs]
  (let [k (:key s)]
    (str "<section class='card"
         (when-not (:open? s) " card--closed")
         (when (pos? (:ready s)) " card--ready") "'>"
         "<header class='card__h'>"
         "<h2 class='hig-headline'>" (esc (:label s)) "</h2>"
         "<span class='lv'>Lv." (:level s) "</span>"
         "</header>"
         "<div class='badges'>"
         (when-not (:open? s) "<span class='badge badge--off'>未開放</span>")
         (cond
           (:hard-human? s) "<span class='badge badge--human'>人手必須</span>"
           (:auto? s)       "<span class='badge badge--auto'>自動</span>"
           :else            "<span class='badge'>要承認</span>")
         "<span class='badge badge--n'>" (:count s) " 点</span>"
         "</div>"
         "<div class='gs'>"
         (if (zero? (:count s))
           "<p class='empty'>—</p>"
           (apply str (map garment-row (take 2 (:garments s)))))
         "</div>"
         "<div class='acts'>"
         "<button class='btn btn--go' data-act='tap' data-arg='" (nm k) "'"
         (when-not (:open? s) " disabled") ">承認</button>"
         (when (:rejectable? s)
           (str "<button class='btn' data-act='reject' data-arg='" (nm k) "'"
                (when-not (:open? s) " disabled") ">差し戻す</button>"))
         "<button class='btn btn--buy' data-act='buy' data-arg='" (nm k) "'>強化 ¥"
         (get costs (nm k)) "</button>"
         "</div>"
         "</section>")))

(defn- hud [sm]
  (str "<div class='hud'>"
       "<div class='hud__row'>"
       "<span class='stat'><b>¥" (:cash sm) "</b><small>売上</small></span>"
       "<span class='stat'><b>" (.repeat "●" (max 0 (:lives sm)))
       (.repeat "○" (max 0 (- 3 (:lives sm)))) "</b><small>顧客の信頼</small></span>"
       "<span class='stat'><b>" (:returned sm) "/" (:target sm) "</b><small>返却</small></span>"
       "<span class='stat'><b>P" (:phase sm) "</b><small>" (esc (:phase-label sm)) "</small></span>"
       "</div>"
       "<div class='hud__row hud__row--thin'>"
       "<span class='cert" (when-not (:cert-current? sm) " cert--bad") "'>"
       (if (:cert-current? sm)
         (str "溶剤取扱資格 有効 (" (:cert-ticks sm) ")")
         "溶剤取扱資格 失効 — 全工程が HOLD")
       "</span>"
       "<span class='q'>待ち " (:queue sm) " 人</span>"
       "</div>"
       "</div>"))

(defn- shop [sm]
  (let [nxt (:next-phase sm)]
    (str "<section class='card card--shop'>"
         "<header class='card__h'><h2 class='hig-headline'>店の運営</h2></header>"
         "<div class='acts acts--wrap'>"
         "<button class='btn' data-act='take-in'>受付する</button>"
         "<button class='btn" (when-not (:cert-current? sm) " btn--go") "' data-act='renew'>資格更新 ¥90</button>"
         "<button class='btn btn--buy' data-act='buy' data-arg='approver'>承認者を雇う ¥"
         (get (:costs sm) "approver") " (" (get (:levels sm) "approver") "人)</button>"
         (if nxt
           (str "<button class='btn btn--go' data-act='phase'>段階を上げる ¥" (:cash nxt)
                " / 実績" (:commits nxt) "</button>")
           "<span class='badge badge--auto'>最終段階</span>")
         "</div>"
         "<div class='acts acts--wrap'>"
         "<span class='badge'>速度</span>"
         (apply str (map (fn [sp]
                           (str "<button class='btn"
                                (when (= (second sp) (:speed @app)) " btn--go")
                                "' data-act='speed' data-arg='" (second sp) "'>"
                                (first sp) "</button>"))
                         speeds))
         "</div>"
         "<p class='note'>承認者は <b>取扱方法</b> と <b>資格照合</b> しか捌けません。"
         "<b>洗浄</b> と <b>返却</b> は phase 3 でも自動化されません — "
         "<code>laundry.phase</code> がどの段階の <code>:auto</code> にも入れていないからです。</p>"
         "</section>")))

(defn- ledger [sm]
  (str "<section class='card card--log'>"
       "<header class='card__h'><h2 class='hig-headline'>監査台帳</h2></header>"
       "<ol class='log'>"
       (apply str
              (map (fn [e]
                     (str "<li class='log__i log__i--" (esc (nm (:disposition e))) "'>"
                          "<span class='log__t'>t" (:t e) "</span> "
                          "<span class='log__op'>" (esc (nm (:op e))) "</span> "
                          (esc (or (:detail e) (when (:basis e) (nm (:basis e))) ""))
                          "</li>"))
                   (reverse (last-n 12 (:ledger sm)))))
       "</ol></section>"))

(defn- banner [sm]
  (cond
    (= (nm (:flow sm)) "victory")
    "<div class='banner banner--win'>監査クローズ — 40点を規程どおり返却しました<button class='btn' data-act='reset'>もう一度</button></div>"
    (= (nm (:flow sm)) "gameover")
    "<div class='banner banner--lose'>信頼を失いました — 台帳を見れば、どの規則で止まったか残っています<button class='btn' data-act='reset'>もう一度</button></div>"
    :else ""))

(def last-paint (atom 0))

(defn- street-panel
  "The chrome around the canvas. The canvas itself is never rewritten — replacing its node
  would drop the WebGL context, and the context is where the whole street lives."
  [w]
  (let [s (world/status w)]
    (str "<div class='street__hud'>"
         "<span class='stat'><b>" (count (filter :unlocked? (:districts s))) "/" (:total s)
         "</b><small>開放</small></span>"
         "<span class='stat'><b>" (:never-auto-total s)
         "</b><small>自動化されない工程</small></span>"
         "</div>"
         "<ol class='street__list'>"
         (apply str
                (map (fn [d]
                       (str "<li class='dist" (when-not (:unlocked? d) " dist--locked") "'"
                            (when (:unlocked? d) (str " data-act='enter' data-arg='" (esc (:id d)) "'"))
                            ">"
                            "<span class='dist__lock'>" (if (:unlocked? d) "●" "🔒") "</span>"
                            "<span class='dist__label'>" (esc (:label d)) "</span>"
                            "<span class='dist__isic'>ISIC " (esc (:isic d)) "</span>"
                            "<span class='dist__subject'>" (esc (:subject d)) "</span>"
                            "</li>"))
                     (:districts s)))
         "</ol>")))

(defn- shop-panel [sm district-id]
  (str "<div class='shop__back'>"
       "<button class='btn' data-act='to-street'>← 営みの街</button>"
       "<span class='shop__which'>" (esc (:district-label sm)) "</span>"
       "<span class='shop__isic'>" (esc district-id) "</span>"
       "</div>"
       (banner sm)
       (hud sm)
       "<div class='grid'>"
       (apply str (map (fn [s] (station-card s (:costs sm))) (:stations sm)))
       "</div>"
       (shop sm)
       (ledger sm)))

(defn- paint! []
  (let [{:keys [view world district st]} @app
        root (js/document.getElementById "shop")]
    (if (= view "street")
      (do (set! (.-innerHTML root) (street-panel world))
          (set! (.-display (.-style (js/document.getElementById "street-wrap"))) "block")
          (street/draw! (js/document.getElementById "street-canvas") world))
      (do (set! (.-display (.-style (js/document.getElementById "street-wrap"))) "none")
          (set! (.-innerHTML root) (shop-panel (l/summary st) district))))))

(defn render!
  "Repaint the board. The whole panel is rebuilt from `summary`, which keeps
  the view a pure function of state -- but at ×16 that would be 50 full
  rebuilds a second, which wastes work and detaches every node mid-click, so a
  pointer can never land on a button. Painting is therefore throttled to
  ~12fps and decoupled from the tick rate; `force?` bypasses the throttle so a
  button press always shows its own result immediately."
  ([] (render! false))
  ([force?]
   (let [now (js/Date.now)]
     (when (or force? (>= (- now @last-paint) 80))
       (reset! last-paint now)
       (paint!)))))

;; --------------------------------------------------------------------------
;; events
;; --------------------------------------------------------------------------

(defn enter!
  "Open a district's board. Refused rather than faked when the district is locked or has no
  board — `street/enterable?` is the one place that decides."
  [id]
  (when (street/enterable? (:world @app) id)
    (swap! app (fn [a] (assoc a :view "shop" :district id
                              :st (l/init (+ 20260808 (count id)) id))))
    (render! true)))

(defn dispatch! [act arg]
  (when (= act "speed")
    (swap! app assoc :speed (js/parseInt arg 10)))
  (when (= act "enter") (enter! arg))
  ;; forced: `render!` throttles to ~12fps, and the tick loop only paints while a shop is
  ;; open — so a throttled-out repaint on the way to the street is never followed by another
  ;; one, and the page keeps showing the shop it just left.
  (when (= act "to-street") (swap! app assoc :view "street") (render! true))
  (swap! app update :st
         (fn [st]
           (cond
             (= act "tap")     (l/reduce-event st [:tap arg])
             (= act "reject")  (l/reduce-event st [:reject arg])
             (= act "buy")     (l/reduce-event st [:buy arg])
             (= act "take-in") (l/reduce-event st [:take-in])
             (= act "renew")   (l/reduce-event st [:renew])
             (= act "phase")   (l/reduce-event st [:phase])
             (= act "reset")   (l/init (+ 1 (:seed st)) (:district @app))
             (= act "speed")   st
             :else st)))
  (render!))

(defn- cleared-ids
  "Districts whose audit has closed, as a set of ids — the street's unlock ladder counts
  these, so a win has to be recorded somewhere that survives leaving the shop."
  [a] (or (:cleared-ids a) #{}))

(defn- note-victory!
  "A closed audit unlocks the next business. Recorded once per district: the flow stays
  `:victory` for every tick afterwards, so counting transitions rather than states is what
  keeps one win from unlocking the whole street."
  []
  (let [{:keys [st district]} @app]
    (when (and (= (str (:flow (l/summary st))) "victory")
               (not (contains? (cleared-ids @app) district)))
      (swap! app (fn [a]
                   (let [ids (conj (cleared-ids a) district)]
                     (assoc a :cleared-ids ids
                              :world (assoc (:world a) :cleared (count ids)))))))))

(defn boot! []
  (.addEventListener (js/document.getElementById "shop") "click"
                     (fn [e]
                       (let [b (.closest (.-target e) "[data-act]")]
                         (when b
                           (dispatch! (.getAttribute b "data-act")
                                      (.getAttribute b "data-arg"))))))
  ;; the 3D street: the engine's GLSL is inlined into the page at build time, so the
  ;; canvas needs nothing from the network
  (let [canvas (js/document.getElementById "street-canvas")
        glsl {:vert (.-vert js/window.__GLSL) :frag (.-frag js/window.__GLSL)}]
    (if-let [why (street/init! canvas glsl)]
      ;; say it rather than quietly showing a list. CLAUDE.md's 3D rule allows a WebGL 2.0
      ;; fallback under WebGPU, not a DOM fallback under WebGL — if this is reached, the
      ;; authoritative view is unavailable and the page should not pretend otherwise.
      (set! (.-innerHTML (js/document.getElementById "street-wrap"))
            (str "<p class='fine'>3D ビューを開けませんでした（WebGL 2.0 が使えません）: "
                 (esc why) "</p>"))
      (do
        (street/expose-probe! canvas)
        (.addEventListener canvas "click"
                           (fn [e]
                             (when-let [id (street/tap->district canvas e)]
                               (enter! id))))
        (.addEventListener js/window "resize"
                           (fn [_] (when (= (:view @app) "street") (render! true)))))))
  ;; a re-armed timeout rather than setInterval, so a speed change takes
  ;; effect on the next tick instead of needing the timer torn down
  (letfn [(step []
            (when (= (:view @app) "shop")
              (swap! app update :st (fn [st] (l/reduce-event st [:tick])))
              (note-victory!)
              (render!))
            (js/setTimeout step (:speed @app)))]
    (js/setTimeout step (:speed @app)))
  (render! true))

(boot!)
