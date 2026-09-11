#!/usr/bin/env nbb
;; --- nbb shims (auto, ADR-2607173000) ---------------------------------
(def ^:private __fs (js/require "node:fs"))
(def ^:private __path (js/require "node:path"))
(def ^:private __cp (js/require "node:child_process"))
(def ^:private __os (js/require "node:os"))
(def ^:private __crypto (js/require "node:crypto"))
(defn- __sh [& args]
  (let [opts (when (map? (last args)) (last args))
        cmd (if opts (butlast args) args)
        r (.spawnSync __cp (first cmd) (to-array (rest cmd))
                      (clj->js (merge {:encoding "utf8"} (when opts {:cwd (:dir opts)}))))]
    {:exit (or (.-status r) 1) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))
(defn- __shell [& args]
  (let [opts (when (map? (first args)) (first args))
        cmd (if opts (rest args) args)
        r (.spawnSync __cp (first cmd) (to-array (rest cmd))
                      (clj->js (merge {:stdio "inherit" :encoding "utf8"}
                                      (when opts {:cwd (:dir opts)}))))]
    (when-not (zero? (or (.-status r) 1))
      (throw (js/Error. (str "shell failed: " (pr-str cmd)))))
    {:exit (or (.-status r) 0) :out "" :err ""}))
;; -----------------------------------------------------------------------
;; icloud-photos-sync.nbb — iCloud Drive + Apple Photos の継続同期 (ADR-0015 取込経路)。
;; launchd (com.junkawasaki.icloud-photos-sync) が日次起動。cid(annex MD5)で重複回避。
;;   iCloud Drive: brctl で実体化 → 新規のみ org/project へコピー (iCloud側は保持=source-of-truth)
;;   Photos      : osxphotos で索引(index.jsonl)更新 + :ingest-policy に従い原本を取込
;;     :metadata-only(既定) = 索引のみ / :local-only = ローカル原本のみ / :windowed = 日付窓 / :full = 全270GB
;; ⚠ 銀行ログイン同様、クラウド認証は本人セッション (Files/Photosアプリ) に依存。
(require ']
         '[clojure.string :as str]
         '         ')

(def base "/Users/junkawasaki/github/com-junkawasaki/orgs/personal")
(def root "/Users/junkawasaki/github/com-junkawasaki")
(def icloud (str (.-homedir __os) "/Library/Mobile Documents/com~apple~CloudDocs"))
(def oxp (str (.-homedir __os) "/.venvs/osxphotos/bin/osxphotos"))

;; ---------- cid (annex MD5) 既存集合 ----------
(defn annex-md5s []
  (let [out (:out (__sh {:dir root} "git" "annex" "find" "--include=*" "--format=${key}\n"))]
    (into #{} (keep #(second (re-find #"MD5E?-s\d+--([0-9a-f]{32})" %)) (str/split-lines out)))))

(defn md5 [path]
  (let [d (java.security.MessageDigest/getInstance "MD5")]
    (with-open [in (io/input-stream path)]
      (let [buf (byte-array (* 1024 1024))]
        (loop [] (let [n (.read in buf)] (when (pos? n) (.update d buf 0 n) (recur))))))
    (apply str (map #(format "%02x" %) (.digest d)))))

;; ---------- iCloud Drive 分類 (downloads/icloud と同方針) ----------
(def rules
  [[#"notahotel|profit_and_loss|損益"       "orgs/personal/litigation/lingling/evidence/finance"]
   [#"Doshisha|IOWN|Project_(Finance|Plan)" "orgs/personal/research/doshisha-iown"]
   [#"voicemail|Voicemail|留守"             "orgs/personal/voicemail"]
   [#"\.cer$|\.pem$|/keys/"                 "orgs/personal/_keys/icloud"]
   [#"ScanSnap|scan"                        "orgs/personal/drive/_intake/scansnap"]
   [#"\.m4a$|\.mp3$"                         "orgs/com-junkawasaki/yukkuri-assets-nist-csf-cis-scs/_intake"]])
(defn classify [rel]
  (or (some (fn [[re d]] (when (re-find re rel) d)) rules)
      "orgs/personal/drive/_intake/icloud"))

(defn- dataless?
  "iCloud退避ファイル = ローカルブロック0 (stat -f %b)。ブロッキング読込を避けるための判定。"
  [path]
  (try (zero? (Long/parseLong (str/trim (:out (__sh "stat" "-f" "%b" path))))) (catch Exception _ false)))

(defn sync-icloud-drive! [existing]
  (println "[iCloud Drive] 差分同期 (実体化済のみ処理; 退避分は brctl 予約して次回)")
  (let [all (->> (file-seq (__path.resolve icloud))
                 (filter #(and (.isFile %) (not (str/ends-with? (.getName %) ".icloud"))
                               (not= (.getName %) ".DS_Store"))))
        ;; 退避(dataless)は brctl download を投げて今回スキップ — 一切ブロックしない
        {dataless true ready false} (group-by #(dataless? (str %)) all)]
    (doseq [f dataless] (try (__sh "brctl" "download" (str f)) (catch Exception _ nil)))
    (when (seq dataless) (println (format "  退避 %d 件を brctl 予約 (次回取込)" (count dataless))))
    (let [new-dests (atom #{}) copied (atom 0)]
    (doseq [f ready
            :let [rel (subs (str f) (inc (count icloud)))
                  m   (try (md5 (str f)) (catch Exception _ nil))]
            :when (and m (not (contains? existing m)))]
      (let [dest (classify rel)
            dst-dir (__path.resolve root dest)
            dst (__path.resolve dst-dir (.getName f))]
        (.mkdirs dst-dir)
        (when-not (.exists dst)
          (io/copy f dst) (swap! copied inc) (swap! new-dests conj dest))))
    (println (format "  新規コピー %d 件 → %d dest" @copied (count @new-dests)))
    (when (pos? @copied)
      (doseq [d @new-dests] (__shell {:dir root} "git" "annex" "add" d))
      (apply __shell {:dir root :continue true} "git" "commit" "-m" "sync(icloud-drive): 差分取込 (icloud-photos-sync.bb)"
             (vec @new-dests))
      (println "  committed"))
    @copied)))

(defn read-policy []
  (let [edn (slurp (__path.resolve base "facts/photos-library.edn"))]
    (or (some-> (re-find #":photos/ingest-policy\s+:([a-z-]+)" edn) second keyword) :metadata-only)))

(defn sync-photos! [existing]
  (println "[Photos] 索引更新 + policy 取込")
  ;; 索引 (index.jsonl) を再生成。500MB級JSONの変換は Python に委譲 (bbのメモリ捕捉は脆弱)。
  (let [tmp "/tmp/photos-sync.json"
        idxf (str (__path.resolve base "photos/index.jsonl"))]
    (__sh oxp "query" "--json" "--mute" {:out (__path.resolve tmp)})
    (let [r (__sh "python3" (str base "/bin/photos-index.py") tmp idxf)]
      (println "  " (str/trim (str (:out r) (:err r))))))
  (let [policy (read-policy)
        outdir (__path.resolve base "photos/originals")]
    (println "  ingest-policy =" policy)
    (case policy
      :metadata-only (println "  原本取込なし (索引のみ)")
      :local-only (do (.mkdirs outdir)
                      (__shell oxp "export" (str outdir) "--skip-missing" "--update"
                             "--dirtemplate" "{created.year}/{created.mm}" "--mute")
                      (__shell {:dir root} "git" "annex" "add" "orgs/personal/photos/originals")
                      (__shell {:dir root :continue true} "git" "commit" "-m" "sync(photos): local originals" "orgs/personal/photos/originals"))
      :windowed (let [win (or (aget (.-env js/process) "PHOTOS_WINDOW") "2026-01-01")]
                  (.mkdirs outdir)
                  (__shell oxp "export" (str outdir) "--from-date" win "--download-missing" "--update"
                         "--dirtemplate" "{created.year}/{created.mm}" "--mute")
                  (__shell {:dir root} "git" "annex" "add" "orgs/personal/photos/originals")
                  (__shell {:dir root :continue true} "git" "commit" "-m" (str "sync(photos): window from " win) "orgs/personal/photos/originals"))
      :full (do (.mkdirs outdir)
                (__shell oxp "export" (str outdir) "--download-missing" "--update"
                       "--dirtemplate" "{created.year}/{created.mm}" "--mute")
                (__shell {:dir root} "git" "annex" "add" "orgs/personal/photos/originals")
                (__shell {:dir root :continue true} "git" "commit" "-m" "sync(photos): full originals" "orgs/personal/photos/originals"))
      (println "  unknown policy — skip"))))

(defn -main [& _]
  (println "=== icloud-photos-sync" (.slice (.toISOString (js/Date.)) 0 10) "===")
  (let [existing (annex-md5s)]
    (println "annex cid 既存:" (count existing))
    (sync-icloud-drive! existing)
    (sync-photos! existing))
  ;; 索引の commit (常に)
  (let [st (:out (__sh {:dir root} "git" "status" "--short" "orgs/personal/photos/index.jsonl"))]
    (when (seq (str/trim st))
      (__shell {:dir root} "git" "annex" "add" "orgs/personal/photos/index.jsonl")
      (__shell {:dir root :continue true} "git" "commit" "-m" "sync(photos): 索引更新" "orgs/personal/photos/index.jsonl")))
  (println "done."))

(-main)
