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
                  (str (os/homedir) "/.gftd/worktrees/dns-resolver-resident")))
(def out-dir (or (aget js/process.env "DNS_RESOLVER_LAKE_DIR")
                 (str (os/homedir) "/.gftd/dns-resolver-lake")))
(def state-path (path/join out-dir "state.json"))
(def ledger-dir (path/join worktree "data" "ledger"))

(defn die! [code msg] (js/console.error msg) (.exit js/process code))

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
  (let [{:keys [files sig]} (ledger-signature)
        prev (read-state)]
    (println (str "LEDGER\t" files " files\tsig=" (subs sig 0 12)))
    (if (and (not force?) (= sig (:sig prev)))
      (do (println (str "current\tlake matches the ledger (last synced " (:synced-at prev) ")"))
          (.exit js/process 0))
      (let [_ (fs/mkdirSync out-dir #js {:recursive true})
            ;; export_and_sync.cljs now persists an accumulator across runs
            ;; instead of re-parsing the whole ledger every tick (found
            ;; live 2026-08-28: re-parsing ~1.6GB of accumulated EDN every
            ;; run OOM'd on the default ~4GB heap) — this raised ceiling is
            ;; a backstop for the accumulator itself (and any bootstrap/
            ;; --force full-reparse) still growing large as the ledger
            ;; scales toward world-scale coverage, not the fix itself.
            r (cp/spawnSync "nbb" #js ["--classpath" "src" "scripts/export_and_sync.cljs"
                                       "--root" root "--out-dir" out-dir]
                            #js {:encoding "utf8" :cwd worktree
                                :env (js/Object.assign #js {} js/process.env
                                                       #js {"NODE_OPTIONS" "--max-old-space-size=8192"})})]
        (print (or (.-stdout r) ""))
        (when (seq (.-stderr r)) (print (.-stderr r)))
        (when (pos? (or (.-status r) 1))
          (die! (if (= 2 (.-status r)) 2 1) (str "export/sync failed (" (.-status r) ")")))
        (fs/writeFileSync state-path
                          (js/JSON.stringify (clj->js {"sig" sig "files" files
                                                       "synced-at" (.toISOString (js/Date.))})))
        (println (str "SYNCED\tsig=" (subs sig 0 12) " -> cloud_itonami.dns_resolution"))))))

(-main)
