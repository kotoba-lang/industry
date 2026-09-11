(ns probe-render-offline-pathtracer
  (:require [kotoba.raytrace.path :as p] [clojure.string :as str]))
;; パストレーサは**絵では測れない**。1/π の欠落・cos の二重適用・pdf の不一致は、
;; どれも「レンダリングに見える画像」を出す。だから既知の値に当てる:
;;   (1) **白炉** —— アルベド 1 の凸な拡散体を一様放射 L の環境に置くと、答えは
;;       厳密に L で**分散ゼロ**。1/π・cos・pdf が打ち消し合うので、どれかが
;;       間違っていればノイズではなく**バイアス**として 1 spp で見える
;;   (2) アルベド ρ なら厳密に ρL（被覆画素の割合が傾き）
;;   (3) **2 つの推定器が同じ積分に到達する** —— 光源の錐サンプリングと半球
;;       サンプリングは pdf も分散も違う別の推定器なので、一致は証拠になる
;;   (4) 誤差が 1/√N で落ちる
;;   (5) 同じ seed が JVM と browser で同じ frame を出す
(try
  (let [abs* (fn [x] (js/Math.abs (double x)))
        furnace (fn [l depth]
                  (let [[st img] (p/film {:shapes [(p/sphere {:albedo [1.0 1.0 1.0]})]
                                          :environment [l l l]}
                                         {:width 8 :height 8 :samples 1 :max-depth depth :seed 42})]
                    (when (= :ok st) (first (p/mean img)))))
        furnace-bad (vec (for [l [0.2 0.7 1.0 3.5] d [2 4 8]
                               :let [m (furnace l d)]
                               :when (or (nil? m) (> (abs* (- m l)) 1.0e-12))]
                           [l d m]))
        mean-for (fn [rho]
                   (first (p/mean (second (p/film {:shapes [(p/sphere {:albedo [rho rho rho]})]
                                                   :environment [1.0 1.0 1.0]}
                                                  {:width 8 :height 8 :samples 1
                                                   :max-depth 8 :seed 7})))))
        m25 (mean-for 0.25) m50 (mean-for 0.5) m90 (mean-for 0.9)
        f (/ (- 1.0 m25) 0.75)
        lit {:shapes [(p/sphere {:centre [0.0 0.0 0.0] :radius 1.0 :albedo [0.8 0.8 0.8]})
                      (p/sphere {:centre [2.2 2.2 1.5] :radius 1.6
                                 :albedo [0.0 0.0 0.0] :emission [6.0 6.0 6.0]})]
             :environment [0.02 0.02 0.02]}
        render (fn [nee? n seed]
                 (first (p/mean (second (p/film lit {:width 12 :height 12 :samples n
                                                     :max-depth 4 :seed seed :next-event? nee?})))))
        brute (render false 256 3) nee (render true 256 3)
        reference (render true 4096 11)
        err (fn [n] (/ (reduce + (for [s [5 17 29 41]] (abs* (- (render false n s) reference)))) 4.0))
        e16 (err 16) e64 (err 64) e256 (err 256)
        [_ gold] (p/film lit {:width 12 :height 12 :samples 8 :max-depth 4
                              :seed 20260823 :next-event? true})
        r9 (fn [x] (/ (js/Math.round (* 1e9 (double x))) 1e9))
        gpx (:image/pixels gold)
        refuses? (and (= :error (first (p/film {:shapes [(p/sphere {:albedo [1.2 1.0 1.0]})]
                                                :environment [1.0 1.0 1.0]}
                                               {:width 4 :height 4 :samples 1 :max-depth 4})))
                      (= :error (first (p/film {:shapes [(p/sphere {})] :environment [0.0 0.0 0.0]}
                                               {:width 4 :height 4 :samples 1 :max-depth 4}))))
        r4 (fn [x] (/ (js/Math.round (* 1e4 x)) 1e4))]
    (cond
      (seq furnace-bad)
      (println "PROBE render-offline-pathtracer FAIL"
               (str "白炉が厳密に L を返さない: " (pr-str furnace-bad)
                    " —— 1/π・cos・pdf のどれかが打ち消し合っていない（バイアス）"))
      (or (> (abs* (- m50 (- 1.0 (* f 0.5)))) 1.0e-12)
          (> (abs* (- m90 (- 1.0 (* f 0.1)))) 1.0e-12))
      (println "PROBE render-offline-pathtracer FAIL"
               (str "アルベドに対して線形でない: " (pr-str [m25 m50 m90])))
      (> (abs* (- brute nee)) (* 0.03 nee))
      (println "PROBE render-offline-pathtracer FAIL"
               (str "2 つの推定器が一致しない: 半球 " (r4 brute) " vs 光源錐 " (r4 nee)))
      (not (> e16 e64 e256))
      (println "PROBE render-offline-pathtracer FAIL"
               (str "誤差が単調に減らない: " (pr-str [(r4 e16) (r4 e64) (r4 e256)])))
      (not (and (< 1.2 (/ e16 e64) 4.5) (< 1.2 (/ e64 e256) 4.5)))
      (println "PROBE render-offline-pathtracer FAIL"
               (str "1/√N で落ちない: 比 " (r4 (/ e16 e64)) " と " (r4 (/ e64 e256))))
      (not= [0.146840686 0.146840686 0.146840686] (mapv r9 (nth gpx 65)))
      (println "PROBE render-offline-pathtracer FAIL"
               (str "golden 画素が host 間で一致しない: " (pr-str (mapv r9 (nth gpx 65)))))
      (not= [1.352917396 1.352917396 1.352917396] (mapv r9 (nth gpx 66)))
      (println "PROBE render-offline-pathtracer FAIL"
               (str "golden 画素 66 が一致しない: " (pr-str (mapv r9 (nth gpx 66)))))
      (not refuses?)
      (println "PROBE render-offline-pathtracer FAIL"
               "アルベド>1 や「何も発光しない場面」を受理している")
      :else
      (println "PROBE render-offline-pathtracer PASS"
               (str "白炉が 12 通りで厳密（1e-12、1spp）/ アルベドに線形 / "
                    "半球 " (r4 brute) " と光源錐 " (r4 nee) " が一致 / 誤差比 "
                    (r4 (/ e16 e64)) "・" (r4 (/ e64 e256)) " / golden 画素が host 一致 / "
                    "アルベド>1・無光源は拒否"))))
  (catch :default ex (println "PROBE render-offline-pathtracer UNMEASURABLE" (.-message ex))))
