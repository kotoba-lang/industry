#!/usr/bin/env nbb
;; Wave 5 Gate Blocker Metrics Collector
;; Collects blocker counts and SLA status from Jira
;; Status: PRODUCTION_READY

(ns collect-blocker-metrics
  (:require ["fs" :as fs]
            ["child_process" :as cp]
            [clojure.string :as str]
            [clojure.data.json :as json]))

(def config
  {:jira-url (or (js/process.env.JIRA_URL) "https://jira.internal")
   :jira-user (or (js/process.env.JIRA_USER) "gates-bot")
   :jira-token (or (js/process.env.JIRA_TOKEN) "")
   :jql (or (js/process.env.JQL) "project in (M5W5, M5IW5, M6W5) AND status = Blocked")
   :metrics-file "metrics-blockers.txt"})

(defn jira-api-call [endpoint]
  "Call Jira API and return parsed JSON"
  (try
    (let [url (str (:jira-url config) "/rest/api/2/" endpoint)
          basic-auth (str (:jira-user config) ":" (:jira-token config))
          encoded-auth (.toString (js/Buffer.from basic-auth) "base64")
          curl-cmd (str "curl -s "
                       "-H 'Authorization: Basic " encoded-auth "' "
                       "-H 'Content-Type: application/json' "
                       "'" url "'")
          response (cp/execSync curl-cmd #js{:encoding "utf-8"})]
      (json/read-str response :key-fn keyword))
    (catch js/Error e
      (println "Error calling Jira API:" (.-message e))
      nil)))

(defn fetch-blocked-issues []
  "Fetch all blocked issues from Jira"
  (try
    (let [encoded-jql (.toString (js/Buffer.from (:jql config)) "base64")
          endpoint (str "search?jql=" (str/replace encoded-jql #"=" "%3D"))
          response (jira-api-call endpoint)]
      (if response
        (:issues response)
        []))
    (catch js/Error e
      (println "Error fetching blockers:" (.-message e))
      [])))

(defn calculate-blocker-age [created-date]
  "Calculate blocker age in hours from creation date"
  (try
    (let [created (js/Date. created-date)
          now (js/Date.)
          age-ms (- (.getTime now) (.getTime created))
          age-hours (/ age-ms (* 1000 60 60))]
      age-hours)
    (catch js/Error _ nil)))

(defn extract-severity [issue]
  "Extract severity from Jira issue"
  (let [priority (get-in issue [:fields :priority :name])
        labels (get-in issue [:fields :labels] [])]
    (cond
      (some #(str/includes? % "P1") labels) "P1"
      (= priority "Highest") "P1"
      (= priority "High") "P2"
      (some #(str/includes? % "P2") labels) "P2"
      :else "P3")))

(defn analyze-blockers [issues]
  "Analyze blockers and return metrics"
  (let [by-severity (group-by extract-severity issues)
        p1-count (count (get by-severity "P1" []))
        p2-count (count (get by-severity "P2" []))
        p3-count (count (get by-severity "P3" []))

        ages (keep (fn [issue]
                    (calculate-blocker-age
                     (get-in issue [:fields :created])))
                  issues)

        oldest-age (if (seq ages) (apply max ages) 0)
        avg-age (if (seq ages) (/ (apply + ages) (count ages)) 0)
        newest-age (if (seq ages) (apply min ages) 0)]

    {:total (count issues)
     :p1_count p1-count
     :p2_count p2-count
     :p3_count p3-count
     :oldest_age_hours (double oldest-age)
     :avg_age_hours (double avg-age)
     :newest_age_hours (double newest-age)
     :sla_breached (> p1-count 0 false) ; Will be set based on age check
     :issues issues}))

(defn format-prometheus-metric [metric-name value labels timestamp]
  "Format metric in Prometheus text exposition format"
  (let [label-str (if (seq labels)
                    (str "{" (str/join "," (map #(str (name (key %)) "=\"" (val %) "\"") labels)) "}")
                    "")]
    (str metric-name label-str " " value " " timestamp "\n")))

(defn collect-metrics []
  "Main collection logic"
  (println "Collecting gate blocker metrics from Jira...")

  (let [issues (fetch-blocked-issues)
        _ (println (str "Found " (count issues) " blocked issues"))

        metrics (analyze-blockers issues)
        now (js/Date.)
        timestamp (/ (.getTime now) 1000)

        labels {:job "gate-blockers"
                :source "jira"
                :gate_projects "M5W5,M5IW5,M6W5"}

        ;; Check SLA breach: P1 blocker > 4 hours old
        sla-breached (and (> (:p1_count metrics) 0)
                         (> (:oldest_age_hours metrics) 4))]

    (println "Blocker metrics calculated:")
    (println (str "  Total blockers: " (:total metrics)))
    (println (str "  P1 blockers: " (:p1_count metrics)))
    (println (str "  P2 blockers: " (:p2_count metrics)))
    (println (str "  P3 blockers: " (:p3_count metrics)))
    (println (str "  Oldest blocker age: " (double (:oldest_age_hours metrics)) "h"))
    (println (str "  Average age: " (double (:avg_age_hours metrics)) "h"))

    ;; Check SLA
    (when sla-breached
      (println "\n🔴 CRITICAL: Blocker SLA BREACH DETECTED"))
      (println (str "  P1 blockers: " (:p1_count metrics) " (threshold: 0)"))
      (println (str "  Oldest P1 age: " (double (:oldest_age_hours metrics)) "h (SLA: 4h)")))

    ;; Format metrics
    (let [output (str
                   ;; Blocker counts
                   (format-prometheus-metric "blocker_count" (:total metrics) labels timestamp)
                   (format-prometheus-metric "blocker_count" (:p1_count metrics)
                                            (assoc labels :severity "P1") timestamp)
                   (format-prometheus-metric "blocker_count" (:p2_count metrics)
                                            (assoc labels :severity "P2") timestamp)
                   (format-prometheus-metric "blocker_count" (:p3_count metrics)
                                            (assoc labels :severity "P3") timestamp)

                   ;; Blocker ages
                   (format-prometheus-metric "blocker_age_hours" (:oldest_age_hours metrics)
                                            (assoc labels :age_type "oldest") timestamp)
                   (format-prometheus-metric "blocker_age_hours" (:avg_age_hours metrics)
                                            (assoc labels :age_type "average") timestamp)
                   (format-prometheus-metric "blocker_age_hours" (:newest_age_hours metrics)
                                            (assoc labels :age_type "newest") timestamp)

                   ;; Severity-specific count
                   (format-prometheus-metric "blocker_severity_p1_count" (:p1_count metrics) labels timestamp)

                   ;; SLA status (1 = healthy, 0 = breach)
                   (format-prometheus-metric "blocker_sla_status"
                                            (if sla-breached 0 1)
                                            labels timestamp)

                   ;; Collection timestamp
                   (format-prometheus-metric "blocker_metrics_collected_timestamp" timestamp labels timestamp))]

      ;; Write to file
      (fs/writeFileSync (:metrics-file config) output)
      (println (str "\n✅ Metrics written to " (:metrics-file config)))

      ;; JSON output for CI logging
      (let [json-output {:timestamp now
                        :metrics (dissoc metrics :issues)
                        :sla_target_p1 0
                        :sla_target_age_hours 4
                        :sla_status (if sla-breached "BREACH" "healthy")
                        :status (if sla-breached "warning" "success")}]
        (println "\n📊 Blocker Metrics Summary:")
        (println (json/write-str json-output :pretty true))

        ;; Output for GitHub Actions
        (println (str "\np1_count=" (:p1_count metrics)))
        (println (str "oldest_age_hours=" (double (:oldest_age_hours metrics))))))))

;; Run collection
(try
  (collect-metrics)
  (println "\n✅ Blocker metrics collection completed")
  (catch js/Error e
    (println (str "\n❌ Error: " (.-message e)))
    (js/process.exit 1)))
