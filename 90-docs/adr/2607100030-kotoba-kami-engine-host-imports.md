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
- ~~Follow-up (recorded in DEMONSTRATIONS.md): a browser port of
  `kotoba.kami-host`'s ECS in wasm-webcomponent (hand-JS, `kgraph.js` /
  `actor-host.js` family) so the same compiled `.wasm` ticks on the
  browser's native engine the way netsurvivors already does.~~
  **Done — addendum (same day):** wasm-webcomponent `fe04bd69` ships
  `src/kami-ecs.js` (hand-JS port; BigInt 64-bit xorshift64 bit-exact
  with the JVM's long math), `examples/kami-survivors/` (the exact
  `kotoba wasm emit` binary — 756 bytes, 12 imports, admission-verified —
  on canvas + requestAnimationFrame, arrow keys/WASD → host axes), and
  `test/verify-kami-survivors.mjs`, which replays the same 300-tick run
  on native WebAssembly (V8) and asserts the SAME pinned counts
  (12 → 8 → 10, player at origin, 15 spawned, x=59 axis check) kotoba's
  Chicory test pins — count-for-count cross-engine parity on the first
  run. Full `npm test` (9 verify-*.mjs) green. Real-browser DOM/rAF
  rendering remains outside the Node test's reach (the repo's documented
  gap; the Chrome extension was unavailable in this session).
  kotoba `31263fa8` updates DEMONSTRATIONS.md accordingly.
- West pins advanced for kotoba / kotoba-core-contracts / kotoba-lang in
  the same landing.

## Addendum 2 (2026-07-10) — runtime-priority realignment: the host premise is ClojureScript, not the JVM

Owner directive: premise the kami host on kotoba wasm runtime /
clojurewasm / ClojureScript — not the JVM (the 2026-07-07 repo-wide
runtime priority). Walking the ladder: the **guest** is already kotoba
wasm; a **host** cannot be expressed in the `.kotoba` subset (N/A by
design). **clojurewasm is blocked today** — cljw v1.0.1's FFI is
`wasm/load`+`wasm/call` for import-free modules, with Clojure-provided
host imports explicitly deferred to its "fuller Phase-16 FFI surface"
(upstream `docs/examples/wasm/README.md`, checked 2026-07-10), and this
guest needs 12 host imports; revisit when Phase-16 lands. **ClojureScript
adopted**:

- kotoba `367b4a14` — `kami_host.clj` → **`kami_host.cljc`**: fully
  portable ECS core; xorshift64 reimplemented as ONE 32-bit-pair
  implementation, bit-identical on JVM longs / cljs int32 ops / nbb SCI;
  `:cljs` wire layer (`kami-host-imports` for the native
  `js/WebAssembly` engine); `:clj` Chicory layer kept for the compat
  suite only. `scripts/verify_kami_survivors_nbb.cljs` (nbb, **no JVM**)
  drives the checked-in fixture for the same 300 ticks and pins the same
  counts — green. Also caught: `test_runner.clj`'s fixed namespace list
  had never included `kotoba.kami-game-test` (the kami suite silently
  never ran under `-M:test`); registered — now 198 tests / 975
  assertions, 0 failures.
- wasm-webcomponent `06316562` — the hand-JS `kami-ecs.js` is retired;
  it is now **compiled by shadow-cljs** from a thin
  `src-cljs/kotoba/kami_ecs.cljs` adapter over the **vendored**
  `kami_host.cljc` (file-for-file from kotoba@367b4a14, `src/vendor`
  ed25519 convention). One `.cljc` source now serves the JVM compat
  suite, the nbb script, and the browser/Node ESM. `npm test` green.
