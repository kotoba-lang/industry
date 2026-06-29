#!/usr/bin/env bb
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))
(def cfg (edn/read-string (slurp (io/file root "manifest" "kotoba-boundaries.edn"))))
(def kotoba-root (io/file root "orgs/kotoba-lang/kotoba"))
(def legacy-root (io/file kotoba-root "crates/kotoba-kotodama"))

(defn rel [f]
  (-> (.toPath (io/file f))
      (.toAbsolutePath)
      (.normalize)
      (str/replace (str (.normalize (.toAbsolutePath (.toPath (io/file root)))) "/") "")))

(defn files-under [dir pred]
  (when (.exists dir)
    (->> (file-seq dir)
         (filter #(.isFile %))
         (filter pred))))

(defn dirs-under [dir]
  (when (.exists dir)
    (->> (.listFiles dir)
         (filter #(.isDirectory %))
         (sort-by #(.getName %)))))

(defn actor-repo [actor]
  (when (seq actor)
    (let [repo (-> actor
                   (str/replace #"_" "-")
                   (str/replace #"^com-etzhayyim-" ""))]
      (str "orgs/etzhayyim/com-etzhayyim-" repo))))

(defn first-match [s patterns]
  (some (fn [[re f]]
          (when-let [m (re-find re s)]
            (f m)))
        patterns))

(defn compat-shim [path]
  (when-let [entry (get cfg :compat-shims)]
    (get entry path)))

(defn primitive-route [path]
  (get (:primitive-routes cfg) (last (str/split path #"/"))))

(defn prefix-route [cell-name]
  (let [prefix (first (str/split cell-name #"[_-]"))]
    (when-let [repo (get-in cfg [:cell-prefix-routes prefix])]
      (str "orgs/etzhayyim/" repo))))

(defn cell-target [cell-dir]
  (let [name (.getName cell-dir)
        readme (io/file cell-dir "README.md")
        text (if (.exists readme) (slurp readme) "")]
    (or
     (first-match
      text
      [[#"(?i)Paired actor:\s*\[?([A-Za-z0-9_-]+)" #(actor-repo (second %))]
       [#"(?i)Paired with\s+`20-actors/([^/`]+)" #(actor-repo (second %))]
       [#"(?i)actor:\s+`20-actors/([^/`]+)" #(actor-repo (second %))]])
     (prefix-route name)
     "orgs/etzhayyim/com-etzhayyim-TBD")))

(defn classify-primitive [f]
  (let [path (rel f)
        text (slurp f)]
    (cond
      (compat-shim path)
      {:kind :compat-shim :path path :target (:target (compat-shim path))}

      (primitive-route path)
      {:kind :domain-actor :path path :target (primitive-route path)}

      (re-find #"(?i)atproto|com\.atproto|PDS_BASE|xrpc|repo\.createRecord" text)
      {:kind :atproto-actor :path path :target (get-in cfg [:owners :atproto-actors])}

      (re-find #"(?i)murakumo|hosting|host-sdk|fleet|deploy|gateway|RunPod" text)
      {:kind :hosting :path path :target (get-in cfg [:owners :hosting])}

      :else
      {:kind :domain-actor :path path :target "orgs/etzhayyim/com-etzhayyim-TBD"})))

(defn under-legacy-cells? [f]
  (str/includes? (rel f) "/crates/kotoba-kotodama/cells/"))

(defn cell-entry [dir]
  {:kind :domain-cell
   :path (rel dir)
   :target (cell-target dir)})

(defn distinct-by [f coll]
  (loop [seen #{}
         out []
         xs (seq coll)]
    (if-not xs
      out
      (let [x (first xs)
            k (f x)]
        (if (contains? seen k)
          (recur seen out (next xs))
          (recur (conj seen k) (conj out x) (next xs)))))))

(defn entries []
  (let [cells (map cell-entry (dirs-under (io/file legacy-root "cells")))
        primitives (map classify-primitive
                        (files-under (io/file legacy-root "py/src/kotodama/primitives")
                                     #(str/ends-with? (.getName %) ".py")))
        routed-files (map (fn [f] {:kind :domain-actor :path (rel f) :target (primitive-route (rel f))})
                          (files-under legacy-root
                                       #(and (primitive-route (rel %))
                                             (not (str/includes? (rel %) "/py/src/kotodama/primitives/")))))
        atproto-files (map (fn [f] {:kind :atproto-actor :path (rel f) :target (get-in cfg [:owners :atproto-actors])})
                           (files-under legacy-root
                                        #(and (not (under-legacy-cells? %))
                                              (not (compat-shim (rel %)))
                                              (not (primitive-route (rel %)))
                                              (not (str/includes? (rel %) "/py/src/kotodama/primitives/"))
                                              (re-find #"\.(clj|cljc|cljs|py|ts|js|json|edn|md)$" (.getName %))
                                              (re-find #"(?i)atproto|com\.atproto|xrpc|PDS" (slurp %)))))
        hosting-files (map (fn [f] {:kind :hosting :path (rel f) :target (get-in cfg [:owners :hosting])})
                           (files-under legacy-root
                                        #(and (not (under-legacy-cells? %))
                                              (not (compat-shim (rel %)))
                                              (not (primitive-route (rel %)))
                                              (re-find #"\.(clj|cljc|cljs|py|ts|js|json|edn|md|toml)$" (.getName %))
                                              (re-find #"(?i)murakumo|host-sdk|hosting|gateway|fleet" (slurp %)))))]
    ;; Priority matters: cell-directory ownership beats file scans; primitives
    ;; are classified once before broader AT Protocol/hosting text scans.
    (distinct-by :path (concat cells primitives routed-files atproto-files hosting-files))))

(defn summary [xs]
  {:total (count xs)
   :by-kind (frequencies (map :kind xs))
   :by-target (into (sorted-map) (frequencies (map :target xs)))})

(let [xs (vec (entries))
      edn? (some #{"--edn"} *command-line-args*)]
  (if edn?
    (prn {:summary (summary xs) :entries xs})
    (do
      (println "kotoba boundary audit")
      (println "summary:" (pr-str (summary xs)))
      (println)
      (doseq [{:keys [kind path target]} (take 200 xs)]
        (println (format "%-15s %-55s -> %s" (name kind) path target)))
      (when (> (count xs) 200)
        (println (format "... %d more entries. Use --edn for full output." (- (count xs) 200)))))))
