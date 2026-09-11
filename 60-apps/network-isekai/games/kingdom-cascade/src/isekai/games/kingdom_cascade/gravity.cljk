(ns isekai.games.kingdom-cascade.gravity
  "Settling the board: pieces fall, spawners top the columns back up.

  Diagonal fall is what makes an irregular board playable at all. Without it,
  a crate or a hole under an overhang leaves a pocket that never refills and
  the level deadlocks. A piece slides diagonally only when the cell directly
  above its target is *solid* — off-board, a block, or a pinned piece under
  ice — so on a plain rectangular board the behaviour is exactly straight
  fall and nothing looks jittery."
  (:require [isekai.games.kingdom-cascade.board :as b]
            [isekai.games.common.rng :as rng]))

(defn- donor
  "The cell whose piece may drop into the vacant `pos`, or nil.

  Straight above wins. Diagonals are only consulted when straight above is
  solid, and the left diagonal is tried first so the choice is deterministic."
  [board [x y]]
  (let [above [x (dec y)]]
    (cond
      (and (b/playable? board above)
           (some? (b/piece-at board above))
           (nil? (b/cover-at board above)))
      above

      (b/solid? board above)
      (first (filter (fn [p]
                       (and (some? (b/piece-at board p))
                            (nil? (b/cover-at board p))))
                     [[(dec x) (dec y)] [(inc x) (dec y)]]))

      :else nil)))

(defn settle-step
  "One tick of falling. Returns `{:board b' :moves [{:from p :to p}]}`.

  Bottom-up so a whole column shifts by one in a single tick, which is what
  makes the animation read as a column of pieces sliding together rather than
  the top piece teleporting to the floor."
  [board]
  (reduce
   (fn [acc pos]
     (let [bd (:board acc)]
       (if (b/vacant? bd pos)
         (if-let [from (donor bd pos)]
           (-> acc
               (assoc :board (b/move-piece bd from pos))
               (update :moves conj {:from from :to pos}))
           acc)
         acc)))
   {:board board :moves []}
   (sort-by (fn [[x y]] [(- y) x]) (b/positions board))))

(defn refill-step
  "Drops one new piece into each empty spawner.

  Returns `{:board b' :seed s' :spawns [{:pos p :piece pc}]}`."
  [board seed colors]
  (reduce
   (fn [acc pos]
     (if (b/vacant? (:board acc) pos)
       (let [[color seed'] (rng/pick (:seed acc) colors)
             piece {:kind :basic :color color}]
         (-> acc
             (assoc :board (b/put-piece (:board acc) pos piece)
                    :seed seed')
             (update :spawns conj {:pos pos :piece piece})))
       acc))
   {:board board :seed seed :spawns []}
   (sort-by (juxt second first) (:spawners board))))

(defn settle
  "Runs fall + refill to a fixed point.

  Returns `{:board b' :seed s' :frames [...]}` where each frame is one tick,
  ready to be handed to the renderer as an animation step.

  `limit` is a runaway guard, not a tuning knob: a board that has not settled
  after `limit` ticks means a rule is wrong, and stopping is better than
  spinning."
  ([board seed colors] (settle board seed colors 512))
  ([board seed colors limit]
   (loop [bd board, sd seed, frames [], n 0]
     (if (>= n limit)
       {:board bd :seed sd :frames frames :exhausted? true}
       (let [{fell :board moves :moves} (settle-step bd)
             {filled :board sd' :seed spawns :spawns} (refill-step fell sd colors)]
         (if (and (empty? moves) (empty? spawns))
           {:board bd :seed sd :frames frames}
           (recur filled sd'
                  (conj frames {:frame/kind :settle
                                :frame/board filled
                                :frame/moves moves
                                :frame/spawns spawns})
                  (inc n))))))))
