(ns gpr.synth
  "合成 B-scan。**テストと開発のためだけにある。**

  ADR-2608750000 の Open question 1 のとおり、実測データはまだ 1 本も無い。
  合成データで確かめられるのは「実装が自分の式どおりに動くこと」だけで、
  **閾値が現場で妥当かどうかは合成では決して分からない** —— 合成の仮定を
  検証してしまうため。閾値の既定値をこの repo に焼かないのはそのため。"
  (:require [gpr.math :as m]
            [gpr.trace :as t]
            [gpr.velocity :as vel]))

(defn ricker
  "Ricker（Mexican hat）ウェーブレット。`f0` は主周波数 [GHz]（GPR の 400 MHz は 0.4）、
  `tau` は中心からの時間差 [ns]。"
  [f0 tau]
  (let [a (* m/pi f0 tau)
        a2 (* a a)]
    (* (- 1.0 (* 2.0 a2)) (m/exp (- a2)))))

(defn point-scatterer-b-scan
  "点状散乱体 1 つが作る双曲線を持つ合成 B-scan。

  `opts`: `:v` 速度 [m/ns] / `:x0` apex 位置 [m] / `:depth-m` 深さ /
  `:f0-ghz` 主周波数 / `:n-samples`（2 の冪）/ `:dt-ns` / `:x-from` `:x-to` `:dx`
  / `:noise` 一様ノイズの振幅（決定論的な擬似乱数）。"
  [{:keys [v x0 depth-m f0-ghz n-samples dt-ns x-from x-to dx noise]
    :or {noise 0.0}}]
  (let [t0 (vel/twt-ns depth-m v)
        xs (vec (range x-from (+ x-to (/ dx 2.0)) dx))
        ;; 決定論的な擬似乱数（テストが実行ごとに揺れないこと自体が要件）
        rnd (fn [i j] (let [s (mod (* (+ (* i 7919) (* j 104729)) 2654435761) 1000)]
                        (- (/ s 500.0) 1.0)))]
    (t/b-scan
     (vec (map-indexed
           (fn [j x]
             (let [tx (vel/hyperbola-twt t0 v x0 x)
                   ;; 幾何拡散で振幅が落ちる（apex から離れるほど弱い）
                   amp (/ t0 (max t0 tx))]
               (t/a-scan
                (mapv (fn [i]
                        (let [tt (* i dt-ns)]
                          (+ (* amp (ricker f0-ghz (- tt tx)))
                             (* noise (rnd i j)))))
                      (range n-samples))
                dt-ns x)))
           xs)))))
