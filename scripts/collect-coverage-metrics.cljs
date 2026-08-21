#!/usr/bin/env nbb
;; Wave 5 Test Coverage Metrics Collector
;; Collects coverage data from CI artifacts
;; Status: PRODUCTION_READY
;; 2026-07-30: two defects fixed.
;;   1. A hardcoded-coverage fallback (91.5 / 91.2 / 90.8 / 89.5) stood in for a
;;      missing report. No data now means NO coverage sample and a non-zero exit.
;;   2. The script could not run at all: it required clojure.data.json, which nbb
;;      does not ship and scripts/nbb_compat does not provide, so it died at the
;;      require. Now uses nbb's native JSON.
;; The two together mattered: the gates under 90-docs/gates/ reference this
;; collector, so they were referencing something that could not run, and would
;; have reported an invented near-target number if it had.

(ns collect-coverage-metrics
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]))

;; nbb has no clojure.data.json and scripts/nbb_compat does not provide one, so
;; JSON goes through the platform. Kept as two tiny functions rather than inline
;; interop so the call sites read the same as before.
(defn- json-read [^string text]
  (js->clj (js/JSON.parse text) :keywordize-keys true))

(defn- json-write [x]
  (js/JSON.stringify (clj->js x) nil 2))

(def config
  ;; `js/process.env.X` is a property read, not a function. It used to be written
  ;; `(js/process.env.X)`, which CALLS it -- "Function.prototype.apply was called
  ;; on undefined". That was the third thing keeping this script from running.
  {:ci-token (or js/process.env.CI_TOKEN "")
   :owner (or js/process.env.OWNER "com-junkawasaki")
   :repo (or js/process.env.REPO "root")
   :coverage-source (or js/process.env.COVERAGE_SOURCE "ci-artifacts")
   :metrics-file "metrics-coverage.txt"})

(defn fetch-latest-coverage []
  "The coverage report, or nil when there is none.

  Returns nil rather than a fallback. This function used to return hardcoded
  91.5 / 91.2 / 90.8 / 89.5 when coverage/coverage-summary.json was absent, which
  is the only branch reachable here -- that file has never existed in this
  repository. Those numbers were emitted as `code_coverage_percent` labelled
  job=\"ci-test-coverage\" branch=\"main\", indistinguishable in the metrics store
  from a measurement, over a status of \"success\".

  Stated precisely, because the difference matters: this was a latent lie, not an
  active one. The script could not actually run (see the header note on
  clojure.data.json), so nothing was emitted. It would have lied the moment
  someone made it runnable -- which is what the same commit does.

  A metric that invents a plausible value when it has no data is worse than no
  metric: the absence is what someone needs to see, and a number hides it."
  (try
    (if (fs/existsSync "coverage/coverage-summary.json")
      (let [content (fs/readFileSync "coverage/coverage-summary.json" "utf-8")]
        (json-read content))
      (do
        (println "collect-coverage-metrics: coverage/coverage-summary.json が無いため")
        (println "  カバレッジは MEASURED されていません。捏造値は出しません。")
        nil))
    (catch js/Error e
      (println (str "Error reading coverage: " (.-message e)))
      nil)))

(defn extract-coverage-metrics [coverage-data]
  "Extract relevant coverage metrics"
  (let [total (get-in coverage-data [:total] {})
        lines-pct (get-in total [:lines :pct] 0)
        statements-pct (get-in total [:statements :pct] 0)
        functions-pct (get-in total [:functions :pct] 0)
        branches-pct (get-in total [:branches :pct] 0)]
    {:overall lines-pct
     :statements statements-pct
     :functions functions-pct
     :branches branches-pct}))

