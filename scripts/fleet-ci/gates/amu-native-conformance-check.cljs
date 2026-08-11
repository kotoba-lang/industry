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
                  "examples/structured.kotoba" "tools/kexe_loader.c"]]
  (when-not (fs/existsSync (path/join root relative))
    (die! 90 "missing after extract:" relative "— refusing to report pass")))

(let [result (cp/spawnSync "npx" #js ["--yes" "nbb" "scripts/jdk-free-native-conformance.cljs"]
                           #js {:cwd root :encoding "utf8" :maxBuffer 33554432
                                :env js/process.env})
      code (if (nil? (.-status result)) 1 (.-status result))
      output (str (or (.-stdout result) "") (or (.-stderr result) ""))]
  (println (str/join "\n" (take-last 20 (str/split-lines (str/trim output)))))
  (when (.-error result)
    (die! 91 "native conformance could not start:" (.. result -error -message)))
  (when-not (zero? code)
    (die! 1 "native conformance exited" code))
  (when-not (re-find #"jdk-free-native: sealed (aarch64|x86_64) artifact independently extracted and executed under W\^X loader"
                     output)
    (die! 1 "native conformance emitted no complete runtime verdict"))
  (println "OK — JDK-free compiler + independent extraction + real host ISA passed"))
