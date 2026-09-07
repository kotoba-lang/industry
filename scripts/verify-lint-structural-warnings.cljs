#!/usr/bin/env nbb
(ns verify-lint-structural-warnings
  "Two clj-kondo linters that nobody runs, both of which say `the shape of
  this form is not the shape you meant`.

  ## The failure this exists for

  Every registered repository here carries the same lint alias -- clj-kondo
  2024.11.14, `--lint src test --fail-level error`. Measured 2026-09-06:
  3,122 of the 4,331 paths `manifest/west.yml` pins declare it. **Nothing
  runs it.** GitHub Actions was retired fleet-wide (ADR-2607300900) and the
  301 entries in `scripts/fleet-ci/gates.edn` contain no clj-kondo gate --
  the one entry NAMED `lint` is `nbb-cross-runtime-lint`, which exercises the
  `lint` library on a second runtime and never invokes a linter.

  ADR-2607091500 (accepted, 2026-07-09) fixed that alias shape AND declared
  lint a required CI job. The later ADR retired the thing that ran it. The
  enforcement clause was never re-homed, so the alias has been inert ever
  since -- present in three thousand `deps.edn` files, run in none.

  The consequence that matters is not the volume. It is two linter ids.

  ## Why only two ids, and why these two

  `--fail-level error` is the second half of the defect. Both ids below are
  `:warning` by default, so even on the day the alias last ran, every one of
  these exited 0. A mechanism that treats them as findings is the only thing
  that sees them.

  **`:missing-else-branch`** -- 9 sites in 6 files, measured by the first
  full sweep below rather than taken from the brief that asked for this. Two
  of the six had already been diagnosed by hand, at real cost:

  - `network-awai/local-murakumo` `src/local_murakumo/worker.cljs:1546` --
    the `if` lost its else, ClojureScript compiled it as a `when`, and every
    non-streaming `/v1/messages` resolved to nil, which the live Worker
    answered as `500 RangeError`. Fixed by hand on 2026-09-01 in `d7225b7`,
    while clj-kondo had been able to print `Missing else branch.` at it the
    whole time. It is absent from the sweep, which is the fix showing up.
  - `kotoba-lang/ayatori` `src/ayatori/remote.cljc:457` -- a nil block source
    cost TWO discoveries and two fetches where none costs one, and a
    rejecting source killed the Node process with an unhandled rejection.
    RESOLVED 2026-09-06, and how it resolved is the lesson. The defect was
    real: the `if-not` opened at 457:37 and CLOSED at 469:89 with one child
    form, so the `(try ...)` on line 470 was its sibling, not its else. But
    the sweep above reported it against a repository whose `main` had already
    been fixed -- `b539060`, merged as `baceead7`. The west pin was ALREADY
    at that merge. Only the shared CHECKOUT lagged, at `408938c`, and this
    detector reads the checkout. The first sweep recorded the fetch that
    would have shown this as refused, and the fix as unmeasured; the fetch
    succeeds, and the commit was one `git fetch` away the whole time.

    So checkout, west pin and the repository's main are three different
    things (ADR-2608136800) -- and the failure mode is not only the one that
    phrase is usually quoted for. A stale checkout makes this detector report
    a site that no longer exists in anything that ships, which is a FALSE
    POSITIVE, the mirror of the silence the rest of this file is about. A
    finding here means `the tree on disk carries it`. Before acting on one,
    check `git -C <path> rev-parse HEAD` against the pin: `west update
    --fetch smart <name>` cleared this one, and the site went with it.

  The other four -- `cloud-itonami-isic-3520`, `cloud-itonami-isic-853`,
  `network-awai/cloud-itonami` and `kototama-component` -- had never been
  named anywhere before this ran, and neither had the sixth file the count
  includes without the prose naming it, `kotoba-lang/ooyake`
  `src/ooyake/cells/world_model/state_machine.cljc:65`.

  **`:missing-body-in-when`** -- 1 site, and it earns its place on evidence
  rather than on symmetry with the first. It is `kotoba-lang/kotoba-lang`
  `test/kotoba/cli_test.cljc:116`:

      (when (pos? (+ (or fail 0) (or error 0)))
        #?(:clj (System/exit 1)))

  Under `:clj` that is correct. Under `:cljs` the reader conditional yields
  NOTHING, so the `when` has no body and **a failing suite does not exit
  non-zero**. clj-kondo reports it as `:lang :cljs` only. Verified here
  2026-09-06 before this detector was written, not taken on trust.

  ## What is deliberately NOT reported, and why

  A gate that lands red against thousands of sites is not a gate; it is a
  number people learn to scroll past. Measured fleet-wide, and excluded:

    unused-binding           2,992 sites / 1,611 repos
    unused-referred-var, unused-namespace, redundant-do,
    misplaced-docstring, single-logical-operand

  Those are this codebase's idiom or they are cosmetic. Neither describes a
  form whose shape differs from its author's intent. The price of the narrow
  scope is visible in the sweep's own output: it saw **20,325 clj-kondo
  findings of every kind** and reported 10. A detector that reported the
  other 20,315 would be one nobody reads, and the 10 would be inside it.

  This detector therefore does NOT re-home ADR-2607091500's enforcement
  clause. It covers two ids of it. Every repository whose OWN `--fail-level
  error` alias would already be red if anything ran it is still unaddressed
  by this or by anything else -- the error-level share of those 20,325 was
  not separated out here, and saying how large it is would be a number this
  file did not measure.

  ## How clj-kondo is invoked, and the three things that had to be measured

  The alias is `clojure -M:lint`, which needs the JVM and this machine's
  heavily contended build lock. A `clj-kondo` binary is on PATH at the SAME
  pinned version -- `clj-kondo --version` reports `clj-kondo v2024.11.14`,
  checked at startup below and an exit-2 refusal if it does not. The binary
  is used: it starts in ~10ms against the JVM's seconds, and it needs no
  lock, so the fleet sweep is minutes rather than hours.

  Three things about it are load-bearing and each was measured on 2026-09-06:

  1. **`--cache false` is mandatory.** clj-kondo writes
     `.clj-kondo/.cache/v1/...` whenever a `.clj-kondo` directory already
     exists, and 24 registered repositories have one. Without the flag this
     detector would modify child repositories -- violating the `:writes
     :none` the registry requires of every entry, and the instruction not to
     touch a child repo. With it, a fixture with `.clj-kondo/` present gains
     no files.

     Skipping the cache cannot change the answer. A classpath-populated cache
     moves `unresolved-namespace` and `unresolved-symbol` counts, because
     those need to know what another namespace defines. `missing-else-branch`
     and `missing-body-in-when` are decided from the arity of one form. They
     are purely syntactic and see nothing outside the file.

  2. **The two levels are forced on explicitly.** A repository's own
     `.clj-kondo/config.edn` can silence a linter, and `--config` merges over
     it. Measured on a fixture: with `{:missing-else-branch {:level :off}}` in
     the repo config the site vanishes and clj-kondo prints `:findings []` --
     a clean answer that measured nothing, which is the failure class this
     workspace names first. With the override the site is reported again.

     No repository silences either id today: all 24 configs were read and
     none mentions them. So the override changes no current answer. It is a
     floor against the config nobody has written yet, not an override of a
     decision somebody made.

  3. **clj-kondo's exit code is not this detector's exit code.** It exits 0
     for nothing, 2 for warnings and 3 for errors, so its status says how bad
     the code is, not whether the run happened. The EDN it prints is parsed
     instead -- `--config '{:output {:format :edn}}'`, structure rather than
     scraped text, so a reworded message moves nothing.

  ## Refusing to answer

  Exit is three-valued:

    0  everything in scope was linted and agreed
    1  everything in scope was linted, at least one site disagreed
    2  something could NOT be linted -- REFUSING to report an agreement it
       did not verify.

  `clean` and `not linted` are the same silence, and telling them apart is
  the entire point of this exercise. So all of these are exit 2, never 0: a
  registered path with no checkout (26 of them, measured), a `clj-kondo` that
  is absent or the wrong version, a process that dies or prints something
  that is not EDN, a file clj-kondo could not parse (9 fleet-wide -- it
  reports these as `:type :syntax` and keeps going, so the REST of that
  repository is still measured and only the unreadable file is unmeasured),
  a `--repo` that does not exist or has nothing to lint, and a sweep that
  linted zero files.

  So the honest state of the fleet is exit 2, not exit 1: 35 things could not
  be linted, and until they can, `neither id occurs anywhere` is a claim this
  detector is not entitled to make.

  A registered path whose checkout has neither `src/` nor `test/` is OUT OF
  SCOPE rather than unmeasured -- 539 of them, mostly specification mirrors
  and document repositories. The alias lints exactly `src test`; where
  neither exists there is no Clojure surface for it to have caught anything
  in. They are counted in the output so that `out of scope` is a number
  somebody can see rather than a silence. A path named with `--repo` is an
  ASSERTION and gets the opposite treatment: the caller said it should be
  measurable, so having nothing to lint is exit 2.

  ## Usage

    nbb --classpath \".:scripts/nbb_compat\" scripts/verify-lint-structural-warnings.cljs \\
        [--scan-root <dir>] [--repo <path>]... [--findings]
    nbb --classpath \".:scripts/nbb_compat\" scripts/verify-lint-structural-warnings.cljs --selftest

  `--scan-root` is where `orgs/` and `manifest/west.yml` live, so the
  detector can run from a worktree that has neither (the shape
  `gen-concept-index.cljs` uses; this file was developed in one). `--repo`
  NARROWS the sweep to the paths named, relative to `--scan-root`."
  (:require ["fs" :as fs]
            ["path" :as p]
            ["os" :as os]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ── what counts as a finding ────────────────────────────────────────────────

(def structural-linters
  "The two ids, with what each one means when it fires.

  A map rather than a set because the detail line has to tell a reader which
  of the two readings applies -- `missing else` is either a dropped branch or
  an `if` that wanted to be a `when`, and those are different one-line fixes."
  {:missing-else-branch
   (str "an `if` with no else. Either a branch was lost to a paren slip -- the "
        "form after it became a sibling instead of the alternative -- or the "
        "`if` should be a `when`. Both fixes are one line; they are not the "
        "same line")
   :missing-body-in-when
   (str "a `when` with a test and no body. On a `.cljc` file reported for one "
        "reader branch only, this is usually a `#?(...)` that yields nothing "
        "under that branch, so the guarded effect silently does not happen")})

