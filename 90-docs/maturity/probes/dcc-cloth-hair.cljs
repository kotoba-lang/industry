(ns probe-dcc-cloth-hair
  (:require [kotoba.physics.cloth :as cl] [clojure.string :as str]))
;; 布は**絵**では測れない（間違った solver も説得力のある形に垂れる）。測るのは
;; 保存量と解析解:
;;   (1) 残差が反復数に対して単調に減る（符号を間違えた solver はここで落ちる）
;;   (2) 自由布は自分の重心を動かさない —— 拘束は 2 粒子を**質量重み付きで逆向きに**
;;       動かすから。均等に重み付けするのは最もありがちな簡略化で、**これ以外の
;;       見た目は何も壊れない**
;;   (3) 平行移動・回転で残差が変わらない（距離拘束は原点を知らない）
;;   (4) 振り子が 2π√(L/g) で振れる —— この実装が一切寄与していない数
;;   (5) compliance が反復数に依存しない（XPBD の λ 累積が効いている証拠）
(try
  (let [abs* (fn [x] (js/Math.abs (double x)))
        g (cl/grid {:rows 8 :cols 8 :spacing 0.1 :pins #{[0 0] [0 7]}})
        residuals (mapv (fn [it] (let [[_ c] (cl/simulate g {:dt 0.016 :iterations it} 40)]
                                   (cl/residual c)))
                        [1 4 16 64])
        free (cl/grid {:rows 6 :cols 6 :spacing 0.1})
        com0 (cl/centre-of-mass free)
        [_ moved] (cl/simulate free {:dt 0.01 :iterations 20 :gravity [0.0 0.0 0.0]} 50)
        com1 (cl/centre-of-mass moved)
        shift (fn [c t] (update c :cloth/particles
                                (fn [ps] (mapv #(-> % (update :particle/position (fn [p] (mapv + p t)))
                                                      (update :particle/previous (fn [p] (mapv + p t)))) ps))))
        base (cl/grid {:rows 5 :cols 5 :spacing 0.1 :pins #{[0 0]}})
        opts {:dt 0.01 :iterations 10 :gravity [0.0 0.0 0.0]}
        [_ a] (cl/simulate base opts 20)
        [_ b] (cl/simulate (shift base [100.0 0.0 -37.5]) opts 20)
        ang (* js/Math.PI (/ 10.0 180.0))
        pend {:cloth/particles [(cl/particle [0.0 0.0 0.0] 0.0)
                                (cl/particle [(js/Math.sin ang) (- (js/Math.cos ang)) 0.0])]
              :cloth/constraints [(cl/constraint 0 1 1.0)]}
        dt 0.0005
        xs (loop [c pend i 0 out []]
             (if (= i 12000) out
               (let [[_ n] (cl/step c {:dt dt :iterations 20})]
                 (recur n (inc i) (conj out (get-in n [:cloth/particles 1 :particle/position 0]))))))
        cross (vec (keep-indexed (fn [i [p q]] (when (and (neg? p) (pos? q)) (* dt i)))
                                 (map vector xs (rest xs))))
        period (when (>= (count cross) 2) (- (nth cross 1) (nth cross 0)))
        analytic (* 2.0 js/Math.PI (js/Math.sqrt (/ 1.0 9.81)))
        sag (fn [c its] (let [[_ d] (cl/simulate c {:dt 0.01 :iterations its} 60)]
                          (nth (cl/centre-of-mass d) 1)))
        soft (fn [] (cl/grid {:rows 6 :cols 6 :spacing 0.1 :pins #{[0 0] [0 5]}
                              :compliance 1.0e-3 :shear-compliance 1.0e-3 :bend-compliance 1.0e-3}))
        s20 (sag (soft) 20) s80 (sag (soft) 80)
        sph {:collider/kind :sphere :collider/centre [0.4 0.5 0.4] :collider/radius 0.3}
        [_ draped] (cl/simulate (cl/grid {:rows 9 :cols 9 :spacing 0.1 :y 1.0})
                                {:dt 0.008 :iterations 12 :colliders [sph]} 120)
        inside (count (filter (fn [p] (< (js/Math.sqrt (reduce + (map (fn [x y] (* (- x y) (- x y)))
                                                                     (:particle/position p) [0.4 0.5 0.4])))
                                         0.2999))
                              (:cloth/particles draped)))
        refuses? (= :error (first (cl/step g {:dt 0.01 :iterations 0})))]
    (cond
      (not (apply > residuals))
      (println "PROBE dcc-cloth-hair FAIL"
               (str "残差が反復数に対して単調に減らない: " (pr-str residuals)))
      (> (last residuals) 2.0e-3)
      (println "PROBE dcc-cloth-hair FAIL" (str "64 反復でも残差 " (last residuals)))
      (not (every? true? (map #(< (abs* (- %1 %2)) 1.0e-12) com1 com0)))
      (println "PROBE dcc-cloth-hair FAIL"
               (str "自由布の重心が動いた " (pr-str com0) " → " (pr-str com1)
                    " —— 拘束の質量重み付けが対称でない"))
      (> (abs* (- (cl/residual a) (cl/residual b))) 1.0e-12)
      (println "PROBE dcc-cloth-hair FAIL"
               (str "平行移動で残差が変わる: " (cl/residual a) " vs " (cl/residual b)))
      (or (nil? period) (> (abs* (/ (- period analytic) analytic)) 0.01))
      (println "PROBE dcc-cloth-hair FAIL"
               (str "振り子の周期 " period " が 2π√(L/g)=" analytic " と 1% 以内で合わない"))
      (> (abs* (- s20 s80)) 1.0e-9)
      (println "PROBE dcc-cloth-hair FAIL"
               (str "compliance が反復数に依存する（垂れ " s20 " → " s80 "）—— λ を"
                    "毎パス作り直していると、solver 設定を変えただけで材質が変わる"))
      (pos? inside)
      (println "PROBE dcc-cloth-hair FAIL" (str "球の中に粒子が " inside " 個残っている"))
      (not refuses?)
      (println "PROBE dcc-cloth-hair FAIL" "反復数 0 を受理している（形だけ布の自由落下）")
      :else
      (println "PROBE dcc-cloth-hair PASS"
               (str "残差 " (pr-str (mapv (fn [r] (/ (js/Math.round (* 1e5 r)) 1e5)) residuals))
                    " が単調減少 / 自由布の重心不変 1e-12 / 剛体運動不変 / "
                    "振り子 " (/ (js/Math.round (* 1e4 period)) 1e4) "s vs 解析解 "
                    (/ (js/Math.round (* 1e4 analytic)) 1e4) "s / compliance が反復数非依存 1e-9 / "
                    "球の中に 0 個"))))
  (catch :default ex (println "PROBE dcc-cloth-hair UNMEASURABLE" (.-message ex))))
