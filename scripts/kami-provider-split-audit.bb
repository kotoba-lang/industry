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

(def ledger-path
  "90-docs/migration/kotoba-only-runtime-ledger.edn")

(def repos-path
  "manifest/repos.edn")

(defn read-edn [path]
  (edn/read-string (slurp (io/file root path))))

(def central
  (:kami/provider-split-repos (read-edn central-path)))

(def ledger
  (read-edn ledger-path))

(def repos
  (read-edn repos-path))

(def west-path
  "manifest/west.yml")

(def west-paths
  (->> (str/split-lines (slurp (io/file root west-path)))
       (keep #(second (re-find #"^[ \t]+path:[ \t]+(.+?)[ \t]*$" %)))
       set))

(def extra-projects
  (set (:extra-projects repos)))

(def materialized-kinds
  #{:game-engine
    :edn-domain-authority
    :wasm-component-provider
    :component-runtime-provider
    :edn-fixture-provider})

(defn local-contract-path [split-to]
  (str split-to "/resources/kami/provider/split_contract.edn"))

(defn rendered [split]
  (with-out-str
    (pprint/pprint split)))

(defn err [path message]
  {:path path :message message})

(defn dependency-errors [split]
  (mapcat
   (fn [repo]
     (remove nil?
             [(when-not (.exists (io/file root repo))
                (err repo (str "dependency repo directory is missing for " (:kami/split-to split))))
              (when-not (or (contains? extra-projects repo)
                            (contains? west-paths repo))
                (err repos-path (str "manifest is missing dependency repo for " (:kami/split-to split) ": " repo)))
              (when-not (contains? west-paths repo)
                (err west-path (str "generated west manifest is missing dependency repo for " (:kami/split-to split) ": " repo)))]))
   (:kami/dependency-repos split)))

(defn split-errors [split]
  (let [split-to (:kami/split-to split)
        local-path (local-contract-path split-to)
        local-file (io/file root local-path)
        local (when (.exists local-file) (read-edn local-path))
        local-text (when (.exists local-file) (slurp local-file))
        expected-text (rendered split)
        extra-projects extra-projects]
    (remove nil?
            (concat
             [(when-not (.exists (io/file root split-to))
                (err split-to "split repo directory is missing"))
              (when-not (contains? materialized-kinds (:kami/kind split))
                nil)
              (when (contains? materialized-kinds (:kami/kind split))
                (when-not (.exists local-file)
                  (err local-path "local split contract is missing")))
              (when (and local (not= split local))
                (err local-path "local split contract differs from central provider split contract"))
              (when (and (contains? materialized-kinds (:kami/kind split))
                         local-text
                         (not= expected-text local-text))
                (err local-path "local split contract is not normalized; run bb scripts/kami-provider-split-sync.bb"))
              (when (and (contains? materialized-kinds (:kami/kind split))
                         (not (contains? (:repos ledger) split-to)))
                (err ledger-path (str "ledger is missing split repo: " split-to)))
              (when (and (contains? materialized-kinds (:kami/kind split))
                         (not (contains? extra-projects split-to)))
                (err repos-path (str "manifest/repos.edn :extra-projects is missing split repo: " split-to)))]
             (dependency-errors split)))))

(defn audit []
  (let [split-targets (map :kami/split-to central)]
    (vec
     (concat
      (when-not (vector? central)
        [(err central-path ":kami/provider-split-repos must be a vector")])
      (when-not (= (count split-targets) (count (distinct split-targets)))
        [(err central-path "split targets must be unique")])
      (mapcat split-errors central)))))

(defn print-human [errors]
  (println "KAMI provider split audit")
  (println "central:" central-path)
  (println "materialized targets:"
           (count (filter #(contains? materialized-kinds (:kami/kind %)) central)))
  (if (empty? errors)
    (println "status: ok")
    (do
      (println "status: failed")
      (doseq [{:keys [path message]} errors]
        (println (str "  " path " - " message))))))

(defn -main [& args]
  (let [errors (audit)
        edn? (some #{"--edn"} args)
        strict? (some #{"--strict"} args)]
    (if edn?
      (prn {:valid? (empty? errors) :errors errors})
      (print-human errors))
    (when (and strict? (seq errors))
      (System/exit 1))))

(apply -main *command-line-args*)