(def kondo-version
  "The version the alias pins in 3,122 deps.edn files. The binary must match:
  a linter is a specification, and a different one answers a different
  question about the same file."
  "2024.11.14")

(def kondo-config
  "Forced on, for the reason in the header. `:output {:format :edn}` so the
  result is read as structure."
  (str "{:linters {:missing-else-branch {:level :warning}"
       " :missing-body-in-when {:level :warning}}"
       " :output {:format :edn}}"))

(def lint-dirs
  "What the alias lints, in the alias's order. Only the ones that exist are
  passed: clj-kondo reports a directory that is not there as an error finding
  rather than skipping it, and a repository with `src` and no `test` is
  ordinary, not broken."
  ["src" "test"])

;; ── state ───────────────────────────────────────────────────────────────────

(def unmeasured (atom []))        ; [{:key :detail}] -- drives exit 2
(def findings   (atom []))        ; [{:sev :key :detail}]
(def findings?  (atom false))
(def scan-root* (atom "."))
(def counters   (atom {}))

(defn- unmeasured! [k detail] (swap! unmeasured conj {:key (str k) :detail detail}))
(defn- finding!  [sev k detail] (swap! findings conj {:sev sev :key (str k) :detail detail}))
(defn- bump!     [k n] (swap! counters update k (fnil + 0) n))

