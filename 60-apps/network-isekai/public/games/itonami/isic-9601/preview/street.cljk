(ns street
  "The 3D street view in the page: draw the world map, and turn a tap into a district.

  This is what makes 3D the authoritative view rather than a claim about one. Until now the
  street existed in 3D only as a PNG the CLI produced; the page a player opened was a 2D DOM
  board of a single shop, and the README said the opposite.

  It holds no scene of its own — `world3d/render-ir` builds the canonical `kami.webgpu`
  render-IR, `gl` draws it, and `gl/pick-at` resolves taps through the engine's picker. The
  same IR feeds `bin/render.cljs` and the JVM suite."
  (:require [gl :as gl]
            [kami.webgpu.pick :as pick]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.world3d :as w3]
            [itonami.isic-9601.district :as district]))

(def handle (atom nil))
(def current-ir (atom nil))

(defn- size
  "Canvas pixel size, capped. A phone at dpr 3 asks for a nine-times-larger frame than the
  CSS box, which costs fill rate for detail nobody can see on flat-coloured boxes."
  [canvas]
  (let [r (.getBoundingClientRect canvas)
        dpr (min 2 (or js/window.devicePixelRatio 1))]
    [(js/Math.max 320 (js/Math.round (* dpr (.-width r))))
     (js/Math.max 240 (js/Math.round (* dpr (.-height r))))]))

(defn ready?
  "Has a context been created? Callers must handle `false` — WebGL 2.0 can be absent, and a
  street that silently becomes a list of buttons is a 3D view that is not 3D."
  []
  (some? @handle))

(defn init!
  "Create the context. Returns nil and leaves `ready?` false if WebGL 2.0 is unavailable;
  the reason is returned so the page can say it rather than show an empty box."
  [canvas glsl]
  (try
    (reset! handle (gl/create! canvas glsl))
    nil
    (catch :default e
      (reset! handle nil)
      (str e))))

(defn draw!
  "Draw the street for a world state. `cleared` decides which shops still wear a padlock."
  [canvas w]
  (when-let [h @handle]
    (let [[cw ch] (size canvas)
          ir (w3/render-ir w (/ (double cw) (double ch)))]
      (reset! current-ir ir)
      (assoc (gl/draw! h ir cw ch) :width cw :height ch))))

(defn tap->district
  "Which district was tapped, or nil.

  `:filter` matters as much as the ray does: the ground, the ring road and the trees are
  instances too, and the nearest hit along a ray through a shopfront is very often the road
  in front of it. Only instances that carry a `:district` are candidates."
  [canvas ev]
  (when-let [ir @current-ir]
    (let [r (.getBoundingClientRect canvas)
          [cw ch] (size canvas)
          px (* (/ (- (.-clientX ev) (.-left r)) (.-width r)) cw)
          py (* (/ (- (.-clientY ev) (.-top r)) (.-height r)) ch)
          hit (gl/pick-at ir px py cw ch {:filter (fn [i] (some? (:district i)))})]
      (:district (:instance hit)))))

(defn enterable?
  "A district can be entered when it is unlocked on the street and has a board behind it."
  [w id]
  (let [s (world/status w)
        d (first (filter (fn [d] (= (:id d) id)) (:districts s)))]
    (boolean (and d (:unlocked? d) (district/spec id)))))

(defn expose-probe!
  "Publish where each shop is on screen, for the browser smoke test to click.

  Only the *wiring* is under test here — canvas click → district → board. Whether the pick
  agrees with what was drawn is settled on the JVM by `pick-agrees-with-projection`, which
  projects every instance's own centre and demands that instance back. Duplicating that in a
  headless browser would add a slower copy of a stronger check."
  [canvas]
  (set! (.-__streetProbe js/window)
        (fn [id]
          (when-let [ir @current-ir]
            (let [r (.getBoundingClientRect canvas)
                  [cw ch] (size canvas)
                  inst (first (filter (fn [i] (= (:district i) id)) (:instances ir)))]
              (when inst
                (let [[x y z] (:pos inst)
                      [_ h _] (:size inst)
                      p (pick/project ir [x (+ y (* 0.5 h)) z] cw ch)]
                  (when p
                    ;; back to CSS pixels, which is what a click event carries
                    #js {:x (+ (.-left r) (* (/ (first p) cw) (.-width r)))
                         :y (+ (.-top r) (* (/ (second p) ch) (.-height r)))}))))))))
