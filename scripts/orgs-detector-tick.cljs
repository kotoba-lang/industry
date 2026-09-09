#!/usr/bin/env nbb
;; scripts/orgs-detector-tick.cljs — a home for the detectors that cannot be
;; fleet gates.
;;
;; ## The problem this is the answer to
;;
;; ADR-2608124800 measured roughly twenty verification scripts that read
;; `orgs/`. None of them can be a murakumo fleet gate, for the structural reason
;; CLAUDE.md already records: a fleet gate ships only the target repo's own
;; tree, so a detector that reads `orgs/` scans an empty workspace on a node.
;; `root-permit-index` is 0 pass / 282 fail for exactly this. Two of the
;; detectors say so in their own headers.
;;
;; So the answer is not another `gates.edn` line. It is a scheduled tick on the
;; machine that actually has `orgs/`. That home existed as five uninstalled
;; launchd plists in `scripts/` and was never loaded; one of them was the only
;; caller of three verifiers, one of which guards custody that has already cost
;; irreversible asset loss (ADR-2607252000). **Nothing was wrong with the
;; checks. The scheduler was never installed.** This generalises that pattern
;; into one scheduler with a registry, so the next detector is a registry entry
;; rather than a sixth plist that also does not get installed.
;;
;; ## What it does with a finding, which is the whole design question
;;
;; It cannot open PRs on twenty repos, and writing to a log nobody reads
;; reproduces the failure the ADR is about -- `audit-gftd-scripts` was correct
;; and unheard for weeks. So the tick writes state, and
;; `.claude/hooks/session-start-orgs-detectors.cljs` puts it in front of an
;; agent at session start, where CLAUDE.md's other SessionStart hooks are
;; already read.
;;
;; **The load-bearing part is that a finding which changed is separated from one
;; that has been true for weeks.** Four gates in this workspace stand at 867,
;; 282, 269 and 268 consecutive failures, and the ADR's own conclusion is that a
;; standing red is indistinguishable from a new red. So each finding carries
;; `:first-seen`, and the report has three classes:
;;
;;   NEW        first seen in the most recent run. Named, with detail.
;;   RESOLVED   present before, absent now. Named once, then dropped.
;;   ACCEPTED   declared known in the registry's `:accepted` map, with a date, a
;;              reason and what would clear it. Counted, never enumerated.
;;   STANDING   everything else. One line with a count and the oldest age.
;;              Never enumerated. It has already been said.
;;
;; ACCEPTED exists because the triage of 2026-08-13 (ADR-2608132600) hit a state
;; the three classes could not express: a finding that is CORRECT, understood,
;; and deliberately not being fixed yet. With only three classes it decays into
;; STANDING, and STANDING is the class this whole design says is
;; indistinguishable from silence -- so a decision to accept would have been
;; recorded nowhere and would read, a month later, exactly like a defect nobody
;; had looked at. Acceptance is therefore a registry entry a human writes, not a
;; state the tick can enter on its own.
;;
;; An acceptance whose finding has gone away is reported LOUDLY as a stale
;; acceptance. An exemption that outlives its subject is how an allowlist
;; quietly grows into a blanket.
;;
;; The first run of a detector is reported as a BASELINE, not as N new findings:
;; on day one everything is new, and shouting it would be the same false alarm
;; from the other direction.
;;
;; ## Three floors, because a detector that reports nothing is ambiguous
;;
;;   1. **A run with no evidence is :inconclusive, never clean.** Each registry
;;      entry declares an `:evidence` regex that must appear in the output. The
;;      detectors print `SCANNED<TAB>n<TAB>unit` and the regex requires n > 0.
;;      ADR-2608124800 found five scripts that print PASSED without measuring
;;      anything; this is the floor that stops one being added here by accident.
;;   2. **A tick that has never run is reported as loudly as a finding.** The
;;      hook says so when state is missing or stale. A tick that has never
;;      reported anything is indistinguishable from a tick that is not running,
;;      so liveness is reported explicitly, every session, in one line.
;;   3. **Admission is fail-closed.** An entry missing `:writes :none`,
;;      `:timeout-ms`, `:evidence` or `:findings :protocol` aborts the whole
;;      tick with exit 2 rather than being skipped quietly.
;;
;; ## Read-only, and exactly how far that is enforced
;;
;; Detectors are run with `GIT_ALLOW_PROTOCOL=none`, so any `git fetch`, `clone`
;; or `push` inside one fails at the transport layer instead of succeeding
;; quietly, and with `GIT_OPTIONAL_LOCKS=0` so read commands do not refresh the
;; index. **That is transport, not a sandbox.** It does not stop a local
;; `git checkout` or a stray `writeFileSync`. Those are held off by admission --
;; `:writes :none` is declared per entry and reviewed before the entry lands --
;; and that is a weaker guarantee, stated here rather than implied.
;;
;; A stat-based sentinel over every `orgs/*/*/.git/HEAD` was considered and
;; rejected: this machine runs many concurrent agent sessions that legitimately
;; move HEADs in `orgs/`, so it would fire constantly on other people's work,
;; and CLAUDE.md's own shallow-check hook warns that a guard which false-positives
;; gets ignored, and a guard that is ignored protects nothing.
;;
;; The tick itself writes only under `~/.itonami/orgs-detector-tick/`. It never
;; writes into any checkout, including the superproject -- a tick that dirtied
;; the shared checkout would feed the stash-churn failure CLAUDE.md documents.
;;
;; ## Usage
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/orgs-detector-tick.cljs
;;   ... --only verify-vendored-copies     ; one detector
;;   ... --force                           ; ignore :interval-ms
;;   ... --root /path/to/checkout/with/orgs
;;   ... --state /tmp/alt-state.edn        ; for testing; never touches real state
;;   ... --self-test                       ; prove the NEW/STANDING/RESOLVED split
;;
;; exit 0 with findings (this is a monitor, not a gate -- the gate is each
;; detector, run by a human or a hook). exit 2 only on admission failure.

