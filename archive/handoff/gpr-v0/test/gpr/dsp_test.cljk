(ns gpr.dsp-test
  (:require [clojure.test :refer [deftest is testing]]
            [gpr.dsp :as dsp]
            [gpr.math :as m]
            [gpr.result :as r]
            [gpr.trace :as t]))

(deftest dewow-removes-slow-drift
  (let [n 128
        drift (mapv (fn [i] (+ 5.0 (* 0.05 i))) (range n))
        out (dsp/dewow drift 33)]
    (testing "うねりだけの信号はほぼ 0 になる（端は窓が片側なので緩い）"
      (is (< (reduce max 0.0 (map m/abs* (subvec out 20 (- n 20)))) 0.05)))))

(deftest first-break-finds-the-onset
  (let [x (into (vec (repeat 20 0.0)) (into [1.0 -0.6] (repeat 20 0.0)))]
    (is (= 20 (dsp/first-break-index x 0.5)))
    (testing "全ゼロのトレースは 0 でなく nil を返す（不在を検出値にしない）"
      (is (nil? (dsp/first-break-index (vec (repeat 10 0.0)) 0.5))))))

(deftest shift-preserves-length
  (let [x (vec (range 10))]
    (is (= 10 (count (dsp/shift x 3))))
    (is (= [3 4 5 6 7 8 9 0.0 0.0 0.0] (dsp/shift x 3)))))

(deftest background-removal-kills-a-flat-event
  (let [n 32
        flat (fn [x] (t/a-scan (assoc (vec (repeat n 0.0)) 10 1.0) 0.1 x))
        bs (t/b-scan (mapv flat [0.0 0.1 0.2 0.3]))
        out (dsp/remove-background bs)]
    (is (every? (fn [tr] (every? #(< (m/abs* %) 1e-12) (:gpr/samples tr)))
                (t/traces out)))))

(deftest envelope-of-b-scan-fails-loudly-on-bad-length
  (let [bs (t/b-scan [(t/a-scan (vec (repeat 30 0.0)) 0.1 0.0)])]
    (is (r/err? (dsp/envelope-b-scan bs)))))
