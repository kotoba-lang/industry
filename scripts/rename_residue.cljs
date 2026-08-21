(ns scripts.rename-residue
  "Untracked files that are the **residue of a completed move**, not work in progress.

  ## The bug this exists to stop

  `scripts/cleanup-land.cljs` classifies an untracked file as `:additive` when no
  path of that name exists on the default branch, and `:additive` is the one class
  it **auto-merges**. The reasoning is sound as far as it goes: if the path is not
  on `main`, landing it cannot rewrite any line of `main`.

  A directory rename breaks that reasoning, and breaks it *precisely*. When
  `worker/` becomes `clj-edge/`, every old path stops existing on the default
  branch — that is what a rename means. Any stale copy still sitting in the shared
  checkout at the old address therefore satisfies `:additive` perfectly, and gets
  committed, PR'd and merged as new work.

  Measured (`net-kotobase/control-plane`, 2026-08-12):

  | commit    | date  | what happened |
  |-----------|-------|---------------|
  | `04e1514` | 08-03 | `worker/` -> `kotobase-edge/`, clean renames, 48 -> 0 tracked |
  | `5a6a55b` | 08-04 | `clj-edge/` -> `kotobase-api-gateway-cljs/`, clean renames |
  | `8f7fbaf` | 08-05 | `cleanup: land untracked WIP (1 files)` — `worker/` 0 -> 1 |
  | `d96f18b` | 08-06 | `cleanup: land untracked WIP (16 files)` — `clj-edge/` 0 -> 14, `worker/` 1 -> 3 |

  Two cleanup passes resurrected 17 files under two directory names that had been
  deliberately renamed away, one and two days earlier. Sixteen of them were
  byte-identical to a revision that is still in the object database at that exact
  path; `clj-edge/src/kotobase/site_skin.cljc` matched the revision immediately
  *before* the commit that superseded its design, so the old doctrine came back a
  week after it was argued out. The seventeenth,
  `worker/src/edge-app.generated.mjs`, is a build artifact that `.gitignore`
  excludes at its live path (`kotobase-api-gateway/src/edge-app.generated.mjs`)
  and cannot exclude at its pre-rename address, because that address is not in
  the pattern. They were deleted again in `3c99bd5`.

  The files were the symptom. The cleanup pass is the bug, and without this check
  it does the same thing after the next rename.

  ## What separates residue from WIP

  Not the disk. On disk they are identical: untracked files with real content.
  What separates them is **history**, and history is exactly what `git status`
  does not consult.

  Three independent signals, cheapest first:

  1. **the path itself was a rename source** — git recorded `R<sim> P T` on a
     commit reachable from the base branch;
  2. **the content is already in the object database at that path** — the local
     blob hash equals the blob at `P` in some commit reachable from base;
  3. **a directory prefix of the path was moved away wholesale** — some ancestor
     directory `D` supplies rename sources and has zero live paths on base.

  Signal 3 is the one that catches the `.gitignore` case, because a generated file
  is in nobody's history: its bytes exist at neither address. What it *does* have
  is a dead parent directory, and following the rename chain forward
  (`worker/src/` -> `kotobase-edge/src/` -> `kotobase-api-gateway/src/`) lands on
  a live directory where `git check-ignore` says the file would be excluded. A
  file the repo has decided to ignore at its live address is not work in progress
  at its dead one.

  ## Why this excludes rather than refuses

  A cleanup pass that silently dropped files would be a worse bug than the one
  being fixed, so verdicts are split and only the provable half is excluded:

  - `:residue` — the bytes are **already in this repo's object database**, or the
    repo has declared it ignores them. Not landing them costs nothing and loses
    nothing; `git show <commit>:<path>` still returns them years from now. These
    are dropped from `:additive` and reported as a named skip class. Nothing is
    deleted from the working tree — that safety floor is untouched.
  - `:suspect` — the evidence says the path is dead but the *content* is not in
    history, so it may be a real edit someone made to a stale copy. These are
    demoted from `:additive` (auto-merge) to `:review` (draft PR, human decision).
    Same mechanism the tool already uses when a path turns out to exist on base
    with different content.
  - `:wip` — untouched.

  Under that split the incident is fully prevented: 16 of the 17 files are
  `:residue`, the 17th is `:residue` via the ignore probe, and nothing reaches
  `main`. See `scripts/rename-residue-test.cljs`.

  ## Cost

  One `ls-tree -r`, one bounded `log --diff-filter=R`, and one `hash-object` per
  candidate, per repo — and only for repos that already have untracked candidates
  after `drop-already-landed`. Measured on `net-kotobase/control-plane` (1,251
  commits): 0.41s for the unbounded rename log. The log is capped at
  `max-rename-commits` and truncation is reported rather than hidden, because a
  survey that silently stops looking is the failure mode this whole runbook keeps
  re-learning."
  (:require [clojure.string :as str]
            [clojure.java.shell :refer [sh]]))

