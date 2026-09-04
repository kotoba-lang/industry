#!/usr/bin/env nbb
;; Resident operator for network-awai/app-hyakka's multi-domain ingest.
;; Credentials remain on the operator host; fleet nodes never receive B2 or
;; Kotobase signing keys. Raw bytes are uploaded before their Git receipt is
;; committed, then the rebuildable EDN projection is published to Kotobase.
;; A tick is complete only after the matching content-addressed catalogue is
;; deployed and verified through wiki.kotobase.net/health.

(require '[cljs.reader :as edn]
         '[clojure.string :as str])

(def cp (js/require "node:child_process"))
(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))

(def root "/Users/junkawasaki/github/com-junkawasaki")
(def default-worktree (str (.homedir os) "/.gftd/worktrees/app-hyakka-resident"))
(def worktree (or (aget js/process.env "HYAKKA_WORKTREE") default-worktree))
(def archive-dir (or (aget js/process.env "HYAKKA_ARCHIVE_DIR")
                     (str (.homedir os) "/.gftd/hyakka-archive")))
(def lock-dir (str (.homedir os) "/.gftd/locks/hyakka-knowledge-ingest.lock"))
;; The graph these ledgers belong to. `:apex` requires graph scope == issuer
;; DID, so a seed that derives anything else does not fail — it writes to a
;; DIFFERENT ref, silently forking the corpus.
;;
;; This constant exists because that nearly happened on 2026-08-28. Recovering
;; a 64-hex `hyakka-kotobase-seed` from `~/.kagi/vault.edn.bak` and finding its
;; DID recorded in ADR-2607311100 looked like proof the right seed was back.
;; It was the v1 seed. The v1 graph was orphaned on 2026-08-24 when everything
;; was re-published under v2, and the next tick would have written 296 ledgers
;; into a graph nobody reads. The identity-seeds map says so four lines below
;; the line that was read.
;;
;; A seed is not identified by being 64 hex characters, nor by deriving a DID
;; that appears somewhere in the docs. It is identified by deriving THIS one.
(def expected-tenant-did "did:key:z6MkwF7M3TPYUvdNP5NtWfr6aA2xtr7dsETCwx26fnVamjQo")

(def b2-endpoint "https://s3.us-west-004.backblazeb2.com")
(def public-health "https://wiki.kotobase.net/health")

