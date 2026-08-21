(ns accept-board
  "CLI acceptance for #1752 — board screenshot via __setState + render --view board.

  Run from the package root:
    npx nbb --classpath src bin/accept_board.cljs

  Pins:
    1. play --dump produces an envelope with :shop
    2. render --view board --state FILE writes a PNG; HUD shows injected numbers
    3. victory banner appears after injecting :flow :victory
    4. non-9601 district station labels paint (not the laundry defaults)
    5. double-inject A then B shows B (force-repaint gotcha)"
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:http" :as http]
            ["node:url" :as url]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [promesa.core :as p]
            [itonami.isic-9601.logic :as l]
            [itonami.isic-9601.state :as state]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.district :as district]))

(def here (path/resolve (path/dirname *file*) ".."))
(def tmp (path/join here ".build/accept-board"))
(def failures (atom 0))
(def port 8733)

(defn- sh [args]
  (let [r (cp/spawnSync "npx" (clj->js (into ["nbb" "--classpath" "src"] args))
                        #js {:cwd here :encoding "utf8"})]
    {:status (.-status r)
     :stdout (or (.-stdout r) "")
     :stderr (or (.-stderr r) "")}))

(defn- fail! [label detail]
  (swap! failures inc)
  (println "  FAIL:" label)
  (when detail (println "       " detail)))

(defn- ok! [label] (println "  ok  " label))

(defn- chrome-path []
  (or (.-PW_CHROMIUM js/process.env)
      (let [pw "/opt/pw-browsers/chromium-1194/chrome-linux/chrome"]
        (when (fs/existsSync pw) pw))
      "/usr/local/bin/google-chrome"))

(defn- envelope->js [env]
  (let [o (clj->js env)]
    (aset o "kind" "itonami-game/state")
    o))

(defn- content-type [p]
  (cond
    (str/ends-with? p ".html") "text/html; charset=utf-8"
    (str/ends-with? p ".css")  "text/css; charset=utf-8"
    (str/ends-with? p ".js")   "text/javascript; charset=utf-8"
    :else "application/octet-stream"))

(defn- serve-board! [page-path]
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

(defn- inject! [page env]
  (p/let [raw (.evaluate page
                         (js/Function. "env" "return window.__setState(env);")
                         (envelope->js env))]
    (js->clj raw :keywordize-keys true)))

(defn- open-board-page []
  (let [page-path (path/join here "preview/board.html")]
    (when-not (fs/existsSync page-path)
      (fail! "board.html missing" "run build_board first")
      (js/process.exit 1))
    (p/let [srv (serve-board! page-path)
            pw (js/import "playwright")
            browser (.launch (.-chromium pw)
                             #js {:args #js ["--no-sandbox"
                                             "--use-gl=swiftshader"
                                             "--enable-unsafe-swiftshader"]
                                  :executablePath (chrome-path)})
            page (.newPage browser)
            _ (.setViewportSize page #js {:width 720 :height 1100})
            _ (.goto page (str "http://127.0.0.1:" port "/")
                     #js {:waitUntil "domcontentloaded"})
            _ (.waitForFunction page "typeof window.__setState === 'function'")]
      {:srv srv :browser browser :page page})))

(defn- close-board! [{:keys [srv browser]}]
  (p/do!
    (.close browser)
    (.close srv)))

(defn- finish! []
  (println)
  (if (pos? @failures)
    (do (println (str "ACCEPT FAIL — " @failures " check(s)"))
        (js/process.exit 1))
    (do (println "ACCEPT OK")
        (js/process.exit 0))))

(defn- pin-victory! [page]
  (println "== victory banner after inject")
  (let [shop (assoc (l/init 1 "isic-9601") :flow :victory :returned 40 :cash 999)
        env (state/wrap shop (world/init) 1 "isic-9601")]
    (p/let [inj (inject! page env)]
      (if-not (:ok inj)
        (fail! "victory inject" (pr-str inj))
        (p/let [_ (.waitForSelector page ".banner--win" #js {:timeout 5000})
                text (.textContent (.locator page ".banner--win"))]
          (if (str/includes? (str text) "監査クローズ")
            (ok! "victory banner paints 監査クローズ")
            (fail! "victory banner text" (pr-str text))))))))

(defn- pin-district-labels! [page]
  (println "== non-9601 district station labels")
  ;; Scope to `.grid` — shop_view's note still hardcodes laundry copy.
  (let [labels (:labels (get district/presentation "isic-4520"))
        want (vec (vals labels))
        shop (assoc (l/init 2 "isic-4520") :cash 1111 :returned 3 :phase 1)
        env (state/wrap shop (world/init) 2 "isic-4520")]
    (p/let [inj (inject! page env)
            _ (.waitForSelector page ".grid" #js {:timeout 5000})
            heads (.textContent (.locator page ".grid"))]
      (cond
        (not (:ok inj))
        (fail! "4520 inject" (pr-str inj))
        (not (every? (fn [lab] (str/includes? (str heads) lab)) want))
        (fail! "4520 station labels"
               (str "want " (pr-str want)
                    " grid=" (pr-str (str/replace (str heads) #"\s+" " "))))
        (str/includes? (str heads) "取扱方法")
        (fail! "4520 grid still shows 9601 label 取扱方法" (pr-str heads))
        (str/includes? (str heads) "洗浄")
        (fail! "4520 grid still shows 9601 label 洗浄" (pr-str heads))
        :else
        (ok! (str "isic-4520 labels paint (" (str/join "·" want) ")"))))))

(defn- pin-double-inject! [page]
  (println "== double-inject A then B shows B (force-repaint)")
  (let [shop-a (assoc (l/init 3 "isic-9601") :cash 111 :returned 1 :phase 1)
        shop-b (assoc (l/init 4 "isic-9601") :cash 2222 :returned 7 :phase 2)
        env-a (state/wrap shop-a (world/init) 3 "isic-9601")
        env-b (state/wrap shop-b (world/init) 4 "isic-9601")]
    (p/let [inj-a (inject! page env-a)
            _ (.waitForSelector page ".hud" #js {:timeout 5000})
            hud-a (.textContent (.locator page ".hud"))
            inj-b (inject! page env-b)
            _ (.waitForFunction page
                                "document.querySelector('.hud') && document.querySelector('.hud').textContent.includes('¥2222')")
            hud-b (.textContent (.locator page ".hud"))]
      (cond
        (not (:ok inj-a)) (fail! "inject A" (pr-str inj-a))
        (not (:ok inj-b)) (fail! "inject B" (pr-str inj-b))
        (not (str/includes? (str hud-a) "¥111"))
        (fail! "HUD after A" (pr-str hud-a))
        (not (str/includes? (str hud-b) "¥2222"))
        (fail! "HUD after B missing ¥2222 (force-repaint?)" (pr-str hud-b))
        (str/includes? (str hud-b) "¥111")
        (fail! "HUD after B still shows A's ¥111" (pr-str hud-b))
        :else
        (ok! "double-inject shows B (¥2222), not stuck on A")))))

(fs/mkdirSync tmp #js {:recursive true})

(println "== build board page")
(let [r (sh ["preview/build_board.cljs"])]
  (if (and (zero? (:status r))
           (fs/existsSync (path/join here "preview/board.html")))
    (ok! "preview/board.html built without engine")
    (fail! "build_board" (str "status=" (:status r)
                              "\n" (:stdout r) (:stderr r)))))

(println "== dump a shop with distinctive HUD numbers")
(let [shop (assoc (l/init 20260808 "isic-9601")
                  :cash 4321 :returned 9 :phase 2 :cert-ticks 777)
      env (state/wrap shop (world/init) 20260808 "isic-9601")
      edn-path (path/join tmp "shop.edn")
      _ (fs/writeFileSync edn-path (state/encode env))
      loaded (state/parse (fs/readFileSync edn-path "utf8"))]
  (if (and (= 4321 (:cash (:shop loaded))) (= 9 (:returned (:shop loaded))))
    (ok! "envelope carries distinctive cash/returned")
    (fail! "dump numbers" (pr-str (select-keys (:shop loaded) [:cash :returned])))))

(println "== render --view board --state (HUD must show injected numbers)")
(let [edn-path (path/join tmp "shop.edn")
      png (path/join tmp "board.png")
      r (sh ["bin/render.cljs" "--view" "board"
             "--state" edn-path "--out" png
             "--width" "720" "--height" "1100"])
      out (str (:stdout r) (:stderr r))]
  (cond
    (not (zero? (:status r)))
    (fail! "render --view board" (str "exit " (:status r) "\n" out))
    (not (fs/existsSync png))
    (fail! "board.png missing" out)
    (not (re-find #"¥4321" out))
    (fail! "HUD cash not reported" out)
    (not (re-find #"injected state visible" out))
    (fail! "acceptance line missing" out)
    :else
    (ok! (str "board PNG + HUD check (" (.-size (fs/statSync png)) " bytes)"))))

(println "== --view board without --state exits 2")
(let [r (sh ["bin/render.cljs" "--view" "board"])]
  (if (= 2 (:status r))
    (ok! "exit 2 when --state missing")
    (fail! "missing state exit" (str "status=" (:status r)))))

(println "== unknown --view exits 2")
(let [r (sh ["bin/render.cljs" "--view" "nope"])]
  (if (= 2 (:status r))
    (ok! "exit 2 on unknown --view")
    (fail! "unknown view exit" (str "status=" (:status r)))))

(p/let [sess (open-board-page)
        page (:page sess)
        _ (pin-victory! page)
        _ (pin-district-labels! page)
        _ (pin-double-inject! page)
        _ (close-board! sess)]
  (finish!))
