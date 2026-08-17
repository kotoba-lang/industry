#!/usr/bin/env nbb
;; verify-cljs-runner-completeness — ClojureScript test runners that run less
;; than the suite they sit in, and report the same thing a complete one does.
;;
;; ## The defect this exists for, measured
;;
;; `kotoba-lang/columnar` had three test namespaces and two ClojureScript
;; runners. Both required exactly one of the three. Measured 2026-08-17, before
;; the repair:
;;
;;   clojure -M:test          Ran 36 tests containing 117 assertions
;;   nbb test/run.cljs        Ran 10 tests containing  54 assertions
;;
;; Nothing prints the number it should have reached, so the shortfall is not
;; visible in the output. With `group/group` altered to drop the last group from
;; every grouped result -- a defect the JVM suite catches at once -- the partial
;; runner printed `Ran 10 tests ... 0 failures` and exited 0.
;;
;; That matters because the workspace is moving `:jvm-test` fleet gates to
;; `:nbb-test` (ADR-2608170200). The gate's own floor only requires that SOME
;; `Ran N tests` line appear, which a one-third runner satisfies. So a gate can
;; be moved to a cheaper node and silently stop testing two thirds of its
;; subject, with an identical signed receipt. This is ADR-2608136000's class
;; reached from the direction of a migration: the check that could not measure
;; returns the same value as the check that measured and found nothing.
;;
;; ## What counts as a runner, and why that line is principled
;;
;; A runner is a non-test `.cljs`/`.cljc` file that CALLS `run-tests` or
;; `run-all-tests`. That definition, rather than an exemption list, is what
;; keeps `kotobase-storage/test/run.cljs` out of scope: it is a hand-written
;; async oracle script that deliberately covers the Worker path the JVM suite
;; cannot reach, calls no `run-tests`, and prints no `Ran N tests` line at all.
;; It is not an incomplete port of the JVM suite; it is a different suite. Those
;; files are counted as `:oracle-script` so they stay visible, never as a
;; finding.
;;
;; ## What is expected to be covered
;;
;; Test namespaces in `.cljc`/`.cljs` files. A `_test.clj` file is JVM-only and
;; cannot run under nbb, so its absence from a ClojureScript runner is not a
;; defect -- it is the `:jvm-source` debt that
;; verify-jvm-dependency-surface.cljs already counts.
;;
;; A `.cljc` test that is absent from the runner IS reported even if the reason
;; is that its body is `#?(:clj ...)` throughout. The reported fact is "the
;; ClojureScript run covers less than the JVM run", which is true either way;
;; why it is true is the repo owner's call.
;;
;; ## The unit is the project, and the question is the UNION of its runners
;;
;; Two false-positive classes made this necessary, both measured 2026-08-17.
;;
;; `gftdcojp/tia` ships five runners -- `run-tests`, `run-image-tests`,
;; `run-store-tests`, `run-ui-tests`, `run-worker-tests`. Each covers one
;; subsystem BY DESIGN, so judging them one at a time called four deliberate
;; subsets defects. Whether a repo splits its runners by subject (tia) or by
;; platform (columnar's SCI half and compiled half) is intent this script cannot
;; read from the source. What it can read, and what actually matters, is whether
;; any test namespace is run by NO runner at all. That is the silent coverage
;; loss; a focused runner is not.
;;
;; `etzhayyim/root` is a monorepo holding many vendored projects, so comparing
;; `20-actors/akashi/run_tests.cljs` against every `deftest` in the checkout
;; reported "1074 of 1079 missing". The unit is therefore the subtree the runner
;; SITS IN -- a `test/`/`tests/` component stripped back to its parent, else the
;; runner's own directory -- widened to a `deps.edn` ancestor when there is one.
;; A deps.edn rule alone was not enough: that monorepo's subprojects carry no
;; deps.edn, so akashi still collapsed to the repo root. Scoped correctly it
;; reports 2 of 7, which matching the runner against the tree by hand confirms.
;;
;; ## Two distinct failures, because requiring is not running
;;
;;   :suite-uncovered  no runner in the project names the namespace. It cannot
;;                     be running anywhere.
;;   :loaded-not-run   some runner requires it, but no runner passes it to
;;                     `run-tests` and none calls `run-all-tests`. Loading a
;;                     test namespace registers its vars; only `run-tests` runs
;;                     them. columnar's own fix had to change both the
;;                     `:require` vector AND the `run-tests` call, so this is
;;                     the half that a require-only audit would miss.
;;
;; ## Refusals
;;
;; A runner file that cannot be read is `:runner-unreadable`, not skipped -- "I
;; could not read it" must not land in the same bucket as "I read it and it was
;; complete". A run that finds fewer than `min-repos` registered checkouts on
;; disk prints `SCANNED 0` and exits **2**, neither 0 nor 1.
;;
;; Note what is deliberately NOT a refusal: a runner with no `(ns ...)` form.
;; The first version demanded one and reported 35 files unmeasurable; every one
;; was an ordinary nbb script using a top-level `(require '[...])`, and one of
;; them was a complete runner. Refusing to answer a question you can answer is
;; the same defect as answering one you cannot, pointed the other way.
;;
;; usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-cljs-runner-completeness.cljs
;;     [--findings] [--strict] [--repo orgs/<org>/<name>] [--edn out.edn] [--verbose]
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def node-path (js/require "node:path"))
(def argv (vec (drop 2 js/process.argv)))
(defn flag? [f] (some #{f} argv))
(defn opt [f] (second (drop-while #(not= f %) argv)))
(def findings? (flag? "--findings"))
(def strict? (flag? "--strict"))
(def verbose? (flag? "--verbose"))
(def one-repo (opt "--repo"))
(def edn-out (opt "--edn"))
(def root (.cwd js/process))
(defn full [p] (.join node-path root p))
(def min-repos 500)

(def prune-dirs
  #{"node_modules" ".git" "target" ".cpcache" ".shadow-cljs" "out" "dist"
    ".datalad" ".claude" "vendor"})

(defn walk-files [dir]
  (let [acc (volatile! [])]
    (letfn [(go [d depth]
              (when (< depth 12)
                (doseq [e (try (.readdirSync fs d #js {:withFileTypes true})
                               (catch :default _ #js []))]
                  (let [nm (.-name e) p (.join node-path d nm)]
                    (cond
                      (.isDirectory e)
                      (when-not (or (contains? prune-dirs nm) (str/starts-with? nm "."))
                        (go p (inc depth)))
                      (.isFile e) (vswap! acc conj p))))))]
      (go dir 0))
    @acc))

(defn read-text [p] (try (.readFileSync fs p "utf8") (catch :default _ nil)))

;; ---------------------------------------------------------------------------
;; Reading the source. The ns name comes from the `(ns ...)` form rather than
;; from the path, because the path->namespace rule (underscore to hyphen, dir to
;; dot) is a convention a file is free to break, and a mismatch here would
;; invent a missing namespace out of a correct runner.
(defn ns-of [text]
  (second (re-find #"\(ns\s+\^?[:a-z{}\s]*?([a-zA-Z][a-zA-Z0-9._<>*+!?-]*)" text)))

(def ns-token-re #"[a-zA-Z][a-zA-Z0-9._<>*+!?-]*")

(defn names-mentioned
  "Every namespace-shaped token in `text`. Deliberately over-inclusive: it is
   used only to answer 'is this namespace named here at all', where a false
   positive makes the check quieter, never louder."
  [text]
  (set (re-seq ns-token-re (or text ""))))

(defn run-targets
  "The namespaces a runner actually RUNS.

   `{:all? true}` when it calls `run-all-tests` (every loaded namespace, so
   completeness reduces to what is required). `{:current-ns-only? true}` when it
   calls `(run-tests)` with no arguments -- that runs the runner's own namespace,
   which contains no tests, and is its own kind of silent zero."
  [text]
  (let [all? (boolean (re-find #"run-all-tests" text))
        ;; BOTH quote forms. `etzhayyim/root`'s akashi runner writes
        ;; `(quote akashi.adapters.test-edn-query)` rather than `'akashi...`
        ;; (it builds the expression as a string and hands it to a child
        ;; process), and reading only the reader-macro form reported all five
        ;; of its genuinely-run namespaces as required-but-never-run.
        quoted (into (set (map second (re-seq #"'([a-zA-Z][a-zA-Z0-9._<>*+!?-]*)" text)))
                     (map second (re-seq #"\(\s*quote\s+([a-zA-Z][a-zA-Z0-9._<>*+!?-]*)" text)))
        bare-call? (boolean (re-find #"run-tests\s*\)" text))]
    {:all? all? :quoted quoted
     :current-ns-only? (and bare-call? (empty? quoted) (not all?))}))

(defn runner? [text]
  (boolean (and text (re-find #"\(\s*[a-zA-Z0-9._/-]*run-(all-)?tests" text))))

;; ---------------------------------------------------------------------------
;; Auto-discovering runners.
;;
;; `kotoba-lang/kotobase-peer/run-nbb-tests.cljs` walks `test/`, turns each path
;; into a namespace, writes a static-require entry file and runs nbb on it. No
;; namespace appears literally in its source, so a purely static audit reported
;; "19 of 19 named by no runner" -- against a runner that covers every one by
;; construction AND has its own zero floor (`no *_test.cljc files found` ->
;; exit 1). Reporting that as a defect would push the fleet away from the better
;; pattern: discovery cannot drift, a hand-written require list can.
;;
;; Being discovering is not a blanket pass, though. A runner that discovers only
;; `_test.cljc` genuinely misses a `_test.cljs` sibling, and one that walks
;; `test/` genuinely misses a test outside it. So the literals it matches on are
;; extracted and applied, rather than assumed to be exhaustive.
(def dir-walk-re #"readdirSync|readdir|file-seq|globSync|\bglob\b|walk-files")

(defn discovery
  "`nil` unless the runner enumerates the filesystem; otherwise
   `{:suffixes #{...} :roots #{...}}` -- the literals it actually matches on,
   empty meaning 'no restriction detectable'."
  [text]
  (when (and (re-find dir-walk-re text)
             (re-find #"[_-]test" text))
    {:suffixes (set (map second (re-seq #"\"([_a-zA-Z0-9.]*[_-]test\.clj[sc]?)\"" text)))
     :roots (set (keep (fn [[_ s]] (when (re-matches #"[a-z][a-z0-9_/-]*" s) s))
                       (re-seq #"\"([a-z][a-z0-9_/-]*)\"" text)))}))

(defn discovered?
  "Would this discovering runner find `path` (relative to the repo)?"
  [{:keys [suffixes roots]} path]
  (and (or (empty? suffixes) (some #(str/ends-with? path %) suffixes))
       ;; A root literal only excludes when at least one of them looks like a
       ;; directory this repo actually nests tests under.
       (or (empty? roots)
           (some #(or (str/starts-with? path (str % "/")) (= % "."))
                 roots))))

;; ---------------------------------------------------------------------------
(defn inspect-repo [rel]
  (let [abs (full rel)]
    (when (try (.isDirectory (.statSync fs abs)) (catch :default _ false))
      (let [files (walk-files abs)
            rels (map #(.relative node-path abs %) files)
            by-rel (zipmap rels files)
            clj-family (filter #(re-find #"\.clj[sc]?$" %) rels)
            texts (into {} (map (fn [r] [r (read-text (by-rel r))]) clj-family))
            ;; Classification is by CONTENT, not by filename. Measured
            ;; 2026-08-17 across the registered checkouts, `.cljc`/`.cljs`
            ;; files under a test directory come in at least six name shapes:
            ;; 5,690 `*_test`, 1,081 `test_*`, 35 `*_tests`, 6 bare
            ;; `test`/`tests`, 86 with `run` in the name, and 270 others
            ;; (`block_vectors`, `browser_harness`, `apex_worker_smoke`).
            ;; A `_test$` rule -- the first version of this script -- read the
            ;; 1,081 `test_*` files as RUNNERS, because they are not tests by
            ;; that rule and they mention namespaces, and produced a false
            ;; finding for each. `deftest` and `run-tests` are exact.
            has-deftest? (fn [r] (boolean (re-find #"\(\s*deftest" (or (texts r) ""))))
            portable? (fn [r] (re-find #"\.clj[sc]$" r))
            ;; ns -> path for every test namespace a ClojureScript runner could
            ;; reach. A `.clj` file holding deftest is JVM-only and is counted
            ;; separately, never expected of a cljs runner.
            expected (into {} (keep (fn [r]
                                      (when (and (portable? r) (has-deftest? r))
                                        (when-let [n (ns-of (texts r))] [n r])))
                                    clj-family))
            clj-only-tests (filter #(and (re-find #"\.clj$" %) (has-deftest? %)) clj-family)
            ;; A file that defines tests is a test namespace, never a runner,
            ;; even when it also calls run-tests on itself.
            runners (filter #(and (portable? %) (not (has-deftest? %))
                                  (runner? (texts %)))
                            clj-family)
            oracles (filter (fn [r]
                              (and (portable? r) (not (has-deftest? r))
                                   (not (runner? (texts r)))
                                   (re-find #"(^|/)(test|tests)/" r)))
                            clj-family)
            ;; Project boundaries: every directory holding a deps.edn, plus the
            ;; repo root. A file belongs to the LONGEST such prefix, which is the
            ;; same rule the Clojure CLI applies when you cd into a subproject.
            project-dirs (->> rels
                              (filter #(= "deps.edn" (.basename node-path %)))
                              (map #(let [d (.dirname node-path %)]
                                      (if (= d ".") "" d)))
                              (cons "")
                              distinct
                              (sort-by (comp - count))
                              vec)
            deps-project (fn [r]
                           (or (first (filter #(or (= "" %) (str/starts-with? r (str % "/")))
                                              project-dirs))
                               ""))
            ;; Where the runner SITS is the second boundary, and the load-bearing
            ;; one. `etzhayyim/root` is a monorepo whose subprojects carry no
            ;; deps.edn of their own, so `20-actors/akashi/run_tests.cljs` fell
            ;; back to the repo root and was asked to cover all 1,033 test
            ;; namespaces in the checkout. A runner answers for the subtree it
            ;; lives in: strip a `test/` or `tests/` component to get the project
            ;; the suite belongs to, else take the runner's own directory.
            sits-in (fn [r]
                      (if-let [m (re-find #"^(.*?)(?:^|/)(?:test|tests)/" r)]
                        (second m)
                        (let [d (.dirname node-path r)] (if (= d ".") "" d))))
            project-of (fn [r]
                         (let [a (deps-project r) b (sits-in r)]
                           (if (> (count b) (count a)) b a)))]
        {:repo rel
         :projects (count project-dirs)
         :expected expected
         :clj-only-tests (count clj-only-tests)
         :oracles (vec oracles)
         :runners
         (vec (for [r runners
                    :let [t (texts r)
                          rns (ns-of t)
                          {:keys [all? quoted current-ns-only?]} (run-targets t)
                          mentioned (names-mentioned t)
                          ;; One BFS hop through an aggregator namespace: a
                          ;; runner may require `all-tests`, which requires the
                          ;; rest. Without this the aggregator pattern would be
                          ;; reported as 100% missing.
                          reachable
                          (loop [seen #{} frontier (set mentioned) depth 0]
                            (if (or (> depth 3) (empty? frontier))
                              seen
                              (let [seen' (into seen frontier)
                                    next-txt (keep (fn [[n rr]]
                                                     (when (and (frontier n) (not (seen n)))
                                                       (read-text (by-rel rr))))
                                                   expected)
                                    grown (reduce into #{} (map names-mentioned next-txt))]
                                ;; `set`, not the bare `remove` seq: the next
                                ;; iteration calls `frontier` as a predicate.
                                (recur seen' (set (remove seen' grown)) (inc depth)))))
                          disc (discovery t)
                          loaded (if disc
                                   (set (keep (fn [[n p]] (when (discovered? disc p) n))
                                              expected))
                                   (set (filter reachable (keys expected))))
                          run-set (cond disc loaded
                                        all? loaded
                                        current-ns-only? #{}
                                        :else (set (filter quoted (keys expected))))]]
                {:path r
                 :ns rns
                 ;; The honest refusal is "I could not read the file", not "it
                 ;; has no (ns ...) form". Measured 2026-08-17, all 35 files
                 ;; that this script first reported as unparsed were ordinary
                 ;; nbb scripts using a top-level `(require '[...])` instead of
                 ;; an ns declaration -- a legitimate idiom, and one that does
                 ;; not impede this check at all, since completeness is read
                 ;; from the names mentioned and the run-tests targets. One of
                 ;; them (cloud-itonami/loop-noren) required and ran all three
                 ;; of its test namespaces: a complete runner, reported as
                 ;; unmeasurable. `ns` is kept for display only.
                 :unreadable? (nil? t)
                 :project (project-of r)
                 :all? all?
                 :current-ns-only? current-ns-only?
                 :loaded loaded
                 :run-set run-set}))
         ;; ns -> path. Membership in a project is decided by PATH CONTAINMENT
         ;; below, not by comparing computed labels: a test that sits outside any
         ;; `test/` directory gets a label of its own (`src/foo`), and equality
         ;; would put it in a project with no runners, where it would produce no
         ;; finding and vanish rather than being reported as uncovered.
         :expected-path expected}))))

;; ---------------------------------------------------------------------------
(def registered
  (if one-repo
    (sorted-set one-repo)
    (->> (str/split-lines (read-text (full "manifest/west.yml")))
         (keep #(second (re-find #"^\s+path:\s*(\S+)\s*$" %)))
         (into (sorted-set)))))

(when (empty? registered)
  (println "SCANNED\t0\tregistered repo(s) -- west.yml yielded no path: entries")
  (binding [*out* *err*] (println "Refusing to report a pass: no paths to inspect."))
  (.exit js/process 2))

(def inspected (vec (keep inspect-repo registered)))

(when (and (not one-repo) (< (count inspected) min-repos))
  (println (str "SCANNED\t" (count inspected) "\tregistered repo(s) present on disk"))
  (binding [*out* *err*]
    (println (str "Refusing to report a pass: only " (count inspected) " of "
                  (count registered) " registered checkouts exist (floor "
                  min-repos "). Could-not-look is not the same as looked-and-clean.")))
  (.exit js/process 2))

;; Only repos that HAVE a ClojureScript runner and at least one cljc/cljs test
;; are in scope. A repo with no runner has no ClojureScript test path to be
;; incomplete; that is the :jvm-source debt the other detector counts.
(def in-scope
  (filter #(and (seq (:runners %)) (seq (:expected-path %))) inspected))

(defn findings-for
  "One judgement per PROJECT, over the union of that project's runners. See the
   header: whether a repo splits runners by subject or by platform is intent this
   cannot read, but 'no runner runs this namespace' is a fact either way."
  [{:keys [repo runners expected-path]}]
  (let [by-project (group-by :project runners)
        projects (set (keys by-project))
        under? (fn [p path] (or (= "" p) (str/starts-with? path (str p "/"))))]
    (mapcat
     (fn [[project rs]]
       (let [;; Tests under this project, excluding any that sit inside a MORE
             ;; specific project that has runners of its own -- otherwise a
             ;; monorepo's root runner would be blamed for every subproject.
             mine (set (keep (fn [[n path]]
                               (when (and (under? project path)
                                          (not (some #(and (not= % project)
                                                           (> (count %) (count project))
                                                           (under? % path))
                                                     projects)))
                                 n))
                             expected-path))
             loaded (reduce into #{} (map :loaded rs))
             run-set (reduce into #{} (map :run-set rs))
             uncovered (sort (remove run-set mine))
             ;; required somewhere, run nowhere -- the half a require-only audit
             ;; would miss
             loaded-not-run (sort (filter #(and (loaded %) (not (run-set %))) mine))
             not-loaded (sort (remove loaded mine))
             label (if (= "" project) "." project)
             where (str/join " " (sort (map :path rs)))]
         (cond-> []
           (some :unreadable? rs)
           (conj {:sev "warn" :kind :runner-unreadable :repo repo :project label
                  :detail (str label ": a runner file could not be read"
                               " -- refusing to call the project covered")})
           (some :current-ns-only? rs)
           (conj {:sev "fail" :kind :runner-runs-nothing :repo repo :project label
                  :detail (str label ": a runner calls (run-tests) with no target,"
                               " which runs its own namespace and no tests")})
           (seq not-loaded)
           (conj {:sev "fail" :kind :suite-uncovered :repo repo :project label
                  :detail (str label ": " (count not-loaded) " of " (count mine)
                               " test namespace(s) named by no runner (" where "): "
                               (str/join " " (take 4 not-loaded)))})
           (seq loaded-not-run)
           (conj {:sev "fail" :kind :runner-loaded-not-run :repo repo :project label
                  :detail (str label ": " (count loaded-not-run) " of " (count mine)
                               " required but handed to no run-tests (" where "): "
                               (str/join " " (take 4 loaded-not-run)))}))))
     by-project)))

(def all-findings (mapcat findings-for in-scope))

;; ---------------------------------------------------------------------------
(println "verify-cljs-runner-completeness"
         (pr-str {:registered (count registered)
                  :present (count inspected)
                  :with-runner (count in-scope)
                  :runners (reduce + (map #(count (:runners %)) in-scope))
                  :oracle-scripts (reduce + (map #(count (:oracles %)) inspected))}))
(println)
(doseq [[k label]
        [[:suite-uncovered       "test namespaces no runner runs"]
         [:runner-loaded-not-run "required but never handed to run-tests"]
         [:runner-runs-nothing   "(run-tests) with no target"]
         [:runner-unreadable     "runner file could not be read"]]]
  (println (str "  " (subs (str (name k) "                       ") 0 24)
                (count (filter #(= k (:kind %)) all-findings)) "  " label)))
(println)
(println "  repos with a complete runner:"
         (count (remove (fn [r] (some #(= (:repo r) (:repo %)) all-findings)) in-scope))
         "of" (count in-scope) "in scope")

(when verbose?
  (doseq [f (sort-by (juxt :repo :project) all-findings)]
    (println "   " (:sev f) (name (:kind f)) (:repo f) "→" (:detail f))))

(when edn-out
  (.writeFileSync fs edn-out (pr-str {:in-scope in-scope :findings all-findings}) "utf8")
  (println "  wrote" edn-out))

(when findings?
  (println (str "SCANNED\t" (count inspected) "\tregistered repo(s) present on disk"))
  (doseq [f (sort-by (juxt :kind :repo :project) all-findings)]
    ;; Key includes the runner path: a repo can ship two halves (SCI and
    ;; compiled) and one can be complete while the other is not.
    (println (str "FINDING\t" (:sev f) "\t" (name (:kind f)) ":" (:repo f)
                  ":" (:project f) "\t" (:detail f)))))

(when (and strict? (some #(= "fail" (:sev %)) all-findings))
  (.exit js/process 1))
