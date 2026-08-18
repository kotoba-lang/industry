(ns office
  "P0 reference: organization-first office datom schema + datom<->document converters.

   Runs under babashka / Clojure (.cljc, no platform deps).

   Scope (doc a Phase 0, extended for org-first + W3C DID + access DAG):
   - schema registry for :org/* :vm/* :grant/* :doc/* :block/*
   - bijective converters: document/org/grant  <->  datoms (vector of [e a v])

   NOTE: entity IDs here are caller-supplied LOGICAL ids. In kotoba the real ids are
   content-addressed CIDs minted by commit(); :org/did, :vm/vid carry stable logical keys
   so re-ingest is idempotent. :block/text is encrypted at write time via assertEncrypted
   (signal:v1:) — this reference treats it as a plain string (encryption is out of P0 scope).")

;; ---------------------------------------------------------------------------
;; schema registry  (Datomic-style attribute metadata)
;; ---------------------------------------------------------------------------

(def schema
  {;; --- org / account (account = org node; account = "org of one") ---
   :org/did           {:db/valueType :did     :db/cardinality :one :db/unique :identity}
   :org/kind          {:db/valueType :keyword :db/cardinality :one} ; :org.kind/root|team|account
   :org/display-name  {:db/valueType :string  :db/cardinality :one}
   :org/parent        {:db/valueType :ref     :db/cardinality :one
                       :doc "ownership/control spine (tree) = W3C DID `controller`; root has none"}
   :org/data-graph    {:db/valueType :string  :db/cardinality :one
                       :doc "graph CID where this org's docs live = W3C DID `service` endpoint"}
   :org/created-at    {:db/valueType :long    :db/cardinality :one}

   ;; --- verification method (W3C DID Document key) ---
   :vm/of                    {:db/valueType :ref     :db/cardinality :one}
   :vm/vid                   {:db/valueType :string  :db/cardinality :one :db/unique :identity} ; did:..#key-1
   :vm/type                  {:db/valueType :keyword :db/cardinality :one} ; :Ed25519 ...
   :vm/public-key-multibase  {:db/valueType :string  :db/cardinality :one}
   :vm/rel                   {:db/valueType :keyword :db/cardinality :many
                              :doc "authentication|assertionMethod|capabilityDelegation|capabilityInvocation|keyAgreement"}

   ;; --- membership grant (access DAG; reified capability ~ CACAO) ---
   :grant/org         {:db/valueType :ref     :db/cardinality :one}
   :grant/subject     {:db/valueType :did     :db/cardinality :one}
   :grant/cap         {:db/valueType :keyword :db/cardinality :one} ; :cap/read|:cap/transact|:cap/admin
   :grant/scope       {:db/valueType :string  :db/cardinality :one} ; graph CID  -> CACAO kotoba://graph/{cid}
   :grant/role        {:db/valueType :keyword :db/cardinality :one} ; :role/owner|admin|member|guest
   :grant/issued-at   {:db/valueType :long    :db/cardinality :one}
   :grant/expires-at  {:db/valueType :long    :db/cardinality :one} ; optional

   ;; --- document ---
   :doc/kind          {:db/valueType :keyword :db/cardinality :one}
   :doc/title         {:db/valueType :string  :db/cardinality :one}
   :doc/owner-org     {:db/valueType :ref     :db/cardinality :one}
   :doc/created-at    {:db/valueType :long    :db/cardinality :one}

   ;; --- block (tree under doc) ---
   :block/kind        {:db/valueType :keyword :db/cardinality :one}
   :block/text        {:db/valueType :string  :db/cardinality :one
                       :db/encrypted true :doc "signal:v1: via assertEncrypted at write time"}
   :block/order       {:db/valueType :string  :db/cardinality :one
                       :doc "fractional-index string (insert-friendly, CRDT-migratable)"}
   :block/parent      {:db/valueType :ref     :db/cardinality :one}
   :block/deleted     {:db/valueType :boolean :db/cardinality :one}})

;; ---------------------------------------------------------------------------
;; small EAV helpers
;; ---------------------------------------------------------------------------

(defn v-one  [datoms e a] (some (fn [[de da v]] (when (and (= de e) (= da a)) v)) datoms))
(defn v-many [datoms e a] (set (keep (fn [[de da v]] (when (and (= de e) (= da a)) v)) datoms)))

;; ---------------------------------------------------------------------------
;; document  <->  datoms   (blocks form a tree under the doc)
;; ---------------------------------------------------------------------------

(defn- block->datoms [parent-id {:keys [id kind text order children deleted]}]
  (into (cond-> [[id :block/kind kind]
                 [id :block/parent parent-id]
                 [id :block/order order]]
          (some? text)    (conj [id :block/text text])
          (some? deleted) (conj [id :block/deleted deleted]))
        (mapcat #(block->datoms id %) children)))

(defn doc->datoms [{:keys [id kind title owner-org created-at blocks]}]
  (into [[id :doc/kind kind]
         [id :doc/title title]
         [id :doc/owner-org owner-org]
         [id :doc/created-at created-at]]
        (mapcat #(block->datoms id %) blocks)))

(defn datoms->doc [datoms]
  (let [doc-id (some (fn [[e a]] (when (= a :doc/kind) e)) datoms)
        children-of (fn children-of [pid]
                      (->> datoms
                           (keep (fn [[e a v]] (when (and (= a :block/parent) (= v pid)) e)))
                           distinct
                           (sort-by #(v-one datoms % :block/order))
                           (mapv (fn [e]
                                   (let [kids (children-of e)
                                         txt  (v-one datoms e :block/text)
                                         del  (v-one datoms e :block/deleted)]
                                     (cond-> {:id e
                                              :kind  (v-one datoms e :block/kind)
                                              :order (v-one datoms e :block/order)}
                                       (some? txt)  (assoc :text txt)
                                       (some? del)  (assoc :deleted del)
                                       (seq kids)   (assoc :children kids)))))))]
    {:id         doc-id
     :kind       (v-one datoms doc-id :doc/kind)
     :title      (v-one datoms doc-id :doc/title)
     :owner-org  (v-one datoms doc-id :doc/owner-org)
     :created-at (v-one datoms doc-id :doc/created-at)
     :blocks     (children-of doc-id)}))

;; ---------------------------------------------------------------------------
;; org (+ DID verification methods)  <->  datoms
;; ---------------------------------------------------------------------------

(defn org->datoms [{:keys [id did kind display-name parent data-graph created-at vms]}]
  (into (cond-> [[id :org/did did]
                 [id :org/kind kind]
                 [id :org/display-name display-name]
                 [id :org/data-graph data-graph]
                 [id :org/created-at created-at]]
          (some? parent) (conj [id :org/parent parent]))
        (mapcat (fn [{:keys [vid type pk rels]}]
                  (into [[vid :vm/of id]
                         [vid :vm/vid vid]
                         [vid :vm/type type]
                         [vid :vm/public-key-multibase pk]]
                        (map (fn [r] [vid :vm/rel r]) rels)))
                vms)))

(defn datoms->org [datoms org-id]
  (let [vm-ids (->> datoms
                    (keep (fn [[e a v]] (when (and (= a :vm/of) (= v org-id)) e)))
                    distinct)
        vms (mapv (fn [vid] {:vid  vid
                             :type (v-one datoms vid :vm/type)
                             :pk   (v-one datoms vid :vm/public-key-multibase)
                             :rels (v-many datoms vid :vm/rel)})
                  vm-ids)]
    (cond-> {:id           org-id
             :did          (v-one datoms org-id :org/did)
             :kind         (v-one datoms org-id :org/kind)
             :display-name (v-one datoms org-id :org/display-name)
             :data-graph   (v-one datoms org-id :org/data-graph)
             :created-at   (v-one datoms org-id :org/created-at)
             :vms          vms}
      (v-one datoms org-id :org/parent) (assoc :parent (v-one datoms org-id :org/parent)))))

;; ---------------------------------------------------------------------------
;; membership grant (access DAG)  <->  datoms
;; ---------------------------------------------------------------------------

(defn grant->datoms [{:keys [id org subject cap scope role issued-at expires-at]}]
  (cond-> [[id :grant/org org]
           [id :grant/subject subject]
           [id :grant/cap cap]
           [id :grant/scope scope]
           [id :grant/role role]
           [id :grant/issued-at issued-at]]
    (some? expires-at) (conj [id :grant/expires-at expires-at])))

(defn datoms->grant [datoms grant-id]
  (cond-> {:id        grant-id
           :org       (v-one datoms grant-id :grant/org)
           :subject   (v-one datoms grant-id :grant/subject)
           :cap       (v-one datoms grant-id :grant/cap)
           :scope     (v-one datoms grant-id :grant/scope)
           :role      (v-one datoms grant-id :grant/role)
           :issued-at (v-one datoms grant-id :grant/issued-at)}
    (v-one datoms grant-id :grant/expires-at) (assoc :expires-at (v-one datoms grant-id :grant/expires-at))))
