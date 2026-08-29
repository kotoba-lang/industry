#!/usr/bin/env nbb
;; west-update-fast.cljs — the same end state as `west update --fetch smart`
;; (all active projects at their manifest pin), without walking every project
;; through west's own per-project machinery.
;;
;; WHY THIS IS SAFE TO SKIP MOST OF THE FLEET
;;
;; west.yml pins are raw 40-hex SHAs (not branch names), and `west update`'s
;; own `-f smart` (the default) already documents that it "skips fetching
;; projects whose revisions are SHAs ... available locally" — i.e. a project
;; already sitting on its pinned SHA needs zero network. Measured 2026-08-29
;; against this checkout: of 4,230 projects with an existing .git, **4,192
;; (99.1%) already have HEAD == the manifest pin**, purely from a local
;; `git rev-parse HEAD` — no fetch, no west involved. Only 38 were behind/
;; different and 23 had no checkout at all. That 220-second local-only scan,
;; run serially, is roughly what `west update --fetch smart` should cost in
;; the same steady state if its own per-project bookkeeping (checkout/reset
;; even when no fetch happens) were the only overhead — CLAUDE.md's
;; documented "~4h" figure for a full-fleet run is from a full-history
;; UNSHALLOW pass (ADR-2607211600), a fundamentally heavier one-time
;; operation, not a routine incremental update. This script does not assume
;; that gap is fully explained; it just refuses to pay per-project overhead
;; for the 99.1% that provably need nothing.
;;
;; WHAT THIS SCRIPT ACTUALLY DOES
;;
;;   1. list every project + its pinned SHA from manifest/west.yml (via a
;;      python3+pyyaml one-liner -- no new JS dependency for one YAML read)
;;   2. for each with an existing checkout, `git rev-parse HEAD` LOCALLY,
;;      IN PARALLEL (bounded concurrency) -- no network in this phase at all
;;   3. partition into :at-pin (skip) / :stale (HEAD != pin) / :missing (no
;;      checkout yet)
;;   4. run `west update --fetch smart <names...>` ONLY on :stale + :missing,
;;      sharded into N concurrent west invocations (west itself has no
;;      --jobs flag; this is N independent west processes over disjoint,
;;      explicitly-named project lists, invoked via child_process directly
;;      -- no shell in between, so the zsh word-splitting trap this
;;      workspace has hit before (word-splitting a project-name list, or an
;;      empty list becoming "update everything") cannot occur here even
;;      though it is exactly the multi-project-argument shape that trap is
;;      about)
;;   5. print a report: how many were skipped, how many were actually
;;      touched, and per-shard exit status -- never claim 0 handled the
;;      same as "there was nothing to do" without saying so
;;
;; Run:
;;   nbb scripts/west-update-fast.cljs [--jobs N] [--dry-run] [--concurrency C]
;;
;; exit 0  every stale/missing project's west update shard exited 0
;;         (including the case where the stale+missing set was empty)
;; exit 1  at least one shard exited non-zero (west skips dirty projects
;;         itself and reports that in its own output, which is forwarded)
;; exit 2  REFUSED -- could not read/parse manifest/west.yml

