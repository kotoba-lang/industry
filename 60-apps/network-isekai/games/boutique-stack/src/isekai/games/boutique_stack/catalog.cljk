(ns isekai.games.boutique-stack.catalog
  "What the shop sells.

  Departments are the unit of unlocking, and their order is the progression:
  a player opens tops, then outerwear, and shoes come last. Shoes are not a
  separate shop — they are the far end of the same clothing store, which is
  why `:dept/requires` chains rather than each department standing alone."
  (:require [clojure.string :as str]))

(def departments
  [{:dept/id :tops
    :dept/name "Tops"
    :dept/requires #{}
    :dept/items [:tee :hoodie]}
   {:dept/id :outerwear
    :dept/name "Outerwear"
    :dept/requires #{:tops}
    :dept/items [:jacket :coat]}
   {:dept/id :shoes
    :dept/name "Shoes"
    :dept/requires #{:tops :outerwear}
    :dept/items [:sneaker :boot]}])

(def items
  "Price is what a customer pays at the till. Restock cost is not modelled:
  in this genre the crate is free and the constraint is the player's time, so
  a cost-of-goods line would be a number that never changes any decision."
  {:tee {:item/name "Tee" :item/dept :tops :item/price 14}
   :hoodie {:item/name "Hoodie" :item/dept :tops :item/price 32}
   :jacket {:item/name "Jacket" :item/dept :outerwear :item/price 58}
   :coat {:item/name "Coat" :item/dept :outerwear :item/price 96}
   :sneaker {:item/name "Sneakers" :item/dept :shoes :item/price 72}
   :boot {:item/name "Boots" :item/dept :shoes :item/price 118}})

(def dept-by-id (into {} (map (juxt :dept/id identity) departments)))

(defn price [item] (:item/price (get items item) 0))

(defn dept-of [item] (:item/dept (get items item)))

(defn items-of [dept] (:dept/items (dept-by-id dept) []))

(defn dept-order
  "Index of a department in the progression. Used wherever a deterministic
  ordering over departments is needed."
  [dept]
  (or (first (keep-indexed (fn [i d] (when (= dept (:dept/id d)) i)) departments))
      99))

(defn unlock-chain
  "Departments in the order they may be opened, each with what it needs
  first. This is the shape the HUD's unlock list reads."
  []
  (mapv (fn [d] (select-keys d [:dept/id :dept/name :dept/requires])) departments))

(defn describe [item] (:item/name (get items item) (str/upper-case (name item))))
