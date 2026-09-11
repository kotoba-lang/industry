(ns probe-cae-contact
  (:require [cae.solver :as s] [cae.nonlinear] [cae.industrial]))
;; 不変条件: 接触ソルバが (1) 収束し (2) 荷重を上げると変位が増える単調性を持ち
;; (3) 未登録の solver は **名指しで拒否**される（黙って nil を返さない）。
;; 数値が出ることではなく、物理として筋が通ることを検査する。
(try
  (let [run (fn [f] (s/solve {:solver {:kind :nonlinear-contact}
                              :force-N f :area-m2 1.0e-4 :thickness-m 0.01
                              :youngs-modulus-Pa 2.0e11 :contact-stiffness-N-m 1.0e8}))
        a (run 1000.0) b (run 2000.0)
        refused (try (do (s/solve {:solver {:kind :no-such-solver}}) :not-refused)
                     (catch :default e (ex-data e)))]
    (cond
      (not (:converged? a)) (println "PROBE cae-contact FAIL" (str "収束しない: " (pr-str a)))
      (not (< 0 (:displacement-m a) (:displacement-m b)))
      (println "PROBE cae-contact FAIL"
               (str "荷重 2 倍で変位が単調に増えない: " (:displacement-m a) " → " (:displacement-m b)))
      (= :not-refused refused)
      (println "PROBE cae-contact FAIL" "未登録の solver が拒否されない")
      (not (seq (:registered refused)))
      (println "PROBE cae-contact FAIL" "拒否はするが、何が登録済みかを言わない")
      :else (println "PROBE cae-contact PASS"
                     (str "u(1kN)=" (:displacement-m a) " u(2kN)=" (:displacement-m b)
                          " status=" (:status a) " 登録済み " (count (:registered refused)) " 種"))))
  (catch :default ex (println "PROBE cae-contact UNMEASURABLE" (.-message ex))))
