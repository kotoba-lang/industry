(ns shop-view
  "Pure HTML builders for the shop board. No rules, no atoms, no WebGL.

  Shared by `ui.cljs` (full page) and `board_entry.cljs` (agent capture page) so the
  board an agent screenshots is the same markup a player sees — not a second renderer."
  (:require [itonami.isic-9601.logic :as l]
            [itonami.isic-9601.world :as world]))

(def speeds
  "Tick intervals in ms. Kept here so the shop panel's speed buttons match the
  full page without `board_entry` having to invent its own."
  [["×1" 300] ["×4" 75] ["×16" 20]])

(defn esc [s]
  (-> (str s)
      (.replaceAll "&" "&amp;") (.replaceAll "<" "&lt;") (.replaceAll ">" "&gt;")))

(defn nm
  "Keyword -> its name. Under squint a keyword IS its name string."
  [x] (str x))

(defn- pct [n d]
  (str (js/Math.round (* 100 (/ n (max 1 d)))) "%"))

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

(defn hud [sm]
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

(defn- shop [sm speed]
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
                                (when (= (second sp) speed) " btn--go")
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

(defn banner [sm]
  (cond
    (= (nm (:flow sm)) "victory")
    "<div class='banner banner--win'>監査クローズ — 40点を規程どおり返却しました<button class='btn' data-act='reset'>もう一度</button></div>"
    (= (nm (:flow sm)) "gameover")
    "<div class='banner banner--lose'>信頼を失いました — 台帳を見れば、どの規則で止まったか残っています<button class='btn' data-act='reset'>もう一度</button></div>"
    (= (nm (:flow sm)) "stalled")
    "<div class='banner banner--lose'>行き詰まり — 資格失効かつ更新費不足<button class='btn' data-act='reset'>もう一度</button></div>"
    :else ""))

(defn shop-panel
  "Full shop board markup for `summary` + district id."
  ([sm district-id] (shop-panel sm district-id 300))
  ([sm district-id speed]
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
        (shop sm speed)
        (ledger sm))))

(defn street-panel
  "The chrome around the canvas (district list). Canvas itself is never rewritten."
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