(ns west-update-fast
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:path" :as path]))

(def argv (vec (or *command-line-args* [])))
(defn arg [flag default]
  (let [i (.indexOf argv flag)]
    (if (neg? i) default (get argv (inc i) default))))
(defn flag? [f] (some? (some #{f} argv)))

(def jobs (js/parseInt (arg "--jobs" "8") 10))
(def scan-concurrency (js/parseInt (arg "--concurrency" "32") 10))
(def dry-run? (flag? "--dry-run"))
(def manifest-path (arg "--manifest" "manifest/west.yml"))

(defn refuse! [why]
  (println "REFUSED —" why)
  (.exit js/process 2))

;; ---------------------------------------------------------------- manifest

(defn sh-sync [cmd args opts]
  (let [r (cp/spawnSync cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8" :maxBuffer (* 64 1024 1024)} opts)))]
    {:status (if (some? (.-status r)) (.-status r) -1)
     :stdout (or (.-stdout r) "") :stderr (or (.-stderr r) "")}))

(def projects
  (let [r (sh-sync "python3"
                   ["-c" (str "import yaml, json, sys; "
                             "d = yaml.safe_load(open(sys.argv[1])); "
                             "print(json.dumps([{'name': p.get('name'), 'path': p.get('path'), "
                             "'revision': p.get('revision')} for p in d['manifest']['projects']]))")
                    manifest-path]
                   {})]
    (if-not (zero? (:status r))
      (refuse! (str "could not read/parse " manifest-path " via python3+pyyaml: " (:stderr r)))
      (js->clj (js/JSON.parse (:stdout r)) :keywordize-keys true))))

(when (empty? projects) (refuse! (str manifest-path " lists zero projects")))

;; ------------------------------------------------------- bounded-parallel map

(defn pmap-bounded
  "Apply `f` (returns a Promise) to every item in `xs`, at most `n` in
  flight at once. Returns a Promise of the results vector, in input order.
  A worker is a self-recursing loop: pull the next queue index, await `f`,
  store by index, recurse until the shared index counter is exhausted."
  [n f xs]
  (let [xs (vec xs)
        total (count xs)
        results (atom (vec (repeat total nil)))
        next-idx (atom 0)]
    (letfn [(worker []
              (let [i @next-idx]
                (if (>= i total)
                  (js/Promise.resolve nil)
                  (do (reset! next-idx (inc i))
                      (-> (f (nth xs i))
                          (.then (fn [r] (swap! results assoc i r) (worker))))))))]
      (-> (js/Promise.all (clj->js (repeatedly (min n total) worker)))
          (.then (fn [_] @results))))))

(defn exec-async
  "ASYNC child process (cp/execFile, not spawnSync) so N of these can
  genuinely be in flight at once inside one Node event loop -- spawnSync
  would block the loop per call, making \"concurrent\" spawnSync calls run
  one at a time regardless of how they're scheduled."
  [cmd args opts]
  (js/Promise.
   (fn [resolve _reject]
     (cp/execFile cmd (clj->js args) (clj->js (merge {:maxBuffer (* 64 1024 1024)} opts))
                  (fn [err stdout stderr]
                    (resolve {:status (if err (or (.-code err) 1) 0)
                              :stdout (str stdout) :stderr (str stderr)}))))))

;; --------------------------------------------------- phase 1: local scan

(defn has-checkout? [path]
  (zero? (:status (sh-sync "test" ["-e" (path/join path ".git")] {}))))

(defn check-project [{:keys [name path revision] :as p}]
  (if-not (has-checkout? path)
    (js/Promise.resolve (assoc p :state :missing))
    (-> (exec-async "git" ["-C" path "rev-parse" "HEAD"] {})
        (.then (fn [{:keys [status stdout]}]
                 (assoc p :state (if (and (zero? status) (= revision (str/trim stdout)))
                                   :at-pin :stale)))))))

(println (str "SCANNING\t" (count projects) " projects from " manifest-path
              " (local rev-parse only, concurrency=" scan-concurrency ")"))

(-> (pmap-bounded scan-concurrency check-project projects)
    (.then
     (fn [checked]
       (let [by-state (group-by :state checked)
             at-pin (count (:at-pin by-state))
             stale (vec (:stale by-state))
             missing (vec (:missing by-state))
             todo (into stale missing)]
         (println (str "SCANNED\t" (count checked) " total: "
                       at-pin " already at pin (skipped entirely), "
                       (count stale) " stale, " (count missing) " no checkout"))

         (if (empty? todo)
           (do (println "NOTHING TO DO — every checked-out project is already at its manifest pin.")
               (.exit js/process 0))

           (if dry-run?
             (do (println (str "DRY-RUN — would run `west update --fetch smart` on "
                               (count todo) " project(s), sharded across " jobs " job(s):"))
                 (doseq [p (take 30 todo)] (println (str "  " (:name p) "\t" (:state p))))
                 (when (> (count todo) 30) (println (str "  ... and " (- (count todo) 30) " more")))
                 (.exit js/process 0))

             ;; ---------------------------------------- phase 2: sharded west update
             (let [names (mapv :name todo)
                   shard-count (min jobs (count names))
                   shards (vec (for [i (range shard-count)]
                                (vec (keep-indexed #(when (= (mod %1 shard-count) i) %2) names))))]
               (println (str "UPDATING\t" (count names) " project(s) via " shard-count
                             " parallel `west update --fetch smart` invocation(s) "
                             "(no shell in between -- names passed as argv, not word-split)"))
               (-> (js/Promise.all
                    (clj->js
                     (map-indexed
                      (fn [i shard]
                        (-> (exec-async "west" (concat ["update" "--fetch" "smart"] shard)
                                       {:timeout 1800000})
                            (.then (fn [r] (assoc r :shard i :names shard)))))
                      shards)))
                   (.then
                    (fn [results]
                      (doseq [{:keys [shard names status stdout stderr]} results]
                        (println (str "shard " shard "\t" (count names) " project(s)\texit=" status))
                        (when-not (zero? status)
                          (println (str "  " (str/replace (or stderr stdout) #"\n" "\n  ")))))
                      (let [failures (remove #(zero? (:status %)) results)]
                        (if (seq failures)
                          (do (println (str "FAILED\t" (count failures) "/" shard-count " shard(s) exited non-zero"))
                              (.exit js/process 1))
                          (do (println "OK — all shards exited 0.")
                              (.exit js/process 0)))))))))))))
    (.catch (fn [e]
              (println (str "FAIL " (or (.-message e) (str e))))
              (.exit js/process 2))))
