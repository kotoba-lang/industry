#!/usr/bin/env nbb
;; scripts/shinshi-catalog-video-publish-once.cljs
;; loop.cljs の publish! と同じ経路で、既に手元にある clip を 1 本だけ載せる。
;;
;; 用途は後追いだけ: quality gate が誤って unanswered / fail を吐いたせいで
;; loop が publish 分岐に入れなかった pass clip を、測り直してから載せる。
;; 生成はしない（murakumo も gad も呼ばない）。
;; 自分で測り、pass でなければ何もしない（fail-closed）。
;;
;; usage:
;;   nbb scripts/shinshi-catalog-video-publish-once.cljs <slug> <file.mp4> --name <char> --series <series> [--dry-run]

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))
(def crypto (js/require "node:crypto"))
(def process (js/require "node:process"))

(def argv
  (let [all (vec (js->clj (.-argv process)))
        i (last (keep-indexed (fn [idx s]
                                (when (str/ends-with? (str (.basename path (str s))) ".cljs") idx))
                              all))]
    (vec (drop (inc (or i 1)) all))))

(defn- flag [name]
  (let [i (first (keep-indexed (fn [idx s] (when (= s name) idx)) argv))]
    (when i (nth argv (inc i) nil))))

(def dry? (boolean (some #{"--dry-run"} argv)))
(def pos (loop [xs argv acc []]
           (cond (empty? xs) acc
                 (str/starts-with? (first xs) "--")
                 (recur (if (= "--dry-run" (first xs)) (rest xs) (drop 2 xs)) acc)
                 :else (recur (rest xs) (conj acc (first xs))))))
(def slug (first pos))
(def file (second pos))
(def char-name (or (flag "--name") slug))
(def series (or (flag "--series") ""))

(def ledger-dir (or (.-SHINSHI_VIDEO_HOME js/process.env)
                    (.join path (.homedir os) ".gftd" "shinshi-catalog-video")))
(def work-dir (.join path ledger-dir "work"))
(def ledger-path (.join path ledger-dir "loop.ledger.edn"))
(def root (or (.-COM_JUNKAWASAKI_ROOT js/process.env) (.cwd process)))
(def quality-path
  (let [local (.join path ledger-dir "quality.cljs")]
    (if (.existsSync fs local)
      local
      (.join path root "scripts" "shinshi-catalog-video-quality.cljs"))))
(def appview
  (or (.-SHINSHI_APPVIEW js/process.env)
      "/Users/junkawasaki/github/com-junkawasaki/orgs/network-awai/club-shinshi-app/appview/ai-gftd-wasm-shinshi-sh1n5h1x"))
(def nbb-bin (or (.-NBB_BIN js/process.env) "nbb"))

(defn- now-iso [] (.toISOString (js/Date.)))

(defn- sh [bin args opts]
  (let [r (.spawnSync cp bin (clj->js args)
                      (clj->js (merge {:encoding "utf8"
                                       :stdio ["ignore" "pipe" "pipe"]}
                                      opts)))]
    {:status (or (.-status r) 1)
     :out (str (or (.-stdout r) ""))
     :err (str (or (.-stderr r) ""))}))

(defn- append! [m]
  (.mkdirSync fs (.dirname path ledger-path) #js {:recursive true})
  (.appendFileSync fs ledger-path (str (pr-str m) "\n") "utf8"))

(defn- last-json [out]
  (let [lines (->> (str/split (str out) #"\n") (remove str/blank?))
        json-line (last (filter #(str/starts-with? % "{") lines))]
    (when-not json-line
      (throw (js/Error. (str "no-json: " (subs (str out) 0 (min 200 (count (str out))))))))
    (js->clj (js/JSON.parse json-line) :keywordize-keys true)))

(defn- score [f]
  (last-json (:out (sh nbb-bin [quality-path f "--json"] {:timeout 120000}))))

(defn- sha256-hex [buf] (-> (.createHash crypto "sha256") (.update buf) (.digest "hex")))
(defn- sql-escape [s] (str/replace (str s) "'" "''"))

(defn- upload-token []
  (or (let [t (.-SHINSHI_UPLOAD_TOKEN js/process.env)] (when (seq (str (or t ""))) t))
      (let [f (.join path appview "wrangler.jsonc")]
        (when (.existsSync fs f)
          (second (re-find #"\"UPLOAD_TOKEN\"\s*:\s*\"([^\"]+)\""
                           (.readFileSync fs f "utf8")))))))

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
        (throw (js/Error. (str "upload-http-" (str/trim (:out r)) " "
                               (subs (:err r) 0 (min 120 (count (:err r)))))))))))

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
                {:cwd appview :env js/process.env :timeout 180000}))]
      (when-not (zero? (:status r))
        (throw (js/Error. (str "d1-apply " (subs (str (:out r) (:err r)) 0 300)))))
      (:out r))))

(when-not (and slug file (.existsSync fs file))
  (println "usage: nbb scripts/shinshi-catalog-video-publish-once.cljs <slug> <file.mp4> --name <char> --series <series> [--dry-run]")
  (.exit process 2))

(let [q (score file)]
  (println (str "SCORED verdict=" (:verdict q) " luma=" (:luma q) " motion=" (:motion q)
                " bytes=" (:bytes q) " " (:width q) "x" (:height q)))
  (when (or (:unanswered q) (not (:pass q)))
    (println "REFUSE: not a measured pass")
    (.exit process 1))
  (let [buf (.readFileSync fs file)
        sha (sha256-hex buf)
        created (now-iso)
        sql (video-post-sql
             {:slug slug :sha-hex sha :created-at created
              :text (str char-name " / " series
                         "\nAI-generated original character. Adult (18+). No real performers.\n"
                         "https://shinshi.club/video/actress/" slug)
              :width (:width q) :height (:height q)
              :size (.-length buf) :alt char-name})]
    (if dry?
      (do (println (str "DRY-RUN sha=" sha))
          (println sql)
          (.exit process 0))
      (do
        (upload-mp4! (or (upload-token) (throw (js/Error. "upload-token-missing"))) sha buf)
        (println (str "UPLOADED https://shinshi.club/b/" sha))
        (d1-apply! sql)
        (append! {:kind :published :slug slug :cid sha
                  :url (str "https://shinshi.club/b/" sha)
                  :luma (:luma q) :motion (:motion q)
                  :verdict (keyword (str (:verdict q)))
                  :as-of (now-iso) :score-raised? false
                  :source :quality-gate-repair})
        (println (str "PUBLISHED " slug " https://shinshi.club/b/" sha))
        (println (str "PAGE https://shinshi.club/video/actress/" slug))
        (.exit process 0)))))
