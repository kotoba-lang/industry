(ns gpr.fft
  "Radix-2 DIT FFT と、そこから作る解析信号（Hilbert）・包絡。

  依存ゼロの純 `.cljc`。**長さは 2 の冪に限り、端数を黙ってゼロ埋めしない** ——
  測れなかった入力を測れた入力と同じ形で返さないため、`gpr.result/err` で断る。
  ゼロ埋めが要るなら呼び出し側が `pad-to-pow2` を明示的に呼ぶ。"
  (:require [gpr.math :as m]
            [gpr.result :as r]))

(defn pow2?
  [n] (and (int? n) (pos? n) (zero? (bit-and n (dec n)))))

(defn next-pow2
  [n] (loop [p 1] (if (>= p n) p (recur (bit-shift-left p 1)))))

(defn pad-to-pow2
  "末尾を 0 で埋めて長さを 2 の冪にする。**呼ぶと信号が変わる**ので、
  スペクトルの分解能を語るときはこの呼び出しを明示すること。"
  [xs]
  (let [v (vec xs) n (count v) p (next-pow2 n)]
    (into v (repeat (- p n) 0.0))))

(defn- transform!
  "in-place Cooley-Tukey。`sign` -1 が順変換、+1 が逆変換（正規化はしない）。"
  [xr xi n sign]
  ;; bit-reversal permutation
  (loop [i 1 j 0]
    (when (< i n)
      (let [j (loop [bit (bit-shift-right n 1) j j]
                (if (zero? (bit-and j bit))
                  (bit-or j bit)
                  (recur (bit-shift-right bit 1) (bit-xor j bit))))]
        (when (< i j)
          (let [tr (aget xr i) ti (aget xi i)]
            (aset xr i (aget xr j)) (aset xi i (aget xi j))
            (aset xr j tr)          (aset xi j ti)))
        (recur (inc i) j))))
  ;; butterflies
  (loop [len 2]
    (when (<= len n)
      (let [half (quot len 2)
            ang (/ (* sign 2.0 m/pi) len)
            wr (m/cos ang)
            wi (m/sin ang)]
        (loop [i 0]
          (when (< i n)
            (loop [k 0 cr 1.0 ci 0.0]
              (when (< k half)
                (let [a (+ i k)
                      b (+ a half)
                      br (aget xr b) bi (aget xi b)
                      vr (- (* br cr) (* bi ci))
                      vi (+ (* br ci) (* bi cr))
                      ur (aget xr a) ui (aget xi a)]
                  (aset xr a (+ ur vr)) (aset xi a (+ ui vi))
                  (aset xr b (- ur vr)) (aset xi b (- ui vi))
                  (recur (inc k) (- (* cr wr) (* ci wi)) (+ (* cr wi) (* ci wr))))))
            (recur (+ i len))))
        (recur (bit-shift-left len 1)))))
  [xr xi])

(defn fft
  "順 DFT。`re` `im` は同じ長さ 2^k の数値ベクタ。→ `[:ok [re' im']]`"
  [re im]
  (let [n (count re)]
    (cond
      (not= n (count im)) (r/err :fft/length-mismatch "re と im の長さが違う" {:re n :im (count im)})
      (not (pow2? n))     (r/err :fft/not-power-of-two "長さが 2 の冪でない（pad-to-pow2 を明示的に呼ぶこと）" {:n n})
      :else (let [[xr xi] (transform! (m/arr re) (m/arr im) n -1)]
              (r/ok [(vec xr) (vec xi)])))))

(defn ifft
  "逆 DFT（1/n 正規化つき）。→ `[:ok [re' im']]`"
  [re im]
  (let [n (count re)]
    (cond
      (not= n (count im)) (r/err :fft/length-mismatch "re と im の長さが違う" {:re n :im (count im)})
      (not (pow2? n))     (r/err :fft/not-power-of-two "長さが 2 の冪でない" {:n n})
      :else (let [[xr xi] (transform! (m/arr re) (m/arr im) n 1)]
              (r/ok [(mapv #(/ % n) (vec xr)) (mapv #(/ % n) (vec xi))])))))

(defn envelope
  "解析信号の絶対値（包絡）。実数信号 `xs`（長さ 2^k）→ `[:ok [amplitude ...]]`。

  負の周波数を落として正の周波数を 2 倍する標準的な Hilbert 経路。
  A-scan の到達時刻を拾うのに使う —— 生波形の極値は波形の位相に依存して
  半周期ずれるが、包絡の山は位相に依らない。"
  [xs]
  (let [n (count xs)]
    (r/bind
     (fft (vec xs) (vec (repeat n 0.0)))
     (fn [[fr fi]]
       (let [half (quot n 2)
             mask (fn [i] (cond (zero? i) 1.0
                                (= i half) 1.0
                                (< i half) 2.0
                                :else 0.0))
             hr (mapv (fn [i] (* (mask i) (nth fr i))) (range n))
             hi (mapv (fn [i] (* (mask i) (nth fi i))) (range n))]
         (r/fmap (ifft hr hi)
                 (fn [[ar ai]]
                   (mapv (fn [i] (m/sqrt (+ (* (nth ar i) (nth ar i))
                                            (* (nth ai i) (nth ai i)))))
                         (range n)))))))))
