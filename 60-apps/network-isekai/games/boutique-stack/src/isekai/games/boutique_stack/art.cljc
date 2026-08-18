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
