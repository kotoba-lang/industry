#!/usr/bin/env nbb
(ns verify-clojure-stdlib-migration
  "The gate a clojure.* -> kotoba.* stdlib rewire proposal has to pass before
  it becomes a PR.

  Mirrors scripts/hermes-kotoba-migration-bots/verify-kotoba-migration.cljs
  in shape (the model proposes, this decides, on evidence it (re-)gathers
  itself) but checks a DIFFERENT thing, because the domain is different:
  that gate verifies a NEW .kotoba/.cljk file was added and compiles; this
  one verifies an EXISTING .clj/.cljc file's require was actually swapped
  — the old namespace is gone, the new one is present, the new dependency
  is really declared, and the target repo's own test command still passes.

  Checks, none optional unless noted:

    1. proposal shape — required keys, :namespace is one of the seven
       migrating namespaces (never clojure.java.io — its migration is only
       a PROPOSED design, adr-2809070100, not accepted), :repo is not one
       of the frozen meta-repos.
    2. every :changed-file exists under repo-root, has a .clj/.cljc
       extension (this is a REWIRE, not a new-file addition — if you meant
       to add a .kotoba file you want the OTHER bot family's gate), and its
       path has no `..` traversal segment.
    3. the file no longer matches the OLD namespace's require/qualified-call
       pattern — an incomplete rewire (one call site missed) is caught here.
    4. the file DOES mention the declared :target-ns — a file that dropped
       the old require without ever adding the new one is not a rewire, it's
       breakage.
    5. the file's current content actually differs from what's on
       origin/main — catches a no-op proposal (nothing was actually changed)
       and, as a side effect, that the file genuinely predates this run
       (not a fabricated new file pretending to be a migrated one).
    6. deps.edn declares the target kotoba-lang repo as a real dependency —
       parsed as EDN, not grepped, so a `:local/root` (unresolvable from a
       fresh clone — the whole reason candidates.cljs excludes such repos
       from candidacy in the first place) is caught even if some OTHER
       dependency entry in the same file happens to be `:local/root`.
    7. self-consistency of the proposal's own :tests-before/:tests-after —
       a rewire that claims a test count went DOWN is rejected outright,
       whether or not the count could be independently confirmed from
       output.
    8. the target repo's own :test-command exits 0 and produces non-empty
       combined stdout/stderr — an exit 0 that printed nothing proves
       nothing, same discipline as the kotoba-migration gate.
    9. BEST-EFFORT, NOT A HARD GATE: tries to extract a test count from the
       command's own output (a short list of common framework phrasings —
       clojure.test's own `Ran N tests`, kotoba-lang/test's own summary
       shape, a generic `N tests? passed`). If found and it disagrees with
       the proposal's declared :tests-after, that IS a hard rejection (the
       proposal lied about its own evidence). If NOT found — an
       unrecognized test runner's output shape — this prints a WARNING and
       does NOT fail the run on that basis alone; see the README for why
       this is weaker than the sibling gate's parity-test-in-output check
       and what remains to harden.

  exit 0  every check passed — land it
  exit 1  at least one check failed — printed, with the failing check named
  exit 2  REFUSED: could not judge (proposal unreadable, target repo path
          missing) — not a verdict on the rewire"
  (:require [cljs.reader :as edn]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]))

(def argv (vec (or *command-line-args* [])))
(defn arg [flag default]
  (let [i (.indexOf argv flag)]
    (if (neg? i) default (get argv (inc i) default))))

(def proposal-path (arg "--proposal" nil))
(def repo-root (path/resolve (arg "--repo-root" ".")))
(def test-timeout-ms (js/parseInt (arg "--test-timeout-ms" "600000") 10))

(def findings (atom []))
(defn fail! [check why] (swap! findings conj {:check check :why why}))

(defn refuse! [why]
  (println "REFUSED — could not judge this rewire proposal.")
  (println why)
  (.exit js/process 2))

(when-not proposal-path (refuse! "no --proposal given"))
(when-not (.existsSync fs proposal-path) (refuse! (str proposal-path " does not exist")))

(def proposal
  (try (edn/read-string (fs/readFileSync proposal-path "utf8"))
       (catch :default e (refuse! (str "cannot read " proposal-path ": " (.-message e))))))

(def required-keys #{:repo :namespace :target-ns :target-repo :changed-files
                      :test-command :tests-before :tests-after})
