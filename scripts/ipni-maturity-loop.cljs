#!/usr/bin/env nbb
;; scripts/ipni-maturity-loop.cljs — the IPNI maturity growth loop.
;; LaunchAgent com.gftd.ipni-maturity wakes it. Sibling of
;; itonami-maturity-improve-loop.cljs and built to the same two-stage shape:
;; MEASURE deterministically, and wake the model only when there is something
;; it could honestly move.
;;
;; ## What this loop is for today
;;
;; It will not wake anything for a while, and that is the point. Iteration 01
;; measured 40.74/100 with every startable axis UNOBSERVABLE from outside: the
;; score can be pushed to 48.15 without a stranger being able to find a single
;; kotobase CID. A loop that spent a model call every 30 minutes to bank that
;; would be manufacturing the number.
;;
;; So the loop's job right now is to be the thing that NOTICES when the
;; blocker clears. Every tick it re-measures and appends the score. The moment
;; an observable hypothesis becomes startable -- which happens when the
;; publisher identity exists -- it wakes the model with the work already
;; ranked.
;;
;; ## Conditions for not waking the model
;;
;;   - cannot fast-forward to origin/main  … do not stack on a stale base
;;   - the probe could not measure an axis … an unmeasured rubric ranks
;;                                           nothing; re-measure first
;;   - no startable AND observable axis    … the only reachable gain is
;;                                           invisible from outside. Record
;;                                           the score, wake nobody.
;;
;; exit 0 always.
;;
;; Usage:
;;   nbb --classpath ".:90-docs:scripts/nbb_compat" scripts/ipni-maturity-loop.cljs
;;   ... --dry-run        ; measure and decide, never wake
;;   ... --probe FILE     ; score a recorded probe instead of measuring live
;;                          (this is how the wake path is tested -- see below)

(ns ipni-maturity-loop
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [ipni-maturity.audit :as audit]
            [ipni-maturity.coscientist :as cs]))

