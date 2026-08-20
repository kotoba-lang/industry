(ns world-ir-test
  "JVM tests that run the world map's render-IR through the REAL KAMI 2D stack —
  `kotoba.sprite2d.layout` from `kotoba-lang/sprite2d`, not a stand-in.

  That is the point of them. `world.cljc` emits a scene + snapshot and claims the SDK can
  frame it and hit-test it; these assertions are what make that a fact rather than a
  design intention. They also pin the three things that had to be added to the SDK to make
  a board game possible at all (`sdk-patches/0001-sprite2d-board-support.patch`): if the
  `:fit` camera, the `:text` primitive or `pick` were reverted upstream, every test below
  fails.

  Run:
    clojure -M:ir-test          # needs sprite2d checked out (see deps.edn)"
  (:require [clojure.test :refer [deftest is]]
            [kotoba.sprite2d.layout :as layout]
            [itonami.isic-9601.world :as world]))

(def W 900.0)
(def H 1600.0)   ;; phone portrait, like the reference screenshots

(def fresh (world/init))
(def ir (world/render-ir fresh))

;; --------------------------------------------------------------------------
;; the districts are real
;; --------------------------------------------------------------------------

(deftest every-district-names-a-repo-and-an-isic-class
  (doseq [d world/districts]
    (is (re-matches #"cloud-itonami-isic-\d+" (:repo d)) (str (:id d) " names a repo"))
    (is (re-matches #"\d{3,4}" (:isic d)) (str (:id d) " names an ISIC class"))
    (is (seq (:ops d)) (str (:id d) " has operations"))))

(deftest the-street-is-about-cleaning-not-about-laundry
  ;; the widening the map exists for: eight different subjects, only one of them clothes
  (let [subjects (set (map :subject world/districts))]
    (is (>= (count subjects) 8) "every district cleans a different kind of thing")
    (is (contains? subjects "衣類"))
    (is (contains? subjects "自動車"))
    (is (contains? subjects "土壌"))))

(deftest every-district-keeps-something-off-the-auto-set
  ;; the one rule the whole street shares — and the reason the map is an argument
  (doseq [d world/districts]
    (is (seq (:never-auto d)) (str (:id d) " has at least one never-auto op"))
    (is (string? (:never-auto-why d)) (str (:id d) " says why"))
    ;; and it really is absent from the phase-3 auto set that repo publishes
    (doseq [op (:never-auto d)]
      (is (not (some #{op} (:auto-at-3 d)))
          (str (:id d) " " op " must not appear in the phase-3 :auto set")))))

(deftest never-auto-ops-outnumber-the-districts
  ;; 9601 contributes two; the rest one each
  (is (= 9 (world/never-auto-op-count))))

;; --------------------------------------------------------------------------
;; progress
;; --------------------------------------------------------------------------

(deftest only-the-laundry-is-open-at-the-start
  (let [s (world/status fresh)]
    (is (= 1 (count (filter :unlocked? (:districts s)))))
    (is (:unlocked? (first (:districts s))))))

(deftest clearing-opens-exactly-the-next-district
  (let [after (world/clear-district fresh "isic-9601" 40)
        s (world/status after)]
    (is (= 2 (count (filter :unlocked? (:districts s)))))))

(deftest replaying-a-district-cannot-farm-unlocks
  (let [w (-> fresh
              (world/clear-district "isic-9601" 40)
              (world/clear-district "isic-9601" 40)
              (world/clear-district "isic-9601" 40))]
    (is (= 2 (count (filter :unlocked? (:districts (world/status w))))))))

(deftest a-locked-district-cannot-be-entered
  (is (nil? (:in (world/enter fresh "isic-3900"))))
  (is (= "isic-9601" (:in (world/enter fresh "isic-9601")))))

;; --------------------------------------------------------------------------
;; the SDK can actually draw and hit-test this map
;; --------------------------------------------------------------------------

(deftest every-district-is-drawn
  (let [dl (layout/draw-list (:scene ir) (:snap ir) W H)]
    (is (= world/district-count (count dl)))
    (is (= (set (map :id world/districts)) (set (map :tag dl))))))

(deftest the-whole-street-fits-on-screen
  ;; what the :fit camera was added for. Without it the default camera looks for a
  ;; "player" entity, finds none, anchors at world origin, and the street drifts off the
  ;; top of a portrait phone with nothing to indicate anything went wrong.
  (let [dl (layout/draw-list (:scene ir) (:snap ir) W H)]
    (doseq [op dl]
      (is (and (> (:sx op) 0) (< (:sx op) W))
          (str (:tag op) " is on screen horizontally (sx=" (:sx op) ")"))
      (is (and (> (:sy op) 0) (< (:sy op) H))
          (str (:tag op) " is on screen vertically (sy=" (:sy op) ")")))))

(deftest it-fits-on-a-different-screen-too
  ;; a fixed :scale can only be right for one viewport; :fit re-solves per viewport
  (doseq [[w h] [[360.0 640.0] [1280.0 720.0] [900.0 1600.0]]]
    (let [dl (layout/draw-list (:scene ir) (:snap ir) w h)]
      (is (every? (fn [op] (and (> (:sx op) 0) (< (:sx op) w)
                                (> (:sy op) 0) (< (:sy op) h)))
                  dl)
          (str "street fits " w "x" h)))))

(deftest tapping-a-shop-returns-that-shop
  ;; the entire interaction of a map screen. `pick` did not exist before 2026-08-08.
  (let [dl (layout/draw-list (:scene ir) (:snap ir) W H)]
    (doseq [op dl]
      (let [hit (layout/pick (:scene ir) (:snap ir) W H [(:sx op) (:sy op)])]
        (is (= (:tag op) (:tag hit))
            (str "tapping " (:tag op) " at its drawn position picks it"))))))

(deftest tapping-empty-street-picks-nothing
  (is (nil? (layout/pick (:scene ir) (:snap ir) W H [2.0 2.0]))))

(deftest shop-signs-are-part-of-the-sprite
  ;; the :text primitive: each building carries its own name and ISIC code, so they move
  ;; with it and survive a camera change. Before it existed, text on this painter was
  ;; only the transient screen-space fx layer.
  (let [sp (get-in ir [:scene :sprites :isic-9601])
        texts (filter (fn [p] (= (first p) :text)) sp)]
    (is (>= (count texts) 3) "name, ISIC code and subject are all sprite parts")
    (is (some (fn [p] (= (:text (second p)) "クリーニング")) texts))
    (is (some (fn [p] (= (:text (second p)) "ISIC 9601")) texts))))

(deftest sign-text-widens-the-hit-box
  ;; a sign wider than its building must still be tappable, which only works because
  ;; sprite-bounds understands :text
  (let [sp (get-in ir [:scene :sprites :isic-9601])
        b (layout/sprite-bounds sp)
        without (layout/sprite-bounds (vec (remove (fn [p] (= (first p) :text)) sp)))]
    (is (some? b))
    (is (some? without))
    ;; the subject label sits below the body, so the box must reach further down
    (is (> (nth b 3) (nth without 3)))))

(deftest locked-shops-are-drawn-differently-but-still-hittable
  ;; you must be able to tap a locked shop to be told what it is and what opens it
  (let [dl (layout/draw-list (:scene ir) (:snap ir) W H)
        locked (first (filter (fn [op] (= (:tag op) "isic-3900")) dl))]
    (is (some? locked))
    (is (= "isic-3900" (:tag (layout/pick (:scene ir) (:snap ir) W H
                                          [(:sx locked) (:sy locked)]))))
    ;; and it looks locked: the padlock arc is only present while locked
    (is (some (fn [p] (= (first p) :arc)) (get-in ir [:scene :sprites :isic-3900])))
    (is (not-any? (fn [p] (= (first p) :arc)) (get-in ir [:scene :sprites :isic-9601])))))

(deftest unlocking-changes-what-is-drawn
  (let [after (world/clear-district fresh "isic-9601" 40)
        ir2 (world/render-ir after)]
    (is (not-any? (fn [p] (= (first p) :arc)) (get-in ir2 [:scene :sprites :isic-4520]))
        "the newly opened shop loses its padlock")
    (is (some (fn [p] (= (first p) :arc)) (get-in ir2 [:scene :sprites :isic-3900]))
        "the ones further down the street keep theirs")))
