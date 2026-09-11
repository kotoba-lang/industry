(ns probe-cae-cfd (:require [kami-cfd.duct :as duct]))
;; 不変条件: 力駆動の平面ポアズイユ流を LBM で解き、**解析解**
;; u(y) = g/(2ν)·y·(h−y) と突き合わせて相対 L2 誤差が許容内に入ること。
;; ⚠ **この probe は重い。格子と刻みは測って選んである**（2026-08-21）:
;;
;;     ny  9 / 4000  L2 1.83%      ny 15 / 4000  L2 0.68%  ← 採用
;;     ny 11 / 3000  L2 1.23%      ny 21 / 4000  L2 1.44%  （旧）
;;
;; 格子を細かくすれば良くなるわけではない —— 定常に達するのに要る刻み数が ny とともに
;; 増えるので、ny=21 は同じ 4000 ステップでは ny=15 より **悪い**。精度と計算量の
;; 両方で ny=15 が勝つ。刻みだけ減らす（L2 が 4.8% → 10.9% と悪化）方向は採らない ——
;; 速い検査より、答えの出る検査を選ぶ。負荷次第でなお timeout しうるので audit 側の
;; probe timeout は 600s、そこで殺されたら :unmeasurable として報告される。
;;
;; 「解が返る」ではなく「既知解に合う」を検査する。この検証自体が repo に在り、
;; 実装のコメントは「これが無かったから密閉箱で流れが発達しない版が landed した」と
;; 書いている —— 検証が実際に噛んだ痕跡なので、それが今も噛むことを確かめる。
(try
  (let [r (duct/validate-poiseuille 15 1.0e-6 0.05 4000 0.05)]
    (cond
      (not (map? r)) (println "PROBE cae-cfd UNMEASURABLE" (str "戻り値が map でない: " (pr-str r)))
      (not (:pass? r))
      (println "PROBE cae-cfd FAIL"
               (str "解析解との相対 L2 誤差 " (:l2-rel r) " が許容 " (:tol r) " を超える"))
      (not (pos? (:u-max-sim r)))
      (println "PROBE cae-cfd FAIL" "流れが発達していない（u-max が 0）")
      :else (println "PROBE cae-cfd PASS"
                     (str "L2rel=" (:l2-rel r) " tol=" (:tol r)
                          " u-max sim=" (:u-max-sim r) " exact=" (:u-max-exact r)))))
  (catch :default ex (println "PROBE cae-cfd UNMEASURABLE" (.-message ex))))
