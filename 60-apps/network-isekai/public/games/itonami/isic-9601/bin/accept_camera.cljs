(ns accept-camera
  "CLI acceptance for #1751 — camera flags reshape the render-IR.

  Run from the package root (needs webgpu + render on the classpath, same as `npm run render`):
    npm run accept:camera

  Checks the library (orbit/zoom/fov/eye land on `:globals`), that underground / inside-fit
  eyes are refused, and that the street path (`bin/render.cljs` → `bin/render_street.cljs`)
  dry-parses good flags and rejects bad ones. Does not require Chromium — pixel draw is
  still the `render` CLI's job; this gate is about aiming."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.world3d :as w3]
            [kami.webgpu.pick :as pick]))

(def here (path/resolve (path/dirname *file*) ".."))
(def engine (path/resolve here "../../../../../../orgs/kotoba-lang"))
(def cp-str
  (str "src:"
       (path/join engine "webgpu/src") ":"
       (path/join engine "render/src")))
(def failures (atom 0))

(defn- fail! [label detail]
  (swap! failures inc)
  (println "  FAIL:" label)
  (when detail (println "       " detail)))

(defn- ok! [label] (println "  ok  " label))

(defn- approx=
  ([a b] (approx= a b 1e-6))
  ([a b eps]
   (and (= (count a) (count b))
        (every? (fn [[x y]] (< (js/Math.abs (- x y)) eps))
                (map vector a b)))))

