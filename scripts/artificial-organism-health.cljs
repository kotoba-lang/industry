#!/usr/bin/env nbb
(require '[scripts.nbb-compat :as io :refer [slurp spit]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def topology-path "80-data/system/artificial-organism-fabric.edn")
(def output-path "80-data/system/artificial-organism-health.edn")
;; NOTE (2026-08-13, ADR-2608137200): this used to write into
;; `orgs/gftdcojp/cloud-murakumo`, a west-UNDECLARED duplicate checkout of the
;; same GitHub repository (id 1285031835) that west declares at
;; `orgs/network-awai/cloud-murakumo`. The old path publishes nothing — it is a
;; leftover of the org transfer. Keep the directory derived from the file path
;; so the two can never drift apart again.
(def public-output-dir "orgs/network-awai/cloud-murakumo/public/health")
(def public-output-path (str public-output-dir "/fabric.json"))
(def release-receipt-path "80-data/system/cloud-murakumo-release.json")

(defn sh [& args]
  (try (apply io/sh args)
       (catch :default e {:exit -1 :out "" :err (str e)})))

(defn parse-args [xs]
  {:live? (boolean (some #{"--live" "live"} xs))
   :strict? (boolean (some #{"--strict" "strict"} xs))})

(defn repo-status [path]
  (let [exists? (.existsSync (js/require "fs") path)
        status (when exists? (sh "git" "-C" path "status" "--porcelain=v1" "--branch" "--untracked-files=no"))
        git? (zero? (or (:exit status) 1))
        lines (when git? (str/split-lines (:out status)))
        dirty (boolean (seq (remove #(str/starts-with? % "## ") lines)))]
    {:repo/path path :repo/exists? exists? :repo/git? git?
     :repo/head nil :repo/dirty? dirty}))

(defn live-probe [{:keys [id url]}]
  (let [r (sh "curl" "-LfsS" "--max-time" "3" "-o" "/dev/null"
              "-w" "%{http_code}" url)]
    {:probe/id id :probe/url url :probe/ok? (and (zero? (:exit r))
                                                  (str/starts-with? (:out r) "2"))
     :probe/http (str/trim (:out r))}))

(defn get-json [url]
  (let [r (sh "curl" "-LfsS" "--max-time" "5" url)]
    (when (zero? (:exit r))
      (try (js->clj (.parse js/JSON (:out r)) :keywordize-keys true)
           (catch :default _ nil)))))

(defn -main [& args]
  (println "collecting artificial-organism fabric health")
  (let [{:keys [live? strict?]} (parse-args args)
        topology (edn/read-string (slurp topology-path))
        repo-paths (distinct (concat (mapcat :repos (:layers topology))
                                     (map :repo (:products topology))))
        repos (mapv repo-status repo-paths)
        probes (if live? (mapv live-probe (get-in topology [:public-health :live-probes])) [])
        missing (filterv (complement :repo/exists?) repos)
        nongit (filterv #(and (:repo/exists? %) (not (:repo/git? %))) repos)
        dirty-repos (filterv :repo/dirty? repos)
        failed-probes (filterv (complement :probe/ok?) probes)
        activity (get-in topology [:public-health :activity])
        ci-state (when live? (get-json (get-in activity [:ci :state-url])))
        spend-state (when live? (get-json (get-in activity [:spend :stats-url])))
        release-receipt (when (.existsSync (js/require "fs") release-receipt-path)
                          (try (js->clj (.parse js/JSON (slurp release-receipt-path)) :keywordize-keys true)
                               (catch :default _ nil)))
        release-isolated? (and (= "git-archive-plus-content-addressed-allowlist"
                                  (:isolation release-receipt))
                               (= 0 (get-in release-receipt [:result :status])))
        healthy? (and (empty? missing) (empty? nongit) release-isolated?
                      (empty? failed-probes))
        report {:health/schema 1
                :health/fabric (:fabric/id topology)
                :health/generated-at (.toISOString (js/Date.))
                :health/status (if healthy? :healthy :degraded)
                :health/summary {:repos (count repos)
                                 :missing (count missing)
                                 :non-git (count nongit)
                                 :dirty (count dirty-repos)
                                 :release-isolated release-isolated?
                                 :live-probes (count probes)
                                 :failed-probes (count failed-probes)}
                :health/repos repos :health/probes probes}
        public-report {"schema" 1
                       "fabric" (name (:fabric/id topology))
                       "generatedAt" (:health/generated-at report)
                       "status" (name (:health/status report))
                       "summary" (into {} (map (fn [[k v]] [(name k) v]) (:health/summary report)))
                       "probes" (mapv (fn [probe]
                                        {"id" (name (:probe/id probe))
                                         "url" (:probe/url probe)
                                         "ok" (:probe/ok? probe)
                                         "http" (:probe/http probe)}) probes)
                       "gaps" (mapv (fn [gap]
                                      {"id" (name (:gap/id gap))
                                       "severity" (name (:severity gap))
                                       "status" (name (:status gap))}) (:gaps topology))
                       "activity" {"ci" {"eventId" (get-in activity [:ci :event-id])
                                           "status" (or (:status ci-state) "unavailable")
                                           "testsPassed" (boolean (:testsPassed ci-state))
                                           "canaryPromoted" (boolean (:canaryPromoted ci-state))}
                                    "runtime" {"healthy" (- (count probes) (count failed-probes))
                                               "total" (count probes)}
                                    "spend" {"settlements" (or (get-in spend-state [:settlements :count]) 0)
                                             "usdTotal" (or (get-in spend-state [:settlements :usd-total]) 0)
                                             "source" (get-in activity [:spend :stats-url])}
                                    "posts" {"count" (get-in activity [:posts :count])
                                             "latestUri" (get-in activity [:posts :latest-uri])
                                             "latestCid" (get-in activity [:posts :latest-cid])}}
                       "rollback" {"strategy" "last-signed-content-addressed-head"
                                   "codeHost" (get-in topology [:kaizen :code-host])
                                   "head" (:sha ci-state)
                                   "canaryRef" (:canaryRef ci-state)}}]
    (spit output-path (str (pr-str report) "\n"))
    (.mkdirSync (js/require "fs") public-output-dir #js {:recursive true})
    (spit public-output-path (str (.stringify js/JSON (clj->js public-report) nil 2) "\n"))
    (println (str "artificial-organism fabric: " (name (:health/status report))
                  ", repos=" (count repos) ", missing=" (count missing)
                  ", dirty=" (get-in report [:health/summary :dirty])
                  ", failed-probes=" (count failed-probes)))
    (when (and strict? (not healthy?)) (io/exit 1))))

(apply -main *command-line-args*)
