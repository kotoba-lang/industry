(ns kyber-plm.kotobase
  "Project the PLM/ERP Datomic graph into kotobase `kg.ingest` entity payloads —
   the tenant WRITE surface proven live in live/LIVE.md (write→commit→read-back
   against kotobase.gftd.ai, tenant identity, no operator master key).

   The kotobase KG model is entity + claims + relations, NOT Datomic tx-data, so
   this is a projection (not the `kyber-plm.store` backend). Field shapes match
   the PRODUCTION pod (verified, not the lexicon):
     claim    = {:pred <kg/claim/...> :value <string>}
     relation = {:pred <kg/relation/...> :target <entity-id>}
   Predicates land as `kg/claim/<pred>` / `kg/relation/<pred>` quads in the
   named graph `kotobase-kg-v1`; read back via `kg.entity?id=<id>`.

   Pure functions: build payloads here, POST them with live/kg_ingest.mjs (which
   carries the tenant Bearer + x-internal-trust)."
  (:require [kyber-plm.db :as db]
            [kyber-plm.plm :as plm]))

(def ^:private item-claim-attrs
  "PLM item attributes projected as kg claims (attr → claim predicate)."
  [[:plm.item/part-no       "plm.item/part-no"]
   [:plm.item/revision      "plm.item/revision"]
   [:plm.item/make-buy      "plm.item/make-buy"]
   [:plm.item/lifecycle     "plm.item/lifecycle"]
   [:plm.item/uom           "plm.item/uom"]
   [:plm.item/std-unit-cost "plm.item/std-unit-cost"]
   [:plm.item/category      "plm.item/category"]
   [:plm.item/package-ref   "plm.item/package-ref"]])

(defn- claim-str [v]
  (cond (keyword? v) (name v)
        :else        (str v)))

(defn item->kg-entity
  "Project released PLM item `iid` into a kg.ingest entity payload:
   claims from item attributes, relations from its MBOM child edges
   (`plm.bom/child` with the per-edge qty carried on a sibling claim).
   Returns nil for an unknown item."
  [d iid]
  (let [m (db/pull d (into [:plm.item/id] (map first) item-claim-attrs)
                   [:plm.item/id iid])]
    (when (:plm.item/id m)
      {:id     iid
       :type   "plm.item"
       :label_en (or (db/attr d :plm.item/name [:plm.item/id iid]) iid)
       :claims (vec (for [[attr pred] item-claim-attrs
                          :let [v (get m attr)]
                          :when (some? v)]
                      {:pred pred :value (claim-str v)}))
       :relations (vec (for [{:keys [child qty]} (plm/mbom-rows d iid)]
                         {:pred "plm.bom/child" :target child :qty (str qty)}))})))

(defn graph->kg-entities
  "Project every released item in the store into kg.ingest entities — the batch
   payload for `kg.ingest_batch`. Order: parents after children is not required
   (relations reference targets by id, resolved on read)."
  [d]
  (->> (db/q '[:find ?id :where [?e :plm.item/id ?id] [?e :plm.item/lifecycle :released]] d)
       (map first)
       sort
       (keep #(item->kg-entity d %))
       vec))
