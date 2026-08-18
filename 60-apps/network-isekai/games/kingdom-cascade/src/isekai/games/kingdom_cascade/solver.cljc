(ns isekai.games.kingdom-cascade.solver
  "A greedy player, used for three things and never shipped as an opponent:

  1. **Hints.** `best-move` is what the hint button should offer. The first
     legal swap in reading order is a legal move, not a good one, and offering
     it teaches players that hints are worthless.
  2. **Solvability.** A level whose goals a greedy player cannot reach inside
     its move budget is very likely unfair. That is a cheap gate to run over a
     level set, and far cheaper than a player complaint.
  3. **Difficulty tuning.** The move count a greedy player needs is a stable,
     reproducible number to design a budget against.

  The search is one ply deep. Deeper search would rate cascade luck as skill,
  which is the wrong signal for all three uses — the shuffle after the clear
  is not knowable at move time."
  (:require [isekai.games.kingdom-cascade.core :as core]
            [isekai.games.kingdom-cascade.level :as level]))

(def goal-weight
  "Goal progress dominates score. A move that clears 30 pieces and no
  obstacle loses to one that breaks a single crate, because the crate is what
  the level is actually asking for."
  400)

(defn move-value
  "Simulated value of one swap: goal progress first, raw score as tiebreak.

  Returns nil when the swap is not legal."
  [state [a bpos]]
  (when (core/legal-swap? state a bpos)
    (let [after (core/swap state a bpos)
          goals (:level/goals (:level state))
          before-left (reduce + (level/remaining goals (:progress state)))
          after-left (reduce + (level/remaining goals (:progress after)))]
      {:move [a bpos]
       :goal-progress (- before-left after-left)
       :score-gain (- (:score after) (:score state))
       :value (+ (* goal-weight (- before-left after-left))
                 (- (:score after) (:score state)))})))

(defn ranked-moves
  "Every legal move, best first. Ties keep board reading order, so the choice
  is reproducible."
  [state]
  (->> (core/legal-swaps state)
       (keep #(move-value state %))
       (sort-by (fn [m] [(- (:value m)) (:move m)]))
       vec))

(defn best-move
  [state]
  (:move (first (ranked-moves state))))

(defn autoplay
  "Plays greedily to a terminal state.

  Returns `{:state final :moves n :reshuffles n}`. `limit` guards against a
  level that never terminates; hitting it is a bug report, not a result."
  ([state] (autoplay state 400))
  ([state limit]
   (loop [s state, n 0, reshuffles 0]
     (cond
       (not= :playing (:status s)) {:state s :moves n :reshuffles reshuffles}
       (>= n limit) {:state s :moves n :reshuffles reshuffles :exhausted? true}
       :else (if-let [[a bpos] (best-move s)]
               (recur (core/swap s a bpos) (inc n) reshuffles)
               (recur (core/reshuffle s) n (inc reshuffles)))))))

(defn solvable?
  "Can a greedy player finish this level? Deterministic for a given seed."
  [lvl]
  (= :won (:status (:state (autoplay (core/new-game lvl))))))
