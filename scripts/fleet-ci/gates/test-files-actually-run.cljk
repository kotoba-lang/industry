#!/usr/bin/env nbb
;; test-files-actually-run.cljs — a test file that defines tests and never runs them.
;;
;; WHY THIS EXISTS. Measured 2026-08-17 in network-isekai: `clojure -M
;; test/royale_world_streaming_test.clj` printed nothing and exited 0. The file has
;; `deftest` forms and no `(run-tests)` at the end, so `clojure -M <file>` loads it, defines
;; the vars, runs none of them, and succeeds. **Observationally identical to passing**, and
;; that is the whole problem: every other test file in that directory ends with the runner,
;; so these were invoked the same way, reported the same way, and measured nothing.
;;
;; 4 of the 28 files with deftests were in that state. One of them had been broken for 36
;; days — it required `physics_2d`, renamed upstream to `physics-2d` on 2026-07-12, a rename
;; recorded in that repo's own deps.edn comment on the line beside it. Nobody knew, because
;; the file was never loaded.
;;
;; This is root CLAUDE.md's "「飛ばした」と「合格した」が出力で区別できるか" as an
;; executable check.
;;
;; Node side:  npx nbb test-files-actually-run.cljs <dir> [--sub test] [--min N]
;;             <dir> FIRST — several gates take the tree as the first non-flag argument and
;;             putting it after a flag makes the flag's VALUE the tree (root CLAUDE.md
;;             records three gates misdiagnosed that way).
;;
;; WHAT IT DELIBERATELY DOES NOT DO. It does not run the tests, and it does not care whether
;; they pass. It answers one question — would invoking this file execute anything at all —
;; which is the question that has no other asker.

