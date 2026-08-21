#!/usr/bin/env nbb
(ns register-archived-west-project
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            ["fs" :as fs]))

(defn fail! [message data]
  (binding [*out* *err*]
    (println message)
    (prn data))
  (js/process.exit 2))

(let [[repos-path project-path note & extra]
      (drop 3 (js->clj (.-argv js/process)))]
  (when (or (str/blank? repos-path)
            (str/blank? project-path)
            (str/blank? note)
            (seq extra))
    (fail! "Usage: nbb scripts/register-archived-west-project.cljs REPOS.edn PROJECT-PATH NOTE"
           {:arguments [repos-path project-path note]}))
  (let [tx (reader/read-string (.readFileSync fs repos-path "utf8"))
        _ (when-not (= 1 (count tx))
            (fail! "Expected one manifest source entity" {:count (count tx)}))
        entity (first tx)
        archived (reader/read-string (:manifest.repos/archived entity))
        projects (vec (:manifest.repos/extra-projects entity))
        updated (-> entity
                    (assoc :manifest.repos/archived
                           (pr-str (assoc archived project-path
                                          {:group "archived" :note note})))
                    (assoc :manifest.repos/extra-projects
                           (if (some #{project-path} projects)
                             projects
                             (conj projects project-path))))]
    (.writeFileSync fs repos-path (str (pr-str [updated]) "\n"))
    (println "registered archived project" project-path)))
