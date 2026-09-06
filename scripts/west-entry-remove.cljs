#!/usr/bin/env nbb
;; scripts/west-entry-remove.cljs — unregister west project entries.
;;
;; The counterpart to west-entry-rename.cljs, and the more dangerous one: a
;; rename that is wrong still points at a repository, and a removal that is
;; wrong points at nothing. Two cases need it — a repository that is being
;; retired, and a duplicate entry for a repository another entry already
;; registers — and both are easy to get backwards.
;;
;; So it refuses unless it can see what is being given up:
;;
;;   1. the entry exists and is a PROJECT (has a `path:`). A remote and a
;;      project can share a name.
;;   2. a local checkout at that path, if there is one, is CLEAN: no
;;      uncommitted changes and no commits its remote does not have. An
;;      unregistered path stops being updated and stops being noticed, so
;;      work left in one is work that disappears quietly.
;;   3. REASON is set and non-empty. Every removal is a decision; a removal
;;      with no reason recorded is one nobody can audit later.
;;
;; It does NOT check whether the upstream repository still exists: removing
;; the entry BEFORE deleting the repository is the correct order, and a
;; check for absence would force the dangerous one.
;;
;;   ENTRIES=names.txt REASON="..." [DRY=1] \
;;     nbb --classpath ".:scripts/nbb_compat" scripts/west-entry-remove.cljs
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def entries-file (.-ENTRIES js/process.env))
(def reason (.-REASON js/process.env))
(def dry? (= "1" (.-DRY js/process.env)))
(def repo "com-junkawasaki/root")
(def path "manifest/west.yml")

