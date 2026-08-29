(ns generate
  "Per-actor media generation for the cast bots.

     image <slug> [--apply]    one portrait via the fleet image API
                               (api.murakumo.cloud /v1/images/generations →
                               gateway → gad ComfyUI). --apply uploads it to
                               shinshi.club /b/upload and registers a scene
                               row so posts can embed it.
     video <slug> [--publish]  one i2v clip from her latest scene image via
                               gad ComfyUI (Wan2.2 ti2v 5B — the same
                               scripts/shinshi-wan-i2v-once.py the catalog
                               loop uses), measured by the deterministic
                               quality gate. --publish hands a PASS clip to
                               scripts/shinshi-catalog-video-publish-once.cljs
                               (which re-measures and is fail-closed).

   Honest-receipt rules: a missing token / unreachable gad / 402|401 from
   the API is an UPSTREAM condition printed as such (exit 0) — only our own
   errors exit 1. generation.murakumo.cloud is NOT used (402/billing —
   skill shinshi-catalog-video).

   env: MURAKUMO_API_KEY (image), SHINSHI_UPLOAD_TOKEN (--apply),
        SHINSHI_GAD (default gad@100.82.98.110), GFTD_ROOT."
  (:require [cast-core :as core]
            [cast-d1 :as d1]
            [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def argv (vec *command-line-args*))
(def mode (first argv))
(def slug (second argv))
(defn- flag? [f] (boolean (some #{f} argv)))

(def murakumo-base
  (or (aget (.-env js/process) "MURAKUMO_API_BASE") "https://api.murakumo.cloud"))
(def gad (or (aget (.-env js/process) "SHINSHI_GAD") "gad@100.82.98.110"))

(defn- sh [cmd args opts]
  (let [r (cp/spawnSync cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8"
                                         :maxBuffer (* 256 1024 1024)} opts)))]
    {:status (.-status r) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn- scratch-file [suffix]
  (path/join (os/tmpdir) (str "shinshi-gen-" slug "-" (.now js/Date) suffix)))

(defn- profile-of [s]
  (get (or (d1/profiles [s]) {}) s))

(defn- sha256-hex [buf]
  (-> (.createHash crypto "sha256") (.update buf) (.digest "hex")))

(defn- upload!
  "-> blob url or nil (receipt printed)."
  [file sha]
  (let [tok (aget (.-env js/process) "SHINSHI_UPLOAD_TOKEN")]
    (if-not tok
      (do (println "UPSTREAM: SHINSHI_UPLOAD_TOKEN not set — not uploading") nil)
      (let [{:keys [status out err]}
            (sh "curl" ["-sS" "-o" "/dev/null" "-w" "%{http_code}"
                        "-H" (str "x-upload-token: " tok)
                        "--data-binary" (str "@" file)
                        (str core/shinshi-origin "/b/upload?cid=" sha)] {})]
        (if (and (zero? status) (str/starts-with? (str/trim out) "2"))
          (core/blob-url sha)
          (do (println (str "UPSTREAM: upload failed http=" (str/trim out) " " err)) nil))))))

(defn- next-scene-index [s]
  (let [rows (d1/d1-query core/shinshi-db
                          (str "SELECT MAX(scene_index) AS m FROM scene WHERE slug='"
                               (d1/sql-escape s) "'"))]
    (when rows (inc (or (:m (first rows)) -1)))))

(defn- register-scene! [s sha]
  (let [idx (next-scene-index s)]
    (if (nil? idx)
      (do (println "UNANSWERED: could not read scene index — scene row not written") false)
      (let [row-id (str s ":" idx)
            tmp (scratch-file ".sql")]
        (fs/writeFileSync
         tmp (str "INSERT OR REPLACE INTO scene (scene_id, slug, scene_index, blob_key) VALUES ('"
                  (d1/sql-escape row-id) "','" (d1/sql-escape s) "'," idx ",'r2:" sha "');"))
        (if (d1/d1-exec-file! core/shinshi-db tmp)
          (do (println (str "scene-registered\t" row-id "\tr2:" sha)) true)
          false)))))

;; ── image ────────────────────────────────────────────────────────────────

(defn image! []
  (let [p (profile-of slug)]
    (cond
      (nil? p)
      (do (println (str "REFUSED: no modelProfile for " slug)) (.exit js/process 2))

      (nil? (aget (.-env js/process) "MURAKUMO_API_KEY"))
      (do (println "UPSTREAM: MURAKUMO_API_KEY not set — image API needs an mk1 token"
                   "(existing scene blobs remain usable for posts)")
          (.exit js/process 0))

      :else
      (let [ctrl (js/AbortController.)
            _ (js/setTimeout #(.abort ctrl) 300000)]
        (-> (js/fetch (str murakumo-base "/v1/images/generations")
                      (clj->js {:method "POST"
                                :signal (.-signal ctrl)
                                :headers {"content-type" "application/json"
                                          "x-api-key" (aget (.-env js/process) "MURAKUMO_API_KEY")}
                                :body (js/JSON.stringify
                                       (clj->js {:prompt (:promptStyle p)
                                                 :size "512x768"}))}))
            (.then (fn [resp]
                     (.then (.text resp)
                            (fn [text]
                              (if-not (= 200 (.-status resp))
                                (do (println (str "UPSTREAM: image API " (.-status resp) " "
                                                  (subs text 0 (min 200 (count text)))))
                                    (.exit js/process 0))
                                (let [j (js/JSON.parse text)
                                      b64 (some-> (aget j "data") (aget 0) (aget "b64_json"))]
                                  (if-not b64
                                    (do (println "UPSTREAM: image API returned no b64_json")
                                        (.exit js/process 0))
                                    (let [buf (js/Buffer.from b64 "base64")
                                          sha (sha256-hex buf)
                                          file (scratch-file ".png")]
                                      (fs/writeFileSync file buf)
                                      (println (str "generated\t" file "\tsha256=" sha
                                                    "\tbytes=" (.-length buf)))
                                      (when (flag? "--apply")
                                        (when-let [url (upload! file sha)]
                                          (when (register-scene! slug sha)
                                            (println (str "applied\t" url)))))
                                      (.exit js/process 0)))))))))
            (.catch (fn [e]
                      (println (str "UPSTREAM: image API unreachable — " (.-message e)))
                      (.exit js/process 0))))))))

;; ── video ────────────────────────────────────────────────────────────────

(defn video! []
  (let [p (profile-of slug)
        blob (get (or (d1/latest-scene-blobs [slug]) {}) slug)]
    (cond
      (nil? p) (do (println (str "REFUSED: no modelProfile for " slug)) (.exit js/process 2))
      (nil? blob) (do (println (str "UPSTREAM: " slug " has no scene image to animate — "
                                    "run `generate.cljs image " slug " --apply` first"))
                      (.exit js/process 0))
      :else
      (let [ref (scratch-file "-ref.png")
            outv (scratch-file ".mp4")
            wan (path/join d1/gftd-root "scripts" "shinshi-wan-i2v-once.py")
            key (str/replace blob #"^r2:" "")
            dl (sh "curl" ["-sS" "-f" "-o" ref (core/blob-url key)] {})]
        (cond
          (not (zero? (:status dl)))
          (do (println (str "UPSTREAM: could not download ref image " (core/blob-url key)))
              (.exit js/process 0))

          (not (fs/existsSync wan))
          (do (println (str "REFUSED: " wan " not found (GFTD_ROOT?)")) (.exit js/process 2))

          :else
          (let [rref (str "/tmp/shinshi-cast-ref-" (.now js/Date) ".png")
                rout (str "/tmp/shinshi-cast-out-" (.now js/Date) ".mp4")
                rpy (str "/tmp/shinshi-cast-wan-" (.now js/Date) ".py")
                motion (str "adult woman, " (or (:promptStyle p) "") ", subtle breathing, cinematic lighting, gentle motion")
                up (sh "scp" ["-o" "ConnectTimeout=10" ref (str gad ":" rref)] {})
                up2 (when (zero? (:status up)) (sh "scp" [wan (str gad ":" rpy)] {}))
                run (when (and up2 (zero? (:status up2)))
                      (sh "ssh" ["-o" "ConnectTimeout=10" gad
                                 (str "COMFY=http://127.0.0.1:8188 python3 " rpy " " rref " " rout " "
                                      "'" (str/replace motion "'" "") "'")]
                          {:timeout 900000}))
                down (when (and run (zero? (:status run)))
                       (sh "scp" [(str gad ":" rout) outv] {}))]
            (if-not (and down (zero? (:status down)) (fs/existsSync outv))
              (do (println (str "UPSTREAM: i2v generation failed — "
                                (str/trim (str (:err (or down run up2 up))))))
                  (.exit js/process 0))
              (let [gate (sh "nbb" [(path/join d1/gftd-root "scripts" "shinshi-catalog-video-quality.cljs")
                                    outv] {})]
                (println (str "generated\t" outv))
                (println (str/trim (:out gate)))
                (if-not (zero? (:status gate))
                  (do (println "quality-gate: FAIL — not publishing") (.exit js/process 0))
                  (do
                    (when (flag? "--publish")
                      (let [pub (sh "nbb" [(path/join d1/gftd-root "scripts" "shinshi-catalog-video-publish-once.cljs")
                                           slug outv
                                           "--name" (or (:charName p) slug)
                                           "--series" (or (:series p) "")] {:timeout 300000})]
                        (println (str/trim (:out pub)))
                        (when-not (zero? (:status pub))
                          (println (str "publish-once failed: " (str/trim (:err pub)))))))
                    (.exit js/process 0)))))))))))

(cond
  (and (= mode "image") slug) (image!)
  (and (= mode "video") slug) (video!)
  :else (do (println "usage: generate.cljs image|video <slug> [--apply|--publish]")
            (.exit js/process 2)))