(def fs (js/require "fs"))
(def os (js/require "os"))
(def cp (js/require "child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.gftd/ipni-maturity.ledger.edn"))
(def argv (vec *command-line-args*))
(def dry-run? (boolean (some #{"--dry-run"} argv)))
(def probe-override
  (loop [a argv] (cond (empty? a) nil
                       (= "--probe" (first a)) (second a)
                       :else (recur (rest a)))))

(defn log! [& xs]
  (println (str (.toISOString (js/Date.)) " " (str/join " " (map str xs)))))

(defn- sh [cmd args opts]
  (try (let [r (.spawnSync cp cmd (clj->js args)
                           (clj->js (merge {:encoding "utf8" :cwd root} opts)))]
         {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
       (catch :default e {:code nil :out "" :err (str e)})))

(defn- append-ledger! [m]
  (try (.appendFileSync fs ledger-file (str (pr-str m) "\n"))
       (catch :default e (log! "ledger append failed:" (str e)))))

(defn- previous-score
  "The last score this loop recorded, or nil. Read so a change can be called
  out; a growth loop that cannot say whether the number moved is a cron job."
  []
  (try
    (when (.existsSync fs ledger-file)
      (->> (str/split-lines (str (.readFileSync fs ledger-file "utf8")))
           (remove str/blank?)
           (keep #(try (:score (edn/read-string %)) (catch :default _ nil)))
           last))
    (catch :default _ nil)))

(defn- ff-clean?
  "True when the superproject can fast-forward onto origin/main."
  []
  (let [_ (sh "git" ["fetch" "origin" "-q"] {:timeout 180000})
        {:keys [code out]} (sh "git" ["rev-list" "--left-right" "--count"
                                      "origin/main...HEAD"] {:timeout 60000})]
    (if (or (not= 0 code) (str/blank? out))
      false
      (let [[behind _ahead] (str/split (str/trim out) #"\s+")]
        (= "0" behind)))))

(defn- measure
  "Run the probe, or read a recorded one. Returns the probe map or nil."
  []
  (if probe-override
    (try (edn/read-string (str (.readFileSync fs probe-override "utf8")))
         (catch :default e (log! "could not read" probe-override (str e)) nil))
    (let [tmp (str home "/.gftd/ipni-probe-latest.edn")
          {:keys [code err]} (sh "nbb" ["--classpath" "90-docs"
                                        "90-docs/ipni_maturity/probe.cljs"
                                        "--out" tmp]
                                 {:timeout 900000})]
      (if (and (= 0 code) (.existsSync fs tmp))
        (try (edn/read-string (str (.readFileSync fs tmp "utf8")))
             (catch :default _ nil))
        (do (log! "probe failed:" (str/trim (or err ""))) nil)))))

(defn -main []
  (let [started (.toISOString (js/Date.))
        probe (measure)]
    (cond
      (nil? probe)
      (do (log! "could not measure. Nothing is ranked on a rubric that was not read.")
          (append-ledger! {:at started :outcome :skipped :why :probe-failed}))

      :else
      (let [{:keys [before meta]} (cs/run probe)
            score (:overall before)
            prev (previous-score)
            incomplete (:incomplete before)
            ;; The gate. A hypothesis worth a model call is one that can be
            ;; started AND that a stranger could observe afterwards.
            actionable (->> (:roadmap meta)
                            (filter #(nil? (:blocked-by %)))
                            (filter :observable?)
                            first)]
        (log! (str "IPNI maturity " (.toFixed score 2) "/100"
                   (cond (nil? prev) " (first recorded)"
                         (> score prev) (str "  ▲ from " (.toFixed prev 2))
                         (< score prev) (str "  ▼ from " (.toFixed prev 2))
                         :else "  (unchanged)")))
        (cond
          (seq incomplete)
          (do (log! "UNMEASURED axes:" (str/join ", " (map name incomplete))
                    "— re-measure before ranking anything")
              (append-ledger! {:at started :score score :outcome :skipped
                               :why :incomplete-measurement :incomplete (vec incomplete)}))

          ;; The substantive check comes BEFORE the staleness one. Being behind
          ;; origin/main only matters if we were about to act; checking it
          ;; first reported an incidental condition and hid the real state,
          ;; which on this loop's first run made "held on the identity
          ;; decision" look like "the checkout is behind".
          (nil? actionable)
          (do (log! "no startable axis that anyone outside could observe."
                    "Blocked on:" (str/join "; " (distinct (keep :blocked-by (:roadmap meta)))))
              (append-ledger! {:at started :score score :outcome :held
                               :why :no-observable-startable-axis
                               :blocked-on (vec (distinct (keep :blocked-by (:roadmap meta))))}))

          (not (ff-clean?))
          (do (log! "there IS work to do, but the checkout is behind origin/main —"
                    "not stacking it on a stale base")
              (append-ledger! {:at started :score score :outcome :skipped
                               :why :behind-main :target (:id actionable)}))

          dry-run?
          (do (log! "--dry-run: would wake the model for" (:id actionable)
                    (name (:axis actionable)))
              (append-ledger! {:at started :score score :outcome :dry-run
                               :target (:id actionable) :axis (:axis actionable)}))

          :else
          (do (log! "waking the model for" (:id actionable) (name (:axis actionable)))
              (let [{:keys [code out err]}
                    (sh "claude" ["-p" (str "IPNI maturity is " (.toFixed score 2)
                                            "/100. The highest-ranked startable and "
                                            "externally observable hypothesis is "
                                            (:id actionable) " (" (name (:axis actionable))
                                            "): " (:change actionable)
                                            ". Read 90-docs/ipni_maturity/iteration-01.edn "
                                            "and ADR-2608160300 first, then do exactly this "
                                            "one thing and land it.")
                                  "--allow-dangerously-skip-permissions"]
                        {:timeout 5400000})]
                (println out)
                (when (seq (str/trim (or err ""))) (log! "stderr:" (str/trim err)))
                (append-ledger! {:at started :finished (.toISOString (js/Date.))
                                 :score score
                                 :outcome (if (= 0 code) :ran :failed) :exit code
                                 :target (:id actionable) :axis (:axis actionable)}))))))))

(-main)
