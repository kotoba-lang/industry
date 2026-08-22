(ns isekai.games.boutique-stack.render-ir
  "Shop state -> canonical EDN render-IR for the KAMI 3D path.

  The shop is drawn as instances, not sprites: this is the pure-3D render-IR
  path (`entities->instances`, `scene->globals` / `scene->materials`, with
  `:art/preset` as a base layer the scene's own keys override) rather than the
  `:render/sprite2d` path Kingdom Cascade uses. Per the workspace 3D rule, no
  DOM, no CSS transform and no Canvas 2D is authoritative here — this
  namespace emits data and the KAMI stack draws it.

  ## Coordinates

  The simulation is 2D: `[x y]` in centimetres with y running away from the
  door. The scene is 3D with y up, so a floor position becomes `[x 0 z]` and
  the carried stack grows along y. That mapping lives here and nowhere else.

  ## Contract status — read before wiring this up

  The upstream consumer is network-isekai's `isekai/render_ir.cljc`. **That
  repository was not reachable from the session that wrote this file** (GitHub
  access was scoped to `com-junkawasaki/root`), so the vocabulary below
  follows the documented shape but has **not** been compiled against the real
  consumer.

  As in the other game, this is the one file that knows a renderer exists. If
  upstream spells something differently, fix it here; nothing else in the shop
  refers to a mesh, a camera or a colour."
  (:require [isekai.games.boutique-stack.art :as art]
            [isekai.games.boutique-stack.catalog :as catalog]
            [isekai.games.boutique-stack.economy :as econ]
            [isekai.games.boutique-stack.sim :as sim]
            [isekai.games.boutique-stack.world :as world]))

(def stack-step
  "Vertical spacing of one garment in a carried stack. The tall wobbling
  column is the signature read of this genre — it is how a player sees their
  carry upgrade without opening a menu."
  22)

(def ^:private ground 0)

(defn- ->scene-pos
  ([[x y]] [x ground y])
  ([[x y] height] [x height y]))

(defn- instance
  [id mesh pos extra]
  (merge {:instance/id id
          :instance/mesh mesh
          :instance/pos pos
          :instance/tint (get art/palette mesh [1.0 1.0 1.0 1.0])}
         extra))

;; ------------------------------------------------------------------- the shop

(defn- zone-instances
  [state world]
  (mapv (fn [zid]
          (let [z (world/zone world zid)
                open? (world/zone-unlocked? world (:unlocked state) zid)]
            (instance (str "zone-" (name zid))
                      (if open? :floor :floor-locked)
                      (->scene-pos (:zone/entry z [0 0]))
                      {:instance/zone zid
                       :instance/locked? (not open?)})))
        (:zone-order world)))

(defn- fixture-instances
  [state world]
  (vec
   (for [fid (:fixture-order world)
         :let [f (world/fixture world fid)
               open? (world/zone-unlocked? world (:unlocked state) (:fixture/zone f))]
         :when (contains? #{:rack :crate :checkout} (:fixture/kind f))]
     (let [stock (get-in state [:stock fid] 0)
           cap (:fixture/capacity f 0)]
       (instance (str "fixture-" (name fid))
                 (:fixture/kind f)
                 (->scene-pos (:fixture/pos f))
                 {:instance/item (:fixture/item f)
                  :instance/stock stock
                  :instance/capacity cap
                  :instance/dim? (not open?)})))))

(defn- shelf-stack-instances
  "The garments actually sitting on a rack. Drawn as a column so a full rack
  reads as full from across the room — a number on a shelf does not."
  [state world]
  (vec
   (for [f (world/open-fixtures world (:unlocked state) :rack)
         :let [fid (:fixture/id f)
               stock (get-in state [:stock fid] 0)]
         i (range stock)]
     (instance (str "shelf-" (name fid) "-" i)
               (:fixture/item f)
               (->scene-pos (:fixture/pos f) (+ ground (* stack-step i)))
               {:instance/kind :shelf-item}))))

;; ----------------------------------------------------------------- the people

(defn- carried-instances
  [id-prefix agent]
  (let [{:keys [item count]} (:carry agent)]
    (vec
     (for [i (range (or count 0))]
       (instance (str id-prefix "-carry-" i)
                 item
                 (->scene-pos (:pos agent) (+ 60 (* stack-step i)))
                 {:instance/kind :carried})))))

(defn- agent-instances
  [state world]
  (let [player (:player state)]
    (into
     (into [(instance "player" :player (->scene-pos (:pos player))
                      {:instance/goal (:goal player)})]
           (carried-instances "player" player))
     (mapcat (fn [member]
               (let [pid (str "staff-" (name (:id member)))]
                 (into [(instance pid
                                  (if (= :cashier (:role member)) :staff-cashier :staff-stocker)
                                  (->scene-pos (:pos member))
                                  {:instance/role (:role member)})]
                       (carried-instances pid member))))
             (:staff state)))))

(defn- customer-instances
  [state]
  (vec
   (for [c (:customers state)]
     (instance (str "customer-" (:id c))
               :customer
               (->scene-pos (:pos c))
               {:instance/state (:state c)
                :instance/basket (count (:basket c))
                ;; A shopper about to give up should look like it. The
                ;; renderer decides how; the simulation only says how close
                ;; they are.
                :instance/patience-ratio (quot (* 100 (:patience c)) sim/default-patience)}))))

;; --------------------------------------------------------------------- badges

(defn badges
  "Floor and overhead labels: MAX over a full rack, and a price pad on every
  locked department the chain currently permits.

  A locked zone that the chain does *not* permit yet gets no pad at all —
  showing a price for something unbuyable is how a player concludes the game
  is broken rather than that they have more to do first."
  [state world]
  (into
   (mapv (fn [r]
           {:badge/kind :max
            :badge/at (->scene-pos (:fixture/pos r) 140)
            :badge/fixture (:fixture/id r)})
         (filter (fn [r] (let [id (:fixture/id r)]
                           (and (pos? (world/capacity-of world id))
                                (= (get-in state [:stock id] 0)
                                   (world/capacity-of world id)))))
                 (world/open-fixtures world (:unlocked state) :rack)))
   (mapv (fn [z]
           {:badge/kind :unlock
            :badge/at (->scene-pos (:zone/entry z [0 0]) 10)
            :badge/zone (:zone/id z)
            :badge/label (:zone/name z)
            :badge/cost (:zone/cost z)
            :badge/affordable? (>= (:money state) (:zone/cost z 0))})
         (econ/unlockable state world))))

;; ---------------------------------------------------------------------- scene

(defn- hud-model
  [state world]
  (let [h (sim/hud state world)]
    {:hud/money (:money h)
     :hud/carry (:count (:carry h) 0)
     :hud/carry-capacity (:carry-capacity h)
     :hud/carry-item (:item (:carry h))
     :hud/departments (mapv (fn [d] {:dept/id d
                                     :dept/name (:dept/name (catalog/dept-by-id d))})
                            (:departments h))
     :hud/served (get-in state [:stats :served])
     :hud/lost (get-in state [:stats :lost])
     :hud/next-unlock (when-let [z (first (econ/unlockable state world))]
                        {:zone/id (:zone/id z)
                         :zone/name (:zone/name z)
                         :zone/cost (:zone/cost z)})
     :hud/upgrades (mapv (fn [[kind spec]]
                           (let [lvl (get-in state [:upgrades kind] 1)]
                             {:upgrade/kind kind
                              :upgrade/name (:upgrade/name spec)
                              :upgrade/level lvl
                              :upgrade/value (econ/upgrade-value kind lvl)
                              :upgrade/cost (econ/upgrade-cost kind lvl)}))
                         (sort-by key econ/upgrade-kinds))
     :hud/tokens art/hud-tokens}))

(defn scene
  "The full render-IR for a shop state."
  [state world]
  (let [preset-id (:shop/preset (:shop world) :boutique-day)
        preset (get art/presets preset-id (:boutique-day art/presets))]
    {:scene/id (str "boutique-stack/" (:shop/id (:shop world)))
     :scene/dimension :3d
     :scene/camera {:camera/kind :orthographic
                    :camera/rig :isometric
                    :camera/follow (->scene-pos (get-in state [:player :pos]))
                    :camera/height 900
                    :camera/pitch-deg 52}
     :art/preset preset-id
     :render/sky (:art/sky preset)
     :render/meshes art/meshes
     :render/instances (-> (zone-instances state world)
                           (into (fixture-instances state world))
                           (into (shelf-stack-instances state world))
                           (into (agent-instances state world))
                           (into (customer-instances state)))
     :render/badges (badges state world)
     :render/hud (hud-model state world)}))

;; ---------------------------------------------------------------------- input

(defn hit-test
  "Scene point -> the fixture or unlock pad under it, or nil.

  Only open fixtures and permitted unlock pads answer, so a stray tap on a
  locked department cannot start an interaction the simulation would refuse."
  [state world [px _ pz]]
  (let [pos [px pz]
        f (first (filter #(world/near? pos (:fixture/pos %) world/interaction-radius)
                         (concat (world/open-fixtures world (:unlocked state) :rack)
                                 (world/open-fixtures world (:unlocked state) :crate)
                                 (world/open-fixtures world (:unlocked state) :checkout))))]
    (if f
      [:fixture (:fixture/id f)]
      (when-let [z (first (filter #(world/near? pos (:zone/entry % [0 0])
                                                world/interaction-radius)
                                  (econ/unlockable state world)))]
        [:unlock (:zone/id z)]))))

;; ============================================================================
;; The KAMI frame — the real executor's contract
;; ============================================================================
;;
;; `scene` above is the shop's own vocabulary: meshes by name, badges, a HUD
;; model. It was written without the upstream consumer on the classpath and, as
;; ADR-2608630000 warned, **it guessed the vocabulary wrong**. The live executor
;; (`kami.webgpu`, ADR-2607120100) reads a much smaller map:
;;
;;   {:globals {:sky {:horizon [r g b] :sun-dir [x y z] :sun [r g b]}
;;              :lighting {…} :eye [x y z] :target [x y z]}
;;    :instances [{:pos [x y z] :color [r g b] :size [w h d] :yaw θ
;;                 :geo :box|:sphere|:cylinder
;;                 :metallic m :roughness r :emissive e}]}
;;
;; `kami-frame` is that map. It is the only thing in this game that the GPU
;; ever sees, and it is checked against the real `kami.webgpu.ir` in
;; `render_ir_test` rather than against a remembered shape.
;;
;; Units: the simulation is integer centimetres; the executor is unitless with
;; y up. One metre = one world unit, so the scale factor lives here and only
;; here.

(def ^:private cm 0.01)

(defn- p3
  "Sim [x y] (cm, y away from the door) -> scene [x y z] (metres, y up)."
  ([xy] (p3 xy 0))
  ([[x y] height] [(* cm x) (* cm height) (* cm y)]))

(defn- prim
  "One instance from the primitive table, positioned on the floor."
  [mesh tint xy height & [overrides]]
  (let [{:keys [geo size roughness metallic emissive]} (get art/prims mesh)
        [w h d] size]
    (merge {:pos (p3 xy height)
            :color (art/rgb tint)
            :size [(* cm w) (* cm h) (* cm d)]
            :geo geo
            :yaw 0
            :metallic (or metallic 0.0)
            :roughness (or roughness 0.65)
            :emissive (or emissive 0.0)}
           overrides)))

(defn- tint-of [mesh] (get art/palette mesh [1.0 0.0 1.0 1.0]))

(defn bounds
  "Axis-aligned extent of every fixture in the shop, in sim units.
  Used for the floor slab and the camera, so a differently shaped shop frames
  itself without anyone editing a constant."
  [world]
  (let [pts (keep (fn [id] (:fixture/pos (world/fixture world id))) (:fixture-order world))
        xs (map first pts)
        ys (map second pts)]
    (if (seq pts)
      {:min-x (apply min xs) :max-x (apply max xs)
       :min-y (apply min ys) :max-y (apply max ys)}
      {:min-x 0 :max-x world/tile :min-y 0 :max-y world/tile})))

(def ^:private floor-margin 180)

(defn- floor-instances
  "One slab for the whole floor, then one band per zone tinted by whether the
  department is open. A locked band is visibly a different floor rather than
  simply missing, which is what tells a player there is more shop to buy."
  [state world]
  (let [{:keys [min-x max-x min-y max-y]} (bounds world)
        w (+ (- max-x min-x) (* 2 floor-margin))
        d (+ (- max-y min-y) (* 2 floor-margin))
        cx (quot (+ min-x max-x) 2)
        cy (quot (+ min-y max-y) 2)
        entries (sort (map (fn [zid] (second (:zone/entry (world/zone world zid) [0 0])))
                           (:zone-order world)))
        gaps (map - (rest entries) entries)
        band (if (seq gaps) (apply min gaps) 300)]
    (into
     ;; The plinth sits a clear 8 cm below the department bands. Coplanar
     ;; tops z-fight, and at this scale that showed up as a staircase of
     ;; speckle along every band edge rather than as an obvious flicker.
     [{:pos (p3 [cx cy] -32) :color (art/rgb [0.34 0.36 0.42 1.0])
       :size [(* cm w) (* cm 24) (* cm d)] :geo :box :yaw 0
       :metallic 0.0 :roughness 0.95 :emissive 0.0}]
     (mapv (fn [zid]
             (let [z (world/zone world zid)
                   open? (world/zone-unlocked? world (:unlocked state) zid)
                   mesh (if open? :floor :floor-locked)
                   [_ zy] (:zone/entry z [0 0])]
               (prim mesh (tint-of mesh) [cx zy] 0
                     {:size [(* cm w) (* cm 6) (* cm band)]})))
           (:zone-order world)))))

(defn- fixture-kami
  [state world]
  (vec
   (for [fid (:fixture-order world)
         :let [f (world/fixture world fid)
               kind (:fixture/kind f)]
         :when (contains? #{:rack :crate :checkout} kind)
         :let [open? (world/zone-unlocked? world (:unlocked state) (:fixture/zone f))
               pos (:fixture/pos f)]
         inst (if (= :rack kind)
                ;; A rack is a top surface on two posts: the garments have to
                ;; sit on something, or a full rack reads as a pile on the floor.
                [(prim :rack-post (tint-of :rack) [(- (first pos) 60) (second pos)] 0)
                 (prim :rack-post (tint-of :rack) [(+ (first pos) 60) (second pos)] 0)
                 (prim :rack (tint-of :rack) pos 90)]
                [(prim kind (tint-of kind) pos 0)])]
     (cond-> inst
       (not open?) (assoc :color (art/rgb (tint-of :floor-locked)) :roughness 0.98)))))

(def ^:private rack-top 114)

(def ^:private shelf-step
  "Garments on a rack lie flat and overlap; garments in your arms are a
  swaying column. Same objects, different spacing — sharing `stack-step` put a
  two-metre tower of tees on every full rack, which is taller than the staff."
  9)

(defn- shelf-kami
  [state world]
  (vec
   (for [f (world/open-fixtures world (:unlocked state) :rack)
         :let [fid (:fixture/id f)
               item (:fixture/item f)
               stock (get-in state [:stock fid] 0)]
         i (range stock)]
     (prim item (tint-of item) (:fixture/pos f) (+ rack-top (* shelf-step i))))))

(defn- person-kami
  "A body and a head. Two primitives read as a person from a tycoon camera;
  one box does not, and the engine's skinned-mesh executor is a separate one
  this game has no asset for (ADR-2607121800 Phase 3)."
  [mesh tint agent]
  (let [pos (:pos agent)
        {:keys [size]} (get art/prims mesh)
        body-h (second size)]
    [(prim mesh tint pos 0)
     (prim :head art/skin pos body-h)]))

(defn- carried-kami
  [agent]
  (let [{:keys [item count]} (:carry agent)]
    (vec
     (for [i (range (or count 0))]
       (prim item (tint-of item) (:pos agent) (+ 100 (* stack-step i)))))))

(defn- people-kami
  [state]
  (-> []
      (into (person-kami :player (tint-of :player) (:player state)))
      (into (carried-kami (:player state)))
      (into (mapcat (fn [m]
                      (let [mesh (if (= :cashier (:role m)) :staff-cashier :staff-stocker)]
                        (into (person-kami mesh (tint-of mesh) m) (carried-kami m))))
                    (:staff state)))
      (into (mapcat (fn [c]
                      ;; A shopper running out of patience reddens. The
                      ;; simulation already counts the two walkout causes
                      ;; separately; this is the same fact, on screen.
                      (let [ratio (/ (:patience c) (double sim/default-patience))
                            [r g b] (art/rgb (tint-of :customer))
                            k (max 0.0 (min 1.0 ratio))
                            tint [(+ r (* (- 1.0 k) 0.26)) (* g (+ 0.45 (* 0.55 k)))
                                  (* b (+ 0.45 (* 0.55 k))) 1.0]]
                        (into (person-kami :customer tint c) (carried-kami c))))
                    (:customers state)))))

(defn- badge-kami
  [state world]
  (mapv (fn [b]
          (case (:badge/kind b)
            :max (prim :max-badge [1.0 0.86 0.30 1.0]
                       (:fixture/pos (world/fixture world (:badge/fixture b)))
                       (+ rack-top 130))
            :unlock (prim :unlock-pad
                          (if (:badge/affordable? b) [0.34 0.86 0.48 1.0] [0.86 0.72 0.28 1.0])
                          (:zone/entry (world/zone world (:badge/zone b)) [0 0])
                          8)))
        (badges state world)))

(defn camera
  "Frame the whole shop from the door side.

  A tycoon is read as a floor plan in perspective, so the camera holds the
  shop rather than following the player. The distance is driven by the
  footprint's *depth plus width*, not by the larger of the two: a phone is
  portrait, so the horizontal field of view is the tight one and framing on
  depth alone crops the shop off the side of the screen (measured — the first
  browser shot lost the right-hand racks entirely)."
  [world]
  (let [{:keys [min-x max-x min-y max-y]} (bounds world)
        cx (* cm (quot (+ min-x max-x) 2))
        cz (* cm (quot (+ min-y max-y) 2))
        w (* cm (- max-x min-x))
        d (* cm (- max-y min-y))
        ;; Fitted against the real frame, not guessed: on a 425x799 portrait
        ;; viewport the executor's 60 degree vertical FOV is only ~34 degrees
        ;; across, so the width term carries more weight than the depth term
        ;; even though the shop is deeper than it is wide.
        reach (+ (* 0.95 d) (* 1.10 w))]
    {:eye [(+ cx (* 0.10 reach)) (* 0.86 reach) (+ cz (* 0.78 reach))]
     :target [cx 0.0 (- cz (* 0.02 reach))]}))

(defn kami-frame
  "Shop state -> the render-IR `kami.webgpu/draw!` consumes.

  This is the single seam. Nothing else in the game names a colour, a mesh or
  a camera in the executor's vocabulary."
  [state world]
  (let [preset-id (:shop/preset (:shop world) :boutique-day)
        preset (get art/presets preset-id (:boutique-day art/presets))
        {:keys [eye target]} (camera world)]
    {:globals {:sky {:horizon (art/rgb (:art/sky preset))
                     :sun-dir [-0.38 -0.86 -0.34]
                     :sun (art/rgb (:art/key-light preset))}
               :lighting {:ambient [0.22 0.24 0.29] :ambient-sky 0.72 :rim 0.30}
               :eye eye
               :target target}
     :instances (-> (floor-instances state world)
                    (into (fixture-kami state world))
                    (into (shelf-kami state world))
                    (into (people-kami state))
                    (into (badge-kami state world)))}))
