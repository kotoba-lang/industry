#!/usr/bin/env nbb
(require '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def root (.cwd js/process))
(def ledger-path "90-docs/migration/kotoba-cljc-common-lib-frontier.edn")

(defn full [p] (.join path root p))
(defn exists? [p] (.existsSync fs (full p)))
(defn read-text [p] (.readFileSync fs (full p) "utf8"))
(defn fail! [errors message data]
  (swap! errors conj {:message message :data data}))

(defn source-counts [repo]
  (let [source-root (full (str "orgs/kotoba-lang/" repo "/src"))
        counts (atom {:clj 0 :cljc 0 :cljs 0 :kotoba 0})]
    (letfn [(walk [dir]
              (when (.existsSync fs dir)
                (doseq [entry (.readdirSync fs dir #js {:withFileTypes true})]
                  (let [p (.join path dir (.-name entry))]
                    (if (.isDirectory entry)
                      (walk p)
                      (when-let [kind ({".clj" :clj ".cljc" :cljc
                                        ".cljs" :cljs ".kotoba" :kotoba}
                                       (.extname path p))]
                        (swap! counts update kind inc)))))))]
      (walk source-root))
    @counts))

(defn dep-id [dep]
  (str (namespace dep) "/" (name dep)))

(defn first-party-dep? [dep]
  (= "io.github.kotoba-lang" (namespace dep)))

(defn repo-name [dep]
  (name dep))

(def ledger (edn/read-string (read-text ledger-path)))
(def libraries (vec (mapcat :libraries (:layers ledger))))
(def library-by-repo (into {} (map (juxt :repo identity) libraries)))
(def errors (atom []))
(def blockers (atom []))

(when-not (= :kotoba.cljc-common-lib-frontier/v1 (:schema ledger))
  (fail! errors "unexpected ledger schema" {:schema (:schema ledger)}))
(when-not (= :kotoba-lang-only (get-in ledger [:policy :leaf-runtime-dependencies]))
  (fail! errors "leaf runtime dependency policy must be kotoba-lang-only"
         {:actual (get-in ledger [:policy :leaf-runtime-dependencies])}))
(when-not (= :forbidden (get-in ledger [:policy :generic-core-repository]))
  (fail! errors "generic core repositories must stay forbidden" {}))

(doseq [{:keys [repo authority status first-party-deps external-runtime-deps tool-deps] :as lib}
        libraries]
  (let [repo-root (str "orgs/kotoba-lang/" repo)
        deps-path (str repo-root "/deps.edn")]
    (when-not (exists? repo-root)
      (fail! errors "cataloged common library repository is missing" {:repo repo}))
    (when-not (exists? deps-path)
      (fail! errors "cataloged common library has no deps.edn" {:repo repo}))
    (when (exists? deps-path)
      (try
        (let [deps (keys (:deps (edn/read-string (read-text deps-path))))
              declared-tools (set tool-deps)
              runtime-deps (remove #(contains? declared-tools (dep-id %)) deps)
              actual-first (set (map repo-name (filter first-party-dep? runtime-deps)))
              actual-external (set (map dep-id (remove first-party-dep? runtime-deps)))
              declared-first (set first-party-deps)
              declared-external (set external-runtime-deps)
              undeclared-external (set/difference actual-external declared-external)]
          (when-not (= actual-first declared-first)
            (fail! errors "first-party dependency declaration drift"
                   {:repo repo :declared declared-first :actual actual-first}))
          (when (seq undeclared-external)
            (fail! errors "undeclared non-kotoba runtime dependency"
                   {:repo repo :dependencies undeclared-external}))
          (when (seq (set/intersection actual-external declared-external))
            (swap! blockers conj
                   {:repo repo :kind :external-runtime-dependency
                    :dependencies (set/intersection actual-external declared-external)})))
        (catch :default e
          (fail! errors "deps.edn cannot be parsed"
                 {:repo repo :error (.-message e)}))))
    (let [{:keys [clj cljc cljs kotoba] :as counts} (source-counts repo)]
      (when (and (= authority :kotoba) (zero? kotoba))
        (fail! errors "Kotoba-authoritative library has no production .kotoba source"
               {:repo repo :counts counts}))
      (when (and (contains? #{:cljc-oracle :mixed} authority) (zero? (+ clj cljc cljs)))
        (fail! errors "oracle/mixed library has no compatibility source"
               {:repo repo :counts counts}))
      (when-not (= status :sovereign)
        (swap! blockers conj {:repo repo :kind :source-authority
                              :authority authority :status status :counts counts})))))

(doseq [{:keys [target-repo status]} (:planned-surfaces ledger)]
  (when (and (= status :design-first)
             (not (contains? library-by-repo target-repo))
             (exists? (str "orgs/kotoba-lang/" target-repo)))
    (fail! errors "planned library exists but is absent from the layer catalog"
           {:repo target-repo})))

(let [known (set (keys library-by-repo))]
  (doseq [{:keys [repo first-party-deps]} libraries
          dep first-party-deps]
    (when-not (contains? known dep)
      (fail! errors "common-lib dependency is outside the audited closure"
             {:repo repo :dependency dep}))))

(println (str "Kotoba common-lib frontier: libraries=" (count libraries)
              " planned-surfaces=" (count (:planned-surfaces ledger))
              " blockers=" (count @blockers)
              " problems=" (count @errors)))
(doseq [blocker @blockers]
  (println "  BLOCKER" (pr-str blocker)))
(doseq [error @errors]
  (println "  ERROR" (pr-str error)))
(when (seq @errors) (.exit js/process 1))
