#!/usr/bin/env nbb
;; Resident operator for network-awai/app-hyakka's multi-domain ingest.
;; Credentials remain on the operator host; fleet nodes never receive B2 or
;; Kotobase signing keys. Raw bytes are uploaded before their Git receipt is
;; committed, then the rebuildable EDN projection is published to Kotobase.

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
    (when (pos? exit) (fail! (str (str/join " " args) ": " (str/trim err)))) out))

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
        r (run [kagi "get" "hyakka-kotobase-seed" "-c" "personal"] {:dir worktree :env e})]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn publish-git! [env]
  (checked ["git" "add" "knowledge/ledger" "knowledge/receipts"] {:dir worktree :env env})
  (let [st (checked ["git" "status" "--porcelain" "--" "knowledge"] {:dir worktree :env env})]
    (when-not (str/blank? st)
      (checked ["git" "commit" "-m" "ingest: resident knowledge tick"] {:dir worktree :env env})
      (checked ["git" "push" "origin" "HEAD:resident/knowledge-ingest"] {:dir worktree :env env})
      (let [r (run ["gh" "api" "repos/network-awai/app-hyakka/merges" "-f" "base=main"
                    "-f" "head=resident/knowledge-ingest"
                    "-f" "commit_message=ingest: merge resident knowledge tick"]
                   {:dir worktree :env env})]
        (when (and (pos? (:exit r)) (not (str/includes? (:err r) "No commits between")))
          (fail! (str "server-side merge failed: " (str/trim (:err r)))))))))

(defn main []
  (acquire-lock!)
  (try
    (when-not (.existsSync fs (.join path worktree ".git"))
      (fail! (str "resident worktree missing: " worktree)))
    (let [env (resolve-b2-env)]
      (checked ["git" "fetch" "origin" "main"] {:dir worktree :env env})
      (checked ["git" "merge" "--ff-only" "origin/main"] {:dir worktree :env env})
      (checked ["nbb" "--classpath" "src" "scripts/resident_ingest.cljs" "--once"]
               {:dir worktree :env env})
      (let [receipts (changed-receipts)]
        (doseq [p receipts] (upload-raw! env p))
        (publish-git! env)
        (if-let [seed (kotobase-seed env)]
          (let [e (js/Object.assign #js {} env)]
            (aset e "HYAKKA_SEED" seed)
            (doseq [ledger (unpublished-ledgers)]
              (let [r (run ["npm" "run" "publish" "--" "--path" ledger "--batch" "100"]
                           {:dir worktree :env e})]
                (when (pos? (:exit r))
                  (println "WARN Kotobase projection failed for" ledger (str/trim (:err r)))))))
          (println "WARN hyakka-kotobase-seed unavailable; Git/B2 source is durable, projection deferred"))
      (println "hyakka resident tick complete; receipts=" (count receipts))))
    (finally (release-lock!))))

(try
  (main)
  (catch :default e
    (binding [*out* *err*] (println "FAIL" (or (.-message e) (str e))))
    (set! (.-exitCode js/process) 1)))
