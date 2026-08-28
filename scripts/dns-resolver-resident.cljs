#!/usr/bin/env nbb
;; Resident operator for cloud-itonami/cloud-itonami-dns-resolver.
;;
;; Runs one bounded G7-gated resolve_tick.cljs tick in a dedicated worktree
;; (never the shared west-managed checkout — CLAUDE.md「並行エージェント運用」),
;; then commits the new ledger file(s) and pushes to main. Simpler than
;; hyakka-knowledge-resident.cljs on purpose: no B2 upload, no Kotobase
;; tenant-DID concern — this tool's only durable output is the git ledger
;; itself (source of truth) and, via a separate resident, the rebuildable
;; Iceberg projection.
;;
;;   nbb scripts/dns-resolver-resident.cljs --source tranco|commoncrawl [--n N]

(require '[clojure.string :as str]
         '["node:child_process" :as cp]
         '["node:fs" :as fs]
         '["node:path" :as path]
         '["node:os" :as os])

(def worktree (or (aget js/process.env "DNS_RESOLVER_WORKTREE")
                  (str (os/homedir) "/.gftd/worktrees/dns-resolver-resident")))
(def lock-path (str (os/homedir) "/.gftd/locks/dns-resolver-" (or (aget js/process.env "TICK_SOURCE") "tick") ".lock"))

(defn run [args opts]
  (let [r (cp/spawnSync (first args) (clj->js (rest args))
                        (clj->js (merge {:encoding "utf8" :cwd worktree
                                        :maxBuffer (* 64 1024 1024)} opts)))]
    {:exit (if (nil? (.-status r)) 1 (.-status r))
     :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn checked! [args opts]
  (let [{:keys [exit out err]} (run args opts)]
    (when (pos? exit)
      (throw (js/Error. (str (str/join " " args) " (exit " exit "): " err out))))
    out))

(defn alive?
  "Is `pid` a live process owned by this user? Anything we cannot answer is
  treated as ALIVE — reclaiming a lock we merely failed to interrogate is
  the dangerous direction (same rule hyakka-knowledge-resident.cljs uses)."
  [pid]
  (if-not (and (number? pid) (pos? pid))
    true
    (try (.kill js/process pid 0) true
         (catch :default e (not= "ESRCH" (.-code e))))))

(defn release-lock! [] (fs/rmSync lock-path #js {:force true}))

(defn exit! [code]
  ;; process.exit() does not unwind — a `finally` after this call would
  ;; never run (found live: the first failed test run left its lock
  ;; behind because .exit was called from inside a catch). Release the
  ;; lock explicitly at every exit point instead of trusting try/finally.
  (release-lock!)
  (.exit js/process code))

(defn claim-lock-or-die! []
  (when (fs/existsSync lock-path)
    (let [pid (js/parseInt (str/trim (fs/readFileSync lock-path "utf8")))]
      (if (alive? pid)
        (do (println (str "REFUSED: lock held by live pid " pid " at " lock-path))
            (.exit js/process 2)) ; nothing to release — this run never claimed it
        (do (println (str "reclaiming lock from dead pid " pid))
            (release-lock!)))))
  (fs/mkdirSync (path/dirname lock-path) #js {:recursive true})
  (fs/writeFileSync lock-path (str (.-pid js/process))))

(defn -main [& argv]
  (claim-lock-or-die!)
  (try
    (println (str "sync " worktree))
    (checked! ["git" "fetch" "cloud-itonami"] {})
    (checked! ["git" "merge" "--ff-only" "cloud-itonami/main"] {})
    (println (str "tick " (str/join " " argv)))
    (println (checked! (into ["nbb" "--classpath" "src" "scripts/resolve_tick.cljs" "--live"] argv)
                       {:env (js/Object.assign #js {} js/process.env
                                               #js {"DNS_RESOLVER_OPERATOR_GATE" "open"})}))
    (let [status (:out (run ["git" "status" "--porcelain" "--" "data/ledger"] {}))]
      (if (str/blank? status)
        (println "no new ledger files — nothing to commit")
        (do
          (checked! ["git" "add" "data/ledger"] {})
          (checked! ["git" "commit" "-m" (str "ingest: resident tick (" (str/join " " argv) ")")] {})
          (checked! ["git" "push" "cloud-itonami" "resident/dns-resolver:main"] {})
          (println "pushed"))))
    (release-lock!)
    (catch :default e
      (binding [*print-fn* *print-err-fn*] (println (str "FAIL " (.-message e))))
      (exit! 1))))

(apply -main *command-line-args*)
