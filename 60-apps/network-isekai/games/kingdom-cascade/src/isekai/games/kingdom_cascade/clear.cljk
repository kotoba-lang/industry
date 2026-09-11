(ns isekai.games.kingdom-cascade.clear
  "Applying a set of cleared positions to the board.

  Matches and blasts both end here, so the rules about what a clear actually
  does — covers absorb it, blocks take splash from their neighbours, specials
  caught in it detonate in turn — are written once and cannot drift between
  the two paths."
  (:require [isekai.games.kingdom-cascade.board :as b]))

(defn- damage
  "Decrements `hp` on the `layer` (`:block` or `:cover`) at `pos`, removing
  the layer when it reaches zero. Returns `[board destroyed-kind-or-nil]`."
  [board pos layer]
  (let [{:keys [kind hp]} (get-in board [:cells pos layer])
        hp' (dec (or hp 1))]
    (if (pos? hp')
      [(assoc-in board [:cells pos layer :hp] hp') nil]
      [(update-in board [:cells pos] dissoc layer) kind])))

(defn apply-clear
  "Clears `positions` and returns a report.

      {:board     board'
       :pieces    {pos piece}   ; pieces actually removed
       :covers    {pos kind}    ; cover layers destroyed
       :blocks    {pos kind}    ; block layers destroyed
       :triggered [[pos power]] ; specials removed, now owed a blast
       :collected [{...}]}      ; goal-countable events, in order

  Order of resolution at a single cell:

  1. A **block** takes the hit. Blocks hold no piece, so nothing else happens.
  2. A **cover** takes the hit and shields the piece under it. This is why ice
     needs two clears to give up its piece: one for the ice, one for the piece.
  3. Otherwise the **piece** is removed. A special piece removed this way is
     reported in `:triggered` rather than detonated here — chaining is the
     caller's job, so that each detonation can become its own animation frame.

  Blocks adjacent to a cell that lost a piece or a cover also take one hit,
  and a block never takes two hits from the same clear."
  [board positions]
  (let [positions (sort-by (juxt second first) (set positions))
        init {:board board :pieces {} :covers {} :blocks {} :triggered [] :collected []}
        ;; pass 1 — pieces and covers
        r (reduce
           (fn [acc pos]
             (let [bd (:board acc)
                   c (b/cell bd pos)]
               (cond
                 (nil? c) acc
                 (:block c) acc                      ; handled in pass 2
                 (:cover c)
                 (let [[bd' destroyed] (damage bd pos :cover)]
                   (cond-> (assoc acc :board bd')
                     destroyed (-> (assoc-in [:covers pos] destroyed)
                                   (update :collected conj {:cover destroyed}))))

                 (:piece c)
                 (let [piece (:piece c)
                       power (b/power-of piece)]
                   (cond-> (-> acc
                               (assoc :board (b/take-piece bd pos))
                               (assoc-in [:pieces pos] piece)
                               (update :collected conj
                                       (if power {:power power} {:color (:color piece)})))
                     power (update :triggered conj [pos power])))

                 :else acc)))
           init
           positions)
        ;; pass 2 — blocks, direct hits and splash from neighbours, once each
        touched (into #{} (concat (keys (:pieces r)) (keys (:covers r))))
        splash (into #{} (mapcat b/neighbors4 touched))
        block-hits (->> (into (set positions) splash)
                        (filter #(b/block-at (:board r) %))
                        (sort-by (juxt second first)))]
    (reduce
     (fn [acc pos]
       (let [[bd' destroyed] (damage (:board acc) pos :block)]
         (cond-> (assoc acc :board bd')
           destroyed (-> (assoc-in [:blocks pos] destroyed)
                         (update :collected conj {:block destroyed})))))
     r
     block-hits)))
