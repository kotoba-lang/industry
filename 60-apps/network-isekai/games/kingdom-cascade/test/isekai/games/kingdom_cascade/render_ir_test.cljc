(ns isekai.games.kingdom-cascade.render-ir-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.kingdom-cascade.core :as g]
            [isekai.games.kingdom-cascade.render-ir :as ir]
            [isekai.games.kingdom-cascade.support :as sup]))

(def level
  {:level/id "kc-test"
   :level/name "Test Chamber"
   :level/moves 20
   :level/seed 20260808
   :level/colors [:coin :gem :clover :goblet]
   :level/goals [{:goal/kind :block :goal/block :crate :goal/count 4}]
   :level/grid ["......" "......" "..cc.." "......" "......" "......"]})

(deftest a-scene-carries-everything-a-renderer-needs
  (let [s (g/new-game level)
        scene (ir/scene s)]
    (is (= :2d (:scene/dimension scene)))
    (is (= :orthographic (:camera/kind (:scene/camera scene))))
    (is (= "kingdom-cascade/kc-test" (:scene/id scene)))
    (is (some? (:render/atlas scene)))
    (is (seq (:render/sprite2d scene)))
    (is (some? (:art/preset scene)) "the chapter look is a named base layer")))

(deftest every-sprite-names-a-frame-that-exists-in-the-atlas
  (testing "a typo here draws a blank tile at runtime and nothing errors"
    (let [s (g/new-game level)
          scene (ir/scene s)
          frames (set (keys (:atlas/frames (:render/atlas scene))))]
      (is (every? #(contains? frames (:sprite/frame %)) (:render/sprite2d scene))))))

(deftest sprites-are-emitted-in-a-stable-order
  (let [board (sup/board-of ["ORG" "cB." "iOR"])]
    (is (= (ir/board->sprites board) (ir/board->sprites board)))))

(deftest a-block-hides-the-piece-and-a-cover-does-not
  (let [board (sup/board-of ["c" "i"])
        layers (group-by :sprite/layer (ir/board->sprites board))]
    (is (= [:crate] (mapv :sprite/frame (get layers 30))))
    (is (= [:ice] (mapv :sprite/frame (get layers 40))))
    (is (= [:coin] (mapv :sprite/frame (get layers 20)))
        "the iced coin still draws under its cover; the crate cell has no piece")))

(deftest damaged-stone-draws-a-different-frame
  (testing "a two-hit block that looks the same after one hit reads as a bug"
    (let [board (sup/board-of ["s"])
          cracked (assoc-in board [:cells [0 0] :block :hp] 1)]
      (is (= [:stone] (mapv :sprite/frame (filter #(= 30 (:sprite/layer %))
                                                  (ir/board->sprites board)))))
      (is (= [:stone-cracked] (mapv :sprite/frame (filter #(= 30 (:sprite/layer %))
                                                          (ir/board->sprites cracked))))))))

(deftest the-timeline-covers-every-frame-of-the-last-action
  (let [s (g/new-game level)
        [a bpos] (g/hint s)
        s' (g/swap s a bpos)
        tl (ir/timeline s')]
    (is (= (count (:frames s')) (count tl)))
    (is (every? pos? (map :step/duration-ms tl)))
    (is (every? #(seq (:render/sprite2d (:step/scene %))) tl))
    (is (= :swap (:step/kind (first tl))))))

(deftest an-action-that-changed-nothing-has-an-empty-timeline
  (is (empty? (ir/timeline (g/new-game level)))))

(deftest hit-test-is-the-exact-inverse-of-the-layout
  (let [board (sup/board-of ["OO" "OO"])]
    (is (= [0 0] (ir/hit-test board [0 0])))
    (is (= [1 1] (ir/hit-test board [110 110])))
    (is (nil? (ir/hit-test board [-1 0])) "off the top-left")
    (is (nil? (ir/hit-test board [1000 0])) "off the board entirely")
    (is (nil? (ir/hit-test board [98 10]))
        "inside the gutter between two tiles, which belongs to neither")))

(deftest the-hud-model-reports-goals-without-styling-them
  (let [s (g/new-game level)
        hud (:render/hud (ir/scene s))]
    (is (= "Test Chamber" (:hud/level hud)))
    (is (= 20 (:hud/moves-left hud)))
    (is (= [4] (mapv :goal/remaining (:hud/goals hud))))
    (is (every? string? (vals (:hud/tokens hud)))
        "token names only — the app must not re-derive their values")))
