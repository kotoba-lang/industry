# ADR-2607121900: implement the `raycast` host binding (Phase A1 of ADR-2607121800)

- **Status**: accepted (2026-07-12)
- **Related**: ADR-2607121800 (network-isekai battle-royale-with-building game-systems
  roadmap — this ADR is that roadmap's Phase A1), ADR-2607100030 (kotoba wasm host
  imports), ADR-2607102200 addenda 7-9 (kami-engine browser-host/physics SSoT extraction
  that produced the current `kotoba-lang/host` + `kotoba-lang/physics` split).

## Context

`kotoba.engine-clj.ast` (the `.cljc` compiler from a game's `logic.cljc` source to WASM)
has declared `raycast` in its host-import ABI since it was first wired
(`kami:engine/physics@1.0.0`, field `"raycast"`, six `f32` args in / `i64` out) — but no
host implementation existed anywhere. `kami.host` (`kotoba-lang/host`'s
`src/kami/host.cljc`, the browser ECS host every compiled game runs against) only
implemented `:apply-impulse` on its `kami:engine/physics@1.0.0` import object; a guest
calling `raycast` would fail at `WebAssembly.instantiate` with a LinkError (missing
import). This was the concrete blocker for any aimed-hitscan gameplay (weapons, aimed
build-placement) — investigated as Phase A1 of ADR-2607121800's battle-royale-with-
building roadmap.

Separately, while wiring this, `kami.host.cljc`'s own `(:require [kami.physics :as
phys])` turned out to be a **pre-existing bug**: `kotoba-lang/physics` only provides
`kotoba.physics` (verified: `src/kotoba/physics.cljc`, no `kami/physics.cljc` anywhere in
that repo). This made the whole `kami.host` namespace fail to load
(`FileNotFoundException`) — including this repo's own JVM test suite, which was
therefore not actually green before this change (untestable, not merely untested).

## Decision

1. **Implement `raycast` as a ray-vs-sphere nearest-hit scan over the ECS**, the same
   `O(n)` full-entity-scan idiom the existing `:nearest` (radius-from-a-point) import
   already uses, just projected along a ray instead. The ECS (`kotoba.host`'s `:ents`
   map) tracks only position/velocity/rotation, never a render size, so every entity is
   approximated as a fixed-radius sphere (`raycast-hit-radius`, 120 ECS-space units) up
   to a fixed maximum range (`raycast-max-range`, 6000 units) — both named constants in
   `kami/host.cljc`, tunable in one place.
2. **Untargeted by design.** The ABI has no tag-filter parameter (unlike `:nearest`), so
   `raycast` can return *any* entity — including a "structure"/scenery entity, which is
   the *correct* behavior for a hitscan weapon (a wall should block a shot aimed through
   it). A caller that wants "did this hit specifically a bot" cross-checks the returned
   id against `nearest-tagged "bot" ...`; a caller wanting to exclude itself (e.g. a
   player's own weapon) compares the returned id against its own — the ABI has no
   spare argument slot for either, and both are cheaply expressible by the guest already.
3. **`apply-force!`** (also declared in the ABI, also previously a no-op) is left a
   no-op. No capability introduced by ADR-2607121800's Phase A needs it; implementing it
   without a concrete caller would be guesswork.
4. **Fix the `kami.physics` → `kotoba.physics` require**, since it blocked verifying this
   change (and every future change to this file) against a working test suite.

## Consequences

- Any game's `logic.cljc` can now author a real aimed-hitscan weapon or aimed build-
  placement using only guest-callable primitives already exposed (`raycast`,
  `nearest-tagged`, `spawn-entity`/`despawn-entity`, `defatom` health) — no further host
  capability is required for hitscan combat specifically (travelling-projectile combat
  was already fully expressible before this change, via `spawn-entity` + `set-velocity!`
  + `nearest-tagged`).
- `kotoba-lang/host`'s own test suite (`clojure -M:test`) now actually runs (previously
  failed to load the namespace at all); its lint (`clojure -M:lint`) is unaffected (0
  errors before and after; the pre-existing "unused require" warnings are a `:clj`-only
  static-analysis artifact of the `#?(:cljs ...)` reader-conditional split, not a real
  issue).
- `raycast-hit-radius`/`raycast-max-range` are fixed constants, not per-call parameters
  (the ABI has no room for either) — a future game needing a different hit radius (a
  much larger creature, a sniper-rifle's tighter tolerance) would need either a new ABI
  revision or a design that tolerates the shared default. Not a blocker for the current
  roadmap; flagged as a known limitation.

## Verification

`clojure -M:test` in `kotoba-lang/host`: 3 tests / 5 assertions, 0 failures (previously
did not even load). `raycast`'s actual hit-detection math is `#?(:cljs ...)`-gated (browser-
only, like the rest of `import-object`) and is verified end-to-end in a real browser build
as part of ADR-2607121800's Phase B (a hitscan weapon in the new gftd game actually
damages/kills a bot).

## References

- `orgs/kotoba-lang/host/src/kami/host.cljc`
- `orgs/kotoba-lang/engine-clj/src/kotoba/engine_clj/ast.cljc` (the ABI declaration this
  binding satisfies)
- `orgs/kotoba-lang/physics/src/kotoba/physics.cljc`
