(ns render-board
  "Capture the shop board: inject an `:itonami-game/state` envelope into the
  page via `window.__setState`, force-repaint, screenshot.

  ADR-2608108000 / #1752. Board is DOM — this is the right answer; do not
  rebuild the board in 3D. Does not require the WebGL engine checkout.

  Invoked by `bin/render.cljs --view board`.

  Usage:
    nbb bin/render.cljs --view board --state FILE [--out FILE] [--width N] [--height N]
                        [--baseline FILE] [--diff-out FILE] [--threshold N]

  Acceptance: the injected cash / returned / phase must appear in the HUD.

  `--baseline FILE` (#1754) diffs the screenshot against FILE and prints EDN
  `{:changed-pixels :max-delta :regions}` plus a diff PNG — same contract as street."
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:child_process" :as cp]
            ["node:path" :as path]
            ["node:url" :as url]
            [clojure.string :as str]
            [promesa.core :as p]
            [itonami.isic-9601.state :as state]
            [itonami.isic-9601.logic :as l]
            [diff-core :as diff]))

(def argv (vec *command-line-args*))
(defn opt [k default]
  (let [i (.indexOf (into-array argv) (str "--" k))]
    (if (neg? i) default (nth argv (inc i) default))))
(defn num-opt [k d] (js/parseFloat (opt k (str d))))

(def here (path/resolve (path/dirname *file*) ".."))
(def W (int (num-opt "width" 900)))
(def H (int (num-opt "height" 1600)))
(def out (opt "out" (path/join here "preview/board.png")))
(def baseline-path (opt "baseline" nil))
(def diff-out-path (opt "diff-out" nil))
(def diff-threshold (int (num-opt "threshold" 0)))
(def state-path (opt "state" nil))
(def port 8732)

(defn- chrome-path []
  (or (.-PW_CHROMIUM js/process.env)
      (let [pw "/opt/pw-browsers/chromium-1194/chrome-linux/chrome"]
        (when (fs/existsSync pw) pw))
      "/usr/local/bin/google-chrome"))

(defn- load-envelope! []
  (when-not state-path
    (println "  --view board requires --state FILE")
    (js/process.exit 2))
  (try
    (let [env (state/parse (fs/readFileSync state-path "utf8"))]
      (when-not (:shop env)
        (println "  state file has no :shop — nothing to show on the board")
        (js/process.exit 2))
      env)
    (catch :default e
      (println (str "state " state-path ": " (or (.-message e) e)))
      (js/process.exit 2))))

(defn- envelope->js
  "clj->js drops the namespace of `:itonami-game/state` (becomes \"state\").
  The page checks the full discriminant, so restore it after conversion."
  [env]
  (let [o (clj->js env)]
    (aset o "kind" "itonami-game/state")
    o))

