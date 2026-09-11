(ns isekai.games.boutique-stack.economy
  "Money, unlocks, upgrades, hiring.

  Every spend goes through `pay`, so there is exactly one place that can take
  money and exactly one place that can fail to. A tycoon game whose costs are
  deducted at each call site eventually grows one that forgets to check the
  balance first, and that bug is invisible until someone reaches a negative
  number the UI cannot show."
  (:require [isekai.games.boutique-stack.world :as world]))

(def upgrade-kinds
  {:carry {:upgrade/name "Armful"
           :upgrade/what "How many garments fit in one carry"
           :upgrade/base 4 :upgrade/step 2 :upgrade/max-level 6
           :upgrade/cost-base 150 :upgrade/cost-growth 2}
   :speed {:upgrade/name "Hustle"
           :upgrade/what "Walking speed, in units per tick"
           :upgrade/base 16 :upgrade/step 4 :upgrade/max-level 6
           :upgrade/cost-base 200 :upgrade/cost-growth 2}
   :service {:upgrade/name "Till"
             :upgrade/what "Ticks to serve one customer (lower is better)"
             :upgrade/base 24 :upgrade/step -3 :upgrade/max-level 5
             :upgrade/cost-base 260 :upgrade/cost-growth 2}})

(def staff-roles
  {:restocker {:staff/name "Stocker" :staff/hire-cost 500 :staff/max 3}
   :cashier {:staff/name "Cashier" :staff/hire-cost 750 :staff/max 2}})

(defn upgrade-value
  "The effective value of an upgrade at `level`. Level 1 is the starting
  value; a shop always has every upgrade at least at level 1."
  [kind level]
  (let [{:keys [:upgrade/base :upgrade/step :upgrade/max-level]} (upgrade-kinds kind)
        level (max 1 (min level max-level))]
    (+ base (* step (dec level)))))

(defn upgrade-cost
  "Cost to go from `level` to `level + 1`, or nil at the cap.

  Geometric, so each level is a decision rather than a formality: doubling
  means the fourth level costs as much as the first three together, which is
  what pushes a player to open a department instead."
  [kind level]
  (let [{:keys [:upgrade/cost-base :upgrade/cost-growth :upgrade/max-level]}
        (upgrade-kinds kind)]
    (when (< level max-level)
      (* cost-base (reduce * 1 (repeat (dec level) cost-growth))))))

(defn maxed? [kind level] (nil? (upgrade-cost kind level)))

;; ---------------------------------------------------------------------- money

(defn pay
  "Deducts `amount` if it is affordable. Returns the state with the money
  gone, or nil — the caller decides what a refusal means, and cannot
  accidentally proceed on one."
  [state amount]
  (when (and (number? amount) (>= (:money state) amount))
    (update state :money - amount)))

(defn earn [state amount] (update state :money + amount))

;; -------------------------------------------------------------------- unlocks

(defn zone-requirements-met?
  [world unlocked zone-id]
  (every? #(world/zone-unlocked? world unlocked %)
          (:zone/requires (world/zone world zone-id) #{})))

(defn unlockable
  "Zones the player could open right now, cheapest first.

  Ordering is by cost then id, so the HUD's \"next unlock\" is stable and two
  runtimes agree on it."
  [state world]
  (->> (:zone-order world)
       (map #(world/zone world %))
       (remove #(world/zone-unlocked? world (:unlocked state) (:zone/id %)))
       (filter #(zone-requirements-met? world (:unlocked state) (:zone/id %)))
       (sort-by (juxt :zone/cost :zone/id))
       vec))

(defn can-unlock?
  [state world zone-id]
  (boolean
   (and (not (world/zone-unlocked? world (:unlocked state) zone-id))
        (zone-requirements-met? world (:unlocked state) zone-id)
        (>= (:money state) (:zone/cost (world/zone world zone-id) 0)))))

(defn unlock
  "Opens a zone. Returns the state unchanged when it is not affordable or its
  requirements are unmet, so callers may treat this as a filter.

  The requirement check is what makes the shoe department the end of a chain
  rather than a thing a lucky player buys first."
  [state world zone-id]
  (if-not (can-unlock? state world zone-id)
    state
    (-> state
        (pay (:zone/cost (world/zone world zone-id) 0))
        (update :unlocked conj zone-id)
        (update :log conj {:event :unlocked :zone zone-id :tick (:tick state)}))))

;; ------------------------------------------------------------------- upgrades

(defn buy-upgrade
  [state kind]
  (let [level (get-in state [:upgrades kind] 1)
        cost (upgrade-cost kind level)]
    (if-let [paid (and cost (pay state cost))]
      (-> paid
          (assoc-in [:upgrades kind] (inc level))
          (update :log conj {:event :upgraded :kind kind :level (inc level)
                             :tick (:tick state)}))
      state)))

(defn hire
  "Adds one staff member of `role`, if affordable and under the cap.

  New staff start at the entrance, like everybody else — spawning them at
  their post would let a player buy a cashier mid-rush and have the queue
  clear instantly, which reads as a bug even when it is generous."
  [state world role]
  (let [{:keys [:staff/hire-cost :staff/max]} (staff-roles role)
        current (count (filter #(= role (:role %)) (:staff state)))
        entrance (first (world/fixtures-of-kind world :entrance))]
    (if (or (>= current max) (nil? entrance))
      state
      (if-let [paid (pay state hire-cost)]
        (-> paid
            (update :staff conj {:id (keyword (str (name role) "-" (inc current)))
                                 :role role
                                 :pos (:fixture/pos entrance)
                                 :carry nil
                                 :goal nil})
            (update :log conj {:event :hired :role role :tick (:tick state)}))
        state))))

(defn carry-capacity [state] (upgrade-value :carry (get-in state [:upgrades :carry] 1)))
(defn walk-speed [state] (upgrade-value :speed (get-in state [:upgrades :speed] 1)))
(defn service-ticks [state] (upgrade-value :service (get-in state [:upgrades :service] 1)))
