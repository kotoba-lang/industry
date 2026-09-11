(ns gpr.math
  "ホスト差を 1 箇所に閉じ込める数学プリミティブ。

  `Math/*` を各 namespace で reader-conditional するとホスト分岐が散らばるので、
  ここだけが `#?(:clj ... :cljs ...)` を持つ。")

(def pi #?(:clj Math/PI :cljs js/Math.PI))

(defn cos [x] #?(:clj (Math/cos (double x)) :cljs (js/Math.cos x)))
(defn sin [x] #?(:clj (Math/sin (double x)) :cljs (js/Math.sin x)))
(defn exp [x] #?(:clj (Math/exp (double x)) :cljs (js/Math.exp x)))
(defn sqrt [x] #?(:clj (Math/sqrt (double x)) :cljs (js/Math.sqrt x)))
(defn pow [x y] #?(:clj (Math/pow (double x) (double y)) :cljs (js/Math.pow x y)))
(defn abs* [x] #?(:clj (Math/abs (double x)) :cljs (js/Math.abs x)))

(defn mean
  [xs] (if (seq xs) (/ (reduce + 0.0 xs) (count xs)) 0.0))

(defn arr
  "数値の seq を、ホストの可変な数値配列にする（FFT の in-place 用）。"
  [xs]
  #?(:clj (double-array (map double xs))
     :cljs (let [v (vec xs) a (make-array (count v))]
             (dotimes [i (count v)] (aset a i (double (nth v i))))
             a)))

(defn zeros
  [n]
  #?(:clj (double-array n)
     :cljs (let [a (make-array n)] (dotimes [i n] (aset a i 0.0)) a)))
