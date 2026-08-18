(ns isekai.games.boutique-stack.render-ir-test
  (:require [clojure.test :refer [deftest is testing]]
            [isekai.games.boutique-stack.art :as art]
            [isekai.games.boutique-stack.economy :as econ]
            [isekai.games.boutique-stack.render-ir :as ir]
            [isekai.games.boutique-stack.sim :as sim]
            [isekai.games.boutique-stack.support :as sup]))

(deftest a-scene-carries-everything-a-renderer-needs
  (let [[w s] (sup/start)
        scene (ir/scene s w)]
    (is (= :3d (:scene/dimension scene)) "instances, not sprites")
    (is (= :isometric (:camera/rig (:scene/camera scene))))
    (is (= "boutique-stack/bs-test" (:scene/id scene)))
    (is (seq (:render/instances scene)))
    (is (some? (:art/preset scene)))))

(deftest every-instance-names-a-mesh-that-exists
  (testing "a typo'd mesh draws nothing and raises nothing, so this is the
            only place it can be caught"
    (let [[w s] (sup/start)
          scene (ir/scene (sim/run s w 800) w)]
      (is (every? #(contains? art/meshes (:instance/mesh %))
                  (:render/instances scene))))))

(deftest the-scene-is-emitted-in-a-stable-order
  (let [[w s] (sup/start)
        after (sim/run s w 400)]
    (is (= (ir/scene after w) (ir/scene after w)))))

(deftest a-locked-department-draws-dimmed-and-not-populated
  (let [[w s] (sup/start)
        scene (ir/scene s w)
        by-id (into {} (map (juxt :instance/id identity) (:render/instances scene)))]
    (is (:instance/locked? (get by-id "zone-dept-shoes")))
    (is (= :floor-locked (:instance/mesh (get by-id "zone-dept-shoes"))))
    (is (not (:instance/locked? (get by-id "zone-dept-tops"))))
    (is (:instance/dim? (get by-id "fixture-rack-sneaker"))
        "its fixtures are drawn, greyed, so the player can see what is behind
         the price")))

(deftest a-carried-stack-is-one-instance-per-garment
  (testing "the tall column is how a player sees a carry upgrade without
            opening a menu"
    (let [[w s] (sup/quiet)
          carrying (assoc-in s [:player :carry] {:item :tee :count 4})
          carried (filter #(= :carried (:instance/kind %))
                          (:render/instances (ir/scene carrying w)))]
      (is (= 4 (count carried)))
      (is (= [:tee :tee :tee :tee] (mapv :instance/mesh carried)))
      (is (apply < (map (fn [i] (second (:instance/pos i))) carried))
          "and it stacks upward"))))

(deftest a-full-rack-gets-a-max-badge
  (let [[w s] (sup/quiet)
        empty-scene (ir/scene s w)
        full (sim/run s w 400)
        full-scene (ir/scene full w)]
    (is (empty? (filter #(= :max (:badge/kind %)) (:render/badges empty-scene))))
    (is (= [:rack-tee] (mapv :badge/fixture
                             (filter #(= :max (:badge/kind %))
                                     (:render/badges full-scene)))))))

(deftest only-permitted-departments-show-a-price-pad
  (testing "a price on something the chain forbids reads as a broken game
            rather than as progression"
    (let [[w s] (sup/start)
          pads (filter #(= :unlock (:badge/kind %)) (:render/badges (ir/scene s w)))]
      (is (= [:dept-outerwear] (mapv :badge/zone pads)))
      (is (= [300] (mapv :badge/cost pads)))
      (is (= [false] (mapv :badge/affordable? pads)) "no money yet")
      (let [opened (econ/unlock (assoc s :money 5000) w :dept-outerwear)
            pads (filter #(= :unlock (:badge/kind %)) (:render/badges (ir/scene opened w)))]
        (is (= [:dept-shoes] (mapv :badge/zone pads)))
        (is (= [true] (mapv :badge/affordable? pads)))))))

(deftest the-hud-reports-progress-without-styling-it
  (let [[w s] (sup/start)
        hud (:render/hud (ir/scene s w))]
    (is (= 0 (:hud/money hud)))
    (is (= 4 (:hud/carry-capacity hud)))
    (is (= [{:dept/id :tops :dept/name "Tops"}] (:hud/departments hud)))
    (is (= {:zone/id :dept-outerwear :zone/name "Outerwear" :zone/cost 300}
           (:hud/next-unlock hud)))
    (is (= 3 (count (:hud/upgrades hud))))
    (is (every? string? (vals (:hud/tokens hud)))
        "token names only — the app must not re-derive their values")))

(deftest hit-test-answers-for-open-fixtures-only
  (let [[w s] (sup/start)]
    (is (= [:fixture :rack-tee] (ir/hit-test s w [300 0 400])))
    (is (nil? (ir/hit-test s w [300 0 100]))
        "the shoe rack is locked, so a tap on it starts nothing")
    (is (= [:unlock :dept-outerwear] (ir/hit-test s w [200 0 250]))
        "but its price pad answers")
    (is (nil? (ir/hit-test s w [9000 0 9000])))))
