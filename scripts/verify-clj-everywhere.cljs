#!/usr/bin/env nbb
(require '[scripts.nbb-compat :refer [sh exit]]
         '[clojure.string :as str])

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))
(defn run! [dir & command]
  (println (str "── " dir ": " (str/join " " command) " ──"))
  (let [result (apply sh (concat command [{:cwd (str root "/" dir)}]))]
    (print (:out result)) (binding [*out* *err*] (print (:err result)))
    (when-not (zero? (:exit result)) (exit (:exit result)))))

(doseq [[script args] [["scripts/kami-webgpu-dsl-runtime-split-audit.cljs" ["--strict" "--requested-tests"]]
                       ["scripts/kotoba-only-runtime-audit.cljs" ["--strict"]]
                       ["scripts/kami-provider-split-audit.cljs" ["--strict"]]
                       ["scripts/kami-provider-split-sync.cljs" ["--check"]]]]
  (apply run! "." "nbb" (str root "/" script) args))

(run! "orgs/kotoba-lang/kotoba-lang" "clojure" "-M:test")
(run! "orgs/kotoba-lang/kotoba-core-contracts" "clojure" "-M:test")
(run! "orgs/kotoba-lang/kotoba" "clojure" "-M:test")
(run! "orgs/kotoba-lang/webgpu" "clojure" "-M:test")
(run! "orgs/kotoba-lang/kami-engine/kami-engine-sdk-clj" "clojure" "-M:test")
(println "✓ kotoba-lang migration gates green — CLJ/CLJC + kotoba sources only")
