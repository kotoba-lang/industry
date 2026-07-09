# ADR 2607032400: network-isekai is the primary/canonical repo; isekai-network consolidated into it

## Status
Accepted

## Context

Earlier this session, `gftdcojp/isekai-network` was created as a standalone repo
for "Ghost Hacker: Shiro & Pico" (ADR-2607023200), following the precedent set by
`ai-gftd-ghosthacker-shiropico`'s own standalone-repo split (ADR-2607011816).

Investigation triggered by a naming-convention question ("kotoba-lang/wgsl 実在
しないの?") led to discovering `gftdcojp/network-isekai` — a pre-existing, already
live-deployed project at `isekai.network` with almost exactly this session's
target architecture: browser-based live CLJ game editing (edit `logic.cljc` →
compile → `WebAssembly.instantiate` → render via `kotoba-lang/webgpu`, zero Rust
at runtime). Its compiler path broke when `kototama`'s Rust wrapper
(`compile_game`/`compile_clj`) was removed with no CLJS replacement during the
clj-wgsl migration (tracked as `network-isekai` issue #49). This session's
`kotoba-lang/kami-engine/kami-engine-clj` (formerly `kotoba-lang/engine`,
consolidated per ADR-2607032100) is `.cljc` and targets the exact same
`kami:engine/*@1.0.0` host-import ABI `kami.host` (in `kotoba-lang/webgpu`)
already implements — a strong, later-confirmed candidate to fix issue #49.

The user clarified explicitly: `network-isekai` (matching the live domain
`isekai.network`) is the **primary/canonical** repo, not `isekai-network`. The
two should be integrated, with `isekai-network`'s content moving into
`network-isekai`, not the reverse.

## Decision

### `isekai-network` consolidated into `network-isekai`

`gftdcojp/isekai-network`'s content (5-concept design bible +
`games/01-netsurvivors/{logic.clj,scene.edn}`, the only concept with real code)
moved to `network-isekai`'s `public/games/gftd/shiro-pico/`, matching that
repo's own existing per-game convention (`public/games/gftd/{name}/{game.edn,
logic.cljc,scene.edn}` — see `tanememi`/`royale` as precedent). `logic.clj` was
renamed to `logic.cljc` (content unchanged — the kami-clj compiler subset needs
no reader-conditional gating). `author.clj`/`deps.edn`/`.gitignore`/CI from the
old standalone repo were NOT carried over — those were specific to
`isekai-network`'s own repo-level tooling (the datalevin-authoring step);
`scene.edn` is already the generated artifact and needs no re-authoring step
inside `network-isekai`. Full accounting in
`public/games/gftd/shiro-pico/PROVENANCE.md` (network-isekai).

Landed via `network-isekai` PR #76 (additive only, new directory, no existing
file touched, no deploy/build config touched) — merged directly given the
low-risk additive nature, following this session's established
archive-and-redirect pattern.

`gftdcojp/isekai-network` is archived (not deleted) with its README pointing to
the new location — same non-destructive pattern as `kotoba-lang/engine`'s
archival (ADR-2607032100).

### Compiler-connection work (issue #49) — separate PR, not merged here

A companion PR (`network-isekai` #72, `feat/issue-49-cljs-compiler`) wires
`kotoba-lang/kami-engine/kami-engine-clj`'s CLJS compile path into
`network-isekai`'s `boot!` as a fallback when the frozen pre-#14 Rust-built
`window.kototama` artifact isn't loaded — verified compiling and running for
real in headless Chromium (production's actual `tanememi/logic.cljc`, 60 real
ticks, live entities). This PR is **not merged** — it touches the live game
boot path and needs human review, unlike this ADR's additive game-directory
move. A reported string-decode discrepancy in that PR's own test writeup could
not be reproduced in a focused re-investigation (raw Node.js `WebAssembly.
instantiate` against the exact same compiled bytes, single- and multi-string-
literal cases, the exact `init` nested-call shape from `tanememi/logic.cljc`,
and the CLJS-EDN-reader wrapping workaround all decoded correctly, byte-for-
byte and string-for-string) — most likely a test-harness artifact in that PR's
specific headless-Chromium setup rather than a defect in the compiler or
`kami.host.cljs`, but this is not fully confirmed since the actual
shadow-cljs/browser build wasn't re-run in this pass (no shadow-cljs toolchain
available in this session's environment without a slow fresh install). Left as
an open follow-up on PR #72 itself, not blocking this ADR's game-move.

## Consequences

- `network-isekai`/`isekai.network` is now explicitly the primary repo for this
  session's game-engine-adjacent work; future network-isekai-genre games should
  land there directly (`public/games/gftd/{name}/`), not as new standalone repos.
- `gftdcojp/isekai-network`'s manifest registration (`repos.edn`/`west.yml`,
  added when it was created) is removed in this same landing — see
  `manifest-registration` below.
- `kotoba-lang/kami-genre-base-systems` and `kotoba-lang/kami-script-runtime-rs`
  (both reference `isekai-network` as their example/reference game) got a short
  pointer note added to their READMEs rather than a full rewrite of every
  historical mention — those repos' own test artifacts (e.g.
  `tests/fixtures/isekai-network-01-netsurvivors.wasm` filenames) are historical
  record of what was actually tested and are left as-is.
- The compiler-connection fix for `network-isekai` issue #49 remains open
  (PR #72, pending review) — independent of this ADR's game-directory move,
  which does not depend on issue #49 landing.

## Alternatives Considered

1. **Keep `isekai-network` as a separate repo, cross-link only**: rejected per
   explicit user direction — `network-isekai` is the primary repo, duplicated
   parallel repos for the same underlying "network-isekai game" concept is
   exactly the confusion this ADR resolves.
2. **Rewrite every historical reference to `isekai-network` across all
   dependent repos and this session's own ADRs**: rejected — ADRs are
   point-in-time historical records (this session's own established
   convention, e.g. ADR-2607011816/2607012000's supersession handling);
   dependent-repo READMEs get a forward-pointing note, not a full rewrite.

## References

- ADR-2607023200 (isekai-network standalone repo — now superseded by this ADR)
- ADR-2607032100 (kotoba-lang/engine → kami-engine/kami-engine-clj consolidation
  — the compiler piece this ADR's issue #49 connection depends on)
- `network-isekai` issue #49, PR #72 (compiler connection, not merged), PR #76
  (game-directory move, merged)
- `gftdcojp/isekai-network` (archived)
