#!/usr/bin/env nbb
;; Cross-repository extraction guard (ADR-2607266000 G-M/G-N/G-P).
;;
;; start  — must run from an up-to-date linked worktree outside the primary
;;          checkout; snapshots exact upstream heads for every touched repo.
;; verify — fails if root or any child upstream moved after the snapshot.
;;
;; This turns "remember to use a pristine checkout and hope upstream stayed
;; still" into an executable precondition.  The state file belongs in a temp
;; directory or another untracked path.

(require '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(defn run [dir & args]
  (let [{:keys [exit out err]} (p/sh (into ["git" "-C" dir] (map str args)))]
    {:exit exit :out (str/trim (or out "")) :err (str/trim (or err ""))}))

(defn git! [dir & args]
  (let [{:keys [exit out err]} (apply run dir args)]
    (when-not (zero? exit)
      (println (str "ERROR git failed in " dir ": " err))
      (compat/exit 1))
    out))

(defn fail! [code data]
  (binding [*print-namespace-maps* false]
    (println (str "ERROR " (name code) " " (pr-str data))))
  (compat/exit 1))

(defn parse-args [args]
  (loop [xs args result {:repos []}]
    (if-let [arg (first xs)]
      (case arg
        "--state" (recur (nnext xs) (assoc result :state (second xs)))
        "--repo" (recur (nnext xs) (update result :repos conj (second xs)))
        (fail! :unknown-argument {:argument arg}))
      result)))

(defn clean-tracked? [dir]
  (str/blank? (git! dir "status" "--porcelain" "--untracked-files=no")))

(defn linked-worktree? [root]
  (let [git-dir (.resolve path root (git! root "rev-parse" "--git-dir"))
        common-dir (.resolve path root (git! root "rev-parse" "--git-common-dir"))]
    (not= (.normalize path git-dir) (.normalize path common-dir))))

(defn upstream-head! [dir]
  (git! dir "fetch" "-q" "origin" "main")
  (git! dir "rev-parse" "origin/main"))

(defn current-head [dir] (git! dir "rev-parse" "HEAD"))

(defn snapshot! [root state repos]
  (when-not (linked-worktree? root)
    (fail! :primary-checkout
           {:root root :remedy "create a sibling git worktree from origin/main"}))
  (when-not (clean-tracked? root)
    (fail! :dirty-root {:root root}))
  (let [root-upstream (upstream-head! root)
        root-head (current-head root)]
    (when-not (= root-upstream root-head)
      (fail! :root-not-pristine
             {:head root-head :origin-main root-upstream}))
    (let [children
          (into (sorted-map)
                (map (fn [repo]
                       (let [dir (.resolve path root repo)]
                         (when-not (.existsSync fs dir)
                           (fail! :missing-repo {:repo repo :path dir}))
                         (when-not (clean-tracked? dir)
                           (fail! :dirty-child {:repo repo}))
                         (let [upstream (upstream-head! dir)
                               head (current-head dir)]
                           (when-not (= upstream head)
                             (fail! :child-not-pristine
                                    {:repo repo :head head :origin-main upstream}))
                           [repo upstream]))))
                repos)
          value {:schema :kotoba.cross-repo-snapshot/v1
                 :root root-upstream :repos children}]
      (.writeFileSync fs state (str (pr-str value) "\n"))
      (println (str "snapshot OK root=" (subs root-upstream 0 12)
                    " repos=" (count children) " state=" state)))))

(defn verify! [root-dir state]
  (when-not (.existsSync fs state)
    (fail! :missing-state {:state state}))
  (let [{:keys [schema repos] snapshot-root :root}
        (edn/read-string (compat/slurp state))]
    (when-not (= :kotoba.cross-repo-snapshot/v1 schema)
      (fail! :invalid-state {:schema schema}))
    (let [current-root (upstream-head! root-dir)]
      (when-not (= snapshot-root current-root)
        (fail! :root-upstream-moved
               {:snapshot snapshot-root :current current-root})))
    (doseq [[repo expected] repos
            :let [dir (.resolve path root-dir repo)
                  current (upstream-head! dir)]]
      (when-not (= expected current)
        (fail! :child-upstream-moved
               {:repo repo :snapshot expected :current current})))
    (println (str "verify OK root and " (count repos) " child upstream heads unchanged"))))

(let [[command & args] *command-line-args*
      {:keys [state repos]} (parse-args args)
      root (git! "." "rev-parse" "--show-toplevel")]
  (when-not state (fail! :missing-state-argument {}))
  (case command
    "start" (snapshot! root state repos)
    "verify" (verify! root state)
    (fail! :unknown-command {:command command})))
