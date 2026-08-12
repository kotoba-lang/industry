#!/usr/bin/env nbb
;; Report repositories that west checks out at more than one path.
;;
;;   nbb scripts/verify-duplicate-registrations.cljs            ; report
;;   nbb scripts/verify-duplicate-registrations.cljs --check    ; exit 1 if any
;;
;; Run from the superproject root.
;;
;; ## What it finds
;;
;; A repository that was renamed keeps working under its old name, because
;; GitHub redirects. So a rename can leave BOTH names registered in
;; `manifest/west.yml` -- two projects, two paths, two checkouts, and two pins
;; for one upstream. The pins then drift, and a consumer that pins the old
;; coordinate gets a different commit from one that pins the new.
;;
;; Measured 2026-08-12: 35 repositories were registered at 75 paths this way,
;; including `kotoba-lang/compiler` + `kotoba-lang/amu` (one repo, GitHub id
;; 1297097065) and `cloud-murakumo` under both `gftdcojp` and `network-awai`.
;; Every one of the 35 was confirmed to be the same upstream, not a fork.
;;
;; ## How it decides, and what that costs
;;
;; Two checkouts of one repository share a root commit; a fork does too. So the
;; offline signal is a shared root commit, which is a CANDIDATE. `--verify-
;; remote` resolves each remote through the GitHub API and confirms by
;; repository id -- that is what turns a candidate into a finding, and it needs
;; network and `gh` auth. Without it the report says candidates and means it.
;;
;; Root commits are read with `git rev-list --max-parents=0`, so a repository
;; with several root commits (a subtree merge, say) contributes each of them;
;; only the first is used, which can under-report. Under-reporting is the safe
;; direction here.

(ns verify-duplicate-registrations
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as child]
            [clojure.string :as str]))

(def argv (vec (drop 2 js/process.argv)))
(def check? (some #{"--check"} argv))
(def verify-remote? (some #{"--verify-remote"} argv))

(defn- sh [cmd]
  (try (str/trim (str (child/execSync cmd #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]})))
       (catch :default _ nil)))

(defn- checkouts []
  (->> (try (js->clj (fs/readdirSync "orgs" #js {:withFileTypes true})) (catch :default _ []))
       (filter #(.isDirectory %))
       (mapcat (fn [org]
                 (let [org-name (.-name org)]
                   (->> (try (js->clj (fs/readdirSync (path/join "orgs" org-name)
                                                      #js {:withFileTypes true}))
                             (catch :default _ []))
                        (filter #(.isDirectory %))
                        (map #(str "orgs/" org-name "/" (.-name %)))))))
       (filter #(fs/existsSync (path/join % ".git")))
       vec))

(defn- root-commit [dir]
  (first (some-> (sh (str "git -C " dir " rev-list --max-parents=0 HEAD"))
                 (str/split #"\n"))))

(defn- remote-slug
  "owner/name for the checkout's first remote, or nil."
  [dir]
  (when-let [url (sh (str "git -C " dir " remote get-url $(git -C " dir " remote | head -1)"))]
    (-> url
        (str/replace #"^.*github\.com[:/]" "")
        (str/replace #"\.git$" "")
        (str/replace #"/$" ""))))

(defn- github-id [slug]
  (when slug (sh (str "gh api repos/" slug " --jq .id"))))

(defn -main []
  (let [dirs (checkouts)
        rows (->> dirs
                  (keep (fn [d] (when-let [r (root-commit d)]
                                  {:dir d :root r :slug (remote-slug d)})))
                  vec)
        groups (->> (group-by :root rows)
                    (filter (fn [[_ v]] (> (count v) 1)))
                    (sort-by first))
        confirmed (when verify-remote?
                    (->> groups
                         (keep (fn [[root members]]
                                 (let [ids (map #(github-id (:slug %)) members)]
                                   (when (and (every? some? ids)
                                              (= 1 (count (distinct ids))))
                                     [root members (first ids)]))))
                         vec))]
    (println (str "scanned " (count dirs) " checkouts"))
    (println (str (count groups) " group(s) share a root commit, covering "
                  (reduce + (map (comp count second) groups)) " checkouts"))
    (when verify-remote?
      (println (str (count confirmed) " of those are CONFIRMED one upstream "
                    "(same GitHub repository id)")))
    (println)
    (doseq [[root members] groups]
      (println (str (if verify-remote?
                      (if (some #(= root (first %)) confirmed) "CONFIRMED " "candidate ")
                      "candidate ")
                    (subs root 0 8)))
      (doseq [{:keys [dir slug]} members]
        (println (str "             " dir "  ->  " (or slug "?")))))
    (println)
    (println (str "A shared root commit is also what a fork has. Without "
                  "--verify-remote these are candidates, not findings."))
    (println (str "Only checked-out repos were scanned; west manages more than "
                  "are on disk."))
    (when (and check? (seq (if verify-remote? confirmed groups)))
      (set! (.-exitCode js/process) 1))))

(-main)
