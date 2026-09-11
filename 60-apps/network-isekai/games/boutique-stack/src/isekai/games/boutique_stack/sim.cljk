(ns isekai.games.boutique-stack.sim
  "The shop simulation: one pure function of state and one tick.

      (sim/tick state world intent) -> state'

  Fixed timestep, integer positions, explicit RNG state. No atoms, no wall
  clock, no `Math/random`. The same tick function runs the browser frame loop,
  the packaged mobile app and the headless balance runner, and all three
  produce the same numbers from the same seed.

  ## Tick order

  Fixed, and worth stating because everything about fairness depends on it:

  1. crates receive their delivery
  2. staff act, in hire order
  3. the player acts
  4. customers act, in spawn order
  5. checkouts serve
  6. new customers spawn

  Serving *after* the customers move means a customer who reaches the queue
  this tick can be served this tick, which is what stops a busy till from
  feeling laggy. Spawning last means a customer never acts on the tick it
  appears.

  ## What is not modelled

  No collision and no pathfinding — see `world`. No cost of goods: the crate
  is free and the real constraint is the player's time, so a purchase price
  would be a number that never changes a decision."
  (:require [isekai.games.boutique-stack.catalog :as catalog]
            [isekai.games.boutique-stack.economy :as econ]
            [isekai.games.boutique-stack.world :as world]
            [isekai.games.common.rng :as rng]))

(def queue-spacing 70)
(def default-patience 600)
(def restock-per-tick 1)

;; ------------------------------------------------------------------- creation