(ns fleet-ci.gates.test-files-actually-run
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(defn- flag [name default]
  (let [i (.indexOf args name)]
    (if (neg? i) default (nth args (inc i)))))

(def sub (flag "--sub" "test"))
(def min-files (js/parseInt (str (flag "--min" 1)) 10))

;; Directories whose files are COUNTED AND PRINTED but do not fail the gate.
;;
;; This exists for one honest situation: a subtree whose tests are driven by something other
;; than the per-file `(run-tests)` footer, or whose tests cannot be driven at all from a tree
;; alone. Measured in network-isekai: `test/isekai/**` holds 18 `.cljc` suites for the
;; sekaiju prototype that no task, no shadow-cljs build and no namespace anywhere in the repo
;; references — and which need sibling `:local/root` checkouts a fleet node does not have.
;; They are a real finding and a separate piece of work; they are not a reason for this gate
;; to be permanently red, which would make it as useless as never failing.
;;
;; Skipped is NOT passed, and the output says so on its own line every run.
(def skipped-dirs
  (set (remove str/blank? (str/split (str (flag "--skip" "")) #","))))

(def root
  (let [flag-values (set (remove nil? [(flag "--sub" nil) (flag "--min" nil) (flag "--skip" nil)]))
        positional (remove #(or (str/starts-with? % "--") (flag-values %)) args)]
    (or (first positional) ".")))

(def scan-dir (path/join root sub))

(def skip-dirs #{"node_modules" ".git" "target" ".shadow-cljs" "out" "dist"})
(def test-ext [".clj" ".cljc" ".cljs"])

(defn- source-files [dir]
  (let [out (atom [])]
    ((fn walk [d]
       (doseq [e (fs/readdirSync d #js {:withFileTypes true})]
         (let [n (.-name e) p (path/join d n)]
           (cond
             (.isDirectory e) (when-not (contains? skip-dirs n) (walk p))
             (some #(str/ends-with? n %) test-ext) (swap! out conj p)))))
     dir)
    @out))

;; A directory that is not there means the tree was filtered or extracted wrong. Reporting
;; "0 files, all fine" would be the exact failure this gate exists to catch, so it refuses
;; to answer instead — exit 90, which is neither pass (0) nor fail (1).
(when-not (fs/existsSync scan-dir)
  (println "FLEET-CI: no such directory:" scan-dir
           "— extraction or --sub is wrong, refusing to report a pass")
  (js/process.exit 90))

(let [files (source-files scan-dir)
      analysed (for [f files
                     :let [src (str (fs/readFileSync f "utf8"))]
                     ;; Only files that DEFINE tests are asked to run them. A shared helper
                     ;; namespace under test/ has nothing to run and is not a finding.
                     :when (re-find #"\(deftest\b" src)]
                 {:file f
                  ;; `(run-tests)`, `(run-tests 'ns)` and clojure.test/run-tests all count.
                  ;; A file that hands off to an external runner declares it the same way.
                  :runs? (boolean (re-find #"\(\s*(?:clojure\.test/)?run-tests\b" src))
                  ;; Can this file be invoked directly at all?
                  ;;
                  ;; A suite that only refers `cljs.test :refer-macros` is compiled by a
                  ;; ClojureScript runner; `clojure -M <file>` cannot load it, so demanding a
                  ;; `(run-tests)` footer of it is the wrong question and a footer would not
                  ;; make it run. Those are reported on their own line instead of failed.
                  ;; (`.cljc` files that reader-condition into `clojure.test` under `:clj`
                  ;; ARE directly invocable, and are held to the rule.)
                  :jvm-invocable? (boolean (re-find #"\bclojure\.test\b" src))})
      in-skipped? (fn [{:keys [file]}]
                    (let [rel (path/relative scan-dir file)]
                      (some #(or (= rel %) (str/starts-with? rel (str % path/sep)))
                            skipped-dirs)))
      all-dead (filterv (complement :runs?) analysed)
      cljs-only (filterv (complement :jvm-invocable?) all-dead)
      jvm-dead (filterv :jvm-invocable? all-dead)
      skipped-dead (filterv in-skipped? jvm-dead)
      dead (filterv (complement in-skipped?) jvm-dead)]

  ;; SCANNED is printed unconditionally and before any verdict, so a run that examined
  ;; nothing cannot be mistaken for a run that found nothing wrong.
  (println (str "SCANNED\t" (count files) " source file(s) under " sub
                ", " (count analysed) " with deftest, " (count all-dead) " with no runner"))
  (when (seq cljs-only)
    (println (str "CLJS-RUNNER\t" (count cljs-only)
                  " file(s) target a ClojureScript runner, so a (run-tests) footer is the"
                  " wrong question for them. Reported, not failed — but a cljs suite with no"
                  " build that names it does not run either.")))
  (doseq [{:keys [file]} (take 20 cljs-only)]
    (println "  (cljs) NO RUNNER" (path/relative root file)))
  (when (seq skipped-dirs)
    (println (str "SKIPPED-DIR\t" (str/join "," skipped-dirs) " — " (count skipped-dead)
                  " file(s) with no runner in there are reported, not failed."
                  " Skipped is not passed.")))
  (doseq [{:keys [file]} (take 20 skipped-dead)]
    (println "  (skipped) NO RUNNER" (path/relative root file)))
  (doseq [{:keys [file]} (take 20 dead)]
    (println "  NO RUNNER" (path/relative root file)
             "— defines tests that nothing executes; add the (run-tests) footer"))

  (cond
    (< (count analysed) min-files)
    (do (println "FLEET-CI: only" (count analysed) "file(s) with deftest found (< " min-files
                 ") — the tree is filtered wrong or the tests moved; refusing to report a pass")
        (js/process.exit 91))

    (seq dead)
    (do (println "FLEET-CI FAIL:" (count dead)
                 "test file(s) define tests that never run. Invoked like their neighbours"
                 "they print nothing and exit 0, which is what passing looks like.")
        (js/process.exit 1))

    :else
    (do (println "FLEET-CI OK:" (count analysed) "test file(s) with deftest all execute them")
        (js/process.exit 0))))
