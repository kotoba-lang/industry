(ns set-state
  "Inject an `:itonami-game/state` envelope into the page (ADR-2608108000 / #1752).

  `window.__setState` is the agent-facing mouth. It replaces the app atom and
  **force-repaints**. `render!` throttles to ~12fps; a throttled-out paint after
  inject would leave the previous board on screen with no follow-up tick to
  recover it (same gotcha as `to-street` in `ui.cljs`).

  Ticks are frozen after inject so the screenshot is the state that was asked
  for, not that state plus N idle ticks that raced the capture."
  (:require [itonami.isic-9601.logic :as l]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.district :as district]))

(defn- revive-shop
  "JSON round-trip drops sets inside `:spec`. Re-attach the district's live
  spec from `:district` so `contains?` on `:auto` / `:writes` keeps working.

  Parameter is `district-id` on purpose — naming it `district` would shadow the
  `district` ns alias and turn `district/spec` into a method call on a string
  (`t.spec is not a function` in the capture page)."
  [shop district-id]
  (when shop
    (let [id (or (:district shop) district-id "isic-9601")
          spec (or (district/spec id) (district/spec "isic-9601"))]
      (assoc shop :spec spec :district (:id spec)))))

(defn apply-envelope!
  "Write `env` (clj map, ADR envelope shape) into `app` atom and force-paint.

  `render!` must accept a force? flag — pass the page's own `render!`."
  [app render! env]
  (let [kind (:kind env)
        version (:version env)
        district-id (or (:district env) "isic-9601")
        seed (or (:seed env) 20260808)
        world-st (or (:world env) (world/init))
        shop (revive-shop (:shop env) district-id)]
    (when (and kind (not= (str kind) "itonami-game/state"))
      (throw (js/Error. (str "not an itonami game state: :kind " kind))))
    (when (and version (not= version 1))
      (throw (js/Error. (str "unsupported state :version " version))))
    (reset! app {:view (if shop "shop" "street")
                 :world world-st
                 :district (if shop (:district shop) district-id)
                 :st (or shop (l/init seed district-id))
                 :speed 300
                 :frozen? true
                 :seed seed})
    ;; FORCE — see ns docstring. Never call (render!) here.
    (render! true)
    (let [sm (l/summary (:st @app))]
      #js {:ok true
           :view (:view @app)
           :district (:district @app)
           :cash (:cash sm)
           :returned (:returned sm)
           :phase (:phase sm)
           :flow (str (:flow sm))
           :hud (str "¥" (:cash sm) " "
                     (:returned sm) "/40 "
                     "P" (:phase sm))})))

(defn from-js!
  "Browser entry: `window.__setState(envelopeJs)`.

  `env-js` is a plain JSON object from Playwright (string keys). Squint treats
  keywords as their name strings, and `squint_core.get` reads JS objects, so no
  `js->clj` round-trip is required — and squint does not implement `js->clj`
  (it would compile to a bare `js__GT_clj` ReferenceError; see squint_shim)."
  [app render! env-js]
  (try
    (apply-envelope! app render! env-js)
    (catch :default e
      #js {:ok false :reason (str (or (.-message e) e))})))
