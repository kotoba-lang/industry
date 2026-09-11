(ns ui
  "Browser shell for the ISIC 9601 idle tycoon.

  This file is COMPILED BY SQUINT, not run by nbb -- it is the only part of
  the game allowed to touch the DOM, and it holds no rules of its own. Every
  decision it draws comes out of `itonami.isic-9601.logic/summary`, so the
  shop cannot behave one way here and another way in the test suite or in the
  network-isekai guest.

  It is written in cljs rather than hand-written JS on purpose: this
  workspace's rule is that new browser-side driver code is cljs compiled to
  JS, not a hand-authored `.mjs` (CLAUDE.md, 2026-07-14).

  `window.__setState` (#1752) injects an `:itonami-game/state` envelope so
  `render --view board` can screenshot a played shop. Board markup lives in
  `shop_view`; injection + force-repaint live in `set_state`."
  (:require [itonami.isic-9601.logic :as l]
            [itonami.isic-9601.world :as world]
            [street :as street]
            [shop-view :as view]
            [set-state :as set-state]))

;; --------------------------------------------------------------------------
;; state -- one mutable cell, the whole shop, replaced wholesale each event
;; --------------------------------------------------------------------------

(def app (atom {:view "street"
                :world (world/init)
                :district "isic-9601"
                :st (l/init 20260808 "isic-9601")
                :speed 300
                :frozen? false}))

;; The street is the first thing shown. A player who lands on one shop's board has no way to
;; learn that the other seven exist, and the eight-independent-confirmations argument — the
;; reason this game has a map at all — is invisible from inside a single laundry.

(def last-paint (atom 0))

(defn- paint! []
  (let [{:keys [view world district st speed]} @app
        root (js/document.getElementById "shop")]
    (if (= view "street")
      (do (set! (.-innerHTML root) (view/street-panel world))
          (when-let [wrap (js/document.getElementById "street-wrap")]
            (set! (.-display (.-style wrap)) "block"))
          (when-let [canvas (js/document.getElementById "street-canvas")]
            (street/draw! canvas world)))
      (do (when-let [wrap (js/document.getElementById "street-wrap")]
            (set! (.-display (.-style wrap)) "none"))
          (set! (.-innerHTML root) (view/shop-panel (l/summary st) district speed))))))

(defn render!
  "Repaint the board. The whole panel is rebuilt from `summary`, which keeps
  the view a pure function of state -- but at ×16 that would be 50 full
  rebuilds a second, which wastes work and detaches every node mid-click, so a
  pointer can never land on a button. Painting is therefore throttled to
  ~12fps and decoupled from the tick rate; `force?` bypasses the throttle so a
  button press always shows its own result immediately.

  **Force-repaint gotcha (#1752):** `window.__setState` and `to-street` must
  pass `true`. A throttled-out paint is not followed by another when the tick
  loop is frozen or the view left the shop — the page would keep showing the
  old board."
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
    (swap! app (fn [a] (assoc a :view "shop" :district id :frozen? false
                              :st (l/init (+ 20260808 (count id)) id))))
    (render! true)))

(defn dispatch! [act arg]
  (when (= act "speed")
    (swap! app assoc :speed (js/parseInt arg 10)))
  (when (= act "enter") (enter! arg))
  ;; forced: `render!` throttles to ~12fps, and the tick loop only paints while a shop is
  ;; open — so a throttled-out repaint on the way to the street is never followed by another
  ;; one, and the page keeps showing the shop it just left.
  (when (= act "to-street") (swap! app assoc :view "street" :frozen? false) (render! true))
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
        g (.-__GLSL js/window)
        glsl (when g {:vert (.-vert g) :frag (.-frag g)})]
    (when (and canvas glsl)
      (if-let [why (street/init! canvas glsl)]
        ;; say it rather than quietly showing a list. CLAUDE.md's 3D rule allows a WebGL 2.0
        ;; fallback under WebGPU, not a DOM fallback under WebGL — if this is reached, the
        ;; authoritative view is unavailable and the page should not pretend otherwise.
        (set! (.-innerHTML (js/document.getElementById "street-wrap"))
              (str "<p class='fine'>3D ビューを開けませんでした（WebGL 2.0 が使えません）: "
                   (view/esc why) "</p>"))
        (do
          (street/expose-probe! canvas)
          (.addEventListener canvas "click"
                             (fn [e]
                               (when-let [id (street/tap->district canvas e)]
                                 (enter! id))))
          (.addEventListener js/window "resize"
                             (fn [_] (when (= (:view @app) "street") (render! true))))))))
  ;; a re-armed timeout rather than setInterval, so a speed change takes
  ;; effect on the next tick instead of needing the timer torn down.
  ;; `:frozen?` (set by __setState) stops the clock so a capture stays put.
  (letfn [(step []
            (when (and (= (:view @app) "shop") (not (:frozen? @app)))
              (swap! app update :st (fn [st] (l/reduce-event st [:tick])))
              (note-victory!)
              (render!))
            (js/setTimeout step (:speed @app)))]
    (js/setTimeout step (:speed @app)))
  ;; Agent mouth (#1752). Must force-repaint — see `render!` docstring.
  (set! (.-__setState js/window)
        (fn [env-js] (set-state/from-js! app render! env-js)))
  (render! true))

(boot!)
