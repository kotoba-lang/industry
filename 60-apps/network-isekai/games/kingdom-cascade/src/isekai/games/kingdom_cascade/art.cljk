(ns isekai.games.kingdom-cascade.art
  "Atlas layout and palette.

  Two different colour systems meet in this game and they must not be
  confused:

  - **Sprites** are drawn by the GPU from an atlas. Their colours are literal
    linear-sRGB tuples, because a WGSL shader cannot read a CSS custom
    property. They live here.
  - **The HUD** is DADS (`jp-go-dds`) chrome over the shared `--hig-*` token
    contract, per the workspace UI standard. It carries token *names*, never
    literals — see `hud-tokens` below.

  A note that costs an afternoon if you learn it the hard way: the
  `jp-go-dds` bridge re-defines `--hig-color-*`, `--hig-palette-*`,
  `--hig-font-*` and `--hig-hairline`, and **not** `--hig-spacing-*`,
  `--hig-text-*-size` or `--hig-radius-*`. Those resolve to nothing on a DADS
  base and silently collapse to zero rather than erroring, so the HUD spec
  below stays off them and uses DADS primitives or `em` for metrics."
  (:require [isekai.games.kingdom-cascade.board :as b]))

(def atlas
  "One 8x2 atlas page of 128px cells. Frame ids are the same keywords the
  game logic already uses, so no lookup table has to be kept in sync."
  {:atlas/id "kingdom-cascade/pieces-v1"
   :atlas/page "atlas/pieces-v1.png"
   :atlas/cell [128 128]
   :atlas/grid [8 2]
   :atlas/frames
   {:coin [0 0] :gem [1 0] :clover [2 0] :goblet [3 0] :crown [4 0] :rune [5 0]
    :arrow-h [6 0] :arrow-v [7 0]
    :bomb [0 1] :prism [1 1]
    :crate [2 1] :stone [3 1] :stone-cracked [4 1]
    :ice [5 1] :frost [6 1]
    :ground [7 1]}})

(def palette
  "Linear-sRGB tints, keyed by frame id. The atlas art is painted neutral and
  tinted here, so a chapter can re-skin the board without a new atlas page."
  {:coin [0.98 0.78 0.22 1.0]
   :gem [0.87 0.20 0.24 1.0]
   :clover [0.30 0.74 0.32 1.0]
   :goblet [0.24 0.50 0.87 1.0]
   :crown [0.70 0.42 0.90 1.0]
   :rune [0.20 0.78 0.80 1.0]
   :arrow-h [1.0 1.0 1.0 1.0]
   :arrow-v [1.0 1.0 1.0 1.0]
   :bomb [1.0 1.0 1.0 1.0]
   :prism [1.0 1.0 1.0 1.0]
   :crate [0.62 0.44 0.26 1.0]
   :stone [0.55 0.56 0.58 1.0]
   :stone-cracked [0.62 0.63 0.65 1.0]
   :ice [0.72 0.88 0.96 0.85]
   :frost [0.60 0.80 0.94 0.92]
   :ground [0.16 0.14 0.20 0.55]})

(def presets
  "Board framing per chapter. Referenced by `:art/preset` in the scene, which
  is how the upstream render-IR layers a scene's own overrides on top of a
  shared look."
  {:royal-cellar {:art/sky [0.10 0.08 0.13 1.0]
                  :art/board-tint [1.0 0.97 0.90 1.0]}
   :throne-hall {:art/sky [0.16 0.10 0.06 1.0]
                 :art/board-tint [1.0 0.94 0.82 1.0]}})

(def hud-tokens
  "Token *names* for the DADS HUD. Values deliberately absent: the whole point
  of the `--hig-*` contract is that the app does not re-derive them."
  {:hud/surface "--hig-color-surface"
   :hud/on-surface "--hig-color-on-surface"
   :hud/accent "--hig-palette-accent"
   :hud/danger "--hig-color-danger"
   :hud/hairline "--hig-hairline"
   :hud/font "--hig-font-sans"})

(defn frame-for-cell
  "The atlas frame a cell should draw for its occupant, or nil for bare
  ground. Cracked stone gets its own frame so damage is visible before the
  block gives way — a two-hit block that looks identical after one hit reads
  as a bug to the player."
  [board pos]
  (let [c (b/cell board pos)]
    (cond
      (nil? c) nil
      (:block c) (let [{:keys [kind hp]} (:block c)]
                   (if (and (= :stone kind) (= 1 hp)) :stone-cracked kind))
      (:piece c) (let [p (:piece c)]
                   (if (b/basic? p) (:color p) (:kind p)))
      :else nil)))
