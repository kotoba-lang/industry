(ns isekai.games.kingdom-cascade.blast-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.kingdom-cascade.blast :as blast]
            [isekai.games.kingdom-cascade.support :as sup]))

(deftest an-arrow-clears-its-line-and-stops-at-the-board-edge
  (let [board (sup/board-of ["OOOOO"
                             "OOOOO"
                             "OOOOO"])]
    (is (= #{[0 1] [1 1] [2 1] [3 1] [4 1]}
           (blast/area board [2 1] :arrow-h)))
    (is (= #{[2 0] [2 1] [2 2]}
           (blast/area board [2 1] :arrow-v)))))

(deftest an-arrow-does-not-hit-cells-outside-the-play-area
  (let [board (sup/board-of ["OO#OO"])]
    (is (= #{[0 0] [1 0] [3 0] [4 0]} (blast/area board [0 0] :arrow-h)))))

(deftest a-bomb-clears-a-three-by-three
  (let [board (sup/board-of ["OOOOO" "OOOOO" "OOOOO"])]
    (is (= 9 (count (blast/area board [2 1] :bomb))))
    (is (= 4 (count (blast/area board [0 0] :bomb)))
        "clipped at the corner, not wrapped")))

(deftest a-prism-with-no-target-eats-the-most-common-colour
  (let [board (sup/board-of ["OOOR"
                             "OOGB"])]
    (is (= :coin (blast/dominant-color board)))
    (is (= #{[0 0] [1 0] [2 0] [0 1] [1 1] [3 3]}
           (conj (blast/area board [3 3] :prism) [3 3]))
        "every coin, plus its own cell")))

(deftest dominant-colour-breaks-ties-in-a-fixed-order
  (testing "two runtimes must agree, so a tie cannot fall to map ordering"
    (let [board (sup/board-of ["OR"])]
      (is (= :coin (blast/dominant-color board))
          "coin outranks gem in board/colors")
      (is (= :coin (blast/dominant-color (sup/board-of ["RO"])))
          "and position on the board does not change that"))))

(deftest two-arrows-clear-a-full-cross
  (let [board (sup/board-of ["OOOOO" "OOOOO" "OOOOO"])
        {:keys [combo cells]} (blast/combo board [2 1] [1 1] :arrow-h :arrow-v)]
    (is (= :arrow-arrow combo))
    (is (= 7 (count cells)) "5 in the row + 3 in the column, sharing one cell")))

(deftest two-bombs-make-a-bigger-crater
  (let [board (sup/board-of ["OOOOO" "OOOOO" "OOOOO" "OOOOO" "OOOOO"])
        {:keys [combo cells]} (blast/combo board [2 2] [1 2] :bomb :bomb)]
    (is (= :bomb-bomb combo))
    (is (= 25 (count cells)) "radius 2, against radius 1 for a lone bomb")))

(deftest bomb-plus-arrow-is-a-three-wide-beam-both-ways
  (let [board (sup/board-of ["OOOOO" "OOOOO" "OOOOO" "OOOOO" "OOOOO"])
        {:keys [combo cells]} (blast/combo board [2 2] [1 2] :bomb :arrow-h)]
    (is (= :bomb-arrow combo))
    (is (= 21 (count cells)) "3 rows + 3 columns, overlapping in a 3x3")))

(deftest two-prisms-take-the-whole-board
  (let [board (sup/board-of ["OOO" "RRR"])
        {:keys [combo cells]} (blast/combo board [1 0] [0 0] :prism :prism)]
    (is (= :prism-prism combo))
    (is (= 6 (count cells)))))

(deftest a-prism-swapped-with-a-piece-eats-that-colour
  (let [board (sup/board-of ["&ROR"
                             "ROOR"])
        {:keys [combo color cells]} (blast/prism-swallow board [0 0] [1 0])]
    (is (= :prism-color combo))
    (is (= :gem color))
    (is (= #{[1 0] [3 0] [0 1] [3 1] [0 0]} cells)
        "every gem, plus both swapped cells")))
