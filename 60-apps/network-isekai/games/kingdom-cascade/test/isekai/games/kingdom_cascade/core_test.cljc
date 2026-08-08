(ns isekai.games.kingdom-cascade.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.kingdom-cascade.board :as b]
            [isekai.games.kingdom-cascade.core :as g]
            [isekai.games.kingdom-cascade.matcher :as m]
            [isekai.games.kingdom-cascade.support :as sup]))

(def level
  {:level/id "kc-test"
   :level/name "Test Chamber"
   :level/moves 20
   :level/seed 20260808
   :level/colors [:coin :gem :clover :goblet]
   :level/goals [{:goal/kind :block :goal/block :crate :goal/count 4}]
   :level/grid ["........"
                "........"
                "........"
                "..cccc.."
                "........"
                "........"
                "........"
                "........"]})

(deftest a-new-game-never-opens-on-a-match
  (let [s (g/new-game level)]
    (is (= :playing (:status s)))
    (is (= 20 (:moves-left s)) "the opening deal is not charged to the player")
    (is (not (m/any-match? (:board s))) "the player gets a settled board")
    (is (seq (g/legal-swaps s)) "but one with something to do")))

(deftest the-opening-deal-is-reproducible
  (is (= (g/digest (g/new-game level)) (g/digest (g/new-game level))))
  (testing "and a different seed gives a different board"
    (is (not= (g/digest (g/new-game level))
              (g/digest (g/new-game (assoc level :level/seed 99)))))))

(deftest an-illegal-swap-returns-the-state-untouched
  (let [s (g/new-game level)]
    (is (identical? s (g/swap s [0 0] [4 4])) "not adjacent")
    (is (identical? s (g/swap s [0 0] [0 0])) "not a pair")
    (is (identical? s (g/swap s [2 3] [3 3])) "both are crates")
    (is (= 20 (:moves-left s)) "and nothing was charged")))

