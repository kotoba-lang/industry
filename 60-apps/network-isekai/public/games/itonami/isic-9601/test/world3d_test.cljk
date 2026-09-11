(ns world3d-test
  "The 3D street, run against the REAL canonical stack — `kami.webgpu.ir`,
  `kami.webgpu.pick` and `kotoba.render.camera` from `kotoba-lang/webgpu`.

  CLAUDE.md's 3D rule says both backends consume the same EDN render-IR, so what is
  verified here holds for the WebGPU path and the WebGL 2.0 fallback alike: there is one
  scene description and these assertions are about it.

  What a screenshot could not tell you, and these do: that every shop is actually in front
  of the camera and inside the frame at several aspect ratios, and that a tap at a
  building's own projected position resolves to that building — the failure mode being a
  picker that is subtly off (wrong up vector, or forgetting that an instance's `:pos` is
  its ground point) and returns the neighbour near the edges of the frame."
  (:require [clojure.test :refer [deftest is]]
            [kami.webgpu.ir :as ir]
            [kami.webgpu.pick :as pick]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.world3d :as w3]))

(def W 900)
(def H 1600)  ;; phone portrait, like the reference screenshots

(def fresh (world/init))
(def aspect (/ (double W) (double H)))
(def frame (w3/render-ir fresh aspect))

(defn shops-of [f]
  (filter w3/shop-instance? (:instances f)))

(defn body-for [f id]
  (first (filter (fn [i] (and (= (:kind i) :shop) (= (:district i) id))) (:instances f))))

;; --------------------------------------------------------------------------
;; the frame is a well-formed render-IR
;; --------------------------------------------------------------------------

(deftest frame-is-valid-render-ir
  (is (ir/valid? frame) "kami.webgpu.ir/valid? accepts the frame"))

(deftest frame-states-its-camera-and-fov
  (let [g (:globals frame)]
    (is (vector? (:eye g)))
    (is (vector? (:target g)))
    ;; picking reads the same key; leaving it implicit makes the pick depend on a default
    (is (number? (:fov g)) ":fov is stated, not inherited")
    (is (map? (:sky g)))))

(deftest the-camera-backs-off-on-a-narrower-screen
  ;; the whole reason :distance is solved rather than stated: a portrait phone sees a
  ;; narrower slice of the world at the same vertical fov, so the eye must retreat
  (let [dist (fn [a] (let [e (:eye (w3/camera a))]
                       (Math/sqrt (double (+ (* (nth e 0) (nth e 0))
                                             (* (nth e 2) (nth e 2))
                                             (* (nth e 1) (nth e 1)))))))]
    (is (> (dist (/ 9.0 16.0)) (dist (/ 16.0 9.0)))
        "portrait needs a longer lens than landscape")))

(deftest every-district-has-a-building
  (doseq [d world/districts]
    (is (some? (body-for frame (:id d))) (str (:id d) " has a body"))))

(deftest every-instance-is-a-cuboid-with-a-ground-position
  (doseq [i (:instances frame)]
    (is (= 3 (count (:pos i))) "pos is [x y z]")
    (is (= 3 (count (:size i))) "size is [w h d]")
    (is (>= (nth (:pos i) 1) -1.0) "nothing is buried")))

;; --------------------------------------------------------------------------
;; the street is actually on screen
;; --------------------------------------------------------------------------

(deftest every-shop-is-in-front-of-the-camera
  (doseq [d world/districts]
    (let [b (body-for frame (:id d))
          c (:center (pick/instance-box b))]
      (is (some? (pick/project frame c W H))
          (str (:id d) " is in front of the camera, not behind it")))))

(deftest every-shop-is-inside-the-frame
  (doseq [d world/districts]
    (let [b (body-for frame (:id d))
          c (:center (pick/instance-box b))
          p (pick/project frame c W H)]
      (is (and (< 0 (nth p 0) W) (< 0 (nth p 1) H))
          (str (:id d) " projects to " (pr-str p) " which is inside " W "x" H)))))

(deftest the-street-frames-on-other-aspect-ratios-too
  (doseq [[w h] [[390 844] [900 1600] [1280 720] [1920 1080]]]
    (doseq [d world/districts]
      (let [b (body-for frame (:id d))
            c (:center (pick/instance-box b))
            f (w3/render-ir fresh (/ (double w) (double h)))
            p (pick/project f (:center (pick/instance-box (body-for f (:id d)))) w h)]
        (is (and p (< 0 (nth p 0) w) (< 0 (nth p 1) h))
            (str (:id d) " fits " w "x" h " (projected " (pr-str p) ")"))))))

;; --------------------------------------------------------------------------
;; tapping
;; --------------------------------------------------------------------------

