#!/usr/bin/env nbb
(ns endpoint-health-resident
  "One tick of the resident: probe, accumulate, and say what may honestly be
  said about each endpoint over the trailing window.

  ## Why a resident and not a script somebody runs

  `scripts/verify-endpoint-health.cljs` answers ONE question: is this endpoint
  keeping its promise right now. That is the right question and it is not
  enough, because nobody was asking it. The two outages in ADR-2608180100 were
  each visible in one request for days -- 20 days, in the image gateway's case.

  A single run also cannot produce an availability number. `uptime` is explicit
  about this and it is the reason this file exists rather than a percentage
  computed here: a ratio needs a window, a window needs coverage, and a prober
  that quietly stopped must not read as a perfect month.

  ## Where it runs, and why not on the laptop

  gad. ADR-2608111721's first question is 'what stops if this machine sleeps
  tonight', and the answer for a health probe has to be 'nothing'. The laptop
  sleeps, runs 100-load builds, and is where the outages were NOT noticed for
  20 days.

  This is a `:slot/anonymous` residency: it holds no write key. It reads public
  endpoints and writes two files next to itself. That is deliberate -- the
  moment it needs a key to publish, it becomes an attested slot and inherits a
  much heavier set of rules.

  ## The three files

    <home>/observations.ledger.edn   append-only, one line per tick (a vector)
    <home>/statement.edn             the current statement per target
    <home>/tick.log                  what the last tick printed

  The ledger is append-only because it is a measurement series, not a document
  (CLAUDE.md's own carve-out for `canvas-ledger` / `design-quality-ledger`).
  Rewriting it would destroy the window the statement is computed over.

  ## What it does NOT do

  It does not publish to kotobase and it does not push to git, because it holds
  no key for either. The bot-facing mirror is pulled by the workspace, which
  already has those keys -- see docs in the commit. Bots run on the workspace
  machine anyway (`bots.clj`: 'A Bot's computer is therefore this machine'), so
  a mirror that refreshes when that machine is live loses nothing.

  usage:
    nbb --classpath <uptime>/src scripts/endpoint-health-resident.cljs \\
        [--root <workspace>] [--home <dir>] [--window-hours 24] [--cheap-only]"
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [uptime.core :as up]))

(def argv (vec *command-line-args*))
(defn opt [k d] (if-let [i (first (keep-indexed #(when (= %2 k) %1) argv))]
                  (get argv (inc i) d) d))
(def root (opt "--root" (js/process.cwd)))
(def home (opt "--home" (.join path (or (.-HOME js/process.env) "/tmp")
                               ".itonami" "endpoint-health")))
(def window-hours (js/parseFloat (opt "--window-hours" "24")))
(def cheap-only? (some #{"--cheap-only"} argv))
(def ledger-path (.join path home "observations.ledger.edn"))
(def statement-path (.join path home "statement.edn"))

(defn read-ledger []
  (if-not (.existsSync fs ledger-path)
    []
    (->> (str/split-lines (.readFileSync fs ledger-path "utf8"))
         (remove str/blank?)
         (mapcat (fn [line]
                   ;; One bad line must not lose the window. Skipping is
                   ;; reported, never silent -- a ledger that quietly shrinks
                   ;; makes coverage look worse and availability look better.
                   (try (edn/read-string line)
                        (catch :default _
                          (println "  WARNING: unreadable ledger line skipped")
                          nil))))
         vec)))

(defn -main []
  (.mkdirSync fs home #js {:recursive true})
  (let [obs-tmp (.join path home "last-run.observations.edn")
        cmd (str "nbb " (.join path root "scripts" "verify-endpoint-health.cljs")
                 " " root " --all --edn " obs-tmp
                 (when cheap-only? " --cheap-only"))
        res (try (.execSync cp cmd #js {:encoding "utf8" :stdio "pipe"
                                        :timeout 900000})
                 (catch :default e (or (some-> (.-stdout e) str) (str e))))
        exit-2? (str/includes? (str res) "REFUSING")]
    (println (str/trim (str res)))
    (cond
      ;; A tick that could not measure writes NOTHING. This is the whole point:
      ;; `uptime` reports a window with no conclusive probe as :unobserved, and
      ;; that only stays true if a prober which could not run leaves no trace
      ;; that looks like one.
      exit-2?
      (println "\ntick could not measure -- no observations recorded (window stays honest)")

      (not (.existsSync fs obs-tmp))
      (println "\ntick produced no observations file -- nothing recorded")

      :else
      (let [fresh (edn/read-string (.readFileSync fs obs-tmp "utf8"))
            _ (.appendFileSync fs ledger-path (str (pr-str fresh) "\n"))
            all (read-ledger)
            now (.now js/Date)
            from (- now (* window-hours 3600000))
            ;; One tick probes every target once, so the schedule expects
            ;; exactly as many probes per target as there were ticks in the
            ;; window. Passing it is what turns "nothing went wrong" into
            ;; "we were watching" (uptime's own words).
            ticks (count (filter #(>= (:observation/at (first %)) from)
                                 (partition-by :observation/at (sort-by :observation/at all))))
            targets (sort (distinct (map :observation/target all)))
            stmts (mapv (fn [t]
                          (let [o (filter #(= t (:observation/target %)) all)]
                            (up/statement t o from now {:expected (max 1 ticks)})))
                        targets)]
        (.writeFileSync fs statement-path
                        (with-out-str (println (pr-str {:at now :window-hours window-hours
                                                        :statements stmts}))))
        (println (str "\n" (int window-hours) "h window, " ticks " tick(s):"))
        (doseq [s stmts] (println (str "  " (up/describe s))))
        (let [bad (filter #(#{:unavailable :degraded} (:availability/status %)) stmts)]
          (when (seq bad)
            (println (str "\n" (count bad) " target(s) not available over the window."))
            (js/process.exit 1)))))))

(-main)
