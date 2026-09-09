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
                  (str (os/homedir) "/.itonami/worktrees/dns-resolver-resident")))

(defn lock-path-for
  "One lock PER SOURCE, not one lock for the whole script — tranco (hourly)
  and commoncrawl (every 15min) run on independent schedules and must not
  serialize behind each other. Found live: without this, both jobs raced
  for literally the same lock file and the more frequent one (commoncrawl)
  lost every time to the slower one (tranco) still mid-fetch."
  [source]
  (str (os/homedir) "/.itonami/locks/dns-resolver-" (or source "tick") ".lock"))

(defn source-arg
  "Pull --source's value out of argv (defaults to \"tranco\", matching
  resolve_tick.cljs's own default) — needed before argv is otherwise
  parsed, just to know which lock file this run claims."
  [argv]
  (let [i (.indexOf (clj->js argv) "--source")]
    (if (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)) "tranco")))

(defn run
  "spawnSync with a hard wall-clock ceiling. Found live: the very first
  launchd RunAtLoad execution hung past resolve_tick.cljs's OWN 120s
  fetch-abort budget (ps showed 3m19s elapsed, 0.62s CPU time — genuinely
  stuck waiting on I/O, not spinning) with no timeout error ever printed.
  Root cause unconfirmed (possibly launchd's Background ProcessType
  affecting network I/O timing in a way an in-process AbortController
  didn't catch) — rather than chase it further, this makes the OUTER
  process responsible for its own child never running forever, which is
  the property that actually matters for a scheduled resident."
  [args opts]
  (let [r (cp/spawnSync (first args) (clj->js (rest args))
                        (clj->js (merge {:encoding "utf8" :cwd worktree
                                        :timeout 300000
                                        :maxBuffer (* 64 1024 1024)} opts)))]
    {:exit (if (nil? (.-status r)) 1 (.-status r))
     :signal (.-signal r)
     :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn checked! [args opts]
  (let [{:keys [exit signal out err]} (run args opts)]
    (when (pos? exit)
      (throw (js/Error. (str (str/join " " args) " (exit " exit
                             (when signal (str " signal=" signal " — likely the spawnSync :timeout"))
                             "): " err out))))
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

(defn release-lock! [lp] (fs/rmSync lp #js {:force true}))

(defn exit! [lp code]
  ;; process.exit() does not unwind — a `finally` after this call would
  ;; never run (found live: the first failed test run left its lock
  ;; behind because .exit was called from inside a catch). Release the
  ;; lock explicitly at every exit point instead of trusting try/finally.
  (release-lock! lp)
  (.exit js/process code))

(defn claim-lock-or-die! [lp]
  (when (fs/existsSync lp)
    (let [pid (js/parseInt (str/trim (fs/readFileSync lp "utf8")))]
      (if (alive? pid)
        (do (println (str "REFUSED: lock held by live pid " pid " at " lp))
            (.exit js/process 2)) ; nothing to release — this run never claimed it
        (do (println (str "reclaiming lock from dead pid " pid))
            (release-lock! lp)))))
  (fs/mkdirSync (path/dirname lp) #js {:recursive true})
  (fs/writeFileSync lp (str (.-pid js/process))))

(defn -main [& argv]
  (let [lp (lock-path-for (source-arg argv))]
    (claim-lock-or-die! lp)
    (try
      (println (str "sync " worktree))
      (checked! ["git" "fetch" "cloud-itonami"] {})
      (checked! ["git" "merge" "--ff-only" "cloud-itonami/main"] {})
      (println (str "tick " (str/join " " argv)))
      ;; resolve_tick.cljs self-limits to --max-duration-sec (default 600s)
      ;; but the OUTER spawnSync timeout must stay comfortably above that,
      ;; not at the 300s default (which would kill a legitimately-running
      ;; tick before its own time budget even expires) — 800s covers the
      ;; 600s work budget plus fixed overhead (nbb boot, cache-file scan)
      ;; with real margin.
      (println (checked! (into ["nbb" "--classpath" "src" "scripts/resolve_tick.cljs" "--live"] argv)
                         {:timeout 800000
                          :env (js/Object.assign #js {} js/process.env
                                                 #js {"DNS_RESOLVER_OPERATOR_GATE" "open"})}))
      (let [status (:out (run ["git" "status" "--porcelain" "--" "data/ledger"] {}))]
        (if (str/blank? status)
          (println "no new ledger files — nothing to commit")
          (do
            ;; data/ledger is git-annex (B2 special remote), not plain git —
            ;; found live 2026-08-28: a tick that hit its full --n within
            ;; budget wrote one 158MB ledger file, over GitHub's 100MB
            ;; limit, and plain `git add`/push kept silently failing forever
            ;; against the same stuck commit. `git annex add` (which
            ;; `datalad save` drives) routes data/ledger/** to B2 per
            ;; .gitattributes, so only a small pointer ever lands in git —
            ;; resolve_tick.cljs's own max-domains-per-ledger-file cap is
            ;; the root-cause fix; this is the transport-level backstop.
            (checked! ["datalad" "save" "-d" "." "-m"
                      (str "ingest: resident tick (" (str/join " " argv) ")")
                      "--" "data/ledger"] {})
            (checked! ["datalad" "push" "-d" "." "--to" "b2"] {})
            (checked! ["git" "push" "cloud-itonami" "resident/dns-resolver:main"] {})
            (println "pushed"))))
      (release-lock! lp)
      (catch :default e
        (binding [*print-fn* *print-err-fn*] (println (str "FAIL " (.-message e))))
        (exit! lp 1)))))

(apply -main *command-line-args*)
