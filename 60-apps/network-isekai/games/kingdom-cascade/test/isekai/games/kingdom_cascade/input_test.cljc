(ns isekai.games.kingdom-cascade.input-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.kingdom-cascade.core :as g]
            [isekai.games.kingdom-cascade.input :as input]
            [isekai.games.kingdom-cascade.render-ir :as ir]
            [isekai.games.kingdom-cascade.support :as sup]))

(def board (sup/board-of ["OOOO" "OOOO" "OOOO" "OOOO"]))

(defn- centre-of [[x y]]
  (let [pitch (+ ir/cell-size ir/gutter)]
    [(+ (* x pitch) (quot ir/cell-size 2))
     (+ (* y pitch) (quot ir/cell-size 2))]))

(deftest a-press-alone-commits-to-nothing
  (let [[ui action] (input/pointer-down input/idle board (centre-of [1 1]))]
    (is (nil? action))
    (is (= [1 1] (:from (:drag ui))))))

(deftest a-press-outside-the-board-starts-no-gesture
  (let [[ui action] (input/pointer-down input/idle board [10000 10000])]
    (is (nil? action))
    (is (nil? (:drag ui)))))

(deftest dragging-past-the-threshold-swaps-with-that-neighbour
  (let [start (centre-of [1 1])
        [ui _] (input/pointer-down input/idle board start)]
    (testing "right"
      (let [[_ action] (input/pointer-move ui board (mapv + start [40 0]))]
        (is (= [:swap [1 1] [2 1]] action))))
    (testing "left"
      (let [[_ action] (input/pointer-move ui board (mapv + start [-40 0]))]
        (is (= [:swap [1 1] [0 1]] action))))
    (testing "down"
      (let [[_ action] (input/pointer-move ui board (mapv + start [0 40]))]
        (is (= [:swap [1 1] [1 2]] action))))
    (testing "up"
      (let [[_ action] (input/pointer-move ui board (mapv + start [0 -40]))]
        (is (= [:swap [1 1] [1 0]] action))))))

(deftest a-drag-commits-on-the-move-not-on-release
  (testing "waiting for release makes a fast flick feel dropped"
    (let [start (centre-of [1 1])
          [ui _] (input/pointer-down input/idle board start)
          [ui' action] (input/pointer-move ui board (mapv + start [40 0]))]
      (is (= [:swap [1 1] [2 1]] action))
      (is (nil? (:drag ui')) "and the same press cannot fire a second swap")
      (is (= [input/idle nil] (input/pointer-up ui' board start))))))

(deftest a-small-wobble-is-still-a-tap
  (let [start (centre-of [1 1])
        [ui _] (input/pointer-down input/idle board start)
        [ui' action] (input/pointer-move ui board (mapv + start [4 3]))]
    (is (nil? action) "below the threshold")
    (is (= [:tap [1 1]] (second (input/pointer-up ui' board start))))))

(deftest a-diagonal-drag-picks-one-neighbour-rather-than-none
  (let [start (centre-of [1 1])
        [ui _] (input/pointer-down input/idle board start)
        [_ action] (input/pointer-move ui board (mapv + start [40 38]))]
    (is (= [:swap [1 1] [2 1]] action) "the larger component wins outright")))

(deftest a-cancelled-gesture-produces-nothing
  (let [[ui _] (input/pointer-down input/idle board (centre-of [1 1]))]
    (is (= [input/idle nil] (input/pointer-cancel ui)))))

(deftest a-move-with-no-gesture-in-progress-is-ignored
  (is (= [input/idle nil] (input/pointer-move input/idle board [10 10]))))

(deftest actions-drive-the-game-through-apply-action
  (let [lvl {:level/id "kc-input" :level/moves 10 :level/seed 5
             :level/colors [:coin :gem :clover :goblet]
             :level/goals [{:goal/kind :collect :goal/color :coin :goal/count 3}]
             :level/grid ["......" "......" "......" "......" "......" "......"]}
        state (g/new-game lvl)
        [a bpos] (g/hint state)
        after (input/apply-action state [:swap a bpos] g/swap g/tap)]
    (is (= 9 (:moves-left after)))
    (testing "and an unknown action leaves the state alone"
      (is (identical? state (input/apply-action state nil g/swap g/tap)))
      (is (identical? state (input/apply-action state [:wat] g/swap g/tap))))))
