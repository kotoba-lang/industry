(ns diff-test
  "Unit pins for #1754 image diff — no Chromium, synthetic RGBA buffers."
  (:require [diff-core :as diff]))

(def failures (atom 0))
(defn- fail! [label detail]
  (swap! failures inc)
  (println "  FAIL:" label)
  (when detail (println "       " detail)))
(defn- ok! [label] (println "  ok  " label))

(defn- solid
  "W×H solid RGBA."
  [W H r g b]
  (let [out (js/Uint8ClampedArray. (* W H 4))]
    (dotimes [p (* W H)]
      (let [i (* p 4)]
        (aset out i r)
        (aset out (+ i 1) g)
        (aset out (+ i 2) b)
        (aset out (+ i 3) 255)))
    out))

(defn- stamp-rect!
  "Paint [x y w h] with colour into buffer (mutates)."
  [buf W x y w h r g b]
  (dotimes [dy h]
    (dotimes [dx w]
      (let [i (* 4 (+ (+ x dx) (* (+ y dy) W)))]
        (aset buf i r)
        (aset buf (+ i 1) g)
        (aset buf (+ i 2) b)
        (aset buf (+ i 3) 255))))
  buf)

(println "== identical inputs → zero changed pixels")
(let [W 32 H 24
      a (solid W H 10 20 30)
      s (diff/compare-rgba W H a a 0)]
  (if (and (zero? (:changed-pixels s))
           (zero? (:max-delta s))
           (empty? (:regions s)))
    (ok! (str "identical " W "x" H))
    (fail! "identical" (pr-str s))))

(println "== one-instance colour change → single region covering that rect only")
(let [W 64 H 48
      a (solid W H 40 40 40)
      b (.slice a)
      ;; "instance" rect away from edges
      rx 20 ry 12 rw 8 rh 10
      _ (stamp-rect! b W rx ry rw rh 200 10 10)
      s (diff/compare-rgba W H a b 0)
      regs (:regions s)
      r0 (first regs)]
  (cond (not= 1 (count regs))
        (fail! "region-count" (pr-str regs))
        (not= (* rw rh) (:changed-pixels s))
        (fail! "changed-pixels" (str (:changed-pixels s) " want " (* rw rh)))
        (not= 160 (:max-delta s)) ; |200-40|
        (fail! "max-delta" (str (:max-delta s)))
        (not= [rx ry rw rh] r0)
        (fail! "region-box" (str (pr-str r0) " want " [rx ry rw rh]))
        :else
        (ok! (str "colour change → " (pr-str r0) " pixels=" (:changed-pixels s)))))

(println "== threshold swallows LSB noise")
(let [W 16 H 16
      a (solid W H 100 100 100)
      b (.slice a)
      _ (aset b 0 101) ; +1 on R of pixel 0
      s0 (diff/compare-rgba W H a b 0)
      s1 (diff/compare-rgba W H a b 1)]
  (if (and (= 1 (:changed-pixels s0)) (zero? (:changed-pixels s1)))
    (ok! "threshold 1 quiets Δ=1")
    (fail! "threshold" (pr-str {:s0 s0 :s1 s1}))))

(println "== two distant changes → two regions")
(let [W 40 H 40
      a (solid W H 0 0 0)
      b (.slice a)
      _ (stamp-rect! b W 2 2 3 3 255 0 0)
      _ (stamp-rect! b W 30 30 2 2 0 255 0)
      s (diff/compare-rgba W H a b 0)]
  (if (= 2 (count (:regions s)))
    (ok! (str "two regions " (pr-str (:regions s))))
    (fail! "two-regions" (pr-str s))))

(println)
(if (zero? @failures)
  (do (println "diff_test OK") (js/process.exit 0))
  (do (println (str "diff_test FAILED (" @failures ")")) (js/process.exit 1)))
