#!/usr/bin/env nbb
;; Disposition report for SHADOW CHECKOUTS: directories under `orgs/` that hold
;; a git checkout `manifest/west.yml` does not declare.
;;
;;   nbb scripts/shadow-checkout-disposition.cljs                  ; report
;;   nbb scripts/shadow-checkout-disposition.cljs --out FILE.edn   ; + datoms
;;   nbb scripts/shadow-checkout-disposition.cljs --no-du          ; skip disk cost
;;   nbb scripts/shadow-checkout-disposition.cljs --limit 20       ; smoke test
;;
;; Run from the superproject root. THIS SCRIPT NEVER WRITES TO A CHECKOUT.
;; Every git call it makes is a read: rev-parse, status, stash list, worktree
;; list, for-each-ref, rev-list --count. It never fetches -- and in particular
;; it never runs a `--depth` fetch, because a worktree shares `.git/shallow`
;; with its parent and this workspace has an observed case of one repository's
;; shallow fetch writing a graft into another's object store (ADR-2608124400).
;;
;; ## What a shadow checkout is
;;
;; Measured 2026-08-13 (ADR-2608134000): of 4,414 checkouts under `orgs/`, 249
;; are undeclared, and all 249 are the SAME GitHub repository already checked
;; out at a declared path. Zero true orphans. They come from renames and org
;; transfers: GitHub absorbs the rename with a redirect so nothing breaks, west
;; registers the new path, and the old checkout is left behind at whatever
;; commit it was on. `west update` never touches it again.
;;
;; They cost almost nothing at build time -- exactly one `:local/root` edge
;; across 3,493 `deps.edn` files lands in one. What they cost is MEASUREMENT:
;; every detector that walks `orgs/` on disk counts this code twice.
;;
;; ## Identity is the GitHub repository id
;;
;; Not the name. The name is precisely what lies here -- a shadow carries the
;; OLD slug in its remote while its declared twin carries the new one, so slug
;; matching finds 0 of 249. And keying on a bare name is how a detector on this
;; same subject reported `kotoba-lang/svgraph` and `com-junkawasaki/svgraph` --
;; two genuinely distinct repositories -- as one duplicate.
;;
;; ## ⚠ The verdict below is a GIT-STATUS verdict, and `git status` hides ignored state
;;
;; Every "holds nothing unique" judgement in this script comes from `git status`,
;; `stash list`, `for-each-ref` and `rev-list` -- all of which are silent about
;; gitignored paths, because hiding them is what gitignore is for. Measured
;; 2026-08-13 (ADR-2608138400): 4 of the 223 checkouts this script called
;; :safe-to-retire held gitignored content that exists in no commit and in no
;; twin, including an actor's Ed25519 identity in 157 bytes.
;;
;; **`:shadow/verdict` is therefore an upper bound on safety.** The corrected
;; three-way ranking lives in the `shadow-ignored` dataset written by
;; `scripts/shadow-ignored-content-survey.cljs`, which joins this one on
;; `:shadow/dir` and carries `:shadow/verdict-revised`. Do not retire on this
;; script's verdict alone.
;;
;; ## Three verdicts, and why the middle one exists
;;
;;   :safe-to-retire   nothing unique, no linked worktree, not shallow, every
;;                     local branch tip reachable from a remote-tracking ref
;;   :needs-draining   holds something that exists nowhere else -- the report
;;                     names exactly what (dirty / untracked / stash / branch)
;;   :cannot-assess    shallow, or the evidence could not be established
;;
;; **A tip-equality test is not a containment test.** Asking whether a local
;; branch tip EQUALS some remote head misses the ordinary case of a branch that
;; is simply behind, and calls it unique. The real question is whether the tip
;; is an ANCESTOR of something already published.
;;
;; Where that question gets asked is the one non-obvious part of this script.
;; The obvious anchor -- `git rev-list --count <tip> --not --remotes`, the
;; checkout's own remote-tracking refs -- **is empty in almost every west
;; checkout**, because west fetches a pinned revision into FETCH_HEAD and
;; points a synthetic `manifest-rev` branch at it rather than mirroring
;; `refs/heads/*`. Measured 2026-08-13: a typical shadow holds exactly one ref,
;; `refs/heads/manifest-rev`, and zero under `refs/remotes/`. Judged against
;; that anchor every shadow looks like it holds unique work, which is the
;; false-positive shape a tip-equality test also produces.
;;
;; So the anchor is the DECLARED TWIN's object store: the same repository, kept
;; current by `west update`. A tip reachable from any ref in the twin is
;; published, by this chain:
;;
;;   tip reachable in twin  ->  twin's HEAD is its west pin
;;                          ->  `verify-west-pins` admits a pin only if it is
;;                              reachable from the upstream default branch
;;                          ->  the tip is on GitHub.
;;
;; Both anchors are tried and both are recorded. Three honest limits, each
;; carried per checkout rather than hidden:
;;
;;   - **A shallow clone answers ancestry questions wrongly while looking
;;     authoritative** (ADR-2608124400). Shallow => :cannot-assess -- and that
;;     applies to the TWIN too, since the twin is where the ancestry question
;;     is asked. Note `git rev-parse --is-shallow-repository` returns true for
;;     an EMPTY `.git/shallow`, so the graft COUNT is the real signal; both are
;;     recorded.
;;   - The twin is a local mirror, so a tip absent from it is not proof of
;;     absence from GitHub -- it is recorded as `absent-from-twin` and lands in
;;     :needs-draining, the conservative direction.
;;   - `:shadow/fetch-age-days` says how stale the evidence is.
;;
;; HEAD is tested alongside the branches, because a detached HEAD at a commit
;; no branch names is exactly the state a branch scan would miss.
;;
;; ## Linked worktrees
;;
;; A checkout with a live or locked linked worktree cannot be removed without
;; breaking whoever owns it, so a worktree is by itself enough for
;; :needs-draining. `git worktree list --porcelain` always lists the checkout
;; itself first; only the rest are linked.
;;
;; ## Cost
;;
;; One `sh` invocation per checkout carrying ~10 git reads, rather than ~10
;; node spawns -- node spawn overhead is what made the previous disk scan 175 s
;; for 1,250 git subprocesses. `du -sk` is included by default and is cheap for
;; these trees (30 shadows measured in 0.7 s), but `--no-du` drops it.
;;
;; gh cost is one `--paginate` org listing per org (about 10 invocations for
;; 4,166 entries), cached to `.cache/org-listings.edn` for 7 days, plus one GET
;; per name absent from its listing, cached to the same 30-day
;; `.cache/duplicate-registrations.edn` that `verify-duplicate-registrations`
;; uses. **Budget exhaustion is not a clean run**: affected rows are UNRESOLVED,
;; they are printed, the summary says so, and the exit code is 2 -- distinct
;; from 0 (clean) and 1 (findings). `gh api rate_limit` does NOT report the
;; secondary limit, so backoff keys on the error text.

