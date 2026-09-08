#!/usr/bin/env nbb
(ns verify-git-dep-pin-reachability
  "Every `:git/sha` in a deps.edn, asked of the library it names.

  ## Why this exists

  CLAUDE.md already says it: west pins have a gate (`verify-west-pins`), and
  `deps.edn` pins have none. So the two failure modes below are invisible until
  a build breaks, and one of them is not even a build break -- it is a wrong
  answer at runtime.

    UNREACHABLE  the pinned sha is not an ancestor of the library's default
                 branch. Either it sits on a branch that was never merged, or
                 the commit no longer exists at all. CLAUDE.md forbids the
                 first by name; nothing was checking.

    STALE        the pinned sha is a real ancestor, just an old one. Ordinarily
                 fine -- a pin is allowed to be a deliberate floor -- but the
                 fix the library landed cannot reach this repo, because
                 tools.deps takes the newest sha it is SHOWN.

  Measured 2026-09-09, and the reason this detector was written: 98 repos
  pinned `io.github.kotoba-lang/json` before d5137c7, the commit that restored
  read-str's trailing kwargs. 23 of them actually called it that way, and every
  such call threw ArityException. The fix had been on main for a day. It was
  merged, and it was not deployed, and nothing in the tree could tell the two
  apart.

  ## What it will not claim

  A library with no local checkout is UNRESOLVED, not clean -- a check that
  could not run must not return the value of a check that found nothing. A
  shallow checkout answers ancestry wrongly and with authority, so it is
  refused rather than believed (CLAUDE.md, Git operations).

  STALE is reported at :info. Being behind is not by itself a defect; being
  behind without knowing it is. UNREACHABLE is a finding.

  Exit: 0 clean, 1 findings, 2 REFUSED (could not measure).

    nbb --classpath \".:scripts/nbb_compat\" scripts/verify-git-dep-pin-reachability.cljs [--findings] [--root DIR]"
  (:require [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(def findings-mode? (some #{"--findings"} argv))
(def root (or (second (drop-while #(not= "--root" %) argv)) "."))

(defn- sh [args opts]
  (let [r (.spawnSync cp (first args) (clj->js (rest args))
                      (clj->js (merge {:encoding "utf8" :maxBuffer 67108864} opts)))]
    {:status (.-status r) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn- deps-files []
  (let [r (sh ["rg" "--files" "-g" "deps.edn" "-g" "!node_modules" "-g" "!target"
               "-g" "!out" "-g" "!.git" "orgs"] {:cwd root})]
    (if (> (:status r) 1)
      nil
      (remove str/blank? (str/split-lines (:out r))))))

;; `io.github.<org>/<repo> {... :git/sha "<40 hex>" ...}` -- the coordinate and
;; its sha have to come from the SAME map, or a file with two coordinates pairs
;; each name with the wrong sha.
(def coord-re
  #"(?:io|com|net)\.github\.([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)\s*\{([^}]*)\}")

(defn- coords-in [text]
  (for [m (re-seq coord-re text)
        :let [[_ org repo body] m
              sha (second (re-find #":git/sha\s+\"([0-9a-f]{7,40})\"" body))
              ;; The coordinate NAME is not the repo address. Measured
              ;; 2026-09-09: kami-app-cad names `io.github.kotoba-lang/cad` and
              ;; points its :git/url at kotoba-lang/kami-engine-cad, where the
              ;; pinned commit exists and is fine. Deriving the library from the
              ;; name reported that healthy pin as a commit that does not exist.
              ;; When a :git/url is declared, it is the address.
              url (second (re-find #":git/url\s+\"([^\"]+)\"" body))
              [uorg urepo] (when url
                             (when-let [m2 (re-find #"github\.com[:/]([^/]+)/([^/\s\"]+?)(?:\.git)?/?$" url)]
                               [(nth m2 1) (nth m2 2)]))]
        :when sha]
    {:org (or uorg org) :repo (or urepo repo) :sha sha
     :named (str org "/" repo)}))

(def ^:private lib-cache (atom {}))

(defn- lib-state
  "Where the library is, whether we can trust its ancestry, and its tip."
  [org repo]
  (or (get @lib-cache [org repo])
      (let [p (path.join root "orgs" org repo)
            st (cond
                 (not (fs.existsSync (path.join p ".git")))
                 {:state :no-checkout}

                 (= "true" (str/trim (:out (sh ["git" "-C" p "rev-parse"
                                                "--is-shallow-repository"] {}))))
                 ;; A shallow clone answers --is-ancestor wrongly and with
                 ;; authority. Refuse it rather than record its answer.
                 {:state :shallow}

                 :else
                 (let [rem (->> (str/split-lines (:out (sh ["git" "-C" p "remote" "-v"] {})))
                                (filter #(str/includes? % "(fetch)"))
                                (filter #(re-find (re-pattern (str "github\\.com[:/]" org "/" repo "(\\.git)?\\s")) %))
                                (map #(first (str/split % #"\s")))
                                first)
                       rem (or rem "origin")
                       ref (first (for [b ["main" "master"]
                                        :let [r (str "refs/remotes/" rem "/" b)]
                                        :when (zero? (:status (sh ["git" "-C" p "rev-parse" "--verify" "-q" r] {})))]
                                    (str rem "/" b)))]
                   (if ref
                     {:state :ok :dir p :ref ref
                      :tip (str/trim (:out (sh ["git" "-C" p "rev-parse" ref] {})))}
                     {:state :no-ref})))]
        (swap! lib-cache assoc [org repo] st)
        st)))

(defn- classify [{:keys [org repo sha]}]
  (let [{:keys [state dir ref tip]} (lib-state org repo)]
    (case state
      :ok (let [known? (zero? (:status (sh ["git" "-C" dir "cat-file" "-e" (str sha "^{commit}")] {})))]
            (cond
              (not known?) {:verdict :unreachable :why "the commit is not in the library at all"}
              (zero? (:status (sh ["git" "-C" dir "merge-base" "--is-ancestor" sha ref] {})))
              (let [behind (str/trim (:out (sh ["git" "-C" dir "rev-list" "--count"
                                                (str sha ".." ref)] {})))]
                (if (= "0" behind)
                  {:verdict :current}
                  {:verdict :stale :behind (js/parseInt behind) :tip tip}))
              :else {:verdict :unreachable
                     :why (str "not an ancestor of " ref " -- an unmerged branch, or history that moved")}))
      {:verdict :unresolved :why (name state)})))

(defn- confirm-upstream
  "A local remote-tracking ref can be older than the library's actual main, and
  then a pin that IS merged reads as unmerged. So a local UNREACHABLE verdict is
  a suspicion, not a finding: ask GitHub before reporting it. A server that
  cannot answer leaves the entry :unverified, which is neither clean nor a
  finding -- a check that could not run must not return either one's value."
  [{:keys [org repo sha]}]
  (let [r (sh ["gh" "api" (str "repos/" org "/" repo "/compare/HEAD..." sha)
               "--jq" ".status"] {})]
    (if (zero? (:status r))
      (let [st (str/trim (:out r))]
        ;; `behind` means sha is an ancestor of the default branch; `identical`
        ;; means it IS the tip. Anything else (ahead/diverged) is the finding.
        (if (#{"behind" "identical"} st)
          {:upstream :reachable :status st}
          {:upstream :unreachable :status st}))
      ;; A 404 from `compare` does NOT mean the commit is absent. It is also
      ;; what a renamed repo, a permission failure, and GitHub's secondary rate
      ;; limit return -- and CLAUDE.md records that `gh api rate_limit` is exempt
      ;; and answers for a different bucket, so it cannot tell them apart.
      ;; Measured 2026-09-09, the three shapes are distinguishable, but only on
      ;; the /commits/<sha> endpoint, not on /compare:
      ;;   real repo, absent sha -> 422 "No commit found for SHA"
      ;;   real repo, real sha   -> 200
      ;;   absent/inaccessible repo -> 404 on repos/<o>/<r> itself
      ;; So: probe the commit, then the repo, and only report a finding when the
      ;; server actually said the commit is not there.
      (let [c (sh ["gh" "api" (str "repos/" org "/" repo "/commits/" sha) "--jq" ".sha"] {})
            cbody (str (:out c) (:err c))]
        (cond
          (re-find #"No commit found for SHA" cbody)
          {:upstream :unreachable :status "absent"}
          ;; The commit is there, so the compare failing is our problem, not the
          ;; pin's. Do not turn our own failure into somebody's finding.
          (zero? (:status c))
          {:upstream :unverified :status "commit exists; compare did not answer"}
          (zero? (:status (sh ["gh" "api" (str "repos/" org "/" repo) "--jq" ".name"] {})))
          {:upstream :unverified :status (str "repo answers, commit query did not: " (str/trim cbody))}
          :else
          {:upstream :unverified :status (str "repo did not answer: " (str/trim cbody))})))))

(defn -main []
  (let [files (deps-files)]
    (when (nil? files)
      (println "REFUSED: ripgrep did not enumerate deps.edn files; nothing was measured.")
      (js/process.exit 2))
    (when (< (count files) 100)
      (println (str "REFUSED: only " (count files) " deps.edn found under orgs/;"
                    " this workspace has thousands, so the walk did not run."))
      (js/process.exit 2))
    (let [rows (for [f files
                     :let [text (try (str (fs.readFileSync (path.join root f) "utf8"))
                                     (catch :default _ nil))]
                     :when text
                     c (coords-in text)]
                 (assoc c :file f))
          rows (vec rows)
          judged (mapv #(merge % (classify %)) rows)
          ;; Two occurrences of the same coordinate in one file are one fact.
          judged (vec (vals (reduce (fn [m r] (assoc m [(:file r) (:org r) (:repo r) (:sha r)] r))
                                    {} judged)))
          by (group-by :verdict judged)
          suspect (vec (:unreachable by))
          confirmed (mapv #(merge % (confirm-upstream %)) suspect)
          bad (vec (filter #(= :unreachable (:upstream %)) confirmed))
          unverified-up (vec (filter #(= :unverified (:upstream %)) confirmed))
          cleared (vec (filter #(= :reachable (:upstream %)) confirmed))
          stale (vec (:stale by))
          unres (vec (:unresolved by))]
      (println (str "SCANNED\t" (count files) "\tdeps.edn files, " (count rows)
                    " git coordinates, " (count (distinct (map (juxt :org :repo) rows)))
                    " distinct libraries; " (count suspect)
                    " looked unreachable locally and were re-asked of GitHub"))
      (if findings-mode?
        (do
          (doseq [{:keys [file org repo sha why]} bad]
            (println (str "FINDING\twarn\t" file ":" org "/" repo "\t" file
                          " pins " org "/" repo " at " (subs sha 0 (min 8 (count sha)))
                          " -- " why)))
          (when (seq stale)
            (let [worst (->> stale (sort-by :behind >) (take 3)
                             (map #(str (:org %) "/" (:repo %) " in " (:file %)
                                        " (" (:behind %) " behind")) )]
              (println (str "FINDING\tinfo\tstale-pins\t" (count stale)
                            " pins are real ancestors but behind their library's tip"
                            " -- a floor is legitimate, being behind unknowingly is not."
                            " Worst: " (str/join "), " worst) ")"))))
          (when (seq unverified-up)
            (println (str "FINDING\tinfo\tunverified-upstream\t" (count unverified-up)
                          " pins look unreachable locally and GitHub could not be"
                          " asked -- UNVERIFIED, which is neither clean nor a finding")))
          (when (seq cleared)
            (println (str "FINDING\tinfo\tstale-local-ref\t" (count cleared)
                          " pins looked unreachable only because the local"
                          " remote-tracking ref is behind the library's real main")))
          (when (seq unres)
            (println (str "FINDING\tinfo\tunresolved\t" (count unres)
                          " coordinates name a library with no usable local checkout"
                          " -- UNRESOLVED, which is not clean"))))
        (do
          (println)
          (if (seq bad)
            (doseq [{:keys [file org repo sha why]} (sort-by :file bad)]
              (println (str "  UNREACHABLE  " file "\n      " org "/" repo " @ "
                            (subs sha 0 (min 8 (count sha))) " -- " why)))
            (println "  every resolvable pin is reachable from its library's default branch"))
          (println)
          (println (str "  stale (ancestor, behind tip): " (count stale)))
          (println (str "  unresolved (no usable checkout): " (count unres)))
          (println (str "  current: " (count (:current by))))
          (println (str "  cleared by asking GitHub (stale local ref): " (count cleared)))
          (println (str "  unverified upstream: " (count unverified-up)))))
      ;; Suspects that NONE of which could be checked is not a clean run. Under
      ;; a failing `gh` -- measured with a stub that returns the secondary rate
      ;; limit's 403 -- all 36 suspects land in :unverified and the finding list
      ;; is empty, which would exit 0 and read exactly like a workspace with no
      ;; bad pins. Refuse instead.
      (when (and (seq suspect) (= (count unverified-up) (count suspect)))
        (println (str "REFUSED: all " (count suspect) " locally-suspect pins were"
                      " unverifiable against GitHub; the reachability question was"
                      " not answered for any of them."))
        (js/process.exit 2))
      (js/process.exit (if (seq bad) 1 0)))))

(-main)
