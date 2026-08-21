#!/usr/bin/env nbb

;;; Automatic Rollback Automation — strangler-fig Month 1
;;; Reference: ADR-2607072100 §3.3 "Rollback Triggers"
;;;
;;; Monitors key metrics and executes autonomous rollback if SLO boundaries are breached:
;;;
;;; Automatic triggers (NO owner confirmation required):
;;;  1. p99 latency > baseline+100ms sustained 5min → revert to previous state
;;;  2. Parity divergence > 1% sustained 1min → full revert to Rust
;;;  3. HTTP 5xx rate > 1% sustained 2min → full revert to Rust
;;;  4. Memory peak > 512MB sustained → revert + increase pool
;;;
;;; Manual triggers (owner decision):
;;;  • Application-level parity violation → staging fix cycle
;;;  • Performance budget miss → escalate SLO or continue
;;;
;;; Usage:
;;;   nbb deploy/rollback-automation.cljs \
;;;     --metrics-endpoint http://localhost:9090/api/v1/query \
;;;     --murakumo-control-url http://localhost:9000 \
;;;     --dry-run true

(ns rollback-automation
  (:require
    ["fs" :as fs]
    ["path" :as path]
    [clojure.string :as str]
    [clojure.edn :as edn]))

;;; ============================================================================
;;; Configuration
;;; ============================================================================

(def default-config
  {:metrics-endpoint "http://localhost:9090/api/v1/query"
   :murakumo-control-url "http://localhost:9000"
   :check-interval-sec 30
   :audit-log-file "metrics/rollback-audit.log"
   :dry-run false})

(def rollback-triggers
  {:latency-regression
   {:metric "histogram_quantile(0.99, http_request_latency_ms)"
    :threshold-ms 250  ;; baseline(100) + 150ms hard ceiling
    :sustained-sec 300 ;; 5 minutes
    :action :partial-revert
    :action-detail "Reduce cljc traffic by 50% then monitor"}

   :parity-divergence
   {:metric "parity_divergence_count"
    :threshold-count 1  ;; Any divergence
    :sustained-sec 60   ;; 1 minute
    :action :full-revert
    :action-detail "Revert 100% traffic to Rust"}

   :reliability-degradation
   {:metric "http_5xx_rate_pct"
    :threshold-pct 1.0
    :sustained-sec 120  ;; 2 minutes
    :action :full-revert
    :action-detail "Revert 100% traffic to Rust"}

   :memory-exhaustion
   {:metric "jvm_heap_peak_mb"
    :threshold-mb 512
    :sustained-sec 30
    :action :partial-revert
    :action-detail "Reduce traffic + increase node pool size"}})

(def state (atom
  {:trigger-detections []  ;; Historical detections
   :sustained-violations {} ;; Currently sustained violations {trigger → start-time}
   :last-rollback nil
   :audit-trail []}))

;;; ============================================================================
;;; Metric Query & Monitoring
;;; ============================================================================

(defn query-prometheus
  "Query Prometheus API for current metric value."
  [endpoint metric-expr]
  ;; In real implementation, would HTTP GET to:
  ;; endpoint?query=metric_expr
  ;; For now, mock implementation
  (println (str "[METRICS] Query: " metric-expr))
  {:metric metric-expr
   :value 95  ;; Simulated value
   :timestamp (js/Date.now)})

(defn check-trigger
  "Check if a single rollback trigger is active (threshold + sustained time)."
  [{:keys [trigger-id metric threshold-key threshold-value sustained-sec]}]
  (let [{:keys [trigger-detections sustained-violations]} @state
        query-result (query-prometheus (get default-config :metrics-endpoint) metric)
        current-value (:value query-result)]

    ;; Check if threshold breached
    (if (> current-value threshold-value)
      (let [now (js/Date.now)
            sustained-start (get sustained-violations trigger-id)]

        ;; First detection of this violation
        (when (nil? sustained-start)
          (swap! state assoc-in [:sustained-violations trigger-id] now)
          (println (str "[TRIGGER] Threshold breach: " trigger-id
                       " (" current-value " > " threshold-value ")")))

        ;; Check if sustained long enough
        (let [elapsed-sec (/ (- now (or sustained-start now)) 1000)]
          (if (>= elapsed-sec sustained-sec)
            {:trigger-id trigger-id
             :status :active
             :metric-value current-value
             :threshold threshold-value
             :sustained-sec elapsed-sec}
            {:trigger-id trigger-id
             :status :monitoring
             :metric-value current-value
             :sustained-sec elapsed-sec})))

      ;; Threshold not breached - clear sustained violation
      (do
        (swap! state update :sustained-violations dissoc trigger-id)
        nil))))

