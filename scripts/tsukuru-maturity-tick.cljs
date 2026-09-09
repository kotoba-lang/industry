#!/usr/bin/env nbb
;; scripts/tsukuru-maturity-tick.cljs — ADR-2800003200 の残 gap を継続測定する
;; ローカル loop。LaunchAgent com.gftd.tsukuru-maturity-tick が起動する。
;;
;; ## この loop が答える問い
;;
;;   1. blocked と記録した条件のうち、**もう blocked でなくなったものはあるか**
;;   2. landed と記録した保証は、**まだ本当か**
;;
;; 2 が要るのは ADR-2607252000 の教訓そのもの: SHIRO & PICO ep01 は「DataLad に
;; ある」と書かれたまま消えていて、誰もその主張を検査していなかったから、
;; 必要になるまで gap が見えなかった。landed は状態であって出来事ではない。
;;
;; ## この loop は observe-only
;;
;; 姉妹の com.gftd.itonami-maturity-tick (ADR-2607254000) は Tier 1 の決定論的
;; 修正を**無人で着地させる**。こちらは違う: **何も書かない・deploy しない・
;; git を触らない**。ここで残っている gap は決定論的な欠陥ではなく、
;; (a) 外部サービスの authorization、(b) 人間の判断（NDA・鍵の再発行）、
;; (c) 到達できない資格情報 —— どれも無人で埋めてよい種類ではない。
;; 測って、状態が変わったら言う。それだけをする。
;;
;; ## 不変条件（姉妹 loop と同一）
;;
;;   - 捏造ゼロ。probe が走らなかったら :unknown と書く。:ok に丸めない。
;;   - human-gated の条件は loop が :ok に反転させない（NDA を機械は読めない）。
;;   - ledger は追記のみ。既存行の編集・削除禁止。
;;   - 秘密の値をログにも ledger にも書かない（在否だけ）。
;;
;; ## 使い方
;;   nbb --classpath ".:scripts/nbb_compat" scripts/tsukuru-maturity-tick.cljs
;;   nbb ... scripts/tsukuru-maturity-tick.cljs --skip-slow   ; 面のロードを飛ばす
;;
;; exit 0 常に（監視ループであって gate ではない。gate は個々の verify スクリプト）。

(ns tsukuru-maturity-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def cp (js/require "child_process"))
(def fs (js/require "fs"))
(def os (js/require "os"))

