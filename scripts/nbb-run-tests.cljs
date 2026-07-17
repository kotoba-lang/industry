#!/usr/bin/env nbb
;; Shared nbb test runner for portable .cljc / cljs test namespaces.
;; Replaces the babashka scaffold pattern:
;;   bb test  ; (clojure.test/run-tests 'foo.main-test) + System/exit
;;
;; Usage (from a child repo):
;;   nbb --classpath src:test /path/to/scripts/nbb-run-tests.cljs foo.main-test
;;   nbb --classpath src:test -e '(require (quote scripts.nbb-run-tests)) …'
;;   or: npm test  (after bb-to-nbb-scaffold emit)
;;
;; Exit 0 iff fail+error == 0. (ADR-2607173000)
(require '[clojure.string :as str]
         '[clojure.test :as t]
         '[scripts.nbb-compat :refer [exit]])

(defn- usage! []
  (binding [*out* *err*]
    (println "usage: nbb-run-tests.cljs <ns>…"))
  (exit 2))

(let [args (vec *command-line-args*)
      nss (mapv symbol args)]
  (when (empty? nss) (usage!))
  (doseq [ns-sym nss] (require ns-sym))
  (let [{:keys [fail error]} (apply t/run-tests nss)
        code (if (pos? (+ (or fail 0) (or error 0))) 1 0)]
    (exit code)))
