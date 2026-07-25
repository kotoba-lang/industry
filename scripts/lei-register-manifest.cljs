#!/usr/bin/env nbb
;; lei-register-manifest.cljs — register any cloud-itonami-lei-* repo that
;; exists on GitHub but is missing from manifest/west.yml (and repos.edn).
;;
;; Exists because acquisition and registration are separate steps, and a loop
;; that runs acquisition on a schedule quietly accumulates the gap between
;; them: `loop-lei-catalog`'s first real cycle created a repo and left it
;; unregistered, which is invisible until someone happens to compare the two
;; lists. Reconciling from OBSERVED state -- what is on GitHub versus what is
;; in the manifest -- rather than from what a previous run believed it created
;; means the drift is repaired even if the run that caused it crashed, was
;; interrupted, or predates this script.
;;
;; Writes through the GitHub Contents API with the blob SHA, so a concurrent
;; edit to west.yml gets a 409 instead of being clobbered -- the optimistic
;; lock CLAUDE.md requires for this file. Pins are each repo's own main HEAD
;; resolved server-side, which satisfies the pin rules (exists upstream,
;; reachable from the default branch) by construction rather than by trusting
;; a local checkout that may be stale.
;;
;; Run (from the superproject root):
;;   nbb scripts/lei-register-manifest.cljs [--dry-run]

(ns lei-register-manifest
  (:require ["child_process" :refer [execSync]]
            [clojure.string :as str]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(def dry-run? (boolean (some #{"--dry-run"} argv)))
(def prefix "cloud-itonami-lei-")

(defn- sh [cmd]
  (execSync cmd #js {:encoding "utf8" :maxBuffer (* 40 1024 1024) :stdio "pipe"}))

(defn- gh-json [cmd] (js->clj (js/JSON.parse (sh (str "gh api " cmd))) :keywordize-keys true))

(defn- b64->str [s] (.toString (js/Buffer.from s "base64") "utf8"))
(defn- str->b64 [s] (.toString (js/Buffer.from s "utf8") "base64"))

(defn on-github []
  (->> (str/split-lines
        (sh "gh api \"orgs/cloud-itonami/repos?per_page=100\" --paginate --jq '.[].name'"))
       (filter #(str/starts-with? % prefix))
       sort vec))

(defn- west []
  (let [listing (gh-json "repos/com-junkawasaki/root/contents/manifest")
        blob (first (filter #(= "west.yml" (:name %)) listing))
        raw (sh "gh api repos/com-junkawasaki/root/contents/manifest/west.yml -H \"Accept: application/vnd.github.raw\"")]
    {:sha (:sha blob) :raw raw}))

(defn- entry-lines [name sha]
  [(str "    - name: " name)
   "      remote: cloud-itonami"
   (str "      revision: " sha)
   (str "      path: orgs/cloud-itonami/" name)
   "      groups: [cloud-itonami]"])

(defn- insert-sorted
  "Insert each entry among the existing cloud-itonami-lei-* blocks in name
  order. Alphabetical placement, not append-at-end, so the diff for a new
  registration is five contiguous lines a reviewer can read -- an append moves
  nothing but is impossible to locate later."
  [lines names->sha]
  (reduce (fn [ls name]
            (let [idxs (keep-indexed (fn [i l] (when (str/starts-with? l (str "    - name: " prefix)) i)) ls)
                  pos (or (first (filter #(pos? (compare (subs (nth ls %) (count "    - name: ")) name)) idxs))
                          (+ (last idxs) 5))]
              (vec (concat (subvec ls 0 pos) (entry-lines name (get names->sha name)) (subvec ls pos)))))
          (vec lines)
          (sort (keys names->sha))))

(defn -main []
  (let [gh (on-github)
        {:keys [sha raw]} (west)
        lines (str/split raw #"\n")
        registered (set (map #(subs % (count "    - name: "))
                             (filter #(str/starts-with? % (str "    - name: " prefix)) lines)))
        missing (vec (remove registered gh))]
    (println "on GitHub:" (count gh) " registered:" (count registered) " missing:" (count missing))
    (cond
      (empty? missing) (println "manifest is in sync — nothing to register")
      dry-run? (doseq [m missing] (println "WOULD REGISTER" m))
      :else
      (let [pins (into {} (map (fn [n]
                                 [n (:sha (gh-json (str "repos/cloud-itonami/" n "/commits/main --jq '{sha:.sha}'")))]))
                       missing)
            updated (str/join "\n" (insert-sorted lines pins))]
        ;; Verify before writing: a name that failed to insert exactly once
        ;; means the insertion point logic mis-fired, and a west.yml with a
        ;; duplicated or missing project is worse than one that is simply
        ;; behind.
        (doseq [n missing]
          (let [c (count (re-seq (re-pattern (str "(?m)^    - name: " n "$")) updated))]
            (when-not (= 1 c)
              (throw (js/Error. (str "refusing to write: " n " appears " c " times after insertion"))))))
        (let [body (js/JSON.stringify
                    (clj->js {:message (str "manifest: register " (count missing)
                                            " cloud-itonami-lei-* repo(s) missing from west.yml"
                                            "\n\nReconciled from observed state: present on GitHub, absent from the"
                                            "\nmanifest. Acquisition and registration are separate steps, so a"
                                            "\nscheduled acquisition loop accumulates this gap silently until someone"
                                            "\ncompares the two lists. Pins are each repo's main HEAD resolved"
                                            "\nserver-side."
                                            "\n\nCo-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>")
                              :content (str->b64 updated)
                              :sha sha
                              :branch "main"}))
              tmp (str "/tmp/west-register-" (.getTime (js/Date.)) ".json")]
          (.writeFileSync (js/require "fs") tmp body)
          (let [out (gh-json (str "-X PUT repos/com-junkawasaki/root/contents/manifest/west.yml --input " tmp))]
            (println "registered:" (str/join " " missing))
            (println "commit:" (subs (get-in out [:commit :sha]) 0 12))))))))

(-main)
