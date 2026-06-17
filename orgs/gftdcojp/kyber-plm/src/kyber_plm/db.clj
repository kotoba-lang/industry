(ns kyber-plm.db
  "Thin facade over Datomic Local (com.datomic/local, datomic.client.api).

   In-memory (:storage-dir :mem) so a run leaves nothing on disk — same custody
   stance as m365-archive/datomic/src/m365/load.clj. For production this conn is
   swapped for the kyber-datomic XRPC transport (ADR-0025 / mangaka.store.kotoba)
   without changing the domain/thread/cost namespaces, which only use the fns here."
  (:require [datomic.client.api :as d]
            [kyber-plm.schema :as schema]))

(defn fresh-conn
  "A fresh ephemeral in-memory DB with the PLM/ERP schema installed."
  ([] (fresh-conn "kyber"))
  ([db-name]
   (let [client (d/client {:server-type :datomic-local :storage-dir :mem :system "kyber-plm"})]
     (d/delete-database client {:db-name db-name})   ; idempotent fresh start
     (d/create-database client {:db-name db-name})
     (let [conn (d/connect client {:db-name db-name})]
       (d/transact conn {:tx-data schema/schema})
       conn))))

(defn tx!  [conn tx]  (d/transact conn {:tx-data (vec tx)}))
(defn db   [conn]     (d/db conn))
(defn q    [query db & inputs] (apply d/q query db inputs))

(defn pull
  "Pull `pattern` from `db` for entity `eid` (eid may be a lookup ref).
   Returns nil when the lookup ref resolves to nothing."
  [db pattern eid]
  (try (d/pull db pattern eid)
       (catch Exception _ nil)))

(defn attr
  "Read a single attribute for entity `eid` (lookup ref ok), or nil."
  [db a eid]
  (get (pull db [a] eid) a))

(defn exists? [db eid] (some? (:db/id (pull db [:db/id] eid))))
