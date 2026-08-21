(ns probe-cae-verification
  (:require [cae.solver :as s] [cae.vv] [cae.verification]))
;; ASME V&V の看板は「解いた」ではなく **「その解を信じてよい理由」**。ここで測るのは:
;;   (1) 製造解法（MMS）が **観測次数を実際に出す** —— 離散化が約束する 2 次に乗る
;;   (2) 誤差が本当に落ちている（平らな列から計算した次数は無意味）
;;   (3) **丸め誤差の床の下では次数を捏造しない**。線形要素はある 3 次式に対して
;;       節点厳密で、誤差は 1e-17。その比から出る「次数 -2.38」を不合格として
;;       報告するのは、**最良の結果を最悪と呼ぶこと**
;;   (4) 次数を読めない mesh 列（等比でない・1 本だけ）は拒否
;;   (5) benchmark suite と qualification gate が dispatch できる
(try
  (let [sine (s/solve {:solver {:kind :manufactured-solution} :family :sine})
        poly (s/solve {:solver {:kind :manufactured-solution} :family :polynomial})
        refused (fn [f] (try (do (f) :accepted) (catch :default _ :refused)))
        bad-mesh (refused #(s/solve {:solver {:kind :manufactured-solution} :element-counts [8 12]}))
        one-mesh (refused #(s/solve {:solver {:kind :manufactured-solution} :element-counts [16]}))
        gate-ok (try (map? (s/solve {:solver {:kind :qualification-gate}
                                     :scope {} :checks [] :evidence nil}))
                     (catch :default _ :threw))]
    (cond
      (not= :computed (:status sine))
      (println "PROBE cae-verification FAIL" (str "MMS(sine) が " (pr-str (:status sine))))
      (> (Math/abs (- (:observed-order sine) 2.0)) 0.15)
      (println "PROBE cae-verification FAIL"
               (str "観測次数が " (:observed-order sine) "（2 次を期待）"))
      (not (apply > (:l2-errors sine)))
      (println "PROBE cae-verification FAIL"
               (str "細分で誤差が単調に減らない: " (pr-str (:l2-errors sine))))
      (not= :exact-to-round-off (:status poly))
      (println "PROBE cae-verification FAIL"
               (str "節点厳密な族を " (pr-str (:status poly)) " と報告する"
                    "（丸め誤差の床の下で次数を計算していないか。-2.38 が出る）"))
      (some? (:observed-order poly))
      (println "PROBE cae-verification FAIL"
               (str "床の下で次数 " (:observed-order poly) " を捏造している"))
      (not (:passed? poly))
      (println "PROBE cae-verification FAIL" "厳密な結果を不合格にしている")
      (not (every? #(= :refused %) [bad-mesh one-mesh]))
      (println "PROBE cae-verification FAIL"
               (str "次数を読めない mesh 列を受理する: " (pr-str [bad-mesh one-mesh])))
      (= :threw gate-ok)
      (println "PROBE cae-verification FAIL" "qualification-gate が dispatch できない")
      :else (println "PROBE cae-verification PASS"
                     (str "MMS 観測次数 " (.toFixed (:observed-order sine) 3)
                          "（L2 " (.toExponential (first (:l2-errors sine)) 1) "→"
                          (.toExponential (last (:l2-errors sine)) 1) "）/ 節点厳密な族は "
                          ":exact-to-round-off で次数を出さない / 読めない mesh 列は拒否"))))
  (catch :default ex (println "PROBE cae-verification UNMEASURABLE" (.-message ex))))
