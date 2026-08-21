#!/usr/bin/env nbb
;; Amu's portable surface suite, run with no JVM anywhere in reach.
;;
;; The suite exists because a `.cljc` extension is a claim, not a fact: cljs
;; compiles `(.getBytes s c)` happily and only fails when it executes, so a
;; namespace can load under nbb and still die on its first JVM method call.
;; Measured on amu the same day the suite was written: a `.getBytes` restored
;; inside an unexercised branch of `kotoba.native.elf64` left its portable
;; suite green.
;;
;; Two things make this gate mean something rather than merely pass:
;;
;;   1. The classpath comes from `kotoba.compiler.nbb.classpath`, which reads
;;      `deps-lock.edn`, NOT from `clojure -Spath`.  Deriving it from the JVM
;;      would make "runs without a JVM" untestable by construction.
;;   2. `java`, `clojure`, `clj` and `javac` are shadowed on PATH by stubs that
;;      exit non-zero and announce themselves.  A green here is evidence no JVM
;;      was consulted, not evidence that nobody looked.
;;
;; Exit codes are deliberately distinct so that "could not answer" never wears
;; the same face as "passed":
;;   0 pass · 1 suite failed · 90 required input missing · 91 cannot run
;;   92 no success sentinel · 93 suite ran nothing · 94 a JVM tool was invoked
(ns fleet-ci.gates.amu-portable-surface-check
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def root (.resolve path (or (first *command-line-args*) ".")))

(defn fail! [message code]
  (println (str "FLEET-CI: " message))
  (js/process.exit code))

;; Evidence floor.  A tree that arrived without these cannot produce a verdict,
;; and must say so rather than reporting the clean run of an empty suite.
(def required
  ["run-portable-surface.cljs"
   "deps.edn"
   "deps-lock.edn"
   "src/kotoba/compiler/nbb/classpath.cljs"
   "src/kotoba/compiler/ios_aot.cljc"
   "test/kotoba/compiler/portable_surface_test.cljc"
   "test/fixtures/ios-aot-artifact.edn"])

(doseq [rel required]
  (let [p (.join path root rel)]
    (when-not (and (.existsSync fs p) (.isFile (.statSync fs p)))
      (fail! (str "required input is missing, so no verdict is possible: " rel) 90))))

;; PATH trap.  Written to a fresh temp dir rather than the repo so a failed run
;; cannot leave executable stubs behind in a checkout.
(def trap-dir
  (.mkdtempSync fs (.join path (.tmpdir os) "amu-portable-trap-")))

(doseq [tool ["java" "clojure" "clj" "javac"]]
  (let [p (.join path trap-dir tool)]
    (.writeFileSync fs p (str "#!/bin/sh\n"
                              "echo 'AMU-PORTABLE-TRAP: " tool " was invoked' >&2\n"
                              "exit 97\n"))
    ;; 0755
    (.chmodSync fs p 493)))

(def trapped-path (str trap-dir ":" (or (.-PATH js/process.env) "")))

(defn run [cmd args]
  (.spawnSync child cmd (clj->js args)
              #js {:cwd root :encoding "utf8" :maxBuffer 33554432
                   :env (js/Object.assign #js {} js/process.env #js {:PATH trapped-path})}))

;; Resolve the dependency directories from the lock.  This is the same code
;; `bin/kotoba` uses for its JDK-free fast path, so a break here is a real
;; break in that path and not a gate-only concern.
(def resolved
  (run "nbb" ["--classpath" "src" "-e"
              (str "(require '[kotoba.compiler.nbb.classpath :as cp])"
                   "(println (clojure.string/join \":\" (cp/resolve-directories \".\")))")]))

(when (.-error resolved)
  (fail! (str "cannot run nbb: " (.. resolved -error -message)) 91))
(when-not (zero? (or (.-status resolved) 1))
  (print (str (or (.-stdout resolved) "") (or (.-stderr resolved) "")))
  (fail! "dependency lock did not resolve; run scripts/lock-classpath.cljs" 91))

(def dep-dirs (.trim (str (or (.-stdout resolved) ""))))

(when (empty? dep-dirs)
  (fail! "dependency lock resolved to nothing, so the suite would run against an empty classpath" 90))

(def result
  (run "nbb" ["--classpath" (str "src:test:" dep-dirs) "run-portable-surface.cljs"]))

(def output (str (or (.-stdout result) "") (or (.-stderr result) "")))
(print output)

(when (.-error result)
  (fail! (str "cannot run the portable suite: " (.. result -error -message)) 91))

;; Checked before the status, because a trapped tool is a different and more
;; interesting failure than a red suite: it means the suite passed only because
;; something reached for a JVM.
(when (.includes output "AMU-PORTABLE-TRAP")
  (fail! "a JVM tool was invoked, so this run is not evidence of a JDK-free path" 94))

(when-not (zero? (or (.-status result) 1))
  (js/process.exit (or (.-status result) 1)))

(def summary (re-find #"nbb: (\d+) tests, (\d+) passed, (\d+) failed, (\d+) errors" output))

(when-not summary
  (fail! "the suite returned without its summary line, so nothing here is a verdict" 92))

(let [[_ tests _passed failed errors] summary]
  ;; A suite that ran nothing exits 0.  Refuse to call that a pass.
  (when (zero? (js/parseInt tests 10))
    (fail! "the portable suite ran no tests" 93))
  (when-not (and (zero? (js/parseInt failed 10)) (zero? (js/parseInt errors 10)))
    (fail! (str "portable suite failed: " failed " failed, " errors " errors") 1))
  (println (str "FLEET-CI: amu portable surface passed with no JVM -- "
                tests " tests, JVM tools trapped on PATH")))
