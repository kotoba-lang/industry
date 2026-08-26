#!/usr/bin/env nbb
;; Capture kototama tender comparison evidence into 90-docs/performance/runs/<date>/.
;;
;; Usage (from superproject root):
;;   nbb scripts/kotoba-tender-benchmark.cljs [--runs N] [--date YYYY-MM-DD]

(ns kotoba-tender-benchmark
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]))

(def here (path/dirname *file*))
(def root (path/resolve here ".."))
(def args (vec *command-line-args*))

(defn opt [name fallback]
  (let [i (.indexOf args name)]
    (if (neg? i) fallback (nth args (inc i) fallback))))

(defn die! [code msg]
  (binding [*out* js/process.stderr]
    (println msg))
  (js/process.exit code))

(defn normalize-loadavg [raw]
  (-> (str/trim (or raw ""))
      (str/replace #"^[{\s]+" "")
      (str/replace #"[}\s]+$" "")))

(defn output-loadavg []
  (let [r (cp/spawnSync "sysctl" #js ["-n" "vm.loadavg"] #js {:encoding "utf8"})]
    (if (zero? (or (.-status r) 1))
      (normalize-loadavg (or (.-stdout r) ""))
      (let [r2 (cp/spawnSync "uptime" #js [] #js {:encoding "utf8"})]
        (when (zero? (or (.-status r2) 1))
          (str/trim (or (.-stdout r2) "")))))))

(defn -main []
  (let [default-kototama (path/join root "orgs/kotoba-lang/kototama")
        kototama (path/resolve (opt "--kototama" (or js/process.env.KOTOTAMA_ROOT default-kototama)))
        date (opt "--date" (.slice (.toISOString (js/Date.)) 0 10))
        runs (opt "--runs" "3")
        out-dir (path/join root "90-docs/performance/runs" (str date "-tender"))
        harness (path/join root "scripts/tender-comparison.cljs")]
    (when-not (fs/existsSync (path/join kototama "deps.edn"))
      (die! 91
            (str "kototama checkout missing at " kototama
                 " — run `west update --fetch smart kototama` or pass --kototama / set KOTOTAMA_ROOT")))
    (when-not (fs/existsSync harness)
      (die! 91 (str "harness missing: " harness)))
    (.mkdirSync fs out-dir #js {:recursive true})
    (let [meta #js {:capturedAt (.toISOString (js/Date.))
                    :platform js/process.platform
                    :arch js/process.arch
                    :cpus (.-length (os/cpus))
                    :totalMemoryBytes (os/totalmem)
                    :node js/process.version
                    :loadavg (output-loadavg)
                    :benchmark "kotoba.tender-comparison/v1"}]
      (fs/writeFileSync (path/join out-dir "host-meta.json")
                        (str (js/JSON.stringify meta nil 2) "\n")))
    (println "\n=== tender comparison ===")
    (let [r (cp/spawnSync "npx" #js ["--yes" "nbb"
                                      "--classpath" (str root ":" root "/scripts/nbb_compat")
                                      harness
                                      "--kototama" kototama
                                      "--runs" runs
                                      "--calls" "400"
                                      "--warmup" "50"
                                      "--output" (path/join out-dir "tender.json")]
                        #js {:cwd root
                             :encoding "utf8"
                             :stdio "inherit"})]
      (when-not (zero? (or (.-status r) 1))
        (js/process.exit (or (.-status r) 1)))
      (println (str "\nEvidence written to " out-dir)))))

(-main)
