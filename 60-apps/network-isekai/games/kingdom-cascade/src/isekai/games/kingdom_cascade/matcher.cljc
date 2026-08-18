(ns isekai.games.kingdom-cascade.matcher
  "Match detection and special-piece promotion.

  Detection is two-pass. First every maximal horizontal and vertical run of
  three-or-more same-coloured cells is collected. Then runs that share a cell
  are merged into one *group*, which is what turns a horizontal run crossing a
  vertical run into a single L/T match instead of two separate threes. The
  shape of the merged group is what decides the reward:

      3 cells                  -> nothing
      4 in a line              -> arrow along that line
      5+ in a line             -> prism
      L / T (both axes >= 3)   -> bomb

  Special pieces are colour-neutral, so they never take part in a colour run.
  They are triggered by being swapped, or by being caught in someone else's
  blast — see `blast.cljc`."
  (:require [clojure.set :as set]
            [isekai.games.kingdom-cascade.board :as b]))

(defn- runs-along
  "Maximal same-colour runs of length >= 3 along one axis.

  `line-of` returns the ordered positions of the k-th line; `lines` is how
  many there are."
  [board lines line-of]
  (into []
        (mapcat
         (fn [k]
           (let [ps (line-of k)]
             (loop [ps ps, run [], color nil, out []]
               (if-let [pos (first ps)]
                 (let [c (b/color-at board pos)]
                   (if (and c (= c color))
                     (recur (rest ps) (conj run pos) color out)
                     (recur (rest ps) [pos] c
                            (if (>= (count run) 3) (conj out {:color color :cells run}) out))))
                 (if (>= (count run) 3)
                   (conj out {:color color :cells run})
                   out)))))
         (range lines))))

(defn- horizontal-runs [board]
  (runs-along board (:h board)
              (fn [y] (->> (keys (:cells board))
                           (filter #(= y (second %)))
                           (sort-by first)))))

(defn- vertical-runs [board]
  (runs-along board (:w board)
              (fn [x] (b/column board x))))

(defn- merge-crossing
  "Unions runs that share at least one cell. Runs of different colours can
  never share a cell, so no colour check is needed here."
  [runs]
  (loop [pending runs, groups []]
    (if-let [r (first pending)]
      (let [cells (set (:cells r))
            [touching separate] ((juxt filter remove)
                                 #(seq (set/intersection cells (set (:cells %))))
                                 groups)
            merged {:color (:color r)
                    :cells (vec (sort-by (juxt second first)
                                         (reduce into cells (map :cells touching))))}]
        (recur (rest pending) (conj (vec separate) merged)))
      groups)))

(defn- longest-contiguous
  "Longest run of consecutive values of `f` within one row or column.

  Not `max - min + 1`: a merged group can hold two separate runs on the same
  row, joined through a vertical run, and a span would count the gap between
  them as part of the match."
  [cells f]
  (let [sorted (sort (distinct (map f cells)))]
    ;; Do not name the loop binding `sorted` as well: `loop` binds
    ;; sequentially like `let`, so `prev` would read the already-rebound
    ;; sequence and quietly drop the first element of every run.
    (loop [remaining (rest sorted), prev (first sorted), run 1, best 1]
      (if-let [v (first remaining)]
        (let [run' (if (= v (inc prev)) (inc run) 1)]
          (recur (rest remaining) v run' (max best run')))
        best))))

(defn- axis-extents
  "Longest contiguous horizontal and vertical extent inside a group."
  [cells]
  (let [longest (fn [groups f] (reduce max 0 (map #(longest-contiguous % f) (vals groups))))]
    [(longest (group-by second cells) first)
     (longest (group-by first cells) second)]))

(defn reward
  "The special a group earns, or nil.

  Bomb wins over prism when a group is both long and bent: a bent five is a
  harder shape to build than a straight five, so it should not be the weaker
  reward."
  [cells]
  (let [[hx vy] (axis-extents cells)
        n (count cells)]
    (cond
      (and (>= hx 3) (>= vy 3)) :bomb
      (>= n 5) :prism
      (= n 4) (if (>= hx 4) :arrow-h :arrow-v)
      :else nil)))

(defn find-groups
  "All match groups currently on the board.

  Returns `[{:color c :cells [[x y] ...] :reward power-or-nil} ...]` in a
  deterministic order."
  [board]
  (->> (concat (horizontal-runs board) (vertical-runs board))
       merge-crossing
       (map (fn [g] (assoc g :reward (reward (:cells g)))))
       (sort-by (comp (juxt second first) first :cells))
       vec))

(defn any-match?
  [board]
  (boolean (seq (find-groups board))))

(defn promotion-site
  "Where the special created by `group` should appear.

  The swapped-in cell if it is part of the group — that is what players
  expect, because it is the cell they touched. Otherwise the crossing cell of
  an L/T, otherwise the first cell in reading order."
  [group touched]
  (let [cells (:cells group)
        cellset (set cells)]
    (or (first (filter cellset touched))
        (let [rows (group-by second cells)
              cols (group-by first cells)
              crossing (filter (fn [[x y]]
                                 (and (>= (count (get rows y)) 3)
                                      (>= (count (get cols x)) 3)))
                               cells)]
          (first crossing))
        (first cells))))
