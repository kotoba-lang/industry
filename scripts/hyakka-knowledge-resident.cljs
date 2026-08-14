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

(defn acquire-lock! []
  (.mkdirSync fs (.dirname path lock-dir) #js {:recursive true})
  (try (.mkdirSync fs lock-dir)
       (catch :default _ (fail! (str "another tick holds " lock-dir)))))

(defn release-lock! [] (try (.rmdirSync fs lock-dir) (catch :default _ nil)))

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

(defn upload-raw! [env receipt-path]
  (let [receipt (edn/read-string (.readFileSync fs (.join path worktree receipt-path) "utf8"))
        bucket (aget env "B2_BUCKET")]
    (doseq [{:keys [archive-relative-path object-key bytes sha256]} (:run/raw receipt)
            :let [archive-path (.join path archive-dir archive-relative-path)]]
      (when-not (.existsSync fs archive-path) (fail! (str "raw archive missing " sha256)))
      (let [dst (str "s3://" bucket "/" object-key)
            head (run ["aws" "s3api" "head-object" "--bucket" bucket "--key" object-key
                       "--endpoint-url" b2-endpoint] {:env env})]
        (when (pos? (:exit head))
          (checked ["aws" "s3" "cp" archive-path dst "--endpoint-url" b2-endpoint
                    "--only-show-errors"] {:env env}))
        (let [verified (checked ["aws" "s3api" "head-object" "--bucket" bucket "--key" object-key
                                "--endpoint-url" b2-endpoint "--query" "ContentLength"
                                "--output" "text"] {:env env})]
          (when-not (= (str bytes) (str/trim verified))
            (fail! (str "B2 byte length mismatch for " sha256))))))))

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

(defn publish-kotobase! [env ledgers]
  (when (seq ledgers)
    (let [e (js/Object.assign #js {} env)
          seed (kotobase-seed env)]
      (aset e "HYAKKA_SEED" seed)
      (println "Kotobase publish pending ledgers in one resumable process")
      (checked ["npm" "run" "publish" "--" "--knowledge" "true"
                "--batch" "100"]
               {:dir worktree :env e}))))

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
                (let [xs (changed-receipts)]
                  (doseq [p xs] (upload-raw! env p))
                  (publish-git! env)
                  xs)))]
        ;; A server-side merge advances main beyond the resident branch. Pull
        ;; that exact merge tree before projection and deploy; last-writer-wins
        ;; deploys from a stale checkout previously reverted this zone.
        (sync-main! env)
        (let [pending (unpublished-ledgers)]
          (println "Kotobase pending ledgers=" (count pending))
          (publish-kotobase! env pending)
          (when (seq pending) (deploy-public! env))
          (println "hyakka resident tick complete; receipts=" (count receipts)
                   "projected=" (count pending)
                   "deployed=" (boolean (seq pending))))))
    (finally (release-lock!))))

(if (= "1" (aget js/process.env "HYAKKA_VALIDATE_ONLY"))
  (println "hyakka resident operator loaded")
  (try
    (main)
    (catch :default e
      (binding [*out* *err*] (println "FAIL" (or (.-message e) (str e))))
      (set! (.-exitCode js/process) 1))))
