(ns board-entry
  "Agent-facing board page: inject state, force-paint, screenshot.

  Compiled without `street` / `gl` / `kami.webgpu`, so `render --view board`
  works when the WebGL engine checkout is absent. Markup is `shop_view` — the
  same builders the full preview uses.

  ADR-2608108000 / #1752: board is DOM; do not rebuild it in 3D."
  (:require [itonami.isic-9601.logic :as l]
            [itonami.isic-9601.world :as world]
            [shop-view :as view]
            [set-state :as set-state]))

(def app (atom {:view "shop"
                :world (world/init)
                :district "isic-9601"
                :st (l/init 20260808 "isic-9601")
                :speed 300
                :frozen? true}))

(def last-paint (atom 0))

(defn- paint! []
  (let [{:keys [district st speed]} @app
        root (js/document.getElementById "shop")]
    (set! (.-innerHTML root) (view/shop-panel (l/summary st) district speed))))

(defn render!
  "Same throttle contract as `ui/render!`. `__setState` must pass `true`."
  ([] (render! false))
  ([force?]
   (let [now (js/Date.now)]
     (when (or force? (>= (- now @last-paint) 80))
       (reset! last-paint now)
       (paint!)))))

(defn boot! []
  (set! (.-__setState js/window)
        (fn [env-js] (set-state/from-js! app render! env-js)))
  ;; Idle placeholder until the agent injects; force so the throttle cannot skip it.
  (render! true))

(boot!)
