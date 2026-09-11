(ns isekai.games.boutique-stack.kami-frame-test
  "The seam test: does what this game emits actually satisfy the executor?

  ADR-2608630000 shipped `render-ir/scene` without the upstream consumer on
  the classpath and said so. It had guessed wrong — the executor reads
  `{:pos :color :size :geo …}`, not `{:instance/mesh :instance/tint …}`. That
  is exactly the failure a `require` of the real namespace catches and a
  remembered shape does not, so this namespace requires `kami.webgpu.ir` and
  checks against it rather than against a copy of it.

  Run with the engine on the classpath:

      npx nbb --classpath src:test:../common/src:\\
        ../../../../../orgs/kotoba-lang/webgpu/src:\\
        ../../../../../orgs/kotoba-lang/render/src run-tests.cljs"
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.boutique-stack.art :as art]
            [isekai.games.boutique-stack.render-ir :as rir]
            [isekai.games.boutique-stack.sim :as sim]
            [isekai.games.boutique-stack.support :as support]
            [isekai.games.boutique-stack.world :as world]
            [kami.webgpu.ir :as ir]))

(defn- frame []
  (let [[world state] (sim/start support/tiny)
        state (sim/run state world 240)]
    [world state (rir/kami-frame state world)]))

(defn- num3? [v]
  (and (vector? v) (= 3 (count v)) (every? number? v)))

(deftest the-frame-satisfies-the-executors-own-validator
  (let [[_ _ f] (frame)]
    (is (ir/valid? f) "kami.webgpu.ir/valid? — the real check, not a local copy")
    (is (seq (:instances f)))))

(deftest every-instance-matches-the-packed-abi
  (let [[_ _ f] (frame)]
    (doseq [i (:instances f)]
      (is (num3? (:pos i)) (str "pos " (pr-str i)))
      (is (num3? (:color i)) (str "color must be rgb, not rgba: " (pr-str (:color i))))
      (is (num3? (ir/instance-size (:size i))))
      (is (every? #(<= 0.0 % 1.0) (:color i)) (str "colour out of gamut: " (pr-str (:color i))))
      (is (number? (:yaw i)))
      (is (<= 0.0 (:metallic i) 1.0))
      (is (<= 0.0 (:roughness i) 1.0)))))

(deftest every-geo-is-a-kind-the-executor-bakes
  (let [[_ _ f] (frame)
        kinds (set (keys ir/default-geometry))]
    (doseq [i (:instances f)]
      (is (contains? kinds (:geo i))
          (str ":geo " (:geo i) " is not in kami.webgpu.ir/default-geometry")))))

(deftest the-primitive-table-covers-every-mesh-the-scene-can-name
  (testing "a mesh with a name but no primitive would silently draw nothing"
    (doseq [m (keys art/meshes)]
      (is (contains? art/prims m) (str "no primitive for mesh " m)))))

(deftest the-globals-carry-a-sky-and-a-camera
  (let [[_ _ f] (frame)
        g (:globals f)]
    (is (num3? (get-in g [:sky :horizon])))
    (is (num3? (get-in g [:sky :sun-dir])))
    (is (num3? (get-in g [:sky :sun])))
    (is (num3? (:eye g)))
    (is (num3? (:target g)))))

(deftest the-camera-is-above-the-shop-and-outside-it
  (let [[world _ f] (frame)
        {:keys [min-x max-x min-y max-y]} (rir/bounds world)
        [ex ey ez] (get-in f [:globals :eye])
        [tx _ tz] (get-in f [:globals :target])]
    (is (pos? ey) "eye is above the floor")
    (is (> ez (* 0.01 max-y)) "eye sits on the door side, outside the shop")
    (is (<= (* 0.01 min-x) tx (* 0.01 max-x)) "target is inside the footprint")
    (is (<= (* 0.01 min-y) tz (* 0.01 max-y)))
    (is (> ey (- ez tz)) "steeper than 45 degrees — a tycoon reads as a floor plan")
    (is (> ex tx) "the shop is seen from an angle, not straight on")))

(deftest a-locked-departments-stock-never-draws
  (testing "the gating survives the seam"
    (let [[world state] (support/quiet)
          ;; The shoe rack is behind two unlocks. Fill it anyway: if the seam
          ;; leaked, a locked department would put four sneakers on screen.
          stocked (assoc-in state [:stock :rack-sneaker] 4)
          rack-at (:fixture/pos (world/fixture world :rack-sneaker))
          over-rack (fn [f]
                      (count (filter (fn [i]
                                       (and (= (nth (:pos i) 0) (* 0.01 (first rack-at)))
                                            (= (nth (:pos i) 2) (* 0.01 (second rack-at)))
                                            (> (nth (:pos i) 1) 1.0)
                                            ;; badges hover over the same
                                            ;; spot and are the emissive ones
                                            (zero? (:emissive i))))
                                     (:instances f))))]
      (is (zero? (over-rack (rir/kami-frame stocked world)))
          "locked: nothing sits on that rack")
      (is (= 4 (over-rack (rir/kami-frame
                           (assoc stocked :unlocked (set (:zone-order world)))
                           world)))
          "unlocked: one instance per garment")
      (is (some #(pos? (:emissive %)) (:instances (rir/kami-frame
                                                   (assoc stocked :unlocked
                                                          (set (:zone-order world)))
                                                   world)))
          "and the full rack gets its MAX badge, which is the emissive one"))))

(deftest the-frame-is-a-function-of-the-state
  (let [[world state] (sim/start support/tiny)
        a (sim/run state world 200)
        b (sim/run state world 200)]
    (is (= (rir/kami-frame a world) (rir/kami-frame b world))
        "same state, same frame — the renderer adds no hidden randomness")))
