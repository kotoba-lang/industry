(ns isekai.games.kingdom-cascade.matcher-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.kingdom-cascade.matcher :as m]
            [isekai.games.kingdom-cascade.support :as sup]))

(deftest finds-a-plain-three
  (let [board (sup/board-of ["OOOR"
                             "RGBG"
                             "GBRB"])
        [g & more] (m/find-groups board)]
    (is (empty? more) "one group, not three overlapping ones")
    (is (= :coin (:color g)))
    (is (= [[0 0] [1 0] [2 0]] (:cells g)))
    (is (nil? (:reward g)) "a three earns nothing")))

(deftest four-in-a-row-earns-an-arrow-along-the-row
  (let [board (sup/board-of ["OOOOR"
                             "RGBGB"
                             "GBRBG"])
        [g] (m/find-groups board)]
    (is (= 4 (count (:cells g))))
    (is (= :arrow-h (:reward g))))
  (testing "and a vertical four earns the vertical arrow"
    (let [board (sup/board-of ["ORG"
                               "OBG"
                               "OGB"
                               "OBG"
                               "RGB"])
          [g] (m/find-groups board)]
      (is (= :arrow-v (:reward g))))))

(deftest five-in-a-line-earns-a-prism
  (let [board (sup/board-of ["OOOOO"
                             "RGBGB"
                             "GBRBG"])
        [g] (m/find-groups board)]
    (is (= 5 (count (:cells g))))
    (is (= :prism (:reward g)))))

(deftest crossing-runs-merge-into-one-bent-group
  (testing "an L earns a bomb, not two separate threes"
    (let [board (sup/board-of ["OOO"
                               "RGO"
                               "GBO"])
          groups (m/find-groups board)]
      (is (= 1 (count groups)) "the horizontal and vertical runs are one match")
      (is (= 5 (count (:cells (first groups)))))
      (is (= :bomb (:reward (first groups))))))
  (testing "a T earns a bomb too"
    (let [board (sup/board-of ["OOOOO"
                               "RGOGB"
                               "GBOBG"])
          [g] (m/find-groups board)]
      (is (= 7 (count (:cells g))))
      (is (= :bomb (:reward g))))))

(deftest specials-are-colour-neutral
  (testing "an arrow between two coins does not bridge them into a match"
    (let [board (sup/board-of ["O-O"
                               "RGB"
                               "GBR"])]
      (is (empty? (m/find-groups board))))))

(deftest a-piece-under-ice-still-matches
  (testing "clearing an iced piece is how the ice comes off, so it must match"
    (let [board (sup/board-of ["OiO"
                               "RGB"
                               "GBR"])]
      (is (= 1 (count (m/find-groups board)))
          "ice at [1 0] covers a coin, completing the row"))))

(deftest blocks-never-join-a-match
  (let [board (sup/board-of ["OcO"
                             "RGB"
                             "GBR"])]
    (is (empty? (m/find-groups board)))))

(deftest a-gap-in-a-merged-group-is-not-counted-as-extent
  (testing "two threes on one row joined by a vertical run must not read as a
            single long horizontal run"
    (let [board (sup/board-of ["OOOROOO"
                               "###O###"
                               "###O###"])
          groups (m/find-groups board)]
      (is (= 2 (count (filter #(= :coin (:color %)) groups)))
          "the two coin runs are separate; nothing joins them"))))

(deftest promotion-lands-on-the-cell-the-player-touched
  (let [board (sup/board-of ["OOOO"
                             "RGBG"])
        [g] (m/find-groups board)]
    (is (= [2 0] (m/promotion-site g [[2 0] [2 1]])))
    (is (= [0 0] (m/promotion-site g []))
        "with nothing touched it falls back to reading order")))
