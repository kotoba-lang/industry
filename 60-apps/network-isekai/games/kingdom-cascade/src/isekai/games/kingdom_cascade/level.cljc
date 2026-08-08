(ns isekai.games.kingdom-cascade.level
  "Level definitions, the opening deal, and goal accounting.

  A level is plain EDN — the grid is a vector of row strings, so a designer
  can read and edit a board in any text editor and a diff of a level change
  is legible in review. See `resources/levels/`.

      {:level/id      \"kc-001\"
       :level/name    \"Cellar Steps\"
       :level/moves   25
       :level/seed    20260808
       :level/colors  [:coin :gem :clover :goblet]
       :level/grid    [\"###...###\" ...]
       :level/goals   [{:goal/kind :block :goal/block :crate :goal/count 9}]}

  Grid glyphs: `.` ground, `*` explicit spawner, `c` crate, `s` stone,
  `i` ice, `f` frost, `#` or space outside the play area."
  (:require [isekai.games.kingdom-cascade.board :as b]
            [isekai.games.common.rng :as rng]))

(defn- would-match?
  "True if placing `color` at `pos` completes a run of three with cells that
  are already dealt. Only left and up need checking: the deal walks in reading
  order, so nothing to the right or below exists yet."
  [board [x y] color]
  (let [same? (fn [p] (= color (b/color-at board p)))]
    (or (and (same? [(- x 1) y]) (same? [(- x 2) y]))
        (and (same? [x (- y 1)]) (same? [x (- y 2)])))))

(defn deal
  "Fills every playable cell that should hold a piece — including the cells
  under covers — never opening with a match already on the board.

  If every colour would match — possible in a tight corridor with few colours
  — the first colour is placed anyway and the cascade loop resolves it on the
  first settle. Refusing to deal would be worse: the level would fail to load."
  [board seed colors]
  (reduce
   (fn [{:keys [board seed] :as acc} pos]
     (if-not (b/pieceless? board pos)
       acc
       (let [[order seed'] (rng/shuffle seed colors)
             color (or (first (remove #(would-match? board pos %) order))
                       (first order))]
         {:board (b/put-piece board pos {:kind :basic :color color})
          :seed seed'})))
   {:board board :seed seed}
   (b/positions board)))

;; --------------------------------------------------------------------- goals

(defn goal-target [goal] (:goal/count goal))

(defn- goal-matches?
  "Does a single collected event count toward `goal`?"
  [goal event]
  (case (:goal/kind goal)
    :block (= (:goal/block goal) (:block event))
    :cover (= (:goal/cover goal) (:cover event))
    :collect (= (:goal/color goal) (:color event))
    false))

(defn credit
  "Advances `progress` — a map of goal index to count — by `collected`.

  A map rather than a vector so a level can gain a goal without every saved
  progress value becoming an index error."
  [goals progress collected]
  (reduce
   (fn [prog event]
     (reduce
      (fn [p i]
        (if (goal-matches? (nth goals i) event)
          (update p i (fnil inc 0))
          p))
      prog
      (range (count goals))))
   (or progress {})
   collected))

(defn goal-met? [goal n] (>= (or n 0) (goal-target goal)))

(defn all-goals-met?
  [goals progress]
  (every? (fn [i] (goal-met? (nth goals i) (get progress i 0)))
          (range (count goals))))

(defn remaining
  "Per-goal shortfall, for the HUD."
  [goals progress]
  (mapv (fn [i] (max 0 (- (goal-target (nth goals i)) (get progress i 0))))
        (range (count goals))))

;; ---------------------------------------------------------------- validation

(defn validate
  "Returns a vector of problems with a level definition. Empty means usable.

  Cheap to run and worth running: a level whose goal asks for more crates than
  the grid contains is unwinnable, and that is far easier to catch here than
  from a player report."
  [level]
  (let [board (b/parse-grid (:level/grid level))
        blocks (frequencies (keep #(:kind (b/block-at board %)) (b/positions board)))
        covers (frequencies (keep #(:kind (b/cover-at board %)) (b/positions board)))]
    (cond-> []
      (not (seq (:level/grid level)))
      (conj {:problem :empty-grid})

      (< (count (:level/colors level)) 3)
      (conj {:problem :too-few-colors :colors (:level/colors level)})

      (not (pos? (or (:level/moves level) 0)))
      (conj {:problem :no-moves})

      (empty? (:spawners board))
      (conj {:problem :no-spawners})

      (not (seq (:level/goals level)))
      (conj {:problem :no-goals})

      :always
      (into (keep (fn [goal]
                    (case (:goal/kind goal)
                      :block (when (> (goal-target goal) (get blocks (:goal/block goal) 0))
                               {:problem :unreachable-goal :goal goal
                                :available (get blocks (:goal/block goal) 0)})
                      :cover (when (> (goal-target goal) (get covers (:goal/cover goal) 0))
                               {:problem :unreachable-goal :goal goal
                                :available (get covers (:goal/cover goal) 0)})
                      nil))
                  (:level/goals level))))))
