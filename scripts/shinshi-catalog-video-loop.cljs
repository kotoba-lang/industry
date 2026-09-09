#!/usr/bin/env nbb
;; scripts/shinshi-catalog-video-loop.cljs — 1 体だけ i2v して品質を測り、
;; pass のときだけ shinshi.club に載せる。モデルは居ない（yellow/想定外 fail だけ起こす）。
;;
;; launchd: scripts/com.gftd.shinshi-catalog-video.plist (30m)
;;
;; 禁ずる: murakumo generation 課金、IP 自動公開、黒/未測定 clip の公開、
;; H3 を止めたまま放置、D1 不通を「埋めるものが無い」と書くこと。

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))
(def crypto (js/require "node:crypto"))
(def process (js/require "node:process"))

(def home (.homedir os))
(def ledger-dir (or (.-SHINSHI_VIDEO_HOME js/process.env)
                    (.join path home ".itonami" "shinshi-catalog-video")))
(def root (or (.-COM_JUNKAWASAKI_ROOT js/process.env) (.cwd process)))
(def this-dir
  (.dirname path (or (second (js->clj (.-argv process)))
                     (.cwd process))))
(defn- resolve-sib [name]
  (let [short (case name
                "shinshi-catalog-video-tick.cljs" "tick.cljs"
                "shinshi-catalog-video-loop.cljs" "loop.cljs"
                "shinshi-catalog-video-quality.cljs" "quality.cljs"
                name)
        local (.join path ledger-dir short)
        local-long (.join path ledger-dir name)
        sib (.join path this-dir name)
        repo (.join path root "scripts" name)]
    (cond (.existsSync fs local) local
          (.existsSync fs local-long) local-long
          (.existsSync fs sib) sib
          :else repo)))
(def tick-path (resolve-sib "shinshi-catalog-video-tick.cljs"))
(def quality-path (resolve-sib "shinshi-catalog-video-quality.cljs"))
(def wan-path (resolve-sib "shinshi-wan-i2v-once.py"))
(def ledger-path (.join path ledger-dir "loop.ledger.edn"))
(def lock-path (.join path ledger-dir "lock"))
(def work-dir (.join path ledger-dir "work"))
(def nbb-bin (or (.-NBB_BIN js/process.env) "nbb"))
(def claude-bin (or (.-CLAUDE_BIN js/process.env) "claude"))
(def gad (or (.-GAD_HOST js/process.env) "gad@100.82.98.110"))
(def appview
  (or (.-SHINSHI_APPVIEW js/process.env)
      "/Users/junkawasaki/github/com-junkawasaki/orgs/network-awai/club-shinshi-app/appview/ai-gftd-wasm-shinshi-sh1n5h1x"))
(def fail-cooldown-ms (* 6 60 60 1000))

(defn- now-iso [] (.toISOString (js/Date.)))

