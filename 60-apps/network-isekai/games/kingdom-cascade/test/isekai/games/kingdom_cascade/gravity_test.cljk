(ns isekai.games.kingdom-cascade.gravity-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.kingdom-cascade.board :as b]
            [isekai.games.kingdom-cascade.gravity :as gravity]
            [isekai.games.kingdom-cascade.support :as sup]))

(def colors [:coin :gem :clover :goblet])

(deftest pieces-fall-straight-down
  (let [board (sup/board-of ["R" "." "."] {:spawners #{}})
        {:keys [board]} (gravity/settle board 1 colors)]
    (is (= ["." "." "R"] (b/render-ascii board)))))

(deftest a-block-holds-the-column-up
  (let [board (sup/board-of ["R" "." "c" "."] {:spawners #{}})
        {:keys [board]} (gravity/settle board 1 colors)]
    (is (= ["." "R" "c" "."] (b/render-ascii board))
        "the piece stops on the crate; the cell below it stays empty")))

(deftest an-iced-piece-does-not-fall-and-nothing-falls-through-it
  (let [board (sup/board-of ["R" "i" "."] {:spawners #{}})
        {:keys [board]} (gravity/settle board 1 colors)]
    (is (= ["R" "i" "."] (b/render-ascii board))
        "ice pins its own cell and blocks the column")))

(deftest pieces-slide-diagonally-past-an-overhang
  (testing "without this, a pocket under an overhang never refills and the
            level deadlocks"
    (let [board (sup/board-of ["R#"
                               ".."] {:spawners #{}})
          {:keys [board]} (gravity/settle board 1 colors)]
      (is (= [".#" "R."] (b/render-ascii board))
          "straight down first")))
  (let [board (sup/board-of [".R"
                             "#."] {:spawners #{}})
        {:keys [board]} (gravity/settle board 1 colors)]
    (is (= [".." "#R"] (b/render-ascii board)))))

(deftest spawners-refill-empty-columns
  (let [board (sup/board-of ["." "." "."])
        {:keys [board]} (gravity/settle board 7 colors)]
    (is (every? #(some? (b/piece-at board %)) (b/positions board))
        "the whole column is topped up from the spawner")
    (is (every? #(contains? (set colors) (b/color-at board %)) (b/positions board))
        "and only from the level's colour set")))

(deftest settling-is-deterministic-for-a-seed
  (let [board (sup/board-of ["." "." "." "."])
        a (gravity/settle board 12345 colors)
        c (gravity/settle board 12345 colors)]
    (is (= (b/render-ascii (:board a)) (b/render-ascii (:board c))))
    (is (= (:seed a) (:seed c)))))

(deftest an-already-settled-board-produces-no-frames
  (let [board (sup/board-of ["R" "G" "B"] {:spawners #{}})
        {:keys [frames]} (gravity/settle board 1 colors)]
    (is (empty? frames) "no work means no animation step")))

(deftest settling-terminates-on-a-sealed-board
  (testing "a board with no spawners and no room must not spin the guard"
    (let [board (sup/board-of ["c" "c"] {:spawners #{}})
          result (gravity/settle board 1 colors)]
      (is (not (:exhausted? result))))))
