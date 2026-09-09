#!/usr/bin/env nbb
;; scripts/worktree-retire.cljs — retire git worktrees whose work has already landed.
;;
;; The worktree-per-task model (CLAUDE.md「並行エージェント運用」, ADR-2609061800)
;; costs almost nothing in disk or time to CREATE; what it costs is the
;; worktree nobody removes after the merge. Measured 2026-09-06: of 124
;; session-owned child worktrees, 55 were already landed on their default
;; branch, and the superproject's own 14 were all landed. The rule says
;; 「着地後の後片付けまでが完了条件」— this script is what makes that true
;; when the session that made the worktree is gone.
;;
;; A worktree is retired only when ALL of these hold:
;;   landed   its HEAD is an ancestor of SOME remote's default branch (any remote:
;;            west fetches into the manifest remote, and a leftover `origin` is often
;;            stale — judging against one guessed remote is what made this lie)
;;   clean    `git status --porcelain` is empty (untracked files count as dirty)
;;   old      newest of (dir mtime, .git/worktrees/<n>/HEAD mtime) >= --min-age-days
;;   idle     no process has its cwd inside it (lsof), no command line names it (ps)
;;   unlocked no `locked` file for it
;;   not bot  not under ~/.itonami/worktrees (resident bots own theirs; --include-bots to widen)
;;
;; Everything else is reported with the reason it was kept. Dirty worktrees
;; are never touched — that is git-cleanup-conflict's job, and it archives
;; before it drops. `git worktree remove` is run WITHOUT --force so git's own
;; dirty check is a second floor under ours. A branch is deleted only with
;; `git branch -d` (refuses unmerged) and only if no other worktree holds it.
;;
;; Stale registrations (directory already gone) are pruned; they cost 0 bytes
;; but make `git worktree list` lie.
;;
;; Six-questions discipline: if lsof cannot be run the script REFUSES to
;; apply (exit 2) — "could not tell whether it is busy" is not "idle".
;; The last line is always `SCANNED <n> retired <k> kept <m> unverified <u>`;
;; SCANNED 0 is exit 2, not a clean pass.
;;
;;   nbb scripts/worktree-retire.cljs [--root <dir>] [--min-age-days 7] [--apply] [--include-bots]
;;                                    [--only <path-prefix>]
;;   exit 0 = answered, 1 = a retire step failed, 2 = could not answer
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def cp (js/require "node:child_process"))
(def os (js/require "node:os"))

(def argv (vec (.slice (.-argv js/process) 2)))
(defn opt [flag default]
  (let [i (.indexOf argv flag)] (if (neg? i) default (nth argv (inc i) default))))
