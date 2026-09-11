#!/usr/bin/env nbb
;; Execute the cross-runtime OCapN probes on the shipped kotoba-lang tree.

(ns fleet-ci.gates.kotoba-ocapn-endo-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def root (or (first *command-line-args*) "."))

(def child-env
  ;; npx exports the operator's project-scoped npm `allow-scripts` setting.
  ;; Reusing it in the shipped repo makes modern npm reject the nested install.
  (let [env (js/Object.assign #js {} (.-env js/process))]
    (js-delete env "npm_config_allow_scripts")
    env))

(defn fail! [message]
  (println (str "FAIL: " message))
  (set! (.-exitCode js/process) 1)
  (js/process.exit 1))

(defn run! [args expected]
  (let [result (.spawnSync cp "npm" (clj->js args)
                           #js {:cwd root :encoding "utf8" :env child-env})
        output (str (.-stdout result) (.-stderr result))]
    (println (str/join "\n" (take-last 20 (str/split-lines output))))
    (when-not (zero? (or (.-status result) 1))
      (fail! (str "npm " (str/join " " args) " exited " (.-status result))))
    (when-not (str/includes? output expected)
      (fail! (str "missing success marker: " expected)))))

(doseq [required ["package.json" "package-lock.json"
                  "scripts/endo_handoff_interop.cljs"
                  "scripts/endo_captp_live.cljs"]]
  (when-not (.existsSync fs (path/join root required))
    (fail! (str required " missing from shipped tree"))))

(let [install (.spawnSync cp "npm" #js ["ci" "--ignore-scripts" "--no-audit"]
                          #js {:cwd root :encoding "utf8" :env child-env})
      output (str (.-stdout install) (.-stderr install))]
  (println (str/join "\n" (take-last 20 (str/split-lines output))))
  (when-not (zero? (or (.-status install) 1))
    (fail! (str "npm ci exited " (.-status install)
                (when-let [error (.-error install)] (str ": " error))))))

(run! ["run" "test:ocapn-endo"]
      "Endo 1.1.1 <-> Kotoba handoff: bytes, signatures, and future deposit passed")
(run! ["run" "test:ocapn-endo-live"]
      "Endo 1.1.1 <-> Kotoba live CapTP TCP session passed")

(println "OK: Endo/Kotoba handoff and live CapTP TCP interoperability")
