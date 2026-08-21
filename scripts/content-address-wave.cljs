#!/usr/bin/env nbb
;; Give many appviews a content address, one repository at a time.
;;
;;   nbb --classpath ".:orgs/kotoba-lang/content-address/src" \
;;       scripts/content-address-wave.cljs <list-file> [--limit N] [--execute]
;;
;; The list file holds one path to a document per line
;; (`orgs/<org>/<repo>/docs/index.html`). Without `--execute` nothing is sent
;; and nothing is written — it reports what it would do.
;;
;; ADR-2608157000. The addressing itself lives in kotoba-lang/content-address;
;; this script is only the loop around it, plus the landing (branch from
;; origin/main → commit → push → server-side merge → delete branch).
;;
;; Idempotent on purpose: a repository whose manifest already records the CID
;; of the current bytes is skipped, so a re-run after an interruption costs a
;; hash and nothing else. Pins are NOT advanced here — that goes through
;; `scripts/west-pin-put.cljs`, which reads and writes the tip under an
;; optimistic lock. Editing west.yml locally for a batch this size is how a
;; parallel session's pins get silently rolled back.
;;
;; Exit codes: 0 every target landed or was already done, 1 some target
;; failed, 2 the question could not be asked (no list, no token, no library).

(ns content-address-wave
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [content-address.archive :as archive]
            [content-address.core :as ca]
            [content-address.digest :as digest]))