(deftest a-legal-swap-costs-one-move-and-produces-frames
  (let [s (g/new-game level)
        [a bpos] (g/hint s)
        s' (g/swap s a bpos)]
    (is (= 19 (:moves-left s')))
    (is (= 1 (:turn s')))
    (is (= :swap (:frame/kind (first (:frames s')))))
    (is (some #{:match} (map :frame/kind (:frames s'))))
    (is (pos? (:score s')))))

(deftest a-swap-that-only-moves-a-piece-is-illegal
  (let [board (sup/board-of ["ORGB"
                             "GBOR"
                             "RGBO"])
        s (sup/state-of board)]
    (is (not (g/legal-swap? s [0 0] [1 0]))
        "no match would open, and no special is involved")))

(deftest swapping-a-special-is-legal-even-though-it-opens-no-colour-match
  (let [board (sup/board-of ["-RGB"
                             "GBOR"
                             "RGBO"])
        s (sup/state-of board)]
    (is (g/legal-swap? s [0 0] [1 0]) "firing an arrow is the point of the swap")
    (let [s' (g/swap s [0 0] [1 0])]
      (is (some #{:blast} (map :frame/kind (:frames s'))))
      (is (pos? (:score s'))))))

(deftest tapping-a-special-fires-it-in-place
  (let [board (sup/board-of ["ORGB"
                             "-RGB"
                             "GBOR"] {:spawners #{}})
        s (sup/state-of board)
        s' (g/tap s [0 1])]
    (is (= :blast (:frame/kind (first (:frames s')))))
    (is (= 9 (:moves-left s')) "a free detonation would beat using it in a swap")))

(deftest tapping-an-ordinary-piece-does-nothing
  (let [s (sup/state-of (sup/board-of ["ORGB"]))]
    (is (identical? s (g/tap s [0 0])))))

(deftest a-match-next-to-a-crate-credits-the-goal
  (let [board (sup/board-of ["OROO"
                             "cOGB"
                             "GBOR"] {:spawners #{}})
        s (sup/state-of board {:level {:level/id "t" :level/moves 10 :level/seed 1
                                       :level/colors [:coin :gem :clover :goblet]
                                       :level/goals [{:goal/kind :block
                                                      :goal/block :crate
                                                      :goal/count 1}]}})
        ;; dropping the coin from [1 1] into [1 0] completes OOOO across row 0;
        ;; the crate at [0 1] is never matched, it just stands next to one
        s' (g/swap s [1 0] [1 1])]
    (is (nil? (b/block-at (:board s') [0 1])) "the crate took splash and broke")
    (is (= :won (:status s')) "which was the only goal")))

(deftest running-out-of-moves-without-the-goal-loses
  (let [board (sup/board-of ["OROO"
                             "GOGB"
                             "GBOR"] {:spawners #{}})
        s (sup/state-of board {:moves-left 1
                               :level {:level/id "t" :level/moves 1 :level/seed 1
                                       :level/colors [:coin :gem :clover :goblet]
                                       :level/goals [{:goal/kind :block
                                                      :goal/block :crate
                                                      :goal/count 9}]}})
        s' (g/swap s [1 0] [1 1])]
    (is (= 0 (:moves-left s')))
    (is (= :lost (:status s')) "the level has no crates at all to find")))

(deftest a-won-game-stops-accepting-moves
  (let [s (assoc (sup/state-of (sup/board-of ["OROO" "GOGB"])) :status :won)]
    (is (identical? s (g/swap s [1 0] [1 1])))))

(deftest cascades-resolve-to-a-fixed-point
  (testing "the runaway guard must never be what stops a real board"
    (let [s (g/new-game level)]
      (loop [s s, n 0]
        (cond
          (not= :playing (:status s)) (is (contains? #{:won :lost} (:status s)))
          (> n 40) (is false "a 20-move level should end within 40 iterations")
          :else (if-let [[a bpos] (g/hint s)]
                  (let [s' (g/swap s a bpos)]
                    (is (not (:exhausted? s')) "cascade hit its guard")
                    (recur s' (inc n)))
                  (recur (g/reshuffle s) (inc n))))))))

(deftest a-deadlocked-board-reshuffles-into-a-playable-one
  (let [board (sup/board-of ["ORGB"
                             "RGBO"
                             "GBOR"
                             "BORG"] {:spawners #{}})
        s (sup/state-of board)]
    (is (g/deadlocked? s) "a Latin square has no legal swap")
    (let [s' (g/reshuffle s)]
      (is (= :reshuffle (:frame/kind (first (:frames s')))))
      (is (not (g/deadlocked? s')))
      (is (= (frequencies (map #(b/piece-at (:board s) %) (b/positions board)))
             (frequencies (map #(b/piece-at (:board s') %) (b/positions board))))
          "a reshuffle rearranges pieces, it does not invent them"))))

(deftest a-reshuffle-leaves-obstacles-where-the-designer-put-them
  (let [board (sup/board-of ["ORGB"
                             "RcBO"
                             "GBiR"
                             "BORG"] {:spawners #{}})
        s (sup/state-of board)
        s' (g/reshuffle s)]
    (is (= :crate (:kind (b/block-at (:board s') [1 1]))))
    (is (= :ice (:kind (b/cover-at (:board s') [2 2]))))))

(deftest the-hud-reports-the-shortfall-per-goal
  (let [s (g/new-game level)
        h (g/hud s)]
    (is (= 20 (:moves-left h)))
    (is (= [4] (mapv :goal/remaining (:goals h))))))

(deftest replaying-the-same-moves-gives-the-same-digest
  (testing "this is the check the browser and the mobile shell are held to"
    (let [play (fn []
                 (loop [s (g/new-game level), n 0]
                   (if (or (= 6 n) (not= :playing (:status s)))
                     s
                     (if-let [[a bpos] (g/hint s)]
                       (recur (g/swap s a bpos) (inc n))
                       (recur (g/reshuffle s) (inc n))))))]
      (is (= (g/digest (play)) (g/digest (play))))
      (is (number? (g/digest (play)))))))
