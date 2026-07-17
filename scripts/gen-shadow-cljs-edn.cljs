#!/usr/bin/env nbb
;; Generates shadow-cljs.edn :source-paths from `clojure -Spath`.
;; nbb port of the shared gen-shadow-cljs-edn.bb family (ADR-2607173000 Wave 1).
;;
;;   nbb scripts/gen-shadow-cljs-edn.cljs          ; cwd = target repo
;;   nbb /path/to/superproject/scripts/gen-shadow-cljs-edn.cljs
(require '[clojure.string :as str]
         '[scripts.nbb-compat :refer [slurp spit sh]])

(def fs (js/require "node:fs"))

(let [r (sh "clojure" "-Spath")
      cp (str/trim (or (:out r) ""))
      dirs (->> (str/split cp #":")
                (remove str/blank?)
                (filter #(.isDirectory (.statSync fs %))))]
  (spit "shadow-cljs.edn"
        (str "{:source-paths " (pr-str (vec (concat ["test"] dirs))) "\n"
             " :builds\n"
             " {:test {:target :node-test\n"
             "         :output-to \"out/test.js\"\n"
             "         :ns-regexp \"-test$\"}}}\n"))
  (println "wrote shadow-cljs.edn with" (count dirs) "source dirs from clojure -Spath"))
