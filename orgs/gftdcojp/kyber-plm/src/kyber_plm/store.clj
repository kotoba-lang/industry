(ns kyber-plm.store
  "Backend abstraction. The PLM/ERP graph speaks three primitives — transact!,
   q*, pull* — so the same schema + tx-data runs on either:

     • Datomic Local (dev/test, in-memory, this is what kyber-plm.db wires up), or
     • kyber-datomic over XRPC (production), using the SAME wire contract as
       mangaka.store.kotoba: {:tx_edn} / {:rows_edn [[edn …]…]} / {:entity_edn}.

   Swapping backend is choosing a Store; the domain primitives are identical."
  (:require [datomic.client.api :as d]
            [clojure.edn :as edn]
            [kyber-plm.schema :as schema]))

(defprotocol Store
  (transact! [s tx]            "Apply tx-data (vector of entity maps); returns the store.")
  (q*        [s query inputs]  "Datalog query against current state → set of tuples.")
  (pull*     [s pattern eid]   "Pull `pattern` for `eid` (lookup ref ok) → map or nil."))

;; ─────────────────────────── Datomic Local (dev/test) ──────────────────────

(defrecord DatomicLocalStore [conn]
  Store
  (transact! [s tx] (d/transact conn {:tx-data (vec tx)}) s)
  (q*    [_ query inputs] (apply d/q query (d/db conn) inputs))
  (pull* [_ pattern eid]  (try (d/pull (d/db conn) pattern eid) (catch Exception _ nil))))

(defn datomic-local
  "Fresh in-memory Datomic Local store with the schema installed."
  ([] (datomic-local "kyber"))
  ([db-name]
   (let [client (d/client {:server-type :datomic-local :storage-dir :mem :system "kyber-plm"})]
     (d/delete-database client {:db-name db-name})
     (d/create-database client {:db-name db-name})
     (let [conn (d/connect client {:db-name db-name})]
       (d/transact conn {:tx-data schema/schema})
       (->DatomicLocalStore conn)))))

;; ─────────────────────────── kyber-datomic / kotoba (prod) ──────────────────

(defrecord KotobaStore [post-fn graph]
  Store
  (transact! [s tx]
    (post-fn "transact" {:graph graph :tx_edn (pr-str (vec tx))})
    s)
  (q* [_ query inputs]
    (let [res (post-fn "q" {:graph graph
                            :query_edn (pr-str query)
                            :args_edn  (pr-str (vec inputs))})]
      ;; rows_edn: each cell is EDN-encoded (mangaka kotoba wire contract)
      (into #{} (map (fn [row] (mapv edn/read-string row))) (:rows_edn res))))
  (pull* [_ pattern eid]
    (let [res (post-fn "pull" {:graph graph
                               :pattern_edn (pr-str pattern)
                               :entity      (pr-str eid)})]
      (some-> (:entity_edn res) edn/read-string))))

(defn kotoba
  "Production store over kyber-datomic XRPC. `post-fn` is the injected transport
   (method, params) → response map (same shape as mangaka.store.kotoba's post-fn,
   e.g. an XRPC client with CACAO auth). Installs the schema once on connect."
  [post-fn graph]
  (let [s (->KotobaStore post-fn graph)]
    (transact! s schema/schema)
    s))

;; ─────────────────────────── shared helpers ────────────────────────────────

(defn q [s query & inputs] (q* s query inputs))

(defn attr
  "Single attribute value for `eid` (lookup ref ok), or nil — backend-agnostic."
  [s a eid]
  (get (pull* s [a] eid) a))
