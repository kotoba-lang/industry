#!/usr/bin/env nbb
;; Run Amu's own Windows loader cross-build verifier.  The repo owns PE parsing,
;; target identities, compiler flags and reproducibility checks; fleet only
;; supplies a node whose measured capability includes :zig.
(ns fleet-ci.gates.amu-windows-loader-cross-check
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def root (.resolve path (or (first *command-line-args*) ".")))
(def verifier (.join path root "scripts" "windows-loader-cross-compile.cljs"))
(def source (.join path root "tools" "kexe_loader_windows.c"))

(defn fail! [message code]
  (println (str "FLEET-CI: " message))
  (js/process.exit code))

(when-not (and (.existsSync fs verifier) (.isFile (.statSync fs verifier)))
  (fail! "Amu Windows cross-build verifier is missing" 90))
(when-not (and (.existsSync fs source) (.isFile (.statSync fs source)))
  (fail! "Amu Windows loader source is missing" 90))

(let [result (.spawnSync child "npx"
                         #js ["--yes" "nbb" verifier]
                         #js {:cwd root :encoding "utf8" :maxBuffer 33554432})
      output (str (or (.-stdout result) "") (or (.-stderr result) ""))]
  (print output)
  (when (.-error result)
    (fail! (str "cannot run Amu verifier: " (.. result -error -message)) 91))
  (when-not (zero? (or (.-status result) 1))
    (js/process.exit (or (.-status result) 1)))
  (when-not (.includes output "windows-loader-cross: OK")
    (fail! "Amu verifier returned without its success sentinel" 92))
  (println "FLEET-CI: Amu Windows loader cross-build gate passed"))
