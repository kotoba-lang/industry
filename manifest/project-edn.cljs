#!/usr/bin/env nbb
;; Deterministically concatenate tx-data EDN inputs declared by an
;; ADR-2608039700 projection contract. Input order and entity order are
;; preserved; output is one canonical `pr-str` vector plus a newline.
;;
;;   nbb manifest/project-edn.cljs project <projection.edn> <output.edn>

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.shell :as shell])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

(defn fail! [message data]
  (throw (ex-info message data)))

(defn inside-root [p]
  (when-not (and (string? p) (not (str/blank? p)))
    (fail! "projection path must be a non-empty string" {:path p}))
  (let [absolute (.resolve path root p)
        relative (.relative path root absolute)]
    (when (or (= relative "..")
              (str/starts-with? relative (str ".." (.-sep path))))
      (fail! "projection path escapes repository root" {:path p}))
    absolute))

(defn read-edn [p]
  (edn/read-string (.readFileSync fs (inside-root p) "utf8")))

(defn tx-entities [p]
  (let [x (read-edn p)
        entities (cond
                   (map? x) [x]
                   (vector? x) x
                   :else (fail! "projection input must be an EDN map or vector"
                                {:path p :type (type x)}))]
    (when-not (every? map? entities)
      (fail! "projection input vector must contain only entity maps" {:path p}))
    (when-not (every? #(contains? % :db/id) entities)
      (fail! "projection entity is missing :db/id" {:path p}))
    entities))

(defn project! [contract-path output-path]
  (let [contract (read-edn contract-path)
        inputs (->> (:projection/inputs contract)
                    (filter #(= :git (:input/type %)))
                    (map :input/path)
                    vec)]
    (when-not (seq inputs)
      (fail! "projection contract has no Git EDN inputs" {:contract contract-path}))
    (when-not (every? #(str/ends-with? (str/lower-case %) ".edn") inputs)
      (fail! "project-edn accepts only .edn Git inputs" {:inputs inputs}))
    (let [entities (vec (mapcat tx-entities inputs))
          output (inside-root output-path)]
      (.mkdirSync fs (.dirname path output) #js {:recursive true})
      (.writeFileSync fs output (str (pr-str entities) "\n"))
      (println (pr-str {:projection/output output-path
                        :projection/entities (count entities)
                        :projection/inputs (count inputs)}))
      entities)))

(defn usage []
  (println "usage: project-edn.cljs project <projection.edn> <output.edn>"))

(let [argv (vec (js->clj (.-argv js/process)))
      command-index (first (keep-indexed (fn [n x] (when (= "project" x) n)) argv))
      contract-path (when command-index (get argv (inc command-index)))
      output-path (when command-index (get argv (+ command-index 2)))]
  (try
    (if (and command-index contract-path output-path)
      (project! contract-path output-path)
      (do (usage) (set! (.-exitCode js/process) 2)))
    (catch :default e
      (js/console.error "project-edn: FAIL" (ex-message e) (pr-str (ex-data e)))
      (set! (.-exitCode js/process) 1))))
