#!/usr/bin/env nbb
;; scripts/fleet-sync-tick.cljs — the fleet's synchronisation DETECTION on a schedule.
;;
;; ADR-2608040100 measured that of the six pairwise consistency obligations between
;; the four planes, four now have a checker and **zero of them are scheduled**: every
;; one runs only when a person types it. That is the balancing loop's whole weakness
;; — its observed period was 8.0 days, which is simply how long it happened to be
;; between two hand-run reconciles. This script is what makes that period a setting
;; instead of an accident.
;;
;; **What it deliberately does NOT do: write.** It detects and records; absorbing the
;; drift (`fleet reconcile` without --check) and landing WIP stay agent/human actions.
;; Two reasons, both concrete: (1) an unattended writer to `main` is a much larger
;; commitment than an unattended reader, and the last automated absorber was removed
;; on 2026-07-30 (ADR-2607300900); (2) under launchd this process cannot count on the
;; credentials a push needs — the sibling fleet-ci tick's own plist documents that
;; kagi cannot show a Keychain prompt there, and its GH-token directory
;; (~/.gftd/fleet-ci-gh-tokens) does not exist on this machine, which is why its log
;; tail is a string of rejected pushes. A detector that silently degrades is worse
;; than no detector, so this one fails loudly and records the failure as an event.
;;
;; usage:
;;   nbb scripts/fleet-sync-tick.cljs check     ;; fleet-db <-> west.yml drift, against main
;;   nbb scripts/fleet-sync-tick.cljs probe     ;; full fleet sync probe (slow, ~5 min)
;;   nbb scripts/fleet-sync-tick.cljs both
;;
;; Every run appends exactly one EDN map to ~/.gftd/fleet-sync/ledger.edn — including
;; runs that fail. That file is a time series: it is what lets the next reading say
;; "the stock moved" instead of "here is a number".

(ns fleet-sync-tick
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]))

(def root (or (.-FLEET_ROOT js/process.env) "/Users/junkawasaki/github/com-junkawasaki"))
(def out-dir (path/join (os/homedir) ".gftd" "fleet-sync"))
(def ledger (path/join out-dir "ledger.edn"))
(def kagami (path/join root "orgs" "kotoba-lang" "kagami"))