(deftest tapping-a-shop-resolves-to-that-district
  (doseq [d world/districts]
    (let [b (body-for frame (:id d))
          c (:center (pick/instance-box b))
          p (pick/project frame c W H)
          hit (pick/pick frame p W H {:filter w3/shop-instance?})]
      (is (= (:id d) (get-in hit [:instance :district]))
          (str "tapping " (:id d) " at " (pr-str p) " returned "
               (pr-str (get-in hit [:instance :district])))))))

(deftest tapping-the-sky-selects-nothing
  (is (nil? (pick/pick frame [4.0 4.0] W H {:filter w3/shop-instance?}))))

(deftest scenery-is-never-a-tap-target
  ;; The ground plane spans the whole map, so an unfiltered pick answers almost every
  ;; pixel with it — which is why the filter exists. Asserted over a grid rather than one
  ;; hand-picked pixel: a single pixel stops proving anything the moment the camera is
  ;; solved per viewport and the scene moves under it.
  (let [pixels (for [x (range 60 W 90) y (range 60 H 90)] [(double x) (double y)])
        filtered (keep (fn [p] (pick/pick frame p W H {:filter w3/shop-instance?})) pixels)
        scenery-only (filter (fn [p]
                               (and (some? (pick/pick frame p W H))
                                    (nil? (pick/pick frame p W H {:filter w3/shop-instance?}))))
                             pixels)]
    (is (pos? (count filtered)) "the grid does cross some shops")
    (is (every? (fn [h] (w3/shop-instance? (:instance h))) filtered)
        "a filtered pick never returns ground, road, tree or player")
    (is (pos? (count scenery-only))
        "and there are pixels where only scenery is under the finger — the filter is doing work")))

(deftest a-nearer-shop-wins-over-one-behind-it
  ;; two buildings on the same screen pixel must resolve to the near one
  (let [f {:globals {:eye [0 12 60] :target [0 2 0] :fov 46.0}
           :instances [(assoc (ir/instance [0 0 -40] [1 0 0] [10 8 10])
                              :kind :shop :district "far")
                       (assoc (ir/instance [0 0 0] [0 1 0] [10 8 10])
                              :kind :shop :district "near")]}
        hit (pick/pick f [(/ W 2.0) (/ H 2.0)] W H {:filter w3/shop-instance?})]
    (is (= "near" (get-in hit [:instance :district])))))

;; --------------------------------------------------------------------------
;; lock state
;; --------------------------------------------------------------------------

(deftest locked-shops-carry-a-padlock-and-open-ones-do-not
  (let [locks (filter (fn [i] (= (:kind i) :lock)) (:instances frame))
        locked-ids (set (map :district locks))]
    (is (= (- world/district-count 1) (count locked-ids))
        "everything except the laundry starts locked")
    (is (not (contains? locked-ids "isic-9601")))))

(deftest a-locked-shop-is-still-tappable
  ;; you must be able to tap a locked shop to be told what it is and what opens it
  (let [b (body-for frame "isic-3900")
        p (pick/project frame (:center (pick/instance-box b)) W H)
        hit (pick/pick frame p W H {:filter w3/shop-instance?})]
    (is (= "isic-3900" (get-in hit [:instance :district])))))

(deftest clearing-a-district-changes-the-frame
  (let [after (world/clear-district fresh "isic-9601" 40)
        f2 (w3/render-ir after aspect)
        locks-of (fn [f] (set (map :district (filter (fn [i] (= (:kind i) :lock))
                                                     (:instances f)))))]
    (is (contains? (locks-of frame) "isic-4520") "locked before")
    (is (not (contains? (locks-of f2) "isic-4520")) "open after")
    (is (contains? (locks-of f2) "isic-3900") "the rest of the street stays locked")))

(deftest the-frame-is-deterministic
  ;; nothing reads a clock or a random source, so the same street draws the same way
  (is (= (w3/render-ir fresh aspect) (w3/render-ir fresh aspect)))
  (is (= (w3/render-ir fresh aspect) frame))
  (is (= frame (w3/render-ir fresh aspect nil))
      "nil camera opts is the default framing — CLI with no flags must not drift"))

