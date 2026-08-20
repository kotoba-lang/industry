(ns probe-cae-fem-static
  (:require [cae.solver :as s] [cae.vv] [cae.high-fidelity]))
;; `:axial-bar-fe` は固定-固定バーに正弦分布体積力を与える **検証用**ベンチで、
;; 自分で `:exact-midpoint-m`（解析解）と `:l2-error-m` を返す。したがって
;; 不変条件は「数値が出る」ではなく **メッシュ細分で解析解へ収束する** こと:
;;   (1) 要素を増やすと L2 誤差が単調に減る
;;   (2) 中点変位が解析解へ近づく
;;   (3) 代数残差が機械精度まで落ちている（線形系を実際に解いている証拠）
;;   (4) 弾塑性が降伏を跨いで応力を返す
(try
  (let [fe (fn [n] (s/solve {:solver {:kind :axial-bar-fe}
                             :elements n :length-m 2.0 :area-m2 1.0e-4
                             :youngs-modulus-Pa 2.0e11 :distributed-load-N-m 1000.0}))
        r4 (fe 4) r16 (fe 16) r64 (fe 64)
        err #(:l2-error-m %)
        gap #(Math/abs (- (:midpoint-displacement-m %) (:exact-midpoint-m %)))
        ep (s/solve {:solver {:kind :fem-elastoplastic} :strain 0.01
                     :youngs-modulus-Pa 2.0e11 :yield-stress-Pa 2.5e8 :hardening-Pa 2.0e9})]
    (cond
      (not (> (err r4) (err r16) (err r64)))
      (println "PROBE cae-fem-static FAIL"
               (str "細分で L2 誤差が単調に減らない: " (err r4) " → " (err r16) " → " (err r64)))
      (not (> (gap r4) (gap r64)))
      (println "PROBE cae-fem-static FAIL"
               (str "中点変位が解析解へ近づかない: " (gap r4) " → " (gap r64)))
      (> (:algebraic-residual-norm r64) 1.0e-6)
      (println "PROBE cae-fem-static FAIL"
               (str "代数残差が " (:algebraic-residual-norm r64) " —— 線形系を解けていない"))
      (not (and (map? ep) (pos? (or (:stress-Pa ep) 0))))
      (println "PROBE cae-fem-static FAIL" (str "弾塑性が応力を返さない: " (pr-str (keys ep))))
      :else (println "PROBE cae-fem-static PASS"
                     (str "L2 誤差 " (err r4) "→" (err r64)
                          " 収束比 " (/ (err r4) (err r64))
                          " 残差 " (:algebraic-residual-norm r64)
                          " 弾塑性 σ=" (:stress-Pa ep)))))
  (catch :default ex (println "PROBE cae-fem-static UNMEASURABLE" (.-message ex))))
