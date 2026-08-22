(ns isekai.games.boutique-stack.web
  "Boutique Stack in a browser, drawn by the live KAMI executor.

  One document, one bundle, one mount (ADR-2608080100). The canvas is the
  authoritative 3D surface — no DOM, no CSS transform, no Canvas 2D draws any
  part of the shop (the repo-wide 3D rule). The HUD around it is DADS over the
  shared `--hig-*` token contract, which is the other repo-wide rule, and it
  reads `render-ir/scene`'s HUD model rather than the simulation directly.

  What runs here that has not run before: `render-ir/kami-frame` handed to
  `kami.webgpu/draw!`. Everything below the seam is the same pure `.cljc` the
  nbb suite and the balance gate already exercise."
  (:require [clojure.edn :as edn]
            [isekai.games.boutique-stack.art :as art]
            [isekai.games.boutique-stack.economy :as econ]
            [isekai.games.boutique-stack.manager :as manager]
            [isekai.games.boutique-stack.render-ir :as rir]
            [isekai.games.boutique-stack.sim :as sim]
            [isekai.games.boutique-stack.world :as world]
            [goog.object :as gobj]
            [html.core :as h]
            [jp-go-dds.core :as dds]
            [jp-go-dds.tokens :as tokens]
            [kami.webgpu :as gpu]
            [shadow.resource :as rc]))

(def shop-edn (rc/inline "shops/bs-flagship.edn"))

(defonce app (atom {:ctx nil :world nil :state nil :running? true :auto? true}))

;; ------------------------------------------------------------------- the HUD

(defn- yen [n] (str "¥" (.toLocaleString (js/Number. n) "ja-JP")))

(defn- el [id] (.getElementById js/document id))

(defn- button
  "A DADS button, rendered from the library's own hiccup rather than a local
  copy of its markup. `:attrs` is the passthrough the component exposes for
  exactly this — a selectable hook that does not fork the component."
  [id label sub enabled?]
  (h/->html
   (dds/button (str label " · " sub)
               {:type (if enabled? :solid-fill :outline)
                :size "sm"
                :disabled (not enabled?)
                :attrs {:data-bs id}})))