(defn sh [cmd]
  (try {:out (str (.execSync cp cmd #js {:encoding "utf8" :maxBuffer 64000000
                                         :stdio #js ["pipe" "pipe" "pipe"]})) :exit 0}
       (catch :default e
         {:out (str (or (.-stdout e) "") (or (.-stderr e) "")) :exit (or (.-status e) 1)})))

(when-not (and (string? reason) (seq (str/trim (or reason ""))))
  (println "REFUSING: REASON is empty. A removal with no reason recorded is one nobody can audit later.")
  (js/process.exit 2))

;; Run from the superproject root or not at all.
;;
;; The checkout checks below resolve `orgs/<org>/<name>` against the current
;; directory. Run from anywhere else -- a worktree, /tmp -- every path is
;; absent, every check finds nothing to lose, and the script reports that all
;; entries are safe to remove. Measured 2026-09-06: it did exactly that, in
;; this script's own first hour, against a checkout that had an unpushed
;; commit sitting in it. A tool that cannot see the working trees must not
;; return the same answer as one that saw them and found them clean.
(when-not (zero? (:exit (sh "test -f manifest/west.yml")))
  (println "REFUSING: no manifest/west.yml here. This must run from the superproject root;"
           "anywhere else, every checkout looks absent and every removal looks safe.")
  (js/process.exit 2))

(def wanted
  (->> (str/split-lines (.readFileSync fs entries-file "utf8"))
       (map str/trim) (remove str/blank?) vec))

(defn tip []
  {:blob (str/trim (:out (sh (str "gh api repos/" repo "/contents/manifest --jq '.[] | select(.name==\"west.yml\") | .sha'"))))
   :text (:out (sh (str "gh api repos/" repo "/contents/" path " -H 'Accept: application/vnd.github.raw'")))})

(defn entry-span [lines entry]
  (let [n (count lines)]
    (loop [i 0]
      (when (< i n)
        (if (= (str/trim (nth lines i)) (str "- name: " entry))
          (let [end (loop [j (inc i)]
                      (if (or (>= j n) (str/starts-with? (str/trim (nth lines j)) "- name: ")) j (recur (inc j))))]
            (if (some #(str/starts-with? (str/trim %) "path: ") (subvec lines i end))
              [i end]
              (recur end)))
          (recur (inc i)))))))

(defn checkout-objection
  "nil when the working tree at PATH may be given up.

  `--branches --not --remotes` compares against the LOCALLY CACHED remote
  refs, and in a west checkout those go stale: west moves the local
  `manifest-rev` branch to each new pin without necessarily refreshing
  `refs/remotes/*`. Measured 2026-09-06, that made this refuse to remove two
  entries whose tips the upstream repository demonstrably had. The refusal
  was in the safe direction, but a check that answers from a stale cache is
  not answering about the remote at all -- so it fetches first, and then
  asks the SERVER about anything that still looks local."
  [p remote repo-path]
  (if-not (zero? (:exit (sh (str "test -e " p "/.git"))))
    ;; Absent is not clean.
    ;;
    ;; A missing path means one of two things and the difference cannot be
    ;; seen from here: the project was never checked out, or this is not the
    ;; tree that has the checkouts. Measured 2026-09-06, running from a
    ;; worktree made every checkout look absent and every removal look safe,
    ;; while one of them held an unpushed commit. So absence refuses, and
    ;; ALLOW_ABSENT=1 turns it into a stated decision instead of an inference.
    (when-not (= "1" (.-ALLOW_ABSENT js/process.env))
      (str "no checkout at " p " -- cannot tell 'never checked out' from"
           " 'wrong tree'. Run from the superproject root, or set ALLOW_ABSENT=1"
           " to say the absence is real"))
    (do
    (sh (str "git -C " p " fetch --quiet " remote " 2>/dev/null"))
    (let [dirty (str/trim (:out (sh (str "git -C " p " status --porcelain 2>/dev/null | head -5"))))
          local (->> (str/split-lines (str/trim (:out (sh (str "git -C " p " log --branches --not --remotes --format=%H 2>/dev/null | head -20")))))
                     (remove str/blank?))
          absent (remove (fn [sha]
                           (zero? (:exit (sh (str "gh api repos/" remote "/" repo-path "/commits/" sha
                                                  " --jq .sha >/dev/null 2>&1")))))
                         local)]
      (cond
        (seq dirty) (str "checkout at " p " has uncommitted changes")
        (seq absent) (str "checkout at " p " has " (count absent)
                          " commit(s) " remote "/" repo-path " does not have, first "
                          (subs (first absent) 0 8))
        :else nil)))))

(let [{:keys [blob text]} (tip)
      lines (vec (str/split-lines text))
      result
      (reduce
       (fn [{:keys [lines removed]} name]
         (if-let [[s e] (entry-span lines name)]
           (let [block (subvec lines s e)
                 fld (fn [k] (some #(let [t (str/trim %)]
                                      (when (str/starts-with? t (str k ": ")) (subs t (+ 2 (count k))))) block))
                 p (fld "path")
                 remote (fld "remote")
                 repo-path (or (fld "repo-path") name)]
             (if-let [err (checkout-objection p remote repo-path)]
               (do (println "  keep" name ":" err) {:lines lines :removed removed})
               (do (println "  remove" name " (" p ")")
                   {:lines (vec (concat (subvec lines 0 s) (subvec lines e)))
                    :removed (conj removed name)})))
           (do (println "  keep" name ": no project entry with that name")
               {:lines lines :removed removed})))
       {:lines lines :removed []} wanted)]
  (println (str "  " (count (:removed result)) " of " (count wanted) " entr(ies) removable"))
  (cond
    (empty? (:removed result)) (println "nothing to write")
    dry? (println "DRY=1 — not writing")
    :else
    (let [out (str (str/join "\n" (:lines result)) "\n")
          payload {:message (str "west: unregister " (count (:removed result))
                                 " entr(ies) — " (str/trim reason))
                   :content (.toString (.from js/Buffer out "utf8") "base64")
                   :sha blob :branch "main"}
          pf "/tmp/west-entry-remove-payload.json"
          _ (.writeFileSync fs pf (js/JSON.stringify (clj->js payload)) "utf8")
          r (sh (str "gh api -X PUT repos/" repo "/contents/" path " --input " pf " --jq .commit.sha"))]
      (if (re-matches #"[0-9a-f]{40}" (str/trim (:out r)))
        (println (str "committed " (str/trim (:out r)) " (" (count (:removed result)) " entries)"))
        (println "PUT failed:" (str/trim (:out r)))))))
