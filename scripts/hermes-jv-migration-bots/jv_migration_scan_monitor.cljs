;; jv_migration_scan_monitor.cljs — the change signal for jv-migration-scan-watch.
;;
;; ## The contract, read from the runner rather than guessed
;;
;; `hermes_cli/cron.py` prints "(agent runs only on output change)" next to a
;; job's monitor and tracks `monitor_state.last_changed_at`. So a monitor is
;; not asked to decide anything: it prints something deterministic, the runner
;; diffs that against the previous run's output, and the agent is woken only
;; when the two differ. Identical output skips the turn entirely.
;;
;; Which makes this monitor's whole job to be a stable digest of what
;; `jv-migration-weekly` regenerates — `90-docs/kotoba-stdlib-router/scan.edn`.
;;
;; ## Why it lives in the repo
;;
;; Measured 2026-09-04: four of the fleet's seven monitor-driven jobs had no
;; working monitor. Three of them (the canvas-watch trio) share a thin .py
;; launcher that delegates to `~/.itonami/canvas-signal.cljs`, which does not
;; exist and has no trace in any repo — it lived outside version control and
;; vanished, taking three monitors with it. None of them failed loudly: the
;; job runs, the monitor never signals, and the prompt's own instruction for
;; the no-change case is to answer [SILENT]. Three jobs watching nothing,
;; quietly.
;;
;; This one is version-controlled for that reason. The profile carries only
;; the launcher the runner's contract requires (it executes .sh via bash and
;; everything else via Python, so the logic cannot be a .cljs the runner
;; invokes directly).
;;
;; ## What it refuses
;;
;; A missing scan.edn prints REFUSED and exits 2 — neither the "unchanged"
;; output that would skip the turn nor a fake digest that would wake the agent
;; for the wrong reason. An absent input is not a measurement of no change.
(ns jv-migration-scan-monitor
  (:require ["fs" :as fs] ["crypto" :as crypto] ["path" :as path]))

(def ^:private root
  ;; nbb's argv is [node, nbb, <script>, ...args] -- dropping 2 hands back the
  ;; script's own path, which then reads as the root and makes every run REFUSE.
  ;; Caught by an idempotence check that was comparing two REFUSED outputs to
  ;; each other and passing.
  (or (first (drop 3 (js->clj js/process.argv)))
      "/Users/junkawasaki/github/com-junkawasaki"))

(def ^:private watched (path/join root "90-docs" "kotoba-stdlib-router" "scan.edn"))

(if-not (fs/existsSync watched)
  (do (println "REFUSED — no" watched)
      (println "An absent input is not a measurement of no change.")
      (js/process.exit 2))
  (let [body (fs/readFileSync watched)
        digest (-> (crypto/createHash "sha256") (.update body) (.digest "hex"))]
    ;; Deterministic and free of a timestamp on purpose: anything that changes
    ;; every run would wake the agent every run, which is the same as having no
    ;; monitor at all.
    (println "jv-migration-scan-watch/v1")
    (println (str "scan.edn sha256=" digest))
    (println (str "bytes=" (.-length body)))))
