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

(defn subset-db-eids
  "A db holding only `eids`.

  Takes the entity ids rather than a dataset predicate so that a caller writing
  every shard can decide which entity belongs where in one pass. `subset-db`
  answers that question by scanning the whole db, which is the right shape for
  one subset and the wrong shape for a hundred: shard writing was scanning all
  480,253 entities once per dataset (measured 2026-09-06)."
  [db eids]
  (if (empty? eids)
    (index/empty-db)
    (index/assert-quads (index/empty-db)
                        (vec (quads-for-entities db eids))
                        (constantly false))))

(defn subset-db
  "Keep only entities whose `source/dataset` is in `datasets` (set of strings)."
  [db datasets]
  (subset-db-eids db (into #{}
                           (filter #(contains? datasets (entity->dataset db %))
                                   (keys (:eavt db))))))

(defn db-quads
  "Every quad in `db`.

  Reads `:eavt` and nothing else, because the other three indices hold the same
  datoms in a different order and whoever receives these quads asserts them back
  into all four. That is also why a shard file's `:aevt` and `:avet` were only
  ever written, parsed and dropped."
  [db]
  (mapcat (fn [[s pm]]
            (mapcat (fn [[p os]] (map (fn [o] {:s s :p p :o o}) os)) pm))
          (:eavt db)))

;; Merging is an accumulator, not a fold over a materialised list of dbs, so
;; that a caller reading many shards can deserialise one, add it, and let it
;; become garbage before opening the next -- rather than holding every shard and
;; the union at the same time.
;;
;; The accumulator is also what keeps the cost linear. `assert-quads` walks the
;; existing outer keys once per call to make their inner maps transient, so a
;; fold that calls it once per db pays that walk once per db -- quadratic in the
;; number of shards. `index/mutable-db` exists precisely to pay it once; its own
;; docstring records an LDBC load that never finished in the looped form.

(defn merge-start
  "A mutable accumulator for `merge-add!`. NOT a db -- finish it with
  `merge-finish` before querying or serialising it."
  []
  (index/mutable-db (index/empty-db)))

(defn merge-add!
  "Add every datom of `db` to the accumulator, returning it."
  [macc db]
  (index/assert-quads! macc (db-quads db) (constantly false)))

(defn merge-finish
  "Finish an accumulator, returning a db with all four indices built."
  [macc]
  (index/persist-db macc))

(defn merge-dbs
  "Union of several datalog dbs (disjoint entity sets assumed)."
  [dbs]
  (merge-finish (reduce merge-add! (merge-start) dbs)))

(defn datom-count [db]
  ;; The inner step used to read `(fn [m _p os] (+ m (count os)) 0)` -- two body
  ;; forms, so it returned the literal `0` and threw the sum away. Every db
  ;; therefore counted zero datoms, and the shard index has been recording
  ;; `:datoms 0` for every shard it ever wrote. A count that cannot come out
  ;; anything but zero is not a count.
  (reduce-kv
   (fn [n _s pm]
     (+ n (reduce-kv (fn [m _p os] (+ m (count os))) 0 pm)))
   0
   (:eavt db)))