(defn- read-ledger []
  (if-not (.existsSync fs ledger-path)
    []
    (->> (str/split (.readFileSync fs ledger-path "utf8") #"\n")
         (remove str/blank?)
         (keep (fn [line]
                 (try (edn/read-string line) (catch :default _ nil))))
         vec)))

(defn- append! [m]
  (.mkdirSync fs (.dirname path ledger-path) #js {:recursive true})
  (.appendFileSync fs ledger-path (str (pr-str m) "\n") "utf8"))

(defn- recent-failed [rows]
  (let [cut (- (js/Date.now) fail-cooldown-ms)]
    (into #{}
          (keep (fn [r]
                  (when (and (= :failed (:kind r))
                             (:slug r)
                             (try (>= (.getTime (js/Date. (:as-of r))) cut)
                                  (catch :default _ false)))
                    (:slug r))))
          rows)))

(defn- lock-live? []
  (when (.existsSync fs lock-path)
    (let [pid (str/trim (.readFileSync fs lock-path "utf8"))
          r (.spawnSync cp "kill" #js ["-0" pid]
                        #js {:stdio #js ["ignore" "ignore" "ignore"]})]
      (zero? (or (.-status r) 1)))))

(defn- take-lock! []
  (when (lock-live?)
    (throw (js/Error. (str "lock-held " (.readFileSync fs lock-path "utf8")))))
  (.mkdirSync fs ledger-dir #js {:recursive true})
  (.writeFileSync fs lock-path (str (.-pid process)) "utf8"))

(defn- drop-lock! []
  (try (.unlinkSync fs lock-path) (catch :default _ nil)))

(defn- sh [bin args opts]
  (let [r (.spawnSync cp bin (clj->js args)
                      (clj->js (merge {:encoding "utf8"
                                       :stdio ["ignore" "pipe" "pipe"]
                                       :timeout 60000}
                                      opts)))]
    {:status (or (.-status r) 1)
     :out (str (or (.-stdout r) ""))
     :err (str (or (.-stderr r) ""))}))

(defn- ssh [cmd timeout]
  (sh "ssh" ["-o" "BatchMode=yes" "-o" "ConnectTimeout=10"
             "-o" "StrictHostKeyChecking=accept-new" gad cmd]
      {:timeout (or timeout 20000)}))

(defn- last-json [out]
  (let [lines (->> (str/split (str out) #"\n") (remove str/blank?))
        json-line (last (filter #(str/starts-with? % "{") lines))]
    (when-not json-line
      (throw (js/Error. (str "no-json: " (subs (str out) 0 (min 200 (count (str out))))))))
    (js->clj (js/JSON.parse json-line) :keywordize-keys true)))

(defn- run-tick []
  (last-json (:out (sh nbb-bin [tick-path "--json"]
                       {:cwd root :env js/process.env :timeout 180000}))))

(defn- score [file]
  (last-json (:out (sh nbb-bin [quality-path file "--json"]
                       {:cwd root :env js/process.env :timeout 60000}))))

(defn- sleep! [ms]
  (.spawnSync cp "perl" #js ["-e" (str "select(undef,undef,undef," (/ ms 1000.0) ")")]
              #js {:stdio #js ["ignore" "ignore" "ignore"]}))

(defn- wait-vram2! [min-gb secs]
  (loop [n 0]
    (let [r (ssh "curl -sS -m 5 http://127.0.0.1:8188/system_stats" 15000)
          gb (try
               (let [j (js->clj (js/JSON.parse (:out r)) :keywordize-keys true)
                     v (or (:vram_free (first (:devices j))) 0)]
                 (/ (double v) 1.0e9))
               (catch :default _ 0))]
      (cond
        (>= gb min-gb) gb
        (>= n secs) (throw (js/Error. (str "vram-timeout gb=" gb)))
        :else (do (sleep! 5000) (recur (+ n 5)))))))

(defn- wait-h3! []
  (loop [n 0]
    (let [r (ssh "curl -sS -o /dev/null -w '%{http_code}' -m 5 http://127.0.0.1:8191/" 15000)]
      (cond
        (= "200" (str/trim (:out r))) true
        (>= n 90) (throw (js/Error. "h3-start-timeout"))
        :else (do (sleep! 3000) (recur (+ n 3)))))))

(defn- upload-token []
  (or (let [t (.-SHINSHI_UPLOAD_TOKEN js/process.env)]
        (when (seq (str (or t ""))) t))
      (let [f (.join path appview "wrangler.jsonc")]
        (when (.existsSync fs f)
          (second (re-find #"\"UPLOAD_TOKEN\"\s*:\s*\"([^\"]+)\""
                           (.readFileSync fs f "utf8")))))))

(defn- sha256-hex [buf]
  (-> (.createHash crypto "sha256") (.update buf) (.digest "hex")))

(defn- sql-escape [s]
  (str/replace (str s) "'" "''"))

(defn- video-post-sql [{:keys [slug sha-hex created-at text width height size alt]}]
  (let [did (str "did:web:sh1n5h1x.gftd.ai:" slug)
        rkey (str "vid-" (subs sha-hex 0 12))
        uri (str "at://" did "/app.bsky.feed.post/" rkey)
        blob-key (str "r2:" sha-hex)
        rec {"$type" "app.bsky.feed.post"
             "text" text
             "createdAt" created-at
             "langs" ["ja" "en"]
             "labels" {"$type" "com.atproto.label.defs#selfLabels"
                       "values" [{"val" "nsfw"} {"val" "sexual"}]}
             "embed" {"$type" "app.bsky.embed.video"
                      "alt" (str (or alt ""))
                      "aspectRatio" {"width" width "height" height}
                      "video" {"$type" "blob"
                               "ref" {"$link" blob-key}
                               "mimeType" "video/mp4"
                               "size" size}}}
        vj (sql-escape (js/JSON.stringify (clj->js rec)))]
    (str "INSERT OR REPLACE INTO vertex_repo_record "
         "(uri, cid, collection, rkey, repo, value_json, created_at, indexed_at) VALUES ('"
         (sql-escape uri) "','','app.bsky.feed.post','" (sql-escape rkey) "','"
         (sql-escape did) "','" vj "','"
         (sql-escape created-at) "','" (sql-escape created-at) "');")))

(defn- sh-quote [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn- upload-mp4! [token sha buf]
  (let [tmp (.join path work-dir (str sha ".mp4"))]
    (.mkdirSync fs work-dir #js {:recursive true})
    (.writeFileSync fs tmp buf)
    (let [r (sh "curl" ["-sS" "-o" "/dev/null" "-w" "%{http_code}"
                        "-X" "POST"
                        "-H" "content-type: video/mp4"
                        "-H" (str "x-upload-token: " token)
                        "--data-binary" (str "@" tmp)
                        (str "https://shinshi.club/b/upload?cid=" sha)]
                {:timeout 120000})]
      (when-not (= "200" (str/trim (:out r)))
        (throw (js/Error. (str "upload-http-" (str/trim (:out r)) " " (subs (:err r) 0 (min 120 (count (:err r)))))))))))

;; `npx --yes wrangler …` はこのループを走らせるマシン（npm 11.12.1）で壊れて
;; いて `npm ERR! cb.apply is not a function` を吐く。publish 経路がこれに当たると
;; **R2 に上がった clip が D1 に入らないまま失敗する**。実測 2026-08-15。
;; wrangler が PATH に居るならそれを直接呼び、居ないときだけ npx へ落ちる。
(def wrangler-cmd
  (let [r (.spawnSync cp "wrangler" #js ["--version"]
                      #js {:encoding "utf8" :timeout 30000
                           :stdio #js ["ignore" "pipe" "pipe"]})]
    (if (zero? (or (.-status r) 1)) :direct :npx)))

(defn- wrangler-argv [args]
  (if (= :direct wrangler-cmd)
    ["wrangler" (vec args)]
    ["npx" (into ["--yes" "wrangler"] args)]))

(defn- d1-apply! [sql]
  (let [f (.join path work-dir "videos.sql")]
    (.mkdirSync fs work-dir #js {:recursive true})
    (.writeFileSync fs f (str sql "\n"))
    (let [r (let [[bin args] (wrangler-argv ["d1" "execute" "ai-gftd-pds-recordlog"
                                             "--remote" "--yes" "--file" f])]
                (sh bin args
                {:cwd appview :env js/process.env :timeout 120000}))]
      (when-not (zero? (:status r))
        (throw (js/Error. (str "d1-apply " (subs (str (:out r) (:err r)) 0 240))))))))

(defn- ensure-wan-remote! []
  (let [c (sh "scp" ["-o" "BatchMode=yes" wan-path (str gad ":/tmp/wan_i2v_once.py")]
              {:timeout 30000})]
    (when-not (zero? (:status c))
      (throw (js/Error. (str "scp-wan " (:err c)))))))

(defn- generate! [cand]
  (let [slug (:slug cand)
        local-ref (.join path work-dir (str slug "-ref.bin"))
        local-mp4 (.join path work-dir (str slug "-0.mp4"))
        remote-ref (str "/tmp/" slug "-ref.bin")
        remote-mp4 (str "/tmp/" slug "-0.mp4")
        prompt (str "adult woman, " (str/lower-case (str (:series cand)))
                    ", subtle breathing, looking at viewer, cinematic lighting, gentle motion")
        img (or (:image-url cand)
                (throw (js/Error. (str "no-scene-image " slug))))]
    (.mkdirSync fs work-dir #js {:recursive true})
    (let [dl (sh "curl" ["-fsSL" "-o" local-ref img] {:timeout 60000})]
      (when-not (zero? (:status dl))
        (throw (js/Error. (str "scene-fetch " slug " " (:err dl))))))
    (ensure-wan-remote!)
    (let [up (sh "scp" ["-o" "BatchMode=yes" local-ref (str gad ":" remote-ref)] {:timeout 60000})]
      (when-not (zero? (:status up))
        (throw (js/Error. (str "scp-ref " (:err up))))))
    (let [g (ssh (str "python3 /tmp/wan_i2v_once.py "
                      remote-ref " " remote-mp4 " " (sh-quote prompt))
                 400000)]
      (when-not (zero? (:status g))
        (throw (js/Error. (str "wan-exit-" (:status g) " " (subs (str (:out g) (:err g)) 0 300))))))
    (let [dn (sh "scp" ["-o" "BatchMode=yes" (str gad ":" remote-mp4) local-mp4] {:timeout 60000})]
      (when-not (zero? (:status dn))
        (throw (js/Error. (str "scp-mp4 " (:err dn))))))
    local-mp4))

(defn- publish! [cand q file]
  (let [buf (.readFileSync fs file)
        sha (sha256-hex buf)
        tok (or (upload-token) (throw (js/Error. "upload-token-missing")))
        rec {:slug (:slug cand)
             :sha-hex sha
             :created-at (now-iso)
             :text (str (:char-name cand) " / " (:series cand)
                        "\nAI-generated original character. Adult (18+). No real performers.\n"
                        "https://shinshi.club/video/actress/" (:slug cand))
             :width (:width q)
             :height (:height q)
             :size (.-length buf)
             :alt (:char-name cand)}]
    (upload-mp4! tok sha buf)
    (d1-apply! (video-post-sql rec))
    {:cid sha
     :public-url (str "https://shinshi.club/b/" sha)
     :page (str "https://shinshi.club/video/actress/" (:slug cand))}))

(defn- wake! [cand q why]
  (let [prompt (str "/shinshi-catalog-video\n\n"
                    "One character quality follow-up. Do not spend murakumo generation. "
                    "Do not publish a fail/unanswered clip. Do not PO. Original series only.\n\n"
                    "why=" why "\n"
                    "candidate=" (js/JSON.stringify (clj->js cand)) "\n"
                    "quality=" (js/JSON.stringify (clj->js q)))
        started (js/Date.now)
        r (.spawnSync cp claude-bin #js ["-p" prompt]
                      #js {:encoding "utf8" :cwd root :env js/process.env :timeout 900000})]
    (append! {:kind :woke :slug (:slug cand) :why why
              :as-of (now-iso) :exit (or (.-status r) 1)
              :ms (- (js/Date.now) started)})
    (or (.-status r) 1)))

(defn- restore-h3! [stopped?]
  (when stopped?
    (ssh "sudo -n systemctl start comfyui-h3.service" 30000)
    (wait-h3!)))

(try
  (cond
    (lock-live?)
    (do (append! {:kind :skip :why :lock :as-of (now-iso)})
        (println "SKIP lock held")
        (.exit process 0))

    :else
    (let [tick (run-tick)
          unanswered (boolean (:unanswered tick))
          seen (recent-failed (read-ledger))
          cands (vec (remove #(contains? seen (:slug %)) (:candidates tick)))
          gpu (:gpu tick)
          one (first cands)]
      (cond
        unanswered
        (do (append! {:kind :skip :why :unanswered :reason (:reason tick) :as-of (now-iso)})
            (println "SKIP unanswered" (:reason tick))
            (.exit process 0))

        (empty? cands)
        (do (append! {:kind :skip :why :no-candidates :scanned (:scanned tick) :as-of (now-iso)})
            (println "SKIP no original-series gaps scanned=" (:scanned tick))
            (.exit process 0))

        (or (not (:ok gpu)) (:busy gpu))
        (do (append! {:kind :skip :why :gpu-busy :gpu gpu :slug (:slug one) :as-of (now-iso)})
            (println "SKIP gpu" (pr-str (select-keys gpu [:busy :vram-free-gb :reason])))
            (.exit process 0))

        :else
        (let [stopped (atom false)]
          (take-lock!)
          (try
            (when (:need-stop-h3 gpu)
              (println "stop H3 for VRAM")
              (ssh "sudo -n systemctl stop comfyui-h3.service" 30000)
              (reset! stopped true)
              (wait-vram2! 20.0 90))
            (println "GENERATE" (:slug one) (:series one))
            (let [file (generate! one)
                  q (score file)]
              (append! {:kind :scored :slug (:slug one) :quality q :as-of (now-iso)})
              (cond
                (:unanswered q)
                (do (append! {:kind :failed :slug (:slug one) :why :quality-unanswered
                              :reason (:reason q) :as-of (now-iso)})
                    (println "FAIL unanswered quality" (:reason q))
                    (wake! one q "quality-unanswered"))

                (not (:pass q))
                (do (append! {:kind :failed :slug (:slug one) :why :quality-fail
                              :verdict (:verdict q) :luma (:luma q) :as-of (now-iso)})
                    (println "FAIL quality" (name (:verdict q)) "luma=" (:luma q))
                    (when-not (get-in q [:checks :black])
                      (wake! one q "quality-fail")))

                :else
                (let [pub (publish! one q file)]
                  (append! {:kind :published :slug (:slug one)
                            :cid (:cid pub) :url (:public-url pub)
                            :luma (:luma q) :motion (:motion q)
                            :verdict (:verdict q) :as-of (now-iso)
                            :score-raised? false})
                  (println "PUBLISHED" (:slug one) (:public-url pub)
                           "luma=" (:luma q) "verdict=" (name (:verdict q)))
                  (when (= :yellow (:verdict q))
                    (wake! one q "yellow")))))
            (finally
              (try (restore-h3! @stopped)
                   (catch :default e
                     (append! {:kind :failed :why :h3-restore :error (str e) :as-of (now-iso)})
                     (println "H3 restore failed" (str e))))
              (drop-lock!)))
          (.exit process 0)))))
  (catch :default e
    (append! {:kind :failed :error (str e) :as-of (now-iso)})
    (try (drop-lock!) (catch :default _ nil))
    (println "FAIL" (str e))
    (.exit process 1)))
