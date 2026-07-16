# ADR 2607032500: JVM verification is insufficient for kami-engine-clj/kami-genre-base-systems — real WASM execution is the standard

## Status
Accepted

## Context

Continuing directly from ADR-2607032400's `network-isekai` integration work, a self-paced
`/loop` to "raise game quality" surfaced two real, previously-undetected defects, and the
user explicitly redirected verification methodology mid-session: "jvm じゃなくて kotoba wasm
で動いてほしい" (I want it to run on kotoba-wasm, not JVM).

### Defect 1 — the "unreproduced" null-byte tag-string bug

`gftdcojp/network-isekai` issue #49 / PR #72 reported entity tag strings (`"player"`,
`"shiro-pico"`, `"ghost"`) decoding with a large null-byte prefix when the compiler's
output was actually run in a real browser (headless Chromium) — but a Node.js-only
`WebAssembly.instantiate` test of the same bytes could not reproduce it, leaving it
"inconclusive" for a full session iteration.

**Root cause**: `kotoba.engine-clj.codegen/emit-str` packs a 64-bit string handle as
`(abs << 32) | len` via `bit-shift-left`/`bit-or`. Under ClojureScript these compile to
JavaScript's native 32-bit bitwise operators, which `ToInt32`-coerce their operand
first — `abs << 32` silently becomes `abs << 0` = `abs`, discarding the high bits
entirely. Correct on the JVM, where Clojure's bitwise ops operate on full 64-bit
`long`s — exactly why `clojure -M:test` (51/51 green throughout) never caught it.
Fixing `emit-str` exposed an identical second-layer instance of the same bug class in
`wasm_bytes.cljc`'s `sleb128`/`uleb128`.

**Fix**: replaced the large-operand bitwise operations with arithmetic (`+`/`*`/`mod`/
`quot`, via a new `shr7` floored-division helper) — correct on both JVM and CLJS.
Landed as `kotoba-lang/kami-engine` PR #95, merged `a47f2623`. Re-verified via real
headless-Chromium execution of both `network-isekai`'s `tanememi` and `shiro-pico`
games post-fix.

### Defect 2 — 16 of 18 genre systems had never actually compiled

`kotoba-lang/kami-genre-base-systems`' 18 genre base systems had every one of their
`author.clj` files running successfully all session, but a direct `codegen/compile`
sweep found **16 of 18 `logic.clj` files failed to compile at all**.

**Root cause**: the maturity pass (combo counters, win/lose conditions, currency,
escalating alert levels, etc.) read `defatom` values via a bare symbol reference
(e.g. `(+ combo 1)`), but the compiler requires the explicit `(atom-val name)` accessor
for reads — a bare symbol is only valid as `set-atom!`'s write target. `sports` also
used `-`/`<` on f32 values where the compiler requires `-f`/`<f`. Every prior
verification pass ran `author.clj` (the datalevin→`scene.edn` authoring path) and
separately code-reviewed `logic.clj` against a proven-vocabulary checklist — neither
step ever actually invoked `codegen/compile`.

**Fix**: wrapped every value-position `defatom` reference in `(atom-val ...)` across
all 16 files; fixed `sports`' float operators. Landed as
`kotoba-lang/kami-genre-base-systems@cafb82e`.

## Decision

**kami-engine-clj-compiled game logic is not "verified" until it has:**
1. Compiled through the real CLJS/ClojureScript path (not JVM `clojure`), producing
   actual `.wasm` bytes.
2. Those bytes have been instantiated and actually **run** via a real WASM host
   (`kami-script-runtime-rs`'s `wasmtime`/`wasmi`, or a real browser's
   `WebAssembly.instantiate`) for enough ticks to observe genuinely mechanic-specific
   behavior — not just absence-of-crash.

JVM `codegen/compile` succeeding, `author.clj` running, and `wasm-tools validate`
passing bytes structurally are each individually necessary but far from sufficient —
useful as fast preliminary filters, never sufficient confirmation on their own.

### Why JVM specifically is insufficient

Clojure's bitwise operators are full-width 64-bit `long` operations on the JVM but
compile to JavaScript's native 32-bit bitwise operators under ClojureScript. Any
`kami-engine-clj` codegen path that packs/unpacks values wider than 32 bits via
bitwise ops is a live risk of Defect 1's bug class recurring silently — invisible to
the JVM test suite by construction, not by bad luck.

### Why `author.clj` specifically is insufficient

`author.clj` only exercises the datalevin→Datalog-query→`scene.edn` projection
(ADR-0036's authoring path) — zero code-path overlap with `logic.clj`'s
parse→codegen→wasm-bytes compilation. A game whose `author.clj` runs perfectly can
have a `logic.clj` that has never successfully compiled, as Defect 2 demonstrated for
16 of 18 genres simultaneously.

### Reference implementation

`kotoba-lang/kami-genre-base-systems`' `scripts/verify/` (`deps.edn` + `shadow-cljs.edn`
+ `verify/core.cljs` + `run-verify.sh`, commit `2c4c092`) implements this exact
two-stage standard and is the pattern other `kami-engine-clj` consumers should
replicate rather than reinvent.

### Not wired to CI

The verification script was run manually end-to-end (18/18 compile, 18/18 execute)
during development but is **not** wired into GitHub Actions CI — `gftdcojp`'s
org-level GitHub Actions setting remains disabled (confirmed multiple times this
session, most recently against `network-isekai` directly), outside this session's
control. A known, previously-reported, unresolved blocker, not a new gap.

## Consequences

- Both defects are fixed and merged/committed. `network-isekai` runs correctly
  end-to-end in a real browser with zero Rust; all 18 `kami-genre-base-systems`
  genres are confirmed to actually run, not merely appear correctly authored.
- **Standing process change**: any future work on `kami-engine-clj` itself, or on any
  consuming game/genre-system repo, should run (or extend) the `scripts/verify/`
  pattern before claiming a compile-level change is safe. JVM-only test passage is no
  longer sufficient grounds for that claim in this codebase.
- **Discovery cost acknowledged**: this defect class went undetected through this
  session's entire earlier compiler-authoring and genre-system-building work because
  the verification methodology itself had this gap. The fix is cheap; the exposure
  window was the full session until real browser/WASM execution was finally exercised
  end-to-end.

## Alternatives Considered

1. **Keep JVM `clojure -M:test` as the sole/primary compiler-correctness gate**,
   treat CLJS/browser execution as an optional final smoke test: rejected — this was
   literally the status quo that let Defect 1 ship undetected through a fully-green
   51/51 JVM suite for as long as it existed.
2. **Treat `wasm-tools validate` as sufficient** once real `.wasm` bytes exist, without
   requiring actual execution: rejected — Defect 1's corrupted bytes were structurally
   valid WASM (any 4-byte sequence is a structurally valid f32/i64 immediate);
   validation cannot distinguish semantically-wrong-but-well-formed bytes from correct
   ones, only execution can.
3. **Wire `scripts/verify/` into GitHub Actions CI now**: rejected for now —
   `gftdcojp` org-level Actions remains disabled, confirmed multiple times this session
   across multiple repos in the org; the script is ready to wire in the moment that
   blocker clears.

## References

- ADR-2607032100 (consolidate `engine` into `kami-engine-clj`)
- ADR-2607032400 (`network-isekai` primary consolidation)
- `kotoba-lang/kami-engine` PR #95 (merged `a47f2623`)
- `gftdcojp/network-isekai` issue #49 (closed)
- `kotoba-lang/kami-genre-base-systems@cafb82e` (16-genre `defatom`-read fix)
- `kotoba-lang/kami-genre-base-systems@2c4c092` (`scripts/verify/`)
