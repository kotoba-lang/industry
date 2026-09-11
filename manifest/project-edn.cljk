#!/usr/bin/env nbb
;; Build canonical Datomic/DataScript-shaped tx-data from the Git EDN inputs
;; declared by an ADR-2608039700 projection contract.
;;
;; This loader is intentionally narrow: it reads Git-resident EDN only. Annex
;; retrieval and age decryption are explicit capabilities handled before this
;; loader; their identities remain bound by the projection contract.

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def root (or (some-> (.-env js/process) (aget "PROJECTION_ROOT"))
              (.cwd js/process)))

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

(defn stable-key [x] (pr-str x))
(defn stable-compare [a b] (compare (stable-key a) (stable-key b)))

(defn canonical-value [x]
  (cond
    (map? x) (into (sorted-map-by stable-compare)
                   (map (fn [[k v]] [k (canonical-value v)]) x))
    (set? x) (into (sorted-set-by stable-compare) (map canonical-value x))
    (vector? x) (mapv canonical-value x)
    (sequential? x) (mapv canonical-value x)
    :else x))

(defn schema-index [schema-path]
  (let [schema (read-edn schema-path)]
    (when-not (and (vector? schema) (every? map? schema))
      (fail! "projection schema must be a vector of attribute maps"
             {:path schema-path}))
    (into {} (map (juxt :db/ident identity) schema))))

(defn value-type? [value-type value]
  (case value-type
    :db.type/string (string? value)
    :db.type/keyword (keyword? value)
    :db.type/boolean (boolean? value)
    :db.type/long (integer? value)
    :db.type/bigint (integer? value)
    :db.type/double (number? value)
    :db.type/float (number? value)
    :db.type/bigdec (number? value)
    :db.type/ref true
    :db.type/uuid true
    :db.type/instant true
    :db.type/bytes true
    false))

(defn many-values [value]
  (when-not (or (set? value) (sequential? value))
    (fail! "cardinality-many attribute requires a collection" {:value value}))
  value)

(defn validate-attribute! [schema attr value]
  (when-not (= attr :db/id)
    (let [{:db/keys [valueType cardinality] :as spec} (get schema attr)]
      (when-not spec
        (fail! "projection entity uses an attribute absent from schema"
               {:attribute attr}))
      (let [values (if (= cardinality :db.cardinality/many)
                     (many-values value)
                     [value])]
        (doseq [v values]
          (when-not (value-type? valueType v)
            (fail! "projection attribute value does not match schema"
                   {:attribute attr :expected valueType :value v})))))))

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

(defn entity-identity [identity-attrs entity]
  (let [missing (remove #(contains? entity %) identity-attrs)]
    (when (seq missing)
      (fail! "projection entity is missing a declared identity attribute"
             {:missing (vec missing) :db/id (:db/id entity)}))
    (mapv (fn [attr] [attr (canonical-value (get entity attr))]) identity-attrs)))

(defn project! [contract-path output-path]
  (let [contract (read-edn contract-path)
        inputs (->> (:projection/inputs contract)
                    (filter #(= :git (:input/type %)))
                    (map :input/path)
                    vec)
        schema-path (get-in contract [:projection/contracts :schema/path])
        identity-attrs (:projection/identity-attrs contract)
        schema (schema-index schema-path)]
    (when-not (seq inputs)
      (fail! "projection contract has no Git EDN inputs" {:contract contract-path}))
    (when-not (every? #(str/ends-with? (str/lower-case %) ".edn") inputs)
      (fail! "project-edn accepts only .edn Git inputs" {:inputs inputs}))
    (when-not (and (vector? identity-attrs) (seq identity-attrs)
                   (every? keyword? identity-attrs))
      (fail! "projection contract requires keyword :projection/identity-attrs" {}))
    (let [entities (vec (mapcat tx-entities inputs))
          identities (mapv #(entity-identity identity-attrs %) entities)]
      (doseq [entity entities
              [attr value] entity]
        (validate-attribute! schema attr value))
      (when-not (= (count identities) (count (distinct identities)))
        (fail! "projection entity identity is not unique" {:identities identities}))
      (let [ordered (->> (map vector identities entities)
                         (sort-by (comp stable-key first))
                         (mapv (fn [[_ entity]] (canonical-value entity))))
            output (inside-root output-path)]
        (.mkdirSync fs (.dirname path output) #js {:recursive true})
        (.writeFileSync fs output (str (pr-str ordered) "\n"))
        (println (pr-str {:projection/output output-path
                          :projection/entities (count ordered)
                          :projection/inputs (count inputs)}))
        ordered))))

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
