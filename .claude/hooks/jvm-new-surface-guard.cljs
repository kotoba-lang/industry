#!/usr/bin/env nbb
;; PreToolUse guard: refuse NEW production `.clj` and NEW top-level third-party
;; JVM runtime deps (maven / external-git) on Write / Edit / StrReplace / commit.
;;
;; Standing debt reported by `verify-jvm-dependency-surface` stays untouched —
;; only growth is denied (ADR-2608201300). Fail-open on any unexpected error.
;;
;; Override (owner / explicit): JVM_NEW_SURFACE_ALLOW=1
(ns jvm-new-surface-guard
  (:require [cheshire.core :as json]
            [babashka.process :as p]
            [clojure.string :as str]
            [jvm-new-surface-policy :as pol]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            [scripts.nbb-compat :as compat]))

(defn allow! [] (compat/exit 0))

(defn deny! [reason]
  (println (json/generate-string
             {:hookSpecificOutput
              {:hookEventName "PreToolUse"
               :permissionDecision "deny"
               :permissionDecisionReason reason}}))
  (compat/exit 0))

(defn- sh
  [dir & args]
  (try
    (let [{:keys [exit out]} (p/sh (into ["git" "-C" dir] (map str args)))]
      (when (zero? exit) out))
    (catch :default _ nil)))

(defn- file-exists? [p]
  (try (.existsSync fs p) (catch :default _ false)))

(defn- read-utf8 [p]
  (try (.readFileSync fs p "utf8") (catch :default _ nil)))

(defn- relpath [p]
  (-> (str p) (str/replace #"\\" "/") (str/replace #"^\./" "")))

(defn- deps-edn-path? [p]
  (or (= "deps.edn" (node-path/basename (str p)))
      (str/ends-with? (str/replace (str p) #"\\" "/") "/deps.edn")))

(defn- check-deps-change! [before after]
  (let [{:keys [added error]} (pol/new-runtime-third-party before after)]
    (cond
      error (allow!) ;; fail-open: cannot parse
      (seq added) (deny! (pol/deny-reason-deps added))
      :else nil)))

(defn- check-new-clj! [path]
  (when (pol/deny-new-production-clj? path true)
    (deny! (pol/deny-reason-clj path))))

(defn- handle-write! [ti]
  (let [path (relpath (or (:file_path ti) (:path ti) ""))
        contents (or (:contents ti) (:content ti))
        abs (if (node-path/isAbsolute path) path path)]
    (when-not (str/blank? path)
      (cond
        (deps-edn-path? path)
        (check-deps-change! (when (file-exists? abs) (read-utf8 abs)) contents)

        (and (pol/production-clj-path? path) (not (file-exists? abs)))
        (check-new-clj! path)))))

(defn- handle-edit! [ti]
  (let [path (relpath (or (:file_path ti) (:path ti) ""))
        abs (if (node-path/isAbsolute path) path path)]
    (when (and (not (str/blank? path)) (deps-edn-path? path) (file-exists? abs))
      (let [before (read-utf8 abs)
            after (cond
                    (string? (:contents ti)) (:contents ti)
                    (and (string? (:old_string ti)) (string? (:new_string ti)))
                    (str/replace before (:old_string ti) (:new_string ti))
                    :else nil)]
        (when after
          (check-deps-change! before after))))))

(defn- lines [s]
  (->> (str/split-lines (or s ""))
       (map str/trim)
       (remove str/blank?)))

(defn- nested-repo?
  "Is `path` inside a git repository of its own, below `top`?

  The west superproject checks out ~4,000 child repositories under `orgs/`,
  and they are not ignored. A file in one of them is never part of a commit
  to `top`, so measuring it here answers about a tree nobody is committing."
  [top path]
  (loop [d (node-path/dirname path)]
    (cond
      (contains? #{"" "." "/"} d) false
      (file-exists? (node-path/join top d ".git")) true
      :else (recur (node-path/dirname d)))))

(defn- commit-paths
  "What this commit will actually contain.

  Staged paths, plus tracked-but-unstaged ones only when `-a` was passed.
  Untracked files are excluded: `git commit` does not add them, and the
  previous `ls-files -o` swept in every child repository under `orgs/`,
  each of whose `deps.edn` then had no HEAD version to compare against — so
  every coordinate in it read as newly added. That is how this guard came to
  refuse a commit touching three prose files (measured 2026-08-30)."
  [top cmd]
  (->> (cond-> [(sh top "diff" "--cached" "--name-only")]
         (pol/commit-includes-unstaged? cmd) (conj (sh top "diff" "--name-only")))
       (mapcat lines)
       distinct))

(defn- push-paths
  "What this push carries that the upstream has not seen. Empty (and so a
  no-op) when there is no upstream to compare against — a push whose commits
  were each checked at commit time."
  [top]
  (lines (sh top "diff" "--name-only" "@{upstream}..HEAD")))

(defn- head-text [top path]
  (sh top "show" (str "HEAD:" path)))

(defn- handle-git! [cmd push?]
  (let [dir (pol/target-dir cmd)]
    ;; nil = the command changes directory somewhere this cannot resolve
    ;; ($VAR, glob, ~). Reading that as "." would measure the superproject
    ;; instead, which is wrong in both directions: a clean tree there hides a
    ;; real new dependency in the repo being committed to, and a busy one
    ;; denies a commit that adds nothing. Say so instead of guessing.
    (when (nil? dir)
      (deny! (str "jvm-new-surface-guard cannot tell which repository this "
                  "targets: the `cd` destination is not a literal path "
                  "(a variable, glob or ~). Re-run with `git -C <literal path> "
                  "…`, or a literal `cd`, so the guard measures the tree you "
                  "are committing to. Set JVM_NEW_SURFACE_ALLOW=1 to skip.")))
    (let [top (some-> (sh dir "rev-parse" "--show-toplevel") str/trim)]
      (when (str/blank? top) (allow!))
      (doseq [path (if push? (push-paths top) (commit-paths top cmd))
              :when (not (nested-repo? top path))]
        (let [abs (node-path/join top path)
              on-disk? (file-exists? abs)
              at-head? (boolean (head-text top path))]
          (cond
            (and (pol/production-clj-path? path) on-disk? (not at-head?))
            (deny! (pol/deny-reason-clj path))

            (and (deps-edn-path? path) on-disk?)
            (check-deps-change! (head-text top path) (read-utf8 abs))))))))

(try
  (when (= "1" (or (aget js/process.env "JVM_NEW_SURFACE_ALLOW") ""))
    (allow!))
  (let [raw (compat/read-stdin)
        input (when-not (str/blank? raw)
                (json/parse-string raw true))
        tool (or (:tool_name input) "")
        ti (or (:tool_input input) {})]
    (cond
      (re-find #"^(Write)$" tool) (handle-write! ti)
      (re-find #"^(Edit|StrReplace)$" tool) (handle-edit! ti)
      (= "Bash" tool)
      (let [cmd (or (:command ti) "")]
        (when-let [verb (second (re-find #"\bgit\b(?:\s+-C\s+\S+)?\s+(commit|push)\b" cmd))]
          (handle-git! cmd (= "push" verb))))
      :else nil)
    (allow!))
  (catch :default _
    (allow!)))
