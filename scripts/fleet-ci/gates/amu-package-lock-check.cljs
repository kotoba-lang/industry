#!/usr/bin/env nbb
;; Amu's package-lock suite (`test/nbb/package_lock.cljs`), run with no JVM
;; anywhere in reach.
;;
;; WHAT IT GUARDS. `ADR-kotoba-package-cid-lock` carried M5 -- some CLI
;; consumes the lock -- as outstanding from 2026-06-30 to 2026-09-06. The
;; consumer now exists: `amu check|compile|module-lock --package-lock`
;; resolves a signed, CID-pinned `kotoba.lock.edn` into source roots and binds
;; the materialised tree to it with five checks (git commit, tree CID,
;; manifest signature, manifest integrity, definition CIDs). This gate is what
;; keeps those five from quietly becoming four.
;;
;; WHY IT MUST BE A NODE GATE WITH JVM TOOLS TRAPPED. Two of those checks were
;; `:clj`-only until 2026-09-06 (signature verification, manifest integrity),
;; and the JVM suite was green the entire time they were unreachable from
;; Node. A gate that allowed a JDK on PATH could pass while the property it
;; names -- a package lock is consumable without one -- was false.
;;
;; The suite needs `git`: it materialises a package as a real checkout so the
;; commit check has something to verify against. A missing `git` is reported
;; as "cannot run", never as a pass.
;;
;; Exit codes are deliberately distinct so that "could not answer" never wears
;; the same face as "passed":
;;   0 pass · 1 suite failed · 90 required input missing · 91 cannot run
;;   92 no summary line · 93 the suite ran too few cases · 94 a JVM tool ran
(ns fleet-ci.gates.amu-package-lock-check
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def root (.resolve path (or (first *command-line-args*) ".")))

(defn fail! [message code]
  (println (str "FLEET-CI: " message))
  (js/process.exit code))

;; Evidence floor. Every one of these is load-bearing: without the authoring
;; namespace the suite cannot build a fixture, and without the consumer there
;; is nothing to check it against.
(def required
  ["test/nbb/package_lock.cljs"
   "src/kotoba/compiler/nbb/package_lock.cljs"
   "src/kotoba/compiler/nbb/package_authoring.cljs"
   "src/kotoba/compiler/nbb/project_source.cljs"
   "src/kotoba/compiler/nbb/classpath.cljs"
   "deps.edn"
   "deps-lock.edn"])

(doseq [rel required]
  (let [p (.join path root rel)]
    (when-not (and (.existsSync fs p) (.isFile (.statSync fs p)))
      (fail! (str "required input is missing, so no verdict is possible: " rel) 90))))

;; `kotoba-core-contracts` is where the signature and integrity checks live.
;; A lock resolved without it on the classpath would be resolved by a shorter
;; sequence of checks, and the suite would still say `failures=0` for the ones
;; that remained.
(let [text (.readFileSync fs (.join path root "deps.edn") "utf8")]
  (when-not (.includes text "kotoba-core-contracts")
    (fail! (str "deps.edn does not carry kotoba-core-contracts; the signature "
                "and manifest-integrity checks cannot be present") 90)))

(def git-check
  (.spawnSync child "git" #js ["--version"] #js {:encoding "utf8"}))

(when-not (and (not (.-error git-check)) (zero? (or (.-status git-check) 1)))
  (fail! (str "git is not available on this node; the suite materialises a "
              "package as a real checkout, so it cannot run here") 91))

(def trap-dir
  (.mkdtempSync fs (.join path (.tmpdir os) "amu-package-lock-trap-")))

(doseq [tool ["java" "clojure" "clj" "javac"]]
  (let [p (.join path trap-dir tool)]
    (.writeFileSync fs p (str "#!/bin/sh\n"
                              "echo 'AMU-PKG-TRAP: " tool " was invoked' >&2\n"
                              "exit 97\n"))
    (.chmodSync fs p 493)))

(def trapped-path (str trap-dir ":" (or (.-PATH js/process.env) "")))

(def nbb-version
  "Pinned for the same reason the sibling regression gate pins it: an
   ambiently-resolved nbb makes a verdict depend on the machine."
  "1.5.212")

(defn run [args]
  (.spawnSync child "npx" (clj->js (concat ["--yes" (str "nbb@" nbb-version)] args))
              #js {:cwd root :encoding "utf8" :maxBuffer 33554432
                   :env (js/Object.assign #js {} js/process.env
                                          #js {:PATH trapped-path})}))

;; The classpath comes from `deps-lock.edn`, never from `clojure -Spath`.
;; Deriving it from the JVM would make "consumable without a JVM" untestable
;; by construction.
(def resolved
  (run ["--classpath" "src" "-e"
        (str "(require '[kotoba.compiler.nbb.classpath :as cp])"
             "(println (clojure.string/join \":\" (cp/resolve-directories \".\")))")]))

(when (.-error resolved)
  (fail! (str "cannot run nbb@" nbb-version ": " (.. resolved -error -message)) 91))
(when-not (zero? (or (.-status resolved) 1))
  (print (str (or (.-stdout resolved) "") (or (.-stderr resolved) "")))
  (fail! "dependency lock did not resolve; run scripts/lock-classpath.cljs" 91))

(def dep-dirs (.trim (str (or (.-stdout resolved) ""))))

(when (empty? dep-dirs)
  (fail! "dependency lock resolved to nothing, so the suite would run against an empty classpath" 90))

(def result (run ["--classpath" (str ".:src:test:" dep-dirs)
                  "test/nbb/package_lock.cljs"]))

(def output (str (or (.-stdout result) "") (or (.-stderr result) "")))
(print output)

(when (.-error result)
  (fail! (str "cannot run the package-lock suite: " (.. result -error -message)) 91))

(when (.includes output "AMU-PKG-TRAP")
  (fail! "a JVM tool was invoked, so this run is not evidence of a JDK-free path" 94))

(def summary (re-find #"package-lock suite, failures=(\d+)" output))

(when-not summary
  (fail! "the suite returned without its summary line, so nothing here is a verdict" 92))

(def passes (count (re-seq #"(?m)^PASS " output)))
(def fails (count (re-seq #"(?m)^FAIL " output)))

;; A floor on the number of cases, and on there being refusals among them. A
;; suite that only ever accepted would be green on a consumer that checked
;; nothing -- which is the exact state this gate exists to make impossible.
(def minimum-cases 14)
(def minimum-rejection-cases 8)

(let [failed (js/parseInt (second summary) 10)
      total (+ passes fails)
      rejections (count (re-seq #"is refused|fails the|refuses a" output))]
  (when (< total minimum-cases)
    (fail! (str "the suite reported " total " cases, fewer than the " minimum-cases
                " this gate requires; something was skipped silently") 93))
  (when (< rejections minimum-rejection-cases)
    (fail! (str "only " rejections " of the cases exercise a refusal; a suite "
                "that only accepts cannot show that any check runs") 93))
  (when-not (zero? failed)
    (fail! (str "package-lock suite failed: " failed " case(s)") 1))
  (println (str "FLEET-CI: amu package-lock suite passed with no JVM -- "
                total " cases (" rejections " refusals), nbb " nbb-version
                ", JVM tools trapped on PATH")))