(ns orgs-detector-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [cljs.pprint :as pp]))

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def path (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def argv (vec (drop 2 js/process.argv)))
(defn- flag [n] (some #{n} argv))
(defn- opt [n] (let [i (.indexOf argv n)] (when (and (>= i 0) (< (inc i) (count argv)))
                                            (nth argv (inc i)))))

(def home (.homedir os))
(def state-dir (str home "/.itonami/orgs-detector-tick"))
(def state-file (or (opt "--state") (str state-dir "/state.edn")))
(def log-file (str state-dir "/tick.log"))
;; The lock is per state file, not global: a fixture run under --state must not
;; be able to block, or be blocked by, the scheduled tick. A single global lock
;; would make the two contend and make the proof runs unreproducible.
(def lock-file (str state-file ".lock"))

;; A resolved finding is named once and then forgotten. Long enough that a
;; session the next morning still sees "this went away"; short enough that the
;; report does not accumulate a museum.
(def resolved-window-ms (* 7 24 60 60 1000))

(defn now-iso [] (.toISOString (js/Date.)))

(defn log! [& xs]
  (let [line (str (now-iso) " " (str/join " " (map str xs)) "\n")]
    (try (.mkdirSync fs state-dir #js {:recursive true}) (catch :default _ nil))
    (try (.appendFileSync fs log-file line) (catch :default _ nil))
    (js/process.stdout.write line)))

(defn read-edn [f]
  (try (edn/read-string (.readFileSync fs f "utf8")) (catch :default _ nil)))

(defn write-edn! [f data]
  ;; tmp + rename: a session-start hook can read this at any moment, and a
  ;; half-written state file would be reported as "the tick has never run".
  (.mkdirSync fs (.dirname path f) #js {:recursive true})
  (let [tmp (str f ".tmp." (.-pid js/process))]
    (.writeFileSync fs tmp (with-out-str (pp/pprint data)))
    (.renameSync fs tmp f)))

;; ───────────────────────── pure core (see --self-test) ─────────────────────────

(defn parse-findings
  "Pull the FINDING/SCANNED protocol lines out of a detector's stdout.

   Returns {:findings [{:severity :key :detail}] :scanned n-or-nil}. Anything
   that is not a protocol line is prose for the log and is ignored here."
  [out]
  (reduce
    (fn [acc line]
      (let [cells (str/split line #"\t")]
        (cond
          (and (= "FINDING" (first cells)) (>= (count cells) 3))
          (update acc :findings conj {:severity (nth cells 1)
                                      :key      (nth cells 2)
                                      :detail   (str/trim (or (nth cells 3 nil) ""))})
          (and (= "SCANNED" (first cells)) (>= (count cells) 2))
          (assoc acc :scanned (js/parseInt (nth cells 1)))
          :else acc)))
    {:findings [] :scanned nil}
    (str/split-lines (or out ""))))

(defn merge-findings
  "Fold this run's findings into the previous state.

   This is the function that makes a new red distinguishable from a standing
   one, so it is pure and covered by --self-test.

     prev-findings  {key {:severity :detail :first-seen :last-seen :seen-runs
                          :detail-changed}}
     prev-resolved  {key {:detail :first-seen :resolved-at}}
     cur            [{:severity :key :detail}]
     accepted       #{key} -- declared known in the registry

   Returns {:findings :resolved :new-keys :resolved-keys :accepted-keys
            :accepted-stale}."
  ([prev-findings prev-resolved cur now]
   (merge-findings prev-findings prev-resolved cur now #{}))
  ([prev-findings prev-resolved cur now accepted]
  (let [cur-by-key (into {} (map (juxt :key identity)) cur)
        findings
        (into {}
              (for [[k {:keys [severity detail]}] cur-by-key
                    :let [was (get prev-findings k)
                          ;; A resolved finding that comes back is NEW again.
                          ;; Its old first-seen is not carried over: "broken for
                          ;; 3 weeks" and "broken again since this morning" are
                          ;; different facts and must not be collapsed.
                          detail-changed? (and was (not= detail (:detail was)))]]
                [k (if was
                     {:severity severity
                      :detail detail
                      :first-seen (:first-seen was)
                      :last-seen now
                      :seen-runs (inc (or (:seen-runs was) 1))
                      :detail-changed (if detail-changed? now (:detail-changed was))}
                     {:severity severity
                      :detail detail
                      :first-seen now
                      :last-seen now
                      :seen-runs 1
                      :detail-changed nil})]))
        gone (remove cur-by-key (keys prev-findings))
        resolved (merge
                   ;; drop anything that came back, and anything old enough to
                   ;; have been read already
                   (into {} (for [[k v] prev-resolved
                                  :when (and (not (cur-by-key k))
                                             (< (- (.getTime (js/Date. now))
                                                   (.getTime (js/Date. (:resolved-at v))))
                                                resolved-window-ms))]
                              [k v]))
                   (into {} (for [k gone
                                  :let [v (get prev-findings k)]]
                              [k {:detail (:detail v)
                                  :first-seen (:first-seen v)
                                  :resolved-at now}])))]
    {:findings findings
     :resolved resolved
     ;; An accepted key is never NEW. It was named, dated and justified by a
     ;; human in the registry, so announcing it as newly discovered would be
     ;; false -- and it is the announcement that costs attention.
     :new-keys (vec (sort (remove #(or (prev-findings %) (accepted %))
                                  (keys cur-by-key))))
     :resolved-keys (vec (sort gone))
     :accepted-keys (vec (sort (filter cur-by-key accepted)))
     ;; Accepted, but the detector no longer reports it. Either it was fixed and
     ;; the acceptance was left behind, or the detector stopped being able to
     ;; see it. Both are reasons to delete the entry, and neither is silent.
     :accepted-stale (vec (sort (remove cur-by-key accepted)))})))

;; ───────────────────────── admission ─────────────────────────

(defn admit!
  "Fail closed. An entry that cannot state its own limits does not run.

   Skipping a malformed entry quietly is how a registry ends up half-live with
   nobody able to say which half."
  [entries]
  (let [accepted-ok?
        (fn [e]
          (let [a (:accepted e)]
            (or (nil? a)
                (and (map? a)
                     (every? (fn [[k v]]
                               (and (string? k) (map? v)
                                    (string? (:since v)) (seq (:since v))
                                    (string? (:why v)) (seq (:why v))
                                    (string? (:clears-when v)) (seq (:clears-when v))))
                             a)))))
        problems
        (for [e entries
              [pred msg] [[(keyword? (:id e))                    ":id must be a keyword"]
                          [(accepted-ok? e)                      ":accepted must map finding-key -> {:since :why :clears-when}, all non-empty strings -- an exemption with no date, no reason and no exit is not a decision"]
                          [(seq (:argv e))                       ":argv missing"]
                          [(= :protocol (:findings e))           ":findings must be :protocol"]
                          [(string? (:evidence e))               ":evidence regex missing (a run with no evidence must not be recordable as clean)"]
                          [(pos-int? (:timeout-ms e))            ":timeout-ms missing"]
                          [(pos-int? (:interval-ms e))           ":interval-ms missing"]
                          [(= :none (:writes e))                 ":writes must be :none -- this home never writes to a checkout"]
                          [(seq (:why e))                        ":why missing -- a detector nobody justified running"]]
              :when (not pred)]
          (str "  " (or (:id e) "<no :id>") ": " msg))]
    (when (seq problems)
      (log! "ADMISSION FAILED — refusing to run any detector:")
      (doseq [p problems] (log! p))
      (js/process.exit 2))))

;; ───────────────────────── running ─────────────────────────

(defn run-one
  "Run one detector. Returns {:status :exit :out :duration-ms :note}.

   :status is :ok | :inconclusive | :error. It is NEVER :ok on the strength of
   an exit code alone -- the evidence regex has to match too."
  [{:keys [id argv cwd timeout-ms evidence]} subst]
  (let [cmd (map subst argv)
        t0 (js/Date.now)]
    (try
      (let [;; process.env is not a plain object -- js->clj hands it back
            ;; unchanged and `merge` then throws. Copy it with Object.assign.
            env (js/Object.assign #js {} js/process.env
                                  #js {"GIT_ALLOW_PROTOCOL" "none"
                                       "GIT_TERMINAL_PROMPT" "0"
                                       "GIT_OPTIONAL_LOCKS" "0"})
            out (str (.execFileSync cp (first cmd) (clj->js (vec (rest cmd)))
                                    #js {:cwd cwd
                                         :encoding "utf8"
                                         :timeout timeout-ms
                                         :stdio #js ["ignore" "pipe" "pipe"]
                                         :maxBuffer (* 64 1024 1024)
                                         :env env}))]
        {:status :ok :exit 0 :out out :duration-ms (- (js/Date.now) t0)})
      (catch :default e
        (let [out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str)
                       ;; execFileSync can fail with no captured output at all
                       ;; (ENOENT on the binary, a bad cwd). Keep the message,
                       ;; or the log records a failure with no cause.
                       (when (str/blank? (str (some-> (.-stdout e) str)
                                              (some-> (.-stderr e) str)))
                         (str "\n(no output) " (.-message e))))
              killed? (or (.-killed e) (= "ETIMEDOUT" (.-code e)))
              code (or (.-status e) 1)
              ;; 0 = clean, 1 = found things, >=2 = could not answer. Every
              ;; detector in this registry follows it (measured 2026-08-19: all
              ;; 15 exit 0 or 1), and several say so in their own headers.
              ;;
              ;; Before this, a refusal was recorded as a clean run. A detector
              ;; with an evidence floor prints SCANNED before it refuses -- it
              ;; has to, the count is the reason it is refusing -- so `qualify`
              ;; saw its evidence line, left the status :ok, and the merge below
              ;; marked every standing finding RESOLVED. Measured the same day:
              ;; verify-appview-page-summary printed `CANNOT ANSWER: found 142
              ;; candidate pages, floor 150`, exited 2, and the tick reported
              ;; `ok exit 2, 0 finding(s)` and resolved all 337.
              ;;
              ;; The protection for this already existed one branch down and is
              ;; commented "the most dangerous possible lie this thing could
              ;; tell". What was missing was reaching it.
              refused? (>= code 2)]
          {:status (cond killed? :error refused? :inconclusive :else :ok)
           :exit code
           :out out
           :duration-ms (- (js/Date.now) t0)
           :note (cond killed? (str "killed after " timeout-ms "ms")
                       refused? (str "exit " code " — the detector refused to answer;"
                                     " the previous finding set is kept, NOT resolved"))})))
    ))

(defn qualify
  "Downgrade :ok to :inconclusive when the evidence floor is not met.

   A detector exiting 0 because it crashed before scanning anything looks
   identical, from the outside, to one that scanned everything and found
   nothing. This is where those two are separated."
  [{:keys [status out] :as r} evidence]
  (if (and (= :ok status)
           (not (re-find (re-pattern evidence) (or out ""))))
    (assoc r :status :inconclusive
             :note (str "no evidence line matching /" evidence "/ -- the run cannot "
                        "be recorded as clean"))
    r))

;; ───────────────────────── lock ─────────────────────────

(defn alive? [pid]
  (try (.kill js/process pid 0) true (catch :default _ false)))

(defn take-lock! []
  (.mkdirSync fs state-dir #js {:recursive true})
  (let [owner (read-edn lock-file)]
    (if (and owner (:pid owner) (alive? (:pid owner)))
      false
      (do (write-edn! lock-file {:pid (.-pid js/process) :started (now-iso)})
          true))))

(defn release-lock! [] (try (.unlinkSync fs lock-file) (catch :default _ nil)))

;; ───────────────────────── self-test ─────────────────────────

(defn self-test []
  (let [t0 "2026-08-01T00:00:00.000Z"
        t1 "2026-08-12T00:00:00.000Z"
        t2 "2026-08-12T06:00:00.000Z"
        fail (fn [msg] (println "SELF-TEST FAIL:" msg) (js/process.exit 1))
        run1 (merge-findings {} {} [{:severity "fail" :key "a" :detail "d1"}
                                    {:severity "fail" :key "b" :detail "d1"}] t0)
        run2 (merge-findings (:findings run1) (:resolved run1)
                             [{:severity "fail" :key "b" :detail "d1"}
                              {:severity "fail" :key "c" :detail "d1"}] t1)
        run3 (merge-findings (:findings run2) (:resolved run2)
                             [{:severity "fail" :key "b" :detail "CHANGED"}] t2)]
    (when-not (= ["a" "b"] (:new-keys run1)) (fail "first run: both keys are new"))
    (when-not (= ["c"] (:new-keys run2)) (fail "second run: only c is new"))
    (when-not (= ["a"] (:resolved-keys run2)) (fail "second run: a resolved"))
    ;; the point of the whole design: b is standing, and standing is not new
    (when (some #{"b"} (:new-keys run2)) (fail "b has been true since run 1; it is not new"))
    (when-not (= t0 (get-in run2 [:findings "b" :first-seen]))
      (fail "b must keep its original first-seen so its age is real"))
    (when-not (= 2 (get-in run2 [:findings "b" :seen-runs])) (fail "seen-runs"))
    (when-not (= t2 (get-in run3 [:findings "b" :detail-changed]))
      (fail "a standing finding whose detail changed must be datable"))
    (when-not (= ["c"] (:resolved-keys run3)) (fail "third run: c resolved"))
    ;; a resolved finding that comes back is new again, not 11 days old
    (let [run4 (merge-findings (:findings run3) (:resolved run3)
                               [{:severity "fail" :key "b" :detail "CHANGED"}
                                {:severity "fail" :key "c" :detail "d1"}] t2)]
      (when-not (= ["c"] (:new-keys run4)) (fail "a returning finding is new again"))
      (when-not (= t2 (get-in run4 [:findings "c" :first-seen]))
        (fail "a returning finding must not inherit its old age"))
      (when (contains? (:resolved run4) "c") (fail "c came back; it is not resolved")))
    ;; parse-findings
    (let [p (parse-findings "noise\nSCANNED\t33\tfiles\nFINDING\tfail\tk1\tdetail here\nmore noise")]
      (when-not (= 33 (:scanned p)) (fail "SCANNED parse"))
      (when-not (= [{:severity "fail" :key "k1" :detail "detail here"}] (:findings p))
        (fail "FINDING parse")))
    ;; ACCEPTED. The point is that an accepted finding is neither shouted as new
    ;; nor buried in STANDING, and that an acceptance outliving its finding is
    ;; loud rather than convenient.
    (let [a1 (merge-findings {} {} [{:severity "fail" :key "x" :detail "d"}
                                    {:severity "fail" :key "y" :detail "d"}]
                             t0 #{"x"})]
      (when-not (= ["y"] (:new-keys a1)) (fail "an accepted finding is not announced as new"))
      (when-not (= ["x"] (:accepted-keys a1)) (fail "an accepted finding that is present is ACCEPTED"))
      (when-not (= [] (:accepted-stale a1)) (fail "x is present, so its acceptance is not stale"))
      (when-not (contains? (:findings a1) "x")
        (fail "an accepted finding is still tracked -- accepting is not deleting"))
      ;; the finding goes away; the acceptance must not go quiet with it
      (let [a2 (merge-findings (:findings a1) (:resolved a1)
                               [{:severity "fail" :key "y" :detail "d"}] t1 #{"x"})]
        (when-not (= ["x"] (:accepted-stale a2)) (fail "an acceptance whose finding is gone is stale"))
        (when-not (= ["x"] (:resolved-keys a2)) (fail "x also resolved"))
        (when-not (= [] (:accepted-keys a2)) (fail "x is absent, so it is not a present acceptance"))))
    (println "SELF-TEST OK — 19 assertions: new/standing/resolved/returning/detail-change/parse/accepted/stale-acceptance")
    (js/process.exit 0)))

;; ───────────────────────── main ─────────────────────────

(defn -main []
  (when (flag "--self-test") (self-test))
  (let [self (or (first (filter #(str/ends-with? (str %) "orgs-detector-tick.cljs")
                                (js->clj js/process.argv)))
                 ".")
        repo (or (opt "--repo") (.resolve path (.dirname path self) ".."))
        root (or (opt "--root") (.cwd js/process))
        registry-file (or (opt "--registry") (str repo "/manifest/orgs-detectors.edn"))
        registry (read-edn registry-file)
        only (when-let [o (opt "--only")] (set (str/split o #",")))
        force? (flag "--force")]

    (when-not (:detectors registry)
      (log! "no registry at" registry-file "— nothing to run") (js/process.exit 2))

    ;; `--admit-only`: run admission and stop. It exists so the check that
    ;; refuses a malformed entry can run where the entry LANDS -- a fleet gate
    ;; on this repository's own tree -- instead of only when the tick next
    ;; fires. Admission is all-or-nothing by design, so one entry missing its
    ;; mandatory fields refuses every detector; that happened twice on
    ;; 2026-08-20 (:verify-error-provenance, then
    ;; :verify-bridge-guest-cannot-execute), and both times the tick's own
    ;; state simply stopped being written while SessionStart kept rendering the
    ;; last good run with nothing saying it was frozen.
    ;;
    ;; It runs BEFORE the orgs/ check on purpose: a fleet node has no orgs/,
    ;; and admission does not need one. Calling `admit!` here rather than
    ;; copying its rules into the gate keeps one implementation of them.
    (when (flag "--admit-only")
      (admit! (:detectors registry))
      (println (str "SCANNED\t" (count (:detectors registry))
                    "\tregistry entries admitted from " registry-file))
      (js/process.exit 0))
    (when-not (.existsSync fs (str root "/orgs"))
      ;; The entire point of this home is that it has orgs/. If it does not,
      ;; every detector would scan nothing and the evidence floor would record
      ;; :inconclusive N times. Say the real reason once instead.
      (log! "FATAL:" root "has no orgs/ — this tick must run on the machine that has the"
            "west checkouts. Pass --root <checkout>.")
      (js/process.exit 2))

    (admit! (:detectors registry))

    (if-not (take-lock!)
      (log! "another orgs-detector-tick is running (pid" (:pid (read-edn lock-file)) ") — skipping")
      (try
        (let [prev (or (read-edn state-file) {:schema 1 :detectors {}})
              now (now-iso)
              subst (fn [s] (-> s (str/replace "{{repo}}" repo) (str/replace "{{root}}" root)))
              entries (cond->> (:detectors registry)
                        only (filter #(only (name (:id %)))))
              next-detectors
              (reduce
                (fn [acc {:keys [id interval-ms evidence] :as e}]
                  (let [k (keyword (name id))
                        p (get-in prev [:detectors k])
                        age (when (:last-run p)
                              (- (.getTime (js/Date. now)) (.getTime (js/Date. (:last-run p)))))
                        due? (or force? (nil? age) (>= age interval-ms))]
                    (if-not due?
                      (do (log! (name id) "not due (" (js/Math.round (/ age 60000)) "m since last run,"
                                "interval" (js/Math.round (/ interval-ms 60000)) "m) — keeping previous state")
                          (assoc acc k p))
                      (let [_ (log! "running" (name id) "…")
                            r (-> (run-one (assoc e :cwd (if (= :repo (:cwd e)) repo root)) subst)
                                  (qualify evidence))
                            parsed (parse-findings (:out r))
                            _ (log! (name id) "→" (name (:status r))
                                    "exit" (:exit r)
                                    (str (js/Math.round (/ (:duration-ms r) 1000)) "s")
                                    (str (count (:findings parsed)) " finding(s)")
                                    (str "scanned=" (:scanned parsed))
                                    (or (:note r) ""))]
                        ;; An :inconclusive or :error run must NOT overwrite the
                        ;; finding set. Otherwise a detector that failed to start
                        ;; would report every standing finding as RESOLVED, which
                        ;; is the most dangerous possible lie this thing could tell.
                        (if (not= :ok (:status r))
                          (do
                            ;; A run that could not be trusted has to leave
                            ;; behind enough to diagnose it. "inconclusive" with
                            ;; no output is the same silence in a new uniform.
                            (doseq [l (take-last 12 (remove str/blank?
                                                            (str/split-lines (or (:out r) ""))))]
                              (log! "   |" l))
                            (assoc acc k (merge p {:last-run now
                                                 :last-status (:status r)
                                                 :last-exit (:exit r)
                                                 :last-duration-ms (:duration-ms r)
                                                 :last-note (:note r)
                                                 :title (:title e)
                                                 :runs (inc (or (:runs p) 0))})))
                          (let [accepted (set (keys (:accepted e)))
                                m (merge-findings (or (:findings p) {}) (or (:resolved p) {})
                                                  (:findings parsed) now accepted)]
                            (doseq [nk (:new-keys m)]
                              (log! "  NEW      " nk " — " (get-in m [:findings nk :detail])))
                            (doseq [rk (:resolved-keys m)]
                              (log! "  RESOLVED " rk))
                            (when (seq (:accepted-keys m))
                              (log! "  ACCEPTED " (count (:accepted-keys m))
                                    "— declared known in the registry:"
                                    (str/join ", " (:accepted-keys m))))
                            (doseq [sk (:accepted-stale m)]
                              (log! "  ⚠ STALE ACCEPTANCE" sk
                                    "— accepted on" (get-in e [:accepted sk :since])
                                    "but this run does not report it. Delete the"
                                    ":accepted entry; an exemption that outlived its"
                                    "finding exempts whatever lands on that key next."))
                            (assoc acc k {:title (:title e)
                                          :accepted-keys (:accepted-keys m)
                                          :accepted-stale (:accepted-stale m)
                                          :last-run now
                                          :last-status :ok
                                          :last-exit (:exit r)
                                          :last-duration-ms (:duration-ms r)
                                          :last-note nil
                                          :last-scanned (:scanned parsed)
                                          :interval-ms interval-ms
                                          :runs (inc (or (:runs p) 0))
                                          ;; Counted separately from :runs on
                                          ;; purpose. The BASELINE report ("day
                                          ;; one, everything is new, do not
                                          ;; shout") has to key off the first
                                          ;; run that PRODUCED a finding set --
                                          ;; a first attempt that died before
                                          ;; scanning must not consume it, or
                                          ;; the real first measurement arrives
                                          ;; disguised as 13 new findings.
                                          :ok-runs (inc (or (:ok-runs p) 0))
                                          :findings (:findings m)
                                          :resolved (:resolved m)}))))))
                  )
                (:detectors prev)
                entries)]
          (write-edn! state-file {:schema 1
                                  :updated now
                                  :root root
                                  :registry registry-file
                                  :detectors next-detectors})
          (log! "state →" state-file))
        (finally (release-lock!))))))

(-main)
