(ns isekai.games.kingdom-cascade.render-ir
  "Game state -> canonical EDN render-IR for the KAMI sprite2d path.

  The whole renderer boundary is this one pure function family. Nothing here
  touches a GPU, a canvas or the DOM: it emits data, and the KAMI stack
  (`kotoba.sprite2d` over `kotoba.webgpu`, falling back to `kotoba.webgl`)
  consumes it. That is what makes the board renderable, diffable, snapshot-
  testable and replayable from the same source.

  Layers, low to high:

      0  board ground plate
     10  per-cell ground tiles
     20  pieces
     30  blocks
     40  covers (ice sits on top of the piece it pins)
     50  effects
     90  HUD anchors

  ## Contract status — read before wiring this up

  The upstream consumer is network-isekai's `isekai/render_ir.cljc` and the
  `kotoba.sprite2d` namespace extracted in the 2026-07-01 kami-webgpu split.
  **Neither repository was reachable from the session that wrote this file**
  (GitHub access was scoped to `com-junkawasaki/root`), so the key names below
  follow the documented shape — `:render/sprite2d` components, an `:art/preset`
  base layer that a scene's own keys override — but have **not** been compiled
  against the real consumer.

  Treat this as the adapter seam. If upstream spells something differently,
  the fix belongs in this namespace and nowhere else: no other file in the
  game knows a renderer exists."
  (:require [isekai.games.kingdom-cascade.art :as art]
            [isekai.games.kingdom-cascade.board :as b]
            [isekai.games.kingdom-cascade.level :as level]))

(def cell-size 96)
(def gutter 4)

(defn- cell-origin
  [[x y]]
  [(* x (+ cell-size gutter))
   (* y (+ cell-size gutter))])

(defn- sprite
  [pos frame layer extra]
  (merge {:sprite/id (str (first pos) "-" (second pos) "-" (name frame))
          :sprite/frame frame
          :sprite/pos (cell-origin pos)
          :sprite/size [cell-size cell-size]
          :sprite/layer layer
          :sprite/tint (get art/palette frame [1.0 1.0 1.0 1.0])}
         extra))

(defn board->sprites
  "Every sprite for a settled board, in stable draw order.

  Order is deterministic because `board/positions` is: two runs over the same
  board must produce byte-identical IR, or the snapshot tests and the replay
  digest are both worthless."
  [board]
  (vec
   (mapcat
    (fn [pos]
      (let [c (b/cell board pos)
            piece (:piece c)
            blk (:block c)
            cov (:cover c)]
        (concat
         [(sprite pos :ground 10 nil)]
         (when (and piece (nil? blk))
           [(sprite pos (if (b/basic? piece) (:color piece) (:kind piece)) 20
                    (when-not (b/basic? piece) {:sprite/glow true}))])
         (when blk
           [(sprite pos (art/frame-for-cell board pos) 30 {:sprite/hp (:hp blk)})])
         (when cov
           [(sprite pos (:kind cov) 40 {:sprite/hp (:hp cov)})]))))
    (b/positions board))))

(defn- hud-model
  "The HUD as data. Rendered by DADS components, not by this namespace — the
  UI standard puts app chrome on `jp-go-dds`, and a sprite renderer drawing
  its own buttons would be a second design system."
  [state]
  (let [lvl (:level state)]
    {:hud/level (:level/name lvl)
     :hud/moves-left (:moves-left state)
     :hud/score (:score state)
     :hud/status (:status state)
     :hud/tokens art/hud-tokens
     :hud/goals (mapv (fn [goal n]
                        {:goal/label (or (:goal/block goal)
                                         (:goal/cover goal)
                                         (:goal/color goal))
                         :goal/frame (or (:goal/block goal)
                                         (:goal/cover goal)
                                         (:goal/color goal))
                         :goal/remaining n
                         :goal/done? (zero? n)})
                      (:level/goals lvl)
                      (level/remaining (:level/goals lvl) (:progress state)))}))

(defn scene
  "The full render-IR for a board state.

  `:art/preset` names the chapter look; the scene's own keys override it,
  which is the layering the upstream `scene->globals` / `scene->materials`
  already implement."
  ([state] (scene state (:board state)))
  ([state board]
   (let [preset (get art/presets (:level/preset (:level state)) (:royal-cellar art/presets))
         w (* (:w board) (+ cell-size gutter))
         h (* (:h board) (+ cell-size gutter))]
     {:scene/id (str "kingdom-cascade/" (:level/id (:level state)))
      :scene/dimension :2d
      :scene/camera {:camera/kind :orthographic
                     :camera/origin :top-left
                     :camera/viewport [w h]}
      :art/preset (:level/preset (:level state) :royal-cellar)
      :render/sky (:art/sky preset)
      :render/atlas art/atlas
      :render/sprite2d (board->sprites board)
      :render/hud (hud-model state)})))

;; ------------------------------------------------------------------ timeline

(def frame-duration-ms
  "How long each kind of frame is on screen. Settling is quick because a deep
  cascade otherwise stops feeling like a reward and starts feeling like a
  cutscene."
  {:swap 140
   :match 220
   :blast 260
   :combo 340
   :settle 110
   :reshuffle 400})

(defn- frame-effects
  "Effect sprites for a frame: the cells it is acting on, so the renderer can
  flash or shatter them without re-deriving what happened."
  [frame]
  (let [cells (or (:frame/cells frame)
                  (into #{} (mapcat :cells (:frame/groups frame))))]
    (vec (for [pos (sort-by (juxt second first) cells)]
           (sprite pos :ground 50 {:sprite/effect (:frame/kind frame)
                                   :sprite/tint [1.0 1.0 1.0 0.9]})))))

(defn timeline
  "The animation script for the last player action.

  Returns a vector of steps, each carrying the scene to show and how long to
  show it. The renderer plays this; it never recomputes game rules. An empty
  vector means the action changed nothing visible."
  [state]
  (vec
   (for [frame (:frames state)]
     {:step/kind (:frame/kind frame)
      :step/duration-ms (get frame-duration-ms (:frame/kind frame) 150)
      :step/scene (scene state (:frame/board frame))
      :step/effects (frame-effects frame)
      :step/moves (:frame/moves frame)
      :step/spawns (mapv :pos (:frame/spawns frame))})))

(defn hit-test
  "Screen point -> board position, or nil.

  Lives here rather than in the input layer because it is the exact inverse of
  `cell-origin`, and an inverse that drifts from its forward function is the
  classic source of \"the game registered the wrong tile\" bug reports."
  [board [px py]]
  (let [pitch (+ cell-size gutter)
        pos [(quot px pitch) (quot py pitch)]]
    (when (and (>= px 0) (>= py 0)
               (< (mod px pitch) cell-size)
               (< (mod py pitch) cell-size)
               (b/playable? board pos))
      pos)))
