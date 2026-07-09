# ADR-2607100030: kami:engine vocabulary as kotoba host imports — the first game authored in a `.kotoba` file

- **Status**: accepted (implemented & landed 2026-07-10)
- **Related**: ADR-2607078000 (Track B gpu/math f32 host imports — the
  precedent this follows), ADR-2607062330/2607062400 (actor:host ABI),
  ADR-2607022700 (no semantic-authority duplication), ADR-2607082400
  (mesh_drama_profile — first `.kotoba` app), docs/DEMONSTRATIONS.md
  (kotoba-lang/kotoba).

## Context

`kotoba-lang/kotoba`'s `docs/DEMONSTRATIONS.md` (landed earlier the same
day) cataloged every real `.kotoba` artifact and ended on an honest gap:
playable game logic ran only in the **kotoba-clj subset** (isekai-network's
01-netsurvivors, kami-genre-base-systems' 8 genre bases) against the
`kami:engine/*` 4-module import shape of `kami-script-runtime-rs` /
wasm-webcomponent's `kami-engine-host.js` — no game had ever been authored
in a `.kotoba` file, because kotoba's own closed host-import table
(kotoba-core-contracts) had no game-engine vocabulary.

## Decision

Expose a minimal kami:engine ECS vocabulary through kotoba's own single
`(module "kotoba")` capability-guarded ABI — NOT by adopting the kami-clj
4-module import shape — following the exact registration path the
gpu/math Track B imports proved (guest computes, host executes):

1. **kotoba-core-contracts** (`9a2f45e3`): capability `"kami/engine"`
   (id 233, one shared capability for the family, like `"graph/kotoba"`
   covering the kgraph-* quartet) + 13 `kami-*` import descriptors:
   tick-n, spawn, despawn, set-position!, set-velocity!, get-x, get-y,
   count-tagged, nearest-tagged, move-tagged-toward!, despawn-within!,
   axis, rand. Batch ops stand in for per-entity iteration the language
   deliberately doesn't have; `kami-rand` is the host's SEEDED xorshift64
   stream (deterministic replay), never the OS RNG (`random-bytes`' job).
2. **kotoba-lang** (`bed42bc6`): `:host/kami-engine` in
   `effect-for-kind`, registered simultaneously so guard-call can't
   reproduce the aiueos/actor:host `:unsupported-kind` runtime-denial gap.
3. **kotoba** (`a2c69208`): `op->kind` entries; a generic
   `guarded-host-functions` factored out of `real-host-functions`;
   **`kotoba.kami-host`** — a deterministic host-owned ECS (entity table,
   fixed-step Euler integration at 1/60s, tick counter, input axes,
   seeded xorshift64) driven "host steps, then calls the guest's 0-arg
   `main`, once per tick" (the now-days loop convention — no guest-side
   loop); **`src/kami_survivors.kotoba`** (+ policy granting only
   `:kami/engine`) — a survivors-style core loop (ring spawns every 20
   ticks to a 12 cap, 30 u/s chase, a 90-tick nova burst, axis-steered
   player); and `test/kotoba/kami_game_test.clj` driving 300 real Chicory
   ticks with exact pinned entity counts (12 → 8 → 10), axis steering,
   static admission, and a fail-closed runtime denial without the grant.

## Consequences

- The first game authored directly in a `.kotoba` file exists, compiles
  through the real emitter, and plays deterministically on Chicory —
  DEMONSTRATIONS.md's Games section now has an "authored in `.kotoba`"
  entry instead of a future-work sentence.
- Full kotoba suite green (192 tests / 945 assertions), clj-kondo clean;
  kotoba-core-contracts and kotoba-lang suites green with the additions.
- Follow-up (recorded in DEMONSTRATIONS.md): a browser port of
  `kotoba.kami-host`'s ECS in wasm-webcomponent (hand-JS, `kgraph.js` /
  `actor-host.js` family) so the same compiled `.wasm` ticks on the
  browser's native engine the way netsurvivors already does.
- West pins advanced for kotoba / kotoba-core-contracts / kotoba-lang in
  the same landing.
