;; scripts/verify-portable-source-tested-on-one-host.cljs
;;
;; Which `.cljc` SOURCE namespaces are tested only by `.clj` tests.
;;
;; ## The defect this exists for, measured
;;
;; 2026-09-08, `kotoba-lang/kotoba-sema`. `(defn main [] :f32 1.5)` compiled on
;; the JVM and was REFUSED on nbb -- `expression type mismatch: expected f32,
;; got f64`. So did `(f32-add 1.5 2.5)`. No f32 expression with a number in it
;; could be written on the runtime this workspace is migrating TO, and
;; `bin/amu`'s default target runs there.
;;
;; The cause was one branch in a `.cljc` file guarded on a HOST double, which a
;; decimal literal only ever is on the JVM. The reason nobody knew is the
;; subject of this script: the only test of the feature was
;; `test/kotoba/compiler/f32_literal_test.clj` -- a `.clj` file. The suite that
;; exercised the feature ran on the one host where the feature worked.
;;
;; ## Why the two sibling detectors were both green on that file
;;
;; This is the part that justifies a third script rather than a flag on one of
;; the existing two. Checked directly, on the tree that had the bug:
;;
;;   verify-cljs-runner-completeness  compares `.cljc` TEST namespaces against
;;     the runner's list. `f32_literal_test.clj` is not `.cljc`, so it is not a
;;     candidate and was never a finding. (It correctly reported four OTHER
;;     kotoba-sema namespaces; none of them was this one.)
;;
;;   verify-cljc-runtime-parity  asks whether a repo has ANY ClojureScript
;;     verification path. kotoba-sema has one -- `run-tests.cljs`, green at 356
;;     tests -- so once that detector was taught to look in the repo root
;;     (2026-09-08, 325 -> 271 findings) kotoba-sema stops being a finding
;;     there too.
;;
;; Both are right about their own question. Neither asks this one: is a
;; PORTABLE SOURCE namespace reached only by tests that cannot run on the
;; second host? That gap is where a feature can be missing on ClojureScript
;; while every dashboard is green.
;;
;; ## What is and is not a finding
;;
;; FINDING `only-jvm-tested` -- a `.cljc` source namespace referenced by one or
;; more `.clj` tests and by zero `.cljc`/`.cljs` tests. This is the deceptive
;; case: it LOOKS tested. It is tested on one host.
;;
;; NOT a finding, counted and printed instead -- a `.cljc` source namespace no
;; test references at all. That is honestly untested, and nobody reading a
;; green suite is being misled about it. Mixing the two would bury the ~20
;; deceptive cases under thousands of ordinary ones, and this script exists
;; because the deceptive ones are invisible.
;;
;; A `.clj` test can legitimately pin JVM-only behaviour (a `java.io` boundary,
;; a Chicory call). The finding is `read this`, not `this is broken`. The
;; question it puts to a reader is narrow and answerable: does this test assert
;; anything that is supposed to hold on both hosts?
;;
;; ## Refusals
;;
;; A source or test file that cannot be read is counted as `unreadable` and
;; reported, never silently skipped -- "I could not read it" must not land in
;; the same bucket as "I read it and found nothing". Fewer than `min-repos`
;; registered checkouts on disk prints `SCANNED 0` and exits **2**, which is
;; neither the 0 of a clean run nor the 1 of a failing one.
;;
;; usage:
;;   nbb --classpath ".:scripts/nbb_compat" \
;;     scripts/verify-portable-source-tested-on-one-host.cljs \
;;     [--findings] [--strict] [--repo orgs/<org>/<name>] [--verbose] [--self-test]
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def node-path (js/require "node:path"))
(def argv (vec (drop 2 js/process.argv)))
(defn flag? [f] (some #{f} argv))
(defn opt [f] (second (drop-while #(not= f %) argv)))
(def findings? (flag? "--findings"))
(def strict? (flag? "--strict"))
(def verbose? (flag? "--verbose"))
(def self-test? (flag? "--self-test"))
(def one-repo (opt "--repo"))
(def max-findings
  "How many FINDING lines to emit. The whole-workspace answer is in the
  THOUSANDS -- 5,445 namespaces over 4,270 checkouts, measured 2026-09-08 --
  and a list that long is not a work queue, it is a wall. What is suppressed is
  always ANNOUNCED on its own line: a truncation nobody can see is the defect
  this workspace has already paid for in `grep | head`.

  `0` means no cap, and that is what the registered detector run passes -- see
  the comment at the `take`."
  (js/parseInt (or (opt "--max-findings") "200") 10))
(def root (.cwd js/process))
(defn full [p] (.join node-path root p))
(def min-repos 500)

(def prune-dirs
  #{"node_modules" ".git" ".shadow-cljs" ".cache" "target" "dist" "build"
    "out" "coverage" "vendor" "_archive" "_working" ".cpcache" ".gitlibs"})

(defn slurp* [p]
  (try (.readFileSync fs p "utf8") (catch :default _ ::unreadable)))
(defn dir? [p] (try (.isDirectory (.statSync fs p)) (catch :default _ false)))
(defn ls [p] (try (vec (.readdirSync fs p)) (catch :default _ ::error)))

(def max-files 4000)

(defn walk
  "Files under D, pruning the usual generated trees. Depth-bounded, and
  file-count-bounded so one pathological repo cannot stall the sweep."
  [d depth acc]
  (if (or (> depth 8) (>= (count acc) max-files))
    acc
    (let [entries (ls d)]
      (if (= entries ::error)
        acc
        (reduce (fn [a e]
                  (cond
                    (>= (count a) max-files) a
                    (contains? prune-dirs e) a
                    :else (let [f (.join node-path d e)]
                            (if (dir? f) (walk f (inc depth) a) (conj a f)))))
                acc entries)))))

;; --- namespace extraction --------------------------------------------------

(def ns-form-re
  "The `(ns ...)` form's name, with an optional metadata prefix.

  The first version wrote the metadata part as `\\^?\\{?[^\\s]*\\}?\\s*`, which is
  optional-but-greedy: on `(ns kotoba.compiler.frontend` it ate the name and
  returned `d`. The self-test below caught that on the first run, which is the
  only reason this comment exists rather than a silently wrong sweep."
  #"\(ns\s+(?:\^[^\s]+\s+)?([a-zA-Z0-9_.*+!\-'?<>=$%&|]+)")

(defn file-ns
  "The namespace a Clojure-family file declares, or nil.

  Reads only the `(ns ...)` form. A file with no `ns` -- an ordinary nbb script
  driven by a top-level `require` -- has no namespace to attribute, and is
  counted, not refused: refusing to answer a question you can answer is the
  same defect as answering one you cannot, pointed the other way."
  [content]
  (when (string? content)
    (some-> (re-find ns-form-re content) second)))

(defn references?
  "Does TEST-CONTENT name NS in a position that reads as a dependency?

  Deliberately not a bare substring test: `kotoba.sema` appears inside
  `kotoba.sema.internal` and inside prose. The symbol must be delimited on both
  sides -- preceded by `[`, `'` or whitespace, and followed by whitespace,
  `]`, `)`, `:`, `,` or end -- which is how it appears in a `:require` vector,
  a quoted symbol, or an alias position.

  The closing `)` was missing from the first version, so `(run-tests
  'kotoba.sema)` did not count as a reference. The self-test caught it; a sweep
  would not have, because the effect is a MISSING finding and this script's
  output is a count."
  [test-content ns]
  (boolean
   (and (string? test-content)
        (re-find (re-pattern (str "(^|[\\[' \\n\\t])"
                                  (str/replace ns #"[.*+?^${}()|\[\]\\]" "\\$&")
                                  "($|[\\s\\]\\):,])"))
                 test-content))))

;; --- per-repo scan ---------------------------------------------------------

(defn scan-repo [rel abs]
  (let [src-dir (.join node-path abs "src")
        test-dirs (filterv dir? [(.join node-path abs "test")
                                 (.join node-path abs "tests")])
        src-files (if (dir? src-dir) (walk src-dir 0 []) [])
        test-files (reduce (fn [a d] (walk d 0 a)) [] test-dirs)
        cljc-src (filterv #(str/ends-with? % ".cljc") src-files)
        tests (filterv #(or (str/ends-with? % ".clj")
                            (str/ends-with? % ".cljc")
                            (str/ends-with? % ".cljs"))
                       test-files)
        ;; read once; a file read twice is a file that can disagree with itself
        read-one (fn [f] (let [c (slurp* f)] {:path f :content c}))
        src-read (mapv read-one cljc-src)
        test-read (mapv read-one tests)
        unreadable (+ (count (filter #(= ::unreadable (:content %)) src-read))
                      (count (filter #(= ::unreadable (:content %)) test-read)))
        jvm-tests (filterv #(str/ends-with? (:path %) ".clj") test-read)
        portable-tests (filterv #(not (str/ends-with? (:path %) ".clj")) test-read)
        results
        (for [{:keys [path content]} src-read
              :let [ns (file-ns content)]
              :when ns
              :let [jvm (filterv #(references? (:content %) ns) jvm-tests)
                    portable (filterv #(references? (:content %) ns) portable-tests)]]
          {:ns ns
           :path (subs path (inc (count abs)))
           :jvm (mapv #(subs (:path %) (inc (count abs))) jvm)
           :portable-count (count portable)})]
    {:repo rel
     :cljc-src (count cljc-src)
     :unreadable unreadable
     :only-jvm (filterv #(and (seq (:jvm %)) (zero? (:portable-count %))) results)
     :untested (count (filter #(and (empty? (:jvm %)) (zero? (:portable-count %))) results))
     :both (count (filter #(pos? (:portable-count %)) results))}))

;; --- self-test -------------------------------------------------------------
;;
;; The point of a control here is that this script's own answer is a COUNT, and
;; a count of zero is what both "nothing is wrong" and "I matched nothing" look
;; like. So the control asserts both directions.

(when self-test?
  (let [ok (atom 0) bad (atom 0)
        check (fn [label expected actual]
                (if (= expected actual)
                  (swap! ok inc)
                  (do (swap! bad inc)
                      (println "SELFTEST FAIL" label "expected" (pr-str expected)
                               "got" (pr-str actual)))))]
    (check "ns is extracted"
           "kotoba.compiler.frontend"
           (file-ns "(ns kotoba.compiler.frontend\n  \"doc\"\n  (:require [x]))"))
    (check "ns with metadata is extracted"
           "a.b" (file-ns "(ns ^:no-doc a.b (:require [c]))"))
    (check "no ns form yields nil" nil (file-ns "(require '[x])\n(println 1)"))
    (check "a require vector counts as a reference"
           true (references? "(ns t (:require [kotoba.sema :as s]))" "kotoba.sema"))
    (check "a quoted symbol counts"
           true (references? "(run-tests 'kotoba.sema)" "kotoba.sema"))
    ;; The one that matters: a longer namespace must not match a shorter one.
    (check "a longer namespace does NOT match a shorter one"
           false (references? "(ns t (:require [kotoba.sema.internal :as i]))" "kotoba.sema"))
    (check "an unrelated namespace does not match"
           false (references? "(ns t (:require [other.thing]))" "kotoba.sema"))
    ;; The real file that motivated this script, in both directions.
    (check "the f32 case: a .clj test does reach kotoba.sema"
           true (references? "(ns kotoba.compiler.f32-literal-test\n  (:require [clojure.test :refer [deftest is testing]]\n            [kotoba.sema :as sema]))"
                             "kotoba.sema"))
    (println (str "SELFTEST\t" @ok " passed, " @bad " failed"))
    (.exit js/process (if (pos? @bad) 1 0))))

;; --- sweep -----------------------------------------------------------------

(def west (slurp* (full "manifest/west.yml")))
(when (= west ::unreadable)
  (println "REFUSED\tno readable manifest/west.yml under" root)
  (.exit js/process 2))

(def registered
  (->> (str/split-lines west)
       (keep #(second (re-find #"^\s*path:\s*(orgs/\S+)" %)))
       set))

(def in-scope
  (cond->> (sort registered)
    one-repo (filter #(= % one-repo))
    true     (filterv #(dir? (full %)))))

;; `--repo` skips the floor below, so it needs its own refusal. Without this,
;; pointing `--repo` at a path that is not on disk scanned nothing and printed
;; `only-jvm-tested 0` -- the same output as a repository with no defect.
;;
;; Measured, on this script, 2026-09-08: run from a sparse worktree that has
;; `manifest/west.yml` but no `orgs/`, `--repo orgs/kotoba-lang/kotoba-native`
;; answered 0. The full sweep over the real tree answered 9, naming
;; `kotoba.native.aarch64`, `macho`, `peephole`, `x86-64` and five more. The
;; wrong answer was the confident-looking one.
(when (and one-repo (empty? in-scope))
  (println (str "SCANNED\t0\t" one-repo " is not a readable checkout under " root))
  (println "REFUSED\trefusing to report a pass for a repository I could not read")
  (.exit js/process 2))

(when (and (not one-repo) (< (count in-scope) min-repos))
  (println (str "SCANNED\t0\tonly " (count in-scope)
                " registered checkout(s) on disk, below the floor of " min-repos))
  (println "REFUSED\trefusing to report a pass over a tree this thin")
  (.exit js/process 2))

(def scans (mapv #(scan-repo % (full %)) in-scope))
(def findings
  (vec (for [s scans, r (:only-jvm s)]
         {:repo (:repo s) :ns (:ns r) :path (:path r) :jvm (:jvm r)})))

(println (str "verify-portable-source-tested-on-one-host "
              {:checkouts (count in-scope)
               :cljc-sources (reduce + (map :cljc-src scans))
               :unreadable (reduce + (map :unreadable scans))}))
(println)
(println (str "  only-jvm-tested   " (count findings)
              "  .cljc source namespace(s) reached only by .clj tests"))
(println (str "  tested-on-both    " (reduce + (map :both scans))
              "  .cljc source namespace(s) a portable test also reaches"))
(println (str "  no-test-at-all    " (reduce + (map :untested scans))
              "  .cljc source namespace(s) no test references -- honestly untested, NOT a finding"))
(println)
(println "  the middle number is the one that can mislead: it looks tested, and it is")
(println "  tested on one host. See this file's header for the measured example.")
(println)
;; The total is a measure of the class, not a backlog. What makes it actionable
;; is which repositories carry it, so that is printed unasked.
(let [by-repo (->> findings (group-by :repo) (map (fn [[r fs]] [r (count fs)]))
                   (sort-by (comp - second)))]
  (println "  worst repositories:")
  (doseq [[r n] (take 12 by-repo)]
    (println (str "    " (subs (str r "                                                  ") 0 50) n)))
  (println (str "    ... " (max 0 (- (count by-repo) 12)) " more repositories with at least one")))

(when verbose?
  (doseq [f (sort-by (juxt :repo :ns) findings)]
    (println "   " (:repo f) (:ns f) "<-" (str/join " " (:jvm f)))))

(when findings?
  (println (str "SCANNED\t" (count in-scope) "\tregistered repo(s) present on disk"))
  (let [ordered (sort-by (juxt :repo :ns) findings)
        ;; 0 means no cap. The registered detector run uses 0 deliberately: a
        ;; truncated finding SET makes the NEW/RESOLVED diff between runs lie,
        ;; because entries enter and leave the window as the sort shifts under
        ;; them. A cap is for a human reading a terminal, never for the state
        ;; a differ is kept in.
        shown (if (zero? max-findings) ordered (take max-findings ordered))
        hidden (- (count ordered) (count shown))]
    (doseq [f shown]
      (println (str "FINDING\tfail\tonly-jvm-tested:" (:repo f) ":" (:ns f)
                    "\t" (:path f) " is .cljc; the only tests reaching it are "
                    (str/join ", " (:jvm f))
                    " -- portable source verified on one host")))
    (when (pos? hidden)
      ;; Announced, never silent. `--max-findings 0` prints every one.
      (println (str "TRUNCATED\t" hidden "\tfurther finding(s) not printed"
                    " (--max-findings " max-findings "); raise it or use --repo")))))

(when (and strict? (seq findings))
  (.exit js/process 1))
