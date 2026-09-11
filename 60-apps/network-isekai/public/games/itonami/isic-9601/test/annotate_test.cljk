(ns annotate-test
  "nbb pins for #1753 annotate / pick — project/pick loop and unfiltered inspection."
  (:require [clojure.test :refer [deftest is run-tests]]
            [kami.webgpu.pick :as pick]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.world3d :as w3]
            [itonami.isic-9601.inspect :as inspect]))

(def W 900)
(def H 1600)
(def aspect (/ (double W) (double H)))
(def fresh (world/init))

(deftest labels-use-pick-project-coords
  (let [f (w3/render-ir fresh aspect)
        labs (inspect/annotate-labels f W H)]
    (is (pos? (count labs)))
    (doseq [lab labs]
      (let [inst (nth (:instances f) (:index lab))
            p (pick/project f (:center (pick/instance-box inst)) W H)]
        (is (= (:district lab) (:district inst)))
        (is (= (:text lab) (str (:index lab) ":" (:district lab))))
        (is (= [(:x lab) (:y lab)] p)
            (str "label coords must be pick/project, got " (pr-str [(:x lab) (:y lab)])
                 " vs " (pr-str p)))))))

(deftest labels-inside-bounds-default-and-orbits
  (doseq [opts [nil {:orbit 40.0} {:orbit 180.0}]]
    (let [f (w3/render-ir fresh aspect opts)
          r (inspect/labels-inside-bounds? f W H)]
      (is (:ok r) (pr-str (take 2 (:bad r)))))))

(deftest pick-at-label-returns-named-instance
  (doseq [opts [nil {:orbit 40.0} {:orbit 180.0}]]
    (let [f (w3/render-ir fresh aspect opts)
          r (inspect/pick-loop-closed? f W H)]
      (is (:ok r) (pr-str (take 2 (:bad r)))))))

(deftest sky-miss-is-nil-not-a-building
  (let [f (w3/render-ir fresh aspect {:eye [0.0 5.0 200.0] :target [0.0 5.0 0.0]})]
    (is (nil? (inspect/pick-at f [450.0 10.0] W H)))))

(deftest road-pick-is-unfiltered
  (let [f (w3/render-ir fresh aspect)
        sample (inspect/road-sample f W H)
        p (:pixel sample)
        hit (:hit sample)]
    (is (some? sample) "a road pixel must exist under the default camera")
    (is (#{:road :road-line} (:kind hit)))
    (is (nil? (:district hit)))
    (is (nil? (pick/pick f p W H {:filter w3/shop-instance?}))
        "contrast: shop filter must miss this road pixel")))

(deftest hit-edn-shape
  (let [f (w3/render-ir fresh aspect)
        lab (first (inspect/annotate-labels f W H))
        hit (inspect/pick-at f [(:x lab) (:y lab)] W H)]
    (is (= #{:index :district :kind :point :t} (set (keys hit))))
    (is (= (:index lab) (:index hit)))
    (is (= (:district lab) (:district hit)))
    (is (number? (:t hit)))
    (is (= 3 (count (:point hit))))))

(run-tests 'annotate-test)