(defn format-prometheus-metric [metric-name value labels timestamp]
  "Format metric in Prometheus text exposition format"
  (let [label-str (if (seq labels)
                    (str "{" (str/join "," (map #(str (name (key %)) "=\"" (val %) "\"") labels)) "}")
                    "")]
    (str metric-name label-str " " value " " timestamp "\n")))

(defn- no-data-output [timestamp labels]
  "What to emit when nothing was measured.

  NO `code_coverage_percent` sample at all -- not a zero. Zero would be read as
  \"coverage collapsed\", which is a different (and also false) claim than \"we did
  not measure\". The only thing emitted is an availability gauge, so the absence
  is itself visible and alertable."
  (str (format-prometheus-metric "coverage_metrics_available" 0 labels timestamp)
       (format-prometheus-metric "coverage_metrics_collected_timestamp" timestamp
                                 labels timestamp)))

(defn collect-metrics []
  "Main collection logic. Returns :measured or :no-data."
  (println "Collecting test coverage metrics...")

  (let [coverage-data (fetch-latest-coverage)
        now (js/Date.)
        timestamp (/ (.getTime now) 1000)
        labels {:job "ci-test-coverage"
                :branch "main"
                :owner (:owner config)
                :repo (:repo config)}]

    (if-not coverage-data
      ;; ---- no data -------------------------------------------------------
      (do
        (fs/writeFileSync (:metrics-file config) (no-data-output timestamp labels))
        (println (str "\ncoverage_metrics_available 0 を " (:metrics-file config)
                      " に書きました（カバレッジ値は1件も出していません）"))
        (println (json-write {:timestamp now
                            :metrics nil
                            :sla_target 92
                            :sla_status "unknown"
                            :status "no-data"}))
        (println "\n❌ カバレッジは測定されていません。")
        (println "   readiness gate はこれを success として読んではいけません。")
        :no-data)

      ;; ---- measured ------------------------------------------------------
      (let [metrics (extract-coverage-metrics coverage-data)]
        (println "Coverage metrics calculated:")
        (println (str "  Overall: " (:overall metrics) "%"))
        (println (str "  Statements: " (:statements metrics) "%"))
        (println (str "  Functions: " (:functions metrics) "%"))
        (println (str "  Branches: " (:branches metrics) "%"))

        (when (< (:overall metrics) 92)
          (println "\n⚠️  WARNING: Coverage below target (92%)")
          (println (str "  Current: " (:overall metrics) "%")))

        (let [output (str
                       (format-prometheus-metric "coverage_metrics_available" 1
                                                 labels timestamp)
                       (format-prometheus-metric "code_coverage_percent" (:overall metrics)
                                                 (assoc labels :type "overall") timestamp)
                       (format-prometheus-metric "code_coverage_percent" (:statements metrics)
                                                 (assoc labels :type "statements") timestamp)
                       (format-prometheus-metric "code_coverage_percent" (:functions metrics)
                                                 (assoc labels :type "functions") timestamp)
                       (format-prometheus-metric "code_coverage_percent" (:branches metrics)
                                                 (assoc labels :type "branches") timestamp)
                       (format-prometheus-metric "coverage_sla_target" 92 labels timestamp)
                       (format-prometheus-metric "coverage_sla_status"
                                                 (if (>= (:overall metrics) 92) 1 0)
                                                 labels timestamp)
                       (format-prometheus-metric "coverage_metrics_collected_timestamp"
                                                 timestamp labels timestamp))]
          (fs/writeFileSync (:metrics-file config) output)
          (println (str "\n✅ Metrics written to " (:metrics-file config)))
          (println "\n📊 Coverage Metrics Summary:")
          (println (json-write {:timestamp now
                              :metrics metrics
                              :sla_target 92
                              :sla_status (if (>= (:overall metrics) 92)
                                            "passing" "failing")
                              :status "measured"}))
          :measured)))))

;; Run collection
(try
  ;; Exit non-zero when nothing was measured. A readiness gate that cannot measure
  ;; coverage must not be able to read this run as a pass -- which is exactly what
  ;; the previous version allowed, by printing "success" over invented numbers.
  (let [outcome (collect-metrics)]
    (if (= :measured outcome)
      (println "\n✅ Coverage metrics collection completed (measured)")
      (do (println "\n⛔ Coverage metrics collection completed WITHOUT DATA")
          (js/process.exit 2))))
  (catch js/Error e
    (println (str "\n❌ Error: " (.-message e)))
    (js/process.exit 1)))
