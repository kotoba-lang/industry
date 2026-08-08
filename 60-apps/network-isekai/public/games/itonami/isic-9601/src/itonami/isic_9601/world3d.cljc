(ns itonami.isic-9601.world3d
  "The street in 3D — the canonical `kami.webgpu` render-IR for 「営みの街」.

  This is the authoritative view. CLAUDE.md's 3D rule is repo-wide and mandatory: 3D goes
  through the canonical kami-engine stack, WebGPU + WGSL first with a WebGL 2.0 fallback,
  and **both backends consume this same EDN render-IR** — no second engine, no Canvas2D
  standing in for a 3D viewport, no per-app renderer.

  So there is exactly one scene description here and two runtimes read it.
  `kami.webgl/pick-backend` chooses which.

  ## What it draws

  The reference is a tilted 3/4 city block: chunky boxes, flat colour, a road winding past
  shopfronts, padlocks on the ones you have not opened. That maps onto the IR directly,
  because the IR's unit *is* the box — `ir/instance` takes a ground position, a size and a
  colour, and the executor instances cuboids. A shopfront is a body, an awning, a sign
  board and a roof sign; a padlock is a shackle and a body. Nothing here needs a mesh.

  ## Coordinates

  `world.cljc` places districts on a 2D map as `[x north]`. Here that becomes
  `[x 0 (- north)]` — the engine is y-up with -z running away from the camera, so map
  north is -z. `district->world` is the only place that conversion happens.

  ## Reading a tap

  Every instance carries `:kind` and, where it belongs to a shop, `:district`. The
  executor ignores keys it does not understand (its own contract), and
  `kami.webgpu.pick/pick` takes a `:filter`, so a tap can be resolved against shopfronts
  only — a map where tapping the road selects the road is a map whose shops are hard to
  hit.

  Same subset discipline as the rest of the game: pure data, pure functions, no interop,
  no keyword-as-function."
  (:require [itonami.isic-9601.world :as world]
            [kami.webgpu.ir :as ir]))

;; --------------------------------------------------------------------------
;; palette — flat, saturated, one hue family per district (from world.cljc)
;; --------------------------------------------------------------------------

(def ground-color [0.44 0.70 0.38])
(def road-color   [0.62 0.63 0.66])
(def road-line    [0.95 0.96 0.97])
(def sign-color   [0.98 0.98 1.0])
(def lock-color   [0.99 0.79 0.16])
(def trunk-color  [0.45 0.32 0.22])
(def leaf-color   [0.24 0.55 0.30])

(defn- dim
  "Locked shops are drawn desaturated toward concrete — the reference art's grey blocks."
  [c]
  (mapv (fn [x] (+ 0.46 (* 0.22 x))) c))

;; --------------------------------------------------------------------------
;; layout
;; --------------------------------------------------------------------------

(def scale
  "Map units per world unit. `world.cljc` lays the street out in sprite-sized numbers;
  the 3D street wants tens of units, not hundreds."
  0.045)

(defn district->world
  "Map `[x north]` → engine `[x 0 z]`. North is -z; this is the only conversion."
  [at]
  [(* scale (nth at 0)) 0.0 (* scale (- (nth at 1)))])

(def street-radius
  "How far the furthest shop sits from the origin — the camera frames this."
  (reduce max 1.0
          (map (fn [d]
                 (let [p (district->world (:at d))]
                   (Math/sqrt (double (+ (* (nth p 0) (nth p 0))
                                         (* (nth p 2) (nth p 2)))))))
               world/districts)))

;; --------------------------------------------------------------------------
;; instances
;; --------------------------------------------------------------------------

(defn- inst
  "An instanced cuboid. `pos` is the point it STANDS on — the executor lifts it by half
  its height — so a box at y=0 sits on the ground."
  [kind pos color size extra]
  (merge {:kind kind :pos pos :color color :size size :yaw 0.0} extra))

