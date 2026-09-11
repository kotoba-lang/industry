(ns isekai.games.kingdom-cascade.support
  "Test helpers: build an exact board from a picture instead of dealing one.

  Almost every rule in this game is easier to state as a before/after picture
  than as a fixture built by calling the game. `board-of` takes the same
  glyphs `board/render-ascii` prints, so a failing test can be pasted straight
  back in as the next test's input."
  (:require [isekai.games.kingdom-cascade.board :as b]))

(def glyph->piece
  {\O {:kind :basic :color :coin}
   \R {:kind :basic :color :gem}
   \G {:kind :basic :color :clover}
   \B {:kind :basic :color :goblet}
   \Y {:kind :basic :color :crown}
   \P {:kind :basic :color :rune}
   \- {:kind :arrow-h}
   \| {:kind :arrow-v}
   \@ {:kind :bomb}
   \& {:kind :prism}})

(defn board-of
  "Builds a board from rows of `render-ascii` glyphs.

  `#` is outside the play area, `.` is an empty playable cell, `c`/`s` are
  blocks and `i`/`f` are covers over whatever `covers` says is underneath
  (default a coin)."
  ([rows] (board-of rows {}))
  ([rows {:keys [covers spawners]}]
   (let [rows (vec rows)
         h (count rows)
         w (reduce max 0 (map count rows))
         cells (into {}
                     (for [y (range h)
                           x (range (count (nth rows y)))
                           :let [ch (nth (nth rows y) x)]
                           :when (not= \# ch)]
                       [[x y]
                        (cond
                          (= \c ch) {:block {:kind :crate :hp 1}}
                          (= \s ch) {:block {:kind :stone :hp 2}}
                          (= \i ch) {:cover {:kind :ice :hp 1}
                                     :piece (get covers [x y] {:kind :basic :color :coin})}
                          (= \f ch) {:cover {:kind :frost :hp 2}
                                     :piece (get covers [x y] {:kind :basic :color :coin})}
                          (= \. ch) {}
                          :else {:piece (get glyph->piece ch)})]))]
     {:w w :h h :cells cells
      :spawners (or spawners
                    (->> (keys cells)
                         (group-by first)
                         (map (fn [[_ ps]] (apply min-key second ps)))
                         set))})))

(defn state-of
  "A minimal playable state around a hand-built board."
  ([board] (state-of board {}))
  ([board overrides]
   (merge {:level {:level/id "test"
                   :level/moves 10
                   :level/seed 1
                   :level/colors [:coin :gem :clover :goblet]
                   :level/goals [{:goal/kind :block :goal/block :crate :goal/count 1}]}
           :board board
           :seed 1
           :moves-left 10
           :progress {}
           :score 0
           :turn 0
           :status :playing
           :frames []}
          overrides)))

(defn colors-on
  [board]
  (into #{} (keep #(b/color-at board %) (b/positions board))))