(defn distinct-by-key
  "One row per key. Two rows under one key are one finding printed twice, and
  a key is what the registry counts findings by."
  [rows]
  (->> rows (reduce (fn [[seen out] r]
                      (if (seen (:key r)) [seen out]
                          [(conj seen (:key r)) (conj out r)]))
                    [#{} []])
       second))

(defn- dir? [x] (try (.isDirectory (fs/statSync x)) (catch :default _ false)))
(defn- file? [x] (try (.isFile (fs/statSync x)) (catch :default _ false)))

;; ── scope ───────────────────────────────────────────────────────────────────

(defn west-paths
  "The paths `manifest/west.yml` pins, read from the scan root.

  The registry that names the repositories and the tree that holds them are
  read from the SAME checkout on purpose: pairing one checkout's manifest
  with another's `orgs/` would report a missing checkout for every repository
  the two disagree about, which is a fact about the pairing and not about the
  fleet.

  Read as text with a regex rather than as YAML, the way
  `gen-concept-index.cljs` does. west.yml is generated and its `path:` lines
  have one shape; a YAML parser would be a second thing to be wrong."
  [scan-root]
  (let [f (p/join scan-root "manifest" "west.yml")]
    (if-not (file? f)
      (do (unmeasured! (str "no-manifest:" f)
                       (str "manifest/west.yml is not readable under the scan root, so the "
                            "set of registered repositories is unknown — a scan of whatever "
                            "happens to be checked out is not a scan of the fleet"))
          [])
      (->> (str (fs/readFileSync f "utf8"))
           (re-seq #"(?m)^\s+path:\s+(\S+)\s*$")
           (map second)
           distinct
           sort
           vec))))

(defn rel-to
  "A path relative to the scan root, for use in a finding KEY. Findings are
  keyed forever, and an absolute path would put this machine's home directory
  inside the key -- so the same defect seen from a worktree would be a
  different finding with no history."
  [abs]
  (let [root (str @scan-root*) abs (str abs)]
    (cond (= abs root) "."
          (str/starts-with? abs (str root "/")) (subs abs (inc (count root)))
          :else abs)))

;; ── the linter ──────────────────────────────────────────────────────────────

(defn kondo-version!
  "The version on PATH, or nil if it will not run.

  Checked once, before the sweep. A detector that cannot start its own
  measuring instrument must say so rather than report a fleet with no
  findings in it."
  []
  (try
    (let [r (cp/spawnSync "clj-kondo" #js ["--version"]
                          #js {:encoding "utf8" :timeout 30000})]
      (when (and (nil? (.-error r)) (zero? (.-status r)))
        (some-> (.-stdout r) str str/trim
                (->> (re-find #"v?(\d{4}\.\d{2}\.\d{2})"))
                second)))
    (catch :default _ nil)))

(defn run-kondo!
  "clj-kondo over `dirs` inside `repo-dir`, parsed.

  Returns {:findings [...] :files n} or {:error \"...\"}. The process status
  is deliberately not consulted -- see the header -- but a process that could
  not be spawned, was killed, or printed something that does not read as EDN
  is an error, because none of those produced an answer."
  [repo-dir dirs]
  (let [args (-> ["--lint"] (into dirs) (into ["--cache" "false" "--config" kondo-config]))
        r (try (cp/spawnSync "clj-kondo" (clj->js args)
                             #js {:cwd repo-dir :encoding "utf8"
                                  :timeout 300000
                                  :maxBuffer (* 64 1024 1024)})
               (catch :default e {:spawn-threw (.-message e)}))]
    (cond
      (:spawn-threw r) {:error (str "clj-kondo could not be spawned: " (:spawn-threw r))}
      (.-error r) {:error (str "clj-kondo did not complete: " (.-message (.-error r)))}
      (.-signal r) {:error (str "clj-kondo was killed by " (.-signal r)
                                " (timeout or output over the 64MB buffer)")}
      :else
      (let [out (str (.-stdout r))]
        (if (str/blank? out)
          {:error (str "clj-kondo printed nothing"
                       (let [e (str/trim (str (.-stderr r)))]
                         (when-not (str/blank? e) (str "; stderr: " (subs e 0 (min 300 (count e)))))))}
          (try
            (let [m (edn/read-string out)]
              (if (map? m)
                {:findings (vec (:findings m)) :files (or (:files (:summary m)) 0)}
                {:error "clj-kondo's output read as EDN but was not a map"}))
            (catch :default e
              {:error (str "clj-kondo's output is not EDN: " (.-message e)
                           " — first 200 chars: " (subs out 0 (min 200 (count out))))})))))))

;; ── classification ──────────────────────────────────────────────────────────

(defn- site-key
  "repo + file + linter id, and deliberately NOT the row or the message.

  A finding is keyed forever. Including the row would make an edit ten lines
  above it a brand new finding; including the message would make a clj-kondo
  reword one. Two sites of the same id in one file therefore share a key and
  are reported together, with their rows in the detail — lossless, and stable
  against both."
  [rel filename id]
  (str (name id) ":" rel ":" filename))

(defn- describe-sites
  "The rows, and the reader branch when it is not both.

  `:lang` is load-bearing rather than decoration: the cli_test.cljc site is
  correct under `:clj` and broken under `:cljs`, and a detail line that
  dropped it would describe a bug that is not there half the time."
  [sites]
  (str/join ", "
            (for [{:keys [row col lang]} (sort-by (juxt :row :col) sites)]
              (str row ":" col (when lang (str " (" (name lang) " only)"))))))

(defn check-repo!
  "Lint one repository and route what comes back.

  Three destinations. A structural id is a finding. A `:syntax` finding is a
  file clj-kondo could not read, which is unmeasured for that file and
  nothing at all for the rest of the repository. Everything else — the 2,992
  unused-binding sites and the rest — is counted and dropped, because a
  detector that reports them is one nobody reads."
  [rel repo-dir {:keys [asserted?]}]
  (let [present (filterv #(dir? (p/join repo-dir %)) lint-dirs)]
    (cond
      (not (dir? repo-dir))
      ;; Only for a path the MANIFEST named. A path the caller named is
      ;; already reported by `sweep!` as `no-such-repo:`, and saying it a
      ;; second time as `registered in manifest/west.yml` would assert
      ;; something about it that nobody checked -- one refusal, one reason.
      (when-not asserted?
        (unmeasured! (str "no-checkout:" rel)
                     (str "registered in manifest/west.yml and not checked out under the scan "
                          "root — whether its src carries either id is unknown, and unknown is "
                          "not clean")))

      (empty? present)
      (if asserted?
        (unmeasured! (str "nothing-to-lint:" rel)
                     (str "named on the command line and has neither src/ nor test/, so there "
                          "was nothing for `--lint src test` to read"))
        (bump! :out-of-scope 1))

      :else
      (let [{:keys [error findings files]} (run-kondo! repo-dir present)]
        (if error
          (unmeasured! (str "kondo-failed:" rel) (str error " (in " (str/join " " present) ")"))
          (do
            (bump! :repos 1)
            (bump! :files (or files 0))
            (bump! :all-findings (count findings))
            ;; a file that does not parse: unmeasured, per file, once
            (doseq [[fname _] (->> findings
                                   (filter #(= :syntax (:type %)))
                                   (group-by :filename))]
              (bump! :unparseable 1)
              (unmeasured! (str "unparseable-file:" rel ":" fname)
                           (str "clj-kondo could not parse it, so whether it carries either id "
                                "is unknown. The rest of this repository was linted")))
            ;; a directory clj-kondo could not open. Only existing dirs are
            ;; passed, so this is a race or a permission, not the ordinary case
            (doseq [{:keys [filename message]} (filter #(= :file (:type %)) findings)]
              (unmeasured! (str "unreadable-path:" rel ":" filename)
                           (str "clj-kondo reported `" message "` for a path that was a "
                                "directory when the sweep chose it")))
            ;; the point
            (doseq [[[fname id] sites] (->> findings
                                            (filter #(contains? structural-linters (:type %)))
                                            (group-by (juxt :filename :type)))]
              (finding! "fail" (site-key rel fname id)
                        (str (name id) " at " (describe-sites sites) " — "
                             (get structural-linters id))))))))))

;; ── reporting ───────────────────────────────────────────────────────────────

(defn- flag-values [argv f]
  (->> (map vector argv (rest argv)) (keep (fn [[a b]] (when (= f a) b))) vec))

(defn sweep!
  "Resolve scope, lint it, and return the exit code."
  [scan-root repos]
  (reset! scan-root* scan-root)
  (let [asserted? (boolean (seq repos))
        scope (if asserted? repos (west-paths scan-root))]
    (when asserted?
      (doseq [r repos]
        (when-not (dir? (p/join scan-root r))
          (unmeasured! (str "no-such-repo:" r)
                       (str (p/join scan-root r) " was named on the command line and is not "
                            "a directory")))))
    (doseq [rel scope]
      (check-repo! rel (p/join scan-root rel) {:asserted? asserted?}))

    (let [{:keys [repos files out-of-scope all-findings unparseable]
           :or {repos 0 files 0 out-of-scope 0 all-findings 0 unparseable 0}} @counters]
      (println (str "clj-kondo structural warnings nothing in this workspace runs  (scan-root "
                    scan-root ")\n"))
      ;; Evidence floor. The registry matches SCANNED\t[1-9][0-9]* before it
      ;; will believe an exit code. Zero files linted is a detector pointed at
      ;; the wrong tree, never a fleet with nothing wrong in it.
      (println (str "SCANNED\t" files
                    "\tsource file(s) linted across " repos " repo(s) with a src/ or test/, of "
                    (count scope) " path(s) in scope"
                    (when (pos? out-of-scope)
                      (str "; " out-of-scope " had neither and are out of scope"))
                    (when (pos? unparseable)
                      (str "; " unparseable " file(s) did not parse"))))
      (println (str "\tlinters in scope: "
                    (str/join ", " (map name (sort (keys structural-linters))))
                    "  —  " all-findings
                    " clj-kondo finding(s) of ALL kinds seen and deliberately not reported "
                    "(see the header for what is excluded and why)"))
      (when (zero? files)
        (unmeasured! :nothing-scanned
                     (str "0 files linted across " (count scope)
                          " path(s) — a scan root with no source under it, not a fleet "
                          "that agrees")))
      (println)
      (let [fs* (sort-by :key (distinct-by-key @findings))
            um  (sort-by :key (distinct-by-key @unmeasured))]
        (doseq [{:keys [sev key detail]} fs*]
          (println (str "  " (if (= "fail" sev) "FAIL" "warn") " " key "\n        " detail))
          (when @findings?
            (println (str "FINDING\t" sev "\t" key "\t" detail))))
        (when (seq um) (println))
        (doseq [{:keys [key detail]} (take 40 um)]
          (println (str "  ?    " key "\n        " detail)))
        (when (> (count um) 40)
          (println (str "  ?    … and " (- (count um) 40) " more unmeasured")))
        (println)
        (cond
          (seq um)
          (do (println (str "  REFUSING to report agreement: " (count um)
                            " thing(s) could not be linted. `clean` and `not linted` are the "
                            "same silence, which is the defect this detector exists for."))
              2)
          (seq fs*)
          (do (println (str "  " (count fs*) " structural finding(s) — a form whose shape is "
                            "not the shape it was written to have"))
              1)
          :else
          (do (println (str "  clean — every file in scope was linted and neither "
                            (str/join " nor " (map name (sort (keys structural-linters))))
                            " occurs in it"))
              0))))))

;; ── selftest ────────────────────────────────────────────────────────────────

(def selftest-fails (atom 0))

(defn- t! [nm ok? detail]
  (if ok?
    (println (str "  ok   " (name nm) "  —  " detail))
    (do (swap! selftest-fails inc)
        (println (str "  FAIL " (name nm) "  —  " detail)))))

(defn- write! [f content]
  (fs/mkdirSync (p/dirname f) #js {:recursive true})
  (fs/writeFileSync f content))

(defn- fixture-run
  "Lint a synthetic tree and return [findings unmeasured].

  `asserted?` is a parameter because the two refusals it selects between are
  different claims: a path the MANIFEST named that is not checked out, and a
  path the CALLER named that does not exist. Fixing them to one value would
  leave one of the two untested."
  ([root scope] (fixture-run root scope true))
  ([root scope asserted?]
   (reset! scan-root* root)
   (reset! findings [])
   (reset! unmeasured [])
   (reset! counters {})
   (doseq [rel scope] (check-repo! rel (p/join root rel) {:asserted? asserted?}))
   [(distinct-by-key @findings) (distinct-by-key @unmeasured)]))

(defn selftest
  "The derivation must report each id on a tree that has it, clear on the same
  tree once fixed, and refuse on a tree it cannot read. A check nobody has
  watched change colour has not been shown to depend on what it claims to
  measure."
  []
  (println "verify-lint-structural-warnings --selftest\n")
  (let [v (kondo-version!)]
    (t! :the-binary-is-the-pinned-version
        (= v kondo-version)
        (str "clj-kondo on PATH reports " (or v "nothing that parses as a version")
             ", alias pins " kondo-version)))

  (let [root (p/join (os/tmpdir) (str "verify-lint-structural-selftest-" (js/Date.now)))
        r "orgs/kotoba-lang/fixture"
        src (p/join root r "src/fx/a.clj")]

    ;; 1. missing-else-branch, on a file that has nothing else wrong with it
    (write! src "(ns fx.a)\n(defn g [x] (if (pos? x) :yes))\n")
    (let [[f u] (fixture-run root [r])]
      (t! :finds-a-missing-else-branch
          (and (empty? u) (= 1 (count f))
               (= (str "missing-else-branch:" r ":src/fx/a.clj") (:key (first f)))
               (= "fail" (:sev (first f))))
          (str "`(if (pos? x) :yes)` → "
               (str/join ", " (map #(str (:sev %) " " (:key %)) f))
               (when (seq u) (str "; " (count u) " unmeasured")))))

    ;; 2. the same tree, one word changed
    (write! src "(ns fx.a)\n(defn g [x] (when (pos? x) :yes))\n")
    (let [[f u] (fixture-run root [r])]
      (t! :clears-when-the-if-becomes-a-when
          (and (empty? f) (empty? u))
          (str "the same file with `if` → `when` → "
               (if (and (empty? f) (empty? u)) "clean"
                   (str (str/join ", " (map :key f)) "; " (count u) " unmeasured")))))

    ;; 3. the cli_test.cljc shape: correct under :clj, empty body under :cljs
    (let [cljc (p/join root r "src/fx/b.cljc")]
      (write! cljc "(ns fx.b)\n(defn main [ok?]\n  (when (not ok?)\n    #?(:clj (prn :bye))))\n")
      (let [[f u] (fixture-run root [r])]
        (t! :finds-a-reader-conditional-that-empties-a-when
            (and (empty? u) (= 1 (count f))
                 (= (str "missing-body-in-when:" r ":src/fx/b.cljc") (:key (first f)))
                 (str/includes? (:detail (first f)) "cljs only"))
            (str "`(when (not ok?) #?(:clj ...))` → "
                 (str/join ", " (map #(str (:sev %) " " (:key %)) f))
                 " / " (:detail (first f)))))
      (fs/rmSync cljc))

    ;; 4. the excluded ids are not reported, on a file full of them
    (write! src (str "(ns fx.a (:require [clojure.string :as s]))\n"
                     "(defn g [x] (let [unused 1] (do (when (pos? x) :y))))\n"))
    (let [[f u] (fixture-run root [r])]
      (t! :does-not-report-the-idiom
          (and (empty? f) (empty? u))
          (str "unused-binding + unused-namespace + redundant-do → "
               (if (empty? f) "no findings, as scoped"
                   (str/join ", " (map :key f))))))

    ;; 5. a repository config that silences the id does NOT silence this.
    ;;    Without the forced level, clj-kondo prints `:findings []` here and
    ;;    the detector would report clean having measured nothing.
    (write! src "(ns fx.a)\n(defn g [x] (if (pos? x) :yes))\n")
    (write! (p/join root r ".clj-kondo/config.edn")
            "{:linters {:missing-else-branch {:level :off} :missing-body-in-when {:level :off}}}\n")
    (let [[f u] (fixture-run root [r])]
      (t! :a-repo-config-cannot-silence-the-detector
          (and (empty? u) (= 1 (count f)))
          (str "repo config sets both to :level :off → "
               (if (seq f) (str "still reported: " (:key (first f)))
                   "SILENCED — the override is not working"))))

    ;; 6. and linting it wrote nothing into the repository, though .clj-kondo
    ;;    now exists — which is the condition under which clj-kondo caches
    (t! :leaves-no-cache-behind
        (not (fs/existsSync (p/join root r ".clj-kondo/.cache")))
        (str ".clj-kondo/ exists and .clj-kondo/.cache does not after linting — "
             "`--cache false` holds, so `:writes :none` holds"))
    (fs/rmSync (p/join root r ".clj-kondo") #js {:recursive true :force true})

    ;; 7. a file that does not parse is unmeasured, never clean
    (write! (p/join root r "src/fx/broken.clj") "(ns fx.broken)\n(defn oops [x]\n  (if (pos? x)\n")
    (let [[f u] (fixture-run root [r])]
      (t! :an-unparseable-file-is-unmeasured
          (and (= 1 (count u))
               (= (str "unparseable-file:" r ":src/fx/broken.clj") (:key (first u))))
          (str "a file with an unclosed paren → "
               (str/join ", " (map :key u))))
      (t! :and-the-rest-of-the-repo-is-still-linted
          (= 1 (count f))
          (str "the sound file in the same repo is still reported: "
               (str/join ", " (map :key f))
               " — an unreadable file does not blind the repository")))
    (fs/rmSync (p/join root r "src/fx/broken.clj"))

    ;; 8. a checkout that is not there. Reported ONCE, and as the right thing:
    ;;    from the manifest it is `no-checkout`, and a path the caller named
    ;;    is left to `sweep!` to report as `no-such-repo` rather than being
    ;;    described here as registered when nobody checked whether it is.
    (let [[_ u] (fixture-run root ["orgs/kotoba-lang/absent"] false)]
      (t! :a-missing-checkout-is-unmeasured
          (and (= 1 (count u)) (str/starts-with? (:key (first u)) "no-checkout:"))
          (str "a registered path with no checkout → " (str/join ", " (map :key u)))))
    (let [[_ u] (fixture-run root ["orgs/kotoba-lang/absent"] true)]
      (t! :an-asserted-missing-path-is-not-called-registered
          (empty? u)
          "a path named on the command line is not also reported as registered in west.yml"))

    ;; 9. a checkout with nothing to lint, named on the command line
    (fs/mkdirSync (p/join root "orgs/kotoba-lang/docsonly") #js {:recursive true})
    (write! (p/join root "orgs/kotoba-lang/docsonly/README.md") "# docs\n")
    (let [[_ u] (fixture-run root ["orgs/kotoba-lang/docsonly"])]
      (t! :an-asserted-repo-with-no-src-is-unmeasured
          (and (= 1 (count u)) (str/starts-with? (:key (first u)) "nothing-to-lint:"))
          (str "--repo at a tree with neither src/ nor test/ → "
               (str/join ", " (map :key u)))))

    (fs/rmSync root #js {:recursive true :force true}))

  (println)
  (if (pos? @selftest-fails)
    (do (println (str "  " @selftest-fails " selftest failure(s)")) 1)
    (do (println "  selftest clean — both ids change colour, the excluded ids stay quiet, "
                 "a repo config cannot silence it, and every unreadable thing refuses")
        0)))

;; ── main ────────────────────────────────────────────────────────────────────

(defn -main [& args]
  (let [argv (vec args)]
    (reset! findings? (boolean (some #{"--findings"} argv)))
    (if (some #{"--selftest"} argv)
      (selftest)
      (let [scan-root (or (first (flag-values argv "--scan-root")) ".")
            repos (flag-values argv "--repo")
            v (kondo-version!)]
        (reset! scan-root* scan-root)
        (if (not= v kondo-version)
          ;; Before anything else. A sweep with the wrong instrument, or with
          ;; none, prints the same empty findings list as a clean fleet.
          (do (println (str "clj-kondo structural warnings nothing in this workspace runs  "
                            "(scan-root " scan-root ")\n"))
              (println (str "SCANNED\t0\tnothing — the linter did not run"))
              (println)
              (println (str "  ?    kondo-unavailable\n        "
                            (if v
                              (str "clj-kondo on PATH is " v " and the alias pins "
                                   kondo-version
                                   ". A different linter answers a different question about "
                                   "the same file")
                              (str "`clj-kondo --version` did not run. The alias pins "
                                   kondo-version
                                   "; without it this would report a fleet with no findings "
                                   "in it, which is exactly what an unrun linter looks like"))))
              (println)
              (println "  REFUSING to report agreement: the measuring instrument is not present")
              2)
          (sweep! scan-root repos))))))

(let [code (apply -main *command-line-args*)]
  (set! (.-exitCode js/process) code))