(defn- now [] (.toISOString (js/Date.)))
(defn- log [& xs] (js/console.error (str (now) " " (str/join " " xs))))
(defn- ensure-dir! [d] (fs/mkdirSync d #js {:recursive true}))

(defn- sh
  "Run a command, returning {:ok? :out :err :code}. Never throws: a failing step is
   an event to record, not a reason to lose the whole tick."
  [cmd args & [{:keys [cwd timeout]}]]
  (try
    (let [r (cp/spawnSync cmd (clj->js args)
                          #js {:encoding "utf8" :cwd (or cwd root)
                               :timeout (or timeout 900000)
                               :maxBuffer (* 64 1024 1024)})]
      {:ok? (zero? (or (.-status r) 1))
       :code (.-status r)
       :out (str (.-stdout r))
       :err (str (.-error r) (.-stderr r))})
    (catch :default e {:ok? false :code -1 :out "" :err (str e)})))

(defn- append-event! [m]
  (ensure-dir! out-dir)
  (fs/appendFileSync ledger (str (pr-str (assoc m :at (now))) "\n")))

;; ---------------------------------------------------------------------------
;; lock — one tick per mode at a time. A probe takes minutes; overlapping runs
;; would multiply the git process count on a machine already running many agents.
;; ---------------------------------------------------------------------------

(defn- lock-path [mode] (path/join out-dir (str "tick-" (name mode) ".lock")))

(defn- alive? [pid]
  (try (js/process.kill pid 0) true (catch :default _ false)))

(defn- acquire! [mode]
  (ensure-dir! out-dir)
  (let [lp (lock-path mode)]
    (when (fs/existsSync lp)
      (let [pid (js/parseInt (str/trim (str (fs/readFileSync lp "utf8"))) 10)]
        (if (and pid (alive? pid))
          (do (log "another" (name mode) "tick is running (pid" pid ") — skipping")
              (js/process.exit 0))
          (log "reclaiming stale lock for" (name mode)))))
    (fs/writeFileSync lp (str js/process.pid))))

(defn- release! [mode]
  (try (fs/unlinkSync (lock-path mode)) (catch :default _)))

;; ---------------------------------------------------------------------------
;; check: fleet-db <-> west.yml, against MAIN rather than the local checkout
;;
;; The local superproject checkout is routinely parked on a session branch that is
;; far behind main (measured: 85 commits behind while this was being written), so a
;; check run against it would report the drift of a stale copy and call it the
;; fleet's state. Same invariant the sibling fleet-ci tick states for its own gates:
;; do not trust the local checkout.
;; ---------------------------------------------------------------------------

(defn- fetch-main-file! [repo-path dest]
  (let [r (sh "gh" ["api" (str "repos/com-junkawasaki/root/contents/" repo-path "?ref=main")
                    "-H" "Accept: application/vnd.github.raw"])]
    (if (and (:ok? r) (seq (:out r)))
      (do (fs/writeFileSync dest (:out r)) true)
      (do (log "FAIL fetching" repo-path (subs (str (:err r)) 0 200)) false))))

(defn- parse-drift
  "`fleet reconcile --check` prints `clean: ...` or `DRIFT: {:changed N :added N ...}`
   and exits 1 on drift. Exit code alone cannot distinguish drift from a crash, so
   the summary is parsed out of stdout and a run that produced neither marker is
   recorded as :unparseable rather than quietly as clean."
  [out]
  (cond
    (str/includes? out "clean:") {:status :clean}
    (str/includes? out "DRIFT:")
    (let [m (re-find #"DRIFT:\s*(\{[^}]*\})" out)]
      {:status :drift :summary (or (second m) "unparsed")})
    :else {:status :unparseable}))

(defn run-check! []
  (let [tmp (fs/mkdtempSync (path/join (os/tmpdir) "fleet-sync-check-"))
        west (path/join tmp "west.yml")
        db (path/join tmp "fleet-db.edn")
        led (path/join tmp "fleet-db.ledger.edn")]
    (if-not (and (fetch-main-file! "manifest/west.yml" west)
                 (fetch-main-file! "manifest/fleet-db.edn" db)
                 (fetch-main-file! "manifest/fleet-db.ledger.edn" led))
      (append-event! {:tick :check :status :fetch-failed
                      :note "could not read main's manifest through gh — under launchd this usually means the GitHub token is not reachable from this session. Loud on purpose."})
      (let [r (sh "nbb" ["--classpath" (path/join kagami "src")
                         (path/join kagami "bin" "fleet.cljs")
                         "reconcile" "--db" db "--west" west "--check"]
                  {:cwd tmp})
            d (parse-drift (:out r))]
        (log "check ->" (pr-str d))
        (append-event! (merge {:tick :check} d
                              (when (= :unparseable (:status d))
                                {:stderr (subs (str (:err r)) 0 300)})
                              (when (= :drift (:status d))
                                {:action-required "nbb .../fleet.cljs reconcile --db manifest/fleet-db.edn --west manifest/west.yml (absorb), then land manifest/fleet-db.edn + .ledger.edn on main"})))))
    (try (fs/rmSync tmp #js {:recursive true :force true}) (catch :default _))))

;; ---------------------------------------------------------------------------
;; probe: the full fleet sync measurement
;; ---------------------------------------------------------------------------

(defn run-probe! []
  (ensure-dir! out-dir)
  (let [dated (path/join out-dir (str "probe-" (subs (now) 0 10) ".edn"))
        latest (path/join out-dir "probe-latest.edn")
        r (sh "nbb" [(path/join root "scripts" "fleet-sync-probe.cljs") "--edn" dated])]
    (if-not (and (:ok? r) (fs/existsSync dated))
      (append-event! {:tick :probe :status :failed :stderr (subs (str (:err r)) 0 300)})
      (let [edn (str (fs/readFileSync dated "utf8"))
            grab (fn [re] (second (re-find re edn)))]
        (fs/copyFileSync dated latest)
        (append-event! {:tick :probe :status :ok
                        :file dated
                        ;; a compact projection so the ledger is greppable without
                        ;; re-reading a 4,000-repo dump for every trend question
                        :materialised (some-> (grab #":materialised (\d+)") js/parseInt)
                        :diverged (some-> (grab #":diverged-total (\d+)") js/parseInt)
                        :not-relatable (some-> (grab #":not-relatable (\d+)") js/parseInt)
                        :median-fetch-age-days (some-> (grab #":median-days ([0-9.]+)") js/parseFloat)
                        :fetched-last-24h (some-> (grab #":fetched-last-24h (\d+)") js/parseInt)
                        :rad-divergence-pct (some-> (grab #":divergence-pct ([0-9.]+)") js/parseFloat)})
        (log "probe ok ->" dated)))))

;; ---------------------------------------------------------------------------

(defn -main [& argv]
  (let [mode (keyword (or (first argv) "both"))]
    (when-not (#{:check :probe :both} mode)
      (js/console.error "usage: fleet-sync-tick.cljs <check|probe|both>")
      (js/process.exit 2))
    (acquire! mode)
    (try
      (when (#{:check :both} mode) (run-check!))
      (when (#{:probe :both} mode) (run-probe!))
      (finally (release! mode)))))

;; nbb exposes the script's own arguments as *command-line-args*; js/process.argv
;; still carries node's and nbb's own entries in front, and dropping a fixed count
;; from it is how this silently read its own path as the mode on the first run.
(apply -main (vec *command-line-args*))
