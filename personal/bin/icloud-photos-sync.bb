#!/usr/bin/env bb
;; icloud-photos-sync.bb — iCloud Drive + Apple Photos の継続同期 (ADR-0015 取込経路)。
;; launchd (com.junkawasaki.icloud-photos-sync) が日次起動。cid(annex MD5)で重複回避。
;;   iCloud Drive: brctl で実体化 → 新規のみ org/project へコピー (iCloud側は保持=source-of-truth)
;;   Photos      : osxphotos で索引(index.jsonl)更新 + :ingest-policy に従い原本を取込
;;     :metadata-only(既定) = 索引のみ / :local-only = ローカル原本のみ / :windowed = 日付窓 / :full = 全270GB
;; ⚠ 銀行ログイン同様、クラウド認証は本人セッション (Files/Photosアプリ) に依存。
(require '[babashka.process :refer [shell sh]]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[cheshire.core :as json])

(def base "/Users/junkawasaki/github/com-junkawasaki/personal")
(def root "/Users/junkawasaki/github/com-junkawasaki")
(def icloud (str (System/getProperty "user.home") "/Library/Mobile Documents/com~apple~CloudDocs"))
(def oxp (str (System/getProperty "user.home") "/.venvs/osxphotos/bin/osxphotos"))

;; ---------- cid (annex MD5) 既存集合 ----------
(defn annex-md5s []
  (let [out (:out (sh {:dir root} "git" "annex" "find" "--include=*" "--format=${key}\n"))]
    (into #{} (keep #(second (re-find #"MD5E?-s\d+--([0-9a-f]{32})" %)) (str/split-lines out)))))

(defn md5 [path]
  (let [d (java.security.MessageDigest/getInstance "MD5")]
    (with-open [in (io/input-stream path)]
      (let [buf (byte-array (* 1024 1024))]
        (loop [] (let [n (.read in buf)] (when (pos? n) (.update d buf 0 n) (recur))))))
    (apply str (map #(format "%02x" %) (.digest d)))))

;; ---------- iCloud Drive 分類 (downloads/icloud と同方針) ----------
(def rules
  [[#"notahotel|profit_and_loss|損益"       "personal/litigation/lingling/evidence/finance"]
   [#"Doshisha|IOWN|Project_(Finance|Plan)" "personal/research/doshisha-iown"]
   [#"voicemail|Voicemail|留守"             "personal/voicemail"]
   [#"\.cer$|\.pem$|/keys/"                 "personal/_keys/icloud"]
   [#"ScanSnap|scan"                        "personal/drive/_intake/scansnap"]
   [#"\.m4a$|\.mp3$"                         "orgs/com-junkawasaki/yukkuri-assets-nist-csf-cis-scs/_intake"]])
(defn classify [rel]
  (or (some (fn [[re d]] (when (re-find re rel) d)) rules)
      "personal/drive/_intake/icloud"))

(defn- dataless?
  "iCloud退避ファイル = ローカルブロック0 (stat -f %b)。ブロッキング読込を避けるための判定。"
  [path]
  (try (zero? (Long/parseLong (str/trim (:out (sh "stat" "-f" "%b" path))))) (catch Exception _ false)))

(defn sync-icloud-drive! [existing]
  (println "[iCloud Drive] 差分同期 (実体化済のみ処理; 退避分は brctl 予約して次回)")
  (let [all (->> (file-seq (io/file icloud))
                 (filter #(and (.isFile %) (not (str/ends-with? (.getName %) ".icloud"))
                               (not= (.getName %) ".DS_Store"))))
        ;; 退避(dataless)は brctl download を投げて今回スキップ — 一切ブロックしない
        {dataless true ready false} (group-by #(dataless? (.getPath %)) all)]
    (doseq [f dataless] (try (sh "brctl" "download" (.getPath f)) (catch Exception _ nil)))
    (when (seq dataless) (println (format "  退避 %d 件を brctl 予約 (次回取込)" (count dataless))))
    (let [new-dests (atom #{}) copied (atom 0)]
    (doseq [f ready
            :let [rel (subs (.getPath f) (inc (count icloud)))
                  m   (try (md5 (.getPath f)) (catch Exception _ nil))]
            :when (and m (not (contains? existing m)))]
      (let [dest (classify rel)
            dst-dir (io/file root dest)
            dst (io/file dst-dir (.getName f))]
        (.mkdirs dst-dir)
        (when-not (.exists dst)
          (io/copy f dst) (swap! copied inc) (swap! new-dests conj dest))))
    (println (format "  新規コピー %d 件 → %d dest" @copied (count @new-dests)))
    (when (pos? @copied)
      (doseq [d @new-dests] (shell {:dir root} "git" "annex" "add" d))
      (apply shell {:dir root :continue true} "git" "commit" "-m" "sync(icloud-drive): 差分取込 (icloud-photos-sync.bb)"
             (vec @new-dests))
      (println "  committed"))
    @copied)))

(defn read-policy []
  (let [edn (slurp (io/file base "facts/photos-library.edn"))]
    (or (some-> (re-find #":photos/ingest-policy\s+:([a-z-]+)" edn) second keyword) :metadata-only)))

(defn sync-photos! [existing]
  (println "[Photos] 索引更新 + policy 取込")
  ;; 索引 (index.jsonl) を再生成 — osxphotos query --json → 軽量化
  (let [tmp "/tmp/photos-sync.json"]
    (spit tmp (:out (sh oxp "query" "--json" "--mute")))
    (let [assets (json/parse-string (slurp tmp) true)]
      (with-open [w (io/writer (io/file base "photos/index.jsonl"))]
        (doseq [a (sort-by (juxt #(str (:date %)) :uuid) assets)]
          (.write w (str (json/generate-string
                          {:uuid (:uuid a) :name (:original_filename a)
                           :date (some-> (:date a) (subs 0 19)) :kind (if (:ismovie a) "movie" "photo")
                           :fav (boolean (:favorite a)) :size (or (:original_filesize a) 0)
                           :missing (boolean (:ismissing a))
                           :albums (:albums a) :persons (remove #{"_UNKNOWN_"} (:persons a))
                           :lat (:latitude a) :lon (:longitude a)}) "\n"))))
      (println (format "  索引 %d 件更新" (count assets)))))
  (let [policy (read-policy)
        outdir (io/file base "photos/originals")]
    (println "  ingest-policy =" policy)
    (case policy
      :metadata-only (println "  原本取込なし (索引のみ)")
      :local-only (do (.mkdirs outdir)
                      (shell oxp "export" (.getPath outdir) "--skip-missing" "--update"
                             "--dirtemplate" "{created.year}/{created.mm}" "--mute")
                      (shell {:dir root} "git" "annex" "add" "personal/photos/originals")
                      (shell {:dir root :continue true} "git" "commit" "-m" "sync(photos): local originals" "personal/photos/originals"))
      :windowed (let [win (or (System/getenv "PHOTOS_WINDOW") "2026-01-01")]
                  (.mkdirs outdir)
                  (shell oxp "export" (.getPath outdir) "--from-date" win "--download-missing" "--update"
                         "--dirtemplate" "{created.year}/{created.mm}" "--mute")
                  (shell {:dir root} "git" "annex" "add" "personal/photos/originals")
                  (shell {:dir root :continue true} "git" "commit" "-m" (str "sync(photos): window from " win) "personal/photos/originals"))
      :full (do (.mkdirs outdir)
                (shell oxp "export" (.getPath outdir) "--download-missing" "--update"
                       "--dirtemplate" "{created.year}/{created.mm}" "--mute")
                (shell {:dir root} "git" "annex" "add" "personal/photos/originals")
                (shell {:dir root :continue true} "git" "commit" "-m" "sync(photos): full originals" "personal/photos/originals"))
      (println "  unknown policy — skip"))))

(defn -main [& _]
  (println "=== icloud-photos-sync" (str (java.time.LocalDate/now)) "===")
  (let [existing (annex-md5s)]
    (println "annex cid 既存:" (count existing))
    (sync-icloud-drive! existing)
    (sync-photos! existing))
  ;; 索引の commit (常に)
  (let [st (:out (sh {:dir root} "git" "status" "--short" "personal/photos/index.jsonl"))]
    (when (seq (str/trim st))
      (shell {:dir root} "git" "annex" "add" "personal/photos/index.jsonl")
      (shell {:dir root :continue true} "git" "commit" "-m" "sync(photos): 索引更新" "personal/photos/index.jsonl")))
  (println "done."))

(-main)
