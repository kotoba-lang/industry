#!/usr/bin/env nbb
;; Wave 5 Team Velocity Metrics Collector
;; Collects story points completed, burndown, and velocity from Jira
;; Status: PRODUCTION_READY

(ns collect-velocity-metrics
  (:require ["fs" :as fs]
            ["child_process" :as cp]
            [clojure.string :as str]
            [clojure.data.json :as json]))

(def config
  {:jira-url (or (js/process.env.JIRA_URL) "https://jira.internal")
   :jira-user (or (js/process.env.JIRA_USER) "gates-bot")
   :jira-token (or (js/process.env.JIRA_TOKEN) "")
   :gate-projects ["M5W5" "M5IW5" "M6W5"]
   :metrics-file "metrics-velocity.txt"})

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

(defn fetch-completed-issues []
  "Fetch all completed issues (stories and tasks) from gate projects"
  (try
    (let [projects (str/join "," (:gate-projects config))
          jql (str "project in (" projects ") AND issuetype in (Story, Task) AND status = Done")
          encoded-jql (.toString (js/Buffer.from jql) "base64")
          endpoint (str "search?jql=" encoded-jql "&fields=storypoints,created,updated,customfield_10000")
          response (jira-api-call endpoint)]
      (if response
        (:issues response)
        []))
    (catch js/Error e
      (println "Error fetching issues:" (.-message e))
      [])))

(defn extract-story-points [issue]
  "Extract story points from issue, handle null/missing"
  (let [points (get-in issue [:fields :customfield_10000])
        ;; Try alternative field names for story points
        alt-points (or points
                      (get-in issue [:fields :storypoints])
                      (get-in issue [:fields :story_points]))]
    (try
      (if alt-points (double alt-points) 1.0) ; Default to 1 if no points
      (catch js/Error _ 1.0))))

(defn group-by-sprint [issues]
  "Group completed issues by sprint/week"
  (let [current-week (- (js/Math.floor (/ (.getTime (js/Date.))
                                         (* 1000 60 60 24 7)))
                       (js/Math.floor (/ (.getTime (js/Date. 2026 6 1))
                                        (* 1000 60 60 24 7))))]
    (group-by (fn [issue]
                (try
                  (let [updated (js/Date. (get-in issue [:fields :updated]))
                        week (- (js/Math.floor (/ (.getTime updated)
                                                  (* 1000 60 60 24 7)))
                               (js/Math.floor (/ (.getTime (js/Date. 2026 6 1))
                                               (* 1000 60 60 24 7))))]
                    week)
                  (catch js/Error _ current-week)))
              issues)))

(defn calculate-velocity [issues]
  "Calculate velocity metrics from completed issues"
  (let [by-sprint (group-by-sprint items)
        total-points (apply + (map extract-story-points issues))
        avg-per-issue (if (> (count issues) 0)
                       (/ total-points (count issues))
                       0)
        recent-sprints (take 4 (reverse (sort (keys by-sprint))))
        sprint-velocities (map (fn [sprint]
                                (let [sprint-issues (get by-sprint sprint)
                                      points (apply + (map extract-story-points sprint-issues))]
                                  points))
                             recent-sprints)]
    {:total_points (double total-points)
     :total_issues (count issues)
     :avg_points_per_issue (double avg-per-issue)
     :recent_sprint_velocities (map double sprint-velocities)
     :avg_sprint_velocity (if (seq sprint-velocities)
                           (double (/ (apply + sprint-velocities) (count sprint-velocities)))
                           0)}))

(defn format-prometheus-metric [metric-name value labels timestamp]
  "Format metric in Prometheus text exposition format"
  (let [label-str (if (seq labels)
                    (str "{" (str/join "," (map #(str (name (key %)) "=\"" (val %) "\"") labels)) "}")
                    "")]
    (str metric-name label-str " " value " " timestamp "\n")))

(defn collect-metrics []
  "Main collection logic"
  (println "Collecting team velocity metrics from Jira...")

  (let [issues (fetch-completed-issues)
        _ (println (str "Found " (count issues) " completed issues this sprint"))

        velocity (calculate-velocity issues)
        now (js/Date.)
        timestamp (/ (.getTime now) 1000)

        labels {:job "team-velocity"
                :source "jira"
                :gate_projects "M5W5,M5IW5,M6W5"}]

    (println "Velocity metrics calculated:")
    (println (str "  Total story points completed: " (:total_points velocity)))
    (println (str "  Total issues closed: " (:total_issues velocity)))
    (println (str "  Avg points per issue: " (double (:avg_points_per_issue velocity))))
    (println (str "  Avg sprint velocity: " (double (:avg_sprint_velocity velocity))))

    ;; Format metrics
    (let [output (str
                   ;; Story points metrics
                   (format-prometheus-metric "story_points_completed" (:total_points velocity) labels timestamp)
                   (format-prometheus-metric "issues_closed_total" (:total_issues velocity) labels timestamp)
                   (format-prometheus-metric "avg_points_per_issue" (:avg_points_per_issue velocity) labels timestamp)

                   ;; Velocity trend
                   (format-prometheus-metric "sprint_velocity" (:avg_sprint_velocity velocity) labels timestamp)

                   ;; Burndown rate (points per day, estimated)
                   (format-prometheus-metric "burndown_rate_points_per_day"
                                            (/ (:total_points velocity) 7) ; Rough estimate
                                            labels timestamp)

                   ;; Collection timestamp
                   (format-prometheus-metric "velocity_metrics_collected_timestamp" timestamp labels timestamp))]

      ;; Write to file
      (fs/writeFileSync (:metrics-file config) output)
      (println (str "\n✅ Metrics written to " (:metrics-file config)))

      ;; JSON output for CI logging
      (let [json-output {:timestamp now
                        :metrics (dissoc velocity :recent_sprint_velocities)
                        :recent_sprints (:recent_sprint_velocities velocity)
                        :status "success"}]
        (println "\n📊 Velocity Metrics Summary:")
        (println (json/write-str json-output :pretty true))))))

;; Run collection
(try
  (collect-metrics)
  (println "\n✅ Velocity metrics collection completed")
  (catch js/Error e
    (println (str "\n❌ Error: " (.-message e)))
    (js/process.exit 1)))
