(ns probe-anim-curves (:require [kami.animation :as a]))
;; 不変条件: 線形は中点で中間値、:step は次キーまで前の値のまま、:smooth は線形と違う。
;; 3 つが同じ値を返すなら「補間モード」は名前だけである。
(try
  (let [lin (a/track :x [(a/keyframe 0 0) (a/keyframe 2 10)])
        stp (a/track :x [(a/keyframe 0 0 :step) (a/keyframe 2 10)])
        smo (a/track :x [(a/keyframe 0 0 :smooth) (a/keyframe 1 10)])
        l1 (a/sample lin 1) s1 (a/sample stp 1)
        m1 (a/sample smo 0.25) mlin 2.5]
    (cond
      (not= 5 l1) (println "PROBE anim-curves FAIL" (str "線形補間の中点が " l1 "（5 を期待）"))
      (not= 0 s1) (println "PROBE anim-curves FAIL" (str ":step が " s1 "（0 を期待）"))
      (= m1 mlin) (println "PROBE anim-curves FAIL" ":smooth が線形と同じ値を返す")
      (not= 0 (a/sample lin -1)) (println "PROBE anim-curves FAIL" "範囲外でクランプしない")
      :else (println "PROBE anim-curves PASS"
                     (str "linear(1)=" l1 " step(1)=" s1 " smooth(.25)=" m1))))
  (catch :default ex (println "PROBE anim-curves UNMEASURABLE" (.-message ex))))
