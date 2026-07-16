# ADR-2607121800: network-isekai small-scale "AAA/Nintendo-quality" roadmap — level design, art direction, character/texture pipeline

- **Status**: accepted (2026-07-12)
- **Related**: ADR-2607120100 (kami-engine cljs-direct WebGPU rendering standard),
  `gftdcojp/network-isekai` local ADR-0033 (at6-texan 3D redesign, the renderer
  half of the same lineage), ADR-2607051400 (kami-engine WebGPU SDK
  consolidation), `90-docs/adr/2607022340-kami-isekai-assets-template-lib.md`
  (the accepted-but-unbuilt `kami.isekai.palette` precedent this roadmap
  supersedes with a concrete, in-repo-first plan), `network-isekai`'s own
  ADR-0008 (Asset Hub).

## Context

Asked how to raise network-isekai's small-scale games toward "AAA title /
Nintendo game" perceived quality, investigation turned up a specific,
addressable gap: the technical building blocks for higher quality **already
exist in this ecosystem**, but three of them are either disconnected from the
actual game pipeline or sitting completely unused.

1. **Character/texture rendering is split into two disconnected executors.**
   `kami.webgpu` (`kotoba-lang/webgpu`'s `kami/webgpu.cljs`) is what every
   network-isekai top-down game (at6-texan, royale, goriketsu, drive, ...)
   renders through — it is pure procedural-primitive: instanced lit
   cuboids/spheres/cylinders from `kami.webgpu.ir/default-geometry`, materials
   are flat `{:color :metallic :roughness :emissive}` uniforms, no UV
   attribute, no texture binding anywhere in the generated shader
   (`kami.shaders/lit-shader`). Separately, `kami.webgpu.mesh`
   (`kotoba-lang/webgpu`'s `kami/webgpu/mesh.cljs`) is a second, additive
   WebGPU executor that already does real arbitrary-mesh geometry, 4-joint
   linear-blend skinning, glTF-style morph-target blending, and **real
   texture upload** (`upload-texture!` → `createImageBitmap` →
   `copyExternalImageToTexture`) — live and working today via
   `isekai.stage.cljc` (network-isekai's VRM dance-stage avatars: hair,
   head, body, accessories, all textured and skinned) and
   `kami-app-character-creator`. These two executors have never been wired
   together — a *game* (as opposed to a dance stage) has no path to a real
   textured/skinned character.
2. **A working, structured level-design library sits completely unused.**
   `kotoba-lang/level`'s `kotoba.level` (`{:spawns :zone :objective}` —
   player/bot spawn points, a shrinking-storm zone with `zone-radius`/
   `in-zone?`, a last-standing objective) is already on network-isekai's own
   classpath (`bb.edn` → `../../kotoba-lang/level/src`) and `royale`'s
   `default-level` is literally named `"KAMI Royale"` for it — but no game
   `require`s `kotoba.level`, and royale itself has no `:spawns`/`:zone`
   data at all, only flat procedural prop scatter (`:render/props`). Level
   layout everywhere else is hardcoded `f32` position constants inside each
   game's compiled-guest `logic.cljc` (e.g. at6-texan's seven signal
   coordinates, drive's gate/cone positions) — code, not data.
3. **Art direction is raw, per-game, uncomposed RGB.** Every game's
   `scene.edn` repeats its own `:render/sky {:zenith :horizon :sun-dir :sun
   :fog :ground}` and `:render/profiles {tag {:color ...}}` inline, with no
   reusable named unit. `kotoba-lang/kami-engine` (a different domain —
   CAD/vehicle-sim/terrain config, not network-isekai games) has already
   proven exactly this pattern works well: `kami-postfx-scene/data/postfx.edn`
   defines named mood presets (`:nintendo`, `:retro`, `:final-fantasy`,
   `:baminiku-character`), `kami-terrain-scene/data/biomes.edn` defines named
   biome presets bundling a 4-layer palette + heightmap params. Network-isekai
   has no equivalent, and the one ADR that proposed one for this exact
   domain — `90-docs/adr/2607022340-kami-isekai-assets-template-lib.md`'s
   `kami.isekai.palette` — was accepted but its target repo
   (`kotoba-lang/kami-isekai-assets`) was never built (does not exist in this
   checkout; a stale dependency reference to it was independently found and
   removed from network-isekai's `deps.edn` by a parallel session during this
   same investigation).

## Decision

Close these three gaps in ascending order of cost/risk, each phase
independently shippable:

### Phase 1 — wire `kotoba.level` into `royale` (cheapest, this pass)

Host-side only, no guest/`logic.cljc` change needed (mirrors the existing
`:platformer` config pattern in `kotoba.host/tick!`, where the host reads a
scene-supplied config and applies an effect the compiled guest never sees).
`royale/scene.edn` gains a `:level` block matching `kotoba.level`'s own
schema; `isekai/game.cljc` requires `kotoba.level`, computes `zone-radius`
each tick, renders it as one more render-IR instance (a thin ring, using the
existing procedural-primitive path — no mesh work needed for this phase),
and despawns `"bot"`-tagged entities that fall outside the zone directly on
the host ECS atom. Proves level-design-as-data is real, end to end, with
code that already existed and just needed a caller.

### Phase 2 — a named art-direction preset, in-repo first (this pass)

Rather than reviving the unbuilt `kami-isekai-assets` repo or extending Asset
Hub (ADR-0008) with a new asset kind up front, start with the cheapest
version that proves the mechanism: `src/isekai/art_presets.cljc` (new,
in `network-isekai` itself) holds a small map of named presets, each
bundling `:render/sky` + `:render/profiles` defaults — the same
named-preset-bundling-properties shape `postfx.edn`/`biomes.edn` already
validate, scoped to what `kami.webgpu`'s render-IR actually consumes.
`isekai/render_ir.cljc`'s `scene->globals`/`scene->materials` merge a
scene's `:art/preset` reference as a base layer, with the scene's own
inline `:render/sky`/`:render/profiles` (if present) overriding it. First
consumer: extract at6-texan's current sky/material values verbatim into one
preset (`:iyashikei-coast`) and switch its `scene.edn` to reference it —
proving the mechanism is lossless before any other game adopts it.
**Promoting this into a first-class, content-addressed Asset Hub kind (a
5th `:art-preset` kind alongside `:model3d`/`:texture`/`:audio`/`:scene`) is
a deliberate follow-up, not done here** — no reason to design the
content-addressing/versioning story before there is more than one preset
proving the shape is right.

### Phase 3 — unify the character/texture rendering executors (deferred, flagship: `goriketsu`)

The highest-value, highest-risk phase: let a game's `:render/profiles` entry
reference a real asset (`:mesh <asset-id>`, resolved via Asset Hub's existing
`:model3d`/`:texture` kinds) instead of only a procedural `:geo` primitive,
and have `isekai/game.cljc` draw mesh-backed instances through
`kami.webgpu.mesh` in a second pass layered on the primitive pass — the
exact `loadOp:"load"` two-pass-in-one-frame pattern `isekai.stage.cljc`
already proves works for VRM avatars (`stage.cljc:354-391`). Flagship target:
**`goriketsu`** (player + gorilla — two characters, a real proving ground for
"does this look like a designed character now"), replacing their current
flat-colored boxes/capsules with a real skinned/textured mesh. Concrete
prerequisites this ADR does NOT resolve: (a) `org-vrmc-vrm`'s texture
extraction today is baseColor-only (no normal/metallic-roughness/occlusion/
emissive map extraction) — sufficient for a first pass, a known limit for
later polish; (b) an actual test character asset (a small humanoid or
gorilla glTF/VRM) has to be sourced or authored — this is not a
code-only step and is the reason this phase is not started in this pass.

## Consequences

- Phases 1 and 2 touch only `network-isekai` (royale's scene/game.cljc,
  a new `art_presets.cljc`, at6-texan's scene.edn) — no new repos, no
  manifest changes.
- Phase 3, when undertaken, is a cross-repo change (`kotoba-lang/webgpu`'s
  render-IR contract, `org-vrmc-vrm`'s texture extraction, `network-isekai`'s
  `game.cljc`) and needs its own follow-up ADR addendum once a concrete asset
  is in hand.
- The `kami-isekai-assets`/`kami.isekai.palette` ADR
  (2607022340) stays accepted but is treated as superseded-in-approach by
  this ADR's Phase 2 for the *first* preset — its content-hub framing is
  the natural target once Phase 2's in-repo mechanism has more than one
  preset to justify it.

## Follow-up

- Promote `art_presets.cljc`'s presets into Asset Hub as a first-class kind
  once there is more than one preset.
- Extend `org-vrmc-vrm` texture extraction beyond baseColor (normal/
  metallic-roughness/occlusion/emissive) ahead of or during Phase 3.
- Source/author a first test character asset for goriketsu before Phase 3
  code work starts.
