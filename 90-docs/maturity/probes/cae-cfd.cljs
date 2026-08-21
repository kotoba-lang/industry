(ns probe-cae-cfd (:require [kami-cfd.duct :as duct]))
;; 不変条件: 力駆動の平面ポアズイユ流を LBM で解き、**解析解**
;; u(y) = g/(2ν)·y·(h−y) と突き合わせて相対 L2 誤差が許容内に入ること。
;; ⚠ **この probe は遅い**（ny=21 で 4000 ステップ、負荷 100 の機械で 3 分強）。
;; 刻みを減らすと定常に達せず、L2 が 1.4% → 4.8% → 7.7% → 10.9% と悪化して
;; 「合っている」と言い切れなくなる（2026-08-21 実測）。決定的な合致の方を採り、
;; audit 側の probe timeout を 600s にした。速い検査より、答えの出る検査を選ぶ。
;;
;; 「解が返る」ではなく「既知解に合う」を検査する。この検証自体が repo に在り、
;; 実装のコメントは「これが無かったから密閉箱で流れが発達しない版が landed した」と
;; 書いている —— 検証が実際に噛んだ痕跡なので、それが今も噛むことを確かめる。
(try
  (let [r (duct/validate-poiseuille 21 1.0e-6 0.05 4000 0.05)]
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
