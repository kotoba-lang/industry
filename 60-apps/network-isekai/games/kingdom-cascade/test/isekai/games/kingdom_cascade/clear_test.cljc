(ns isekai.games.kingdom-cascade.clear-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.kingdom-cascade.board :as b]
            [isekai.games.kingdom-cascade.clear :as clear]
            [isekai.games.kingdom-cascade.support :as sup]))

(deftest clearing-removes-pieces-and-reports-colours
  (let [board (sup/board-of ["OOO"])
        r (clear/apply-clear board [[0 0] [1 0] [2 0]])]
    (is (= 3 (count (:pieces r))))
    (is (every? #(b/vacant? (:board r) %) [[0 0] [1 0] [2 0]]))
    (is (= [{:color :coin} {:color :coin} {:color :coin}] (:collected r)))))

(deftest a-cover-absorbs-the-clear-and-shields-the-piece
  (let [board (sup/board-of ["i"])
        r (clear/apply-clear board [[0 0]])]
    (is (nil? (b/cover-at (:board r) [0 0])) "one hit removes single-layer ice")
    (is (some? (b/piece-at (:board r) [0 0])) "the piece under it survives")
    (is (= {[0 0] :ice} (:covers r)))
    (is (empty? (:pieces r)))))

(deftest a-two-layer-cover-needs-two-clears
  (let [board (sup/board-of ["f"])
        r1 (clear/apply-clear board [[0 0]])]
    (is (= 1 (:hp (b/cover-at (:board r1) [0 0]))) "frost cracks but holds")
    (is (empty? (:covers r1)) "nothing destroyed yet, so nothing to count")
    (let [r2 (clear/apply-clear (:board r1) [[0 0]])]
      (is (nil? (b/cover-at (:board r2) [0 0])))
      (is (= {[0 0] :frost} (:covers r2))))))

(deftest a-block-takes-splash-from-a-neighbouring-clear
  (testing "matching next to a crate breaks it without matching on it"
    (let [board (sup/board-of ["OOO"
                               "c.."])
          r (clear/apply-clear board [[0 0] [1 0] [2 0]])]
      (is (= {[0 1] :crate} (:blocks r)))
      (is (nil? (b/block-at (:board r) [0 1])))
      (is (= 1 (count (filter :block (:collected r))))))))

(deftest a-block-takes-only-one-hit-per-clear
  (testing "a crate touching three cleared cells is still one hit, so a
            single-hit crate cannot absorb work meant for a two-hit stone"
    (let [board (sup/board-of ["OOO"
                               ".s."])
          r (clear/apply-clear board [[0 0] [1 0] [2 0]])]
      (is (= 1 (:hp (b/block-at (:board r) [1 1])))
          "stone has 2 hp and took exactly one hit despite three neighbours")
      (is (empty? (:blocks r))))))

(deftest specials-caught-in-a-clear-are-reported-not-detonated
  (let [board (sup/board-of ["-@"])
        r (clear/apply-clear board [[0 0] [1 0]])]
    (is (= [[[0 0] :arrow-h] [[1 0] :bomb]] (:triggered r))
        "chaining is the caller's job, so each detonation can be its own frame")
    (is (= [{:power :arrow-h} {:power :bomb}] (:collected r)))))

(deftest clearing-an-absent-cell-is-a-no-op
  (let [board (sup/board-of ["O#O"])
        r (clear/apply-clear board [[0 0] [1 0] [2 0] [9 9]])]
    (is (= 2 (count (:pieces r))))))
