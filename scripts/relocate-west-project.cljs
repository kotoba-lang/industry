#!/usr/bin/env nbb
(ns relocate-west-project
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            ["fs" :as fs]))

(defn fail! [message data]
  (binding [*out* *err*]
    (println message)
    (prn data))
  (js/process.exit 2))

(defn replace-path [value from to]
  (cond
    (= value from) to
    (vector? value) (mapv #(replace-path % from to) value)
    (set? value) (set (map #(replace-path % from to) value))
    (map? value)
    (into (empty value)
          (map (fn [[k v]]
                 [(replace-path k from to)
                  (replace-path v from to)]))
          value)
    :else value))

(def blob-attrs
  #{:manifest.repos/datalad
    :manifest.repos/rad-rids
    :manifest.repos/path-overrides})

(defn migrate [entity from to]
  (let [entity
        (reduce
         (fn [current attr]
           (if-let [blob (get current attr)]
             (assoc current attr
                    (pr-str (replace-path (reader/read-string blob)
                                          from to)))
             current))
         entity
         blob-attrs)
        entity (update entity :manifest.repos/extra-projects
                       #(replace-path % from to))
        overrides (reader/read-string
                   (:manifest.repos/path-overrides entity))]
    (assoc entity :manifest.repos/path-overrides
           (pr-str (assoc overrides from to)))))

(let [[path from to & extra] (drop 3 (js->clj (.-argv js/process)))]
  (when (or (str/blank? path) (str/blank? from) (str/blank? to)
            (seq extra))
    (fail! "Usage: nbb scripts/relocate-west-project.cljs REPOS.edn FROM TO"
           {:arguments [path from to]}))
  (let [tx (reader/read-string (.readFileSync fs path "utf8"))
        _ (when-not (= 1 (count tx))
            (fail! "Expected one manifest source entity" {:count (count tx)}))
        migrated [(migrate (first tx) from to)]
        rendered (str (pr-str migrated) "\n")]
    (.writeFileSync fs path rendered)
    (println "relocated" from "->" to)))
