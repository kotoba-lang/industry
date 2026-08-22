(ns gpr.detect-test
  "合成 B-scan に対する往復。**これが確かめるのは実装が式どおりであることだけで、
  閾値が現場で妥当かどうかは何も確かめていない**（gpr.synth の docstring）。"
  (:require [clojure.test :refer [deftest is testing]]
            [gpr.detect :as detect]
            [gpr.dsp :as dsp]
            [gpr.math :as m]
            [gpr.result :as r]
            [gpr.synth :as synth]
            [gpr.velocity :as vel]))

(def ^:private truth
  {:v 0.1 :x0 2.0 :depth-m 0.8 :f0-ghz 0.4
   :n-samples 512 :dt-ns 0.1 :x-from 0.0 :x-to 4.0 :dx 0.05})

(defn- pipeline
  [opts]
  (let [bs (synth/point-scatterer-b-scan opts)]
    (r/bind (dsp/envelope-b-scan bs)
            (fn [env] (detect/features env {:from-ns 0.0 :to-ns 51.2} 0.05)))))

(deftest recovers-velocity-depth-and-apex
  (let [res (pipeline truth)]
    (is (r/ok? res))
    (let [f (r/value res)]
      (testing "速度 ±5%"
        (is (< (m/abs* (- (:velocity-m-per-ns f) (:v truth))) (* 0.05 (:v truth)))))
      (testing "頂点の TWT ±5%"
        (let [t0 (vel/twt-ns (:depth-m truth) (:v truth))]
          (is (< (m/abs* (- (:twt-ns f) t0)) (* 0.05 t0)))))
      (testing "深度 ±10%"
        (is (< (m/abs* (- (:depth-m f) (:depth-m truth))) (* 0.10 (:depth-m truth)))))
      (testing "apex 位置 ±0.1 m"
        (is (< (m/abs* (- (:apex-x-m f) (:x0 truth))) 0.1)))
      (testing "比誘電率が湿った土の桁（εr ≈ 9）"
        (is (< (m/abs* (- (:permittivity f) 9.0)) 1.0)))
      (testing "当てはまりが良い"
        (is (> (:r2 f) 0.99))))))

(deftest survives-noise
  (let [res (pipeline (assoc truth :noise 0.02))]
    (is (r/ok? res))
    (is (< (m/abs* (- (:velocity-m-per-ns (r/value res)) (:v truth))) (* 0.10 (:v truth))))))

(deftest reports-why-it-could-not-fit
  (testing "ピックが足りないときは「合格」ではなく理由つきの err"
    (let [flat (synth/point-scatterer-b-scan (assoc truth :x-from 0.0 :x-to 0.1 :dx 0.05))
          res (r/bind (dsp/envelope-b-scan flat)
                      (fn [env] (detect/features env {:from-ns 0.0 :to-ns 51.2} 0.05)))]
      (is (r/err? res))
      (is (contains? #{:detect/too-few-picks :detect/no-plausible-fit}
                     (:code (r/error res)))))))

(deftest empty-gate-yields-no-picks
  (testing "ゲートが空なら候補 0 件。0 件を「異常なし」と読ませない err を返す"
    (let [bs (synth/point-scatterer-b-scan truth)
          res (r/bind (dsp/envelope-b-scan bs)
                      (fn [env] (detect/features env {:from-ns 0.0 :to-ns 0.0} 0.05)))]
      (is (r/err? res)))))

(deftest fit-rejects-implausible-velocity
  (testing "r2 が良くても物理的にありえない速度の解は採らない"
    (let [ps (mapv (fn [x] {:x-m x :twt-ns (vel/hyperbola-twt 16.0 0.5 2.0 x) :amplitude-ratio 1.0})
                   (range 0.0 4.01 0.05))]
      (is (r/err? (detect/fit-best ps)))
      (is (= :detect/no-plausible-fit (:code (r/error (detect/fit-best ps))))))))
