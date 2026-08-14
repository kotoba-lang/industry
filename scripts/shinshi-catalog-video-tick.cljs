#!/usr/bin/env nbb
;; scripts/shinshi-catalog-video-tick.cljs — 次に埋める original-series 1 体と
;; gad ComfyUI の空きを測る。モデルは居ない。
;;
;; 不変条件:
;;   - D1 / Comfy / ssh が答えられないとき candidates=[] を「埋めるものが無い」
;;     と書かない。:unanswered を立てる（SCANNED=0 は pass ではない）。
;;   - IP 由来キャラは候補にしない（ORIGINAL-SERIES 12 名のみ）。
;;   - murakumo generation は使わない。スコアは上げない（dogfood catalog）。
;;
;; usage:
;;   nbb scripts/shinshi-catalog-video-tick.cljs
;;   nbb scripts/shinshi-catalog-video-tick.cljs --json

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))
(def process (js/require "node:process"))

(def home (.homedir os))
(def ledger-dir (or (.-SHINSHI_VIDEO_HOME js/process.env)
                    (.join path home ".gftd" "shinshi-catalog-video")))
(def scan-path (.join path ledger-dir "scans.edn"))
(def json? (boolean (some #{"--json"} (js->clj (.-argv process)))))

(def original-suffix
  {"neon-tokyo" "Neon Tokyo"
   "seaside" "Seaside Days"
   "campus" "Campus Life"
   "office" "After Office"
   "sakura" "Sakura Court"
   "gothic" "Gothic Rose"
   "fitness" "Fitness Club"
   "cafe" "Cafe Corner"
   "fantasy" "Astral Realm"
   "idol" "Stage Lights"
   "winter" "Snow Resort"
   "noir" "Midnight Bar"})

(def original-series (set (vals original-suffix)))

(def appview
  (or (.-SHINSHI_APPVIEW js/process.env)
      "/Users/junkawasaki/github/com-junkawasaki/orgs/network-awai/club-shinshi-app/appview/ai-gftd-wasm-shinshi-sh1n5h1x"))

(def gad (or (.-GAD_HOST js/process.env) "gad@100.82.98.110"))
(def vram-floor-gb 20.0)

(defn- now-iso [] (.toISOString (js/Date.)))

(defn- sql-quote [s]
  (str "'" (str/replace (str (or s "")) "'" "''") "'"))

(defn- parse-wrangler [out]
  (let [i (.indexOf (str out) "[")]
    (when (neg? i)
      (throw (js/Error. (str "wrangler-no-json: " (subs (str out) 0 (min 200 (count (str out))))))))
    (js->clj (js/JSON.parse (subs (str out) i)) :keywordize-keys true)))

(defn- d1 [db sql]
  (when-not (.existsSync fs (.join path appview "wrangler.jsonc"))
    (throw (js/Error. (str "wrangler.jsonc missing: " appview))))
  (let [r (.spawnSync cp "npx"
                      #js ["--yes" "wrangler" "d1" "execute" db
                           "--remote" "--json" "--command" sql]
                      #js {:encoding "utf8"
                           :cwd appview
                           :env js/process.env
                           :timeout 120000
                           :stdio #js ["ignore" "pipe" "pipe"]})
        st (or (.-status r) 1)
        out (str (or (.-stdout r) ""))
        err (str (or (.-stderr r) ""))]
    (when-not (zero? st)
      (throw (js/Error. (str "d1-exit-" st " " (subs (str out err) 0 (min 240 (count (str out err))))))))
    (let [parsed (parse-wrangler out)]
      (vec (:results (first parsed))))))

(defn- series-of [slug profile]
  (let [ps (str/trim (str (or (:series profile) "")))]
    (cond
      (contains? original-series ps) ps
      (seq ps) nil
      :else (get original-suffix (last (str/split (str slug) #"-"))))))

(defn- char-name-of [slug profile series]
  (or (not-empty (str (:charName profile)))
      (let [suf (some (fn [[k]] (when (str/ends-with? (str slug) (str "-" k)) k))
                      original-suffix)
            base (if suf
                   (subs (str slug) 0 (- (count (str slug)) (inc (count suf))))
                   (str slug))]
        (->> (str/split base #"-")
             (map (fn [w]
                    (if (str/blank? w) w
                        (str (str/upper-case (subs w 0 1)) (subs w 1)))))
             (str/join " ")))))

(defn- blob-url [blob-key]
  (let [k (str blob-key)]
    (cond
      (str/starts-with? k "r2:") (str "https://shinshi.club/b/" (subs k 3))
      (str/starts-with? k "http") k
      (seq k) (str "https://shinshi.club/b/" k)
      :else nil)))

(defn- ssh [cmd]
  (let [r (.spawnSync cp "ssh"
                      #js ["-o" "BatchMode=yes" "-o" "ConnectTimeout=10"
                           "-o" "StrictHostKeyChecking=accept-new"
                           gad cmd]
                      #js {:encoding "utf8"
                           :timeout 20000
                           :stdio #js ["ignore" "pipe" "pipe"]})]
    {:status (or (.-status r) 1)
     :out (str (or (.-stdout r) ""))
     :err (str (or (.-stderr r) ""))}))

(defn- gpu []
  (let [r (ssh (str "curl -sS -m 5 http://127.0.0.1:8188/system_stats; echo '---'; "
                    "curl -sS -m 5 http://127.0.0.1:8188/queue"))]
    (if-not (zero? (:status r))
      {:ok false :unanswered true :reason (str "ssh-or-comfy:" (subs (str (:err r) (:out r)) 0 160))}
      (try
        (let [parts (str/split (:out r) #"---")
              stats (js->clj (js/JSON.parse (str/trim (or (first parts) "{}"))) :keywordize-keys true)
              queue (js->clj (js/JSON.parse (str/trim (or (second parts) "{}"))) :keywordize-keys true)
              dev (first (or (:devices stats) []))
              vram (or (:vram_free dev) 0)
              gb (/ (double vram) 1.0e9)
              running (count (or (:queue_running queue) []))
              pending (count (or (:queue_pending queue) []))]
          {:ok true
           :unanswered false
           :vram-free-gb gb
           :busy (or (pos? running) (pos? pending))
           :need-stop-h3 (< gb vram-floor-gb)
           :queue-running running
           :queue-pending pending})
        (catch :default e
          {:ok false :unanswered true :reason (str "gpu-parse:" e)})))))

(defn- candidates []
  (let [rows (d1 "ai-gftd-shinshi"
                 (str "SELECT a.slug AS slug, a.did AS did, COUNT(s.scene_id) AS scenes "
                      "FROM actress a JOIN scene s ON s.slug = a.slug "
                      "WHERE a.status = 'active' AND s.blob_key IS NOT NULL "
                      "GROUP BY a.slug, a.did "
                      "HAVING scenes >= 1 "
                      "ORDER BY scenes DESC, a.slug ASC LIMIT 80"))
        dids (mapv :did rows)
        in (str/join "," (map sql-quote dids))
        profs (if (empty? dids) {}
                  (into {}
                        (keep (fn [{:keys [repo value_json]}]
                                (when (and repo (seq (str value_json)))
                                  (try
                                    [repo (js->clj (js/JSON.parse value_json) :keywordize-keys true)]
                                    (catch :default _ nil)))))
                        (d1 "ai-gftd-pds-recordlog"
                            (str "SELECT repo, value_json FROM vertex_repo_record "
                                 "WHERE collection = 'ai.gftd.apps.shinshi.modelProfile' "
                                 "AND repo IN (" in ")"))))
        vids (if (empty? dids) {}
                 (reduce (fn [acc {:keys [repo rkey]}]
                           (update acc repo (fnil conj #{}) (str rkey)))
                         {}
                         (d1 "ai-gftd-pds-recordlog"
                             (str "SELECT repo, rkey FROM vertex_repo_record "
                                  "WHERE collection = 'app.bsky.feed.post' "
                                  "AND repo IN (" in ") "
                                  "AND json_extract(value_json, '$.embed.video') IS NOT NULL"))))
        meta (into []
                   (keep (fn [{:keys [slug did scenes]}]
                           (let [profile (or (get profs did) {})
                                 series (series-of slug profile)
                                 nvid (count (get vids did #{}))]
                             (when (and series (< nvid 1))
                               {:slug slug :did did :scenes scenes :videos nvid
                                :series series
                                :char-name (char-name-of slug profile series)}))))
                   rows)
        slugs (mapv :slug meta)
        blobs (if (empty? slugs) {}
                  (let [sin (str/join "," (map sql-quote slugs))]
                    (into {}
                          (map (fn [{:keys [slug blob_key]}] [slug blob_key]))
                          (d1 "ai-gftd-shinshi"
                              (str "SELECT s.slug AS slug, s.blob_key AS blob_key "
                                   "FROM scene s JOIN ("
                                   "SELECT slug, MAX(scene_index) AS mx FROM scene "
                                   "WHERE slug IN (" sin ") AND blob_key IS NOT NULL "
                                   "GROUP BY slug) t "
                                   "ON s.slug = t.slug AND s.scene_index = t.mx")))))]
    {:scanned (count rows)
     :picked (mapv (fn [m]
                     (let [bk (get blobs (:slug m))]
                       (assoc m :blob-key bk :image-url (blob-url bk))))
                   meta)}))

(defn- append-scan! [m]
  (.mkdirSync fs (.dirname path scan-path) #js {:recursive true})
  (.appendFileSync fs scan-path (str (pr-str m) "\n") "utf8"))

(defn- emit [out]
  (when-not json?
    (println (str "SCANNED\t" (:scanned out)))
    (println (str "MATCHES\t" (count (:candidates out))))
    (println (str "UNANSWERED\t" (boolean (:unanswered out))))
    (when (:reason out) (println (str "REASON\t" (:reason out))))
    (when-let [n (:next out)]
      (println (str "NEXT\t" (:slug n) "\tseries=" (:series n)
                    "\tscenes=" (:scenes n) "\tvideos=" (:videos n))))
    (when-let [g (:gpu out)]
      (println (str "GPU\tbusy=" (boolean (:busy g))
                    " vram_free_gb=" (when (:vram-free-gb g)
                                       (.toFixed (:vram-free-gb g) 2))
                    (when (:reason g) (str " " (:reason g))))))
    (println (str "RESULT\t" (if (:unanswered out) "unanswered" "recorded"))))
  (println (js/JSON.stringify (clj->js out))))

(try
  (let [{:keys [scanned picked]} (candidates)
        g (gpu)
        unanswered (boolean (:unanswered g))
        out {:kind :shinshi-catalog-video-scan
             :as-of (now-iso)
             :unanswered unanswered
             :reason (when unanswered (:reason g))
             :scanned scanned
             :candidates picked
             :next (first picked)
             :gpu g
             :score-raised? false
             :note "Original-series catalog fill on gad Wan i2v. Not revenue. Do not spend murakumo generation."}]
    (append-scan! out)
    (emit out)
    (.exit process 0))
  (catch :default e
    (let [out {:kind :shinshi-catalog-video-scan
               :as-of (now-iso)
               :unanswered true
               :reason (str e)
               :scanned 0
               :candidates []
               :next nil
               :note "D1/Comfy was not measured. SCANNED=0 is not an empty catalog."}]
      (append-scan! out)
      (emit out)
      (.exit process 0))))
