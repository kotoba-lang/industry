(ns accept-visual
  "CLI acceptance for #1754 — image diff, golden frames, visual-regression gate.

  Run from the package root (street goldens need webgpu + render on the classpath,
  same as `npm run render`; board needs Chromium only):
    npm run accept:visual
    npm run test:diff

  ## What it proves

  1. `diff-core` identical buffers → 0 changed pixels (also `test/diff_test.cljs`).
  2. Re-render each golden via `bin/render.cljs` (street / board paths after #2106)
     and `--baseline` the in-repo PNG → `{:changed-pixels 0}` at the measured
     threshold in `goldens/THRESHOLD.edn`.
  3. Gate exits ≠ 0 when a golden mismatches (see red/green proof below).

  Does not undo annotate/pick (#2117) or camera refusal (#2105).

  ## Threshold (measured, not assumed) — 2026-08-12

  See `goldens/THRESHOLD.edn`. Three street re-renders and two board re-renders of
  identical inputs on this container's SwiftShader/WebGL2 (and DOM board) path
  produced `max-delta 0` / `changed-pixels 0`. Chosen threshold: **0**.
  That stability is a property of this container, not of WebGL generally.

  ## Gate must fail — red/green proof (2026-08-12)

  The repo already scarred itself with `root-itonami-game-never-auto` claiming
  \"verified failing\" when sed never matched. So:

  **Break applied (grep proved the edit landed) before the red run:**

      # world.cljc isic-9601 :hue [0.36 0.55 0.95] → [0.95 0.20 0.20]
      grep -n '0.95 0.20 0.20' src/itonami/isic_9601/world.cljc
      # → 65:    :at [-933 -400] :unlock-at 0 :hue [0.95 0.20 0.20]

      npm run accept:visual   # exit 1
      # → FAIL street-cleared-0  changed-pixels=757  max-delta=65
      #    regions=([62 274 31 29])
      # → FAIL street-cleared-3 / street-cleared-8 (same region; 9601 unlocked at 0)
      # → board still ok (DOM path untouched by street hue)

  **Green on the unmodified tree (hue restored; grep shows [0.36 0.55 0.95]):**

      npm run accept:visual   # exit 0
      # → ACCEPT visual OK  (4 goldens, changed-pixels 0 each)

  Diff of the broken street frame against the golden satisfied the one-instance
  constraint: a **single** region `[62 274 31 29]` — the 9601 shopfront — and
  nothing else. Documented here so a future agent does not register a gate that
  never actually went red."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [diff-core :as diff]))

(def here (path/resolve (path/dirname *file*) ".."))
(def engine (path/resolve here "../../../../../../orgs/kotoba-lang"))
(def goldens-dir (path/join here "goldens"))
(def tmp (path/join here ".build/accept-visual"))
(def failures (atom 0))

(def street-cp
  (str "src:bin:"
       (path/join engine "webgpu/src") ":"
       (path/join engine "render/src")))

(defn- fail! [label detail]
  (swap! failures inc)
  (println "  FAIL:" label)
  (when detail (println "       " detail)))

(defn- ok! [label] (println "  ok  " label))

(defn- last-edn [stdout]
  (let [line (->> (str/split-lines stdout)
                  (map str/trim)
                  (remove str/blank?)
                  last)]
    (when line
      (try (edn/read-string line)
           (catch :default _ nil)))))

(defn- load-threshold []
  (let [p (path/join goldens-dir "THRESHOLD.edn")
        raw (fs/readFileSync p "utf8")
        ;; strip leading ;; comment lines for edn/read-string friendliness
        body (->> (str/split-lines raw)
                  (remove #(str/starts-with? (str/trim %) ";"))
                  (str/join "\n"))]
    (edn/read-string body)))

(defn- load-manifest []
  (let [p (path/join goldens-dir "manifest.edn")
        raw (fs/readFileSync p "utf8")
        body (->> (str/split-lines raw)
                  (remove #(str/starts-with? (str/trim %) ";"))
                  (str/join "\n"))]
    (edn/read-string body)))

(defn- spawn-render [classpath args]
  (cp/spawnSync "npx" (clj->js (into ["nbb" "--classpath" classpath "bin/render.cljs"] args))
                #js {:cwd here :encoding "utf8" :env (js/Object.assign
                                                      #js {}
                                                      js/process.env
                                                      #js {:PW_CHROMIUM
                                                           (or (.-PW_CHROMIUM js/process.env)
                                                               (let [p "/home/ubuntu/.cache/ms-playwright/chromium-1193/chrome-linux/chrome"]
                                                                 (when (fs/existsSync p) p))
                                                               "/opt/google/chrome/chrome")})}))

(fs/mkdirSync tmp #js {:recursive true})

(def thr-map (load-threshold))
(def threshold (int (or (:chosen-threshold thr-map) (:threshold thr-map) 0)))
(def manifest (load-manifest))

(println (str "== threshold from goldens/THRESHOLD.edn: " threshold
              "  (measured " (:measured-at thr-map) ")"))

(println "== library: identical PNG → zero changed pixels")
(let [g (path/join goldens-dir "street-cleared-0.png")
      s (diff/compare-files g g 0 false)]
  (if (and (zero? (:changed-pixels s)) (zero? (:max-delta s)))
    (ok! "self-baseline street-cleared-0")
    (fail! "self-baseline" (pr-str s))))

(println "== goldens: re-render + --baseline")
(doseq [entry manifest]
  (let [id (name (:id entry))
        golden (path/join goldens-dir (:file entry))
        out (path/join tmp (str id ".png"))
        diff-out (path/join tmp (str id ".diff.png"))
        cp (if (= :board (:view entry)) "src:bin" street-cp)
        args (vec (concat (:args entry)
                          ["--out" out
                           "--baseline" golden
                           "--diff-out" diff-out
                           "--threshold" (str threshold)]))
        _ (println (str "  … " id))
        r (spawn-render cp args)
        out-s (str (.-stdout r) (.-stderr r))
        edn (last-edn (.-stdout r))]
    (cond (not (fs/existsSync golden))
          (fail! id "golden missing")
          (not (zero? (.-status r)))
          (fail! id (str "render exit " (.-status r) "\n" out-s))
          (nil? edn)
          (fail! id (str "no EDN stats line\n" out-s))
          (not (zero? (:changed-pixels edn)))
          (fail! id (str "changed-pixels=" (:changed-pixels edn)
                         " max-delta=" (:max-delta edn)
                         " regions=" (pr-str (take 3 (:regions edn)))))
          :else
          (ok! (str id "  changed-pixels=0  max-delta=" (:max-delta edn))))))

(println)
(if (zero? @failures)
  (do (println "ACCEPT visual OK") (js/process.exit 0))
  (do (println (str "ACCEPT visual FAILED (" @failures ")")) (js/process.exit 1)))
