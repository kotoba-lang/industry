(ns isekai.games.kingdom-cascade.solver-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.kingdom-cascade.core :as g]
            [isekai.games.kingdom-cascade.solver :as solver]
            [isekai.games.kingdom-cascade.support :as sup]))

(def level
  {:level/id "kc-solver"
   :level/name "Solver Bench"
   :level/moves 20
   :level/seed 20260808
   :level/colors [:coin :gem :clover :goblet]
   :level/goals [{:goal/kind :block :goal/block :crate :goal/count 4}]
   :level/grid ["........"
                "........"
                "..cccc.."
                "........"
                "........"
                "........"]})

(deftest every-ranked-move-is-a-legal-move
  (let [s (g/new-game level)
        moves (solver/ranked-moves s)]
    (is (seq moves))
    (is (every? (fn [{[a bpos] :move}] (g/legal-swap? s a bpos)) moves))))

(deftest moves-are-ranked-best-first
  (let [s (g/new-game level)
        values (mapv :value (solver/ranked-moves s))]
    (is (= values (vec (reverse (sort values)))))))

(deftest goal-progress-outranks-raw-score
  (testing "a move that breaks an obstacle beats a bigger match that does not"
    (let [board (sup/board-of ["OOOOO"
                               "cO..."
                               "RGBRG"
                               "OROGB"
                               "OGRGB"] {:spawners #{}})
          s (sup/state-of board {:level {:level/id "t" :level/moves 10 :level/seed 1
                                         :level/colors [:coin :gem :clover :goblet]
                                         :level/goals [{:goal/kind :block
                                                        :goal/block :crate
                                                        :goal/count 1}]}})
          ranked (solver/ranked-moves s)
          top (first ranked)]
      (is (pos? (:goal-progress top))
          "the top-ranked move must be one that actually breaks the crate"))))

(deftest a-move-that-is-not-legal-has-no-value
  (let [s (g/new-game level)]
    (is (nil? (solver/move-value s [[0 0] [4 4]])))))

(deftest ranking-is-reproducible
  (let [s (g/new-game level)]
    (is (= (solver/ranked-moves s) (solver/ranked-moves s)))
    (is (= (solver/best-move s) (solver/best-move s)))))

(deftest autoplay-terminates-and-does-not-hit-its-guard
  (let [{:keys [state moves exhausted?]} (solver/autoplay (g/new-game level))]
    (is (not exhausted?))
    (is (contains? #{:won :lost} (:status state)))
    (is (pos? moves))))

(deftest the-bench-level-is-solvable
  (testing "this is the gate `levels.cljs --gate` runs over the whole set"
    (is (solver/solvable? level))))

(deftest a-level-whose-budget-is-too-small-is-reported-unsolvable
  (testing "the negative path has to work too, or the gate passes everything"
    (is (not (solver/solvable?
              (assoc level
                     :level/moves 2
                     :level/goals [{:goal/kind :collect
                                    :goal/color :coin
                                    :goal/count 500}]))))))

(deftest a-single-cascade-can-finish-a-shallow-goal
  (testing "recorded because it is a level-design fact, not an accident: the
            crate bands in this grid all fall to one well-chosen swap, so a
            generous move budget on such a level is wasted"
    (is (>= 3 (:moves (solver/autoplay (g/new-game level)))))))