(def home (.homedir os))
(def root (str home "/github/com-junkawasaki"))
(def log-file (str home "/.itonami/tsukuru-maturity-tick.log"))
(def ledger-file (str home "/.itonami/tsukuru-maturity-tick.ledger.edn"))
(def dataset (str root "/orgs/cloud-itonami/tsukuru-manufacturing-artifacts"))
(def actor (str root "/orgs/cloud-itonami/tsukuru-actor"))
(def skip-slow? (boolean (some #{"--skip-slow"} *command-line-args*)))

(defn log! [& xs]
  (let [line (str (.toISOString (js/Date.)) " " (str/join " " (map str xs)) "\n")]
    (try (.appendFileSync fs log-file line) (catch :default _ nil))
    (js/process.stdout.write line)))

(defn sh
  "Run a command. Returns {:code n :out s :timeout? bool}. Never throws — a probe
  that blows up must record :unknown rather than take the tick down with it.

  `:timeout?` is reported separately because a killed process and a failed one
  are indistinguishable from the exit code alone: Node reports both as
  `status` nil -> code 1, with whatever partial stdout had accumulated. Reading
  that partial output as a verdict is how a slow machine turns into a
  `:regressed` (see probe-script)."
  [dir cmd args & [{:keys [timeout-ms env]}]]
  (try
    {:code 0 :timeout? false
     :out (str (.execFileSync cp cmd (clj->js args)
                              (clj->js (cond-> {:cwd dir :encoding "utf8"
                                                :stdio ["ignore" "pipe" "pipe"]
                                                :maxBuffer (* 32 1024 1024)}
                                         timeout-ms (assoc :timeout timeout-ms)
                                         env (assoc :env (merge (js->clj js/process.env) env))))))}
    (catch :default e
      {:code (or (.-status e) 1)
       ;; execFileSync surfaces a timeout kill as code "ETIMEDOUT" and/or a
       ;; signal (SIGTERM) with a null status — never as a real exit code.
       :timeout? (boolean (or (= "ETIMEDOUT" (.-code e))
                              (and (nil? (.-status e)) (some? (.-signal e)))))
       :out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str))})))

;; ───────────────────────── probes ─────────────────────────
;; Each returns {:probe kw :state kw :note str}. States:
;;   :ok       the condition holds, verified this tick
;;   :blocked  the condition does not hold, and that is the recorded expectation
;;   :regressed something that was :ok is no longer
;;   :unknown  the probe could not run — NEVER conflated with :ok
;;   :human    a human must decide; the loop reports and never flips it

(defn probe-seed
  "Is the shared marketplace seed back in kagi?

  The single most valuable thing this loop watches: ADR-2800003200 Phase 2 is
  blocked on it, and the moment it exists the live verification can proceed.
  Targeted single-item lookup by known identifier — never an enumeration
  (CLAUDE.md safety floor 7). The VALUE is never read into a variable that
  could reach a log; only the exit code is."
  []
  (let [{:keys [code]} (sh root (str root "/orgs/kotoba-lang/kagi/bin/kagi")
                           ["get" "itonami-marketplace-kotobase-seed"]
                           {:timeout-ms 60000 :env {"KAGI_HOME" (str home "/.kagi")}})]
    (if (zero? code)
      {:probe :marketplace-seed :state :ok
       :note "kagi itonami-marketplace-kotobase-seed exists — Phase 2 live verification is unblocked"}
      {:probe :marketplace-seed :state :blocked
       :note "kagi has no itonami-marketplace-kotobase-seed (as recorded 2026-08-05)"})))

(defn- probe-route
  "Can this worker actually reach the shared ref?

  `q 401 Unauthenticated` in the body is the specific failure recorded on
  2026-08-05: same DID, same code, some kinds readable and others not. Matching
  on the string is deliberate — a 200 carrying a store-error is still a failure,
  and only the body distinguishes them."
  [id url]
  (let [{:keys [out]} (sh root "curl" ["-sS" "-m" "25" url] {:timeout-ms 40000})]
    (cond
      (str/blank? out) {:probe id :state :unknown :note "no response"}
      (str/includes? out "Unauthenticated") {:probe id :state :blocked
                                             :note "q 401 Unauthenticated — cannot read the shared ref"}
      (str/includes? out "unauthorised") {:probe id :state :unknown
                                          :note "route is token-gated; this loop holds no token"}
      (str/includes? out "store-error") {:probe id :state :blocked :note (subs out 0 (min 160 (count out)))}
      :else {:probe id :state :ok :note (subs out 0 (min 120 (count out)))})))

(defn probe-ref-reachability []
  (let [base "https://cloud-itonami-marketplace-"]
    [(probe-route :ref-read-order (str base "order.04-feasts-minded.workers.dev/orders"))
     (probe-route :ref-read-listing (str base "listing.04-feasts-minded.workers.dev/offers"))
     (probe-route :ref-read-fulfillment (str base "fulfillment.04-feasts-minded.workers.dev/tasks"))]))

(defn probe-tsukuru-worker
  "tsukuru's own Worker. 503 with did:null is the CORRECT state while the seed
  is missing — fail-closed. A 200 here means somebody supplied a seed, which is
  the same news as probe-seed from the other side; a 500 means it is broken."
  []
  (let [{:keys [out]} (sh root "curl" ["-sS" "-m" "25"
                                       "https://cloud-itonami-tsukuru.04-feasts-minded.workers.dev/health"]
                          {:timeout-ms 40000})]
    (cond
      (str/blank? out) {:probe :tsukuru-worker :state :unknown :note "no response"}
      (str/includes? out "\"did\":null") {:probe :tsukuru-worker :state :blocked
                                          :note "503 fail-closed, no seed — the expected state"}
      (str/includes? out "\"ok\":true") {:probe :tsukuru-worker :state :ok
                                         :note "worker has a store — seed supplied"}
      :else {:probe :tsukuru-worker :state :regressed :note (subs out 0 (min 160 (count out)))})))

(defn- probe-script
  "Run one of the repo's verify scripts. Exit 0 is the guarantee still holding."
  [id script extra]
  (let [{:keys [code out timeout?]} (sh root "nbb" (concat ["--classpath" ".:scripts/nbb_compat" script] extra)
                                       {:timeout-ms 900000})]
    {:probe id
     ;; An ENVIRONMENT failure is :unknown, not :regressed. A missing script, an
     ;; unresolvable npm module, an absent checkout — none of those are evidence
     ;; that the guarantee stopped holding, and reporting them as regressions
     ;; makes the loop cry wolf every six hours until nobody reads it.
     ;; Measured on the first launchd firing: `node_modules/` at the superproject
     ;; root had been emptied, so the factory-plane probe could not load
     ;; datascript and this classified it :regressed. The plane was fine; the
     ;; machine was not.
     ;; A TIMEOUT is also :unknown. The probe was killed mid-run, so its partial
     ;; stdout is not a verdict — treating it as one is the same cry-wolf failure
     ;; the paragraph above describes, in the one shape that recurs on schedule.
     ;; Measured 2026-08-13: the factory-plane check took 46.3 min wall clock
     ;; against this 900s budget on a machine sitting at load 100–157, so it was
     ;; killed and classified :regressed every firing while the plane was fine.
     ;; Loading the plane once instead of three times (2026-08-13) cuts it, but
     ;; the fit is load-dependent, not structural — so the misclassification has
     ;; to be closed here as well as made faster there.
     :state (cond (zero? code) :ok
                  timeout? :unknown
                  (some #(str/includes? out %)
                        ["no such file" "Cannot find module" "ENOENT"
                         "not checked out" "command not found"]) :unknown
                  :else :regressed)
     :note (let [t (str/trim out)
                 tail (subs t (max 0 (- (count t) 200)))]
             (if timeout? (str "TIMEOUT (900s) — 未完了。末尾: " tail) tail))}))

(defn probe-guarantees []
  (cond-> [(probe-script :artifact-custody "scripts/annex-custody-verify.cljs"
                         ["--names" "tsukuru-manufacturing-artifacts" "--sample" "2"])
           (probe-script :artifact-path-policy "scripts/verify-artifact-path-policy.cljs"
                         ["--names" "tsukuru-manufacturing-artifacts"])]
    ;; The factory-plane check loads the whole datom plane ONCE (it used to load
    ;; it three times, one child process per assertion). The load is the whole
    ;; cost, so this is a 3x cut — but the absolute number is set by the host's
    ;; load, not by this script: ~1 min on an idle machine, 15 min+ at load 80.
    ;; Skippable so an operator can get a fast answer, but ON by default: a
    ;; guarantee nobody re-runs is the one that rots.
    (not skip-slow?)
    (conj (probe-script :factory-plane "scripts/verify-tsukuru-factory-plane.cljs" []))))

(defn probe-actor-tests []
  (if-not (.existsSync fs actor)
    {:probe :actor-tests :state :unknown :note "tsukuru-actor not checked out"}
    (let [{:keys [code out]} (sh actor "clojure" ["-M:test"] {:timeout-ms 900000})
          m (re-find #"Ran (\d+) tests containing (\d+) assertions" out)
          f (re-find #"(\d+) failures, (\d+) errors" out)]
      {:probe :actor-tests
       :state (if (zero? code) :ok :regressed)
       :note (if m (str (nth m 1) " tests / " (nth m 2) " assertions"
                        (when f (str " — " (nth f 1) " failures, " (nth f 2) " errors")))
                 "could not parse the test summary")})))

(defn probe-confidential-preconditions
  "The four things that must become true before this plane may hold a third
  party's confidential drawing. Two are machine-checkable and checked here; two
  are not, and the loop reports them as :human rather than guessing."
  []
  (let [enc (:out (sh dataset "git" ["annex" "info" "b2"] {:timeout-ms 60000}))
        encrypted? (and (str/includes? enc "encryption:")
                        (not (str/includes? enc "encryption: none")))
        pol (try (edn/read-string (.readFileSync fs (str dataset "/index/policy.edn") "utf8"))
                 (catch :default _ nil))
        permitted? (get-in pol [:policy/confidential-material :permitted?])]
    [{:probe :artifact-remote-encrypted
      :state (cond (str/blank? enc) :unknown encrypted? :ok :else :blocked)
      :note (if encrypted? "b2 remote reports encryption" "b2 remote is encryption: none — bytes are plaintext in B2")}
     {:probe :artifact-confidential-permitted
      ;; NEVER flipped by this loop. It reads the dataset's own declaration; a
      ;; machine cannot read an NDA, and a loop that could mark this :ok would
      ;; be the single most dangerous line in the file.
      :state (if permitted? :human :blocked)
      :note (if pol
              (str "policy.edn says permitted?=" (pr-str permitted?)
                   " — flipping this is a human decision (NDA + dedicated bucket)")
              "index/policy.edn unreadable")}]))

;; ───────────────────────── tick ─────────────────────────

(defn- previous-states []
  (try
    (let [lines (->> (str/split-lines (.readFileSync fs ledger-file "utf8"))
                     (remove str/blank?))]
      (when-let [last-line (last lines)]
        (into {} (map (juxt :probe :state) (:probes (edn/read-string last-line))))))
    (catch :default _ nil)))

(let [t0 (js/Date.now)
      _ (log! "tick start" (if skip-slow? "(--skip-slow)" ""))
      probes (vec (concat [(probe-seed) (probe-tsukuru-worker)]
                          (probe-ref-reachability)
                          (probe-guarantees)
                          [(probe-actor-tests)]
                          (probe-confidential-preconditions)))
      prev (previous-states)
      changed (vec (for [{:keys [probe state note]} probes
                         :let [was (get prev probe)]
                         :when (and was (not= was state))]
                     {:probe probe :from was :to state :note note}))
      by-state (frequencies (map :state probes))
      entry {:tick/at (.toISOString (js/Date.))
             :tick/adr "ADR-2800003200"
             :tick/duration-ms (- (js/Date.now) t0)
             :tick/skipped-slow? skip-slow?
             :tick/summary by-state
             :tick/changed changed
             :probes probes}]
  (doseq [{:keys [probe state note]} probes]
    (log! (str "  " (name state) "\t" (name probe) "\t" note)))
  (when (seq changed)
    (log! "*** STATE CHANGED ***")
    (doseq [{:keys [probe from to]} changed]
      (log! (str "  " (name probe) ": " (name from) " -> " (name to)))))
  (try
    (.appendFileSync fs ledger-file (str (pr-str entry) "\n"))
    (catch :default e (log! "ledger append failed:" (.-message e))))
  (log! "tick done" (pr-str by-state) (str (quot (- (js/Date.now) t0) 1000) "s"))
  ;; Always 0: this is a monitor, not a gate. The gates are the verify scripts
  ;; it runs, and each of those keeps its own exit code.
  (js/process.exit 0))