(defn run [args {:keys [dir env]}]
  (let [r (.spawnSync cp (first args) (clj->js (rest args))
                      (clj->js (cond-> {:encoding "utf8" :maxBuffer (* 128 1024 1024)}
                                 dir (assoc :cwd dir) env (assoc :env env))))]
    {:exit (if (nil? (.-status r)) 1 (.-status r))
     :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn fail! [message]
  (throw (js/Error. message)))

(defn checked [args opts]
  (let [{:keys [exit out err]} (run args opts)]
    (when (pos? exit)
      (fail! (str (str/join " " args) ": "
                  (str/trim (str err (when (and (seq err) (seq out)) "\n") out)))))
    out))

;; ---- per-step measurement -------------------------------------------------
;;
;; Two defects, one fix. (1) A tick is a sequence of expensive steps and the
;; log recorded only the whole; a 2h tick could not be attributed to a step, so
;; "which work should move to the fleet" (root ADR-2608300100 decision 1) had
;; no measurement behind it. (2) `run` uses spawnSync, which CAPTURES child
;; stdout instead of streaming it — so a step in progress prints nothing at all
;; and an operator cannot tell slow from hung. Measured 2026-08-29: a publish
;; step was silent for 93 minutes while it was in fact draining normally.
;;
;; The load average is recorded BESIDE each duration, because on this
;; workstation (many concurrent agents) a duration without the load it was
;; measured under is not a fact about the step — the same catalog build has
;; been observed at minutes and at tens of minutes. CLAUDE.md's rule.
(def step-timings (atom []))

(defn- load1
  "Current 1-minute load average, or nil when it cannot be read. Never throws:
  this is decoration on a measurement, and must not be able to fail a tick."
  []
  (try
    (let [out (:out (run ["sysctl" "-n" "vm.loadavg"] {}))]
      (some-> (re-find #"[\d.]+" (str out)) js/parseFloat))
    (catch :default _ nil)))

(defn timed!
  "Run `f`, recording and printing its wall time and the load it ran under.

  Prints a line BEFORE the step as well: a step that is running must be
  distinguishable from a step that has hung, and with captured child output
  the start marker is the only evidence available while it runs."
  [label f]
  (let [started (js/Date.now)
        load-at-start (load1)]
    (println (str "▶ " label " (load " (or load-at-start "?") ")"))
    (try
      (let [result (f)
            elapsed-ms (- (js/Date.now) started)]
        (swap! step-timings conj {:step label :ms elapsed-ms :load load-at-start})
        (println (str "✓ " label " " (.toFixed (/ elapsed-ms 1000) 1) "s"
                      " (load " (or load-at-start "?") ")"))
        result)
      (catch :default e
        ;; A failed step is still a measured step. Dropping its duration is how
        ;; "the tick died somewhere" loses the one number that says where.
        (let [elapsed-ms (- (js/Date.now) started)]
          (swap! step-timings conj {:step label :ms elapsed-ms
                                    :load load-at-start :failed? true})
          (println (str "✗ " label " " (.toFixed (/ elapsed-ms 1000) 1) "s"
                        " (load " (or load-at-start "?") ") — failed")))
        (throw e)))))

(defn print-step-timings!
  "One line per step, slowest first. Printed even when the tick failed, so the
  question `where did the time go` is answerable from the log alone."
  []
  (when (seq @step-timings)
    (let [total (reduce + (map :ms @step-timings))]
      (println (str "step timings (total " (.toFixed (/ total 1000) 1) "s):"))
      (doseq [{:keys [step ms load failed?]} (sort-by :ms > @step-timings)]
        (println (str "  " (.padStart (.toFixed (/ ms 1000) 1) 8) "s"
                      "  load " (.padStart (str (or load "?")) 6)
                      "  " step (when failed? " [FAILED]")))))))

(def lock-owner-file (str lock-dir "/owner.edn"))

(defn- alive?
  "Is `pid` a live process owned by this user? `kill -0` answers without
  signalling. Anything we cannot answer is treated as ALIVE — reclaiming a
  lock we merely failed to interrogate is the dangerous direction."
  [pid]
  (if-not (and (number? pid) (pos? pid))
    true
    (try (.kill js/process pid 0) true
         (catch :default e (not= "ESRCH" (.-code e))))))

(defn- lock-owner []
  (try (edn/read-string (.readFileSync fs lock-owner-file "utf8"))
       (catch :default _ nil)))

(defn- reclaim-stale-lock!
  "A bare `mkdir` lock with no owner record cannot tell `a tick is working`
  from `a tick died holding it`, and reports both as the same refusal. On
  2026-08-15 a tick died inside `npm run build` (shadow-cljs par-compile
  abort) and the next FIVE hourly ticks refused to run for ~7h — the ingest
  looked idle rather than broken. So the lock now records its owner and a
  later tick may reclaim it, but only when it can SHOW the owner is gone:
  a dead pid, or a lock with no owner record at all (written by the old
  scheme, or by a tick killed between mkdir and write)."
  []
  (let [{:keys [pid started-at]} (lock-owner)]
    (cond
      (nil? pid)
      (do (println (str "reclaiming ownerless lock " lock-dir))
          (try (.rmSync fs lock-dir #js {:recursive true}) true
               (catch :default _ false)))

      (alive? pid) false

      :else
      (do (println (str "reclaiming lock from dead pid " pid
                        " (held since " started-at ")"))
          (try (.rmSync fs lock-dir #js {:recursive true}) true
               (catch :default _ false))))))

(defn- claim! []
  (.mkdirSync fs lock-dir)
  (.writeFileSync fs lock-owner-file
                  (pr-str {:pid (.-pid js/process)
                           :started-at (.toISOString (js/Date.))
                           :host (.hostname os)})))

(defn acquire-lock! []
  (.mkdirSync fs (.dirname path lock-dir) #js {:recursive true})
  (try (claim!)
       (catch :default _
         ;; Occupied. Reclaim only on evidence the owner is gone, then make
         ;; exactly one more attempt — a retry loop here would race two
         ;; ticks into the same worktree.
         (if (reclaim-stale-lock!)
           (try (claim!)
                (catch :default _
                  (fail! (str "another tick took " lock-dir " during reclaim"))))
           (fail! (str "another tick holds " lock-dir " — owner "
                       (pr-str (lock-owner))))))))

(defn release-lock! []
  (try (.rmSync fs lock-dir #js {:recursive true}) (catch :default _ nil)))

(defn process-env [] (js/Object.assign #js {} js/process.env))

(defn resolve-b2-env []
  (let [out (checked ["nbb" "--classpath"
                      (str root "/orgs/kotoba-lang/secret-resolve/src:"
                           root "/scripts/nbb_compat:" root)
                      (str root "/scripts/b2-creds.cljs") "--json"] {:dir root})
        creds (js->clj (js/JSON.parse out))
        e (process-env)]
    (doseq [[k v] creds] (aset e k v))
    (aset e "AWS_DEFAULT_REGION" "us-west-004")
    (aset e "HYAKKA_ARCHIVE_DIR" archive-dir)
    (aset e "HYAKKA_SUPERPROJECT_ROOT" root)
    e))

(defn changed-receipts []
  (->> (str/split-lines (checked ["git" "status" "--porcelain" "--untracked-files=all"
                                  "--" "knowledge/receipts"]
                                 {:dir worktree}))
       (keep #(when (>= (count %) 4) (subs % 3)))
       vec))

(defn unpublished-ledgers []
  (let [published-path (.join path worktree ".resident" "published.edn")
        published (if (.existsSync fs published-path)
                    (set (edn/read-string (.readFileSync fs published-path "utf8")))
                    #{})]
    (->> (str/split-lines (checked ["git" "ls-files" "knowledge/ledger"] {:dir worktree}))
         (filter #(str/ends-with? % ".datoms.edn"))
         (remove published)
         sort vec)))

;; Raw entries whose bytes are in neither the local archive nor B2. Reported
;; at the end of the tick so the run is not mistaken for a clean one, without
;; letting a single lost object abandon everything after it.
(def lost-archives (atom []))

;; Why the Kotobase datom plane was not written this tick, if it was not.
(def kotobase-skip (atom nil))

(def kotobase-failure
  "The error from a datom-plane publish that FAILED, as opposed to one that was
  skipped for a missing credential.

  These are the same event for the public catalogue and were not treated the
  same. `publish-kotobase!` returns a map when it cannot run and THROWS when it
  runs and fails, and only the first case let the tick continue — so a missing
  credential was survivable while a transport error, a compile abort or a
  rejected batch took the public deploy down with it.

  Measured 2026-08-29: the publish step died after 807 s when shadow-cljs
  aborted par-compile under load ~400. The books merged to main hours earlier
  were in the committed catalogue and never reached the Worker, because the
  step that deploys it is three lines below the one that threw. ADR-2607311100
  already says these are different sinks with different audiences; that has to
  hold for a failure and not only for an absence."
  (atom nil))

(defn upload-raw!
  "Put a receipt's archived bytes in B2. Returns the entries it could not.

  The local-existence check used to come FIRST, before asking B2 whether the
  object was already there. Measured 2026-08-28: 69 of 734 raw entries had no
  local file and 68 of them were already in B2 — the bytes had been uploaded
  and the local copy pruned. Demanding a local copy to prove an upload that had
  already happened turns ordinary housekeeping into a permanent stop.

  The 69th is gone from both, and that one stopped this resident for 17 hours:
  `fail!` threw, the caller's doseq abandoned the whole tick, and every later
  receipt, ledger, projection and deploy went with it. One lost object must not
  mean no pipeline. It is RETURNED instead — the run continues, and the caller
  reports the loss rather than retrying it hourly forever.

  A byte-length mismatch stays a hard failure. That is corruption, not absence."
  [env receipt-path]
  (let [receipt (edn/read-string (.readFileSync fs (.join path worktree receipt-path) "utf8"))
        bucket (aget env "B2_BUCKET")
        lost (atom [])]
    (doseq [{:keys [archive-relative-path object-key bytes sha256]} (:run/raw receipt)
            :let [archive-path (.join path archive-dir archive-relative-path)
                  dst (str "s3://" bucket "/" object-key)
                  head (run ["aws" "s3api" "head-object" "--bucket" bucket "--key" object-key
                             "--endpoint-url" b2-endpoint] {:env env})
                  in-b2? (zero? (:exit head))]]
      (cond
        ;; Already there. The local copy is housekeeping, not evidence.
        in-b2? nil

        (.existsSync fs archive-path)
        (checked ["aws" "s3" "cp" archive-path dst "--endpoint-url" b2-endpoint
                  "--only-show-errors"] {:env env})

        :else
        (do (swap! lost conj {:receipt receipt-path :sha256 sha256
                              :object-key object-key :bytes bytes})
            (println (str "LOST raw bytes in neither place: " sha256
                          " (" bytes " bytes, " receipt-path ")"))))

      (when (or in-b2? (.existsSync fs archive-path))
        (let [verified (checked ["aws" "s3api" "head-object" "--bucket" bucket "--key" object-key
                                "--endpoint-url" b2-endpoint "--query" "ContentLength"
                                "--output" "text"] {:env env})]
          (when-not (= (str bytes) (str/trim verified))
            (fail! (str "B2 byte length mismatch for " sha256))))))
    @lost))

(defn kotobase-seed [env]
  (let [e (js/Object.assign #js {} env)
        _ (aset e "KAGI_HOME" (str (.homedir os) "/.kagi"))
        kagi (str root "/orgs/kotoba-lang/kagi/bin/kagi")
        seed (str/trim
              (checked [kagi "get" "hyakka-kotobase-seed"]
                       {:dir worktree :env e}))]
    (when-not (re-matches #"[0-9a-fA-F]{64}" seed)
      (fail! "hyakka-kotobase-seed is not a 32-byte hex seed"))
    seed))

(defn kagi-get-opt
  "Read one kagi item, or nil if absent. `kagi get` exits non-zero and writes
  `no such item` to stderr for a missing item, so absence is not an error here
  — only a present-but-unreadable item would be, and that surfaces as an empty
  value the caller treats as absent."
  [env name]
  (let [e (js/Object.assign #js {} env)
        _ (aset e "KAGI_HOME" (str (.homedir os) "/.kagi"))
        kagi (str root "/orgs/kotoba-lang/kagi/bin/kagi")
        r (run [kagi "get" name] {:dir worktree :env e})]
    (when (zero? (:exit r))
      (let [v (str/trim (:out r))]
        (when-not (str/blank? v) v)))))

(defn authn-service-creds
  "The tenant service-account credential for the Biscuit datom-plane path
  (ADR-2608291500), or nil when not provisioned in this vault. The service
  token is a secret; the tenant id is a public identifier. Both are required
  for the Biscuit path; a partial pair is treated as absent."
  [env]
  (let [token (kagi-get-opt env "hyakka-authn-service-token")
        tenant (kagi-get-opt env "hyakka-authn-tenant-id")]
    (when (and token tenant) {:service-token token :tenant-id tenant})))

(defn publish-git! [env]
  ;; The generated catalogue is committed with the ledger that produced it,
  ;; so repository main, the deployed Worker and its SHA-256 snapshot cannot
  ;; silently describe three different heads.
  (checked ["npm" "run" "catalog"] {:dir worktree :env env})
  (checked ["git" "add" "knowledge/ledger" "knowledge/receipts"
            "src/hyakka/catalog.cljc"] {:dir worktree :env env})
  (let [st (checked ["git" "status" "--porcelain" "--" "knowledge"
                     "src/hyakka/catalog.cljc"] {:dir worktree :env env})]
    (if (str/blank? st)
      false
      (do
      (checked ["git" "commit" "-m" "ingest: resident knowledge tick"] {:dir worktree :env env})
      (checked ["git" "push" "origin" "HEAD:resident/knowledge-ingest"] {:dir worktree :env env})
      (let [r (run ["gh" "api" "repos/network-awai/app-hyakka/merges" "-f" "base=main"
                    "-f" "head=resident/knowledge-ingest"
                    "-f" "commit_message=ingest: merge resident knowledge tick"]
                   {:dir worktree :env env})]
        (when (and (pos? (:exit r)) (not (str/includes? (:err r) "No commits between")))
          (fail! (str "server-side merge failed: " (str/trim (:err r)))))
        true)))))

(defn sync-main! [env]
  (checked ["git" "fetch" "origin" "main"] {:dir worktree :env env})
  (let [ff (run ["git" "merge" "--ff-only" "origin/main"] {:dir worktree :env env})]
    (when (pos? (:exit ff))
      ;; Diverged, not merely behind: a previous tick committed and pushed but
      ;; its server-side merge was refused (measured 2026-08-29: main moved
      ;; under the tick, the generated catalogue conflicted, the merge 409'd,
      ;; and every later tick failed this fast-forward the same way for hours
      ;; — a silent permanent stall, since only launchd reads the exit code).
      ;; When the stranded commits are already on the integration branch,
      ;; retry the server-side merge once — main may have moved past the
      ;; conflict — then fast-forward onto the result. A conflict that
      ;; persists is named with the stuck commits and the runbook instead of
      ;; being retried into the same wall every 15 minutes.
      (let [ahead (str/trim (checked ["git" "rev-list" "--oneline" "origin/main..HEAD"]
                                     {:dir worktree :env env}))
            on-branch (zero? (:exit (run ["git" "merge-base" "--is-ancestor" "HEAD"
                                          "refs/remotes/origin/resident/knowledge-ingest"]
                                         {:dir worktree :env env})))]
        (when-not on-branch
          ;; Push first so the retry below always merges what this worktree
          ;; actually holds; HEAD:resident/knowledge-ingest is the wrapper's
          ;; own integration branch.
          (run ["git" "push" "origin" "HEAD:resident/knowledge-ingest"]
               {:dir worktree :env env}))
        (let [merge-result (run ["gh" "api" "repos/network-awai/app-hyakka/merges"
                                 "-f" "base=main" "-f" "head=resident/knowledge-ingest"
                                 "-f" "commit_message=ingest: merge stranded resident tick"]
                                {:dir worktree :env env})]
          (when (and (pos? (:exit merge-result))
                     (not (str/includes? (:err merge-result) "No commits between")))
            (fail! (str "resident worktree diverged from origin/main and the server-side "
                        "merge retry was refused (" (str/trim (:err merge-result)) "). "
                        "Stranded local commits:\n" ahead "\n"
                        "Runbook: merge origin/resident/knowledge-ingest onto origin/main in a "
                        "clean worktree, resolving src/hyakka/catalog.cljc by `npm run catalog` "
                        "regeneration (never by marker editing), land it server-side, then this "
                        "worktree fast-forwards on the next tick.")))
          (checked ["git" "fetch" "origin" "main"] {:dir worktree :env env})
          (checked ["git" "merge" "--ff-only" "origin/main"] {:dir worktree :env env}))))))

(defn catalogue-id []
  (let [source (.readFileSync fs (.join path worktree "src/hyakka/catalog.cljc") "utf8")
        match (re-find #":catalog-id \"(sha256:[0-9a-f]{64})\"" source)]
    (or (second match) (fail! "generated catalogue has no SHA-256 catalog id"))))

(defn sleep! [millis]
  (js/Atomics.wait (js/Int32Array. (js/SharedArrayBuffer. 4)) 0 0 millis))

(defn verify-live-catalogue! [expected]
  ;; A custom-domain deployment can briefly answer from the previous Worker
  ;; version. Retry the semantic value, not merely curl transport success.
  (loop [attempt 1]
    (let [url (str public-health "?catalog=" (subs expected 7 19)
                   "&attempt=" attempt)
          response (run ["curl" "-fsS" url] {})
          health (when (zero? (:exit response))
                   (try (js->clj (js/JSON.parse (:out response)) :keywordize-keys true)
                        (catch :default _ nil)))]
      (cond
        (and (:ok health) (= expected (:catalog-id health))) health
        (< attempt 10) (do (sleep! 2000) (recur (inc attempt)))
        :else (fail! (str "live catalogue verification failed after " attempt
                         " attempts: expected " expected ", got "
                         (:catalog-id health)))))))

(defn live-catalogue-id []
  ;; A failed deploy must remain retryable even after its ledger was already
  ;; projected to Kotobase. Transport or JSON failures deliberately read as
  ;; "unknown", which causes a safe redeploy followed by semantic verification.
  (let [response (run ["curl" "-fsS" (str public-health "?resident=preflight")] {})]
    (when (zero? (:exit response))
      (try
        (let [health (js->clj (js/JSON.parse (:out response)) :keywordize-keys true)]
          (when (:ok health) (:catalog-id health)))
        (catch :default _ nil)))))

(defn deploy-required? [pending expected live]
  (or (seq pending) (not= expected live)))

(defn validate-policy! []
  (doseq [[pending expected live wanted]
          [[[] "sha256:new" "sha256:new" false]
           [[] "sha256:new" "sha256:old" true]
           [[] "sha256:new" nil true]
           [["ledger"] "sha256:new" "sha256:new" true]]]
    (when-not (= wanted (boolean (deploy-required? pending expected live)))
      (fail! (str "deploy retry policy validation failed: "
                  (pr-str {:pending pending :expected expected :live live})))))
  (println "hyakka resident deploy retry policy validated"))

(defn verify-tenant-did!
  "Does this seed derive the graph we publish into? Returns nil if it does.

  Delegates to app-hyakka's own `scripts/verify_identity.cljs`, which derives
  through `kotobase.cid` — the authority the live plane uses — rather than
  re-implementing base58btc here. A second implementation of a DID derivation
  is precisely the thing that produces a plausible wrong answer.

  The seed is passed in the environment and never appears in argv."
  [env seed]
  (let [e (js/Object.assign #js {} env)
        _ (aset e "HYAKKA_SEED" seed)
        r (run ["nbb" "--classpath" "../../kotoba-lang/kotobase-client/src:../../kotoba-lang/org-nist-sha2/src"
                "scripts/verify_identity.cljs" expected-tenant-did]
               {:dir worktree :env e})]
    (when-not (zero? (:exit r))
      (str "seed derives a different tenant DID than " expected-tenant-did
           " — publishing would fork the corpus into a graph nobody reads. "
           (str/trim (str (:out r) " " (:err r)))))))

(defn publish-kotobase!
  "Publish pending ledgers to the Kotobase datom plane. Returns why it could not.

  The Kotobase ref and the public catalogue are DIFFERENT SINKS with different
  audiences: the ref is where cross-corpus Datalog joins live, the catalogue is
  what wiki.kotobase.net serves. This used to throw when the seed was missing,
  and the throw took the whole tick with it — so an absent credential for one
  sink silently stopped the other one from ever deploying.

  Two credential paths, preferred in order (ADR-2608291500):

  1. Biscuit — a tenant service account (kagi `hyakka-authn-service-token` +
     `hyakka-authn-tenant-id`). The datom plane went Biscuit-required
     (net-kotobase ADR-2608280230) and 401s a self-issued CACAO, so this is
     now the working path; `publish.cljs` exchanges the token for a
     graph-scoped Biscuit and writes to the tenant graph. No seed is needed.
  2. CACAO — the legacy `hyakka-kotobase-seed`, kept as a fallback. It has
     been absent from kagi since 2026-08-15 (ADR-2608271450) and, even when
     present, the plane now rejects it — so this path exists only so a vault
     without the service account degrades to a spoken skip rather than a
     crash that also stops the public deploy.

  A missing credential is a spoken SKIP that leaves the ledgers pending and
  never takes the tick (and its public deploy) down with it. A present CACAO
  seed is still checked against `expected-tenant-did` before a ledger moves."
  [env ledgers]
  (when (seq ledgers)
    (let [e (js/Object.assign #js {} env)]
      (if-let [{:keys [service-token tenant-id]} (authn-service-creds env)]
        (do (aset e "HYAKKA_AUTHN_SERVICE_TOKEN" service-token)
            (aset e "HYAKKA_AUTHN_TENANT_ID" tenant-id)
            (println "Kotobase publish via tenant Biscuit (service account) —"
                     (count ledgers) "pending ledger(s)")
            (checked ["npm" "run" "publish" "--" "--knowledge" "true" "--batch" "100"]
                     {:dir worktree :env e})
            nil)
        (let [seed (try {:ok (kotobase-seed env)}
                        (catch :default ex {:err (or (.-message ex) (str ex))}))]
          (if-let [why (:err seed)]
            (do (binding [*out* *err*]
                  (println "SKIP Kotobase publish —" (count ledgers)
                           "ledger(s) stay pending (no service account, no seed):"
                           (str/trim (str why))))
                {:skipped (count ledgers) :why (str/trim (str why))})
            (if-let [wrong (verify-tenant-did! env (:ok seed))]
              (do (binding [*out* *err*]
                    (println "SKIP Kotobase publish —" (count ledgers)
                             "ledger(s) stay pending:" wrong))
                  {:skipped (count ledgers) :why wrong})
              (do (aset e "HYAKKA_SEED" (:ok seed))
                  (println "Kotobase publish via legacy CACAO seed —"
                           (count ledgers) "pending ledger(s)")
                  (checked ["npm" "run" "publish" "--" "--knowledge" "true" "--batch" "100"]
                           {:dir worktree :env e})
                  nil))))))))

(defn deploy-public! [env]
  (println "verify tests before public deploy")
  (checked ["npm" "test"] {:dir worktree :env env})
  (println "build content-addressed public catalogue")
  (checked ["npm" "run" "build"] {:dir worktree :env env})
  (let [expected (catalogue-id)
        deploy-out (checked ["npx" "wrangler" "deploy" "--config" "worker/wrangler.jsonc"]
                            {:dir worktree :env env})
        health (verify-live-catalogue! expected)
        actual (:catalog-id health)]
    (when-not (and (:ok health) (= expected actual))
      (fail! (str "live catalogue verification failed: expected " expected
                  ", got " actual)))
    (when-let [version-line (first (filter #(str/includes? % "Current Version ID:")
                                           (str/split-lines deploy-out)))]
      (println (str/trim version-line)))
    (println "live catalogue verified" actual)))

;; ---- post-deploy sinks: IPFS index + R2 Data Catalog -----------------------
;;
;; Both are content-addressed projections of the SAME ledger the deploy above
;; serves. Before 2026-09-04 neither ran in the loop: index-root.edn sat at
;; its 2026-09-01 build while the wiki moved on, and the R2 Iceberg tables
;; were only ever refreshed by hand. They run AFTER the public deploy and are
;; best-effort — a failure here leaves the tick's exit code untouched unless
;; BOTH fail, because neither sink can invalidate what wiki.kotobase.net
;; already serves (each is addressed by content, not by this process).

(def ipfs-post-failures (atom []))

(defn- ipfs-index! [env]
  "Rebuild the search index blocks, pin them into the local ipfs node (which
  ipfs.kotobase.net serves from), and republish the wiki's IPNS name so the
  mutable pointer lands on the new root. Best-effort per stage."
  (checked ["nbb" "--classpath" "src:scripts" "scripts/build_index.cljs"]
           {:dir worktree :env env})
  (let [blocks-dir (str worktree "/.index-build/blocks")
        root (let [s (checked ["cat" (str worktree "/index-root.edn")] {})]
               (:index/root-cid (edn/read-string s)))]
    (when-not root
      (fail! "ipfs-index: no :index/root-cid in index-root.edn after build"))
    (checked ["ipfs" "add" "-r" "--pin" "--quieter" blocks-dir] {})
    ;; import the root block explicitly so the pin covers the whole DAG
    (checked ["ipfs" "add" "--pin" "--quieter"
              (str blocks-dir "/" root)] {})
    ;; republish the wiki's IPNS name under a dedicated key (mints on first run)
    (let [key (if (some #{"hyakka-wiki-index"}
                        (str/split-lines (checked ["ipfs" "key" "list"] {})))
                "hyakka-wiki-index"
                (do (checked ["ipfs" "key" "gen" "hyakka-wiki-index"
                              "--type=ed25519"] {})
                    "hyakka-wiki-index"))]
      (checked ["ipfs" "name" "publish" "--key" key root] {}))
    ;; index-root.edn is a tracked local record; restoring it keeps the
    ;; worktree clean so the next tick's ff-only merge cannot be blocked by
    ;; sink-written drift. The block CID above is the durable record.
    (run ["git" "checkout" "--" "index-root.edn"] {:dir worktree :env env})
    (println "ipfs index root" root)))

(defn- r2-datalake-sync! [env]
  "Refresh the R2 Data Catalog Iceberg tables from the freshly committed
  catalogue. datalake_sync.cljs resolves CF_CATALOG_TOKEN itself (env, then
  Keychain) and refuses — not fails — when it cannot run."
  (checked ["nbb" "--classpath" "src" "scripts/datalake_sync.cljs"]
           {:dir worktree :env env}))

(defn- post-deploy-sinks!
  "Run both post-deploy sinks; record failures instead of throwing so one
  broken sink cannot hide the other, and a broken sink cannot fail the tick
  for work the wiki has already served. Exit-code reporting happens at the
  end of main via ipfs-post-failures."
  [env]
  (doseq [[label f] [["ipfs-index (build + pin + ipns)" ipfs-index!]
                     ["r2-datalake (Iceberg sync)" r2-datalake-sync!]]]
    (try
      (timed! label (fn [] (f env)))
      (catch :default e
        (swap! ipfs-post-failures conj {:step label :error (str (.-message e))})
        (binding [*out* *err*]
          (println (str "✗ " label " — best-effort sink failed: "
                        (or (.-message e) (str e)))))))))

(defn publish-then-deploy!
  "Publish to the datom plane, then deploy the public catalogue EITHER WAY.

  The two are different sinks with different audiences (ADR-2607311100): the
  ref is where cross-corpus Datalog joins live, the catalogue is what
  wiki.kotobase.net serves. `publish-kotobase!` already refuses to take the
  tick down when a credential is missing — it returns a map. But when it RUNS
  and fails it throws, and a throw three lines above the deploy skipped the
  deploy. Absence was survivable; failure was not, for no reason anyone chose.

  Measured 2026-08-29: the publish died after 807 s (shadow-cljs aborted
  par-compile at load ~400). A connector merged to main hours earlier was in
  the committed catalogue and never reached the Worker.

  The pending ledgers stay pending on either path, so the next tick retries
  exactly as it does after a skip. The tick still ends non-zero — the failure
  is reported, not swallowed.

  Takes the two steps as thunks so the ordering can be exercised without
  publishing or deploying anything (`HYAKKA_DEPLOY_ISOLATION_SELFTEST=1`)."
  [publish! deploy! deploy?]
  (let [outcome (try {:ok (publish!)} (catch :default e {:failed e}))]
    (reset! kotobase-skip (:ok outcome))
    (reset! kotobase-failure (:failed outcome))
    (when deploy? (deploy!))
    outcome))

(defn deploy-isolation-selftest!
  "Show that a FAILING publish still deploys, and that a succeeding one does
  too — and that `deploy? false` deploys neither. Both directions, because a
  version that always deployed would pass the first check alone."
  []
  (let [deployed (atom 0)
        fail! (fn [] (throw (js/Error. "simulated publish failure")))
        ok! (fn [] {:skipped 0})
        deploy! (fn [] (swap! deployed inc))
        check (fn [label expected actual]
                (if (= expected actual)
                  (println "  ok  " label)
                  (do (println "  FAIL" label
                               (str "expected " (pr-str expected) " got " (pr-str actual)))
                      (set! (.-exitCode js/process) 1))))]
    (reset! deployed 0)
    (let [r (publish-then-deploy! fail! deploy! true)]
      (check "a failing publish still deploys" 1 @deployed)
      (check "  and the failure is recorded" true (some? (:failed r)))
      (check "  and is visible in the atom" true (some? @kotobase-failure)))
    (reset! deployed 0) (reset! kotobase-failure nil) (reset! kotobase-skip nil)
    (let [r (publish-then-deploy! ok! deploy! true)]
      (check "a succeeding publish deploys" 1 @deployed)
      (check "  and records no failure" nil (:failed r)))
    (reset! deployed 0) (reset! kotobase-failure nil) (reset! kotobase-skip nil)
    (publish-then-deploy! fail! deploy! false)
    (check "deploy? false deploys nothing, even after a failure" 0 @deployed)
    (reset! kotobase-failure nil) (reset! kotobase-skip nil)
    (println "deploy-isolation selftest done")))

(defn main []
  (acquire-lock!)
  (try
    (when-not (.existsSync fs (.join path worktree ".git"))
      (fail! (str "resident worktree missing: " worktree)))
    (let [catch-up-only? (= "1" (aget js/process.env "HYAKKA_CATCH_UP_ONLY"))
          env (if catch-up-only? (process-env) (resolve-b2-env))]
      (timed! "sync-main (pre)" #(sync-main! env))
      (let [receipts
            (if catch-up-only?
              []
              (do
                (timed! "ingest"
                        #(checked ["nbb" "--classpath"
                                   "src:../kotoba-lang/chain-observer/src"
                                   "scripts/resident_ingest.cljs" "--once"]
                                  {:dir worktree :env env}))
                (let [xs (changed-receipts)
                      lost (timed! "upload-raw (B2)"
                                   #(vec (mapcat (fn [r] (upload-raw! env r)) xs)))]
                  (reset! lost-archives lost)
                  (timed! "publish-git (catalog + commit + merge)" #(publish-git! env))
                  xs)))]
        ;; A server-side merge advances main beyond the resident branch. Pull
        ;; that exact merge tree before projection and deploy; last-writer-wins
        ;; deploys from a stale checkout previously reverted this zone.
        (timed! "sync-main (post)" #(sync-main! env))
        (let [pending (unpublished-ledgers)
              expected (catalogue-id)
              live (live-catalogue-id)
              deploy? (boolean (deploy-required? pending expected live))]
          (println "Kotobase pending ledgers=" (count pending))
          (when (and deploy? (empty? pending))
            (println "public catalogue drift detected; redeploying"
                     "expected=" expected "live=" (or live "unavailable")))
          (publish-then-deploy!
           #(timed! (str "publish-kotobase (" (count pending) " ledgers)")
                    (fn [] (publish-kotobase! env pending)))
           #(timed! "deploy-public (test + build + wrangler)"
                    (fn [] (deploy-public! env)))
           deploy?)
          ;; content-addressed projections of the same ledger (IPFS index
          ;; blocks + R2 Iceberg). Best-effort: recorded, not thrown.
          (post-deploy-sinks!)
          (println "hyakka resident tick complete; receipts=" (count receipts)
                   "projected=" (count pending)
                   "deployed=" deploy?
                   "lost-archives=" (count @lost-archives)
                   "kotobase-skipped=" (or (:skipped @kotobase-skip) 0)
                   "sink-failures=" (count @ipfs-post-failures))
          ;; post-deploy sinks are content-addressed projections: a failure is
          ;; reported loudly but does not fail the tick, because neither sink
          ;; can invalidate what wiki.kotobase.net already serves. One working
          ;; sink out of two is still progress; both broken is an operator
          ;; page, not a silent green.
          (when (and (pos? (count @ipfs-post-failures))
                     (= 2 (count @ipfs-post-failures)))
            (binding [*out* *err*]
              (println "FAILED both post-deploy sinks:"
                       (mapv :step @ipfs-post-failures))
              (set! (.-exitCode js/process) 1)))
          ;; A tick that finished its work but could not back some bytes is
          ;; neither a failure nor a clean run, and printing the count is not
          ;; enough on its own: launchd records the exit status, and a 0 here
          ;; would file "one archive is gone forever" next to "nothing to do".
          (when-let [e @kotobase-failure]
            (binding [*out* *err*]
              (println "FAILED Kotobase publish:" (or (.-message e) (str e)))
              (println "  " (count pending) "ledger(s) stay pending and the next tick"
                       "retries them. The public catalogue above is unaffected —"
                       "it is a different sink.")))
          (when-let [k @kotobase-skip]
            (binding [*out* *err*]
              (println "SKIPPED Kotobase publish:" (:why k)
                       "—" (:skipped k) "ledger(s) still unpublished to the datom plane."
                       "The public catalogue above is unaffected.")))
          (when (seq @lost-archives)
            (binding [*out* *err*]
              (println "LOST" (count @lost-archives)
                       "raw archive(s) exist in neither the local store nor B2;"
                       "the receipts naming them cannot be backed and will not"
                       "be retried into a stall:"))
            (doseq [{:keys [sha256 receipt bytes]} @lost-archives]
              (binding [*out* *err*]
                (println " " sha256 (str "(" bytes " bytes)") receipt)))
            (set! (.-exitCode js/process) 1))
          (when (or @kotobase-skip @kotobase-failure)
            (set! (.-exitCode js/process) 1)))))
    (finally
      ;; Before the lock, and outside the success path: a tick that died in
      ;; step 4 of 7 is exactly the tick whose step timings someone needs.
      (print-step-timings!)
      (release-lock!))))

(defn lock-selftest!
  "Exercise the real acquire/release pair and nothing else, so the reclaim
  rule can be shown to refuse AND to reclaim. Without this the only way to
  test the lock is to run a full tick, which uploads and deploys."
  []
  (acquire-lock!)
  (println "acquired" (pr-str (lock-owner)))
  (release-lock!)
  (println "released"))

(if (= "1" (aget js/process.env "HYAKKA_DEPLOY_ISOLATION_SELFTEST"))
  (try (deploy-isolation-selftest!)
       (catch :default e
         (binding [*out* *err*] (println "FAIL" (or (.-message e) (str e))))
         (set! (.-exitCode js/process) 1)))
(if (= "1" (aget js/process.env "HYAKKA_LOCK_SELFTEST"))
  (try (lock-selftest!)
       (catch :default e
         (binding [*out* *err*] (println "FAIL" (or (.-message e) (str e))))
         (set! (.-exitCode js/process) 1)))
(if (= "1" (aget js/process.env "HYAKKA_VALIDATE_ONLY"))
  (validate-policy!)
  (try
    (main)
    (catch :default e
      (binding [*out* *err*] (println "FAIL" (or (.-message e) (str e))))
      (set! (.-exitCode js/process) 1))))))
