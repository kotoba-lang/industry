#!/usr/bin/env nbb
(require '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def root (.cwd js/process))
(def policy-file (.join path root "manifest" "d1-kotobase-migration.edn"))

(defn fail! [message data]
  (binding [*print-namespace-maps* false]
    (println (str "ERROR " message " " (pr-str data))))
  (.exit js/process 1))

(defn config? [name]
  (boolean (re-matches #"wrangler(?:\.[A-Za-z0-9_-]+)?\.(?:jsonc?|toml)" name)))

(def ignored-dirs #{".git" ".claude" "node_modules" "target" "dist" ".wrangler"})

(defn files-under [directory]
  (mapcat (fn [entry]
            (let [name (.-name entry) full (.join path directory name)]
              (cond
                (and (.isDirectory entry) (not (contains? ignored-dirs name))) (files-under full)
                (and (.isFile entry) (config? name)) [full]
                :else [])))
          (.readdirSync fs directory #js {:withFileTypes true})))

(defn active-d1? [file]
  (let [text (.readFileSync fs file "utf8")
        without-blocks (str/replace text #"(?s)/\*.*?\*/" "")
        active-lines (->> (str/split-lines without-blocks)
                          (remove #(re-matches #"\s*(?://|#).*" %))
                          (str/join "\n"))]
    (boolean (re-find #"(?i)(?:\[\[\s*d1_databases\s*\]\]|[\"']?d1_databases[\"']?\s*:)" active-lines))))

(when-not (.existsSync fs policy-file)
  (fail! "migration policy missing" {:file policy-file}))

(let [policy (edn/read-string (.readFileSync fs policy-file "utf8"))
      _ (when-not (= "kotobase.d1-migration.v1" (:schema policy))
          (fail! "unsupported migration policy" {:schema (:schema policy)}))
      registered (set (map :config (concat (:bindings policy) (:reviewed-configs policy))))
      discovered (->> (files-under (.join path root "orgs"))
                      (filter active-d1?)
                      (map #(.relative path root %))
                      set)
      unregistered (sort (set/difference discovered registered))
      stale (sort (set/difference registered discovered))]
  (when (seq unregistered)
    (fail! "active D1 binding is not registered; new D1 bindings are forbidden"
           {:configs unregistered}))
  (when (seq stale)
    (fail! "migration inventory contains a config that no longer binds D1; remove its entry"
           {:configs stale}))
  (println (str "ok - " (count discovered) " active D1 configs are closed-world registered; no unreviewed binding")))
