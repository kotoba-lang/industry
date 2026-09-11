(ns accept-annotate
  "CLI acceptance for #1753 — `--annotate` / `--pick` on the street render path.

  Run from the package root (needs webgpu + render on the classpath, same as `npm run render`):
    npm run accept:annotate

  Covers ADR-2608108000 acceptance:

  1. Every annotate label sits inside the projected bounds of the instance it names,
     for the default camera and at least two `--orbit` values. Coordinates are
     `kami.webgpu.pick/project` — not re-derived.
  2. `--pick X,Y` returns EDN `{:index :district :kind :point :t}`; picking at a
     label's own coordinates returns the instance that label names.
  3. `--pick` on empty sky returns nil (no nearest-building fallback).
  4. `--pick` on the road returns the road — shop-only tap filter stays off.

  Does not require Chromium for the gate (library + `--pick` / `--dry` CLI). Pixel burn
  of `--annotate` is the render CLI's job; coords are what the accept proves."
  (:require ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.world3d :as w3]
            [itonami.isic-9601.inspect :as inspect]
            [kami.webgpu.pick :as pick]))

(def here (path/resolve (path/dirname *file*) ".."))
(def engine (path/resolve here "../../../../../../orgs/kotoba-lang"))
(def cp-str
  (str "src:"
       (path/join engine "webgpu/src") ":"
       (path/join engine "render/src")))
(def failures (atom 0))
(def W 900)
(def H 1600)
(def aspect (/ (double W) (double H)))
(def fresh (world/init))

(defn- fail! [label detail]
  (swap! failures inc)
  (println "  FAIL:" label)
  (when detail (println "       " detail)))

(defn- ok! [label] (println "  ok  " label))

