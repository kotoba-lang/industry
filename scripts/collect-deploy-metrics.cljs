#!/usr/bin/env nbb
;; Wave 5 Deployment Success Metrics Collector
;; Collects deployment metrics from CloudFlare Workers/Pages
;; Status: PRODUCTION_READY
;; 2026-07-30: this collector could not run, and would have misreported if it had.
;;   - required clojure.data.json, which nbb does not provide -> died at the require
;;   - (js/process.env.X) CALLS a property -> "apply was called on undefined"
;;   - an unreachable or unexpected upstream was reported as ZERO, not as absent
;; All three are fixed together: fixing only the first two would have turned a
;; script that could not run into one that published a comfortable falsehood.
;; Measured before the fix, with nothing reachable, this family reported things
;; like "0 builds, 0% failure rate, status success".


(ns collect-deploy-metrics
  (:require ["fs" :as fs]
            ["child_process" :as cp]
            [clojure.string :as str]))
;; nbb ships no clojure.data.json and scripts/nbb_compat does not provide one, so
;; JSON goes through the platform. Two small functions so the call sites read as
;; they did before.
(defn- json-read [^string text]
  (js->clj (js/JSON.parse text) :keywordize-keys true))

(defn- json-write [x]
  (js/JSON.stringify (clj->js x) nil 2))


(def config
  {:cloudflare-token (or js/process.env.CLOUDFLARE_API_TOKEN "")
   :deployment-source (or js/process.env.DEPLOYMENT_SOURCE "cloudflare-api")
   :metrics-file "metrics-deployment.txt"
   :account-id (or js/process.env.CLOUDFLARE_ACCOUNT_ID "")})

(defn fetch-deployment-events []
  "Fetch recent deployment events from CloudFlare"
  (try
    (let [endpoint (str "https://api.cloudflare.com/client/v4/accounts/"
                       (:account-id config) "/workers/deployments")
          curl-cmd (str "curl -s "
                       "-H 'Authorization: Bearer " (:cloudflare-token config) "' "
                       "-H 'Content-Type: application/json' "
                       "'" endpoint "'")
          response (cp/execSync curl-cmd #js{:encoding "utf-8"})
          parsed (json-read response)]

      ;; nil, not []: a response whose :success is false, or which carries no
      ;; :result, means we could not measure. [] would say "CloudFlare answered and
      ;; there were no deployments" -- a different claim, and the comfortable one.
      (if (and (:success parsed) (some? (:result parsed)))
        (vec (:result parsed))
        nil))
    (catch js/Error e
      (println "Error fetching deployments:" (.-message e))
      ;; nil, not []: this is "could not fetch", which is not zero.
      nil)))

(defn calculate-deployment-stats [deployments]
  "Calculate success rate and other metrics from deployments"
  (let [total (count deployments)
        successful (count (filter #(= "success" (:status %)) deployments))
        failed (count (filter #(= "failure" (:status %)) deployments))
        success-rate (if (> total 0)
                       (double (/ successful total))
                       0.0)

        ;; Calculate time to production (in seconds)
        durations (keep (fn [d]
                         (try
                           (let [created (js/Date. (:created_on d))
                                 deployed (js/Date. (:deployed_on d))]
                             (if (and created deployed)
                               (/ (- (.getTime deployed) (.getTime created)) 1000)
                               nil))
                           (catch js/Error _ nil)))
                       deployments)
        avg-time (if (seq durations)
                   (/ (apply + durations) (count durations))
                   0)]

    {:total total
     :successful successful
     :failed failed
     :success_rate success-rate
     :avg_deployment_time avg-time}))

(defn format-prometheus-metric [metric-name value labels timestamp]
  "Format metric in Prometheus text exposition format"
  (let [label-str (if (seq labels)
                    (str "{" (str/join "," (map #(str (name (key %)) "=\"" (val %) "\"") labels)) "}")
                    "")]
    (str metric-name label-str " " value " " timestamp "\n")))

(defn collect-metrics []
  "Main collection logic"
  (println "Collecting deployment metrics from CloudFlare...")

  (let [deployments (fetch-deployment-events)
        _ (println (str "Found " (count deployments) " recent deployments"))
        ;; An unreachable API used to report zero deployments with a 0.0 success
  ;; rate -- indistinguishable from every deploy having failed.
        _ (when (nil? deployments)
            (let [ts (/ (.getTime (js/Date.)) 1000)]
              (fs/writeFileSync
               (:metrics-file config)
               (format-prometheus-metric "deployment_metrics_available" 0 {:job "deployment" :source "cloudflare"} ts))
              (println (str "\n\u26d4 upstream unreachable or unexpected: wrote "
                            "deployment_metrics_available 0 to " (:metrics-file config)
                            " and no measurement at all."))
              (println "   A readiness gate must not read this run as a pass.")
              (js/process.exit 2)))

        stats (calculate-deployment-stats deployments)
        now (js/Date.)
        timestamp (/ (.getTime now) 1000)

        labels {:job "deployment-success"
                :source "cloudflare"
                :service "kotobase.net"}]

    (println "Deployment metrics calculated:")
    (println (str "  Total deployments: " (:total stats)))
    (println (str "  Successful: " (:successful stats)))
    (println (str "  Failed: " (:failed stats)))
    (println (str "  Success rate: " (str (double (* (:success_rate stats) 100)) "%")))
    (println (str "  Avg time to production: " (double (:avg_deployment_time stats)) "s"))

    ;; Check SLA (99.5%)
    (when (< (:success_rate stats) 0.995)
      (println "\n🚨 CRITICAL: Deployment success rate below SLA (99.5%)")
      (println (str "  Current: " (str (double (* (:success_rate stats) 100)) "%"))))

    ;; Format metrics
    (let [output (str
                   ;; Success rate and counts
                   (format-prometheus-metric "deployment_success_rate" (:success_rate stats) labels timestamp)
                   (format-prometheus-metric "deployment_total" (:total stats) labels timestamp)
                   (format-prometheus-metric "deployment_success_total" (:successful stats) labels timestamp)
                   (format-prometheus-metric "deployment_failure_total" (:failed stats) labels timestamp)

                   ;; Time to production
                   (format-prometheus-metric "time_to_production" (:avg_deployment_time stats) labels timestamp)

                   ;; SLA status
                   (format-prometheus-metric "deployment_sla_status"
                                            (if (>= (:success_rate stats) 0.995) 1 0)
                                            labels timestamp)

                   ;; Collection timestamp
                   (format-prometheus-metric "deployment_metrics_collected_timestamp" timestamp labels timestamp)

                   ;; Rollback counter (initialize if not exists)
                   (format-prometheus-metric "rollback_count" 0 labels timestamp))]

      ;; Write to file
      (fs/writeFileSync (:metrics-file config) output)
      (println (str "\n✅ Metrics written to " (:metrics-file config)))

      ;; JSON output for CI logging
      (let [json-output {:timestamp now
                        :metrics stats
                        :sla_target 0.995
                        :sla_status (if (>= (:success_rate stats) 0.995) "passing" "failing")
                        :status "success"}]
        (println "\n📊 Deployment Metrics Summary:")
        (println (json-write json-output))

        ;; Also output success_rate for GitHub Actions
        (println (str "\nsuccess_rate=" (:success_rate stats)))))))

;; Run collection
(try
  (collect-metrics)
  (println "\n✅ Deployment metrics collection completed")
  (catch js/Error e
    (println (str "\n❌ Error: " (.-message e)))
    (js/process.exit 1)))
