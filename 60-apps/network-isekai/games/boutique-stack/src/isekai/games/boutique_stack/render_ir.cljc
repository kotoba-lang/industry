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