(defn flag? [f] (some #(= % f) argv))

(def root (.resolve path (opt "--root" (.cwd js/process))))
(def min-age-days (js/parseInt (opt "--min-age-days" "7") 10))
(def apply? (flag? "--apply"))
(def include-bots? (flag? "--include-bots"))
;; Restrict to worktrees under this path prefix. Per-tick bot farms are throwaway and
;; tolerate a 1-day floor; a human's parked worktree wants the 7-day default. Without a
;; scope the two populations can only share one floor, and the safe floor reclaims nothing.
(def only-prefix (some-> (opt "--only" nil) (#(.resolve path %))))
(def bot-dir (.join path (.homedir os) ".itonami" "worktrees"))
(def receipt (.join path (.homedir os) ".itonami" "worktree-retire.log"))

(defn sh
  "Run cmd; return {:out :exit}. Never throws; a timeout is exit 124 like coreutils."
  ([cmd] (sh cmd nil))
  ([cmd cwd]
   (try {:out (str (.execSync cp cmd #js {:encoding "utf8" :cwd (or cwd root) :timeout 60000
                                          :maxBuffer 64000000 :stdio #js ["pipe" "pipe" "pipe"]}))
         :exit 0}
        (catch :default e
          {:out (str (or (.-stdout e) "")) :err (str (or (.-stderr e) ""))
           :exit (cond (= "ETIMEDOUT" (.-code e)) 124 :else (or (.-status e) 1))}))))

(defn q [s] (str "'" (str/replace s "'" "'\\''") "'"))
(defn exists? [p] (try (.existsSync fs p) (catch :default _ false)))
(defn mtime [p] (try (.getTime (.-mtime (.statSync fs p))) (catch :default _ 0)))

;; ---- repos: superproject + every orgs/<org>/<repo> that has .git/worktrees ----
(defn ls-dirs [p]
  (try (->> (.readdirSync fs p #js {:withFileTypes true})
            (filter #(.isDirectory %)) (map #(.-name %)) vec)
       (catch :default _ [])))
(defn repos []
  (into (if (exists? (.join path root ".git" "worktrees")) [root] [])
        (for [org (ls-dirs (.join path root "orgs"))
              r   (ls-dirs (.join path root "orgs" org))
              :let [d (.join path root "orgs" org r)]
              :when (exists? (.join path d ".git" "worktrees"))]
          d)))

;; ---- busy detection: cwd of every process + every command line ----
(defn busy-index []
  (let [lsof (sh "lsof -a -d cwd -F n 2>/dev/null")
        ps   (sh "ps -axo command 2>/dev/null")]
    (if (or (not= 0 (:exit lsof)) (str/blank? (:out lsof)))
      nil
      {:cwds (->> (str/split-lines (:out lsof)) (filter #(str/starts-with? % "n")) (map #(subs % 1)) set)
       :cmds (:out ps)})))
(defn busy? [idx wt]
  (or (some #(or (= % wt) (str/starts-with? % (str wt "/"))) (:cwds idx))
      (str/includes? (:cmds idx) wt)))

;; ---- per-repo ----
(defn remotes [repo]
  (->> (str/split-lines (:out (sh "git remote" repo)))
       (map str/trim) (remove str/blank?)))

(defn remote-default-ref
  "This one remote's default branch, or nil if it cannot be resolved."
  [repo rem]
  (let [r (sh (str "git symbolic-ref -q --short refs/remotes/" rem "/HEAD") repo)]
    (if (and (= 0 (:exit r)) (not (str/blank? (:out r))))
      (str/trim (:out r))
      (some (fn [b]
              (let [c (str rem "/" b)]
                (when (= 0 (:exit (sh (str "git rev-parse --verify -q " c "^{commit}") repo))) c)))
            ["main" "master"]))))

(defn default-refs
  "Every remote's default branch. Empty when none resolves — and empty is not
  `origin/main`: an unresolvable ref must reach classify as `could not measure`,
  never as `unlanded` (ADR-2608136000).

  All of them, not one, because picking one is what kept getting this wrong.
  west names a remote after the manifest remote (kotoba-lang / gftdcojp / …) and
  measured 2026-08-08, 197 of 273 sampled `orgs/` repos have no `origin` at all —
  so hardcoding `origin` made the ancestry test fatal and every worktree read
  `unlanded`. But preferring `origin` when it *is* present fails the other way:
  measured 2026-09-09, 294 repos carry both, and in `network-awai/net-kotobase`
  both point at the same GitHub repo while `origin/main` sits 56 commits behind
  the `gftdcojp/main` that west actually fetches. Judging against the stale one
  called 127 landed worktrees `unlanded`.

  A worktree is retired only when its commits already exist upstream, so the
  honest question is whether HEAD is on *a* published default branch — not on
  whichever remote we guessed."
  [repo]
  (->> (remotes repo) (keep #(remote-default-ref repo %)) distinct vec))

(defn worktrees [repo]
  (let [{:keys [out]} (sh "git worktree list --porcelain" repo)
        blocks (remove str/blank? (str/split out #"\n\n"))]
    (->> blocks
         (map (fn [b]
                (let [lines (str/split-lines b)
                      kv (fn [k] (some #(when (str/starts-with? % (str k " ")) (subs % (inc (count k)))) lines))]
                  {:path (kv "worktree") :sha (kv "HEAD")
                   :branch (some-> (kv "branch") (str/replace #"^refs/heads/" ""))
                   :locked (some #(str/starts-with? % "locked") lines)
                   :prunable (some #(str/starts-with? % "prunable") lines)})))
         (drop 1)))) ; first block is the main working tree

(defn admin-dir [repo wt]
  ;; .git/worktrees/<name> — name is the basename unless git had to disambiguate;
  ;; resolve it through the gitdir file instead of guessing.
  (let [gd (.join path wt ".git")]
    (when (try (.isFile (.statSync fs gd)) (catch :default _ false))
      (let [s (str/trim (str (.readFileSync fs gd "utf8")))]
        (when (str/starts-with? s "gitdir: ") (subs s 8))))))

(defn classify [repo def-refs idx shallow? wt]
  (let [p (:path wt)]
    (cond
      (:prunable wt) {:verdict :prune :why "directory gone"}
      (not (exists? p)) {:verdict :prune :why "directory gone"}
      (:locked wt) {:verdict :keep :why "locked"}
      (and (not include-bots?) (str/starts-with? p (str bot-dir "/"))) {:verdict :keep :why "resident bot"}
      shallow? {:verdict :unverified :why "repo is shallow; ancestry answer untrusted"}
      (empty? def-refs) {:verdict :unverified :why "no resolvable default ref; ancestry unanswerable"}
      (nil? idx) {:verdict :unverified :why "lsof unavailable"}
      (busy? idx p) {:verdict :keep :why "busy (process cwd / argv)"}
      :else
      (let [landed (boolean (some #(= 0 (:exit (sh (str "git merge-base --is-ancestor " (:sha wt) " " %) repo)))
                                  def-refs))
            st (sh "git status --porcelain" p)
            clean (and (= 0 (:exit st)) (str/blank? (:out st)))
            ad (admin-dir repo p)
            newest (max (mtime p) (if ad (mtime (.join path ad "HEAD")) 0))
            age-days (/ (- (.now js/Date) newest) 86400000)]
        (cond
          (not= 0 (:exit st)) {:verdict :unverified :why (str "git status exit " (:exit st))}
          (not landed) {:verdict :keep :why "unlanded" :age age-days}
          (not clean) {:verdict :keep :why "landed but dirty (→ git-cleanup-conflict)" :age age-days}
          (< age-days min-age-days) {:verdict :keep :why (str "landed, clean, but " (.toFixed age-days 1) "d < " min-age-days "d") :age age-days}
          :else {:verdict :retire :why (str "landed, clean, " (.toFixed age-days 1) "d") :age age-days})))))

(defn other-holder? [repo branch wt]
  (some #(and (= (:branch %) branch) (not= (:path %) wt)) (worktrees repo)))

(defn retire! [repo wt]
  (let [p (:path wt)
        rm (sh (str "git worktree remove " (q p)) repo)]
    (if (not= 0 (:exit rm))
      {:ok false :step "worktree remove" :err (str/trim (or (:err rm) (:out rm)))}
      (let [br (:branch wt)
            bd (when (and br (not (other-holder? repo br p)))
                 (sh (str "git branch -d " (q br)) repo))]
        {:ok true :branch-deleted (and bd (= 0 (:exit bd)))
         :branch-note (when (and bd (not= 0 (:exit bd))) (str/trim (or (:err bd) (:out bd))))}))))

(defn log-receipt! [m]
  (try (.mkdirSync fs (.dirname path receipt) #js {:recursive true})
       (.appendFileSync fs receipt (str (js/JSON.stringify (clj->js m)) "\n"))
       (catch :default _ nil)))

(defn -main []
  (let [idx (busy-index)
        rs (repos)
        counts (atom {:scanned 0 :retired 0 :kept 0 :unverified 0 :pruned 0 :failed 0})]
    (when (and apply? (nil? idx))
      (println "REFUSED: lsof unavailable — cannot tell idle from busy; not applying.")
      (.exit js/process 2))
    (println (str (if apply? "APPLY" "DRY-RUN") " root=" root " repos-with-worktrees=" (count rs)
                  " min-age-days=" min-age-days (when include-bots? " include-bots") (when only-prefix (str " only=" only-prefix))))
    (doseq [repo rs]
      (let [rel (if (= repo root) "root" (subs repo (inc (count root))))
            shallow? (= "true" (str/trim (:out (sh "git rev-parse --is-shallow-repository" repo))))
            def-refs (default-refs repo)
            wts (cond->> (worktrees repo)
                  only-prefix (filter #(let [p (:path %)]
                                         (and p (or (= p only-prefix)
                                                    (str/starts-with? p (str only-prefix "/")))))))]
        (when (seq wts)
          (doseq [wt wts]
            (swap! counts update :scanned inc)
            (let [{:keys [verdict why]} (classify repo def-refs idx shallow? wt)]
              (case verdict
                :prune (do (swap! counts update :pruned inc)
                           (println (str "PRUNE   " rel "  " (:path wt) "  (" why ")"))
                           (when apply? (sh "git worktree prune" repo)))
                :keep (do (swap! counts update :kept inc)
                          (println (str "keep    " rel "  " (:path wt) "  (" why ")")))
                :unverified (do (swap! counts update :unverified inc)
                                (println (str "UNVERIFIED " rel "  " (:path wt) "  (" why ")")))
                :retire
                (if-not apply?
                  (do (swap! counts update :retired inc)
                      (println (str "RETIRE  " rel "  " (:path wt) "  [" (or (:branch wt) "detached") " " (subs (:sha wt) 0 10) "]  (" why ")")))
                  (let [r (retire! repo wt)]
                    (log-receipt! (merge {:at (.toISOString (js/Date.)) :repo rel :path (:path wt)
                                          :sha (:sha wt) :branch (:branch wt) :why why} r))
                    (if (:ok r)
                      (do (swap! counts update :retired inc)
                          (println (str "RETIRED " rel "  " (:path wt) "  [" (or (:branch wt) "detached") " " (subs (:sha wt) 0 10) "]"
                                        (when (:branch-deleted r) " branch -d") (when (:branch-note r) (str "  (branch kept: " (:branch-note r) ")")))))
                      (do (swap! counts update :failed inc)
                          (println (str "FAILED  " rel "  " (:path wt) "  " (:step r) ": " (:err r)))))))))))))
    (let [{:keys [scanned retired kept unverified pruned failed]} @counts]
      (println (str "SCANNED " scanned " retired " retired " kept " kept " unverified " unverified " pruned " pruned " failed " failed))
      (.exit js/process (cond (zero? scanned) 2 (pos? failed) 1 :else 0)))))

(-main)
