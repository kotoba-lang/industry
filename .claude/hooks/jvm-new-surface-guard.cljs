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

(defn- target-dir [cmd]
  (or (second (re-find #"\bgit\s+-C\s+(\S+)" cmd))
      (second (re-find #"^\s*cd\s+(\S+)\s*&&" cmd))
      "."))

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

(defn- changed-paths [top]
  (->> [(sh top "diff" "--name-only")
        (sh top "diff" "--cached" "--name-only")
        (sh top "ls-files" "-o" "--exclude-standard")]
       (keep identity)
       (mapcat str/split-lines)
       (map str/trim)
       (remove str/blank?)
       distinct))

(defn- head-text [top path]
  (sh top "show" (str "HEAD:" path)))

(defn- handle-commit! [cmd]
  (let [top (some-> (sh (target-dir cmd) "rev-parse" "--show-toplevel") str/trim)]
    (when (str/blank? top) (allow!))
    (doseq [path (changed-paths top)]
      (let [abs (node-path/join top path)
            on-disk? (file-exists? abs)
            at-head? (boolean (head-text top path))]
        (cond
          (and (pol/production-clj-path? path) on-disk? (not at-head?))
          (deny! (pol/deny-reason-clj path))

          (and (deps-edn-path? path) on-disk?)
          (check-deps-change! (head-text top path) (read-utf8 abs)))))))

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
        (when (re-find #"\bgit\b(?:\s+-C\s+\S+)?\s+(?:commit|push)\b" cmd)
          (handle-commit! cmd)))
      :else nil)
    (allow!))
  (catch :default _
    (allow!)))