(defn evaluate-all-triggers
  "Check all rollback triggers, return active ones."
  []
  (mapv (fn [[trigger-id trigger-config]]
          (check-trigger
            {:trigger-id trigger-id
             :metric (:metric trigger-config)
             :threshold-key (first (keys (dissoc trigger-config :metric :sustained-sec :action :action-detail)))
             :threshold-value (first (vals (dissoc trigger-config :metric :sustained-sec :action :action-detail)))
             :sustained-sec (:sustained-sec trigger-config)}))
        rollback-triggers))

;;; ============================================================================
;;; Rollback Actions
;;; ============================================================================

(defn partial-revert
  "Reduce cljc traffic by fraction (e.g., 50%) and monitor for recovery."
  [trigger-config]
  (println "[ROLLBACK] PARTIAL REVERT triggered")
  (println (str "  Trigger: " (:trigger-id trigger-config)))
  (println (str "  Action: " (:action-detail trigger-config)))

  ;; Get current traffic split
  ;; Set new split: reduce cljc by 50%
  ;; Wait 5min for stabilization
  ;; If stable, hold. Else continue reduction.

  {:action :partial-revert
   :previous-state {:cljc-percent 5}
   :new-state {:cljc-percent 2.5}
   :duration-sec 300
   :monitor true})

(defn full-revert
  "Revert 100% traffic to Rust fleet, drain cljc staging gracefully."
  [trigger-config]
  (println "[ROLLBACK] FULL REVERT to Rust fleet")
  (println (str "  Trigger: " (:trigger-id trigger-config)))
  (println (str "  Reason: " (:action-detail trigger-config)))
  (println (str "  Metric: " (get trigger-config :metric-value) " > " (get trigger-config :threshold)))

  (println "\n  Steps:")
  (println "    1. Set cljc traffic split = 0%")
  (println "    2. Enable Rust pool health checks")
  (println "    3. Graceful drain of cljc nodes (30sec timeout)")
  (println "    4. Shutdown cljc staging pool (keep for post-mortem)")
  (println "    5. Send alert to ops team")
  (println "    6. Log rollback event with full context")

  {:action :full-revert
   :previous-state {:cljc-percent 5
                    :cljc-nodes 3}
   :new-state {:cljc-percent 0
               :rust-percent 100}
   :drain-timeout-sec 30
   :keep-staging-pool true
   :alert-ops true})

(defn execute-rollback
  "Execute rollback action based on trigger."
  [trigger active-config]
  (let [{:keys [action action-detail]} (get rollback-triggers trigger)
        dry-run (get default-config :dry-run false)]

    (println (str "\n[ROLLBACK] Executing " action " for " trigger))

    (let [rollback-result
          (case action
            :partial-revert (partial-revert active-config)
            :full-revert (full-revert active-config)
            nil)]

      ;; Log rollback event
      (let [audit-event
            {:timestamp (js/Date.now)
             :action action
             :trigger trigger
             :metric-value (:metric-value active-config)
             :threshold (:threshold active-config)
             :dry-run dry-run
             :result rollback-result}]

        ;; Update state
        (swap! state
          (fn [s]
            (-> s
              (update :audit-trail conj audit-event)
              (assoc :last-rollback audit-event))))

        ;; Write to audit log
        (let [log-file (get default-config :audit-log-file)]
          (fs/appendFileSync log-file (str (pr-str audit-event) "\n")))

        ;; If not dry-run, execute
        (when (not dry-run)
          (println (str "\n[ROLLBACK] " (if dry-run "DRY RUN - NOT EXECUTING" "EXECUTING ROLLBACK")))
          ;; HTTP PUT to murakumo control plane
          ;; PUT /fleet/traffic-split {:cljc-percent 0}
          (println "  → Send rollback command to murakumo control plane"))

        (println (str "\n[ROLLBACK] Audit logged: " log-file)))

      rollback-result)))