;; ---------------------------------------------------------------- pure core

(def max-rename-commits
  "How far back to look for renames. A leftover whose move is older than this many
  commits is implausible, and an unbounded `git log -M` on the largest repos in
  this fleet is the one call here that could run long. Truncation is reported."
  20000)

(defn segments [path] (str/split path #"/"))

(defn ancestor-dirs
  "Directory prefixes of `path`, **longest first**. `a/b/c.txt` -> [\"a/b\" \"a\"].
  Longest first so the most specific renamed-away directory wins."
  [path]
  (let [segs (vec (segments path))]
    (->> (range (dec (count segs)) 0 -1)
         (map #(str/join "/" (subvec segs 0 %)))
         vec)))

(defn common-suffix-count
  "How many trailing path segments `a` and `b` share."
  [a b]
  (let [ra (reverse (segments a)) rb (reverse (segments b))]
    (count (take-while true? (map = ra rb)))))

(defn rename->dir-move
  "A rename pair `[src dst]` reduced to the directory move it implies, or nil.

  `worker/src/edge-app.cljc -> kotobase-edge/src/edge-app.cljc` shares the
  trailing two segments, so it says `worker -> kotobase-edge` and nothing about
  `src`. A rename that only changes the basename
  (`.../cacao.cljc -> .../edge_cacao.cljc`) shares nothing trailing and implies no
  directory move at all — returning nil there is what keeps a plain file rename
  from condemning its whole directory."
  [[src dst]]
  (let [n (common-suffix-count src dst)]
    (when (pos? n)
      (let [ss (segments src) ds (segments dst)
            sd (str/join "/" (drop-last n ss))
            dd (str/join "/" (drop-last n ds))]
        (when (and (seq sd) (seq dd) (not= sd dd))
          [sd dd])))))

(defn dir-move-map
  "All rename pairs folded into `src-dir -> {:to dst-dir :n count}`.

  When one source directory was split across several targets the most-attested
  target wins; ties break on the first seen. Splits are rare and the loser is
  still reported as `:suspect` rather than silently landed, so a wrong guess here
  degrades to a human decision instead of a bad merge."
  [pairs]
  (->> pairs
       (keep rename->dir-move)
       (reduce (fn [m [sd dd]] (update-in m [sd dd] (fnil inc 0))) {})
       (reduce-kv (fn [m sd targets]
                    (let [[dd n] (apply max-key val targets)]
                      (assoc m sd {:to dd :n n})))
                  {})))

(defn map-dir
  "Apply `dir-move-map` to `d` once, inheriting an ancestor's move when `d` itself
  has none.

  Inheritance is required, not a nicety. `worker/src/app.cljc ->
  edge/src/app.cljc` shares two trailing segments, so the move it records is
  `worker -> edge` — nothing is ever recorded for `worker/src` itself. Without
  inheritance the chain dead-ends on exactly the deep paths that matter, which is
  where the `.gitignore` leftover lived."
  [moves d]
  (or (some-> (get moves d) :to)
      (some (fn [a] (when-let [{:keys [to]} (get moves a)]
                      (str to (subs d (count a)))))
            (ancestor-dirs d))))

(defn resolve-live-dir
  "Follow `map-dir` forward from `d` until a directory that is live on the base
  branch is reached. Returns `{:live <dir> :hops [...]}` or nil.

  The chain matters. In the incident `worker/src/` had moved twice — once on
  08-03 and again on 08-04 — so one hop lands on `kotobase-edge/src/`, which is
  as dead as where it started. Stopping there would have missed the `.gitignore`
  evidence entirely. `limit` guards against a rename cycle."
  ([moves live-dir? d] (resolve-live-dir moves live-dir? d 10))
  ([moves live-dir? d limit]
   (loop [cur d hops [] n limit]
     (cond
       (live-dir? cur) {:live cur :hops hops}
       (zero? n)       nil
       :else (if-let [nxt (map-dir moves cur)]
               (if (some #{nxt} hops)      ; cycle
                 nil
                 (recur nxt (conj hops nxt) (dec n)))
               nil)))))

(defn classify
  "Decide one candidate from already-gathered facts. Pure, so the incident can be
  replayed as a fixture without a clone.

  `facts`:
    :path                 the untracked path
    :on-base?             path exists on the base branch tree
    :rename-source        {:commit :similarity :to} | nil — git recorded this
                          exact path as a rename source reachable from base
    :history-blob         {:commit} | nil — local content equals the blob at this
                          path in that reachable commit
    :dead-dir             the longest ancestor directory that supplies rename
                          sources and has no live paths on base | nil
    :mapped-path          where that directory's chain says the file now lives | nil
    :mapped-on-base?      that mapped path exists on the base tree
    :mapped-same-content? local content equals the base blob at the mapped path
    :mapped-ignored?      the base branch's ignore rules exclude the mapped path

  -> {:verdict :residue|:suspect|:wip :reason <kw> :path ... plus evidence}"
  [{:keys [path on-base? rename-source history-blob dead-dir mapped-path
           mapped-on-base? mapped-same-content? mapped-ignored?] :as facts}]
  (let [out (fn [verdict reason extra]
              (merge {:path path :verdict verdict :reason reason} extra))]
    (cond
      ;; Not this check's business: cleanup-land's drop-already-landed already
      ;; demotes same-path collisions to :review.
      on-base?
      (out :wip :exists-on-base {})

      ;; Strongest: git itself recorded the move, and these are the bytes from
      ;; before it. This is the 16-file half of the incident.
      (and rename-source history-blob)
      (out :residue :rename-source-restored
           {:renamed-to (:to rename-source)
            :similarity (:similarity rename-source)
            :rename-commit (:commit rename-source)
            :content-commit (:commit history-blob)})

      ;; The path was deleted rather than renamed, and this is an old revision of
      ;; it. Landing it reverts a deliberate deletion.
      history-blob
      (out :residue :deleted-path-restored
           {:content-commit (:commit history-blob)})

      ;; The parent directory moved and this file already exists, byte-identical,
      ;; at the live address. A duplicate at a dead address.
      (and dead-dir mapped-on-base? mapped-same-content?)
      (out :residue :duplicate-of-live-path
           {:dead-dir dead-dir :mapped-path mapped-path})

      ;; The parent directory moved and the repo ignores this file at its live
      ;; address. Build output cannot be work in progress just because .gitignore
      ;; names the new path and not the old one.
      (and dead-dir mapped-ignored?)
      (out :residue :ignored-artifact-at-dead-path
           {:dead-dir dead-dir :mapped-path mapped-path})

      ;; git recorded the move but the content is not in history: someone edited a
      ;; stale copy, or genuinely re-created the path. Not provable — hand it over.
      rename-source
      (out :suspect :rename-source-modified
           {:renamed-to (:to rename-source)
            :similarity (:similarity rename-source)
            :rename-commit (:commit rename-source)})

      ;; Lives at the live address too, but the bytes differ. Could be a real edit
      ;; that belongs at the *new* path; moving it is a human call.
      (and dead-dir mapped-on-base?)
      (out :suspect :edited-copy-at-dead-path
           {:dead-dir dead-dir :mapped-path mapped-path})

      dead-dir
      (out :suspect :under-renamed-away-directory
           {:dead-dir dead-dir :mapped-path mapped-path})

      :else
      (out :wip :new-path (select-keys facts [])))))

(def residue-explanation
  {:rename-source-restored
   "git recorded this exact path as a rename source; the bytes match a revision still in the object database"
   :deleted-path-restored
   "this path was deleted; the bytes match a revision still in the object database"
   :duplicate-of-live-path
   "the parent directory was moved; this file is byte-identical to the copy at the live path"
   :ignored-artifact-at-dead-path
   "the parent directory was moved; the repo's ignore rules exclude this file at its live path"
   :rename-source-modified
   "git recorded this exact path as a rename source, but the content is not in history"
   :edited-copy-at-dead-path
   "the parent directory was moved and a different copy is live; this may be an edit that belongs at the new path"
   :under-renamed-away-directory
   "the parent directory was moved away wholesale and has no live paths"})

;; ------------------------------------------------------------------ git side

(defn- git [dir & xs]
  (let [{:keys [out exit]} (apply sh "git" "-C" dir xs)]
    (when (zero? exit) out)))

(defn- lines [s] (remove str/blank? (str/split-lines (or s ""))))

(defn base-paths
  "Every blob path on the base branch, as a set."
  [dir baseref]
  (set (lines (git dir "ls-tree" "-r" "--name-only" baseref))))

(defn parse-rename-log
  "Parse `git log -M --diff-filter=R --name-status --format=%H` output into
  `[{:similarity :src :dst :commit} ...]`. Split out so the parser is testable
  without a repository."
  [out]
  (loop [ls (lines out) commit nil acc []]
    (if-let [l (first ls)]
      (if (re-matches #"[0-9a-f]{40}" l)
        (recur (rest ls) l acc)
        (let [[st src dst] (str/split l #"\t")]
          (if (and dst st (str/starts-with? st "R"))
            (recur (rest ls) commit
                   (conj acc {:similarity (parse-long (subs st 1))
                              :src src :dst dst :commit commit}))
            (recur (rest ls) commit acc))))
      acc)))

(defn rename-pairs
  "Every rename reachable from `baseref`. `-M` is explicit: `git log` only turns
  rename detection on by default under some configurations, and this check is
  worthless without it. `renames-truncated?` reports the cap rather than hiding
  it — a survey that silently stops looking is the failure mode this runbook
  keeps re-learning."
  [dir baseref]
  (let [rows (parse-rename-log
              (git dir "log" "-M" "--diff-filter=R" "--name-status" "--format=%H"
                   "-n" (str max-rename-commits) baseref))]
    {:pairs rows
     :rows (count rows)
     :truncated? (>= (count (distinct (map :commit rows))) max-rename-commits)}))

(defn- local-blob [dir path]
  (some-> (git dir "hash-object" "--" path) str/trim not-empty))

(defn- base-blob [dir baseref path]
  (some-> (git dir "rev-parse" (str baseref ":" path)) str/trim not-empty))

(defn parse-raw-log
  "Parse `git log --format=%H --raw --abbrev=40` into `[[commit post-image-blob] ...]`.

  `--raw` carries the post-image blob sha on the diff line itself, so one process
  answers what `rev-parse <commit>:<path>` would have needed one process per
  commit for. Measured on the incident replay: 10.1s -> 3.3s wall for 17
  candidates, and the gap widens linearly with history depth."
  [out]
  (loop [ls (lines out) commit nil acc []]
    (if-let [l (first ls)]
      (cond
        (re-matches #"[0-9a-f]{40}" l) (recur (rest ls) l acc)
        (str/starts-with? l ":")
        (let [post (nth (str/split (first (str/split l #"\t")) #"\s+") 3 nil)]
          (recur (rest ls) commit
                 (if (and post (not (re-matches #"0+" post)))
                   (conj acc [commit post]) acc)))
        :else (recur (rest ls) commit acc))
      acc)))

(defn- history-blob-match
  "The most recent commit reachable from `baseref` whose blob at `path` equals
  `blob`, or nil. `--no-renames` is deliberate: this asks only about this exact
  path. Bounded by `limit` commits touching the path — a leftover matching only a
  revision hundreds of commits deep is a coincidence, and the bound keeps the
  walk cheap."
  [dir baseref path blob limit]
  (when blob
    (->> (parse-raw-log (git dir "log" "--format=%H" "--raw" "--abbrev=40"
                             "--no-renames" "-n" (str limit) baseref "--" path))
         (some (fn [[c post]] (when (= blob post) {:commit c}))))))

(defn- ignored?
  "Does the repo's ignore configuration exclude `path`? `check-ignore` reads the
  working tree's `.gitignore`, which is the checkout being cleaned. That is the
  right file to ask — it is the same commit graph — but if the checkout is far
  behind, a rule added since would be missed. Missing it degrades the verdict to
  `:suspect`, never to a silent merge."
  [dir path]
  (zero? (:exit (sh "git" "-C" dir "check-ignore" "-q" "--no-index" "--" path))))

(defn scan
  "Classify `paths` (untracked candidates, repo-relative) in `dir` against
  `baseref` (e.g. `origin/main`). -> {:results [...] :truncated? bool :renames n}

  `opts`:
    :base-paths  the set of live paths, when the caller already knows it from a
                 more authoritative source than `baseref`.

  That override exists because of a measurement. west fetches into `refs/west/*`,
  so most child checkouts have no remote-tracking ref at all: 43 of 121
  `orgs/kotoba-lang/*` checkouts resolved `<remote>/main` (2026-08-12). Reading
  liveness out of a local ref that does not exist would make this gate inert in
  two thirds of the fleet. `cleanup-land.cljs` already fetches the default
  branch's tree from the GitHub API for `drop-already-landed`, so it passes that
  set here and the local ref is used only for the commit graph — which is the
  half a stale checkout still answers correctly."
  ([dir baseref paths] (scan dir baseref paths nil))
  ([dir baseref paths opts]
   (let [on-base (or (:base-paths opts) (base-paths dir baseref))
        {:keys [pairs truncated? rows]} (rename-pairs dir baseref)
        src-index (into {} (for [{:keys [similarity src dst commit]} pairs]
                             [src {:similarity similarity :to dst :commit commit}]))
        ;; A directory is "live" when the base tree still has any path under it.
        live-dirs (into #{} (mapcat (fn [p] (butlast (reductions
                                                      (fn [a b] (str a "/" b))
                                                      (segments p))))
                                    on-base))
        moves (dir-move-map (map (juxt :src :dst) pairs))
        ;; Only directories that actually supplied a rename source count as dead;
        ;; an empty directory that never moved is not evidence of anything.
        rename-src-dirs (into #{} (mapcat #(ancestor-dirs (:src %)) pairs))
        results
        (for [p paths]
          (let [dead-dir (first (filter #(and (rename-src-dirs %)
                                              (not (live-dirs %)))
                                        (ancestor-dirs p)))
                blob (local-blob dir p)
                resolved (when dead-dir (resolve-live-dir moves live-dirs dead-dir))
                mapped (when-let [live (:live resolved)]
                         (str live (subs p (count dead-dir))))]
            (classify
             {:path p
              :on-base? (contains? on-base p)
              :rename-source (get src-index p)
              :history-blob (history-blob-match dir baseref p blob 200)
              :dead-dir dead-dir
              :mapped-path mapped
              :mapped-on-base? (boolean (and mapped (contains? on-base mapped)))
              :mapped-same-content? (boolean (and mapped blob
                                                  (= blob (base-blob dir baseref mapped))))
              :mapped-ignored? (boolean (and mapped (ignored? dir mapped)))})))]
     {:results (vec results) :truncated? truncated? :renames rows})))

(defn resolve-baseref
  "The best local rev to read the commit graph from, and how it was reached.
  -> {:ref <rev> :how :remote|:head} or nil.

  Preference is `<remote>/<branch>`; the fallback is `HEAD`, and the fallback is
  sound here for a specific reason. An untracked leftover at a pre-rename path can
  only exist in a checkout that is *at or after* the rename — before it, those
  paths are tracked, not untracked. So HEAD, even parked on a stale pin, still
  contains the rename this gate is looking for."
  [dir remote branch]
  (let [ok? (fn [r] (zero? (:exit (sh "git" "-C" dir "rev-parse" "--verify" "-q"
                                      (str r "^{commit}")))))
        remote-ref (str remote "/" branch)]
    (cond (ok? remote-ref) {:ref remote-ref :how :remote}
          (ok? "HEAD")     {:ref "HEAD" :how :head})))

(defn split-verdicts
  "-> {:residue [...] :suspect [...] :wip [...]} keyed by verdict, values are rows."
  [results]
  (merge {:residue [] :suspect [] :wip []} (group-by :verdict results)))