(defn- render-shop-actions!
  [state world]
  (let [host (el "bs-actions")
        entries
        (concat
         (for [z (econ/unlockable state world)]
           {:id (str "unlock:" (name (:zone/id z)))
            :label (:zone/name z) :sub (yen (:zone/cost z))
            :on? (econ/can-unlock? state world (:zone/id z))
            :f #(econ/unlock % world (:zone/id z))})
         (for [[role spec] (sort-by key econ/staff-roles)
               :let [have (count (filter #(= role (:role %)) (:staff state)))
                     cost (:staff/hire-cost spec)]]
           {:id (str "hire:" (name role))
            :label (str (:staff/name spec) " " have "/" (:staff/max spec))
            :sub (yen cost)
            :on? (and (< have (:staff/max spec)) (>= (:money state) cost))
            :f #(econ/hire % world role)})
         (for [[kind spec] (sort-by key econ/upgrade-kinds)
               :let [lvl (get-in state [:upgrades kind] 1)
                     cost (econ/upgrade-cost kind lvl)]]
           {:id (str "upgrade:" (name kind))
            :label (str (:upgrade/name spec) " Lv" lvl)
            :sub (if cost (yen cost) "MAX")
            :on? (boolean (and cost (>= (:money state) cost)))
            :f #(econ/buy-upgrade % kind)}))]
    (set! (.-innerHTML host)
          (apply str (map #(button (:id %) (:label %) (:sub %) (:on? %)) entries)))
    (doseq [e entries]
      (when-let [node (.querySelector host (str "[data-bs=\"" (:id e) "\"]"))]
        (.addEventListener node "click" (fn [] (swap! app update :state (:f e))))))))

(defn- render-hud!
  [state world]
  (let [scene (rir/scene state world)
        h (:render/hud scene)
        stats (:stats state)]
    (set! (.-textContent (el "bs-money")) (yen (:hud/money h)))
    (set! (.-textContent (el "bs-carry"))
          (str (:hud/carry h) " / " (:hud/carry-capacity h)
               (when-let [i (:hud/carry-item h)] (str " " (name i)))))
    (set! (.-textContent (el "bs-served")) (str (:served stats)))
    (set! (.-textContent (el "bs-lost"))
          (str (:lost stats) " (棚 " (:walkouts-empty stats)
               " / 列 " (:walkouts-queue stats) ")"))
    (set! (.-textContent (el "bs-depts"))
          (->> (:hud/departments h) (map :dept/name) (interpose " · ") (apply str)))
    (set! (.-textContent (el "bs-clock"))
          (let [secs (quot (:tick state) (:shop/tick-rate (:shop world) 20))]
            (str (quot secs 60) "分" (mod secs 60) "秒")))
    (render-shop-actions! state world)))

;; --------------------------------------------------------------------- input

(defn- canvas->shop
  "Pointer -> a point on the shop floor.

  The camera is a fixed frame of the whole shop, so the inverse is an affine
  map of the viewport onto the footprint; a full unproject would buy nothing
  the hit-test can use and would need the executor's matrices."
  [world ev]
  (let [c (el "bs-canvas")
        r (.getBoundingClientRect c)
        u (/ (- (.-clientX ev) (.-left r)) (.-width r))
        v (/ (- (.-clientY ev) (.-top r)) (.-height r))
        {:keys [min-x max-x min-y max-y]} (rir/bounds world)]
    [(int (+ min-x (* u (- max-x min-x))))
     (int (+ min-y (* v (- max-y min-y))))]))

(defn- on-pointer!
  [ev]
  (let [{:keys [world state]} @app
        [x y] (canvas->shop world ev)]
    (when-let [hit (rir/hit-test state world [x 0 y])]
      (when (= :unlock (first hit))
        (swap! app update :state econ/unlock world (second hit))))))

;; ---------------------------------------------------------------------- loop

(def ^:private ticks-per-frame 1)

(defn- step!
  []
  (let [{:keys [ctx world state running? auto?]} @app]
    (when (and running? ctx)
      (let [state (loop [s state k 0]
                    (if (>= k ticks-per-frame)
                      s
                      (recur (cond-> (sim/tick s world) auto? (manager/policy world)) (inc k))))]
        (swap! app assoc :state state)
        (gpu/draw! ctx (rir/kami-frame state world))
        (render-hud! state world)))
    (js/requestAnimationFrame step!)))

;; -------------------------------------------------------------------- mount

(defn- inject-tokens!
  "DADS primitives with the `--hig-*` contract redefined on top of them.

  `jp-go-dds.page/->page` is a server-side page builder; this is a canvas app
  that mounts into an existing document, so the same two stylesheets are
  injected directly. Everything in the app CSS is written against `--hig-*`
  and nothing re-derives a token — the bridge carries all 71."
  []
  (let [s (.createElement js/document "style")]
    (set! (.-textContent s) (str tokens/root-css "\n" tokens/bridge-css))
    (.appendChild (.-head js/document) s)))

(defn- resize!
  "Match the drawing buffer to the element's box.

  The executor captures width/height once, at `init!`, and uses them for both
  the GL viewport and the projection aspect. So this has to run *after* layout
  and *before* init, and a later size change means a fresh init rather than a
  new canvas size — otherwise the frame is stretched by whatever ratio the two
  drifted apart by."
  [canvas]
  (let [dpr (js/Math.min 2 (or (.-devicePixelRatio js/window) 1))
        _ (gobj/set js/window "__dprUsed" dpr)
        r (.getBoundingClientRect canvas)
        w (js/Math.max 1 (js/Math.round (* dpr (.-width r))))
        h (js/Math.max 1 (js/Math.round (* dpr (.-height r))))]
    (when (or (not= w (.-width canvas)) (not= h (.-height canvas)))
      (set! (.-width canvas) w)
      (set! (.-height canvas) h)
      true)))

(defn- after-layout
  "Two frames: one for the document to lay out, one for the reflow that the
  first frame's style resolution can still cause."
  [f]
  (js/requestAnimationFrame #(js/requestAnimationFrame f)))

(defn- boot-gpu!
  [canvas]
  (-> (gpu/init! canvas)
      (.then (fn [ctx]
               (swap! app assoc :ctx ctx)
               (set! (.-textContent (el "bs-backend"))
                     (str "KAMI " (name (or (:backend ctx) :webgpu))
                          " · " (.-width canvas) "×" (.-height canvas)))
               ctx))))

(defn ^:export start!
  []
  (inject-tokens!)
  (let [shop (edn/read-string shop-edn)
        [world state] (sim/start shop)
        canvas (el "bs-canvas")]
    (swap! app assoc :world world :state state)
    (.addEventListener canvas "pointerdown" on-pointer!)
    (.addEventListener (el "bs-auto") "click"
                       (fn [] (let [a (not (:auto? @app))]
                                (swap! app assoc :auto? a)
                                (set! (.-textContent (el "bs-auto"))
                                      (if a "店長: 自動" "店長: 手動")))))
    ;; A resize invalidates the executor's cached viewport, so re-init rather
    ;; than resize under it. Debounced: a drag emits dozens of events.
    ;;
    ;; A window `resize` listener is not enough. The element's box changes
    ;; without the window changing — the action bar only gets its height once
    ;; the first HUD render puts buttons in it, and that alone left the
    ;; drawing buffer 7% taller than its box (measured), which is a 7%
    ;; vertical stretch of the whole shop. ResizeObserver sees the element.
    (let [pending (atom nil)
          reflow (fn []
                   (when-let [t @pending] (js/clearTimeout t))
                   (reset! pending
                           (js/setTimeout
                            #(when (resize! canvas) (boot-gpu! canvas))
                            120)))]
      (.addEventListener js/window "resize" reflow)
      (.observe (js/ResizeObserver. reflow) canvas))
    (after-layout
     (fn []
       (resize! canvas)
       (-> (boot-gpu! canvas)
           (.then (fn [ctx]
                    (let [api (js-obj)]
                      (gobj/set api "backend" (name (or (:backend ctx) :webgpu)))
                      (gobj/set api "instances"
                                (fn [] (count (:instances (rir/kami-frame (:state @app) (:world @app))))))
                      (gobj/set api "tick" (fn [] (:tick (:state @app))))
                      (gobj/set api "money" (fn [] (:money (:state @app))))
                      (gobj/set api "served" (fn [] (get-in (:state @app) [:stats :served])))
                      (gobj/set api "canvas"
                                (fn [] (js-obj "w" (.-width canvas) "h" (.-height canvas)
                                               "cw" (.-clientWidth canvas) "ch" (.-clientHeight canvas)
                                               "dpr" (.-devicePixelRatio js/window))))
                      (gobj/set api "run"
                                (fn [n]
                                  (swap! app update :state
                                         #(sim/run % (:world @app) n manager/policy))
                                  (:tick (:state @app))))
                      (gobj/set js/window "boutiqueStack" api))
                    (step!)))
           (.catch (fn [e]
                     (set! (.-textContent (el "bs-backend")) (str "GPU 起動失敗: " e))
                     (js/console.error e))))))))
