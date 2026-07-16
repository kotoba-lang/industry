#!/usr/bin/env nbb
;; langchain-store-adoption-scan.cljs — increment-3 visibility for the
;; store-seam commonalization (ADR-2607141600). Scans every `store.cljc`
;; under a root (default `orgs/cloud-itonami`) and classifies each by
;; whether it has adopted the shared `kotoba-lang/langchain-store` seam
;; or still hand-rolls the EDN-blob codec that is complete-identical in
;; ~190 repos.
;;
;; Buckets:
;;   :adopted     — requires `langchain-store.core`
;;   :hand-rolled — still has the local `(defn- enc [v] (pr-str v))` /
;;                  `(defn- dec* ...)` codec (the migration backlog)
;;   :other       — a store.cljc that does neither (MemStore-only, or a
;;                  different shape — inspect manually)
;;
;; Prints a summary + the hand-rolled backlog list (migration targets).
;; No writes; safe to run anytime.
;;
;;   nbb scripts/langchain-store-adoption-scan.cljs [root-dir]
(ns langchain-store-adoption-scan
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(defn- walk-store-files [dir]
  (->> (try (fs/readdirSync dir) (catch :default _ []))
       (remove #{".git" "node_modules" ".cpcache" "target"})
       (mapcat (fn [e]
                 (let [full (path/join dir e)
                       st (try (fs/statSync full) (catch :default _ nil))]
                   (cond
                     (nil? st) []
                     (.isDirectory st) (walk-store-files full)
                     (str/ends-with? e "store.cljc") [full]
                     :else []))))))

(defn- classify [file]
  (let [c (str (fs/readFileSync file))]
    (cond
      (str/includes? c "langchain-store.core") :adopted
      (or (str/includes? c "(defn- enc [v] (pr-str v))")
          (str/includes? c "(defn- dec* ")) :hand-rolled
      :else :other)))

(defn -main [& args]
  (let [root (or (first args) "orgs/cloud-itonami")
        files (sort (walk-store-files root))
        by (group-by classify files)
        total (count files)]
    (println (str "langchain-store adoption scan — root: " root))
    (println (str "  total store.cljc : " total))
    (println (str "  :adopted         : " (count (:adopted by))))
    (println (str "  :hand-rolled     : " (count (:hand-rolled by))))
    (println (str "  :other           : " (count (:other by))))
    (when (seq (:hand-rolled by))
      (println "\nhand-rolled backlog (migrate on touch):")
      (doseq [f (sort (:hand-rolled by))] (println (str "  - " f))))
    (when (seq (:adopted by))
      (println "\nadopted:")
      (doseq [f (sort (:adopted by))] (println (str "  + " f))))))

(apply -main *command-line-args*)
