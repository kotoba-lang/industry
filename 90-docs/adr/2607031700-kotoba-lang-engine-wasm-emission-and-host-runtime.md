# ADR 2607031700: kotoba-lang/engine WASM byte-emission + kotoba-lang/kami-script-runtime-rs (the recovered WASM host substrate)

## Status
Accepted

## Context

`gftdcojp/isekai-network` (Ghost Hacker: Shiro & Pico) had a real,
EDN-validated, proven-vocabulary-checked `games/01-netsurvivors/logic.clj`
since early this session, but no way to verify it actually ran.
`kami-engine-clj` (the compiler `kami-engine`'s CLAUDE.md documented) turned
out to have been deleted upstream (`kotoba-lang/kotoba` PR #259) as part of
ADR-2607010930's clj-wgsl migration. Its CLJC replacement — found under the
differently-named `kotoba-lang/engine`, not something literally called
`kamiclj` — compiled `logic.clj` to a Module IR successfully, but the IR was
a data structure, not loadable `.wasm` bytes. Even with bytes, nothing
existed to actually load and execute them with real host-import behaviour:
`kotoba-lang/kami-script-runtime` (the CLJC-scoped repo) explicitly,
permanently excludes the Rust WASM-host substrate from its own scope.

## Decision

### Byte emission: `kotoba.engine-clj.wasm-bytes` (kotoba-lang/engine, PR #1)

A 1:1 port of the deleted Rust `codegen.rs::compile`'s section assembly
(type/import/function/memory/global/export/code/data, same order, same
`cabi_realloc` bump-allocator special case), recovered read-only from
`kami-engine`'s git history at the same pin `codegen.cljc` itself was
recovered from. Verified with `wasm-tools validate` (independent external
validator) and `wasmtime run --invoke` (real execution, not just structural
validity), 51 tests / 213 assertions.

**A real bug was found and fixed (PR #2)**: `:f32-const` was missing the
`num/f32-bits` conversion, discovered only by `kami-script-runtime-rs`'s
real tick-loop execution — ghosts spawned via a `def`-bound constant moved
correctly; ghosts using an inline `f32` literal in the same `cond` sat at a
NaN position forever. `wasm-tools validate` never caught it because any 4
bytes are structurally valid IEEE-754. Fixed with 2 new regression tests
that decode the emitted bytes back via an independent `ByteBuffer` read.

### Host runtime: new repo `kotoba-lang/kami-script-runtime-rs`

The deleted Rust `kami-script-runtime/src/lib.rs` (2042 lines) was ALWAYS
classified `:stay-Rust-substrate` in ADR-2607010930's original ledger, but
was accidentally swept into the wholesale `kami-engine` Rust-workspace
deletion. `kotoba-lang/kami-script-runtime`'s CLJC port only restored the
genuinely-portable `input_map.rs` submodule and states its host-runtime
exclusion in absolute terms ("permanent scope exclusion, not a TODO").
`kotoba-lang/kami-script-runtime-rs` is the missing repo that closes this
gap: wasmtime (JIT) + wasmi (no-JIT) dual backends, one binding codebase, 14
host-import implementations recovered 1:1 from the deleted `lib.rs`, a
hand-rolled `HashMap`-based entity store (not `hecs` — disclosed
simplification). Render/audio/physics host-imports are NOT implemented
because isekai-network's actual compiled module declares none — confirmed
empirically, not assumed, matching the original pre-deletion Rust's own
no-op status for those bindings.

**Verified, not just "it compiled"**: a wasmi/wasmtime parity test runs
isekai-network's real compiled module through both backends for 200 ticks
and asserts bit-for-bit identical position/velocity/tag/count state — the
golden-frame determinism contract ADR-2607010930's original design called
for. `src/bin/kami_clj_play.rs` reuses the deleted original's wgpu
rendering code, replaced its in-process `hecs`+CLJ-compile `Game` struct
with a thin `KamiHost` wrapper loading a pre-compiled `game.wasm`, plus a
hand-rolled `scene.edn` parser scoped to `author.clj`'s known flat output
shape. Confirmed running: a real window, sustained ~60fps for 720 frames,
isekai-network's `game.wasm` executing live (entity count 5→21 via spawn,
beat-spark bonus firing), and pixel-level colour verification via a real
render-to-texture screenshot capture — Shiro's hood-white near-exact match,
teal/lime eye accents consistent with the shader's glow-brightening + sRGB
encode once that two-step colour pipeline was correctly accounted for
(documented directly in the fragment shader source as a comment, so future
verification work doesn't repeat the naive-hex-comparison gap the first
pass hit).

### Naming: one open question, deliberately not resolved unilaterally

`kami-script-runtime-rs`'s own README already flagged that no established
`kotoba-lang` naming convention was found for "Rust substrate host paired
with an almost-same-named CLJC sibling repo." This ADR does not rename the
already-public, manifest-registered, actively-depended-on repo — that's a
disruptive decision left to the project owner. Separately, `kami-genre-base-
systems`' `games/<genre>/` layout was checked against `kami-clj-play/games/`
and found consistent (no naming gap there); whether individual genre
systems should eventually graduate to separate `kami-app-{genre}` crates
per `ARCHITECTURE.md`'s convention is a real open question, deliberately
left unresolved since these are base/foundation prototypes, not
crate-worthy shipped games.

### Library reuse: checked, confirmed nothing applicable

None of the ~113 Phase-7-restored `kotoba-lang` CLJC crates (`physics-2d`,
`pathfind`, `tilemap`, etc.) expose anything the kami-clj **guest** compiler
can call — they're host-side-only Rust-callable libraries, not part of the
guest module's fixed host-import vocabulary. The guest compiler's callable
surface is exactly the 14 host-imports `kami-script-runtime-rs` implements,
matching this session's established finding (concept 2/3/4/5 investigations
for isekai-network) that guest logic cannot reach Rust-substrate crates
directly.

## Consequences

- The specific compile-and-run blocker `isekai-network`'s README documented
  all session is closed — real execution, real pixel-verified rendering,
  both backends deterministic.
- `kotoba-lang/engine` now has both compile halves closed (semantic compile
  + byte emission); the previously-undocumented host-runtime gap now has an
  owning repo.
- `kami-genre-base-systems` (a separate, parallel initiative this session)
  can, as real follow-up, actually WASM-compile and run its systems through
  this same pipeline — not attempted in this ADR's scope.
- One naming question (`kami-script-runtime-rs`) is explicitly deferred,
  not silently accepted as final.

## Alternatives Considered

1. **Wait for `kotoba-lang/kami-script-runtime`'s own scope to eventually
   cover the host runtime**: rejected — that repo states the exclusion in
   absolute, permanent terms.
2. **Port the deleted Rust host runtime into `kami-engine`** (the
   asset/contract shell repo): rejected — `kami-engine` runs a CI "no-rust
   guard," structurally cannot host Rust source.
3. **Full `hecs`-based entity store instead of hand-rolled `HashMap`**:
   rejected for this pass given this session's established slow-build cost
   for wgpu/wasmtime-adjacent Rust crates — the hand-rolled store is real,
   tested, and functionally equivalent for current verification needs;
   `hecs` parity is a disclosed, legitimate follow-up.

## References

- ADR-2607010930 (clj-wgsl migration — the source of the deletion this ADR
  recovers from)
- ADR-2607023200 (isekai-network standalone repo — the consumer this work
  unblocks)
- `kotoba-lang/engine` PR #1, PR #2 (both merged)
- `kotoba-lang/kami-script-runtime-rs` (new repo)
- `gftdcojp/isekai-network`
