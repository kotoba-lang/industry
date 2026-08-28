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
  (checked ["git" "merge" "--ff-only" "origin/main"] {:dir worktree :env env}))

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

(defn publish-kotobase!
  "Publish pending ledgers to the Kotobase datom plane. Returns why it could not.

  The Kotobase ref and the public catalogue are DIFFERENT SINKS with different
  audiences: the ref is where cross-corpus Datalog joins live, the catalogue is
  what wiki.kotobase.net serves. This used to throw when the seed was missing,
  and the throw took the whole tick with it — so an absent credential for one
  sink silently stopped the other one from ever deploying.

  `hyakka-kotobase-seed` has been absent from kagi since 2026-08-15 and is
  recorded as owner action in ADR-2608271450 and the secrets map. What was NOT
  recorded is that it also held the public deploy hostage. It no longer does.
  The ledgers stay pending — they already were — and the reason is returned."
  [env ledgers]
  (when (seq ledgers)
    (let [e (js/Object.assign #js {} env)
          seed (try {:ok (kotobase-seed env)}
                    (catch :default ex {:err (or (.-message ex) (str ex))}))]
      (if-let [why (:err seed)]
        (do (binding [*out* *err*]
              (println "SKIP Kotobase publish —" (count ledgers)
                       "ledger(s) stay pending:" (str/trim (str why))))
            {:skipped (count ledgers) :why (str/trim (str why))})
        (do (aset e "HYAKKA_SEED" (:ok seed))
            (println "Kotobase publish pending ledgers in one resumable process")
            (checked ["npm" "run" "publish" "--" "--knowledge" "true"
                      "--batch" "100"]
                     {:dir worktree :env e})
            nil)))))

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

(defn main []
  (acquire-lock!)
  (try
    (when-not (.existsSync fs (.join path worktree ".git"))
      (fail! (str "resident worktree missing: " worktree)))
    (let [catch-up-only? (= "1" (aget js/process.env "HYAKKA_CATCH_UP_ONLY"))
          env (if catch-up-only? (process-env) (resolve-b2-env))]
      (sync-main! env)
      (let [receipts
            (if catch-up-only?
              []
              (do
                (checked ["nbb" "--classpath" "src" "scripts/resident_ingest.cljs" "--once"]
                         {:dir worktree :env env})
                (let [xs (changed-receipts)
                      lost (vec (mapcat #(upload-raw! env %) xs))]
                  (reset! lost-archives lost)
                  (publish-git! env)
                  xs)))]
        ;; A server-side merge advances main beyond the resident branch. Pull
        ;; that exact merge tree before projection and deploy; last-writer-wins
        ;; deploys from a stale checkout previously reverted this zone.
        (sync-main! env)
        (let [pending (unpublished-ledgers)
              expected (catalogue-id)
              live (live-catalogue-id)
              deploy? (boolean (deploy-required? pending expected live))]
          (println "Kotobase pending ledgers=" (count pending))
          (reset! kotobase-skip (publish-kotobase! env pending))
          (when (and deploy? (empty? pending))
            (println "public catalogue drift detected; redeploying"
                     "expected=" expected "live=" (or live "unavailable")))
          (when deploy? (deploy-public! env))
          (println "hyakka resident tick complete; receipts=" (count receipts)
                   "projected=" (count pending)
                   "deployed=" deploy?
                   "lost-archives=" (count @lost-archives)
                   "kotobase-skipped=" (or (:skipped @kotobase-skip) 0))
          ;; A tick that finished its work but could not back some bytes is
          ;; neither a failure nor a clean run, and printing the count is not
          ;; enough on its own: launchd records the exit status, and a 0 here
          ;; would file "one archive is gone forever" next to "nothing to do".
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
          (when @kotobase-skip (set! (.-exitCode js/process) 1)))))
    (finally (release-lock!))))

(defn lock-selftest!
  "Exercise the real acquire/release pair and nothing else, so the reclaim
  rule can be shown to refuse AND to reclaim. Without this the only way to
  test the lock is to run a full tick, which uploads and deploys."
  []
  (acquire-lock!)
  (println "acquired" (pr-str (lock-owner)))
  (release-lock!)
  (println "released"))

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
      (set! (.-exitCode js/process) 1)))))
