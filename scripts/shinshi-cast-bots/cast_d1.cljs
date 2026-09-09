(ns cast-d1
  "D1 access + roster derivation for the club-shinshi cast bots.

   All reads/writes go through `wrangler d1 execute --remote`, run with cwd =
   the club-shinshi appview directory (its wrangler.jsonc names both
   databases and the account). The appview lives in the shared west checkout
   and is used READ-ONLY as a working directory — nothing is written there.

   A query that could not be asked (wrangler missing, auth failure, network)
   returns nil, and callers must treat nil as :unanswered — never as an
   empty result (repo rule: a check that could not run must not look like a
   check that passed)."
  (:require [cast-core :as core]
            [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:os" :as os]
            ["node:path" :as path]))

(def gftd-root
  (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
      ;; legacy; `gftd` is retired (manifest/gftd-retirement.edn)
      (aget (.-env js/process) "GFTD_ROOT")
      (path/join (os/homedir) "github" "com-junkawasaki")))

(def appview-dir
  (or (aget (.-env js/process) "SHINSHI_APPVIEW_DIR")
      (path/join gftd-root "orgs" "network-awai" "club-shinshi-app"
                 "appview" "ai-gftd-wasm-shinshi-sh1n5h1x")))

;; npx --yes wrangler is broken with pass-through flags on this machine's npm
;; (see scripts/shinshi-catalog-video-loop.cljs:208) — prefer wrangler on PATH.
(def wrangler-direct?
  (zero? (or (.-status (cp/spawnSync "wrangler" #js ["--version"]
                                     #js {:encoding "utf8"})) 1)))

(defn- run-wrangler [args]
  (let [[cmd argv] (if wrangler-direct?
                     ["wrangler" args]
                     ["npx" (into ["--yes" "wrangler"] args)])
        r (cp/spawnSync cmd (clj->js argv)
                        #js {:encoding "utf8" :cwd appview-dir
                             :maxBuffer (* 64 1024 1024)})]
    {:status (.-status r) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn d1-query
  "-> vector of row maps (keyword keys), or nil when the question could not
   be asked. An empty vector means the database answered 'no rows'."
  [db sql]
  (let [{:keys [status out err]} (run-wrangler ["d1" "execute" db "--remote" "--json" "--command" sql])]
    (if-not (zero? status)
      (do (println (str "UNANSWERED d1 " db ": " (str/trim (str err))))
          nil)
      (try
        (let [parsed (js->clj (js/JSON.parse out) :keywordize-keys true)]
          (vec (mapcat :results parsed)))
        (catch :default e
          (println (str "UNANSWERED d1 " db ": unparseable output — " (.-message e)))
          nil)))))

(defn d1-exec-file!
  "Apply a SQL file. -> true on success, false otherwise (stderr printed)."
  [db file]
  (let [{:keys [status err]} (run-wrangler ["d1" "execute" db "--remote" "--file" file])]
    (or (zero? status)
        (do (println (str "D1-WRITE-FAILED " db ": " (str/trim (str err)))) false))))

(defn sql-escape [s] (str/replace (str s) "'" "''"))
(defn sql-in [xs] (str/join "," (map #(str "'" (sql-escape %) "'") xs)))

;; ── roster derivation ─────────────────────────────────────────────────────

(declare profiles)

(defn cast-members
  "-> [{:slug :series-key :series :scenes :profile}] one per original
   series, or nil when D1 was unreachable.

   Selection is deterministic given the catalog: per series take the
   candidates ordered by (most scenes, slug ascending); a candidate counts
   only if its RECORDLOG modelProfile says the SAME original series name —
   this is what excludes IP characters whose slug merely ends in an
   original suffix (measured 2026-08-29: tifa-final-fantasy matched
   '%-fantasy'). Across the 12 picks, a first name already cast is skipped
   while an unused one remains, so ties do not collapse the whole cast into
   one alphabetically-first character (measured: akari-hoshino-* won 11/12
   on slug-ascending ties)."
  []
  (let [like (str/join " OR " (map (fn [[k _]] (str "a.slug LIKE '%-" k "'")) core/series))
        rows (d1-query core/shinshi-db
                       (str "SELECT a.slug AS slug, COUNT(s.scene_id) AS scenes "
                            "FROM actress a LEFT JOIN scene s ON s.slug = a.slug "
                            "WHERE " like " GROUP BY a.slug"))]
    (when rows
      (let [top-per-series
            (mapv (fn [[k nm]]
                    (let [suffix (str "-" k)]
                      {:series-key k :series nm
                       :cands (->> rows
                                   (filter #(str/ends-with? (:slug %) suffix))
                                   (sort-by (juxt #(- (:scenes %)) :slug))
                                   (take 24) vec)}))
                  core/series)
            all-slugs (into [] (comp (mapcat :cands) (map :slug) (distinct))
                            top-per-series)
            profs (profiles all-slugs)]
        (when profs
          (-> (reduce
               (fn [{:keys [used out]} {:keys [series-key series cands]}]
                 (let [ok (filterv (fn [{:keys [slug]}]
                                     (= series (:series (get profs slug))))
                                   cands)
                       fresh (filterv (fn [{:keys [slug]}]
                                        (not (used (first (str/split slug #"-")))))
                                      ok)
                       pick (or (first fresh) (first ok))]
                   (if pick
                     {:used (conj used (first (str/split (:slug pick) #"-")))
                      :out (conj out {:slug (:slug pick) :series-key series-key
                                      :series series :scenes (:scenes pick)
                                      :profile (get profs (:slug pick))})}
                     {:used used :out out})))
               {:used #{} :out []}
               top-per-series)
              :out))))))

(defn profiles
  "slugs -> {slug profile-map} from RECORDLOG modelProfile records, or nil."
  [slugs]
  (let [rows (d1-query core/recordlog-db
                       (str "SELECT repo, value_json FROM vertex_repo_record "
                            "WHERE collection='ai.gftd.apps.shinshi.modelProfile' "
                            "AND repo IN (" (sql-in (map core/actress-did slugs)) ")"))]
    (when rows
      (into {}
            (keep (fn [{:keys [repo value_json]}]
                    (let [slug (subs repo (count core/shinshi-did-prefix))]
                      (try [slug (js->clj (js/JSON.parse value_json) :keywordize-keys true)]
                           (catch :default _ nil)))))
            rows))))

(defn latest-scene-blobs
  "slugs -> {slug blob-key} (highest scene_index per slug), or nil."
  [slugs]
  (let [rows (d1-query core/shinshi-db
                       (str "SELECT slug, blob_key, scene_index FROM scene "
                            "WHERE slug IN (" (sql-in slugs) ") ORDER BY scene_index ASC"))]
    (when rows
      (reduce (fn [m {:keys [slug blob_key]}] (assoc m slug blob_key)) {} rows))))

(defn existing-slugs
  "-> set of every actress slug, or nil."
  []
  (let [rows (d1-query core/shinshi-db "SELECT slug FROM actress")]
    (when rows (into #{} (map :slug) rows))))
