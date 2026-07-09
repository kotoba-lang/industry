#!/usr/bin/env bb
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :refer [sh]]
         '[clojure.pprint :as pprint]
         '[clojure.string :as str])

(def root
  (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))

(def central-path
  "orgs/kotoba-lang/kami-contracts/resources/kami/provider_split_repos.edn")

(def materialized-kinds
  #{:game-engine
    :edn-domain-authority
    :wasm-component-provider
    :component-runtime-provider
    :edn-fixture-provider})

(defn read-edn [path]
  (edn/read-string (slurp (io/file root path))))

(defn local-contract-path [split-to]
  (str split-to "/resources/kami/provider/split_contract.edn"))

(defn rendered [split]
  (with-out-str
    (pprint/pprint split)))

(defn materialized-splits []
  (->> (:kami/provider-split-repos (read-edn central-path))
       (filter #(contains? materialized-kinds (:kami/kind %)))))

(defn sync-one [check? split]
  (let [path (local-contract-path (:kami/split-to split))
        file (io/file root path)
        want (rendered split)
        have (when (.exists file) (slurp file))
        ok? (= want have)]
    (cond
      ok?
      {:path path :status :ok}

      check?
      {:path path :status :stale}

      :else
      (do
        (.mkdirs (.getParentFile file))
        (spit file want)
        {:path path :status :updated}))))

(defn print-human [results]
  (println "KAMI provider split sync")
  (println "central:" central-path)
  (doseq [{:keys [path status]} results]
    (println (format "  %-72s %s" path (name status)))))

(defn -main [& args]
  (let [check? (some #{"--check"} args)
        edn? (some #{"--edn"} args)
        results (mapv #(sync-one check? %) (materialized-splits))
        stale? (some #(= :stale (:status %)) results)]
    (if edn?
      (prn {:valid? (not stale?) :results results})
      (print-human results))
    (when (and check? stale?)
      (System/exit 1))))

(apply -main *command-line-args*)
