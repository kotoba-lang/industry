(ns manifest.edn-query-datalog
  "kotoba-lang/datalog backend for manifest/edn-query.cljs (ADR-2608260200).

  Replaces npm `datascript` with the same bare-string attribute convention and
  integer entity ids queries already expect."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datalog.core :as dl]
            [datalog.index :as index]))

(def ^:dynamic *card-many-attrs*
  "Cardinality-many attribute names (bare strings). Bind or extend before build."
  #{"patent/applicant-norm"
    "yakuwari.policy/autonomous" "yakuwari.policy/voice-required"
    "yakuwari.policy/approval-required" "yakuwari.policy/blocked"
    "yakuwari.policy/unknown" "yakuwari/capability" "yakuwari/runners"
    "business/roles" "factory/capabilities" "factory/fulfillment-modes"})

(defn kw->attr
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (and (string? k) (str/starts-with? k ":") (> (count k) 1)) (subs k 1)
    (string? k) k
    :else (str k)))

(defn- scalar? [v]
  (or (string? v) (number? v) (boolean? v) (keyword? v) (nil? v)))

(defn ->value
  "Match manifest/edn-query.cljs `->ds-value` / `->ds-scalar` encoding."
  [v]
  (cond
    (keyword? v) (kw->attr v)
    (and (string? v) (str/starts-with? v ":") (> (count v) 1)) (subs v 1)
    (map? v) (pr-str v)
    (set? v) (pr-str v)
    (nil? v) ""
    :else v))

(defn- card-many? [attr]
  (contains? *card-many-attrs* attr))

(defn- entity->quads [eid ent]
  (mapcat
   (fn [[k v]]
     (when (not= k :db/id)
       (let [attr (kw->attr k)]
         (cond
           (and (card-many? attr) (sequential? v))
           (map (fn [item] {:s eid :p attr :o (->value item)}) v)

           (and (sequential? v) (every? scalar? v))
           (map (fn [item] {:s eid :p attr :o (->value item)}) v)

           :else
           [{:s eid :p attr :o (->value v)}]))))
   ent))

(defn- transact-entities! [mdb ents]
  (reduce
   (fn [acc ent]
     (let [eid (:db/id ent)
           quads (entity->quads eid ent)]
       ;; edn-query は :db.type/ref を解決しない（値一致 join）。vaet は不要。
       (index/assert-quads! acc quads (constantly false))))
   mdb
   ents))

(defn build-db
  "Load entity maps (each must have `:db/id`) into a datalog db."
  [entities]
  (let [batch-size 50000
        entities (vec entities)]
    (loop [mdb (index/mutable-db (index/empty-db))
           i 0
           n (count entities)]
      (if (>= i n)
        (index/persist-db mdb)
        (recur (transact-entities! mdb (subvec entities i (min n (+ i batch-size))))
               (+ i batch-size)
               n)))))

(defn- parse-vector-query [query]
  (let [qvec (if (string? query) (edn/read-string query) query)
        idx-in (or (first (keep-indexed #(when (= %2 :in) %1) qvec)) -1)
        idx-where (or (first (keep-indexed #(when (= %2 :where) %1) qvec)) -1)
        find-end (if (pos? idx-in) idx-in idx-where)
        find-syms (vec (subvec qvec 1 find-end))
        in-syms (when (pos? idx-in) (vec (subvec qvec (inc idx-in) idx-where)))
        where-clauses (when (pos? idx-where) (vec (subvec qvec (inc idx-where))))]
    {:find find-syms :in in-syms :where where-clauses}))

(defn- norm-ground [x]
  (cond
    (symbol? x) x
    (keyword? x) (kw->attr x)
    :else x))

(defn- norm-clause [clause]
  (mapv norm-ground clause))

(defn q
  "Run a DataScript-shaped vector query string against `db`."
  [db query & inputs]
  (let [{:keys [find in where]} (parse-vector-query query)
        in-syms (vec (remove #{'$} (or in [])))
        _ (when (not= (count in-syms) (count inputs))
            (throw (ex-info "edn-query-datalog: :in arity mismatch"
                            {:in in-syms :inputs inputs})))]
    (dl/q db {:find find :in in :where (mapv norm-clause where)}
          (constantly true)
          inputs)))

(defn serialize-db [db]
  (pr-str db))

(defn deserialize-db [s]
  (edn/read-string {:default (fn [_tag v] v)} s))

(defn entity->dataset
  "eid -> `source/dataset` string, or nil."
  [db eid]
  (first (get-in db [:eavt eid "source/dataset"] #{})))

(defn- quads-for-entities [db eids]
  (mapcat
   (fn [eid]
     (when-let [pm (get-in db [:eavt eid])]
       (mapcat (fn [[p os]] (map (fn [o] {:s eid :p p :o o}) os)) pm)))
   eids))

(defn subset-db
  "Keep only entities whose `source/dataset` is in `datasets` (set of strings)."
  [db datasets]
  (let [eids (into #{}
                   (filter #(contains? datasets (entity->dataset db %))
                           (keys (:eavt db))))]
    (if (empty? eids)
      (index/empty-db)
      (let [quads (vec (quads-for-entities db eids))]
        (index/assert-quads (index/empty-db) quads (constantly false))))))

(defn merge-dbs
  "Union of several datalog dbs (disjoint entity sets assumed)."
  [dbs]
  (reduce
   (fn [acc db]
     (let [quads (mapcat (fn [[s pm]]
                           (mapcat (fn [[p os]]
                                     (map (fn [o] {:s s :p p :o o}) os))
                                   pm))
                         (:eavt db))]
       (index/assert-quads acc quads (constantly false))))
   (index/empty-db)
   dbs))

(defn datom-count [db]
  (reduce-kv
   (fn [n _s pm]
     (+ n (reduce-kv (fn [m _p os] (+ m (count os)) 0) 0 pm)))
   0
   (:eavt db)))
