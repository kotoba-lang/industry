(ns gpr.fft-test
  (:require [clojure.test :refer [deftest is testing]]
            [gpr.fft :as fft]
            [gpr.math :as m]
            [gpr.result :as r]))

(defn- close? [a b tol] (< (m/abs* (- a b)) tol))

(deftest rejects-non-power-of-two
  (testing "端数長を黙ってゼロ埋めせず err で断る"
    (let [res (fft/fft [1.0 2.0 3.0] [0.0 0.0 0.0])]
      (is (r/err? res))
      (is (= :fft/not-power-of-two (:code (r/error res)))))))

(deftest delta-has-flat-spectrum
  (let [n 16
        x (assoc (vec (repeat n 0.0)) 0 1.0)
        res (fft/fft x (vec (repeat n 0.0)))]
    (is (r/ok? res))
    (let [[re im] (r/value res)]
      (is (every? #(close? % 1.0 1e-9) re))
      (is (every? #(close? % 0.0 1e-9) im)))))

(deftest round-trip
  (let [n 64
        x (mapv (fn [i] (+ (m/sin (* 0.3 i)) (* 0.25 (m/cos (* 1.1 i))))) (range n))
        res (r/bind (fft/fft x (vec (repeat n 0.0)))
                    (fn [[re im]] (fft/ifft re im)))]
    (is (r/ok? res))
    (let [[re _] (r/value res)]
      (is (every? true? (map #(close? %1 %2 1e-9) re x))))))

(deftest sine-peaks-at-its-bin
  (let [n 64 k 5
        x (mapv (fn [i] (m/sin (/ (* 2.0 m/pi k i) n))) (range n))
        [re im] (r/value (fft/fft x (vec (repeat n 0.0))))
        mag (mapv (fn [a b] (m/sqrt (+ (* a a) (* b b)))) re im)
        peak (apply max-key #(nth mag %) (range 1 (quot n 2)))]
    (is (= k peak))))

(deftest envelope-peaks-at-wavelet-centre
  (testing "包絡の山は位相に依らず波形の中心に立つ"
    (let [n 256 dt 0.1 centre 8.0
          x (mapv (fn [i] (let [tau (- (* i dt) centre)
                                a (* m/pi 0.4 tau)]
                            (* (- 1.0 (* 2.0 a a)) (m/exp (- (* a a))))))
                  (range n))
          env (r/value (fft/envelope x))
          idx (apply max-key #(nth env %) (range n))]
      (is (close? (* idx dt) centre 0.2)))))
