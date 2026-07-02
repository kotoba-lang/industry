#!/usr/bin/env bb
;; bb run-tests.bb — 70-tools/bmc test runner
(require '[babashka.fs :as fs] '[babashka.classpath :as cp])
(def here (fs/parent (fs/real-path *file*)))
(cp/add-classpath (str here "/src"))
(cp/add-classpath (str here "/test"))
(require '[clojure.test :as t] 'gftd.bmc-test)
(let [{:keys [fail error]} (t/run-tests 'gftd.bmc-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
