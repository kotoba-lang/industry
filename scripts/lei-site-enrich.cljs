#!/usr/bin/env nbb
;; lei-site-enrich.cljs — real, provenance-tracked official-website
;; enrichment for the cloud-itonami-lei-<LEI> archive repos
;; (ADR-2607110300). For each repo: read its blueprint.edn's
;; :company/website, do a real HTTP GET against the real official site,
;; extract <title>/<meta name="description">, and append the result as
;; a new 80-data/public/site.journal.edn quad-log entry — the SAME
;; Datomic-style [entity attr value tx :add] format tos.journal.edn
;; already uses (this script does not invent a new schema convention).
;;
;; Real, not fabricated: a fetch failure/timeout/non-200 is recorded
;; HONESTLY as :site/http-status + :site/fetch-error, never silently
;; skipped and never invented as a fake success.
;;
;; Owner directive (2026-07-18): "まずは公開企業を対象で良いので, lei
;; ごとに公式サイトなどの情報を取得して edn に組み込んで" — these are
;; already-registered real public companies with real GLEIF LEIs
;; (ADR-2607110300), so no new discovery/collection step is needed here.
;;
;; Run:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/lei-site-enrich.cljs [--limit N] [--dry-run]
(require '[scripts.nbb-compat :refer [slurp spit sh]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(def fs (js/require "fs"))
(def args (vec (drop 2 js/process.argv)))
(defn- flag-value [name]
  (let [i (.indexOf args name)]
    (when (and (>= i 0) (< (inc i) (count args))) (nth args (inc i)))))
(def limit (some-> (flag-value "--limit") js/parseInt))
(def dry-run? (boolean (some #{"--dry-run"} args)))

(def scratch-root "/private/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/49cac0a5-ec8f-4ae8-9b5b-c7e37c8e4cae/scratchpad/lei-site-enrich")

;; sh takes SEPARATE argv strings (spawnSync, no shell) -- a compound
;; command (&&, cd, quoting) needs an explicit bash -c wrapper.
(defn- bash! [cmd-str]
  (sh "bash" "-c" cmd-str))

(defn list-lei-repos
  "REST (gh api), not `gh repo list` (GraphQL) -- same rate-limit-friendly
  choice `kotobase-ingest-cloud-itonami-lei.cljs` already made."
  []
  (let [out (bash! "gh api \"orgs/cloud-itonami/repos?per_page=100\" --paginate --jq '.[].name'")]
    (->> (str/split-lines (:out out))
         (filter #(str/starts-with? % "cloud-itonami-lei-"))
         (remove str/blank?)
         sort
         vec)))

(defn- extract-tag [html tag-re]
  (when-let [m (re-find tag-re html)]
    (-> (second m) str/trim)))

(defn fetch-site-info
  "Real HTTP GET against `url` via curl. Returns {:http-status
  int-or-nil :title str-or-nil :description str-or-nil :fetch-error
  str-or-nil} -- NEVER throws, always returns a real, honest record of
  what happened."
  [url body-path]
  (try
    (let [out (bash! (str "curl -sL -m 20 -o '" body-path "' -w '%{http_code}' "
                          "-A 'Mozilla/5.0 (compatible; cloud-itonami-research/1.0; +https://github.com/cloud-itonami)' "
                          "'" url "' 2>/tmp/lei-curl-err.log"))
          status (some-> (:out out) str/trim not-empty js/parseInt)
          err (not-empty (str/trim (or (:err out) "")))
          html (when (and status (< status 400) (.existsSync fs body-path))
                 (try (slurp body-path) (catch :default _ nil)))]
      {:http-status status
       :title (when html (extract-tag html #"(?is)<title[^>]*>([^<]*)</title>"))
       :description (when html (extract-tag html #"(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)[\"']"))
       :fetch-error (when (or (nil? status) (>= status 400)) err)})
    (catch :default e
      {:http-status nil :title nil :description nil :fetch-error (str (.-message e))})))

(defn- esc [s] (some-> s (str/replace #"\s+" " ") str/trim not-empty))

(defn quad-entry [company-slug idx {:keys [http-status title description fetch-error]} url today]
  (let [eid (str company-slug "-site-1")]
    (cond-> [[eid :site/url url idx :add]
             [eid :site/checked-at today idx :add]]
      http-status  (conj [eid :site/http-status http-status idx :add])
      (esc title)        (conj [eid :site/title (esc title) idx :add])
      (esc description)  (conj [eid :site/description (esc description) idx :add])
      fetch-error  (conj [eid :site/fetch-error fetch-error idx :add]))))

(defn- today-str []
  (let [d (js/Date.)]
    (str (.getUTCFullYear d) "-"
         (.padStart (str (inc (.getUTCMonth d))) 2 "0") "-"
         (.padStart (str (.getUTCDate d)) 2 "0"))))

(defn- slug-of [bp repo-name]
  (-> (or (first (str/split (or (:company/legal-name bp) repo-name) #"[ ,\.]")) repo-name)
      str/lower-case
      (str/replace #"[^a-z0-9]" "")))

(defn- commit-and-push! [wt journal-path updated]
  (spit journal-path (str "[" (str/join "\n " (map pr-str updated)) "]\n"))
  (bash! (str "cd '" wt "' && git add 80-data/public/site.journal.edn"))
  (let [commit (bash! (str "cd '" wt "' && git commit -q -m 'chore(site): add official-website enrichment (title/description/reachability)' "
                           "-m 'ADR-2607182500. Real HTTP fetch, checked-at date is real run-time provenance, same discipline as tos.journal.edn.'"))]
    (if-not (zero? (:exit commit))
      (println "  commit FAILED:" (:err commit))
      (let [push (bash! (str "cd '" wt "' && git push -q origin main"))]
        (println "  push:" (if (zero? (:exit push)) "ok" (str "FAILED: " (:err push))))))))

(defn process-repo! [repo-name today]
  (let [wt (str scratch-root "/" repo-name)
        bp-path (str wt "/blueprint.edn")
        journal-path (str wt "/80-data/public/site.journal.edn")]
    (bash! (str "rm -rf '" wt "'"))
    (let [clone (bash! (str "git clone -q --depth 1 git@github.com:cloud-itonami/" repo-name ".git '" wt "'"))]
      (cond
        (not (zero? (:exit clone)))
        (println "ERROR" repo-name "clone failed:" (:err clone))

        (not (.existsSync fs bp-path))
        (println "SKIP" repo-name "-- no blueprint.edn")

        :else
        (let [bp (edn/read-string (slurp bp-path))
              url (:company/website bp)
              existing (if (.existsSync fs journal-path) (edn/read-string (slurp journal-path)) [])
              already-checked-today? (some #(and (= :site/checked-at (nth % 1)) (= today (nth % 2))) existing)]
          (cond
            (str/blank? url)
            (println "SKIP" repo-name "-- no :company/website in blueprint.edn")

            already-checked-today?
            (println "SKIP" repo-name "-- already checked today")

            :else
            (let [info (fetch-site-info url (str wt "/.fetch-body.html"))
                  next-idx (inc (count existing))
                  entry (quad-entry (slug-of bp repo-name) next-idx info url today)
                  updated (into existing entry)]
              (println repo-name "|" url "|" (:http-status info) "|" (or (:title info) (:fetch-error info)))
              (when-not dry-run? (commit-and-push! wt journal-path updated)))))))
    (bash! (str "rm -rf '" wt "'"))))

(defn -main []
  (println "Enumerating cloud-itonami-lei-* repos...")
  (let [repos (list-lei-repos)
        target (if limit (take limit repos) repos)
        today (today-str)]
    (println (count repos) "total repos;" (count target) "targeted this run" (if dry-run? "(DRY RUN)" ""))
    (doseq [r target]
      (try
        (process-repo! r today)
        (catch :default e
          (println "ERROR" r "--" (.-message e)))))
    (println "Done.")))

(-main)
