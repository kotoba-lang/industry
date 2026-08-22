(ns gpr.velocity
  "速度・比誘電率・深度の変換と、点状散乱体がつくる双曲線の式。

  時間は two-way travel time (TWT) [ns]、速度 [m/ns]、深度 [m] で通す。
  **単位を関数名に書いてあるのは飾りではない** —— GPR は ns と m/ns、
  地震探査は s と m/s を使うので、混ざると 10^9 の桁で静かに間違える。"
  (:require [gpr.math :as m]
            [gpr.result :as r]))

(def c-m-per-ns
  "真空中の光速 [m/ns]。"
  0.299792458)

(def ^:private plausible-velocity
  "現実の地中で観測される v の範囲 [m/ns]。

  下限は水（εr≈81 → 0.033）、上限は空気（εr=1 → 0.300）。この外に出た推定は
  媒質の値ではなくフィットの失敗を意味するので、候補として報告しない。"
  {:min 0.033 :max 0.300})

(defn velocity-from-permittivity
  "比誘電率 εr [-] → 速度 [m/ns]。εr < 1 は物理的にありえないので err。"
  [er]
  (if (or (nil? er) (< er 1.0))
    (r/err :velocity/permittivity-out-of-range "比誘電率が 1 未満" {:er er})
    (r/ok (/ c-m-per-ns (m/sqrt er)))))

(defn permittivity-from-velocity
  "速度 [m/ns] → 比誘電率 εr [-]。"
  [v]
  (if (or (nil? v) (<= v 0.0) (> v c-m-per-ns))
    (r/err :velocity/out-of-range "速度が 0 以下か真空中の光速より速い" {:v v})
    (r/ok (let [q (/ c-m-per-ns v)] (* q q)))))

(defn plausible-velocity?
  [v] (and (number? v) (<= (:min plausible-velocity) v (:max plausible-velocity))))

(defn depth-m
  "TWT [ns] と速度 [m/ns] → 深度 [m]。"
  [twt-ns v] (/ (* v twt-ns) 2.0))

(defn twt-ns
  "深度 [m] と速度 [m/ns] → TWT [ns]。"
  [depth-m v] (/ (* 2.0 depth-m) v))

(defn hyperbola-twt
  "点状散乱体（apex が `x0` [m]、頂点の TWT が `t0` [ns]）を速度 `v` [m/ns] の
  媒質で `x` [m] から見たときの TWT [ns]。

      t(x) = sqrt( t0^2 + (2 (x - x0) / v)^2 )

  アンテナの送受信間距離を 0 と置いた単純化（common-offset の近似）。
  offset を持つ実機のデータでは浅部で系統的にずれる —— その補正はまだ無い。"
  [t0 v x0 x]
  (let [dx (- x x0)
        a (/ (* 2.0 dx) v)]
    (m/sqrt (+ (* t0 t0) (* a a)))))