(let [missing (remove #(contains? proposal %) required-keys)]
  (when (seq missing)
    (refuse! (str "proposal is missing required keys: " (str/join ", " missing)))))

(when-not (.existsSync fs repo-root)
  (refuse! (str "--repo-root " repo-root " does not exist")))

(defn rp [rel] (path/join repo-root rel))

;; --------------------------------------------------------- 1. shape / scope

(def target-map
  {"clojure.string"    {:target-ns "kotoba.string"       :target-repo "string"}
   "clojure.test"       {:target-ns "kotoba.lang.test"     :target-repo "test"}
   "clojure.edn"        {:target-ns "kotoba.lang.edn"      :target-repo "edn"}
   "clojure.set"        {:target-ns "kotoba.lang.coll"     :target-repo "coll"}
   "clojure.walk"       {:target-ns "kotoba.lang.coll"     :target-repo "coll"}
   "clojure.pprint"     {:target-ns "kotoba.lang.fmt"      :target-repo "fmt"}
   "clojure.java.shell" {:target-ns "kotoba.lang.process"  :target-repo "process"}})

(def meta-repos #{"kotoba" "kotoba-lang" "amu" "kototama" "aiueos"})

(def ns-pattern
  {"clojure.string"    #"clojure\.string\b"
   "clojure.test"       #"clojure\.test(?!\.check)\b"
   "clojure.edn"        #"clojure\.edn\b"
   "clojure.set"        #"clojure\.set\b"
   "clojure.walk"       #"clojure\.walk\b"
   "clojure.pprint"     #"clojure\.pprint\b"
   "clojure.java.shell" #"clojure\.java\.shell\b"})

(when (= (:namespace proposal) "clojure.java.io")
  (fail! :namespace-not-accepted
         (str "clojure.java.io migration is only a PROPOSED design "
              "(adr-2809070100-clojure-java-io-capability-boundary-proposal, "
              "status \"proposed\", not accepted). This gate refuses to accept "
              "any proposal for this namespace regardless of how it looks.")))

(when-not (contains? target-map (:namespace proposal))
  (fail! :namespace-known
         (str (:namespace proposal) " is not one of the seven migrating "
              "namespaces this gate recognizes: " (str/join ", " (keys target-map)))))

(when (contains? meta-repos (:repo proposal))
  (fail! :repo-not-meta
         (str (:repo proposal) " is one of the frozen meta-repos ("
              (str/join ", " meta-repos) ") — a rewire inside the bootstrap "
              "itself is an architectural tranche, not an autonomous swap.")))

;; only continue into file-level checks if the namespace itself is legitimate
(def expected-target-ns
  (get-in target-map [(:namespace proposal) :target-ns]))
(def expected-target-repo
  (get-in target-map [(:namespace proposal) :target-repo]))

(when (and expected-target-ns (not= expected-target-ns (:target-ns proposal)))
  (fail! :target-ns-matches-adr
         (str "proposal declares :target-ns " (:target-ns proposal)
              " but adr-2809061500 maps " (:namespace proposal) " to "
              expected-target-ns ". If the ADR has genuinely changed, that is "
              "a reason to update this gate by hand, not to override it here.")))

(when (empty? (:changed-files proposal))
  (fail! :changed-files-nonempty "proposal lists zero :changed-files"))

;; ----------------------------------------------------- 2-5. per-file checks

(defn safe-rel? [rel]
  (and (not (str/includes? rel ".."))
       (re-find #"\.(clj|cljc)$" rel)))

(defn sh [cmd args opts]
  (let [r (cp/spawnSync cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8" :maxBuffer (* 32 1024 1024)} opts)))]
    {:status (if (some? (.-status r)) (.-status r) -1)
     :stdout (or (.-stdout r) "")
     :stderr (or (.-stderr r) "")}))

(when (empty? @findings)
  (doseq [rel (:changed-files proposal)]
    (cond
      (not (safe-rel? rel))
      (fail! :changed-file-path-safe
             (str rel " is not a safe relative .clj/.cljc path (traversal or "
                  "wrong extension — this gate is for REWIRING an existing "
                  "file, not adding a new .kotoba/.cljk one)"))

      (not (.existsSync fs (rp rel)))
      (fail! :changed-file-exists (str rel " not found under " repo-root))

      :else
      (let [content (.toString (fs/readFileSync (rp rel)))
            old-pat (get ns-pattern (:namespace proposal))]
        (when (and old-pat (re-find old-pat content))
          (fail! :old-namespace-gone
                 (str rel " still matches " old-pat " — the rewire is "
                      "incomplete, at least one reference to " (:namespace proposal)
                      " remains.")))
        (when-not (str/includes? content (:target-ns proposal))
          (fail! :new-namespace-present
                 (str rel " never mentions " (:target-ns proposal) " — dropping "
                      "the old require without adding the new one is breakage, "
                      "not a rewire.")))
        (let [origin (sh "git" ["-C" repo-root "show" (str "origin/main:" rel)] {})]
          (if (zero? (:status origin))
            (when (= (:stdout origin) content)
              (fail! :file-actually-changed
                     (str rel " is byte-identical to origin/main — nothing was "
                          "actually changed in this file.")))
            (fail! :file-predates-migration
                   (str rel " does not exist on origin/main (git show exited "
                          (:status origin) ") — a rewire proposal should be "
                          "modifying a file that was already there, not "
                          "introducing a new one."))))))))

;; ------------------------------------------------------ 6. dependency added

(when (empty? @findings)
  (let [deps-path (rp "deps.edn")]
    (if-not (.existsSync fs deps-path)
      (fail! :deps-edn-exists (str "no deps.edn under " repo-root " — cannot "
                                    "verify the target library was declared "
                                    "as a real dependency."))
      (let [deps (try (edn/read-string (fs/readFileSync deps-path "utf8"))
                       (catch :default e
                         (fail! :deps-edn-readable
                                (str deps-path " is not readable EDN: " (.-message e)))
                         nil))]
        (when deps
          (let [dep-entries (:deps deps)
                target-token (str "kotoba-lang/" expected-target-repo)
                matching (when dep-entries
                           (->> dep-entries
                                (filter (fn [[k _]] (str/includes? (str k) target-token)))
                                first))]
            (cond
              (nil? matching)
              (fail! :target-dependency-declared
                     (str deps-path " has no :deps entry mentioning " target-token
                          " — the target kotoba-lang library was never actually "
                          "wired in as a dependency."))

              (contains? (second matching) :local/root)
              (fail! :target-dependency-not-local-root
                     (str deps-path "'s " (first matching) " entry uses "
                          ":local/root — unresolvable from a fresh clone, and "
                          "exactly what candidates.cljs excludes repos for. If "
                          "this repo genuinely needs :local/root for this "
                          "dependency it does not belong in this bot's "
                          "candidate pool at all."))

              :else nil)))))))

;; --------------------------------------------- 7. self-consistent counts

(when (< (:tests-after proposal -1) (:tests-before proposal 0))
  (fail! :test-count-not-regressed
         (str "proposal declares :tests-before " (:tests-before proposal)
              " and :tests-after " (:tests-after proposal) " — a rewire that "
              "makes the test count go DOWN dropped or disabled tests, and "
              "is rejected regardless of what the test command itself says.")))

;; --------------------------------------------------------- 8. suite passes

(def known-count-patterns
  ;; [regex group-index] — first match wins. Best-effort only; see docstring
  ;; item 9 and the README for what this does NOT cover.
  [[#"Ran (\d+) tests?" 1]
   [#"(\d+) tests?,\s*\d+ assertions" 1]
   [#"(\d+) tests? (?:ran|passed)" 1]
   [#"Tests:\s+(\d+) passed" 1]])

(defn extract-test-count [output]
  (some (fn [[pat idx]]
          (when-let [m (re-find pat output)]
            (js/parseInt (nth m idx) 10)))
        known-count-patterns))

(when (empty? @findings)
  (let [cmd-parts (str/split (:test-command proposal) #"\s+")
        r (sh (first cmd-parts) (rest cmd-parts) {:cwd repo-root :timeout test-timeout-ms})
        combined (str (:stdout r) (:stderr r))]
    (println (str "test-command\t" (:test-command proposal) "\texit=" (:status r)))
    (cond
      (not (zero? (:status r)))
      (fail! :suite-passes (str "`" (:test-command proposal) "` exited " (:status r)
                                 ":\n" combined))

      (str/blank? combined)
      (fail! :suite-ran-something (str "`" (:test-command proposal) "` exited 0 but "
                                        "produced no output at all."))

      :else
      (let [observed (extract-test-count combined)]
        (if (some? observed)
          (if (= observed (:tests-after proposal))
            (println (str "observed-test-count\t" observed "\tmatches declared :tests-after"))
            (fail! :declared-count-matches-observed
                   (str "test output reports " observed " tests but the proposal "
                        "declared :tests-after " (:tests-after proposal) " — the "
                        "proposal's own evidence disagrees with what actually ran.")))
          (println (str "observed-test-count\tUNKNOWN\t⚠ this test runner's output "
                        "did not match any recognized count pattern — :tests-before/"
                        ":tests-after (" (:tests-before proposal) "/" (:tests-after proposal)
                        ") is SELF-REPORTED and NOT independently verified for this run. "
                        "See README: this is the gate's known weak point.")))))))

;; ------------------------------------------------------------------ verdict

(if (empty? @findings)
  (do (println)
      (println "ACCEPTED — namespace scope, file-level rewire completeness, "
                "declared dependency, and the target repo's own test command "
                "all check out.")
      (.exit js/process 0))
  (do (println)
      (println (str "REJECTED — " (count @findings) " check(s) failed:"))
      (doseq [f @findings]
        (println (str "  " (name (:check f)) "\n    " (:why f))))
      (.exit js/process 1)))