(defn- sh [cmd]
  (try {:out (str/trim (.toString (cp/execSync cmd #js {:encoding "utf8"
                                                        :stdio #js ["pipe" "pipe" "pipe"]})))}
       (catch :default e
         {:error (or (some-> (.-stderr e) str str/trim not-empty)
                     (str e))})))

(defn- script-args []
  (if (seq *command-line-args*)
    (vec *command-line-args*)
    (let [argv (vec (js->clj (.-argv js/process)))
          i (first (keep-indexed
                    (fn [i v] (when (str/ends-with? v "content-address-wave.cljs") i)) argv))]
      (if (some? i) (subvec argv (inc i)) []))))

(defn- flag [argv name] (second (drop-while #(not= name %) argv)))

(defn- read-edn [p] (try (edn/read-string (.readFileSync fs p "utf8")) (catch :default _ nil)))

;; --------------------------------------------------------------- targets

(defn- registered?
  "Is this repository a west project? An unregistered path is usually a stray
  worktree (`…-wt-flagship-item2`), and publishing from one would address
  bytes no checkout of the real repository has."
  [west repo]
  (str/includes? west (str "name: " repo "\n")))

(defn- target [west line]
  (let [[_ org repo] (re-matches #"orgs/([^/]+)/([^/]+)/.*" line)]
    {:document line
     :org org
     :repo repo
     :root (str "orgs/" org "/" repo)
     :manifest (str "orgs/" org "/" repo "/kotoba.app.edn")
     :app-id (str (str/replace org #"-" ".") "."
                  (str/replace repo #"^cloud-itonami-" ""))
     :registered? (registered? west repo)}))

;; ------------------------------------------------------------- one repo

(defn- remote-name
  "West checkouts do not all call it `origin` — 2 of the 443 documents in the
  first sweep sat in repositories whose only remote is the org name. Assuming
  `origin` there produced a commit on a detached HEAD, a push that failed,
  and a working tree that looked finished."
  [root]
  (let [remotes (->> (str/split-lines (or (:out (sh (str "git -C " root " remote"))) ""))
                     (remove str/blank?))]
    (or (first (filter #{"origin"} remotes)) (first remotes))))

(defn- landed?
  "Is this manifest on the remote's default branch, naming these bytes?

  Asking the working tree instead is how an unlanded repository reports as
  done: the first sweep wrote the manifest, committed it to a detached HEAD,
  failed to push, and left a clean tree holding the right CID. A re-run would
  have called that `already`."
  [{:keys [root manifest]} cid remote]
  (let [shown (sh (str "git -C " root " show " remote "/HEAD:"
                       (str/replace manifest (str root "/") "")))]
    (boolean
     (when-not (:error shown)
       (= cid (:bundle-cid (ca/file-address-of
                            (try (edn/read-string (:out shown))
                                 (catch :default _ nil)))))))))

(defn- prepare!
  "Put the checkout on a fresh branch off the remote's default, BEFORE the
  manifest is written.

  Order matters and it took two repositories to see why. Writing first and
  branching second means `checkout -B` has to reconcile a modified file
  against a target that does not have it, and git correctly refuses —
  leaving a commit on a detached HEAD, a push with nothing to push, and a
  clean-looking tree. Branch first and there is nothing to reconcile."
  [{:keys [root]} remote]
  (let [steps [(str "git -C " root " fetch -q " remote)
               (str "git -C " root " checkout -q -B agent/content-address "
                    remote "/HEAD")]]
    (reduce (fn [_ cmd]
              (let [r (sh cmd)]
                (if (:error r) (reduced {:error (:error r) :cmd cmd}) nil)))
            nil steps)))

(defn- finish!
  "Commit the written manifest, push, merge server-side, put the checkout
  back on the default branch, and remove the branch."
  [{:keys [root org repo manifest]} remote]
  (let [branch "agent/content-address"
        msg (str "content-address: identify this appview by its bytes\n\n"
                 "The document at docs/index.html now has an identity that is not a\n"
                 "path: a raw CIDv1 of its own bytes, archived at kotobase.net and\n"
                 "GET-verified byte-for-byte after the PUT.\n\n"
                 ":kotoba.app/bundle-cid  identity   (bafkrei...)\n"
                 ":kotoba.app/embed-url   link       (ipfs://...)\n"
                 ":published              location   (the host that answered; NOT identity)\n\n"
                 "ADR-2608157000. Written by kotoba-lang/content-address.")
        add (sh (str "git -C " root " add "
                     (str/replace manifest (str root "/") "")))
        commit (if (:error add)
                 {:error (str "git add: " (:error add))}
                 (try {:out (.toString (cp/execSync
                                        (str "git -C " root
                                             " -c user.name='Jun Kawasaki'"
                                             " -c user.email='root@junkawasaki.com'"
                                             " commit -q -F -")
                                        #js {:encoding "utf8" :input msg
                                             :stdio #js ["pipe" "pipe" "pipe"]}))}
                      (catch :default e {:error (str (or (some-> (.-stderr e) str) e))})))]
    (if (:error commit)
      {:landed? false :stage :commit :error (:error commit)}
      (let [push (sh (str "git -C " root " push -q " remote " " branch))
            merged (when-not (:error push)
                     (sh (str "gh api repos/" org "/" repo "/merges"
                              " -f base=main -f head=" branch
                              " -f commit_message='Merge agent/content-address:"
                              " identify this appview by its bytes (ADR-2608157000)'"
                              " --jq .sha")))]
        (if (or (:error push) (:error merged))
          {:landed? false :stage (if (:error push) :push :merge)
           :error (or (:error push) (:error merged))}
          (do (sh (str "git -C " root " fetch -q " remote))
              (sh (str "git -C " root " checkout -q --detach " remote "/HEAD"))
              (sh (str "git -C " root " branch -D " branch))
              (sh (str "git -C " root " push -q " remote " --delete " branch))
              {:landed? true :sha (:out merged)}))))))

(defn- process! [{:keys [document root manifest app-id registered?] :as t} token execute?]
  (cond
    (not registered?)
    {:state :skip-unregistered}

    (not (.existsSync fs document))
    {:state :skip-no-document}

    (seq (:out (sh (str "git -C " root " status --porcelain"))))
    {:state :skip-dirty}

    :else
    (let [{:keys [cid size octets]} (archive/address (digest/->octets (.readFileSync fs document)))
          refusals (archive/refusals {:cid cid :size size :token (or token "dry")})
          remote (remote-name root)]
      (cond
        (nil? remote) {:state :skip-no-remote}
        (landed? t cid remote) {:state :already :cid cid}
        (seq refusals) {:state :refused :cid cid :refusals refusals}
        (not execute?) {:state :would-publish :cid cid :size size}
        :else
        (-> (archive/put! {:cid cid :octets octets :token token
                           :content-type "text/html; charset=utf-8"})
            (.then (fn [put]
                     (if-not (contains? #{200 201} (:status put))
                       {:state :put-failed :cid cid :status (:status put) :body (:body put)}
                       (.then (archive/verify {:cid cid})
                              (fn [v]
                                (if-not (:verified? v)
                                  {:state :not-verified :cid cid :derived (:derived v)}
                                  (let [record (ca/record-address
                                                (or (read-edn manifest)
                                                    {:kotoba.app/id app-id
                                                     :kotoba.app/kind "appview"})
                                                {:bundle-cid cid :size size
                                                 :put-status (:status put)
                                                 :get-status (:status v)
                                                 :at (.toISOString (js/Date.))})]
                                    (if-let [bad (prepare! t remote)]
                                      {:state :prepare-failed :cid cid
                                       :error (str (:cmd bad) " — " (:error bad))}
                                      (do (.writeFileSync fs manifest
                                                          (str (pr-str record) "\n"))
                                          (merge {:state :published :cid cid :size size}
                                                 (finish! t remote))))))))))))))))

;; ------------------------------------------------------------------ main

(defn- run! [targets token execute? tally]
  (if (empty? targets)
    (js/Promise.resolve tally)
    (let [t (first targets)]
      (-> (js/Promise.resolve (process! t token execute?))
          (.then (fn [r]
                   (let [state (if (and (= :published (:state r)) (not (:landed? r)))
                                 :land-failed
                                 (:state r))]
                     (println (str (name state) "\t" (:repo t) "\t"
                                   (or (:cid r) "")
                                   (when (:error r) (str "\t" (subs (str (:error r)) 0
                                                                    (min 160 (count (str (:error r)))))))
                                   (when (:refusals r) (str "\t" (pr-str (:refusals r))))))
                     (run! (rest targets) token execute?
                           (update tally state (fnil inc 0))))))))))

(defn- report! [targets tally]
  (println (str "TARGETS\t" (count targets)))
  (doseq [[k v] (sort-by (comp - val) tally)]
    (println (str (str/upper-case (name k)) "\t" v)))
  (when (seq (select-keys tally [:put-failed :not-verified :land-failed :refused]))
    (set! (.-exitCode js/process) 1)))

(defn- go! [targets token execute?]
  (-> (run! targets token execute? {})
      (.then (fn [tally] (report! targets tally)))
      (.catch (fn [e]
                (println "UNANSWERED" (str e))
                (set! (.-exitCode js/process) 2)))))

(defn -main [& args]
  (let [argv (vec args)
        list-file (first (remove #(str/starts-with? % "--") argv))
        limit (some-> (flag argv "--limit") js/parseInt)
        execute? (some #{"--execute"} argv)
        token (aget (.-env js/process) "KOTOBASE_ARCHIVE_TOKEN")]
    (cond
      (or (nil? list-file) (not (.existsSync fs list-file)))
      (do (println "usage: content-address-wave.cljs <list-file> [--limit N] [--execute]")
          (set! (.-exitCode js/process) 2))

      (and execute? (str/blank? (str token)))
      (do (println "UNANSWERED — KOTOBASE_ARCHIVE_TOKEN is not set")
          (set! (.-exitCode js/process) 2))

      :else
      (let [west (.readFileSync fs "manifest/west.yml" "utf8")
            lines (->> (str/split-lines (.readFileSync fs list-file "utf8"))
                       (remove str/blank?))
            targets (cond->> (mapv #(target west %) lines)
                      limit (take limit)
                      true vec)]
        (if (zero? (count targets))
          (do (println "UNANSWERED — the list is empty")
              (set! (.-exitCode js/process) 2))
          (go! targets token execute?))))))

(apply -main (script-args))
