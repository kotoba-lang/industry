(ns isekai.games.boutique-stack.manager
  "A spending policy, used the way `solver` is used in Kingdom Cascade: to
  find out whether the shop the designer built can actually be played to the
  end, and how long that takes.

  It is not an idle-mode autoplayer and should not be shipped as one. What it
  is good for is answering, deterministically and in seconds, the questions a
  spreadsheet cannot: *does a competent player ever reach the shoe
  department, and if so at what minute?*

  The order of preference is the one a competent player converges on:

  1. **Open a department** when affordable. New stock is the only thing that
     raises the ceiling; everything else raises throughput toward a ceiling
     that is already there.
  2. **Hire** when the shop is losing customers to a queue — the loss the
     player cannot outrun by walking faster.
  3. **Upgrade** carry, then speed, then till, keeping a cash reserve for the
     next department so a shop cannot upgrade itself out of progression."
  (:require [isekai.games.boutique-stack.economy :as econ]
            [isekai.games.boutique-stack.world :as world]))

(def upgrade-order [:carry :speed :service])

(defn- next-department-cost
  [state world]
  (:zone/cost (first (econ/unlockable state world))))

(def ^:private loss-threshold
  "Walkouts of one kind before that kind is worth spending on. Low, because
  the first few are the tutorial and the rest are a trend."
  3)

(defn bottlenecks
  "Ways the shop is leaking customers, worst first.

  Two different losses with two different cures, and collapsing them into one
  number is how a shop gets a second cashier while its shelves stay bare.
  `:shelves` means there was nothing to buy; `:queue` means the line was too
  slow.

  A *list* rather than a single winner, because the dominant loss has a
  staffing cap. In this shop the shelves lose roughly three customers for
  every one the till loses, so a policy that only ever acts on the winner
  hires stockers until the cap and then stops — leaving the queue losses
  unaddressed and the cashier role permanently unsold."
  [state]
  (let [{:keys [walkouts-queue walkouts-empty]} (:stats state)]
    (->> [[:shelves walkouts-empty] [:queue walkouts-queue]]
         (filter (fn [[_ n]] (>= n loss-threshold)))
         (sort-by (fn [[kind n]] [(- n) (name kind)]))
         (mapv first))))

(def ^:private cure
  {:shelves :restocker
   :queue :cashier})

(defn- can-hire?
  [state world role reserve]
  (let [{:keys [:staff/hire-cost :staff/max]} (econ/staff-roles role)]
    (and (< (count (filter #(= role (:role %)) (:staff state))) max)
         (>= (- (:money state) reserve) hire-cost))))

(defn decide
  "One spending decision, or nil. Pure — returns a keyword-tagged intent
  rather than a mutated state, so a test can assert on the decision without
  running the shop."
  [state world]
  (let [next-zone (first (econ/unlockable state world))
        reserve (or (:zone/cost next-zone) 0)
        ;; Worst leak first, falling through to the next one when the role
        ;; that would fix it is already at its cap.
        role (first (filter #(can-hire? state world % 0)
                            (map cure (bottlenecks state))))]
    (cond
      (and next-zone (econ/can-unlock? state world (:zone/id next-zone)))
      [:unlock (:zone/id next-zone)]

      ;; A hire that fixes a live bottleneck ignores the reserve. Nobody saves
      ;; for a new department while customers are walking out of the one they
      ;; already have, and a policy that does reports a shop as unplayable
      ;; when what is unplayable is the policy.
      role
      [:hire role]

      :else
      (when-let [kind (first (filter (fn [k]
                                       (let [lvl (get-in state [:upgrades k] 1)
                                             cost (econ/upgrade-cost k lvl)]
                                         ;; Half the next department is kept
                                         ;; back, not all of it: saving in
                                         ;; full stalls every upgrade until
                                         ;; the last unlock is bought.
                                         (and cost (>= (- (:money state) (quot reserve 2)) cost))))
                                     upgrade-order))]
        [:upgrade kind]))))

(defn apply-decision
  [state world decision]
  (case (first decision)
    :unlock (econ/unlock state world (second decision))
    :hire (econ/hire state world (second decision))
    :upgrade (econ/buy-upgrade state (second decision))
    state))

(defn policy
  "Drop-in `sim/run` policy: spend at most once per tick.

  Once per tick rather than in a loop so a windfall is spent over several
  ticks and the log reads as a sequence of decisions instead of one blur."
  [state world]
  (if-let [d (decide state world)]
    (apply-decision state world d)
    state))

(defn- next-department-name
  [state world]
  (:zone/name (first (econ/unlockable state world))))

(defn progress
  "A readable snapshot for the balance runner."
  [state world]
  {:tick (:tick state)
   :money (:money state)
   :revenue (get-in state [:stats :revenue])
   :served (get-in state [:stats :served])
   :lost (get-in state [:stats :lost])
   :lost-to-queue (get-in state [:stats :walkouts-queue])
   :lost-to-empty (get-in state [:stats :walkouts-empty])
   :departments (world/open-departments world (:unlocked state))
   :upgrades (:upgrades state)
   :staff (frequencies (map :role (:staff state)))
   :next-unlock (next-department-name state world)
   :next-unlock-cost (next-department-cost state world)})
