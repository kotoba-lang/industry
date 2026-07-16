# ADR-2607078000: `wasm-webcomponent`'s "zero-build-step hand-JS" host policy is superseded — kotoba/kototama WASM hosts are authored in ClojureScript, compiled once, run everywhere (browser + Node)

## Status

Accepted (implemented, one module migrated)

## Context

Follow-up to ADR-2607072700 (`kami-solar-helix-scene`). Continuing that work
(making `kami-engine` Rust-free, then rendering via kotoba wasm runtime →
clojurewasm → ClojureScript, in that priority order), Track A required
porting `kotoba-lang/kami-script-runtime-rs` (the last real Rust dependency
— a wasmtime host binding 14 `kami:engine/*` host-imports for
`kotoba-lang/engine`-compiled game scripts) to a browser-only host (owner
decision, prior session).

The first pass wrote this port as hand-rolled JavaScript
(`wasm-webcomponent/src/kami-engine-host.js`), matching that repo's existing
convention: `actor-host.js`/`kgraph.js` are explicitly, by their own header
comments, "not a compiled build of the CLJC source" — the repo's stated
design goal is "zero-build-step, CDN-servable as raw ES modules" (extracted
from `kotoba-lang/kototama`'s PoC per ADR-2607061850).

Owner feedback: hand-written JS isn't one of the sanctioned authoring tiers
(`kotoba wasm runtime → clojurewasm → ClojureScript`) at all — matching
local repo convention doesn't excuse skipping the org-wide `.cljc`/portable-
authoring discipline. Redirected to try `clojurewasm` (cljw) first, per the
priority order.

**`clojurewasm` investigated and ruled out for this role, for two
independent, structural reasons** (not a maturity gap that will resolve
with more work — a fundamental mismatch):

1. **Native-only, not browser-executable.** Per its own README: "It builds
   to a small native binary (arm64/amd64) that starts in milliseconds."
   The project's browser-facing demos (playground, bookshelf) are thin web
   clients calling a cljw *server* process — cljw itself never runs inside
   a browser tab. This directly conflicts with the owner's "ブラウザのみ"
   (browser-only) decision for this host.
2. **FFI is unidirectional, the wrong direction.** cljw's WebAssembly FFI
   is Clojure-*calls*-wasm only: `(wasm/load "mod.wasm")` then
   `(wasm/call m "fn" ...)` — cljw is the caller, the wasm module is a
   callee library. Track A needs the opposite relationship: the compiled
   `game.wasm` is the caller, and our host must be the callee providing
   `kami:engine/*` host-import functions the guest calls *into*. No such
   host-providing-imports capability is documented anywhere in cljw's
   README or org pages.

Given that, and the owner's explicit push-back on "match local zero-
build-step convention" as an excuse: the right target is **ClojureScript**,
which the org already uses extensively for browser code (`dom-gpu`,
`kami-web`, `htmldom`/`cssom`, 50+ `shadow-cljs.edn` configs across
`kotoba-lang`). `wasm-webcomponent`'s own "zero-build-step" design goal is
itself what's being revised here — it was a reasonable choice when this
repo's only content was small hand-portable ABIs (`kgraph-*`, `has-
capability`, 7-of-8 `actor:host` imports), but it is not a principle worth
preserving at the cost of hand-writing every future host port as raw JS,
duplicated effort the rest of the org's `.cljc`/cljs convention exists
specifically to avoid.

## Decision

1. **New policy for `kotoba-lang/wasm-webcomponent` (and, by extension, any
   future kotoba/kototama WASM host needing more than a trivial hand-port):
   author in ClojureScript, compile once via shadow-cljs `:target :esm`, to
   a single ES module that runs identically in a browser `<script
   type="module">` and in Node's own `import`.** Node and evergreen
   browsers share the same `WebAssembly` JS API and (as of ~2020) the same
   BigInt-based i64 interop — one compiled artifact genuinely runs
   everywhere; there is no need for separate JVM/browser/native hand-ports
   of the same host logic.
   - This *does* introduce a build step (shadow-cljs) into a repo that
     previously had none — an explicit, accepted trade-off. The existing
     hand-JS modules (`actor-host.js`, `kgraph.js`, `has-capability.js`,
     `kotoba-wasm-element.js`) are **not** touched by this ADR (still
     correct, still working, migrating them isn't in scope here) — only
     new/replaced work follows the new policy.
2. **`kami-engine-host` (Track A's port) is the first implementation under
   this policy.** `src-cljs/kotoba/kami_engine_host.cljs` — a 1:1 semantic
   port of `kami-script-runtime-rs/src/lib.rs`'s `HostState`/`EcsStore`/
   `KamiHost` (entity spawn/despawn/position/velocity, tag queries,
   nearest/move-toward, xorshift64 random, fixed-step Euler integration,
   export-section `-tick`-suffix ordering) — compiles via `shadow-cljs.edn`
   (`:target :esm`, `:output-dir "src"`) to `src/kami-engine-host.js`,
   exporting `createKamiEngineHost`/`orderedTickExports`. Wires all 14
   `kami:engine/*` host-imports across the 4 WASM import modules
   (`kami:engine/scene@1.0.0`/`/input`/`/random`/`/time`) the compiler
   actually emits.
   - i64 WASM values cross the boundary as native JS `BigInt` (no manual
     32-bit splitting needed — both Node and browsers have shipped this
     since ~2020); ECS state is idiomatic immutable ClojureScript data in
     an atom (not a hand-rolled mutable `js/Map`), simpler and less
     interop-error-prone than mirroring Rust's `HashMap` mechanically.
   - Parity verified in Node (`test/verify-kami-engine-host.mjs`) by
     driving the exact same checked-in fixture
     (`examples/kami-engine-host/isekai-network-01-netsurvivors.wasm`,
     isekai-network's real compiled `games/01-netsurvivors/logic.clj`) for
     300 ticks: `entities=16 shiro-pico=1 ghost=14 beat-spark=1` — the
     **exact same counts** `kami-script-runtime-rs/README.md` documents
     from its own real `cargo run`/wasmtime execution. This is the parity
     proof for retiring that crate's runtime role.
   - Real-browser confirmation attempted via `claude-in-chrome` against a
     local `python3 -m http.server`; blocked by the same sandboxed-
     automation-environment network-namespace gap `wasm-webcomponent`'s own
     README already documents for this exact scenario ("a local
     `python3 -m http.server` ... unreachable from the browser-automation
     tool's actual Chrome process"). Node's `WebAssembly` engine is the
     same V8 a Chromium browser uses — this repo's own testing philosophy
     already treats that as the valid equivalent when a live browser isn't
     reachable; a from-scratch real-browser tab confirmation remains a
     known, pre-existing gap (not introduced by this change), documented as
     follow-up.

## Consequences

- (+) One compiled artifact runs in both target environments (browser and
  server/Node) instead of needing separate hand-ports — directly answers
  "kototama wasm で browser でも server でも どこでも動くように."
- (+) `kami-script-runtime-rs`'s Rust runtime role now has a verified,
  non-Rust, non-JVM replacement (Track A.4: mark that crate deprecated/
  frozen, not delete — same precedent this org uses elsewhere).
- (+) `clojurewasm` is now a **documented, verified dead end** for host-
  providing-imports roles specifically (native-only + wrong-direction FFI)
  — future sessions don't need to re-investigate it for this class of
  problem; it may still be worth investigating for actual FFI-consumer use
  cases (Clojure code that wants to call into a Rust/Go/Zig library), which
  is a different role entirely.
- (−) `wasm-webcomponent` now has a build dependency (shadow-cljs/Node) it
  didn't have before — a real cost against the "zero-dependency, CDN-
  servable as raw files" property ADR-2607061850 valued, accepted here as
  the right trade for portability + org-convention consistency going
  forward. Existing hand-JS modules are grandfathered, not retrofitted.
- (−) Real-browser (not just Node) confirmation of `kami-engine-host`
  remains outstanding, for environmental reasons pre-dating this ADR.

## Follow-up

- Get a real-browser confirmation of `examples/kami-engine-host/index.html`
  from an environment where the browser and a static file server actually
  share a network (a developer's own machine, not this sandboxed session).
- Consider whether `actor-host.js`/`kgraph.js`/`has-capability.js` should
  eventually migrate to the same ClojureScript-authored pattern for
  consistency — explicitly out of scope here (working, not broken, not
  touched).
- The `render`/`audio`/`physics` `kami:engine/*` bindings remain unported
  (matches `kami-script-runtime-rs`'s own documented scope — the original
  crate never wired them either).

## Addendum (2026-07-08): direction widened to "Rust-free including native"

Owner clarified the direction is not limited to the browser/Node path this
ADR's Decision covers — it extends to **native/windowed targets too**
("Rust はやめましょう"). Context that prompted this: while the above was
being implemented, a concurrent session independently pushed real,
verified new work to `kami-script-runtime-rs`'s `main` — a `wasmi` no-JIT
backend (bit-for-bit parity tested against `wasmtime`) and `kami_clj_play`,
a native `winit`+`wgpu` windowed player that runs isekai-network's real
compiled game through `KamiHost` at ~60fps with real screenshot-verified
character rendering. That work is **not Rust-free** (needs native code for
OS windowing, which browsers/Node structurally cannot do).

**Decision on that conflict**: do not touch or revert the concurrent
session's code (real, verified, in-progress work by another session — not
this ADR's to disrupt). Instead, add a **documentation-only** notice to
`kotoba-lang/kami-script-runtime-rs`'s `README.md`/`Cargo.toml` recording
the org's actual direction (native desktop targets should eventually go
through a Chromium-shell/webview wrapper around the same browser-proven
`dom-gpu`/`kami-engine-host` ClojureScript path — e.g. Electron/Tauri-style
— not hand-written `wgpu`/`winit`, since a webview shell is genuinely not
Rust even though it produces a native window) — landed at commit
`4f08f5b42ae7bd4ac0ff91ab0e9c0f80e1b0c068`. No `.rs` file was modified,
deleted, or disabled; `KamiHost`'s headless-host role (this ADR's actual
replacement target) was and remains unaffected by the concurrent session's
additions either way (verified via `git diff` before writing the notice —
only a `wasmi_host` module registration and one new error variant touched
`src/lib.rs`, the file `kami-engine-host.cljs` ports).

**Consequence**: the "Rust-free" scope for `kami-engine`/kotoba-lang going
forward now explicitly includes native desktop, not just browser — this is
a widening of this ADR's Decision, not a correction of an error in it. A
concrete native-desktop-without-Rust implementation (e.g. wrapping
`kami-engine-host`/`dom-gpu` in a webview shell) remains unbuilt — follow-up,
not resolved by this addendum.

## Addendum 2 (2026-07-08): Track A closed, proceeding to Track B

Track A (removing `kami-engine`'s Rust dependency) is now closed: the
headless WASM-host role is ported and parity-verified (Decision, above),
and the org-direction notice on `kami-script-runtime-rs` (Addendum 1)
resolves the concurrent-session conflict without disrupting that session's
work. No further Track A action is planned.

Proceeding to Track B (the original ask this ADR chain serves: rendering
`kami-solar-helix-scene`'s solar helical model via kotoba wasm runtime,
guest-driven WebGPU, per the plan at `curious-zooming-sifakis.md` — Phase
0's `.kotoba` + browser-host WebGPU spike is next).

## Addendum 3 (2026-07-08): Track B Phase 0 — the ABI half landed; the browser-verified half is still open

Phase 0's gate (per `curious-zooming-sifakis.md`) is a technical-risk
spike: prove a guest-driven WebGPU call can work as a genuinely
*synchronous* Wasm host-import, since the browser's `requestAdapter`/
`requestDevice` are async but every per-frame WebGPU call
(`beginRenderPass`/`draw`/`queue.submit`, ...) is synchronous per spec
— the same async/sync wall that ruled `http-post` out of the browser
`actor:host` surface (ADR-2607062400) does NOT apply here, *if* device/
adapter setup happens once, host-side, before the guest ever runs.

**Landed: the capability contract.** `gpu-clear` registered in
`kotoba-core-contracts` (id 226, `kotoba-core-contracts#5`) —
`gpu-clear(rgba8: i32) -> 0|-1`, one packed `0xRRGGBBAA` `i32` rather
than the plan's sketched `gpu-clear(r,g,b)` three-param shape (simpler
ABI, same `(scalar-in) -> status-out` convention every non-buffer
import here already uses; a deliberate refinement during
implementation, not a plan deviation worth blocking on). 9 tests/93
assertions green, `clj-kondo` clean.

**NOT yet landed, and this addendum does not claim otherwise:** the
actual browser host implementation (device/adapter/canvas setup +
the `gpu_clear` `HostFunction`, per this ADR's own Decision authored in
ClojureScript compiled to an ESM host — Track A's `kami-engine-host`
pattern is the template to follow, not `actor-host.js` hand-JS), a
minimal `.kotoba` guest calling it, and Phase 0's actual acceptance
criterion: a real `claude-in-chrome` screenshot confirming the canvas
genuinely clears to the requested color in a live browser tab. The
capability contract existing is a necessary precondition for that work,
not a substitute for it — Phase 0 is open until the synchronous-call
risk is verified against a real browser, not just declared in an EDN
table.

## Addendum 4 (2026-07-08): Track B Phase 0 — closed, browser-verified

Addendum 3's "NOT yet landed" half is now landed and verified, closing
Phase 0's actual acceptance criterion (a real browser confirming the
canvas clears, not just the capability contract existing).

- **Browser host**: `kotoba-lang/wasm-webcomponent`
  `src-cljs/kotoba/gpu_clear_host.cljs` → `src/gpu-clear-host.js`
  (`shadow-cljs :target :esm`, `:optimizations :advanced`) — one-time
  async `requestAdapter`/`requestDevice`/canvas-context setup, then a
  synchronous `gpu_clear` `HostFunction` (`createCommandEncoder` →
  `beginRenderPass` load-clear → `end` → `queue.submit`), exactly the
  `kami-engine-host` ClojureScript-authored pattern this ADR's Decision
  specifies (not hand-JS). `:advanced` optimization required switching
  every WebGPU property/method access to `unchecked-get`/`js-invoke`
  (Closure's property renaming silently breaks bare `.method`/`.-prop`
  interop against externs-less browser APis otherwise — caught via
  `:infer-warning`s during compilation, not at runtime).
- **Guest**: `demo_gpu_clear.kotoba` — `(gpu-clear -16776961)` (signed-i32
  bit pattern of packed RGBA8 `0xFF0000FF`, opaque red). Compiled via
  `kotoba-lang/kotoba`'s real `kotoba wasm emit` after bumping its
  `kotoba-core-contracts` pin to the commit carrying `gpu-clear`
  (`kotoba-lang/kotoba` commit `3ba5f607e08f626db679d987a5416100e49e0774`,
  146 tests / 786 assertions green against the bumped pin).
- **Real browser verification**: `claude-in-chrome` against
  `https://kotoba-lang.github.io/wasm-webcomponent/examples/gpu-clear/`
  (GitHub Pages enabled on this repo specifically to get a real,
  non-sandboxed browser tab — the sandboxed session environment couldn't
  reach a local `python3 -m http.server`, the same documented gap this
  repo's README already records, and separately a claude.ai Artifact
  preview's own iframe sandboxing blocked scroll/DOM inspection). Screenshot
  confirms: canvas fully opaque red, `#out` reads `main() -> 0 (0 =
  gpu_clear succeeded)`. Phase 0's core risk — a guest-driven WebGPU call
  works as a genuinely synchronous host-import — is confirmed, not just
  argued from the spec.

**Consequence**: Track B Phase 1 (per `curious-zooming-sifakis.md`) is
unblocked — `cos`/`sin` in `.kotoba`'s builtin-fns, the
`gpu-set-instance-transform`/`gpu-set-camera`/`gpu-draw-frame` capability
set, porting `kami-solar-helix-scene`'s position math to `.kotoba`, and
the 9-body vertical-slice render.

## References

- ADR-2607072700 (`kami-solar-helix-scene`, this work's immediate
  predecessor)
- ADR-2607061630 / ADR-2607061850 (`wasm-webcomponent`'s original browser
  WASM AOT + WebComponent PoC and library extraction — this ADR revises,
  not reverts, that lineage)
- `kotoba-lang/kami-script-runtime-rs` (the Rust crate this replaces;
  addendum commit `4f08f5b42ae7bd4ac0ff91ab0e9c0f80e1b0c068`)
- `kotoba-lang/wasm-webcomponent` (`src-cljs/kotoba/kami_engine_host.cljs`,
  `src-cljs/kotoba/gpu_clear_host.cljs`, `shadow-cljs.edn`,
  `examples/gpu-clear/`, `test/verify-kami-engine-host.mjs`)
- `kotoba-lang/kotoba-core-contracts` (`gpu-clear` capability, commit
  `e5bc820b77288964f4aa3fc6f13965955b6a6437`)
- `kotoba-lang/kotoba` (pin bump, commit
  `3ba5f607e08f626db679d987a5416100e49e0774`)

## Addendum 5 (2026-07-08): proceeding to Track B Phase 1

Phase 0 closed (Addendum 4). Starting Phase 1 per
`curious-zooming-sifakis.md`: `.kotoba` `cos`/`sin` builtins, the
`gpu-set-instance-transform`/`gpu-set-camera`/`gpu-draw-frame` capability
set (closed, synchronous, same `(scalar-in) -> status-out`/`(ptr) ->
status-out` conventions `gpu-clear` established), porting
`kami-solar-helix-scene`'s `heliocentric-position-au`/
`galactic-frame-position-au` arithmetic to `.kotoba`, and the 9-body
(Sun + 8 planets) vertical-slice render — spheres, heliocentric/galactic
frame toggle, verified against a real browser per Phase 0's precedent.

## Addendum 6 (2026-07-08): Track B Phase 1 — CLOSED, 9/9 bodies rendered and browser-verified

The vertical slice landed: a compiled `.kotoba` guest computes all 9
bodies' positions via real orbital math and a host-side WebGPU pipeline
renders them as spheres, confirmed in a live browser (all 9 visible,
correctly colored/sized/ordered).

**What actually shipped, vs. the plan sketched in Addendum 5**:

- **`.kotoba` gained real `f32` support** (`kotoba-lang/kotoba` commit
  `05088a18`) — `wasm-valtypes`/`f32`-literal/`f32+`/`f32-`/`f32*`/`f32div`/
  `f32sqrt`/`f32neg`/f32-comparisons/`^:f32` fn-metadata. Not in the
  original Phase 1 sketch — discovered mid-implementation that
  `kotoba-lang/kotoba`'s host-import ABI was i32/i64-only (zero `f32`
  anywhere), which would have forced a fixed-point-i64 workaround for
  orbital math; owner chose "add real f32 to the compiler" over the
  workaround. Also fixed a latent `wasm-exec/call-main`/`run-main` bug this
  surfaced: Chicory's raw `long[]` return slot was always read as i64,
  silently wrong for f32 (`4.0` came back as `1082130432`, its raw bit
  pattern) — both fns now take an optional result-type. 152 tests/801
  assertions green.
- **Capabilities**: `math/cos`/`math/sin` (`cos`/`sin(radians: f32) ->
  f32`) and `gpu/set-position`/`gpu/draw-frame` — **not**
  `gpu-set-instance-transform`/`gpu-set-camera` as Addendum 5 sketched.
  `gpu-set-position(body-id, x, y, z)` is deliberately scalar-only, no
  guest-constructed matrix and no guest-controlled camera: `.kotoba` has no
  vector/matrix type or loops-beyond-recursion yet, so the guest computes
  *where* each body goes (real `cos`/`sin` orbital math) and the host owns
  *how* it's drawn (mesh/camera/pipeline/matrices) — same "guest computes,
  host renders" split ADR-2607078000's Decision already established for
  `kami-engine-host`, just made explicit here. `kotoba-core-contracts`
  commit `3e60ae6`, `kotoba-lang/kotoba` pin bumped to it (commit
  `b7f8427c`, 152 tests still green).
- **Guest**: `wasm-webcomponent/examples/solar-helix/demo_solar_helix.kotoba`
  — a **static single-frame render** (t=45 days fixed), not the animated
  heliocentric/galactic-frame-toggle Addendum 5 sketched. Sqrt-scaled
  orbital radii (linear AU scale would crush Mercury invisibly close to
  the Sun relative to Neptune) computed via real `cos`/`sin`/`f32sqrt` from
  the same semi-major-axis/period data as `kami-solar-helix-scene`'s
  `resources/solar-helix.edn`. The galactic-frame view and animation are
  explicitly deferred, not silently dropped — see Follow-up.
- **Host**: `src-cljs/kotoba/solar_render_host.cljs` — procedural UV
  sphere mesh, hand-written mat4 perspective/lookAt/multiply/
  translate-scale (no external dep), one draw call per body (not true
  GPU instancing — 9 is small enough it isn't worth the complexity), fixed
  elevated camera, WGSL Lambertian shader. `cullMode: "none"` deliberately
  (sphere winding order untested — avoided an entire bug class rather than
  risk it for this first slice).
- **Two real bugs found only via live-browser verification** (both would
  have passed any structural/compile-time check):
  1. The host initially wired only `gpu_set_position`/`gpu_draw_frame`,
     forgetting `cos`/`sin` — `WebAssembly.instantiate` failed with a
     `LinkError` ("function import requires a callable") before `main()`
     ever ran.
  2. After fixing that: `main()` succeeded but the canvas stayed empty.
     Root cause: `queue.writeBuffer` executes immediately, but
     `pass.drawIndexed` calls only *record* into the command encoder and
     don't run until `queue.submit()` at the very end — a single shared
     uniform buffer written 9x in a row before any of the 9 recorded draws
     executes means every draw referenced only the *last* write (Neptune).
     Fixed with one dedicated uniform buffer + bind group per body slot.
  Also needed a camera pull-back/FOV-widen after an initial verified
  render showed only 7/9 bodies (Uranus/Neptune clipped by the frustum).
  Each fix was verified via a fresh screenshot before moving on, not
  assumed from the code change alone.
- **Verification**: GitHub Pages (same technique Phase 0 established —
  this sandboxed session can't reach a local static server, and a
  claude.ai Artifact preview's iframe sandboxing blocks scroll/DOM
  inspection), with explicit cache-bust query params and forced Pages
  rebuilds (`POST .../pages/builds`) once CDN edge-caching was found to
  serve stale JS after a push despite a "built" status. Final screenshot:
  all 9 bodies visible, correctly colored (Sun gold, Mercury grey, Venus
  pale, Earth blue, Mars red, Jupiter/Saturn tan, Uranus cyan, Neptune deep
  blue) and correctly ordered inner-to-outer.

## Follow-up (not done, explicitly deferred, not dropped silently)

- Galactic-frame view / helical toggle (`kami-solar-helix-scene`'s actual
  headline feature) — this slice only ports the flat heliocentric position
  math, not `galactic-frame-position-au`.
- Animation (continuous tick loop via `requestAnimationFrame` calling the
  guest repeatedly) — this slice is one static frame at a fixed t.
  `kami-engine-host`'s existing `tick`-loop pattern is the template.
  `main()` would need to become a per-frame `tick(dt)` export instead of a
  single one-shot `main()`.
  cullMode "back" once sphere winding order is verified (currently "none",
  a deliberate safety margin, not a proven-correct value).
- No guest-controlled camera or per-body color/radius — both are still a
  fixed host-side palette/camera.

## Addendum 7 (2026-07-08): maturity/coverage pass — direct unit tests for solar-render-host/gpu-clear-host's pure logic, docs backfilled

Track B Phase 0/1 (Addenda 4/6) shipped both browser hosts and verified
them end-to-end via GitHub Pages screenshots, but the two `.cljs` modules
had **zero direct unit-test coverage** of their own — every mat4/vec3/
sphere-mesh/bit-unpacking helper was `defn-` (private) and only ever
exercised transitively through a live WebGPU render, which is real
verification but not a regression-catching unit test a CI run (no GPU)
can execute. `wasm-webcomponent/README.md` also never gained `## Files`/
`## Run the tests` entries for either module or their `examples/gpu-clear/`
`examples/solar-helix/` dirs, despite both having shipped in Addenda 4/6.

**What changed** (`kotoba-lang/wasm-webcomponent` commit `1a74d4e`, merged
to main as `ac0db24`):
- `gpu_clear_host.cljs`'s `unpack-rgba8` and `solar_render_host.cljs`'s
  `mat4-multiply`/`mat4-perspective`/`mat4-look-at`/`mat4-translation-scale`/
  `vec3-normalize`/`vec3-sub`/`vec3-cross`/`vec3-dot`/`build-sphere-mesh`
  promoted from `defn-` to `defn` (public) specifically so they're
  reachable from a Node test without a GPU.
- `mat4-identity` deleted — a grep confirmed it was defined but never
  called anywhere in the file (dead code, not wired to anything after the
  `mat4-look-at`/`mat4-translation-scale` refactor that replaced it).
- `build-sphere-mesh`'s return value changed from a Clojure map
  (`{:vertices ... :indices ...}`) to a plain JS object
  (`#js {"vertices" ... "indices" ...}`) — under `:advanced` Closure
  optimization a Clojure map's keyword-dispatch keys aren't inspectable as
  plain JS properties from outside the compiled module, so a Node test
  couldn't read `mesh.vertices`/`mesh.indices` without this change. The two
  internal call sites in `setup-solar-render-host` were updated to
  `unchecked-get` accordingly (consistent with the rest of the file's
  WebGPU interop convention).
- `shadow-cljs.edn`'s `:gpu-clear-host`/`:solar-render-host` build configs
  gained `:exports` entries for all of the above so they're reachable as
  named ESM exports (`unpackRgba8`, `mat4Multiply`, `mat4Perspective`,
  `mat4LookAt`, `mat4TranslationScale`, `vec3Normalize`, `vec3Sub`,
  `vec3Cross`, `vec3Dot`, `buildSphereMesh`) — both rebuilt via `npx
  shadow-cljs release gpu-clear-host solar-render-host`, 0 warnings.
- `test/verify-gpu-clear-host.mjs` (7 assertions: bit-unpacking across the
  full byte range, including sign-bit-set i32 patterns a naive shift
  without `>>> 0` would get wrong) and `test/verify-solar-render-host.mjs`
  (17 assertions: mat4 identity laws, vec3 algebra against hand-worked
  values, `mat4Perspective`/`mat4LookAt` checked against their closed-form
  formulas independently re-derived in the test rather than just re-running
  the same code, and `build-sphere-mesh` vertex-count/index-bounds/
  unit-sphere-radius checks) — both added to `package.json`'s new `npm
  test` runner (`for f in test/verify-*.mjs; do node "$f" || exit 1; done`)
  alongside the 6 pre-existing `verify-*.mjs` files, all 8 confirmed green
  together.
- `package.json` gained `compile:`/`release:` scripts for `gpu-clear-host`
  and `solar-render-host` (previously only `kami-engine-host` had them,
  despite all three builds existing in `shadow-cljs.edn` since Addenda 4/6).
- `README.md`'s `## Files` section gained entries for
  `gpu_clear_host.cljs`/`solar_render_host.cljs` and both `examples/` dirs;
  `## Run the tests` documents `npm test` plus the two new `verify-*.mjs`
  invocations.

**What this does and doesn't close**: the pure math/mesh-generation logic
now has fast, GPU-free regression coverage a CI runner without WebGPU
support can execute. The actual WebGPU draw path (pipeline creation, bind
groups, `queue.submit` timing — where both real bugs Addendum 6 documents
were actually found) still has no automated coverage and still requires a
real browser to verify, same as before this pass; that gap is unchanged
and is not what this addendum claims to fix.

Landed via isolated worktree (`/tmp/root-worktrees/wasm-webcomponent-
maturity`, outside the superproject root per this repo's worktree-topdir
guidance) + `gh api .../merges` server-side merge, not a direct commit to
the shared `orgs/kotoba-lang/wasm-webcomponent` checkout — that checkout
was edited directly at first (in a detached-HEAD state matching
`origin/main`), then `git stash push -u` moved the in-progress work out
before committing anywhere, reconciled into the worktree via the stash's
diff plus its untracked-files parent commit, and the stash was dropped
only after the worktree's own copy was confirmed complete and pushed —
this is the shared-checkout risk this repo's CLAUDE.md documents
(concurrent sessions branch-switching a shared west checkout can silently
discard another session's uncommitted edits), sidestepped here rather
than triggered by one.

## Addendum 8 (2026-07-08): Track B Phase 1 follow-up CLOSED — animation + heliocentric/galactic view toggle, both real-GPU-verified

Follow-up's first two deferred items (Addendum 6's Follow-up section) are
now shipped and verified: `demo_solar_helix.kotoba` animates continuously
and toggles between the flat heliocentric view and
`kami-solar-helix-scene`'s real galactic-frame (tilt + Sun's own forward
drift) transform.

**Two new capabilities** (`kotoba-core-contracts` commit `2d6983a`,
ids 231/232): `time/now-days() -> f32` (a host-owned, wrapped simulated
day count — the host recomputes it from wall-clock time and just calls
the guest's 0-arity `main` again every `requestAnimationFrame` tick, no
guest-side clock/loop or new wasm export needed) and
`render/galactic-frame() -> i32` (a host-owned view-toggle boolean, wired
to a page checkbox; an i32 works directly as a wasm `if` condition).
`kotoba-lang/kotoba` pin bumped to it (commit `610f80f`, 184 tests still
green — neither capability needed a dedicated `.kotoba` fixture in that
repo, matching the existing pattern for cos/sin/gpu-set-position).

**A real, previously-latent compiler bug found and fixed en route**
(`kotoba-lang/kotoba` commit `edb768f`): `compile-wasm-expr`'s `if`
hardcoded the WASM `if` block's own result-type byte to `0x7f` (i32)
regardless of what the branches actually compiled to. Every prior
`if`-using fixture in that repo only ever branched on/to i32 values, so
this went undetected since i64/f32 support was added — but the new guest
code's `(if gal (f32* ...) (f32 0.0))` (branching to decide the
tilt/forward transform) is an f32-typed `if`, and hit it immediately:
Chicory's validator rejected the emitted bytecode with a stack
type-mismatch (declares an i32 block, pushes an f32 value). Fixed by
reading the block-type byte from the then-branch's actual
`compiled-result-type` via `wasm-valtypes` instead of hardcoding it;
confirmed the same latent bug affected i64-typed `if` too (same fix,
same test coverage added: `demo_f32_if.kotoba`/`demo_i64_if.kotoba`,
186 tests green). A second, related bug surfaced reproducing the first
one: `kotoba.wasm-exec/stub-host-function`'s `valtype` map had no `:f32`
entry (only `:i32`/`:i64`), so `kotoba wasm run` on any program calling
an f32-result host-import without an explicit override
NullPointerException'd deep inside Chicory's `FunctionType/of` — fixed
alongside the `if` bug in the same commit.

**Guest** (`demo_solar_helix.kotoba`): `main` reads `(now-days)` and
`(galactic-frame?)` once per call via `let`, computes a shared
`(theta t period)`/`sin`/`cos` per body, and branches per-body y/z on the
galactic-frame flag — ports `galactic-frame-position-au`'s tilt
(`cos-tilt`/`sin-tilt`, baked-in 0-arg fns for cos(60°)/sin(60°) since the
tilt angle never varies) and forward-drift (`forward-per-day`, an
illustrative — not literal-AU — constant calibrated so Earth's own
pitch/circumference ratio matches `kami-solar-helix-scene`'s real ~7.4x;
other bodies' ratios drift from their exact real values since
sqrt-compressing radius but not forward can't preserve every body's ratio
simultaneously with one shared linear rate, but the qualitative
"farther/slower stretches out more" trend still holds).
`forward-per-day` is negative, not positive — the fixed camera sits at
z=+2.7 looking toward the origin, so positive z moves bodies *toward* the
camera (clipping past the near plane); this was a real bug found via
live-browser verification (see below) and fixed before landing.

**Host** (`solar_render_host.cljs`): `now_days`/`galactic_frame`
host-imports backed by a `performance.now()`-derived, JS-`%`-wrapped
clock (`days-per-second`=8.0, `wrap-days`=80.0 — a 10-second loop) and a
`galactic-frame?` atom; `setGalacticFrame`/`setFixedNowDays` exposed on
`setup-solar-render-host`'s return value (the latter a test-only
determinism hook, see below). The page (`index.html`) owns instantiating
the guest and the `requestAnimationFrame` loop that calls
`instance.exports.main()` again every tick — `.kotoba` has no clock or
loop beyond recursion, so this is the only place animation can live.

**Verification — three independent lines of evidence, after a real
investigation, not a single screenshot**:
1. **GPU-free numerical soundness**: `test/verify-solar-helix-guest.mjs`
   (Node's native `WebAssembly`) sweeps the guest's own computed
   positions across the *entire* `now-days` wrap range in both view
   modes — finite, bounded, correctly signed. Independently re-verified
   via `com.dylibso.chicory` from `kotoba-lang/kotoba`'s own JVM tooling
   (a genuinely different WASM engine) with identical results.
2. **Interactive real-browser screenshots** (GitHub Pages +
   claude-in-chrome, same technique Phase 0/1 established): confirmed
   correct rendering at page load and immediately after toggling
   galactic frame. But *sustained* multi-second live animation
   intermittently produced a solid-black canvas in both view modes, with
   `main()` still returning success and the frame counter still
   advancing. A real investigation (not a shrug) ruled out the guest math
   (line 1 above), ruled out the host/pipeline code in isolation (a
   fresh, DOM-attached canvas driven through the identical compiled host,
   in the same tab, after the page's own canvas had already gone black,
   rendered correctly for hundreds of frames), and reproduced from a
   clean single-tab session with no prior WebGPU activity (ruling out
   pure test-pollution as the *sole* explanation). Working conclusion:
   browser/GPU-process-level flakiness specific to the sandboxed
   browser-*automation-tool* used for interactive verification, not the
   shipped code — consistent with this repo's own already-documented
   history of similar sandboxed-environment tooling dead-ends.
3. **Headless real-GPU CI** (`test/render/verify-render-solar-helix.mjs`,
   built on a concurrent session's new `test/render/` Playwright
   harness — real macOS Metal GPU, not Linux/SwiftShader, per that
   harness's own findings): settles the question a still frame *can*
   answer. Added `setFixedNowDays` + `?test_fixed_t=<days>
   &test_galactic=<0|1>` query-param wiring so the render stays fully
   deterministic (the original test's fixed-t=45 determinism came from a
   hardcoded guest constant that no longer exists now that `main` is
   animated) — one scenario recovers the pre-existing heliocentric
   landmarks/colors exactly, a new second scenario (landmarks computed by
   projecting the guest's own Chicory-verified t=45 galactic positions
   through `solar_render_host.cljs`'s exported camera-math functions)
   verifies the galactic-frame view for the first time via CI-style real
   pixels. Both pass cleanly.

None of this directly re-runs the sustained-multi-second-animation
scenario line 2 found flaky (CI has no "watch it animate for 30 seconds
and take periodic screenshots" primitive) — that specific gap is
documented honestly in `README.md`'s "sustained-animation verification
gap" section, not swept under the rug. But three independent angles now
point at the code being correct and zero point at it specifically; if a
real, un-sandboxed browser left animating for an extended period ever
reproduces the blank-canvas symptom, the next place to look is WebGPU
device/resource lifecycle over a long `requestAnimationFrame` run, not
the position math.

**Consequence**: 2 of 4 Addendum 6 Follow-up items are closed (animation;
heliocentric/galactic toggle). Remaining: `cullMode` "back" (still
"none", untested triangle winding order) and guest-controlled
camera/color (still a fixed host-side palette/camera) — both explicitly
still deferred, not attempted this pass.

Landed across three repos, each via isolated worktree + `gh api .../merges`
server-side merge: `kotoba-core-contracts` (capability registration,
commit `2d6983a`), `kotoba-lang/kotoba` (pin bump `610f80f`; `if`
blocktype + stub valtype fix `edb768f`), `wasm-webcomponent` (guest/host/
test changes, merged as `39692cd` — this merge itself landed on top of a
concurrent session's `test/render/` infrastructure that arrived mid-session,
requiring a real (non-trivial) merge-conflict resolution in `README.md`
and calibrating the concurrent session's solar-helix render test, which
had been written against the old fixed-t=45 guest, to the new
animated/toggle-based one).