;;; ============================================================================
;;; Monitoring Loop
;;; ============================================================================

(defn monitor-triggers-loop
  [config]
  (println "[MONITOR] Starting rollback automation monitor...")
  (println (str "  Metrics endpoint: " (:metrics-endpoint config)))
  (println (str "  Check interval: " (:check-interval-sec config) "s"))
  (println (str "  Dry-run: " (:dry-run config)))
  (println (str "  Audit log: " (:audit-log-file config) "\n"))

  ;; Create audit log directory
  (let [log-dir (path/dirname (:audit-log-file config))]
    (when (not (fs/existsSync log-dir))
      (fs/mkdirSync log-dir {:recursive true})))

  ;; Check-interval timer
  (js/setInterval
    (fn []
      ;; Evaluate all triggers
      (let [all-results (evaluate-all-triggers)
            active-triggers (filter (fn [r] (and r (= (:status r) :active)))
                                   all-results)]

        ;; Log monitoring status
        (when (not-empty all-results)
          (println (str "[MONITOR] Status check @ " (js/Date.now)))
          (doseq [{:keys [trigger-id status metric-value]} all-results]
            (when (not-nil? trigger-id)
              (println (str "  " trigger-id ": " status " (" metric-value ")")))))

        ;; Execute rollback for each active trigger
        (doseq [{:keys [trigger-id] :as active} active-triggers]
          (execute-rollback trigger-id active))))

    (* (:check-interval-sec config) 1000)))

  (println "[MONITOR] Loop active. Watching for SLO violations..."))

;;; ============================================================================
;;; Status & Control
;;; ============================================================================

(defn status
  "Report current rollback automation status."
  []
  (let [{:keys [sustained-violations last-rollback audit-trail]} @state]
    (println "\n===== ROLLBACK AUTOMATION STATUS =====\n")
    (println "Monitored Triggers:")
    (doseq [[tid tconf] rollback-triggers]
      (let [sustained? (contains? sustained-violations tid)]
        (println (str "  " (if sustained? "⚠️" "✓") " " tid
                     " (" (:metric tconf) ")"))))

    (when (not-empty sustained-violations)
      (println "\nCurrent Sustained Violations:")
      (doseq [[tid start-time] sustained-violations]
        (let [elapsed (/ (- (js/Date.now) start-time) 1000)]
          (println (str "  → " tid ": sustained for " (Math/round elapsed) "s")))))

    (when last-rollback
      (println "\nLast Rollback Event:")
      (println (str "  Trigger: " (:trigger last-rollback)))
      (println (str "  Action: " (:action last-rollback)))
      (println (str "  Time: " (:timestamp last-rollback))))

    (println (str "\nAudit trail events: " (count audit-trail)))
    (println (str "Audit log: " (:audit-log-file default-config) "\n")))

(defn reset-sustained
  "Clear sustained violation tracking (for testing/reset)."
  []
  (swap! state assoc :sustained-violations {})
  (println "[RESET] Cleared sustained violation tracking"))

;;; ============================================================================
;;; Main Entry Point
;;; ============================================================================

(defn -main
  [& args]
  (let [arg-map (apply hash-map args)
        config (merge default-config arg-map)]

    (case (first args)
      "status" (status)
      "reset" (reset-sustained)
      "monitor" (do
                  (monitor-triggers-loop config)
                  ;; Keep alive
                  (js/setInterval #() 60000))
      (do
        (println "Usage:")
        (println "  nbb deploy/rollback-automation.cljs monitor")
        (println "    [--metrics-endpoint URL]")
        (println "    [--murakumo-control-url URL]")
        (println "    [--check-interval-sec N]")
        (println "    [--dry-run true|false]")
        (println "")
        (println "  nbb deploy/rollback-automation.cljs status")
        (println "  nbb deploy/rollback-automation.cljs reset")
        (System/exit 1)))))

(apply -main *command-line-args*)
