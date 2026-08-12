(ns camera-test
  "nbb pins for #1751 camera overrides — same assertions as the JVM world3d_test extras,
  runnable without `clojure -M:gpu-test` when the engine is on the classpath."
  (:require [clojure.test :refer [deftest is run-tests]]
            [kami.webgpu.ir :as ir]
            [kami.webgpu.pick :as pick]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.world3d :as w3]))

(def W 900)
(def H 1600)
(def aspect (/ (double W) (double H)))
(def fresh (world/init))

(defn- body-for [f id]
  (first (filter (fn [i] (and (= (:kind i) :shop) (= (:district i) id))) (:instances f))))

(defn- eye-range [cam]
  (let [[x y z] (:eye cam)]
    (js/Math.sqrt (+ (* x x) (* y y) (* z z)))))

(deftest default-equals-nil-opts
  (is (= (w3/render-ir fresh aspect) (w3/render-ir fresh aspect nil))))

(deftest zoom-and-orbit-change-the-eye
  (let [base (w3/camera aspect)
        spun (w3/camera aspect {:orbit 180.0})
        zoomed (w3/camera aspect {:zoom 2.0})]
    ;; default azimuth is π/2 (eye on +z); +180° lands on -z — x stays ~0
    (is (> (js/Math.abs (- (nth (:eye spun) 2) (nth (:eye base) 2))) 1.0)
        "orbit 180 flips the eye across the street")
    (is (< (eye-range zoomed) (* 0.6 (eye-range base))))))

(deftest fov-and-absolute-eye
  (let [f (w3/render-ir fresh aspect {:fov 33.0 :eye [5.0 30.0 40.0] :target [0.0 1.0 0.0]})]
    (is (ir/valid? f))
    (is (= 33.0 (get-in f [:globals :fov])))
    (is (= [5.0 30.0 40.0] (get-in f [:globals :eye])))
    (is (= [0.0 1.0 0.0] (get-in f [:globals :target])))))

(deftest pick-agrees-under-orbit-zoom-fov
  (let [f (w3/render-ir fresh aspect {:orbit 25.0 :zoom 1.1 :fov 48.0})]
    (doseq [d world/districts]
      (let [b (body-for f (:id d))
            c (:center (pick/instance-box b))
            p (pick/project f c W H)
            hit (pick/pick f p W H {:filter w3/shop-instance?})]
        (is (some? p) (:id d))
        (is (= (:id d) (get-in hit [:instance :district])) (:id d))))))

(run-tests 'camera-test)
