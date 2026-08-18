(ns smoke
  "Real-browser smoke test for `preview/index.html`.

  Run from the repo root, after `preview/build.cljs`:
    PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers \\
      npx nbb --classpath 60-apps/network-isekai/public/games/itonami/isic-9601/node_modules \\
      60-apps/network-isekai/public/games/itonami/isic-9601/preview/smoke.cljs

  It opens the built page in headless Chromium, lets the shop run, drives the
  same approve/reject/upgrade buttons a player would, and asserts the page
  actually moved -- a build that renders a dead board passes no check here.
  Exits non-zero on failure so it can be a gate."
  (:require ["node:path" :as path]
            ["playwright" :refer [chromium]]
            [promesa.core :as p]))

(def here (path/resolve (path/dirname *file*) ".."))

(def page-url
  ;; resolved from this script, not from the working directory — see preview/build.cljs
  (str "file://" (path/join here "preview/index.html")))

(def failures (atom 0))

(defn is! [ok? label]
  (println (str "  " (if ok? "ok  " "FAIL") "  " label))
  (when-not ok? (swap! failures inc)))

(def play-script
  "A player's loop, run inside the page: take customers in, reject a plan that
  conflicts with the care label, approve everything else, climb the tier."
  "(async () => {
     const click = s => { const b = document.querySelector(s); if (b && !b.disabled) b.click(); };
     click(\"[data-act='speed'][data-arg='20']\");
     for (let i = 0; i < 1200; i++) {
       click(\"[data-act='take-in']\");
       if (document.querySelector('.g--risk')) click(\"[data-act='reject']\");
       else click(\"[data-act='tap'][data-arg='verify']\");
       click(\"[data-act='tap'][data-arg='screen']\");
       click(\"[data-act='tap'][data-arg='clean']\");
       click(\"[data-act='tap'][data-arg='return']\");
       click(\"[data-act='phase']\");
       await new Promise(r => setTimeout(r, 22));
     }
     return document.querySelector('.hud').textContent;
   })()")

(def canvas-probe
  "Read the drawn canvas back and count distinct colours.

  `> 0 non-background pixels` is not a check — a blank canvas passes it, and did, in the CLI
  renderer's first fallback verifier: it reported `1440000 non-background · 1 distinct colour`
  and called it a success. A drawn street has many colours; a cleared one has exactly one."
  "(() => {
     const cv = document.getElementById('street-canvas');
     const gl = cv.getContext('webgl2');
     if (!gl) return {ok:false, reason:'no webgl2'};
     const px = new Uint8Array(cv.width * cv.height * 4);
     gl.readPixels(0, 0, cv.width, cv.height, gl.RGBA, gl.UNSIGNED_BYTE, px);
     const colors = new Set();
     for (let i = 0; i < px.length; i += 4)
       colors.add((px[i]>>4)+','+(px[i+1]>>4)+','+(px[i+2]>>4));
     return {ok:true, w:cv.width, h:cv.height, colors:colors.size, glError:gl.getError()};
   })()")

(defn run [page]
  (p/let [_ (.goto page page-url)

          ;; --- the street, in 3D ------------------------------------------------------
          _ (.waitForSelector page "#street-canvas" #js {:timeout 8000})
          _ (.waitForTimeout page 800)
          frame (.evaluate page canvas-probe)
          _ (is! (.-ok frame) (str "WebGL 2.0 context on the street canvas"))
          _ (is! (zero? (.-glError frame)) (str "no GL error (got " (.-glError frame) ")"))
          _ (is! (> (.-colors frame) 1)
                 (str "the street actually drew: " (.-colors frame) " distinct colours at "
                      (.-w frame) "×" (.-h frame)))

          rows (.count (.locator page ".dist"))
          _ (is! (= rows 8) (str "eight districts on the street (got " rows ")"))
          locked (.count (.locator page ".dist--locked"))
          _ (is! (pos? locked) (str "some are still locked (got " locked ")"))

          ;; --- tapping the 3D shopfront opens that shop's board ------------------------
          at (.evaluate page "window.__streetProbe('isic-9601')")
          _ (is! (some? at) "the laundry's shopfront has a screen position")
          _ (.click (.-mouse page) (.-x at) (.-y at))
          _ (.waitForSelector page ".hud" #js {:timeout 8000})
          which (.textContent (.locator page ".shop__isic"))
          _ (is! (= (str which) "isic-9601")
                 (str "tapping the shopfront entered that district (got " (str which) ")"))

          cards (.count (.locator page ".grid .card"))
          _ (is! (= cards 5) (str "five station cards rendered (got " cards ")"))

          humans (.count (.locator page ".badge--human"))
          _ (is! (= humans 2) (str "two stations marked 人手必須 (got " humans ")"))

          ;; the clock runs on its own -- customers arrive with no input
          _ (.click (.locator page "[data-act='speed'][data-arg='20']"))
          _ (.waitForTimeout page 1500)
          qtext (.textContent (.locator page ".q"))
          _ (is! (re-find #"待ち" (str qtext)) (str "queue is live: " (str qtext)))

          ;; a button press reaches the reducer and the ledger records it
          _ (.click (.locator page "[data-act='take-in']"))
          _ (.waitForTimeout page 600)
          entries (.count (.locator page ".log__i"))
          _ (is! (pos? entries) (str "audit ledger has entries (got " entries ")"))

          hud (.evaluate page play-script)
          _ (is! (re-find #"P[0-3]" (str hud)) "HUD reports a rollout phase after play")
          cash (second (re-find #"¥(\d+)" (str hud)))
          _ (is! (and cash (pos? (js/parseInt cash 10)))
                 (str "the shop earned cash (¥" cash ")"))

          returned (second (re-find #"(\d+)/40" (str hud)))
          _ (is! (and returned (pos? (js/parseInt returned 10)))
                 (str "garments were returned (" returned "/40)"))

          _ (.screenshot page #js {:path (path/join here "preview/screenshot.png")
                                   :fullPage true})

          ;; --- and back out to the street ---------------------------------------------
          _ (.click (.locator page "[data-act='to-street']"))
          _ (.waitForSelector page ".dist" #js {:timeout 8000})
          back (.evaluate page canvas-probe)
          _ (is! (> (.-colors back) 1)
                 (str "the street redraws after leaving a shop (" (.-colors back) " colours)"))
          _ (.screenshot page #js {:path (path/join here "preview/street-view.png")})
          _ (println "  screenshots written")]
    true))

(defn -main []
  (p/let [browser (.launch chromium #js {:args #js ["--no-sandbox"] :executablePath (or (.-PW_CHROMIUM js/process.env) "/opt/pw-browsers/chromium-1194/chrome-linux/chrome")})
          page (.newPage browser)
          _ (p/catch (run page)
                     (fn [e]
                       (println "ERROR" (str e))
                       (swap! failures inc)))
          _ (.close browser)]
    (println)
    (if (pos? @failures)
      (do (println (str "FAILURES: " @failures)) (js/process.exit 1))
      (println "SMOKE OK"))))

(-main)
