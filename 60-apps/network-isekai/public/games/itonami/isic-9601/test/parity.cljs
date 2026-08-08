(ns parity
  "Same engine, two compilers, one set of numbers.

  The preview page runs `kami.webgpu.ir` and `kami.webgpu.submission` in the browser through
  **squint**, while `bin/render.cljs` runs them through **nbb** and `test/world3d_test.clj`
  runs them on the **JVM**. Three executions of one engine is fine; three engines is the
  thing CLAUDE.md's 3D rule forbids. The difference between those two sentences is entirely
  whether the numbers agree, so this checks that they do.

  It compares every float: 4,768 instance floats and 60 globals per frame, three frames.
  Not a hash — a hash tells you something differs and nothing about what, and the first
  question after a parity failure is always *which lane*. A mismatch here names the frame,
  the index, and both values.

  Run: npm run parity   (needs a JVM; `clojure -M:parity-dump` produces the reference)"
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]))

(def here (path/resolve (path/dirname *file*) ".."))
(defn- sh
  "`execSync` returns nil when stdout is not piped, so the `.toString` has to be conditional."
  [cmd opts]
  (let [out (cp/execSync cmd (clj->js (merge {:cwd here :maxBuffer (* 64 1024 1024)} opts)))]
    (when out (.toString out))))

;; --------------------------------------------------------------------------

(def failures (atom []))
(defn- fail! [& xs] (swap! failures conj (apply str (interpose " " (map str xs)))))

(defn- close?
  "Float32 round-trip, two different double->float paths, two different `Math.sin`
  implementations. Bit equality is the wrong bar; anything above single-precision epsilon is
  a real disagreement."
  [a b]
  (let [d (js/Math.abs (- a b))]
    (or (< d 1e-6) (< (/ d (max 1e-9 (js/Math.abs a) (js/Math.abs b))) 1e-6))))

(defn- compare-lane! [label frame-i xs ys]
  (cond
    (not= (count xs) (count ys))
    (fail! "frame" frame-i label "length" (count ys) "≠ JVM" (count xs))

    :else
    (let [bad (->> (range (count xs))
                   (remove (fn [i] (close? (nth xs i) (nth ys i))))
                   (take 5))]
      (doseq [i bad]
        (fail! "frame" frame-i label "[" i "] JVM" (nth xs i) "≠ squint" (nth ys i))))))

;; --------------------------------------------------------------------------

(println)
(println "  [1/3] JVM engine")
(def jvm (js->clj (js/JSON.parse (sh "clojure -M:parity-dump" {:stdio #js ["ignore" "pipe" "ignore"]}))
                  :keywordize-keys true))

(println "  [2/3] squint engine")
(sh "npx --yes squint-cljs@0.8.147 compile" {:stdio "ignore"})
(sh (str "npx --yes esbuild@0.25.0 .build/parity_probe.mjs --bundle --format=iife "
         "--target=es2020 --inject:preview/squint_shim.mjs --outfile=.build/parity_probe.js")
    {:stdio "ignore"})
(def sq (js->clj (js/JSON.parse (sh "node .build/parity_probe.js" {:stdio #js ["ignore" "pipe" "inherit"]}))
                 :keywordize-keys true))

(println "  [3/3] compare")
(println)

(let [a (:frames jvm) b (:frames sq)]
  (if (not= (count a) (count b))
    (fail! "frame count" (count b) "≠ JVM" (count a))
    (doseq [i (range (count a))]
      (let [fa (nth a i) fb (nth b i)]
        (when (not= (:n fa) (:n fb))
          (fail! "frame" i "instance count" (:n fb) "≠ JVM" (:n fa)))
        (compare-lane! "inst" i (:inst fa) (:inst fb))
        (compare-lane! "g" i (:g fa) (:g fb))
        (println (str "   cleared=" (:cleared fa)
                      "  instances " (:n fa)
                      "  floats " (count (:inst fa)) " + " (count (:g fa))
                      "  " (if (empty? @failures) "一致" "…")))))))

(println)
(if (seq @failures)
  (do (println "FAIL parity — the browser engine and the JVM engine disagree")
      (doseq [f (take 20 @failures)] (println "  -" f))
      (when (> (count @failures) 20)
        (println "  … and" (- (count @failures) 20) "more"))
      (js/process.exit 1))
  (println "PARITY OK — squint と JVM は同じ数値を出しています"))
