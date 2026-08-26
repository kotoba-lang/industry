#!/usr/bin/env nbb
(ns fleet-ci.gates.kotoba-stack-performance-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (path/resolve (or (first (remove #(str/starts-with? % "--") args)) ".")))
(def verifier (path/join root "scripts" "verify-kotoba-stack-performance.cljs"))
(defn die! [code msg] (println (str "FLEET-CI: " msg)) (js/process.exit code))

(doseq [p ["scripts/verify-kotoba-stack-performance.cljs"
           "90-docs/performance/performance.datoms.edn"
           "90-docs/performance/runs/2026-08-26-judah-quiet/runtime.json"
           "90-docs/performance/runs/2026-08-26-judah-quiet/compile.json"
           "90-docs/performance/runs/2026-08-26-judah-quiet/host-meta.json"
           "docs/performance/kotoba-stack-benchmark-2026-08-26.md"
           "90-docs/adr/2608260800-kotoba-stack-performance-world-class.edn"]]
  (when-not (fs/existsSync (path/join root p))
    (die! 90 (str "required input missing after extract: " p))))

(let [r (cp/spawnSync "npx" #js ["--yes" "nbb"
                                  "--classpath" (str root ":" root "/scripts/nbb_compat")
                                  verifier "--root" root]
                      #js {:encoding "utf8" :cwd root :maxBuffer 16777216})
      out (str (or (.-stdout r) "") (or (.-stderr r) ""))]
  (print out)
  (when-not (zero? (or (.-status r) 1))
    (js/process.exit (or (.-status r) 1)))
  (when-not (str/includes? out "kotoba-stack-performance: OK")
    (die! 93 "verifier returned without success receipt")))
