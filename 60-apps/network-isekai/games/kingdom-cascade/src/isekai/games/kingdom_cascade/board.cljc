(ns isekai.games.kingdom-cascade.board
  "Grid model.

  A board is a sparse map, not a rectangle. Only *playable* cells are present
  in `:cells`; the irregular silhouette of a level (the pebble field cutting
  into the play area, the shaft down the middle) is expressed by the cells
  that simply are not there. Nothing has to encode `:wall` as a value, and no
  traversal can walk into one by forgetting a bounds check.

  Shape of a board:

      {:w 9 :h 9
       :cells    {[x y] cell}   ; playable cells only
       :spawners #{[x y]}}      ; cells that refill from off-board

  Shape of a cell — every key optional:

      {:piece {:kind :basic   :color :coin}   ; or {:kind :arrow-h} etc.
       :cover {:kind :ice     :hp 1}          ; sits on top of the piece
       :block {:kind :crate   :hp 1}}         ; occupies the cell instead

  Coordinates are `[x y]` with y growing downward, so `inc` on y is \"below\"
  and gravity is `+`.")

(def colors
  "The six basic piece colours. A level picks a subset."
  [:coin :gem :clover :goblet :crown :rune])

(def powers
  "Special (colour-neutral) pieces.

  - `:arrow-h` / `:arrow-v` clear their row / column
  - `:bomb` clears a radius-1 square
  - `:prism` clears every piece of one colour"
  #{:arrow-h :arrow-v :bomb :prism})

(def block-hp
  {:crate 1
   :stone 2})

(def cover-hp
  {:ice 1
   :frost 2})

;; ---------------------------------------------------------------- accessors

(defn playable?
  "True when `pos` is part of the play area at all."
  [board pos]
  (contains? (:cells board) pos))

(defn cell [board pos] (get-in board [:cells pos]))

(defn piece-at [board pos] (:piece (cell board pos)))

(defn block-at [board pos] (:block (cell board pos)))

(defn cover-at [board pos] (:cover (cell board pos)))

(defn spawner? [board pos] (contains? (:spawners board) pos))

(defn basic?
  "True for an ordinary coloured piece."
  [piece]
  (= :basic (:kind piece)))

(defn power-of
  "The special power of a piece, or nil for a basic piece."
  [piece]
  (when (and piece (not (basic? piece))) (:kind piece)))

(defn color-at
  "The colour a match may run through this cell, or nil.

  A piece under ice still counts: clearing it is how the ice comes off. A
  block never counts — it holds no piece."
  [board pos]
  (let [p (piece-at board pos)]
    (when (basic? p) (:color p))))

(defn swappable?
  "True when the player is allowed to drag this cell.

  Covered pieces are pinned in place — that is the entire mechanic of ice.
  They can still be *matched* by a swap made elsewhere."
  [board pos]
  (let [c (cell board pos)]
    (boolean (and c (:piece c) (nil? (:cover c)) (nil? (:block c))))))

(defn solid?
  "True when nothing can fall through `pos`.

  Off-board, a block, or a pinned (covered) piece."
  [board pos]
  (let [c (cell board pos)]
    (or (nil? c)
        (some? (:block c))
        (some? (:cover c)))))

(defn vacant?
  "True when a *falling or spawning* piece may land here: playable, holding
  no piece, and neither blocked nor covered."
  [board pos]
  (let [c (cell board pos)]
    (boolean (and c (nil? (:piece c)) (nil? (:block c)) (nil? (:cover c))))))

(defn pieceless?
  "True when `pos` should hold a piece but does not.

  Unlike `vacant?` this ignores covers, because a covered cell *does* hold a
  piece — the cover sits on top of it. The two are easy to conflate and the
  bug it causes is silent: cover cells never get dealt a piece, so no match
  can ever run through them and the level's cover goal becomes unreachable
  without anything erroring."
  [board pos]
  (let [c (cell board pos)]
    (boolean (and c (nil? (:piece c)) (nil? (:block c))))))

;; ------------------------------------------------------------------ geometry

(defn- magnitude [n] (if (neg? n) (- n) n))

(defn adjacent?
  [[ax ay] [bx by]]
  (= 1 (+ (magnitude (- ax bx)) (magnitude (- ay by)))))

(defn neighbors4
  [[x y]]
  [[x (dec y)] [x (inc y)] [(dec x) y] [(inc x) y]])

(defn positions
  "All playable positions, in a stable reading order (top-left to
  bottom-right). Determinism of iteration matters: cascade resolution and the
  replay digest both depend on it."
  [board]
  (sort-by (juxt second first) (keys (:cells board))))

(defn column
  "Playable positions in column `x`, top to bottom."
  [board x]
  (->> (keys (:cells board))
       (filter #(= x (first %)))
       (sort-by second)))

;; ------------------------------------------------------------------ mutators

(defn put-piece [board pos piece] (assoc-in board [:cells pos :piece] piece))

(defn take-piece [board pos] (update-in board [:cells pos] dissoc :piece))

(defn move-piece
  [board from to]
  (-> board
      (put-piece to (piece-at board from))
      (take-piece from)))

(defn swap-pieces
  [board a b]
  (let [pa (piece-at board a)
        pb (piece-at board b)]
    (-> board (put-piece a pb) (put-piece b pa))))

;; ------------------------------------------------------------------- parsing

(def ^:private glyphs
  "Level-grid characters. `.` is plain playable ground; space and `#` are
  outside the play area."
  {\. {}
   \* {:spawn? true}
   \c {:block {:kind :crate :hp 1}}
   \s {:block {:kind :stone :hp 2}}
   \i {:cover {:kind :ice :hp 1}}
   \f {:cover {:kind :frost :hp 2}}})

(defn- auto-spawners
  "Every column's topmost playable cell refills from off-board, unless the
  level marked its own spawners with `*`."
  [cells explicit]
  (if (seq explicit)
    (set explicit)
    (->> (keys cells)
         (group-by first)
         (map (fn [[_ ps]] (apply min-key second ps)))
         set)))

(defn parse-grid
  "Builds an empty board (no pieces yet) from a vector of equal-length row
  strings.

      \"#..*..#\"

  Throws on an unknown glyph rather than silently treating it as a hole — a
  typo in a level file should not quietly shrink the board."
  [rows]
  (let [rows (vec rows)
        h (count rows)
        w (reduce max 0 (map count rows))
        parsed (for [y (range h)
                     x (range (count (nth rows y)))
                     :let [ch (nth (nth rows y) x)]
                     :when (not (contains? #{\space \#} ch))]
                 (if-let [spec (get glyphs ch)]
                   [[x y] spec]
                   (throw (ex-info "unknown level glyph"
                                   {:glyph ch :pos [x y]}))))
        cells (into {} (map (fn [[pos spec]] [pos (dissoc spec :spawn?)]) parsed))
        explicit (keep (fn [[pos spec]] (when (:spawn? spec) pos)) parsed)]
    {:w w
     :h h
     :cells cells
     :spawners (auto-spawners cells explicit)}))

(defn render-ascii
  "Debug view. Used by the tests and by the headless runner — being able to
  read a board without a GPU is what makes the cascade logic reviewable."
  [board]
  (let [glyph (fn [pos]
                (let [c (cell board pos)]
                  (cond
                    (nil? c) \#
                    (:block c) (case (:kind (:block c)) :crate \c :stone \s \?)
                    (:cover c) (case (:kind (:cover c)) :ice \i :frost \f \?)
                    (:piece c) (let [p (:piece c)]
                                 (if (basic? p)
                                   (case (:color p)
                                     :coin \O :gem \R :clover \G
                                     :goblet \B :crown \Y :rune \P \?)
                                   (case (:kind p)
                                     :arrow-h \- :arrow-v \| :bomb \@ :prism \& \?)))
                    :else \.)))]
    (->> (range (:h board))
         (map (fn [y] (apply str (map (fn [x] (glyph [x y])) (range (:w board))))))
         vec)))