(defn new-state
  [world]
  (let [shop (:shop world)
        entrance (first (world/fixtures-of-kind world :entrance))]
    {:tick 0
     :seed (rng/seed (:shop/seed shop 1))
     :money (:shop/starting-money shop 0)
     :unlocked (into #{} (comp (filter #(zero? (:zone/cost % 0))) (map :zone/id))
                     (:shop/zones shop))
     :upgrades {:carry 1 :speed 1 :service 1}
     :stock (into {} (for [id (:fixture-order world)
                           :let [f (world/fixture world id)]
                           :when (contains? #{:rack :crate} (:fixture/kind f))]
                       ;; Crates arrive full, racks arrive empty. The first
                       ;; thing a player does is carry the shop into existence.
                       [id (if (= :crate (:fixture/kind f)) (:fixture/capacity f 0) 0)]))
     :crate-timers {}
     :player {:pos (:fixture/pos entrance) :carry nil :goal nil}
     :staff []
     :customers []
     :next-id 1
     :spawn-timer 0
     :service {}
     :queues {}
     :stats {:served 0 :lost 0 :revenue 0 :items 0 :walkouts-empty 0 :walkouts-queue 0}
     :log []}))

(defn start
  "Convenience: shop EDN in, `[world state]` out."
  [shop]
  (let [w (world/build shop)]
    [w (new-state w)]))

;; -------------------------------------------------------------------- helpers

(defn- log [state event] (update state :log conj (assoc event :tick (:tick state))))

(defn- stock-of [state id] (get-in state [:stock id] 0))

(defn- space-in [state world id]
  (max 0 (- (world/capacity-of world id) (stock-of state id))))

(defn- open-racks [state world] (world/open-fixtures world (:unlocked state) :rack))

(defn- open-crates [state world] (world/open-fixtures world (:unlocked state) :crate))

(defn- checkouts [state world] (world/open-fixtures world (:unlocked state) :checkout))

(defn- queue-slot
  "Where the n-th customer in a checkout's line stands."
  [checkout n]
  (let [[x y] (:fixture/pos checkout)]
    [x (+ y (* queue-spacing (inc n)))]))

(defn- nearest
  "The fixture nearest `pos` among `candidates`, ties broken by id so the
  choice never depends on map ordering."
  [pos candidates]
  (first (sort-by (fn [f] [(world/dist2 pos (:fixture/pos f)) (:fixture/id f)])
                  candidates)))

;; ---------------------------------------------------------------- restocking

(defn- item-deficit
  "How many of `item` the open racks are short."
  [state world item]
  (reduce + 0 (for [r (open-racks state world)
                    :when (= item (:fixture/item r))]
                (space-in state world (:fixture/id r)))))

(defn- restock-goal
  "Where a restocking agent should head next, or nil when there is nothing
  useful to do.

  Carrying: the nearest rack that wants what is in hand. Empty-handed: the
  crate for whichever item the shop is shortest of, preferring an item the
  shop can actually put somewhere."
  [state world agent]
  (if-let [carried (:item (:carry agent))]
    (let [wanting (filter #(and (= carried (:fixture/item %))
                                (pos? (space-in state world (:fixture/id %))))
                          (open-racks state world))]
      (:fixture/id (nearest (:pos agent) wanting)))
    (let [useful (for [c (open-crates state world)
                       :let [item (:fixture/item c)
                             deficit (item-deficit state world item)]
                       :when (and (pos? deficit) (pos? (stock-of state (:fixture/id c))))]
                   [deficit (:fixture/id c)])]
      ;; Biggest shortfall first; id breaks the tie.
      (second (first (sort-by (fn [[d id]] [(- d) id]) useful))))))

(defn- transfer-in
  "Agent picks one unit out of a crate."
  [state world agent-path crate-id]
  (let [agent (get-in state agent-path)
        item (:fixture/item (world/fixture world crate-id))
        carry (:carry agent)
        cap (econ/carry-capacity state)]
    (if (and (pos? (stock-of state crate-id))
             (or (nil? carry) (= item (:item carry)))
             (< (:count carry 0) cap))
      (-> state
          (update-in [:stock crate-id] - restock-per-tick)
          (assoc-in (conj agent-path :carry)
                    {:item item :count (+ (:count carry 0) restock-per-tick)}))
      state)))

(defn- transfer-out
  "Agent puts one unit onto a rack."
  [state world agent-path rack-id]
  (let [agent (get-in state agent-path)
        carry (:carry agent)]
    (if (and carry
             (= (:item carry) (:fixture/item (world/fixture world rack-id)))
             (pos? (space-in state world rack-id)))
      (let [left (- (:count carry) restock-per-tick)]
        (-> state
            (update-in [:stock rack-id] (fnil + 0) restock-per-tick)
            (assoc-in (conj agent-path :carry)
                      (when (pos? left) {:item (:item carry) :count left}))))
      state)))

(defn- work-fixture
  "Runs whatever a restocking agent does on arrival at its goal."
  [state world agent-path goal-id]
  (case (:fixture/kind (world/fixture world goal-id))
    :crate (transfer-in state world agent-path goal-id)
    :rack (transfer-out state world agent-path goal-id)
    state))

(defn- move-agent
  "Walks an agent one tick toward `goal-id`, working it once in range."
  [state world agent-path goal-id]
  (if (nil? goal-id)
    (assoc-in state (conj agent-path :goal) nil)
    (let [agent (get-in state agent-path)
          target (:fixture/pos (world/fixture world goal-id))
          state (assoc-in state (conj agent-path :goal) goal-id)]
      (if (world/near? (:pos agent) target world/interaction-radius)
        (work-fixture state world agent-path goal-id)
        (assoc-in state (conj agent-path :pos)
                  (world/step-toward (:pos agent) target (econ/walk-speed state)))))))

;; ------------------------------------------------------------------- cashiers

(defn- queue-of [state checkout-id] (get-in state [:queues checkout-id] []))

(defn- busiest-checkout
  "The till that most needs someone behind it — longest line first."
  [state world]
  (->> (checkouts state world)
       (sort-by (fn [c] [(- (count (queue-of state (:fixture/id c)))) (:fixture/id c)]))
       first))

(defn- shortest-checkout
  "The till a shopper joins — shortest line first, id breaking the tie."
  [state world]
  (->> (checkouts state world)
       (sort-by (fn [c] [(count (queue-of state (:fixture/id c))) (:fixture/id c)]))
       first))

(defn- cashier-at?
  "Is anyone standing at this till?"
  [state world checkout-id]
  (let [pos (:fixture/pos (world/fixture world checkout-id))
        manning (fn [a] (world/near? (:pos a) pos world/interaction-radius))]
    (boolean (or (some #(and (= :cashier (:role %)) (manning %)) (:staff state))
                 (and (= checkout-id (:goal (:player state))) (manning (:player state)))))))

(defn- cashier-goal
  [state world]
  (:fixture/id (busiest-checkout state world)))

;; ------------------------------------------------------------------- customers

(defn- customer-index
  [state id]
  (first (keep-indexed (fn [i c] (when (= id (:id c)) i)) (:customers state))))

(defn- being-served?
  [state id]
  (boolean (some #(= id (:cust %)) (vals (:service state)))))

(defn- leave!
  [state id reason]
  (if-let [i (customer-index state id)]
    (-> state
        (assoc-in [:customers i :state] :leaving)
        (assoc-in [:customers i :reason] reason)
        ;; Drop any till that thinks it is serving this customer. Without
        ;; this the till completes a sale for somebody who already walked
        ;; out — and once they have been reaped, for nobody at all.
        (update :service (fn [svc] (into {} (remove (fn [[_ v]] (= id (:cust v))) svc))))
        (update-in [:stats (if (= :queue reason) :walkouts-queue :walkouts-empty)] inc)
        (update-in [:stats :lost] inc)
        (log {:event :walkout :customer id :reason reason}))
    state))

(defn- take-from-rack
  [state world id rack-id item]
  (let [i (customer-index state id)]
    (-> state
        (update-in [:stock rack-id] dec)
        (update-in [:customers i :basket] conj item)
        (update-in [:customers i :want] rest)
        (assoc-in [:customers i :patience] default-patience)
        (assoc-in [:customers i :goal] nil))))

(defn- join-queue
  [state world id checkout-id]
  (let [i (customer-index state id)]
    (-> state
        (update-in [:queues checkout-id] (fnil conj []) id)
        (assoc-in [:customers i :state] :waiting)
        (assoc-in [:customers i :checkout] checkout-id))))

(defn- act-customer
  [state world id]
  (let [i (customer-index state id)
        c (get-in state [:customers i])
        speed (econ/walk-speed state)]
    (case (:state c)
      :browsing
      (if (empty? (:want c))
        (assoc-in state [:customers i :state] :queuing)
        (let [item (first (:want c))
              stocked (filter #(and (= item (:fixture/item %))
                                    (pos? (stock-of state (:fixture/id %))))
                              (open-racks state world))]
          (if-let [rack (nearest (:pos c) stocked)]
            (let [target (:fixture/pos rack)]
              (if (world/near? (:pos c) target world/interaction-radius)
                (take-from-rack state world id (:fixture/id rack) item)
                (-> state
                    (assoc-in [:customers i :goal] (:fixture/id rack))
                    (assoc-in [:customers i :pos] (world/step-toward (:pos c) target speed)))))
            ;; Nothing on the shelf. Wait, and give up when patience runs out.
            (let [patience (dec (:patience c))]
              (if (pos? patience)
                (assoc-in state [:customers i :patience] patience)
                (if (seq (:basket c))
                  ;; Already holding something — go pay for that rather than
                  ;; walking out, which is what a real shopper does.
                  (assoc-in state [:customers i :state] :queuing)
                  (leave! state id :empty)))))))

      :queuing
      (if-let [checkout (shortest-checkout state world)]
        (let [target (queue-slot checkout (count (queue-of state (:fixture/id checkout))))]
          (if (world/near? (:pos c) target world/interaction-radius)
            (join-queue state world id (:fixture/id checkout))
            (-> state
                (assoc-in [:customers i :goal] (:fixture/id checkout))
                (assoc-in [:customers i :pos] (world/step-toward (:pos c) target speed)))))
        state)

      :waiting
      ;; Patience does not run out while the till is actually ringing you up.
      ;; Letting it would make a customer walk out of a transaction that is
      ;; about to complete, which reads as a bug and is one.
      (if (being-served? state id)
        state
        (let [patience (dec (:patience c))]
          (if (pos? patience)
            (assoc-in state [:customers i :patience] patience)
            (-> state
                (update-in [:queues (:checkout c)] (fn [q] (vec (remove #{id} q))))
                (leave! id :queue)))))

      :leaving
      (let [exit (or (first (world/fixtures-of-kind world :exit))
                     (first (world/fixtures-of-kind world :entrance)))
            target (:fixture/pos exit)]
        (if (world/near? (:pos c) target world/interaction-radius)
          (assoc-in state [:customers i :state] :gone)
          (assoc-in state [:customers i :pos] (world/step-toward (:pos c) target speed))))

      state)))

;; ---------------------------------------------------------------------- tills

(defn- complete-sale
  [state world checkout-id cust-id]
  (if-let [i (customer-index state cust-id)]
    (let [basket (get-in state [:customers i :basket])
          total (reduce + 0 (map catalog/price basket))]
      (-> state
          (update-in [:queues checkout-id] (fn [q] (vec (remove #{cust-id} q))))
          (update :service dissoc checkout-id)
          (assoc-in [:customers i :state] :leaving)
          (assoc-in [:customers i :reason] :served)
          (econ/earn total)
          (update-in [:stats :served] inc)
          (update-in [:stats :revenue] + total)
          (update-in [:stats :items] + (count basket))
          (log {:event :sale :customer cust-id :total total :items (vec basket)})))
    ;; The customer is gone. Free the till rather than crediting a sale to
    ;; nobody — and never index a vector with the nil this used to produce.
    (-> state
        (update-in [:queues checkout-id] (fn [q] (vec (remove #{cust-id} q))))
        (update :service dissoc checkout-id))))

(defn- serve-tills
  [state world]
  (reduce
   (fn [st c]
     (let [id (:fixture/id c)
           active (get-in st [:service id])]
       (cond
         active
         (let [left (dec (:ticks-left active))]
           (if (pos? left)
             (assoc-in st [:service id :ticks-left] left)
             (complete-sale st world id (:cust active))))

         (and (seq (queue-of st id)) (cashier-at? st world id))
         (assoc-in st [:service id] {:cust (first (queue-of st id))
                                     :ticks-left (econ/service-ticks st)})

         :else st)))
   state
   (checkouts state world)))

;; -------------------------------------------------------------------- spawning

(defn- spawn-interval
  "Traffic grows as the shop opens departments. A new department that did not
  bring shoppers with it would make unlocking feel like a cost."
  [state world]
  (let [base (:shop/spawn-interval (:shop world) 60)
        depts (count (world/open-departments world (:unlocked state)))]
    (max 10 (quot base (max 1 depts)))))

(defn- roll-wants
  "One to three items, drawn from what the shop currently sells."
  [state world]
  (let [sellable (world/sellable-items world (:unlocked state))]
    (if (empty? sellable)
      [(:seed state) []]
      (let [[n seed] (rng/draw (:seed state) 3)]
        (loop [k (inc n), seed seed, acc []]
          (if (zero? k)
            [seed acc]
            (let [[item seed'] (rng/pick seed sellable)]
              (recur (dec k) seed' (conj acc item)))))))))

(defn- spawn-customer
  [state world]
  (let [entrance (first (world/fixtures-of-kind world :entrance))
        [seed wants] (roll-wants state world)
        id (:next-id state)]
    (if (empty? wants)
      (assoc state :seed seed)
      (-> state
          (assoc :seed seed)
          (update :customers conj {:id id
                                   :pos (:fixture/pos entrance)
                                   :state :browsing
                                   :want (vec wants)
                                   :basket []
                                   :goal nil
                                   :checkout nil
                                   :patience default-patience})
          (update :next-id inc)))))

(defn- spawning
  [state world]
  (let [limit (:shop/max-customers (:shop world) 24)
        live (count (remove #(= :gone (:state %)) (:customers state)))]
    (if (<= (dec (:spawn-timer state)) 0)
      (cond-> (assoc state :spawn-timer (spawn-interval state world))
        (< live limit) (spawn-customer world))
      (update state :spawn-timer dec))))

;; --------------------------------------------------------------------- crates

(defn- deliver
  "Crates refill on a timer — the shop's supply line."
  [state world]
  (reduce
   (fn [st c]
     (let [id (:fixture/id c)
           every (:fixture/refill-ticks c 40)
           t (inc (get-in st [:crate-timers id] 0))]
       (if (and (>= t every) (pos? (space-in st world id)))
         (-> st (assoc-in [:crate-timers id] 0) (update-in [:stock id] inc))
         (assoc-in st [:crate-timers id] t))))
   state
   (open-crates state world)))

;; ----------------------------------------------------------------------- tick

(defn- player-goal
  "What the player's autopilot does next.

  Mans a till whenever one has a line and nobody behind it, otherwise
  restocks. This is a *policy for balance testing*, not a claim about how a
  human plays — but it is the policy a competent human converges on, which is
  what makes the numbers it produces worth reading."
  [state world]
  (let [needy (first (filter #(and (seq (queue-of state (:fixture/id %)))
                                   (not (cashier-at? state world (:fixture/id %))))
                             (checkouts state world)))]
    (or (:fixture/id needy)
        (restock-goal state world (:player state)))))

(defn- act-staff
  [state world]
  (reduce
   (fn [st i]
     (let [member (get-in st [:staff i])
           path [:staff i]]
       (case (:role member)
         :restocker (move-agent st world path (restock-goal st world member))
         :cashier (move-agent st world path (cashier-goal st world))
         st)))
   state
   (range (count (:staff state)))))

(defn- act-player
  [state world intent]
  (if-let [[dx dy] (:move intent)]
    ;; Direct control. The player still works whatever they are standing next
    ;; to, so a joystick user never has to press a second button.
    (let [speed (econ/walk-speed state)
          [px py] (get-in state [:player :pos])
          pos' [(+ px (* dx speed)) (+ py (* dy speed))]
          state (assoc-in state [:player :pos] pos')
          here (first (filter #(world/near? pos' (:fixture/pos %) world/interaction-radius)
                              (concat (open-crates state world) (open-racks state world))))]
      (if here
        (-> state
            (assoc-in [:player :goal] (:fixture/id here))
            (work-fixture world [:player] (:fixture/id here)))
        (assoc-in state [:player :goal] nil)))
    (move-agent state world [:player] (player-goal state world))))

(defn- reap
  "Drops customers who have walked out of the door."
  [state]
  (update state :customers (fn [cs] (vec (remove #(= :gone (:state %)) cs)))))

(defn tick
  "Advances the shop one step.

  `intent` is `{:move [dx dy]}` for direct control, or `nil` to let the
  player's autopilot decide."
  ([state world] (tick state world nil))
  ([state world intent]
   (let [ids (mapv :id (:customers state))]
     (-> state
         (update :tick inc)
         (assoc :log [])
         (deliver world)
         (act-staff world)
         (act-player world intent)
         (as-> st (reduce #(act-customer %1 world %2) st ids))
         (serve-tills world)
         (spawning world)
         reap))))

(defn run
  "Runs `n` ticks. `policy` is called with `[state world]` between ticks and
  may return a modified state — that is where a manager spends money."
  ([state world n] (run state world n nil))
  ([state world n policy]
   (loop [st state, k 0]
     (if (>= k n)
       st
       (let [st (tick st world)]
         (recur (if policy (policy st world) st) (inc k)))))))

;; ------------------------------------------------------------------- readouts

(defn hud
  [state world]
  {:money (:money state)
   :tick (:tick state)
   :carry (get-in state [:player :carry])
   :carry-capacity (econ/carry-capacity state)
   :departments (world/open-departments world (:unlocked state))
   :customers (count (:customers state))
   :queued (reduce + 0 (map count (vals (:queues state))))
   :stats (:stats state)})

(defn shelf-report
  "Per-rack fill, for the balance runner and for the MAX badge over a full
  rack. Empty racks are the thing that loses sales, and they are invisible in
  a revenue total."
  [state world]
  (mapv (fn [r]
          (let [id (:fixture/id r)]
            {:fixture id
             :item (:fixture/item r)
             :stock (stock-of state id)
             :capacity (world/capacity-of world id)
             :full? (= (stock-of state id) (world/capacity-of world id))
             :empty? (zero? (stock-of state id))}))
        (open-racks state world)))

(def ^:private digest-modulus 1000000007)

(defn- char-code [ch]
  #?(:clj (int ^Character ch) :cljs (.charCodeAt ^string ch 0)))

(defn digest
  "Replay fingerprint. Same shop, same seed, same tick count, same number —
  on every runtime. Deliberately not `clojure.core/hash`, which is free to
  differ between Clojure and ClojureScript."
  [state]
  (reduce (fn [h s]
            (reduce (fn [acc ch] (mod (+ (* acc 31) (char-code ch)) digest-modulus))
                    h
                    (seq (str s))))
          11
          [(:tick state) (:money state)
           (pr-str (into (sorted-map) (:stock state)))
           (pr-str (into (sorted-map) (:stats state)))
           (pr-str (sort (:unlocked state)))
           (count (:customers state))]))
