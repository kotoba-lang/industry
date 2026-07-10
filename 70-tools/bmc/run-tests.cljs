#!/usr/bin/env nbb
;; nbb run-tests.cljs — 70-tools/bmc test runner
(require '[scripts.nbb-compat :refer [slurp spit file-seq format]]
         '[babashka.fs :as fs])
(def here (fs/parent (fs/real-path *file*)))
(require '[clojure.test :as t] 'gftd.bmc-test)
(let [{:keys [fail error]} (t/run-tests 'gftd.bmc-test)]
  (scripts.nbb-compat/exit (if (pos? (+ fail error)) 1 0)))
