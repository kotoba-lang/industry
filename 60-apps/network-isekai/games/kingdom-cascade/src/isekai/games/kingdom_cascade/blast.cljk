(ns isekai.games.kingdom-cascade.blast
  "Areas of effect for special pieces, and for two specials swapped together.

  Nothing here mutates a board — every function answers the single question
  \"which cells does this hit?\" and hands the answer to `clear/apply-clear`.
  Keeping detonation and clearing apart is what lets a chain of five specials
  become five reviewable animation frames instead of one opaque board diff."
  (:require [isekai.games.kingdom-cascade.board :as b]))

(defn- playable-in [board positions]
  (into #{} (filter #(b/playable? board %) positions)))

(defn- row [board y] (playable-in board (map (fn [x] [x y]) (range (:w board)))))

(defn- col [board x] (playable-in board (map (fn [y] [x y]) (range (:h board)))))

(defn- square
  [board [x y] r]
  (playable-in board (for [dx (range (- r) (inc r))
                           dy (range (- r) (inc r))]
                       [(+ x dx) (+ y dy)])))

(defn- cells-of-color
  [board color]
  (into #{} (filter #(= color (b/color-at board %)) (b/positions board))))

(def ^:private color-rank
  (into {} (map-indexed (fn [i c] [c i]) b/colors)))

(defn dominant-color
  "The colour a prism picks when nothing told it which one to eat — the most
  common colour on the board, ties broken by `board/colors` order so two
  runtimes never disagree."
  [board]
  (let [freq (frequencies (keep #(b/color-at board %) (b/positions board)))]
    (when (seq freq)
      (->> (keys freq)
           (sort-by (fn [c] [(- (freq c)) (color-rank c 99)]))
           first))))

(defn area
  "Cells hit by a single special detonating at `origin`."
  [board origin power]
  (let [[x y] origin]
    (case power
      :arrow-h (row board y)
      :arrow-v (col board x)
      :bomb (square board origin 1)
      :prism (if-let [c (dominant-color board)]
               (conj (cells-of-color board c) origin)
               #{origin})
      #{origin})))

(defn- arrow? [p] (contains? #{:arrow-h :arrow-v} p))

(defn combo
  "Cells hit when two specials are swapped into each other.

  `at` is the cell the player dragged into — combos centre on it, because
  that is the cell they were aiming at.

  Returns `{:cells #{pos} :combo keyword}`; the keyword is carried into the
  frame log so the renderer and the telemetry both name the same effect."
  [board at other-pos pa pb]
  (let [[x y] at
        both (fn [p] (or (= pa p) (= pb p)))
        pair (set [pa pb])
        partner (if (= pa :prism) pb pa)]
    (cond
      ;; two prisms: the board is the area
      (= pair #{:prism})
      {:combo :prism-prism :cells (set (b/positions board))}

      ;; prism plus a special: convert one colour into that special and fire
      (and (both :prism) (or (= partner :bomb) (arrow? partner)))
      (let [other partner
            c (dominant-color board)
            seeds (if c (cells-of-color board c) #{at})
            spread (case other
                     :arrow-h (mapcat (fn [[_ py]] (row board py)) seeds)
                     :arrow-v (mapcat (fn [[px _]] (col board px)) seeds)
                     :bomb (mapcat #(square board % 1) seeds)
                     nil)]
        {:combo (keyword (str "prism-" (name other)))
         :cells (into (conj seeds at other-pos) spread)})

      ;; two bombs: one bigger crater
      (= pair #{:bomb})
      {:combo :bomb-bomb :cells (square board at 2)}

      ;; bomb plus arrow: a three-wide beam both ways
      (and (both :bomb) (or (both :arrow-h) (both :arrow-v)))
      {:combo :bomb-arrow
       :cells (into #{} (concat (mapcat #(row board %) [(dec y) y (inc y)])
                                (mapcat #(col board %) [(dec x) x (inc x)])))}

      ;; two arrows: a full cross regardless of the two directions
      (and (arrow? pa) (arrow? pb))
      {:combo :arrow-arrow :cells (into (row board y) (col board x))}

      :else
      {:combo :none :cells (into (area board at pa) (area board other-pos pb))})))

(defn prism-swallow
  "A prism swapped with an ordinary piece eats that piece's colour."
  [board at other-pos]
  (let [c (b/color-at board other-pos)]
    {:combo :prism-color
     :color c
     :cells (conj (if c (cells-of-color board c) #{}) at other-pos)}))
