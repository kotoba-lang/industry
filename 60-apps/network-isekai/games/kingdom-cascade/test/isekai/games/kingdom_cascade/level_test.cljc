(ns isekai.games.kingdom-cascade.level-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.kingdom-cascade.board :as b]
            [isekai.games.kingdom-cascade.level :as level]
            [isekai.games.kingdom-cascade.matcher :as m]
            [isekai.games.kingdom-cascade.rng :as rng]))

(def colors [:coin :gem :clover :goblet])

(deftest a-grid-parses-into-a-sparse-board
  (let [board (b/parse-grid ["#..#"
                             ".cc."
                             "#ii#"])]
    (is (= 8 (count (:cells board))))
    (is (not (b/playable? board [0 0])) "`#` is outside the play area")
    (is (= :crate (:kind (b/block-at board [1 1]))))
    (is (= :ice (:kind (b/cover-at board [1 2]))))))

(deftest each-column-gets-a-spawner-at-its-highest-playable-cell
  (let [board (b/parse-grid ["#..#"
                             "...."
                             "...."])]
    (is (= #{[0 1] [1 0] [2 0] [3 1]} (:spawners board)))))

(deftest an-explicit-spawner-replaces-the-automatic-ones
  (let [board (b/parse-grid ["...."
                             "..*."
                             "...."])]
    (is (= #{[2 1]} (:spawners board)))))

(deftest an-unknown-glyph-is-an-error-not-a-hole
  (testing "silently dropping a typo would shrink the board behind the
            designer's back"
    (is (thrown? #?(:clj Exception :cljs js/Error) (b/parse-grid ["..z.."])))))

(deftest the-deal-fills-every-cell-and-opens-no-match
  (doseq [seed [1 2 3 17 20260808]]
    (let [board (b/parse-grid ["........" "........" "........"
                               "........" "........" "........"])
          {:keys [board]} (level/deal board (rng/seed seed) colors)]
      (is (every? #(some? (b/piece-at board %)) (b/positions board))
          (str "seed " seed " left a hole"))
      (is (not (m/any-match? board)) (str "seed " seed " dealt a free match")))))

(deftest the-deal-fills-the-cells-under-covers
  (testing "a covered cell holds a piece — the cover sits on top of it.
            Skipping them leaves no colour to match, which makes a cover goal
            silently unreachable without anything erroring."
    (let [board (b/parse-grid ["..ii.."
                               "..ff.."
                               "......"])
          {:keys [board]} (level/deal board (rng/seed 3) colors)]
      (is (every? #(some? (b/piece-at board %)) (b/positions board)))
      (is (some? (b/color-at board [2 0])) "the iced cell can join a match")
      (is (= :ice (:kind (b/cover-at board [2 0]))) "and is still covered"))))

(deftest the-deal-only-uses-the-level-colours
  (let [board (b/parse-grid ["....." "....."])
        {:keys [board]} (level/deal board (rng/seed 5) [:coin :gem :clover])]
    (is (= #{:coin :gem :clover}
           (into #{} (map #(b/color-at board %) (b/positions board)))))))

(deftest goals-count-only-their-own-events
  (let [goals [{:goal/kind :block :goal/block :crate :goal/count 2}
               {:goal/kind :collect :goal/color :coin :goal/count 3}]
        progress (level/credit goals {} [{:block :crate} {:block :stone}
                                         {:color :coin} {:color :gem}])]
    (is (= {0 1, 1 1} progress))
    (is (not (level/all-goals-met? goals progress)))
    (is (= [1 2] (level/remaining goals progress)))
    (is (level/all-goals-met?
         goals
         (level/credit goals progress [{:block :crate} {:color :coin} {:color :coin}])))))

(deftest validation-catches-a-goal-the-grid-cannot-satisfy
  (let [problems (level/validate {:level/moves 10
                                  :level/colors colors
                                  :level/grid ["..c.." "....."]
                                  :level/goals [{:goal/kind :block
                                                 :goal/block :crate
                                                 :goal/count 5}]})]
    (is (= [:unreachable-goal] (mapv :problem problems)))
    (is (= 1 (:available (first problems))))))

(deftest a-well-formed-level-validates-clean
  (is (empty? (level/validate {:level/moves 10
                               :level/colors colors
                               :level/grid ["..c.." "..c.."]
                               :level/goals [{:goal/kind :block
                                              :goal/block :crate
                                              :goal/count 2}]}))))

(deftest the-rng-is-reproducible-and-stays-in-range
  (is (= (rng/draw (rng/seed 42) 6) (rng/draw (rng/seed 42) 6)))
  (let [[v _] (reduce (fn [[_ s] _] (rng/draw s 6)) [nil (rng/seed 1)] (range 500))]
    (is (and (>= v 0) (< v 6))))
  (testing "and every intermediate stays inside exact double range, so the
            JVM and ClojureScript cannot drift apart"
    (loop [s (rng/seed 20260808) n 0]
      (when (< n 2000)
        (is (< s rng/modulus))
        (recur (rng/step s) (inc n))))))
