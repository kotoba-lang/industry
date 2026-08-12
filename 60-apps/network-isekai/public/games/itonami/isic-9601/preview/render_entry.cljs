(ns render-entry
  "The WebGL 2.0 half of `bin/render.cljs`, as page code.

  `bin/render.cljs` runs on nbb, outside a browser, so it cannot call `gl` directly — it has
  to hand something to Chromium. It used to hand it a JavaScript string containing the whole
  draw sequence, which meant the picture the CLI verified and the picture the page rendered
  came from two hand-maintained transcriptions of one procedure. Both were right the day they
  were written; neither had any way of noticing the other drifting.

  Now the CLI bundles this module — the same `gl`, the same `world3d`, the same engine — and
  injects it. `window.__render(opts)` is the whole interface.

  This is why the CLI render is evidence about the page and not merely about itself."
  (:require [gl :as gl]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.world3d :as w3]))

(defn- background?
  "Is this pixel the cleared horizon rather than something drawn?"
  [r g b [sr sg sb]]
  (and (< (js/Math.abs (- r (js/Math.round (* 255 sr)))) 12)
       (< (js/Math.abs (- g (js/Math.round (* 255 sg)))) 12)
       (< (js/Math.abs (- b (js/Math.round (* 255 sb)))) 12)))

(defn render!
  "Draw one street frame off-screen and read it back.

  Returns the PNG plus the two numbers that decide whether anything was actually drawn.
  **Both are needed.** `non-background > 0` alone passes a blank canvas — it did, in this
  renderer's first fallback verifier, which reported `1440000 non-background · 1 distinct
  colour` and called that a success. One colour is a cleared canvas whatever its colour is."
  [opts]
  (let [w (.-width opts)
        h (.-height opts)
        cleared (.-cleared opts)
        ;; Resolved camera from the CLI (same IR `bin/render.cljs` packed). Absent on
        ;; older callers — fall back to the default fit so the page path stays usable.
        cam (cond-> {}
              (.-eye opts) (assoc :eye (js->clj (.-eye opts)))
              (.-target opts) (assoc :target (js->clj (.-target opts)))
              (number? (.-fov opts)) (assoc :fov (.-fov opts)))
        glsl {:vert (.-vert opts) :frag (.-frag opts)}
        canvas (js/document.createElement "canvas")]
    (set! (.-width canvas) w)
    (set! (.-height canvas) h)
    (try
      (let [handle (gl/create! canvas glsl)
            ir (w3/render-ir (assoc (world/init) :cleared cleared)
                             (/ (double w) (double h))
                             (when (seq cam) cam))
            drawn (gl/draw! handle ir w h)
            ctx (:ctx handle)
            px (js/Uint8Array. (* w h 4))
            _ (.readPixels ctx 0 0 w h (.-RGBA ctx) (.-UNSIGNED_BYTE ctx) px)
            sky (get-in ir [:globals :sky :horizon])
            colors (js/Set.)]
        (loop [i 0 non-sky 0]
          (if (>= i (.-length px))
            (let [dbg (.getExtension ctx "WEBGL_debug_renderer_info")]
              #js {:ok true
                   :glError (:error drawn)
                   :instances (:instances drawn)
                   :blocks 2
                   :nonSkyPixels non-sky
                   :distinctColors (.-size colors)
                   :renderer (if dbg
                               (.getParameter ctx (.-UNMASKED_RENDERER_WEBGL dbg))
                               "n/a")
                   :png (.toDataURL canvas "image/png")})
            (let [r (aget px i) g (aget px (+ i 1)) b (aget px (+ i 2))]
              (.add colors (str (bit-shift-right r 4) ","
                                (bit-shift-right g 4) ","
                                (bit-shift-right b 4)))
              (recur (+ i 4) (if (background? r g b sky) non-sky (inc non-sky)))))))
      (catch :default e
        #js {:ok false :reason (str e)}))))

(set! (.-__render js/window) render!)
