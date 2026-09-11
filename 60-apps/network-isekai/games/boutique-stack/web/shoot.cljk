#!/usr/bin/env nbb
;; Drive the real build in a real browser and prove a frame came out.
;;
;; This is the gap ADR-2608037000 lists as #6 — \"3D に視覚 CI が無い:
;; ヘッドレス WebGPU は SwiftShader 下で全3Dゲームが黒\". A screenshot on its
;; own does not close it: a black canvas screenshots just fine. So the harness
;; asserts on the pixels it captured, not on the fact that it captured them.
(ns shoot
  (:require ["playwright" :refer [chromium]]
            [clojure.string :as str]
            [promesa.core :as p]))

(def args (vec *command-line-args*))
(defn- arg [k d] (or (second (drop-while #(not= k %) args)) d))

(def chrome
  "The container ships Chromium at a fixed path and tells us not to download
  another (PLAYWRIGHT_BROWSERS_PATH). The npm playwright pin does not match
  that build number, so the executable is named rather than resolved."
  (arg "--chrome" "/opt/pw-browsers/chromium-1194/chrome-linux/chrome"))

(def url (arg "--url" "http://127.0.0.1:8177/"))
(def out (arg "--out" "shots/boutique-stack.png"))
(def ticks (js/parseInt (arg "--ticks" "600")))
(def width (js/parseInt (arg "--width" "440")))
(def height (js/parseInt (arg "--height" "956")))

(def gpu-flags
  ;; Headless Chromium has no real GPU here. Without these the page reports no
  ;; adapter and kami falls back; with them SwiftShader answers and we can see
  ;; which path actually ran. Either way the backend is read from the page.
  #js ["--enable-unsafe-swiftshader"
       "--enable-features=Vulkan,UseSkiaRenderer"
       "--use-angle=swiftshader"
       "--use-gl=angle"
       "--ignore-gpu-blocklist"])

(defn- ev
  "`page.evaluate` with a string evaluates it as an *expression*. Passing
  \"() => x\" therefore yields a function object, which is not serializable,
  so every reading came back `undefined` and every numeric check compared
  against NaN. Expressions only, and the checks below insist on numbers."
  [page expr]
  (.evaluate page expr))

(defn- canvas-stats
  "Sample the canvas back into an offscreen 2D context and describe it.
  A frame that drew nothing is uniform; a frame that drew the shop is not."
  [page]
  (.evaluate page "(() => {
     const c = document.getElementById('bs-canvas');
     const o = document.createElement('canvas');
     o.width = 160; o.height = 320;
     const g = o.getContext('2d');
     g.drawImage(c, 0, 0, o.width, o.height);
     const d = g.getImageData(0, 0, o.width, o.height).data;
     const seen = new Set(); let sum = 0, n = 0, dark = 0;
     for (let i = 0; i < d.length; i += 4) {
       const r = d[i], gg = d[i+1], b = d[i+2];
       seen.add((r >> 3) + ',' + (gg >> 3) + ',' + (b >> 3));
       const l = (r + gg + b) / 3; sum += l; n++; if (l < 8) dark++;
     }
     return { colours: seen.size, mean: sum / n, darkFraction: dark / n };
   })()"))

(p/let [browser (.launch chromium #js {:headless true :executablePath chrome :args gpu-flags})
        page (.newPage browser #js {:viewport #js {:width width :height height}
                                    :deviceScaleFactor 2})
        _ (.on page "console" (fn [m] (when (= "error" (.type m)) (println "  page error:" (.text m)))))
        _ (.on page "pageerror" (fn [e] (println "  pageerror:" (str e))))
        _ (.goto page url #js {:waitUntil "load"})
        ;; An expression, not "() => …": `waitForFunction` also evaluates a
        ;; string as an expression, so a function literal is truthy on the
        ;; first poll and the wait returns before the page has booted.
        _ (.waitForFunction page "window.boutiqueStack !== undefined" nil #js {:timeout 30000})
        backend (.evaluate page "window.boutiqueStack.backend")
        _ (println "backend      " backend)
        ;; Advance the simulation deterministically rather than waiting on
        ;; wall-clock frames: the shot has to be the same shop every run.
        tick (.evaluate page (str "window.boutiqueStack.run(" ticks ")"))
        _ (.waitForTimeout page 900)
        instances (.evaluate page "window.boutiqueStack.instances()")
        money (.evaluate page "window.boutiqueStack.money()")
        served (.evaluate page "window.boutiqueStack.served()")
        geom (ev page "JSON.stringify(window.boutiqueStack.canvas())")
        stats (canvas-stats page)
        _ (.screenshot page #js {:path out})
        _ (.close browser)]
  (println "tick         " tick)
  (println "instances    " instances)
  (println "money        " money)
  (println "served       " served)
  (println "canvas size  " geom)
  (println "canvas       " (str "colours=" (.-colours stats)
                                " mean=" (.toFixed (.-mean stats) 1)
                                " dark=" (.toFixed (* 100 (.-darkFraction stats)) 1) "%"))
  (println "wrote        " out)
  (let [num? (fn [v] (and (number? v) (not (js/isNaN v))))
        problems (cond-> []
                   ;; A field the page never set reads as `undefined`, and
                   ;; every numeric comparison against it is false — which is
                   ;; how the first version of this gate reported PASS on a
                   ;; page whose whole API had been renamed away.
                   (not (string? backend)) (conj "page exposed no backend")
                   (not (num? instances)) (conj "page exposed no instance count")
                   (not (num? tick)) (conj "page did not advance the simulation")
                   (< (.-colours stats) 24)
                   (conj (str "canvas has only " (.-colours stats)
                              " distinct colours — nothing was drawn"))
                   (> (.-darkFraction stats) 0.9)
                   (conj "canvas is >90% black — the 3D-goes-black failure")
                   (and (num? instances) (< instances 20))
                   (conj (str "only " instances " instances in the frame"))
                   (and (num? tick) (< tick ticks))
                   (conj (str "simulation only reached tick " tick)))]
    (if (seq problems)
      (do (println "\nFAIL") (doseq [p problems] (println " -" p)) (js/process.exit 1))
      (println "\nPASS  a non-trivial frame was rendered by" backend))))