(defn shopfront
  "One district as a group of boxes: body, awning, sign board, and a padlock while locked.
  Every piece carries `:district` so a pick anywhere on the building resolves to the shop."
  [d unlocked?]
  (let [[x _ z] (district->world (:at d))
        base (:hue d)
        body (if unlocked? base (dim base))
        sign (if unlocked? sign-color (dim sign-color))
        tag {:district (:id d)}
        w 11.0 h 7.0 dep 9.0]
    (vec
     (concat
      [(inst :shop [x 0.0 z] body [w h dep] tag)
       ;; awning: a thin slab across the front face
       (inst :shop-awning [x (* h 0.62) (+ z (* dep 0.5))] sign [(* w 1.06) 0.8 1.6] tag)
       ;; sign board above the door, facing the camera
       (inst :shop-sign [x h z] sign [(* w 0.82) 2.2 0.6] tag)
       ;; roof block, so the silhouette is not a plain cube
       (inst :shop-roof [x (+ h 2.2) z] (dim body) [(* w 0.55) 1.2 (* dep 0.55)] tag)]
      (when-not unlocked?
        ;; padlock floating in front of the door — the reference's lock motif
        [(inst :lock [x (* h 0.5) (+ z (* dep 0.5) 2.0)] lock-color [2.2 2.6 1.2] tag)
         (inst :lock-shackle [x (+ (* h 0.5) 2.4) (+ z (* dep 0.5) 2.0)] lock-color
               [1.4 1.4 0.7] tag)])))))

(defn- road-ring
  "A ring road threading past every shopfront. Segments are boxes laid flat and yawed to
  the tangent — the winding grey ribbon in the reference, without needing a mesh."
  [segments]
  ;; outside everything: a ring drawn at a fraction of the street radius runs straight
  ;; through the shopfronts, which the first CLI render made obvious at a glance
  (let [r (+ street-radius 16.0)
        two-pi (* 2.0 Math/PI)]
    (vec
     (mapcat
      (fn [i]
        (let [a (* two-pi (/ (double i) segments))
              cx (* r (Math/cos a))
              cz (* r (Math/sin a))
              ;; tangent direction; a box's length runs along x, so yaw by the tangent
              yaw (- (+ a (/ Math/PI 2.0)))
              seg-len (+ 2.0 (/ (* two-pi r) segments))]
          [(inst :road [cx 0.02 cz] road-color [seg-len 0.12 5.4] {:yaw yaw})
           (inst :road-line [cx 0.10 cz] road-line [(* seg-len 0.42) 0.06 0.28] {:yaw yaw})]))
      (range segments)))))

(defn- tree [x z]
  [(inst :tree [x 0.0 z] trunk-color [0.5 1.4 0.5] {})
   (inst :tree [x 1.4 z] leaf-color [2.4 2.8 2.4] {})])

(defn- scenery
  "Deterministic tree scatter, seeded — nothing here reads a clock or a random source, so
  the same street draws the same way every run."
  [n]
  (loop [i 0 s 12345 acc []]
    (if (>= i n)
      acc
      (let [s1 (mod (* 16807 s) 2147483647)
            s2 (mod (* 16807 s1) 2147483647)
            a (* 2.0 Math/PI (/ (double s1) 2147483647.0))
            r (+ (* street-radius 0.95) (* street-radius 0.55 (/ (double s2) 2147483647.0)))
            x (* r (Math/cos a))
            z (* r (Math/sin a))]
        (recur (inc i) s2 (into acc (tree x z)))))))

(defn ground []
  ;; `:pos` is the point a box STANDS on and it extends UP by its height, so a 1-unit slab
  ;; whose top must be y=0 stands at y=-1. Standing it at -0.5 puts its top at +0.5 and
  ;; swallows the road, which sits at 0.02 — the first CLI render showed a street with no
  ;; road on it and no error anywhere.
  [(inst :ground [0.0 -1.0 0.0] ground-color
         [(* street-radius 4.0) 1.0 (* street-radius 4.0)] {})])

