(ns probe-dcc-particles-vfx
  (:require [kotoba.physics.particles :as p] [clojure.string :as str]))
;; パーティクルは記帳で壊れる。見るのは絵ではなく次の 4 つ:
;;   (1) **放出はレートであってフレーム当たりの個数ではない** —— 秒 30 個・60fps は
;;       フレーム 0.5 個で、floor(rate·dt) 方式は永久に 0 個放出する（数週間
;;       「調整の問題」に見える）。端数を繰り越せば、同じ時間の放出数は
;;       30/60/240 steps/s のどれでも同じになる
;;   (2) 積分器には**閉じた式**がある。半陰的 Euler は
;;       x_n = x_0 + n·dt·v_0 + g·dt²·n(n+1)/2 に**厳密に**一致する
;;   (3) 定常個数 = rate × lifetime
;;   (4) **seed は約束**。同じ seed が JVM と CLJS で同じ粒子を出す（golden 値）
(try
  (let [abs* (fn [x] (js/Math.abs (double x)))
        r9 (fn [x] (/ (js/Math.round (* 1e9 (double x))) 1e9))
        counts (mapv (fn [dt]
                       (let [s (p/system {:rate 30.0 :lifetime 100.0 :seed 7})
                             n (js/Math.round (/ 2.0 dt))
                             [_ d] (p/simulate s {:dt dt :gravity [0.0 0.0 0.0]} n)]
                         (:particles/emitted d)))
                     [(/ 1.0 30.0) (/ 1.0 60.0) (/ 1.0 240.0)])
        x0 [0.0 0.0 0.0] v0 [1.0 5.0 0.0] g [0.0 -9.81 0.0] dt 0.01 nn 100
        one (-> (p/system {:rate 0.0 :lifetime 1.0e6 :seed 1})
                (update :particles/particles conj
                        {:particle/position x0 :particle/velocity v0
                         :particle/age 0.0 :particle/lifetime 1.0e6}))
        [_ flown] (p/simulate one {:dt dt :gravity g} nn)
        got (get-in flown [:particles/particles 0 :particle/position])
        want (p/ballistic-position x0 v0 g dt nn)
        steady (p/system {:rate 200.0 :lifetime 0.5 :seed 3})
        [_ filled] (p/simulate steady {:dt 0.002 :gravity [0.0 0.0 0.0]} 1000)
        [_ emptied] (p/simulate filled {:dt 0.002 :gravity [0.0 0.0 0.0] :emitting? false} 400)
        golden (p/system {:rate 50.0 :lifetime 2.0 :seed 20260822 :spread 3.0})
        [_ gd] (p/simulate golden {:dt 0.01} 100)
        gv (mapv r9 (get-in gd [:particles/particles 0 :particle/velocity]))
        gp (mapv r9 (:particle/position (last (:particles/particles gd))))
        floor {:collider/kind :plane :collider/point [0.0 0.0 0.0]
               :collider/normal [0.0 1.0 0.0] :collider/restitution 0.5}
        ball (-> (p/system {:rate 0.0 :lifetime 1.0e6 :seed 1})
                 (update :particles/particles conj
                         {:particle/position [0.0 1.0 0.0] :particle/velocity [0.0 0.0 0.0]
                          :particle/age 0.0 :particle/lifetime 1.0e6}))
        ys (loop [c ball i 0 out []]
             (if (= i 2000) out
               (let [[_ n] (p/step c {:dt 0.001 :colliders [floor]})]
                 (recur n (inc i) (conj out (get-in n [:particles/particles 0 :particle/position 1]))))))
        apex (apply max (drop 500 ys))
        refuses? (and (= :error (first (p/step golden {:dt 0.0})))
                      (= :error (first (p/step (assoc golden :particles/rng nil) {:dt 0.01}))))]
    (cond
      (not= [60 60 60] counts)
      (println "PROBE dcc-particles-vfx FAIL"
               (str "2 秒間の放出数がステップ幅で変わる: " (pr-str counts)
                    " —— 端数を捨てている（レートではなくフレーム当たり個数になっている）"))
      (not (every? true? (map #(< (abs* (- %1 %2)) 1.0e-12) got want)))
      (println "PROBE dcc-particles-vfx FAIL"
               (str "半陰的 Euler の閉形式と一致しない: " (pr-str got) " vs " (pr-str want)))
      (not= 100 (p/count-alive filled))
      (println "PROBE dcc-particles-vfx FAIL"
               (str "定常個数が rate×lifetime にならない: " (p/count-alive filled) " ≠ 100"))
      (pos? (p/count-alive emptied))
      (println "PROBE dcc-particles-vfx FAIL"
               (str "放出を止めても " (p/count-alive emptied) " 個が消えない（寿命が効いていない）"))
      (not= [-2.338524675 -5.963974813 -2.028965738] gv)
      (println "PROBE dcc-particles-vfx FAIL"
               (str "seed 20260822 の golden 速度が一致しない: " (pr-str gv)
                    " —— host 間で乱数が割れている（再レンダリングできないショット）"))
      (not= [0.005969995 0.031954839 -0.004097723] gp)
      (println "PROBE dcc-particles-vfx FAIL" (str "golden 位置が一致しない: " (pr-str gp)))
      (> (abs* (- apex 0.25)) 1.0e-3)
      (println "PROBE dcc-particles-vfx FAIL"
               (str "反発係数 0.5 の跳ね返り最高点が " apex "（期待 e²·h = 0.25）"))
      (not refuses?)
      (println "PROBE dcc-particles-vfx FAIL" "dt=0 や seed 無しを受理している")
      :else
      (println "PROBE dcc-particles-vfx PASS"
               (str "放出数 " (pr-str counts) " がステップ幅非依存 / 閉形式と 1e-12 一致 / "
                    "定常 100 = rate×lifetime / golden 値が host 一致 / 跳ね返り "
                    (/ (js/Math.round (* 1e4 apex)) 1e4) " ≈ e²h / dt=0・seed 無しは拒否"))))
  (catch :default ex (println "PROBE dcc-particles-vfx UNMEASURABLE" (.-message ex))))