;; --------------------------------------------------------------------------
;; camera overrides (#1751) — same IR pick/project reads
;; --------------------------------------------------------------------------

(defn- eye-xz
  "Ground-plane bearing of the eye from the origin (the street target)."
  [cam]
  (let [[x _ z] (:eye cam)]
    (Math/atan2 (double z) (double x))))

(defn- eye-range [cam]
  (let [[x y z] (:eye cam)]
    (Math/sqrt (double (+ (* x x) (* y y) (* z z))))))

(deftest orbit-spins-the-default-rig
  ;; 180° puts the eye on the opposite side of the street — the point of the flag.
  ;; Default azimuth is π/2 (eye on +z); +180° lands on -z.
  (let [base (w3/camera aspect)
        spun (w3/camera aspect {:orbit 180.0})
        delta (Math/abs (- (eye-xz spun) (eye-xz base)))
        ;; wrap to [0, π]; expect ~π
        delta (min delta (- (* 2.0 Math/PI) delta))]
    (is (> delta 2.5) (str "orbit 180 should flip azimuth, got delta=" delta))
    (is (> (Math/abs (- (nth (:eye spun) 2) (nth (:eye base) 2))) 1.0)
        "orbit 180 flips eye z across the origin")
    (is (< (Math/abs (- (eye-range spun) (eye-range base))) 1e-6)
        "orbit alone does not change distance")))

(deftest zoom-pulls-the-eye-in
  (let [base (eye-range (w3/camera aspect))
        close (eye-range (w3/camera aspect {:zoom 2.0}))
        far (eye-range (w3/camera aspect {:zoom 0.5}))]
    (is (< close (* base 0.6)) "zoom 2 is roughly half the range")
    (is (> far (* base 1.5)) "zoom 0.5 pushes out")))

(deftest fov-override-is-stated-on-the-frame
  (let [f (w3/render-ir fresh aspect {:fov 30.0})]
    (is (= 30.0 (get-in f [:globals :fov])))
    ;; narrower lens backs the eye off so the street still fits
    (is (> (eye-range (w3/camera aspect {:fov 30.0}))
           (eye-range (w3/camera aspect {:fov 60.0}))))))

(deftest absolute-eye-and-target-win-over-the-rig
  ;; eye must sit outside the fit volume — [12 40 -8] is inside and is refused
  (let [eye [12.0 40.0 -80.0]
        target [1.0 2.0 3.0]
        f (w3/render-ir fresh aspect {:eye eye :target target :orbit 90.0 :zoom 3.0})]
    (is (= eye (get-in f [:globals :eye])) "absolute :eye wins over orbit/zoom")
    (is (= target (get-in f [:globals :target])))))

(deftest underground-eye-is-refused
  (let [ex (try (w3/camera aspect {:eye [0.0 -1.0 50.0]})
                (catch Exception e e))]
    (is (instance? Exception ex))
    (is (re-find #"underground" (ex-message ex)))
    (is (= :underground (:reason (ex-data ex))))))

(deftest eye-inside-fit-volume-is-refused
  ;; extreme zoom pulls the rig into the fit sphere; silent 1e-6 clamp used to succeed
  (let [ex (try (w3/camera aspect {:zoom 100.0})
                (catch Exception e e))]
    (is (instance? Exception ex))
    (is (re-find #"fit volume" (ex-message ex)))
    (is (= :inside-fit (:reason (ex-data ex)))))
  (let [ex (try (w3/camera aspect {:eye [0.0 1.0 0.0]})
                (catch Exception e e))]
    (is (re-find #"fit volume" (ex-message ex)))
    (is (= :inside-fit (:reason (ex-data ex))))))

(deftest non-positive-zoom-is-refused-not-clamped
  (let [ex (try (w3/camera aspect {:zoom 0.0})
                (catch Exception e e))]
    (is (re-find #":zoom" (ex-message ex)))
    (is (= :bad-camera-number (:reason (ex-data ex))))))

(deftest overridden-camera-still-projects-and-picks
  ;; ADR acceptance: pick/project agree on the overridden IR — reuse the shop loop
  (let [f (w3/render-ir fresh aspect {:orbit 35.0 :zoom 1.2 :fov 42.0})]
    (is (ir/valid? f))
    (doseq [d world/districts]
      (let [b (body-for f (:id d))
            c (:center (pick/instance-box b))
            p (pick/project f c W H)
            hit (when p (pick/pick f p W H {:filter w3/shop-instance?}))]
        (is (some? p) (str (:id d) " still projects under the override"))
        (is (= (:id d) (get-in hit [:instance :district]))
            (str "pick at projected " (:id d) " returns that district, not a neighbour"))))))

(deftest absolute-eye-looking-at-a-shop-picks-that-shop
  ;; agent says "look at this building from here" — the IR must answer the same pixel
  (let [b (body-for frame "isic-9601")
        c (:center (pick/instance-box b))
        eye [(nth c 0) (+ (nth c 1) 25.0) (+ (nth c 2) 40.0)]
        f (w3/render-ir fresh aspect {:eye eye :target c :fov 40.0})
        p (pick/project f c W H)
        hit (pick/pick f p W H {:filter w3/shop-instance?})]
    (is (and p (< 0 (nth p 0) W) (< 0 (nth p 1) H))
        "chosen shop is on screen")
    (is (= "isic-9601" (get-in hit [:instance :district])))))
