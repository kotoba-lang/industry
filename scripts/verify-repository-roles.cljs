#!/usr/bin/env nbb
;; Validate kotoba.repository-role-rules.v0 declarations against real
;; production git dependencies.  Test-only dependencies are intentionally
;; excluded: the rule describes the shipped dependency closure.

(require '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(def allowed-role-edges
  {:contract #{:contract :library :assurance}
   :library #{:contract :library}
   :backend #{:contract :library :backend}
   :compiler #{:contract :library :backend :verifier}
   :verifier #{:contract :backend}
   :runtime #{:contract :library :backend :compiler :verifier :runtime :assurance}
   :provider #{:contract :runtime :assurance}
   :assurance #{:contract :library :assurance}
   :placement #{:runtime :assurance}})

(defn fail! [message data]
  (binding [*print-namespace-maps* false]
    (println (str "ERROR " message " " (pr-str data))))
  (compat/exit 1))

(defn read-edn [file]
  (edn/read-string (compat/slurp file)))

(defn normalize-repo [value]
  (let [repo (if (map? value) (:repo value) value)]
    (cond
      (not (string? repo)) nil
      (str/includes? repo "/") repo
      :else (str "kotoba-lang/" repo))))

(defn git-url->repo [url]
  (some->> url
           (re-find #"github\.com[/:]([^/]+)/([^/.]+)(?:\.git)?$")
           rest
           ((fn [[org repo]] (when (and org repo) (str org "/" repo))))))

(defn production-deps [deps-file]
  (let [deps (:deps (read-edn deps-file))]
    (into #{}
          (keep (fn [[_ coordinate]]
                  (some-> (:git/url coordinate) git-url->repo)))
          deps)))

(defn repo-record [dir]
  (let [rule-file (.join path dir "resources" "repository-rules.edn")
        deps-file (.join path dir "deps.edn")]
    (when-not (.existsSync fs rule-file)
      (fail! "repository-rules.edn is missing" {:repo-dir dir}))
    (let [rule (read-edn rule-file)
          declared (:this-repo rule)
          name (:name declared)
          repo (str "kotoba-lang/" name)]
      (when-not (= "kotoba.repository-role-rules.v0" (:schema rule))
        (fail! "unsupported repository role schema" {:repo repo :schema (:schema rule)}))
      (when-not (= name (.basename path dir))
        (fail! "declared repository name differs from checkout" {:repo repo :dir dir}))
      {:repo repo
       :tier (:tier declared)
       :role (:role declared)
       :declared (into #{} (keep normalize-repo) (:depends-on declared))
       :actual (if (.existsSync fs deps-file) (production-deps deps-file) #{})})))

(defn assert-declarations! [records]
  (doseq [{:keys [repo tier role declared actual]} records]
    (when-not (and (keyword? tier) (keyword? role) (contains? allowed-role-edges role))
      (fail! "tier/role is missing or unknown" {:repo repo :tier tier :role role}))
    (when-not (= declared actual)
      (fail! "declared dependencies differ from production deps"
             {:repo repo
              :missing-from-rule (vec (sort (set/difference actual declared)))
              :not-in-production (vec (sort (set/difference declared actual)))}))))

(defn assert-role-direction! [records]
  (let [by-repo (into {} (map (juxt :repo identity)) records)]
    (doseq [{from :repo from-role :role deps :declared} records
            dep deps
            :let [target (get by-repo dep)]
            :when target]
      (when-not (contains? (get allowed-role-edges from-role) (:role target))
        (fail! "dependency crosses a forbidden responsibility edge"
               {:from from :from-role from-role
                :to dep :to-role (:role target)})))))

(defn assert-acyclic! [records]
  (let [known (set (map :repo records))
        graph (into {} (map (fn [{:keys [repo declared]}]
                              [repo (set/intersection known declared)]))
                    records)
        visiting (volatile! #{})
        visited (volatile! #{})]
    (letfn [(visit! [node trail]
              (when (contains? @visiting node)
                (fail! "repository dependency cycle" {:cycle (conj trail node)}))
              (when-not (contains? @visited node)
                (vswap! visiting conj node)
                (doseq [dep (get graph node)] (visit! dep (conj trail node)))
                (vswap! visiting disj node)
                (vswap! visited conj node)))]
      (doseq [node known] (visit! node [])))))

(let [dirs (vec *command-line-args*)]
  (when (empty? dirs)
    (println "Usage: nbb scripts/verify-repository-roles.cljs <repo-dir>...")
    (compat/exit 2))
  (let [records (mapv repo-record dirs)]
    (assert-declarations! records)
    (assert-role-direction! records)
    (assert-acyclic! records)
    (doseq [{:keys [repo tier role actual]} (sort-by :repo records)]
      (println (str "OK " repo " " (name tier) "/" (name role)
                    " deps=" (count actual))))
    (println (str "verify-repository-roles: " (count records) " repositories OK"))))
