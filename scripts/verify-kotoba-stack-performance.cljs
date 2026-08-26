#!/usr/bin/env nbb
;; Verify pinned quiet-host performance evidence for the kotoba stack.
;;
;; Default: integrity — committed JSON, datoms, host-meta, and docs cross-check.
;; --live: re-run amu benchmarks when orgs/kotoba-lang/amu exists and load < 4;
;;         fail if medians regress more than 5% vs the pinned baseline JSON.
;;
;; Exit: 0 pass · 1 regression or mismatch · 2 cannot certify (load/host) ·
;;       90 required input missing · 91 cannot run harness

(ns verify-kotoba-stack-performance
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn flag? [n] (some #(= % n) args))
(defn opt [n default]
  (let [i (.indexOf args n)]
    (if (neg? i) default (nth args (inc i) default))))
(def root (path/resolve (opt "--root" ".")))
(def live? (flag? "--live"))
(def regression-frac 0.05)
(def datoms-tolerance 0.015)
(def official-status "quiet-host-official")
(def baseline-run-dir "90-docs/performance/runs/2026-08-26-judah-quiet")
(def failures (atom []))
(defn fail! [s] (swap! failures conj s))
(defn die! [code msg]
  (println msg)
  (js/process.exit code))

(defn read-text [p]
  (let [abs (if (path/isAbsolute p) p (path/join root p))]
    (if (fs/existsSync abs)
      (str (fs/readFileSync abs "utf8"))
      (do (fail! (str "missing " p)) ""))))

(defn read-json [p]
  (try (js/JSON.parse (read-text p))
       (catch :default e
         (fail! (str "invalid JSON " p ": " (.-message e)))
         nil)))

(defn read-edn-file [p]
  (try (reader/read-string (read-text p))
       (catch :default e
         (fail! (str "invalid EDN " p ": " (ex-message e)))
         nil)))

(defn first-loadavg [s]
  (when (and (string? s) (not (str/blank? s)))
    (js/parseFloat (first (str/split (str/trim s) #"\s+")))))

(defn median-ns [runtime engine]
  (some-> runtime .-engines (aget engine) .-steadyStateNanosecondsPerKernel .-median))

(defn ratio [a b]
  (when (and (number? a) (number? b) (pos? b)) (/ a b)))

(defn within? [actual expected tol]
  (and (number? actual) (number? expected)
       (<= (js/Math.abs (- actual expected)) (* (js/Math.abs expected) tol))))

(defn worse-by? [actual baseline max-frac]
  (and (number? actual) (number? baseline) (pos? baseline)
       (> actual (* baseline (+ 1 max-frac)))))

(defn wasm32-compile-medians [compile-json]
  (let [targets (.-targets compile-json)
        workers (.-persistentWorkers compile-json)
        n (.-length targets)
        t (loop [i 0]
            (when (< i n)
              (let [x (aget targets i)]
                (if (= "wasm32" (.-target x)) x (recur (inc i))))))
        m (.-length workers)
        worker (loop [i 0]
                 (when (< i m)
                   (let [x (aget workers i)]
                     (if (= "wasm32" (.-target x)) x (recur (inc i))))))]
    {:cold (some-> t .-processColdWall .-medianMilliseconds)
     :loaded (some-> t .-loadedCompiler .-medianMilliseconds)
     :semantic (some-> worker .-semanticEditIncremental .-roundTripMilliseconds)}))

(defn official-entity [datoms]
  (some #(when (= official-status (:performance/status %)) %) datoms))

(defn verify-integrity! [entity]
  (if-not entity
    (do (fail! (str "no :performance/status " official-status " entity in performance.datoms.edn"))
        nil)
    (let [runtime-path (:performance/runtime-json entity)
          compile-path (:performance/compile-json entity)
          host-meta (path/join baseline-run-dir "host-meta.json")
          runtime (read-json runtime-path)
          compile (read-json compile-path)
          host (read-json host-meta)]
      (doseq [k [:performance/runtime-json :performance/compile-json
                 :performance/summary-path :performance/roadmap-path :performance/adr-id]]
        (when-not (get entity k) (fail! (str "datoms missing " (name k)))))
      (when runtime
        (when-not (= "kotoba.runtime-comparison/v1" (.-format runtime))
          (fail! "runtime.json format drift"))
        (let [rust (median-ns runtime "rust")
              amu-n (median-ns runtime "amu-native")
              amu-w (median-ns runtime "amu-wasm32")
              native-ratio (ratio amu-n rust)
              wasm-ratio (ratio amu-w rust)]
          (when-not (within? native-ratio (:performance/amu-native-vs-rust entity) datoms-tolerance)
            (fail! (str "amu-native-vs-rust datoms/json mismatch: datoms="
                        (:performance/amu-native-vs-rust entity)
                        " computed=" (when native-ratio (.toFixed native-ratio 3)))))
          (when-not (within? wasm-ratio (:performance/amu-wasm-vs-rust entity) datoms-tolerance)
            (fail! (str "amu-wasm-vs-rust datoms/json mismatch: datoms="
                        (:performance/amu-wasm-vs-rust entity)
                        " computed=" (when wasm-ratio (.toFixed wasm-ratio 3)))))))
      (when compile
        (when-not (= "kotoba.performance-baseline/v1" (.-format compile))
          (fail! "compile.json format drift"))
        (let [{:keys [cold loaded semantic]} (wasm32-compile-medians compile)]
          (when-not (within? cold (:performance/compile-cold-ms-wasm32 entity) datoms-tolerance)
            (fail! (str "compile-cold-ms datoms/json mismatch: datoms="
                        (:performance/compile-cold-ms-wasm32 entity) " json=" cold)))
          (when-not (within? loaded (:performance/compile-loaded-ms-wasm32 entity) datoms-tolerance)
            (fail! (str "compile-loaded-ms datoms/json mismatch: datoms="
                        (:performance/compile-loaded-ms-wasm32 entity) " json=" loaded)))
          (when (and semantic (:performance/compile-semantic-edit-ms-wasm32 entity))
            (when-not (within? semantic (:performance/compile-semantic-edit-ms-wasm32 entity) datoms-tolerance)
              (fail! (str "semantic-edit-ms datoms/json mismatch: datoms="
                          (:performance/compile-semantic-edit-ms-wasm32 entity) " json=" semantic))))))
      (when host
        (when-not (= official-status (.-certification host))
          (fail! "host-meta.json certification is not quiet-host-official"))
        (let [load1 (first-loadavg (.-loadavg host))]
          (when-not (and (number? load1) (< load1 4))
            (fail! (str "host-meta loadavg[0] must be < 4 for official baseline, got " load1)))))
      (doseq [doc [(:performance/summary-path entity)
                   (:performance/roadmap-path entity)
                   (str "90-docs/adr/"
                        (str/replace (str (:performance/adr-id entity)) #"^adr-" "")
                        ".edn")]]
        (read-text doc))
      {:runtime runtime :compile compile :entity entity})))

(defn run-live! [{:keys [runtime compile]}]
  (let [amu (path/join root "orgs/kotoba-lang/amu")]
    (when-not (fs/existsSync amu)
      (die! 90 "orgs/kotoba-lang/amu missing; cannot --live without amu checkout"))
    (let [load1 (first-loadavg
                 (or (some-> (cp/execSync "sysctl -n vm.loadavg"
                                          #js {:encoding "utf8"})
                             str/trim)
                     ""))]
      (when-not (and (number? load1) (< load1 4))
        (die! 2 (str "load average " load1 " >= 4; refusing live certification")))
      (let [tmpdir (fs/mkdtempSync (path/join (os/tmpdir) "kotoba-perf-live-"))
            runtime-out (path/join tmpdir "runtime.json")
            compile-out (path/join tmpdir "compile.json")
            run (fn [script args]
                  (cp/spawnSync "node"
                                (clj->js (concat [script] args))
                                #js {:cwd amu :encoding "utf8" :stdio "pipe"
                                     :maxBuffer (* 32 1024 1024)}))
            rt (run "scripts/runtime-comparison.mjs"
                    ["--runs" "3" "--calls" "100000" "--warmup" "10000" "--n" "200"
                     "--output" runtime-out])
            _ (when-not (zero? (or (.-status rt) 1))
                (print (str (or (.-stdout rt) "") (or (.-stderr rt) "")))
                (die! 91 "runtime-comparison.mjs failed"))
            cp-run (run "scripts/performance-baseline.mjs"
                        ["--runs" "3" "--output" compile-out])
            _ (when-not (zero? (or (.-status cp-run) 1))
                (print (str (or (.-stdout cp-run) "") (or (.-stderr cp-run) "")))
                (die! 91 "performance-baseline.mjs failed"))
            live-runtime (js/JSON.parse (str (fs/readFileSync runtime-out "utf8")))
            live-compile (js/JSON.parse (str (fs/readFileSync compile-out "utf8")))
            base-rust (median-ns runtime "rust")
            base-amu-n (median-ns runtime "amu-native")
            base-amu-w (median-ns runtime "amu-wasm32")
            base-compile (wasm32-compile-medians compile)
            live-rust (median-ns live-runtime "rust")
            live-amu-n (median-ns live-runtime "amu-native")
            live-amu-w (median-ns live-runtime "amu-wasm32")
            live-compile (wasm32-compile-medians live-compile)]
        (doseq [[label live base]
                [["amu-native ns/call" live-amu-n base-amu-n]
                 ["amu-wasm32 ns/call" live-amu-w base-amu-w]
                 ["compile cold ms" (:cold live-compile) (:cold base-compile)]
                 ["compile loaded ms" (:loaded live-compile) (:loaded base-compile)]
                 ["semantic edit ms" (:semantic live-compile) (:semantic base-compile)]]]
          (when (worse-by? live base regression-frac)
            (fail! (str "regression >5% on " label ": baseline=" base " live=" live))))
        (when (and base-rust live-rust (worse-by? (ratio live-amu-n live-rust)
                                                  (ratio base-amu-n base-rust)
                                                  regression-frac))
          (fail! "regression >5% on amu-native-vs-rust ratio"))
        (when (and base-rust live-rust (worse-by? (ratio live-amu-w live-rust)
                                                  (ratio base-amu-w base-rust)
                                                  regression-frac))
          (fail! "regression >5% on amu-wasm-vs-rust ratio"))
        (try (fs/rmSync tmpdir #js {:recursive true :force true}) (catch :default _ nil))))))

(defn -main []
  (doseq [p ["90-docs/performance/performance.datoms.edn"
             (path/join baseline-run-dir "runtime.json")
             (path/join baseline-run-dir "compile.json")
             (path/join baseline-run-dir "host-meta.json")]]
    (when-not (fs/existsSync (path/join root p))
      (die! 90 (str "required input missing: " p))))
  (let [datoms (read-edn-file "90-docs/performance/performance.datoms.edn")
        entity (when (sequential? datoms) (official-entity datoms))
        _ (verify-integrity! entity)]
    (when live? (run-live! {:runtime (read-json (path/join baseline-run-dir "runtime.json"))
                            :compile (read-json (path/join baseline-run-dir "compile.json"))}))
    (if (seq @failures)
      (do (doseq [f @failures] (println "FAIL:" f))
          (die! 1 (str "kotoba-stack-performance: " (count @failures) " failure(s)")))
      (println "kotoba-stack-performance: OK SCANNED=1 official="
               (:performance/id entity)))))

(-main)
