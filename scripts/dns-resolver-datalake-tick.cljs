#!/usr/bin/env nbb
;; dns-resolver-datalake-tick.cljs — cloud-itonami-dns-resolver's ledger ->
;; R2 Data Catalog (Iceberg). Same shape as hyakka-datalake-tick.cljs and
;; the same reason: `datalake-sync.py` does a full overwrite every run, so
;; re-syncing an unchanged ledger just adds a snapshot nobody needed. This
;; takes a signature of the ledger dir (file names + sizes — the same
;; commit-untracked-safe measure hyakka-datalake-tick.cljs uses) and skips
;; export_and_sync.cljs entirely when nothing moved since last time.
;;
;;     nbb scripts/dns-resolver-datalake-tick.cljs [--force]

(require '[clojure.string :as str]
         '["node:fs" :as fs]
         '["node:path" :as path]
         '["node:os" :as os]
         '["node:crypto" :as crypto]
         '["node:child_process" :as cp])

(def argv (vec (drop 3 (js->clj (.-argv js/process)))))
(def force? (some? (some #{"--force"} argv)))

(def root "/Users/junkawasaki/github/com-junkawasaki")
(def worktree (or (aget js/process.env "DNS_RESOLVER_WORKTREE")
                  (str (os/homedir) "/.itonami/worktrees/dns-resolver-resident")))
(def out-dir (or (aget js/process.env "DNS_RESOLVER_LAKE_DIR")
                 (str (os/homedir) "/.itonami/dns-resolver-lake")))
(def state-path (path/join out-dir "state.json"))
(def ledger-dir (path/join worktree "data" "ledger"))
(def lock-path (str (os/homedir) "/.itonami/locks/dns-resolver-datalake.lock"))

(defn die! [code msg] (js/console.error msg) (.exit js/process code))

;; Found live (2026-08-29): this job had NO lock, unlike
;; dns-resolver-resident.cljs's per-source locks. As accumulator.ndjson
;; grew (12M+ rows), a single run's load+resync started taking longer
;; than the hourly StartInterval, so a second invocation could start
;; while the first was still mid-run -- both reading/writing the same
;; accumulator.ndjson/folded-files.json checkpoint files independently,
;; each unaware of the other's progress. Same lock pattern as the
;; resident ingest scripts.
(defn alive? [pid]
  (if-not (and (number? pid) (pos? pid))
    true
    (try (.kill js/process pid 0) true
         (catch :default e (not= "ESRCH" (.-code e))))))

(defn release-lock! [] (fs/rmSync lock-path #js {:force true}))

(defn exit! [code] (release-lock!) (.exit js/process code))

(defn claim-lock-or-die! []
  (when (fs/existsSync lock-path)
    (let [pid (js/parseInt (str/trim (fs/readFileSync lock-path "utf8")))]
      (if (alive? pid)
        (do (println (str "REFUSED: lock held by live pid " pid " at " lock-path))
            (.exit js/process 2))
        (do (println (str "reclaiming lock from dead pid " pid))
            (release-lock!)))))
  (fs/mkdirSync (path/dirname lock-path) #js {:recursive true})
  (fs/writeFileSync lock-path (str (.-pid js/process))))

(defn ledger-signature []
  (when-not (fs/existsSync ledger-dir)
    (die! 2 (str "REFUSED: no ledger at " ledger-dir)))
  (let [files (sort (mapcat (fn walk [p]
                              (let [st (fs/statSync p)]
                                (if (.isDirectory st)
                                  (mapcat walk (sort (map #(path/join p %) (fs/readdirSync p))))
                                  (when (str/ends-with? p ".edn") [p]))))
                            [ledger-dir]))
        h (crypto/createHash "sha256")]
    (doseq [f files] (.update h (str f ":" (.-size (fs/statSync f)) "\n")))
    {:files (count files) :sig (.digest h "hex")}))

(defn read-state []
  (try (js->clj (js/JSON.parse (fs/readFileSync state-path "utf8")) :keywordize-keys true)
       (catch :default _ nil)))

(defn -main []
  (claim-lock-or-die!)
  (try
    (let [{:keys [files sig]} (ledger-signature)
          prev (read-state)]
      (println (str "LEDGER\t" files " files\tsig=" (subs sig 0 12)))
      (if (and (not force?) (= sig (:sig prev)))
        (do (println (str "current\tlake matches the ledger (last synced " (:synced-at prev) ")"))
            (exit! 0))
        (let [_ (fs/mkdirSync out-dir #js {:recursive true})
              ;; export_and_sync.cljs now persists an accumulator across
              ;; runs instead of re-parsing the whole ledger every tick
              ;; (found live 2026-08-28: re-parsing ~1.6GB of accumulated
              ;; EDN every run OOM'd on the default ~4GB heap). This
              ;; raised ceiling is a backstop for the accumulator itself
              ;; still growing large as the ledger scales toward
              ;; world-scale coverage, not the fix itself.
              ;;
              ;; ⚠ KNOWN GAP, not fixed here (2026-08-29): the accumulator
              ;; is held in memory whole on EVERY run because the Iceberg
              ;; sync is always a full `t.overwrite(tbl)` (datalake-sync.py
              ;; has no incremental append/upsert path). This ceiling WILL
              ;; be hit again as the ledger keeps growing -- raising it
              ;; further only buys time, it does not remove the wall. The
              ;; actual fix is incremental Iceberg append/upsert in
              ;; datalake-sync.py (shared infra, needs its own session).
              ;; See 90-docs/adr/2608280900-world-scale-dns-domain-collection.edn.
              r (cp/spawnSync "nbb" #js ["--classpath" "src" "scripts/export_and_sync.cljs"
                                         "--root" root "--out-dir" out-dir]
                              #js {:encoding "utf8" :cwd worktree
                                  :env (js/Object.assign #js {} js/process.env
                                                         #js {"NODE_OPTIONS" "--max-old-space-size=16384"})})]
          (print (or (.-stdout r) ""))
          (when (seq (.-stderr r)) (print (.-stderr r)))
          (when (pos? (or (.-status r) 1))
            (println (str "FAIL export/sync (exit " (.-status r) ")"))
            (exit! (if (= 2 (.-status r)) 2 1)))
          (fs/writeFileSync state-path
                            (js/JSON.stringify (clj->js {"sig" sig "files" files
                                                         "synced-at" (.toISOString (js/Date.))})))
          (println (str "SYNCED\tsig=" (subs sig 0 12) " -> cloud_itonami.dns_resolution"))
          (exit! 0))))
    (catch :default e
      (binding [*print-fn* *print-err-fn*] (println (str "FAIL " (.-message e))))
      (exit! 1))))

(-main)