(defn player
  "The little figure from the reference, standing outside the shop that is open."
  [w]
  (let [open (last (filter (fn [d] (world/unlocked? d (:cleared w))) world/districts))
        [x _ z] (district->world (:at (or open (first world/districts))))]
    [(inst :player [x 0.0 (+ z 8.0)] [0.20 0.45 0.85] [1.2 2.0 1.2] {})
     (inst :player [x 2.0 (+ z 8.0)] [0.96 0.80 0.68] [1.1 1.1 1.1] {})]))

;; --------------------------------------------------------------------------
;; the frame
;; --------------------------------------------------------------------------

(def fov-deg
  "Vertical field of view. Stated here because three things read it and must agree: the
  executor, `kami.webgpu.pick`, and the camera fit below."
  46.0)

(def pitch-deg
  "How far down the camera looks. The reference framing is steep — a near-overhead 3/4 —
  and steepness is not only a look: a shallow camera turns a wide, flat street into a thin
  band across the middle of a portrait frame, with sky above and below doing nothing."
  52.0)

(def camera-rig
  "A high, near-overhead 3/4 view — the reference's framing, which is essentially
  axis-aligned with a steep pitch rather than rotated off it. `:azimuth` is π/2 (the eye on
  +z looking down -z) on purpose: an off-axis camera slides the two columns of the street
  across each other, and a shop hidden behind another is a shop that cannot be tapped.

  `:distance` is absent on purpose: it is solved per viewport by `ir/fit-rig`, because the
  horizontal field of view is the vertical one widened by the aspect. A constant distance
  tuned on a desktop puts half this street off both sides of a portrait phone, and nothing
  reports it — the shops are simply not on screen."
  {:azimuth (/ Math/PI 2.0)
   :look-height 2.0})

(def fit-radius
  "The sphere the camera must frame: the furthest shopfront plus its own bulk."
  (+ street-radius 9.0))

(defn camera
  "eye/target for the street at this viewport aspect (width/height).

  `ir/fit-distance` gives the RANGE at which the street fits — the straight-line distance
  from the target. A rig states its ground distance and its height separately, so the range
  is split between them by `pitch-deg`; handing the range straight to `:distance` and
  picking a height independently is how you end up with a camera that is both too far away
  and too low, which is what the first CLI render looked like."
  ([] (camera (/ 9.0 16.0)))
  ([aspect]
   (let [range (ir/fit-distance fit-radius fov-deg aspect)
         th (* pitch-deg (/ Math/PI 180.0))]
     (ir/rig->camera (assoc camera-rig
                            :distance (* range (Math/cos th))
                            :height (* range (Math/sin th)))
                     [0.0 0.0]))))

(defn instances
  "Every box in the street, scenery first so shopfronts sort later in the vector (order is
  not depth — the executor depth-tests — but it keeps the interesting things together)."
  [w]
  (vec
   (concat (ground)
           (road-ring 24)
           (scenery 26)
           (mapcat (fn [d] (shopfront d (world/unlocked? d (:cleared w)))) world/districts)
           (player w))))

(defn render-ir
  "The frame for a viewport of this aspect (width/height).

  `:globals :fov` is stated rather than left to the executor's default, because
  `kami.webgpu.pick` reads the same key — a frame that leaves it implicit is a frame whose
  picking silently depends on a default staying put. The aspect argument is required for
  the same class of reason: a scene that does not know its viewport cannot promise to fit
  in it."
  ([w] (render-ir w (/ 9.0 16.0)))
  ([w aspect]
  (let [{:keys [eye target]} (camera aspect)]
    {:globals {:sky {:horizon [0.53 0.74 0.93]
                     :sun-dir [0.35 -0.86 0.36]
                     :sun [1.0 0.97 0.90]}
               :eye eye
               :target target
               :fov fov-deg
               :near 0.5
               :far 4000.0}
     :instances (instances w)})))

(def shop-kinds
  "The parts of a building a tap should resolve to. Roads, ground, trees and the player
  are not tap targets."
  #{:shop :shop-awning :shop-sign :shop-roof :lock :lock-shackle})

(defn shop-instance?
  "Filter for `kami.webgpu.pick/pick` — restricts a tap to shopfronts."
  [i]
  (contains? shop-kinds (:kind i)))
