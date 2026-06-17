(ns kyber-plm.plm
  "PLM core domain — pure constructors (tx maps) + queries over the item /
   BOM-edge / change-order graph. No ERP side effects live here; the ERP
   thread (kyber-plm.thread) reads released PLM state and derives them."
  (:require [kyber-plm.db :as db]))

;; ───────────────────────────── constructors (tx maps) ──────────────────────

(defn item-id [part-no rev] (str part-no "@" rev))

(defn item
  "Build an item-master tx map. Defaults lifecycle :draft, uom :ea.
   `:make-buy` is :make | :buy | :phantom; :buy items carry :std-unit-cost."
  [{:keys [part-no rev name uom make-buy category std-unit-cost package-ref]
    :or   {rev "A" uom :ea make-buy :make}}]
  (cond-> {:plm.item/id        (item-id part-no rev)
           :plm.item/part-no   part-no
           :plm.item/revision  rev
           :plm.item/name      (or name part-no)
           :plm.item/uom       uom
           :plm.item/make-buy  make-buy
           :plm.item/lifecycle :draft}
    category      (assoc :plm.item/category category)
    std-unit-cost (assoc :plm.item/std-unit-cost (bigdec std-unit-cost))
    package-ref   (assoc :plm.item/package-ref package-ref)))

(defn bom-edge
  "Build a BOM-edge tx map linking parent→child (both given as \"<part>@<rev>\"
   item ids). `:view` is :ebom | :mbom; cost roll-up & consumption use :mbom."
  [{:keys [parent child qty uom find-no ref-designator view]
    :or   {qty 1 uom :ea find-no 0 view :mbom}}]
  (cond-> {:plm.bom/id     (str parent "|" find-no "|" child)
           :plm.bom/parent [:plm.item/id parent]
           :plm.bom/child  [:plm.item/id child]
           :plm.bom/qty    (bigdec qty)
           :plm.bom/uom    uom
           :plm.bom/find-no (long find-no)
           :plm.bom/view   view}
    ref-designator (assoc :plm.bom/ref-designator ref-designator)))

(defn change-order
  "Build an ECO tx map. `:affected` is a seq of affected item ids; on release the
   thread applies `:new-unit-cost` to those :buy items and revalues their parents."
  [{:keys [id kind affected disposition new-unit-cost]
    :or   {kind :eco disposition :use-as-is}}]
  (cond-> {:plm.eco/id          id
           :plm.eco/kind        kind
           :plm.eco/state       :draft
           :plm.eco/disposition disposition
           :plm.eco/affected    (mapv (fn [iid] [:plm.item/id iid]) affected)}
    new-unit-cost (assoc :plm.eco/new-unit-cost (bigdec new-unit-cost))))

;; ───────────────────────────── queries ─────────────────────────────────────

(defn lifecycle [db iid] (db/attr db :plm.item/lifecycle [:plm.item/id iid]))
(defn make-buy  [db iid] (db/attr db :plm.item/make-buy  [:plm.item/id iid]))
(defn unit-cost [db iid] (db/attr db :plm.item/std-unit-cost [:plm.item/id iid]))
(defn released? [db iid] (= :released (lifecycle db iid)))

(defn mbom-children
  "Released MBOM children of `iid` as [{:child <id> :qty <bigdec>} ...]."
  [db iid]
  (->> (db/q '[:find ?cid ?qty
               :in $ ?pid
               :where
               [?p :plm.item/id ?pid]
               [?e :plm.bom/parent ?p]
               [?e :plm.bom/view :mbom]
               [?e :plm.bom/child ?c]
               [?c :plm.item/id ?cid]
               [?e :plm.bom/qty ?qty]]
             db iid)
       (map (fn [[cid qty]] {:child cid :qty qty}))
       (sort-by :child)
       vec))

(defn parents-using
  "Item ids of MBOM parents that directly consume `iid` (where-used, 1 level)."
  [db iid]
  (->> (db/q '[:find ?pid
               :in $ ?cid
               :where
               [?c :plm.item/id ?cid]
               [?e :plm.bom/child ?c]
               [?e :plm.bom/view :mbom]
               [?e :plm.bom/parent ?p]
               [?p :plm.item/id ?pid]]
             db iid)
       (map first)
       sort
       vec))
