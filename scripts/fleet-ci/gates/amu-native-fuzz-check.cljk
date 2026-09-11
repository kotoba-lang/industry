#!/usr/bin/env nbb
(ns fleet-ci.gates.amu-native-fuzz-check
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- die! [code & message]
  (println (str "FLEET-CI: " (str/join " " message)))
  (js/process.exit code))

(def required
  ["scripts/fuzz-native.cljs"
   "fuzz/baselines/native-parser.edn"
   "fuzz/corpus/parser"
   "tools/kexe_loader.c"
   "tools/kexe_parser_fuzz.c"])

(doseq [relative required]
  (when-not (fs/existsSync (path/join root relative))
    (die! 90 "missing after extract:" relative "— refusing to report pass")))

(def baseline
  (try
    (reader/read-string
     (str (fs/readFileSync (path/join root "fuzz/baselines/native-parser.edn") "utf8")))
    (catch :default e
      (die! 1 "native fuzz baseline is unreadable:" (ex-message e)))))

(def loader-sha
  (-> (crypto/createHash "sha256")
      (.update (fs/readFileSync (path/join root "tools/kexe_loader.c")))
      (.digest "hex")))

(when-not (= loader-sha (:loader-source-sha256 baseline))
  (die! 1 "native fuzz baseline is stale: expected"
        (:loader-source-sha256 baseline) "actual" loader-sha))

(let [result (cp/spawnSync
              "npx" #js ["--yes" "nbb" "scripts/fuzz-native.cljs"]
              #js {:cwd root :encoding "utf8" :maxBuffer 33554432
                   :env (js/Object.assign #js {} js/process.env
                                          #js {"KOTOBA_NATIVE_FUZZ_RUNS" "20000"
                                               "KOTOBA_NATIVE_FUZZ_SEED" "424242"})})
      code (if (nil? (.-status result)) 1 (.-status result))
      output (str (or (.-stdout result) "") (or (.-stderr result) ""))]
  (println (str/join "\n" (take-last 20 (str/split-lines (str/trim output)))))
  (when (.-error result)
    (die! 91 "native fuzz process could not start:" (.. result -error -message)))
  (when-not (zero? code)
    (die! 1 "native fuzz exited" code))
  (when-not (and (str/includes? output ":kotoba.fuzz-coverage/v1")
                 (str/includes? output "native-fuzz: 20000")
                 (str/includes? output "parser fuzz passed"))
    (die! 1 "native fuzz emitted no complete 20,000-case verdict"))
  (println "OK — native parser baseline fresh + 20,000 sanitized fuzz cases passed"))
