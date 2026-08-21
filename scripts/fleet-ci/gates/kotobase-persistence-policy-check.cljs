#!/usr/bin/env nbb
(ns fleet-ci.gates.kotobase-persistence-policy-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (path/resolve (or (first (remove #(str/starts-with? % "--") args)) ".")))
(def verifier (path/join root "scripts" "verify-kotobase-persistence-policy.cljs"))
(defn die! [code msg] (println (str "FLEET-CI: " msg)) (js/process.exit code))
(when-not (fs/existsSync verifier)
  (die! 90 "kotobase persistence verifier missing after extract"))
(doseq [p ["AGENTS.md" "manifest/repository-rules.edn"
           "90-docs/adr/2608159100-kotobase-net-default-durable-boundary.edn"]]
  (when-not (fs/existsSync (path/join root p))
    (die! 90 (str p " missing after extract"))))
(let [r (cp/spawnSync "npx" #js ["--yes" "nbb" verifier "--root" root]
                      #js {:encoding "utf8" :cwd root})
      out (str (or (.-stdout r) "") (or (.-stderr r) ""))]
  (print out)
  (when-not (zero? (or (.-status r) 1)) (js/process.exit (or (.-status r) 1)))
  (when-not (str/includes? out "kotobase-persistence-policy: OK")
    (die! 93 "verifier returned without its success receipt")))