(defn- spawn-render [& args]
  ;; Thin dispatcher forwards argv to render_street (camera / --dry live there).
  (cp/spawnSync "npx" (clj->js (into ["nbb" "--classpath" cp-str "bin/render.cljs"] args))
                #js {:cwd here :encoding "utf8"}))

(def aspect (/ 900.0 1600.0))
(def fresh (world/init))

(println "== library: default frame unchanged by nil opts")
(let [a (w3/render-ir fresh aspect)
      b (w3/render-ir fresh aspect nil)]
  (if (= a b) (ok! "nil opts == default") (fail! "nil opts" "frames differ")))

(println "== library: --orbit / --zoom / --fov / --eye land on IR globals")
(let [base (w3/camera aspect)
      spun (w3/camera aspect {:orbit 180.0})
      zoomed (w3/camera aspect {:zoom 2.0})
      narrow (w3/render-ir fresh aspect {:fov 28.0})
      ;; outside fit volume (r ≈ 143 > fit-radius ≈ 67)
      aimed (w3/render-ir fresh aspect {:eye [0.0 80.0 120.0] :target [0.0 2.0 0.0]})
      range (fn [c]
              (let [[x y z] (:eye c)]
                (js/Math.sqrt (+ (* x x) (* y y) (* z z)))))]
  ;; default azimuth is π/2 (eye on +z); +180° → -z, so compare z not x
  (if (> (js/Math.abs (- (nth (:eye spun) 2) (nth (:eye base) 2))) 1.0)
    (ok! "orbit 180 moves the eye")
    (fail! "orbit" (str "base=" (pr-str (:eye base)) " spun=" (pr-str (:eye spun)))))
  (if (< (range zoomed) (* 0.6 (range base)))
    (ok! "zoom 2 pulls in")
    (fail! "zoom" (str "base-range=" (range base) " zoomed=" (range zoomed))))
  (if (= 28.0 (get-in narrow [:globals :fov]))
    (ok! "fov stated on frame")
    (fail! "fov" (str (get-in narrow [:globals :fov]))))
  (if (approx= [0.0 80.0 120.0] (get-in aimed [:globals :eye]))
    (ok! "absolute eye wins")
    (fail! "eye" (pr-str (get-in aimed [:globals :eye])))))

(println "== library: underground / inside-fit refused (no silent clamp)")
(let [under (try (w3/camera aspect {:eye [0.0 -1.0 50.0]})
                 (catch :default e e))
      inside (try (w3/camera aspect {:zoom 100.0})
                  (catch :default e e))
      tiny (try (w3/camera aspect {:zoom 0.0})
                (catch :default e e))]
  (if (and (re-find #"underground" (ex-message under))
           (= :underground (:reason (ex-data under))))
    (ok! "underground eye throws")
    (fail! "underground" (str (ex-message under) " data=" (pr-str (ex-data under)))))
  (if (and (re-find #"fit volume" (ex-message inside))
           (= :inside-fit (:reason (ex-data inside))))
    (ok! "inside-fit / extreme zoom throws")
    (fail! "inside-fit" (str (ex-message inside) " data=" (pr-str (ex-data inside)))))
  (if (and (re-find #"zoom" (ex-message tiny))
           (= :bad-camera-number (:reason (ex-data tiny))))
    (ok! "zoom 0 refused (not clamped to 1e-6)")
    (fail! "zoom-zero" (str (ex-message tiny) " data=" (pr-str (ex-data tiny))))))

(println "== library: pick/project agree under override (ADR acceptance)")
(let [f (w3/render-ir fresh aspect {:orbit 40.0 :zoom 1.15 :fov 44.0})
      W 900 H 1600
      misses (atom [])]
  (doseq [d world/districts]
    (let [b (first (filter (fn [i] (and (= (:kind i) :shop) (= (:district i) (:id d))))
                           (:instances f)))
          c (:center (pick/instance-box b))
          p (pick/project f c W H)
          hit (when p (pick/pick f p W H {:filter w3/shop-instance?}))]
      (when-not (= (:id d) (get-in hit [:instance :district]))
        (swap! misses conj {:id (:id d) :p p :got (get-in hit [:instance :district])}))))
  (if (empty? @misses)
    (ok! "every shop projects and picks back to itself")
    (fail! "pick/project" (pr-str (take 3 @misses)))))

(println "== CLI: dry parse succeeds for good --orbit / --eye")
(let [r (spawn-render "--dry" "--orbit" "180" "--out" "/tmp/itonami-cam-dry.png")
      out (str (.-stdout r) (.-stderr r))]
  (if (and (zero? (.-status r)) (re-find #"eye \[" out))
    (ok! "good --orbit --dry exits 0 with eye")
    (fail! "dry-orbit" (str "status=" (.-status r) " out=" out))))

(let [r (spawn-render "--dry" "--eye" "0,80,120" "--target" "0,2,0"
                      "--out" "/tmp/itonami-cam-dry-eye.png")
      out (str (.-stdout r) (.-stderr r))]
  (if (and (zero? (.-status r))
           (re-find #"eye \[0 80 120\]" out))
    (ok! "good --eye --dry exits 0 with stated eye")
    (fail! "dry-eye" (str "status=" (.-status r) " out=" out))))

(println "== CLI: bad flags and ground penetration exit ≠ 0")
(let [r (spawn-render "--eye" "1,2" "--out" "/tmp/itonami-cam-bad.png")
      out (str (.-stdout r) (.-stderr r))]
  (if (and (= 2 (.-status r)) (re-find #"X,Y,Z" out))
    (ok! "bad --eye exits 2")
    (fail! "bad --eye" (str "status=" (.-status r) " out=" out))))

(let [r (spawn-render "--zoom" "0" "--out" "/tmp/itonami-cam-badz.png")
      out (str (.-stdout r) (.-stderr r))]
  (if (and (= 2 (.-status r)) (re-find #"zoom" out))
    (ok! "bad --zoom exits 2")
    (fail! "bad --zoom" (str "status=" (.-status r) " out=" out))))

(let [r (spawn-render "--eye" "0,-1,50" "--out" "/tmp/itonami-cam-under.png")
      out (str (.-stdout r) (.-stderr r))]
  (if (and (= 2 (.-status r)) (re-find #"camera eye is underground" out))
    (ok! "underground --eye exits 2")
    (fail! "underground-cli" (str "status=" (.-status r) " out=" out))))

(let [r (spawn-render "--zoom" "100" "--out" "/tmp/itonami-cam-inside.png")
      out (str (.-stdout r) (.-stderr r))]
  (if (and (= 2 (.-status r)) (re-find #"camera eye is inside the fit volume" out))
    (ok! "inside-fit --zoom exits 2")
    (fail! "inside-fit-cli" (str "status=" (.-status r) " out=" out))))

(println)
(if (zero? @failures)
  (do (println "ACCEPT camera OK") (js/process.exit 0))
  (do (println (str "ACCEPT camera FAILED (" @failures ")")) (js/process.exit 1)))