(ns shadow-checkout-disposition
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as child]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def argv (vec (drop 2 js/process.argv)))
(defn- flag? [f] (some #{f} argv))
(defn- opt [f d] (let [i (.indexOf argv f)] (if (neg? i) d (get argv (inc i) d))))

(def no-cache? (flag? "--no-cache"))
(def no-du? (flag? "--no-du"))
(def deps? (flag? "--deps"))
(def budget (js/parseInt (opt "--budget" "600") 10))
(def limit (when (flag? "--limit") (js/parseInt (opt "--limit" "0") 10)))
(def out-file (opt "--out" nil))
(def root (opt "--root" "."))

(def repo-cache-file ".cache/duplicate-registrations.edn")
(def listing-cache-file ".cache/org-listings.edn")
(def repo-ttl-ms (* 30 24 60 60 1000))
(def listing-ttl-ms (* 7 24 60 60 1000))

(defn- log! [& parts] (js/console.error (str "[shadow] " (str/join "" parts))))

(def api-calls (atom 0))
(def budget-hit (atom false))

;; ---------------------------------------------------------------- west.yml

(defn- parse-west
  "Project entries as {:name :remote :rev :path :repo-path}. The `remotes:`
  block reuses the `- name:` shape at the same indent, so a `path:` is required."
  [file]
  (let [lines (str/split-lines (str (fs/readFileSync file "utf8")))]
    (loop [ls lines cur nil acc []]
      (if (empty? ls)
        (filterv :path (if cur (conj acc cur) acc))
        (let [l (first ls)
              done (fn [] (if cur (conj acc cur) acc))]
          (cond
            (re-find #"^    - name: " l) (recur (rest ls) {:name (str/trim (subs l 12))} (done))
            (nil? cur) (recur (rest ls) cur acc)
            ;; `repo-path:` overrides the project name as the GitHub repo name;
            ;; 59 of 4,166 entries carry one. The west NAME is not the repo name.
            (re-find #"^      repo-path: " l) (recur (rest ls) (assoc cur :repo-path (str/trim (subs l 17))) acc)
            (re-find #"^      remote: " l) (recur (rest ls) (assoc cur :remote (str/trim (subs l 14))) acc)
            (re-find #"^      revision: " l) (recur (rest ls) (assoc cur :rev (str/trim (subs l 16))) acc)
            (re-find #"^      path: " l) (recur (rest ls) (assoc cur :path (str/trim (subs l 12))) acc)
            :else (recur (rest ls) cur acc)))))))

(defn- gh-name [e] (or (:repo-path e) (:name e)))

;; ------------------------------------------------------------------- cache

(defn- read-edn-cache [file ttl]
  (if no-cache?
    {}
    (try (let [m (edn/read-string (str (fs/readFileSync file "utf8")))
               now (.now js/Date)]
           (into {} (remove (fn [[_ v]] (> (- now (:at v 0)) ttl)) m)))
         (catch :default _ {}))))

(defn- write-edn-cache! [file m]
  (when-not no-cache?
    (try (fs/mkdirSync (path/dirname file) #js {:recursive true}) (catch :default _ nil))
    (try (fs/writeFileSync file (with-out-str (pr m))) (catch :default _ nil))))

;; --------------------------------------------------------------------- gh

(defn- gh
  "{:out s} or {:error s}. Never a bare nil: a nil cannot distinguish `no` from
  `gh never answered`, and collapsing those is how a verifier reports a clean
  fleet during a rate limit."
  [args]
  (swap! api-calls inc)
  (try {:out (str/trim (str (child/execSync (str "gh api " args)
                                            #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)
                                                 :stdio #js ["ignore" "pipe" "pipe"]})))}
       (catch :default e
         {:error (or (some-> (.-stderr e) str str/trim str/split-lines first)
                     (str/trim (str (.-message e))))})))

(defn- rate-limited? [msg]
  (boolean (some #(str/includes? (str/lower-case (or msg "")) %)
                 ["rate limit" "secondary" "abuse detection" "403"])))

(defn- gh-retry [args]
  (loop [attempt 0]
    (let [r (gh args)]
      (cond
        (:out r) r
        (and (rate-limited? (:error r)) (< attempt 4))
        (let [wait (* 15 (inc attempt))]
          (log! "rate limited, sleeping " wait "s (" (inc attempt) "/4): " (:error r))
          (try (child/execSync (str "sleep " wait)) (catch :default _ nil))
          (recur (inc attempt)))
        :else r))))

(defn- org-listing
  "name -> id for every repo that CURRENTLY exists under `org`.
  `select(.owner.login == org)` is load-bearing, not defensive: the `users/`
  fallback returns MEMBER repos from other orgs too."
  [org]
  (let [q (fn [kind]
            (gh-retry (str "\"" kind "/" org "/repos?per_page=100&type=all\" --paginate "
                           "--jq '.[] | select(.owner.login == \"" org "\") "
                           "| [.name, (.id|tostring), .full_name] | @tsv'")))
        ;; `com-junkawasaki` is a USER, so `orgs/.../repos` 404s.
        r (let [a (q "orgs")] (if (:error a) (q "users") a))]
    (if (:error r)
      {:error (:error r)}
      {:repos (into {} (keep (fn [line]
                               (let [[n id full] (str/split line #"\t")]
                                 (when (and n id) [n {:id id :full-name full}]))))
                    (str/split-lines (:out r)))})))

(defn- resolve-slug [slug]
  (if (>= @api-calls budget)
    (do (reset! budget-hit true) {:error (str "budget " budget " exhausted")})
    (let [r (gh-retry (str "repos/" slug " --jq '[(.id|tostring), .full_name] | @tsv'"))]
      (if (:error r) r
          (let [[id full] (str/split (:out r) #"\t")]
            (if id {:id id :full-name full} {:error (str "unparseable: " (:out r))}))))))

;; ------------------------------------------------------------ git evidence

(def marker "@@")

(defn- probe-script
  "One `sh` invocation carrying every read we need from one checkout.
  Deliberately compound: node spawn overhead, not git, dominated the previous
  disk scan. Every command here is read-only."
  [dir du?]
  (str "cd " (js/JSON.stringify dir) " 2>/dev/null || exit 9\n"
       "echo '@@DOTGIT'; if [ -f .git ]; then echo file; elif [ -d .git ]; then echo dir; else echo none; fi\n"
       "echo '@@HEAD'; git rev-parse HEAD 2>/dev/null\n"
       "echo '@@BRANCH'; git rev-parse --abbrev-ref HEAD 2>/dev/null\n"
       "echo '@@SHALLOW'; git rev-parse --is-shallow-repository 2>/dev/null\n"
       "echo '@@GRAFTS'; if [ -f .git/shallow ]; then wc -l < .git/shallow; else echo 0; fi\n"
       "echo '@@DIRTY'; git status --porcelain=v1 --untracked-files=normal 2>/dev/null | head -400\n"
       "echo '@@STASH'; git stash list 2>/dev/null\n"
       "echo '@@WT'; git worktree list --porcelain 2>/dev/null\n"
       "echo '@@WTLOCK'\n"
       "for w in .git/worktrees/*/; do\n"
       "  [ -d \"$w\" ] || continue\n"
       "  s=unlocked\n"
       "  [ -f \"${w}locked\" ] && s=LOCKED\n"
       "  g=$(cat \"${w}gitdir\" 2>/dev/null)\n"
       "  echo \"$w $s $g\"\n"
       "done\n"
       "echo '@@REMOTES'; git remote -v 2>/dev/null | grep fetch\n"
       "echo '@@NREMOTEREFS'; git for-each-ref refs/remotes --format='x' 2>/dev/null | wc -l\n"
       "echo '@@BRANCHES'; git for-each-ref refs/heads --format='%(refname:short) %(objectname)' 2>/dev/null\n"
       ;; Containment against this checkout's OWN remote-tracking refs. Usually
       ;; vacuous in a west checkout (no `refs/remotes/*` at all), which is why
       ;; the twin probe below exists; kept because when it IS populated it is
       ;; the cheapest correct answer.
       "echo '@@SELFCONTAIN'; git for-each-ref refs/heads --format='%(refname:short)' 2>/dev/null | "
       "while read -r b; do n=$(git rev-list --count \"$b\" --not --remotes 2>/dev/null); "
       "echo \"$b ${n:-?}\"; done\n"
       (if du? "echo '@@DU'; du -sk . 2>/dev/null | cut -f1\n" "echo '@@DU'\n")
       "echo '@@END'\n"))

(defn- twin-probe-script
  "Asks the DECLARED TWIN whether it already has each of the shadow's tips.
  `rev-list --count <tip> --not --all` counts commits reachable from the tip
  and from no ref in the twin: 0 means the twin already publishes every commit
  the shadow's tip names. Read-only. Also reports the twin's own shallow state,
  because that is the repository whose ancestry answer we are trusting."
  [twin-dir tips]
  (str "cd " (js/JSON.stringify twin-dir) " 2>/dev/null || exit 9\n"
       "echo '@@TWINSHALLOW'; git rev-parse --is-shallow-repository 2>/dev/null\n"
       "echo '@@TWINGRAFTS'; if [ -f .git/shallow ]; then wc -l < .git/shallow; else echo 0; fi\n"
       "echo '@@CONTAIN'\n"
       "for tip in " (str/join " " tips) "; do\n"
       "  if git cat-file -e \"$tip^{commit}\" 2>/dev/null; then\n"
       "    n=$(git rev-list --count \"$tip\" --not --all 2>/dev/null)\n"
       "    echo \"$tip ${n:-?}\"\n"
       "  else\n"
       "    echo \"$tip absent\"\n"
       "  fi\n"
       "done\n"
       "echo '@@END'\n"))

(defn- run-sh
  "Runs one compound read-only shell script and splits its output on the
  `@@SECTION` markers into {:section [lines]}."
  [script]
  (try
    (let [out (str (child/execSync script
                                   #js {:encoding "utf8" :shell "/bin/sh"
                                        :maxBuffer (* 32 1024 1024)
                                        :stdio #js ["ignore" "pipe" "ignore"]}))]
      (loop [ls (str/split-lines out) key nil acc {}]
        (if (empty? ls)
          acc
          (let [l (first ls)]
            (if (str/starts-with? l marker)
              (recur (rest ls) (keyword (str/lower-case (subs l 2))) acc)
              (recur (rest ls) key (if key (update acc key (fnil conj []) l) acc)))))))
    (catch :default e {:probe-error [(str/trim (str (.-message e)))]})))

(defn- nonblank [xs] (vec (remove str/blank? (or xs []))))

(defn- slug-from-remotes
  "owner/name for the checkout's best GitHub remote.
  NOT `origin` alone -- west names each remote after the ORG, so a west-managed
  checkout often has no `origin`, and asking only for it left 182 of 249 with
  `remote unknown`. And not the first remote either: alphabetical order picks a
  git-annex `b2` remote, or `ai-gftd-apps-gftdcojp`'s `kotoba-upstream`, which
  points at an entirely different repository."
  [remote-lines org]
  (let [pairs (keep (fn [l]
                      (let [[n u] (str/split (str/trim l) #"\s+")]
                        (when (and n u) [n u])))
                    (nonblank remote-lines))
        github? (fn [[_ u]] (re-find #"github\.com[:/]" u))
        pick (or (first (filter (fn [[n _ :as p]] (and (= n "origin") (github? p))) pairs))
                 (first (filter (fn [[n _ :as p]] (and (= n org) (github? p))) pairs))
                 (first (filter github? pairs)))]
    (when pick
      (-> (second pick)
          (str/replace #"^.*github\.com[:/]" "")
          (str/replace #"\.git$" "") (str/replace #"/$" "")))))

;; ------------------------------------------------------------- path readers

;; Generated inventories that describe EVERY repo path in the workspace. They
;; mention a shadow the way a census mentions a house: retiring the checkout
;; makes their row wrong, not their caller broken. `manifest/repos.edn` belongs
;; here for a sharper reason -- it already records 192 of the 249 as
;; `:path-overrides` old -> new, so its mentions are the rename ledger itself.
(def descriptive-catalogs
  #{"manifest/repo-taxonomy.edn" "manifest/repo-maturity.edn" "manifest/repos.edn"
    "manifest/kotoba-boundaries.edn"})

(def reader-roots
  ["manifest" "scripts" ".claude" "70-tools"
   (path/join (os/homedir) ".gftd")
   (path/join (os/homedir) "Library/LaunchAgents")])

;; `.claude/worktrees` holds dozens of whole copies of this superproject; left
;; in, it turned 710 hits into 18,805 and buried every real one.
(def reader-skip-dirs
  #{".git" "node_modules" ".cache" "target" "dist" "build" ".shadow-cljs"
    ".projection-cache" "worktrees" "hayari-data" "hayari-mirror" "hyakka-archive"
    "mesh" "fleet-ci-cache" "logs" "kotoba-lang" "m365-archive" "cache"
    "venv" ".venv" "__pycache__"})

(def reader-exts
  #{".cljs" ".cljc" ".clj" ".edn" ".json" ".yml" ".yaml" ".plist" ".toml"
    ".md" ".txt" ".js" ".mjs" ".ts" ".conf" ".ini" ""})

(defn- scan-readers
  "Files that resolve a path INTO a shadow checkout. Matches `orgs/<org>/<name>`
  and `../<org>/<name>` with a right boundary, because a bare substring match
  reads `cloud-itonami/dougaka` inside `cloud-itonami/dougaka-actor`.

  Returns {shadow-dir [{:file :line :ctx :descriptive?}]}. A path something
  reads is not merely stale: retiring it breaks a caller."
  [shadow-dirs]
  (let [by-pair (into {} (map (fn [d] [(str/join "/" (rest (str/split d #"/"))) d])) shadow-dirs)
        re (js/RegExp. "(?:orgs/|\\.\\./)([a-z0-9-]+/[A-Za-z0-9_.-]+)" "g")
        home (os/homedir)
        acc (atom {})
        scanned (atom 0)]
    (letfn [(walk [dir depth]
              (when (< depth 9)
                (doseq [e (try (js->clj (fs/readdirSync dir #js {:withFileTypes true})) (catch :default _ []))]
                  (let [f (path/join dir (.-name e))]
                    (cond
                      (.isDirectory e) (when-not (reader-skip-dirs (.-name e)) (walk f (inc depth)))
                      (.isFile e)
                      (when (reader-exts (path/extname (.-name e)))
                        (let [size (try (.-size (fs/statSync f)) (catch :default _ 0))]
                          (when (< size (* 4 1024 1024))
                            (when-let [s (try (str (fs/readFileSync f "utf8")) (catch :default _ nil))]
                              (when-not (str/includes? s (js/String.fromCharCode 0))
                                (swap! scanned inc)
                                (let [lines (str/split-lines s)
                                      rel (str/replace f home "~")]
                                  (set! (.-lastIndex re) 0)
                                  (loop []
                                    (when-let [m (.exec re s)]
                                      (when-let [target (get by-pair (aget m 1))]
                                        (let [line (count (str/split-lines (subs s 0 (.-index m))))]
                                          (swap! acc update target (fnil conj [])
                                                 {:file rel :line line
                                                  :ctx (str/trim (subs (or (nth lines (dec line) "") "")
                                                                       0 (min 200 (count (or (nth lines (dec line) "") "")))))
                                                  :descriptive? (boolean (descriptive-catalogs rel))})))
                                      (recur)))))))))
                      :else nil)))))]
      (doseq [r reader-roots] (walk r 0)))
    (log! "reader scan: " @scanned " files read, "
          (count @acc) " shadow path(s) referenced")
    @acc))

;; --------------------------------------------------------- :local/root edges

(defn- deps-edges
  "`:local/root` edges across every `deps.edn` under `orgs/` whose RESOLVED
  target is a shadow. Answers `does retiring this break a build`, which the
  reader scan above cannot -- it walks the superproject and `~`, not the 4,414
  checkouts.

  Uses `rg` because `find`/`grep -r` over `orgs/` times out on this machine.
  Separate from the verdict on purpose: measured 2026-08-13, the only consumer
  is ITSELF a shadow, so the edge cannot change the disposition of anything
  west declares -- it just means the two must retire together."
  [shadow-dirs]
  (let [shadows (set shadow-dirs)
        out (try (str (child/execSync
                       (str "rg --no-heading --line-number --glob 'orgs/**/deps.edn' "
                            "--glob '!**/.git/**' ':local/root\\s+\"([^\"]+)\"' -o -r '$1' orgs")
                       #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)
                            :stdio #js ["ignore" "pipe" "ignore"] :cwd root}))
                 (catch :default e (str (some-> (.-stdout e) str))))
        lines (nonblank (str/split-lines (or out "")))
        parsed (keep (fn [l]
                       (when-let [m (re-find #"^(.*deps\.edn):(\d+):(.*)$" l)]
                         (let [[_ file line v] m]
                           (when (str/starts-with? v ".")
                             {:file file :line line
                              :target (path/normalize (path/join (path/dirname file) v))}))))
                     lines)]
    {:total (count lines)
     :into-shadow (vec (filter #(shadows (:target %)) parsed))}))

;; ---------------------------------------------------------------- disk scan

(defn- disk-checkouts []
  (->> (try (js->clj (fs/readdirSync (path/join root "orgs") #js {:withFileTypes true})) (catch :default _ []))
       (filter #(.isDirectory %))
       (mapcat (fn [org]
                 (let [o (.-name org)]
                   (->> (try (js->clj (fs/readdirSync (path/join root "orgs" o) #js {:withFileTypes true}))
                             (catch :default _ []))
                        (filter #(.isDirectory %))
                        (map #(str "orgs/" o "/" (.-name %)))))))
       (filter #(fs/existsSync (path/join root % ".git")))
       vec))

;; ---------------------------------------------------------------- verdicts

(defn- classify
  "Three verdicts. :cannot-assess wins over :needs-draining because a shallow
  clone's ancestry answer is wrong AND authoritative-looking; it must never
  read as a clean bill. The unique-content evidence is still printed for those
  rows -- the verdict says the ancestry question is unanswered, not that there
  is nothing there.

  A live reader demotes out of :safe-to-retire. It is not unique CONTENT, but
  it is something that has to be drained -- repointed -- before the directory
  can go, which is what :needs-draining means operationally."
  [r]
  (cond
    (seq (:blocked r)) :cannot-assess
    (:shallow? r) :cannot-assess
    (or (seq (:unique-reasons r)) (seq (:live-readers r))) :needs-draining
    :else :safe-to-retire))

;; ------------------------------------------------------------------- main

(defn -main []
  (let [t0 (.now js/Date)
        load1 (first (js->clj (os/loadavg)))
        entries (parse-west (path/join root "manifest/west.yml"))
        by-org (group-by :remote entries)
        _ (log! (count entries) " west entries across " (count by-org) " orgs; load1="
                (.toFixed load1 1))

        ;; ---- ids for declared entries
        listing-cache (read-edn-cache listing-cache-file listing-ttl-ms)
        listings (into {}
                       (map (fn [[org es]]
                              (if-let [hit (get listing-cache org)]
                                (do (log! "org " org " (" (count es) " entries) cached: "
                                          (count (:repos hit)) " repos")
                                    [org hit])
                                (do (log! "listing org " org " (" (count es) " entries) ...")
                                    (let [r (org-listing org)]
                                      (log! "  " org ": " (if (:error r) (str "FAILED " (:error r))
                                                              (str (count (:repos r)) " repos")))
                                      [org (assoc r :at (.now js/Date))])))))
                       (sort-by first by-org))
        _ (write-edn-cache! listing-cache-file listings)

        repo-cache-m (atom (read-edn-cache repo-cache-file repo-ttl-ms))
        lookup! (fn [slug]
                  (if-let [hit (get @repo-cache-m slug)]
                    hit
                    (let [r (resolve-slug slug)]
                      (swap! repo-cache-m assoc slug (assoc r :at (.now js/Date)))
                      r)))
        entry-id (fn [e]
                   (let [n (gh-name e)
                         live (get-in listings [(:remote e) :repos n])]
                     (or live (lookup! (str (:remote e) "/" n)))))
        declared (mapv (fn [e] (assoc e :id (:id (entry-id e)))) entries)
        declared-by-id (into {} (keep (fn [e] (when (:id e) [(:id e) e]))) declared)
        _ (log! "declared entries with an id: " (count declared-by-id))

        ;; ---- shadows
        declared-paths (set (map :path entries))
        dirs (disk-checkouts)
        orphans (cond->> (sort (remove declared-paths dirs))
                  limit (take limit)
                  true vec)
        _ (log! "disk: " (count dirs) " checkouts, " (count orphans) " undeclared")
        readers (scan-readers orphans)
        deps (when deps? (deps-edges orphans))
        _ (when deps (log! ":local/root edges: " (:total deps) " total, "
                           (count (:into-shadow deps)) " land in a shadow"))
        _ (log! "probing " (count orphans) " shadow checkouts")

        rows
        (vec
         (for [[i d] (map-indexed vector orphans)]
           (let [org (second (str/split d #"/"))
                 p (run-sh (probe-script (path/join root d) (not no-du?)))
                 _ (when (zero? (mod i 25))
                     (log! "  probe " i "/" (count orphans) " " d))
                 blocked (cond-> []
                           (seq (:probe-error p)) (conj (str "probe failed: " (first (:probe-error p)))))
                 dotgit (first (nonblank (:dotgit p)))
                 head (first (nonblank (:head p)))
                 shallow-flag (= "true" (first (nonblank (:shallow p))))
                 grafts (js/parseInt (or (first (nonblank (:grafts p))) "0") 10)
                 dirty-lines (nonblank (:dirty p))
                 modified (vec (remove #(str/starts-with? % "??") dirty-lines))
                 untracked (vec (filter #(str/starts-with? % "??") dirty-lines))
                 stashes (nonblank (:stash p))
                 wt-lines (nonblank (:wt p))
                 worktree-paths (->> wt-lines
                                     (filter #(str/starts-with? % "worktree "))
                                     (mapv #(subs % 9)))
                 linked (vec (rest worktree-paths))
                 locks (nonblank (:wtlock p))
                 locked (vec (filter #(str/includes? % "LOCKED") locks))
                 slug (slug-from-remotes (:remotes p) org)
                 n-remote-refs (js/parseInt (or (first (nonblank (:nremoterefs p))) "0") 10)
                 branches (nonblank (:branches p))
                 branch-tips (into {} (keep (fn [l]
                                              (let [[b sha] (str/split (str/trim l) #"\s+")]
                                                (when (and b sha) [b sha]))))
                                   branches)
                 ;; Containment against this checkout's own remote-tracking
                 ;; refs; only meaningful when there are any.
                 self-contained (when (pos? n-remote-refs)
                                  (into {} (keep (fn [l]
                                                   (let [[b n] (str/split (str/trim l) #"\s+")]
                                                     (when (and b n) [b n]))))
                                        (nonblank (:selfcontain p))))
                 ;; How stale the `refs/remotes/*` we judge containment against
                 ;; actually are. Read from node rather than spawning python per
                 ;; checkout.
                 fetch-age (try (let [st (fs/statSync (path/join root d ".git/FETCH_HEAD"))]
                                  (js/Math.floor (/ (- (.now js/Date) (.getTime (.-mtime st)))
                                                    86400000)))
                                (catch :default _ nil))
                 du-kb (some-> (first (nonblank (:du p))) (js/parseInt 10))
                 id (when slug (:id (lookup! slug)))
                 twin (get declared-by-id id)
                 ;; Every tip we must account for. HEAD is included because a
                 ;; detached HEAD at a commit no branch names is exactly what a
                 ;; branch-only scan misses.
                 tips (vec (distinct (remove nil? (cons head (vals branch-tips)))))
                 tp (when (and twin (seq tips))
                      (run-sh (twin-probe-script (path/join root (:path twin)) tips)))
                 twin-shallow? (when tp (or (= "true" (first (nonblank (:twinshallow tp))))
                                            (pos? (js/parseInt (or (first (nonblank (:twingrafts tp))) "0") 10))))
                 twin-grafts (if tp (js/parseInt (or (first (nonblank (:twingrafts tp))) "0") 10) 0)
                 contain (into {} (keep (fn [l]
                                          (let [[sha n] (str/split (str/trim l) #"\s+")]
                                            (when (and sha n) [sha n]))))
                               (nonblank (:contain tp)))
                 twin-head (when twin
                             (try (str/trim (str (child/execSync
                                                  (str "git -C " (js/JSON.stringify (path/join root (:path twin)))
                                                       " rev-parse HEAD")
                                                  #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]})))
                                  (catch :default _ nil)))
                 ;; Per-tip verdict: contained (by either anchor), unique (with
                 ;; the commit count), or unverifiable.
                 tip-verdict
                 (fn [label sha]
                   (let [self (get self-contained label)
                         tw (get contain sha)]
                     (cond
                       (= self "0") {:state :contained :via "own remote-tracking refs"}
                       (= tw "0") {:state :contained :via "declared twin"}
                       (and tw (not= tw "absent") (not= tw "?"))
                       {:state :unique :detail (str label "@" (subs sha 0 8) " +" tw " commit(s) in no twin ref")}
                       (= tw "absent") {:state :unique :detail (str label "@" (subs sha 0 8) " object absent from twin")}
                       (nil? twin) {:state :unverifiable :detail (str label ": no twin to compare against")}
                       :else {:state :unverifiable :detail (str label "@" (subs sha 0 8) ": rev-list gave no answer")})))
                 tip-results (vec (concat
                                   (when head [(assoc (tip-verdict "HEAD" head) :label "HEAD")])
                                   (for [[b sha] (sort branch-tips)]
                                     (assoc (tip-verdict b sha) :label b))))
                 unique-tips (vec (keep :detail (filter #(= :unique (:state %)) tip-results)))
                 unverifiable-tips (vec (keep :detail (filter #(= :unverifiable (:state %)) tip-results)))
                 blocked (cond-> blocked
                           (nil? head) (conj "no HEAD (empty or broken checkout)")
                           (nil? slug) (conj "no GitHub remote -- identity unestablished")
                           (and slug (nil? id)) (conj (str "id unresolved for " slug))
                           (and id (nil? twin)) (conj (str "id " id " matches no declared west entry"))
                           twin-shallow? (conj (str "twin " (:path twin) " is SHALLOW ("
                                                    twin-grafts " grafts) -- its ancestry answer is unreliable"))
                           (seq unverifiable-tips)
                           (conj (str "containment unverifiable: " (str/join "; " unverifiable-tips))))
                 unique-reasons (cond-> []
                                  (seq modified) (conj (str (count modified) " uncommitted modification(s)"))
                                  (seq untracked) (conj (str (count untracked) " untracked path(s)"))
                                  (seq stashes) (conj (str (count stashes) " stash(es)"))
                                  (seq linked) (conj (str (count linked) " linked worktree(s)"
                                                          (when (seq locked) (str ", " (count locked) " LOCKED"))))
                                  (seq unique-tips) (conj (str (count unique-tips) " unpublished tip(s): "
                                                               (str/join ", " unique-tips))))
                 all-readers (get readers d [])
                 live-readers (vec (remove :descriptive? all-readers))
                 row {:dir d :org org :slug slug :id id
                      :readers all-readers :live-readers live-readers
                      :dotgit dotgit :head head :twin (:path twin) :twin-name (:name twin)
                      :twin-head twin-head
                      :same-head (and head twin-head (= head twin-head))
                      :shallow? (or shallow-flag (pos? grafts)) :grafts grafts
                      :twin-shallow? (boolean twin-shallow?) :twin-grafts twin-grafts
                      :modified modified :untracked untracked :stashes stashes
                      :linked-worktrees linked :locked-worktrees locked
                      :branches (count branches) :unique-branches unique-tips
                      :remote-refs n-remote-refs :fetch-age-days fetch-age
                      :du-kb du-kb :blocked blocked :unique-reasons unique-reasons}]
             (assoc row :verdict (classify row)))))

        _ (write-edn-cache! repo-cache-file @repo-cache-m)
        by-verdict (group-by :verdict rows)
        recoverable (reduce + 0 (keep :du-kb (:safe-to-retire by-verdict)))
        total-kb (reduce + 0 (keep :du-kb rows))
        elapsed (/ (- (.now js/Date) t0) 1000.0)]

    (println (str "SCANNED\t" (count dirs) "\tcheckouts\t" (count orphans) "\tundeclared"))
    (println (str "gh-invocations\t" @api-calls "\tbudget\t" budget
                  "\telapsed-s\t" (.toFixed elapsed 1) "\tload1\t" (.toFixed load1 1)))
    (println (str "safe-to-retire\t" (count (:safe-to-retire by-verdict))
                  "\tneeds-draining\t" (count (:needs-draining by-verdict))
                  "\tcannot-assess\t" (count (:cannot-assess by-verdict))))
    (println (str "disk-kb-total\t" total-kb "\tdisk-kb-recoverable-if-safe-only\t" recoverable))
    (println)
    (doseq [v [:needs-draining :cannot-assess :safe-to-retire]
            :let [rs (sort-by (fn [r] (- (or (:du-kb r) 0))) (get by-verdict v))]]
      (println (str "== " (name v) " (" (count rs) ") =="))
      (doseq [r rs]
        (println (str "  " (:dir r)
                      "  id=" (or (:id r) "?")
                      "  twin=" (or (:twin r) "?")
                      (if (:same-head r) "  same-head" "  DIVERGENT")
                      "  du=" (if (:du-kb r) (str (:du-kb r) "K") "?")
                      (when (:shallow? r) (str "  SHALLOW(" (:grafts r) " grafts)"))
                      (when (seq (:unique-reasons r)) (str "  unique: " (str/join "; " (:unique-reasons r))))
                      (when (seq (:live-readers r))
                        (str "  READ-BY: " (str/join ", " (map #(str (:file %) ":" (:line %))
                                                               (:live-readers r)))))
                      (when (seq (:blocked r)) (str "  blocked: " (str/join "; " (:blocked r)))))))
      (println))

    (when deps
      (println (str "== :local/root edges into a shadow (" (count (:into-shadow deps))
                    " of " (:total deps) " total) =="))
      (doseq [e (:into-shadow deps)]
        (println (str "  " (:file e) ":" (:line e) "  ->  " (:target e))))
      (println))

    (let [read-rows (filter #(seq (:live-readers %)) rows)]
      (println (str "== paths something READS (" (count read-rows) ") =="))
      (doseq [r (sort-by :dir read-rows)]
        (println (str "  " (:dir r)))
        (doseq [h (:live-readers r)]
          (println (str "      " (:file h) ":" (:line h) "  " (:ctx h)))))
      (println))

    (when @budget-hit
      (println (str "BUDGET-EXHAUSTED after " budget " gh invocations -- this run is NOT clean."))
      (println))

    (when out-file
      (let [datoms (vec (map-indexed
                         (fn [i r]
                           {:db/id (- (inc i))
                            :source/dataset "shadow-checkouts"
                            :shadow/dir (:dir r)
                            :shadow/org (:org r)
                            :shadow/slug (or (:slug r) "")
                            :shadow/repo-id (or (:id r) "")
                            :shadow/twin-path (or (:twin r) "")
                            :shadow/twin-entry (or (:twin-name r) "")
                            :shadow/head (or (:head r) "")
                            :shadow/twin-head (or (:twin-head r) "")
                            :shadow/same-head (boolean (:same-head r))
                            :shadow/verdict (name (:verdict r))
                            :shadow/shallow (boolean (:shallow? r))
                            :shadow/grafts (:grafts r)
                            :shadow/twin-shallow (boolean (:twin-shallow? r))
                            :shadow/twin-grafts (:twin-grafts r)
                            :shadow/modified-count (count (:modified r))
                            :shadow/untracked-count (count (:untracked r))
                            :shadow/stash-count (count (:stashes r))
                            :shadow/linked-worktrees (str/join " | " (:linked-worktrees r))
                            :shadow/locked-worktrees (str/join " | " (:locked-worktrees r))
                            :shadow/branch-count (:branches r)
                            :shadow/unique-branches (str/join " | " (:unique-branches r))
                            :shadow/remote-ref-count (:remote-refs r)
                            :shadow/fetch-age-days (or (:fetch-age-days r) -1)
                            :shadow/disk-kb (or (:du-kb r) -1)
                            :shadow/unique-reasons (str/join " | " (:unique-reasons r))
                            :shadow/blocked (str/join " | " (:blocked r))
                            :shadow/read-by (str/join " | " (map #(str (:file %) ":" (:line %))
                                                                 (:live-readers r)))
                            :shadow/described-by (str/join " | " (distinct (map :file (filter :descriptive? (:readers r)))))})
                         (sort-by :dir rows)))]
        (fs/writeFileSync out-file (str (pr-str datoms) "\n"))
        (log! "wrote " (count datoms) " datoms to " out-file)))

    (cond
      @budget-hit (do (log! "exit 2: evidence incomplete") (set! (.-exitCode js/process) 2))
      (seq (:cannot-assess by-verdict))
      (do (log! "exit 2: " (count (:cannot-assess by-verdict)) " row(s) could not be assessed")
          (set! (.-exitCode js/process) 2))
      :else (log! "done in " (.toFixed elapsed 1) "s, " @api-calls " gh invocations"))))

(-main)
