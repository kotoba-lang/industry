#!/usr/bin/env nbb
(ns fleet-ci.gates.amu-native-conformance-check
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- die! [code & message]
  (println (str "FLEET-CI: " (str/join " " message)))
  (js/process.exit code))

(doseq [relative ["bin/kotoba" "scripts/jdk-free-native-conformance.cljs"
                  "scripts/windows-profile-conformance.cljs"
                  "examples/structured.kotoba" "examples/nested-record.kotoba"
                  "examples/held-operations.kotoba"
                  "tools/kexe_loader.c"]]
  (when-not (fs/existsSync (path/join root relative))
    (die! 90 "missing after extract:" relative "— refusing to report pass")))

(defn- run-script! [script label]
  (let [result (cp/spawnSync "npx" #js ["--yes" "nbb" script]
                             #js {:cwd root :encoding "utf8" :maxBuffer 33554432
                                  :env js/process.env})
        code (if (nil? (.-status result)) 1 (.-status result))
        output (str (or (.-stdout result) "") (or (.-stderr result) ""))]
    (println (str/join "\n" (take-last 20 (str/split-lines (str/trim output)))))
    (when (.-error result)
      (die! 91 label "could not start:" (.. result -error -message)))
    (when-not (zero? code)
      (die! 1 label "exited" code))
    output))

(let [native-output (run-script! "scripts/jdk-free-native-conformance.cljs"
                                 "native conformance")]
  (when-not (re-find #"jdk-free-native: sealed (aarch64|x86_64) scalar, aggregate-variant, callable, bounded-apply artifacts independently extracted and executed under W\^X loader"
                     native-output)
    (die! 1 "native conformance emitted no held-operation runtime verdict")))

(let [windows-output (run-script! "scripts/windows-profile-conformance.cljs"
                                  "Windows profile conformance")]
  (when-not (re-find #"windows-profile: recursive-record (aarch64|x86_64) Windows KEXE verified"
                     windows-output)
    (die! 1 "Windows profile emitted no recursive-record KEXE verdict"))
  (when-not (re-find #"windows-profile: aggregate-variant callable bounded-apply (aarch64|x86_64) Windows KEXE verified"
                     windows-output)
    (die! 1 "Windows profile emitted no held-operation KEXE verdict"))
  (when-not (re-find #"windows-profile: entryless (aarch64|x86_64) Windows library verified"
                     windows-output)
    (die! 1 "Windows profile emitted no entryless-library verdict")))

(println "OK — JDK-free aggregate/callable execution + Windows KEXE verification passed")
