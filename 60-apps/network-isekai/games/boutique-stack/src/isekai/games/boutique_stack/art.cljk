(ns isekai.games.boutique-stack.art
  "Mesh names, palette and HUD tokens.

  Same split as the other game and for the same reason: sprite and mesh
  colours are literal linear-sRGB, because a shader cannot read a CSS custom
  property; HUD chrome is DADS (`jp-go-dds`) over the shared `--hig-*` token
  contract and carries token *names* only.

  The token list stays off `--hig-spacing-*`, `--hig-text-*-size` and
  `--hig-radius-*`. The DADS bridge does not carry those, and they collapse to
  zero rather than erroring — the layout just quietly goes flat.")

(def meshes
  "Every mesh the scene can name. Kept as one list so `render-ir` can be
  checked against it: a typo'd mesh name draws nothing and raises nothing."
  {:floor {:mesh/file "shop/floor.glb"}
   :floor-locked {:mesh/file "shop/floor.glb"}
   :rack {:mesh/file "shop/rack.glb"}
   :crate {:mesh/file "shop/crate.glb"}
   :checkout {:mesh/file "shop/till.glb"}
   :player {:mesh/file "people/player.glb" :mesh/rig :humanoid}
   :staff-stocker {:mesh/file "people/staff.glb" :mesh/rig :humanoid}
   :staff-cashier {:mesh/file "people/staff.glb" :mesh/rig :humanoid}
   :customer {:mesh/file "people/customer.glb" :mesh/rig :humanoid}
   :tee {:mesh/file "goods/tee.glb"}
   :hoodie {:mesh/file "goods/hoodie.glb"}
   :jacket {:mesh/file "goods/jacket.glb"}
   :coat {:mesh/file "goods/coat.glb"}
   :sneaker {:mesh/file "goods/sneaker.glb"}
   :boot {:mesh/file "goods/boot.glb"}})

(def palette
  "Linear-sRGB tints. The goods meshes are modelled neutral and tinted here,
  so a seasonal re-skin is a palette change rather than fourteen new models."
  {:floor [0.72 0.80 0.94 1.0]
   :floor-locked [0.48 0.50 0.56 1.0]
   :rack [0.96 0.78 0.52 1.0]
   :crate [0.86 0.66 0.40 1.0]
   :checkout [0.94 0.94 0.96 1.0]
   :player [0.30 0.56 0.92 1.0]
   :staff-stocker [0.36 0.74 0.56 1.0]
   :staff-cashier [0.92 0.56 0.32 1.0]
   :customer [0.74 0.70 0.78 1.0]
   :tee [0.36 0.72 0.42 1.0]
   :hoodie [0.24 0.52 0.38 1.0]
   :jacket [0.86 0.34 0.32 1.0]
   :coat [0.58 0.24 0.42 1.0]
   :sneaker [0.30 0.74 0.80 1.0]
   :boot [0.42 0.32 0.26 1.0]})

(def presets
  {:boutique-day {:art/sky [0.90 0.93 0.98 1.0]
                  :art/key-light [1.0 0.98 0.92 1.0]}
   :boutique-evening {:art/sky [0.20 0.18 0.30 1.0]
                      :art/key-light [1.0 0.86 0.70 1.0]}})

(def hud-tokens
  {:hud/surface "--hig-color-surface"
   :hud/on-surface "--hig-color-on-surface"
   :hud/accent "--hig-palette-accent"
   :hud/money "--hig-color-success"
   :hud/danger "--hig-color-danger"
   :hud/hairline "--hig-hairline"
   :hud/font "--hig-font-sans"})

(def prims
  "What each mesh actually draws **today**.

  `art/meshes` names a `.glb` per mesh, and none of those files exist — the
  shop has no authored art yet. The live KAMI executor draws instanced
  primitives (`kami.webgpu.ir/default-geometry`: box / sphere / cylinder) with
  full PBR, and that is what every top-down game in this fleet renders through
  right now. So the primitive is not a placeholder for the renderer's benefit:
  it is the thing that draws. When a `.glb` lands, `:mesh/file` becomes
  reachable and this table is what it replaces.

  Sizes are in simulation units (1 unit = 1 cm), so they read against
  `world/tile` = 100 without a second scale living somewhere else."
  {:floor          {:geo :box      :size [300 6 300]   :roughness 0.92 :metallic 0.0}
   :floor-locked   {:geo :box      :size [300 6 300]   :roughness 0.96 :metallic 0.0}
   :rack           {:geo :box      :size [150 24 55]   :roughness 0.62 :metallic 0.05}
   :rack-post      {:geo :cylinder :size [10 90 10]    :roughness 0.45 :metallic 0.55}
   :crate          {:geo :box      :size [80 70 80]    :roughness 0.78 :metallic 0.0}
   :checkout       {:geo :box      :size [140 95 70]   :roughness 0.35 :metallic 0.10}
   :player         {:geo :cylinder :size [46 96 46]    :roughness 0.70 :metallic 0.0}
   :staff-stocker  {:geo :cylinder :size [44 92 44]    :roughness 0.70 :metallic 0.0}
   :staff-cashier  {:geo :cylinder :size [44 92 44]    :roughness 0.70 :metallic 0.0}
   :customer       {:geo :cylinder :size [42 88 42]    :roughness 0.74 :metallic 0.0}
   :head           {:geo :sphere   :size [40 40 40]    :roughness 0.66 :metallic 0.0}
   :tee            {:geo :box      :size [58 16 40]    :roughness 0.85 :metallic 0.0}
   :hoodie         {:geo :box      :size [60 18 42]    :roughness 0.88 :metallic 0.0}
   :jacket         {:geo :box      :size [62 20 44]    :roughness 0.72 :metallic 0.0}
   :coat           {:geo :box      :size [64 22 46]    :roughness 0.70 :metallic 0.0}
   :sneaker        {:geo :box      :size [50 20 30]    :roughness 0.55 :metallic 0.05}
   :boot           {:geo :box      :size [52 26 32]    :roughness 0.50 :metallic 0.05}
   :max-badge      {:geo :box      :size [70 14 14]    :roughness 0.30 :metallic 0.0 :emissive 1.4}
   :unlock-pad     {:geo :box      :size [220 3 220]   :roughness 0.40 :metallic 0.0 :emissive 0.7}})

(def skin
  "One neutral head tint. People are told apart by body colour, not face."
  [0.86 0.72 0.60 1.0])

(defn rgb
  "Palette entries are authored RGBA; the instance ABI reads three floats."
  [c]
  (let [[r g b] (or c [1.0 0.0 1.0 1.0])] [r g b]))