(defn- spawn-render [& args]
  (cp/spawnSync "npx" (clj->js (into ["nbb" "--classpath" cp-str "bin/render.cljs"] args))
                #js {:cwd here :encoding "utf8"}))

(defn- last-edn
  "Last non-empty stdout line of a `--pick` run — the EDN hit, or the symbol nil."
  [stdout]
  (let [line (->> (str/split-lines stdout)
                  (map str/trim)
                  (remove str/blank?)
                  last)]
    (when line (edn/read-string line))))

(println "== library: annotate labels inside projected bounds (default + two orbits)")
(doseq [[tag opts] [["default" nil]
                    ["orbit 40" {:orbit 40.0}]
                    ["orbit 180" {:orbit 180.0}]]]
  (let [f (w3/render-ir fresh aspect opts)
        r (inspect/labels-inside-bounds? f W H)]
    (if (:ok r)
      (ok! (str tag " — " (:count r) " labels inside bounds"))
      (fail! (str tag " bounds") (pr-str (take 3 (:bad r)))))))

(println "== library: pick at label coords returns that instance (project/pick loop)")
(doseq [[tag opts] [["default" nil]
                    ["orbit 40" {:orbit 40.0}]
                    ["orbit 180" {:orbit 180.0}]]]
  (let [f (w3/render-ir fresh aspect opts)
        r (inspect/pick-loop-closed? f W H)]
    (if (:ok r)
      (ok! (str tag " — loop closed for " (:count r) " labels"))
      (fail! (str tag " loop") (pr-str (take 3 (:bad r)))))))

(println "== library: empty sky → nil (not nearest building)")
(let [;; Default framing's ground slab covers the view; aim so a top-of-frame ray misses.
      f (w3/render-ir fresh aspect {:eye [0.0 5.0 200.0] :target [0.0 5.0 0.0]})
      hit (inspect/pick-at f [450.0 10.0] W H)
      shop? (and hit (w3/shop-instance? {:kind (:kind hit)}))]
  (if (nil? hit)
    (ok! "sky miss returns nil")
    (fail! "sky" (str "got " (pr-str hit) (when shop? " (a building — forbidden)")))))

(println "== library: road pick returns the road (no shop-only filter)")
(let [f (w3/render-ir fresh aspect)
      sample (inspect/road-sample f W H)
      p (:pixel sample)
      hit (:hit sample)
      filtered (when p (pick/pick f p W H {:filter w3/shop-instance?}))]
  (cond (nil? sample)
        (fail! "road-sample" "no road pixel under the default camera")
        (not (#{:road :road-line} (:kind hit)))
        (fail! "road-pick" (str "unfiltered got " (pr-str hit)))
        (some? filtered)
        (fail! "road-filter-contrast"
               "shop filter also hit — sample is not a pure road pixel for the contrast check")
        :else
        (ok! (str "road → " (:kind hit) " (shop filter would be " (pr-str filtered) ")"))))

(println "== library: label text is index:district from project coords")
(let [f (w3/render-ir fresh aspect)
      labs (inspect/annotate-labels f W H)
      lab (first (filter (fn [l] (= (:district l) "isic-9601")) labs))
      inst (when lab (nth (:instances f) (:index lab)))
      p (when inst (pick/project f (:center (pick/instance-box inst)) W H))]
  (if (and lab
           (= (:text lab) (str (:index lab) ":isic-9601"))
           p
           (= [(:x lab) (:y lab)] p))
    (ok! (str "sample label " (:text lab) " @ " (pr-str p)))
    (fail! "label-shape" (pr-str {:lab lab :p p}))))

(println "== CLI: --pick prints EDN and closes the loop at a label")
(let [f (w3/render-ir fresh aspect)
      lab (first (inspect/annotate-labels f W H))
      xy (str (:x lab) "," (:y lab))
      r (spawn-render "--pick" xy)
      out (str (.-stdout r) (.-stderr r))
      hit (last-edn (.-stdout r))]
  (if (and (zero? (.-status r))
           (map? hit)
           (= (:index lab) (:index hit))
           (= (:district lab) (:district hit))
           (contains? hit :kind)
           (contains? hit :point)
           (contains? hit :t))
    (ok! (str "--pick at label → index " (:index hit) " " (:district hit)))
    (fail! "cli-pick-label" (str "status=" (.-status r) " hit=" (pr-str hit) " out=" out))))

(println "== CLI: --pick on sky prints nil")
(let [r (spawn-render "--pick" "450,10"
                      "--eye" "0,5,200" "--target" "0,5,0")
      hit (last-edn (.-stdout r))]
  (if (and (zero? (.-status r)) (nil? hit))
    (ok! "--pick sky → nil")
    (fail! "cli-pick-sky" (str "status=" (.-status r) " hit=" (pr-str hit)))))

(println "== CLI: --pick on road returns road kind (not a shop)")
(let [f (w3/render-ir fresh aspect)
      sample (inspect/road-sample f W H)
      p (:pixel sample)
      r (spawn-render "--pick" (str (first p) "," (second p)))
      hit (last-edn (.-stdout r))]
  (if (and (zero? (.-status r))
           (map? hit)
           (#{:road :road-line} (:kind hit))
           (nil? (:district hit)))
    (ok! (str "--pick road → " (:kind hit)))
    (fail! "cli-pick-road" (str "status=" (.-status r) " hit=" (pr-str hit)))))

(println "== CLI: --annotate --dry lists labels; bad --pick exits 2")
(let [r (spawn-render "--annotate" "--dry" "--orbit" "40")
      out (str (.-stdout r) (.-stderr r))]
  (if (and (zero? (.-status r))
           (re-find #"labels \d+" out)
           (re-find #"eye \[" out))
    (ok! "--annotate --dry prints label count + eye")
    (fail! "annotate-dry" (str "status=" (.-status r) " out=" out))))

(let [r (spawn-render "--pick" "1,2,3")
      out (str (.-stdout r) (.-stderr r))]
  (if (and (= 2 (.-status r)) (re-find #"X,Y" out))
    (ok! "bad --pick exits 2")
    (fail! "bad-pick" (str "status=" (.-status r) " out=" out))))

(println "== CLI: camera refusal still intact with annotate flags present")
(let [r (spawn-render "--annotate" "--dry" "--eye" "0,-1,50")
      out (str (.-stdout r) (.-stderr r))]
  (if (and (= 2 (.-status r)) (re-find #"underground" out))
    (ok! "underground --eye still exits 2 under --annotate")
    (fail! "annotate-underground" (str "status=" (.-status r) " out=" out))))

(println)
(if (zero? @failures)
  (do (println "ACCEPT annotate OK") (js/process.exit 0))
  (do (println (str "ACCEPT annotate FAILED (" @failures ")")) (js/process.exit 1)))
