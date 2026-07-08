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