(defn- build-board-page!
  "Ensure preview/board.html exists (no engine required)."
  []
  (println "  building preview/board.html (no WebGL engine)")
  (cp/execSync "npx nbb preview/build_board.cljs"
               #js {:cwd here :stdio "inherit"}))

(defn- content-type [p]
  (cond
    (str/ends-with? p ".html") "text/html; charset=utf-8"
    (str/ends-with? p ".css")  "text/css; charset=utf-8"
    (str/ends-with? p ".js")   "text/javascript; charset=utf-8"
    (str/ends-with? p ".png")  "image/png"
    :else "application/octet-stream"))

(defn- serve-board!
  "Serve the board page from localhost. file:// is fine for DOM, but matching
  the street renderer's http habit keeps CSP/secure-context behaviour consistent
  if the page later grows a worker."
  [page-path]
  (let [root (path/dirname page-path)
        srv (.createServer http
              (fn [req res]
                (let [u (url/parse (.-url req))
                      rel (if (or (nil? (.-pathname u)) (= (.-pathname u) "/"))
                            (path/basename page-path)
                            (subs (.-pathname u) 1))
                      fp (path/resolve root rel)]
                  (if (and (str/starts-with? fp root) (fs/existsSync fp))
                    (do (.writeHead res 200 #js {"Content-Type" (content-type fp)})
                        (.end res (fs/readFileSync fp)))
                    (do (.writeHead res 404)
                        (.end res "missing"))))))]
    (.listen srv port "127.0.0.1")
    srv))

(defn -main []
  (let [env (load-envelope!)
        sm (l/summary (:shop env))
        expect-cash (:cash sm)
        expect-returned (:returned sm)
        expect-phase (:phase sm)]
    (println (str "  view    board"))
    (println (str "  state   " state-path))
    (println (str "  expect  ¥" expect-cash
                  "  returned " expect-returned
                  "  phase " expect-phase
                  "  district " (:district env)))
    (build-board-page!)
    (let [page-path (path/join here "preview/board.html")]
      (when-not (fs/existsSync page-path)
        (println "  FAILED  preview/board.html missing after build")
        (js/process.exit 3))
      (p/let [srv (serve-board! page-path)
              pw (js/import "playwright")
              browser (.launch (.-chromium pw)
                               #js {:args #js ["--no-sandbox"
                                               "--use-gl=swiftshader"
                                               "--enable-unsafe-swiftshader"]
                                    :executablePath (chrome-path)})
              page (.newPage browser)
              _ (.setViewportSize page #js {:width W :height H})
              _ (.goto page (str "http://127.0.0.1:" port "/")
                       #js {:waitUntil "domcontentloaded"})
              _ (.waitForFunction page "typeof window.__setState === 'function'")
              ;; Force a paint that the throttle would skip if we only called
              ;; render! without force — the gotcha called out in #1752.
              ;; Use js/Function: a cljs fn's toString is not valid page JS.
              injected (.evaluate page
                                  (js/Function. "env" "return window.__setState(env);")
                                  (envelope->js env))
              inj (js->clj injected :keywordize-keys true)
              _ (when-not (:ok inj)
                  (println (str "  FAILED  __setState: " (:reason inj)))
                  (js/process.exit 4))
              _ (.waitForSelector page ".hud" #js {:timeout 5000})
              hud-text (.textContent (.locator page ".hud"))
              abs (path/resolve here out)
              _ (fs/mkdirSync (path/dirname abs) #js {:recursive true})
              _ (.screenshot page #js {:path abs :fullPage true})
              _ (.close browser)
              _ (.close srv)]
        (println (str "  inject  ok  view=" (:view inj)
                      "  cash=" (:cash inj)
                      "  returned=" (:returned inj)
                      "  phase=" (:phase inj)))
        (println (str "  hud     " (str/replace (str hud-text) #"\s+" " ")))
        (let [cash-ok (str/includes? (str hud-text) (str "¥" expect-cash))
              ret-ok (str/includes? (str hud-text) (str expect-returned "/"))
              phase-ok (str/includes? (str hud-text) (str "P" expect-phase))]
          (when-not cash-ok
            (println (str "  FAILED  HUD missing ¥" expect-cash
                          " — injected state did not paint (force-repaint?)"))
            (js/process.exit 5))
          (when-not ret-ok
            (println (str "  FAILED  HUD missing returned " expect-returned))
            (js/process.exit 5))
          (when-not phase-ok
            (println (str "  FAILED  HUD missing P" expect-phase))
            (js/process.exit 5))
          (println (str "  wrote   " abs))
          (println "  note    board capture is DOM screenshot; no WebGL/engine required")
          (println "  ok      injected state visible in HUD")
          (when baseline-path
            (when-not (fs/existsSync baseline-path)
              (println (str "  FAILED  --baseline missing: " baseline-path))
              (js/process.exit 2))
            (try
              (let [stats (diff/compare-files baseline-path abs diff-threshold true)
                    dpath (path/resolve here
                                        (or diff-out-path
                                            (str abs ".diff.png")))
                    edn (diff/summarize stats)]
                (diff/write-png! dpath (:width stats) (:height stats) (:diff-data stats))
                (println (str "  baseline " baseline-path
                              "  threshold=" diff-threshold))
                (println (str "  diff     " dpath))
                (println (pr-str edn)))
              (catch :default e
                (println (str "  FAILED  baseline diff: " (or (.-message e) e)))
                (js/process.exit 6)))))))))

(-main)
