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
;; Measured 2026-08-12: 42 candidate groups covering 85 checkouts, of which 40
;; were confirmed one upstream by GitHub id -- including `kotoba-lang/compiler`
;; + `kotoba-lang/amu` (one repo, id 1297097065) and `cloud-murakumo` under both
;; `gftdcojp` and `network-awai`.
;;
;; The other TWO are the reason the candidate/confirmed distinction is not
;; ceremony: `org-w3-svg` + `svgraph`, and `com-unity-ads` +
;; `com-unity-api-services`, each share a root commit and are different
;; repositories. Retiring either on the offline signal alone would have deleted
;; a real project. An earlier note here said every candidate was confirmed and
;; that no forks existed; that was wrong, and the two counterexamples were
;; already on disk when it was written.
;;
;; ## How it decides, and what that costs
;;
;; Two checkouts of one repository share a root commit; a fork does too. So the
;; offline signal is a shared root commit, which is a CANDIDATE. `--verify-
;; remote` resolves each remote through the GitHub API and confirms by
;; repository id -- that is what turns a candidate into a finding, and it needs
;; network and `gh` auth. Without it the report says candidates and means it.
;;
;; ## Three outcomes, because two would let it lie
;;
;; With `--verify-remote` a group is CONFIRMED (all remotes resolved, all to one
;; id), REFUTED (all resolved, ids differ -- distinct repositories), or
;; UNVERIFIABLE (a lookup failed). The third is not a variant of the second.
;;
;; This mattered concretely. `gh` exits non-zero on an expired token and on rate
;; limiting, and the first version of this script read any non-zero exit as
;; "no id". A group with an unresolved member simply fell out of the confirmed
;; set, so a wholly broken `gh` produced "0 of those are CONFIRMED" and
;; `--check --verify-remote` exited 0 -- reporting a clean fleet while 40 real
;; duplicate registrations sat in front of it. A verifier that cannot reach its
;; evidence must not be indistinguishable from one that looked and found
;; nothing, so UNVERIFIABLE fails `--check` on its own and the failing slug and
;; its error are printed.
;;
;; Root commits are read with `git rev-list --max-parents=0`, so a repository
;; with several root commits (a subtree merge, say) contributes each of them;
;; only the first is used, which can under-report. Measured 2026-08-12: 30
;; checkouts have more than one root, and grouping by ANY shared root gives the
;; identical 42 groups / 85 checkouts -- so the shortcut costs nothing in this
;; fleet today, which is a fact about the fleet and not about the method.

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

(defn- github-id
  "`{:id \"...\"}` or `{:error \"...\"}` -- never a bare nil.

  The distinction is the whole point: a nil cannot say whether the repository
  has no id or whether `gh` never answered, and collapsing those two is how the
  earlier version reported a clean fleet during a rate limit."
  [slug]
  (if-not slug
    {:error "checkout has no remote"}
    (try {:id (str/trim (str (child/execSync (str "gh api repos/" slug " --jq .id")
                                             #js {:encoding "utf8"
                                                  :stdio #js ["ignore" "pipe" "pipe"]})))}
         (catch :default e
           {:error (or (some-> (.-stderr e) str str/trim str/split-lines first)
                       (str/trim (str (.-message e))))}))))

(defn- classify
  "CONFIRMED / REFUTED / UNVERIFIABLE for one candidate group."
  [members]
  (let [looked (map #(assoc % :lookup (github-id (:slug %))) members)
        errs (filter (comp :error :lookup) looked)
        ids (distinct (keep (comp :id :lookup) looked))]
    (cond
      (seq errs) {:verdict :unverifiable :members looked :errors errs}
      (= 1 (count ids)) {:verdict :confirmed :members looked :id (first ids)}
      :else {:verdict :refuted :members looked :ids ids})))

(defn -main []
  (let [dirs (checkouts)
        rows (->> dirs
                  (keep (fn [d] (when-let [r (root-commit d)]
                                  {:dir d :root r :slug (remote-slug d)})))
                  vec)
        groups (->> (group-by :root rows)
                    (filter (fn [[_ v]] (> (count v) 1)))
                    (sort-by first))
        verdicts (when verify-remote?
                   (into {} (map (fn [[root members]] [root (classify members)])) groups))
        by (fn [v] (filter #(= v (:verdict (verdicts (first %)))) groups))
        confirmed (when verify-remote? (by :confirmed))
        refuted (when verify-remote? (by :refuted))
        unverifiable (when verify-remote? (by :unverifiable))]
    (println (str "scanned " (count dirs) " checkouts"))
    (println (str (count groups) " group(s) share a root commit, covering "
                  (reduce + (map (comp count second) groups)) " checkouts"))
    (when verify-remote?
      (println (str (count confirmed) " CONFIRMED one upstream (same GitHub id), "
                    (count refuted) " REFUTED (different repositories), "
                    (count unverifiable) " UNVERIFIABLE (a lookup failed)")))
    (println)
    (doseq [[root members] groups]
      (let [{:keys [verdict]} (get verdicts root)]
        (println (str (case verdict
                        :confirmed "CONFIRMED    "
                        :refuted "REFUTED      "
                        :unverifiable "UNVERIFIABLE "
                        "candidate    ")
                      (subs root 0 8)))
        (doseq [{:keys [dir slug lookup]} (or (:members (get verdicts root)) members)]
          (println (str "             " dir "  ->  " (or slug "?")
                        (when-let [id (:id lookup)] (str "  id=" id))
                        (when-let [e (:error lookup)] (str "  LOOKUP FAILED: " e)))))))
    (println)
    (println (str "A shared root commit is also what a fork has. Without "
                  "--verify-remote these are candidates, not findings."))
    (when (seq unverifiable)
      (println (str (count unverifiable) " group(s) could not be verified at all. "
                    "That is not the same as clean, and --check treats it as a "
                    "failure: check `gh auth status` and rate limits.")))
    (println (str "Only checked-out repos were scanned; west manages more than "
                  "are on disk."))
    (when (and check? (seq (if verify-remote?
                             (concat confirmed unverifiable)
                             groups)))
      (set! (.-exitCode js/process) 1))))

(-main)
