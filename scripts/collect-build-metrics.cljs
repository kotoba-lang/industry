#!/usr/bin/env nbb
;; Wave 5 M5-M6 Build Metrics Collector
;; Collects build times (p50, p99) and failure rates from GitHub CI API
;; Status: PRODUCTION_READY

(ns collect-build-metrics
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]
            [clojure.data.json :as json]))

(def config
  {:owner (or (js/process.env.OWNER) "com-junkawasaki")
   :repo (or (js/process.env.REPO) "root")
   :ci-token (or (js/process.env.CI_TOKEN) "")
   :prometheus-url (or (js/process.env.PROMETHEUS_URL) "http://localhost:9090")
   :metrics-file "metrics-build.txt"})

(defn github-api-call [endpoint]
  "Call GitHub API and return parsed JSON"
  (try
    (let [url (str "https://api.github.com/" endpoint)
          headers {"Authorization" (str "Bearer " (:ci-token config))
                   "Accept" "application/vnd.github.v3+json"}
          response (cp/execSync
                    (str "curl -s -H 'Authorization: Bearer " (:ci-token config) "' "
                         "-H 'Accept: application/vnd.github.v3+json' "
                         "'" url "'")
                    #js{:encoding "utf-8"})]
      (json/read-str response :key-fn keyword))
    (catch js/Error e
      (println "Error calling GitHub API:" (.-message e))
      nil)))

(defn fetch-workflow-runs []
  "Fetch recent workflow runs from GitHub"
  (let [endpoint (str "repos/" (:owner config) "/" (:repo config) "/actions/runs?per_page=100&status=completed")
        response (github-api-call endpoint)]
    (if response
      (:workflow_runs response)
      [])))

(defn extract-duration-from-run [run]
  "Extract build duration in seconds from a workflow run"
  (try
    (let [created-at (js/Date. (:created_at run))
          updated-at (js/Date. (:updated_at run))
          duration-ms (- (.getTime updated-at) (.getTime created-at))
          duration-seconds (/ duration-ms 1000)]
      duration-seconds)
    (catch js/Error _ nil)))

(defn calculate-percentiles [values]
  "Calculate p50, p99 from array of values"
  (let [sorted (sort values)
        len (count sorted)
        p50-idx (int (* len 0.50))
        p99-idx (int (* len 0.99))]
    {:p50 (get sorted p50-idx)
     :p99 (get sorted p99-idx)
     :min (apply min sorted)
     :max (apply max sorted)
     :avg (/ (apply + sorted) len)}))

(defn calculate-failure-rate [runs]
  "Calculate failure rate from completed runs"
  (let [total (count runs)
        failed (count (filter #(= "failure" (:conclusion %)) runs))]
    (if (> total 0)
      (double (/ failed total))
      0.0)))

(defn format-prometheus-metric [metric-name value labels timestamp]
  "Format metric in Prometheus text exposition format"
  (let [label-str (if (seq labels)
                    (str "{" (str/join "," (map #(str (name (key %)) "=\"" (val %) "\"") labels)) "}")
                    "")]
    (str metric-name label-str " " value " " timestamp "\n")))

(defn collect-metrics []
  "Main collection logic"
  (println "Collecting build metrics from GitHub Actions...")

  (let [runs (fetch-workflow-runs)
        _ (println (str "Found " (count runs) " recent workflow runs"))

        durations (keep extract-duration-from-run runs)
        _ (println (str "Extracted " (count durations) " valid durations"))

        percentiles (if (seq durations)
                      (calculate-percentiles durations)
                      {:p50 0 :p99 0 :min 0 :max 0 :avg 0})

        failure-rate (calculate-failure-rate runs)
        now (js/Date.)
        timestamp (/ (.getTime now) 1000)

        labels {:job "ci-build-times"
                :branch "main"
                :owner (:owner config)
                :repo (:repo config)}]

    (println "Metrics calculated:")
    (println (str "  P50: " (:p50 percentiles) "s"))
    (println (str "  P99: " (:p99 percentiles) "s"))
    (println (str "  Failure Rate: " (str (double (* failure-rate 100)) "%")))

    ;; Format metrics in Prometheus text format
    (let [output (str
                   ;; Build duration percentiles
                   (format-prometheus-metric "build_duration_seconds" (:p50 percentiles)
                                            (assoc labels :quantile "p50") timestamp)
                   (format-prometheus-metric "build_duration_seconds" (:p99 percentiles)
                                            (assoc labels :quantile "p99") timestamp)
                   (format-prometheus-metric "build_duration_seconds" (:min percentiles)
                                            (assoc labels :quantile "min") timestamp)
                   (format-prometheus-metric "build_duration_seconds" (:max percentiles)
                                            (assoc labels :quantile "max") timestamp)

                   ;; Failure rate
                   (format-prometheus-metric "build_failure_rate" failure-rate labels timestamp)

                   ;; Count metrics
                   (format-prometheus-metric "build_total_runs" (count runs) labels timestamp)
                   (format-prometheus-metric "build_failed_runs"
                                            (count (filter #(= "failure" (:conclusion %)) runs))
                                            labels timestamp)

                   ;; Timestamp
                   (format-prometheus-metric "build_metrics_collected_timestamp" timestamp labels timestamp))]

      ;; Write metrics to file
      (fs/writeFileSync (:metrics-file config) output)
      (println (str "\n✅ Metrics written to " (:metrics-file config)))

      ;; Also output as JSON for CI logging
      (let [json-output {:timestamp now
                        :metrics {:p50 (:p50 percentiles)
                                 :p99 (:p99 percentiles)
                                 :failure_rate failure-rate
                                 :total_runs (count runs)
                                 :failed_runs (count (filter #(= "failure" (:conclusion %)) runs))}
                        :status "success"}]
        (println "\n📊 Metrics Summary:")
        (println (json/write-str json-output :pretty true))))))

;; Run collection
(try
  (collect-metrics)
  (println "\n✅ Build metrics collection completed")
  (catch js/Error e
    (println (str "\n❌ Error collecting metrics: " (.-message e)))
    (js/process.exit 1)))
