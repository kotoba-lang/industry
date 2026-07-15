# ADR-2607052000: kami-engine-sdk — retire Svelte, migrate to ClojureScript incrementally (cljc SSoT delegation, per ADR-2607020200's genko precedent)

## Status
Proposed

## Context

User direction (2026-07-05): "@etzhayyim/kami-engine-sdk を svelte から cljs にして. svelte
は退役" — convert `kami-engine-sdk` from Svelte to ClojureScript; Svelte is being retired.

`kami-engine-sdk` (`kotoba-lang/kami-engine-sdk`, package `@etzhayyim/kami-engine-sdk`,
Svelte 5) is a real, published SDK: VRM character viewer + headless builders (morph/bone/
motion/voice/emotion/part-composer/conversation), a Genko manga editor, a 3D Gaussian Splat
preview bridge, a headless incident-response "webvr" engine, WebRTC calling (`call/`), a
document/scene bridge, manufacturing/robotics planning helpers, and a trackpad-gesture embed
— 11 modules, ~91 files. Real consumers exist today: `ai-gftd-cyber-drill` (webvr),
`ai-gftd-mangaka`, `etzhayyim-project-image2metahuman`, `etzhayyim-project-image2vrm`,
`etzhayyim-project-baminiku`, `sumitsubo`, `kotoba-kotodama/kotodama-host-sdk`.

Initial scoping (user-confirmed): cover all 11 modules; consumer-app migration is explicit
follow-up, out of scope here — the SDK itself changes now, each consumer migrates off Svelte
separately, later.

**Critical discovery before any code was written**: this repo already has a real, in-flight,
ADR-governed incremental migration — `90-docs/adr/2607020200-mangaka-genko-cljc.md`
("genko の document model を cljc SSoT 化"). Two increments (`移植#1`, `移植#2`, commits
`67c3ce5`/`517bd8d`, 2026-07-02, authored by the user) already landed: `genko-embed.ts`'s
inline `allNodes`/`wouldCycle` logic was replaced, one function at a time, with calls to
`globalThis.KamiGenko.*` — a global populated by a compiled `.cljc` SSoT
(`kotoba-lang/kami-genko`, `shadow-cljs.edn`'s `:genko` build → `dist/kami-genko.js`, init-fn
`kami.mangaka.genko-js/install!`). ADR-2607020200's own text is explicit: *"this ADR does
NOT replace the TS runtime — genko-embed.ts keeps running as-is; cljc is a parallel SSoT,
with a follow-up to compile it to cljs and swap in the corresponding logic later."* Each
increment was verified in a real browser (WebGPU Chrome) before landing, gated by
`vitest`/`nbb` tests.

User-confirmed (this ADR): follow that exact established methodology — incremental
delegation to cljc SSoT repos, one function/component at a time, verified before moving on
— rather than a big-bang rewrite of all 91 files, which was explicitly rejected once the
existing precedent was surfaced.

A second discovery, also load-bearing for the plan below: `kotoba-lang/turn`,
`kotoba-lang/rt`, `kotoba-lang/net`, `kotoba-lang/signal` already exist — CLJC
reimplementations of the Rust `kotoba-turn`/`kotoba-rt`/`kotoba-net`/`kotoba-signal` crates
(removed by `kotoba-lang/kotoba` PR #259, restored per `repos.edn`'s `:extra-projects`
comment). `kami-engine-sdk`'s `src/lib/call/turn.ts` (TURN ephemeral-credential HMAC-SHA1
mint/verify) and `wire.ts` (hand-rolled CBOR signaling codec, byte-compatible with
`kotoba-rt`'s Rust `ciborium` implementation) are TypeScript reimplementations of exactly
what these repos already are the canonical home for — this is not a case of "extract logic
into a brand-new SSoT repo" like genko needed; the SSoT repos already exist and are already
tested against the same RFC 2202 / cross-language wire-format vectors `turn.test.ts`/
`wire.test.ts` assert.

## Decision

**Migrate `kami-engine-sdk` from Svelte to ClojureScript module-by-module, each module
following the genko precedent's shape: pure logic → an existing-or-new cljc SSoT repo,
compiled to a `globalThis.<Name>` JS bridge, the TS/Svelte host delegates one function at a
time, each increment verified before the next.** Full research (5 parallel deep-reads,
covering every file in all 11 modules — types/data, builders, components, call+webvr, genko,
gsplat/manufacturing/trackpad/document) is complete and gives an accurate ground truth for
every subsequent increment; that research is not repeated here.

### Per-module SSoT destination and plan

1. **`call`/`webvr`'s protocol layer → existing `kotoba-lang/turn`/`rt`/`net`/`signal`.**
   - `turn.ts` (HMAC-SHA1 TURN credentials): `kotoba-lang/turn`'s `kotoba.turn.credential`
     already has `hmac-sha1-base64`/`constant-time-eq`/RFC-2202-tested primitives, just
     under a different (single-`user`, plain-boolean-verify) contract than `turn.ts`'s
     room/player-scoped, reason-reporting one. **Landed this ADR's first real increment**:
     added `mint-credential-scoped`/`verify-credential-scoped` to that same namespace,
     matching `turn.ts`'s exact username encoding (`"<expiry>:<room>:<player>"`) and verify
     result shape (`{:ok true :room :player}` / `{:ok false :reason ...}`), with JVM tests
     replicating all 7 of `turn.test.ts`'s assertions (`kotoba-lang/turn` commit `2d90bddc`,
     merged to main). **Not yet done** (explicit follow-up, not this increment): wiring
     `turn.ts` itself to delegate to a compiled `globalThis.KotobaTurn.*` bridge — that needs
     a `shadow-cljs.edn` `:browser` build + `install!` init-fn in `kotoba-lang/turn` (mirror
     of `kami-genko`'s `:genko` build target) that doesn't exist yet, plus wiring that bundle
     into `kami-engine-sdk`'s build/publish step.
   - `wire.ts` (CBOR signaling codec): belongs in `kotoba-lang/rt` or `kotoba-lang/signal`
     (whichever already owns the `kotoba-rt` wire-format port) — not yet started.
   - `call.ts` (WebRTC orchestration — perfect-negotiation glare resolution, reconnect
     backoff, stats): the actual `RTCPeerConnection`/`WebSocket` orchestration is inherently
     host/browser-API code, not pure logic — likely stays as a thin TS/CLJS host shell that
     calls into the wire/turn cljc bridges, matching genko's "host keeps DOM/WASM/mutation,
     cljc gets pure logic" split. Not yet started.
   - `webvr`'s `incident-pregel.ts` (`applySelection`/`initialState`, the actually-executed
     pure state-machine logic — the compiled `INCIDENT_GRAPH` StateGraph is vestigial/
     unused at runtime per the research) is a good, small, next candidate: pure, has a full
     test suite (`webvr.test.ts`), no host/DOM coupling. Target SSoT repo not yet chosen —
     candidate: a new small `kotoba-lang/kami-webvr-clj` (no existing repo owns
     "choice-scenario KPI state machine" specifically) or fold into `kotoba-lang/rt` if that
     repo's scope is broad enough. Decide at that increment's start, not speculatively here.

2. **`genko` → already in progress under ADR-2607020200/`kotoba-lang/kami-genko`.** Continue
   its existing `移植#N` numbering; this ADR does not restart or duplicate that work. Per the
   genko research: `Genko.svelte`'s `FloatingTools` undo/redo callbacks are wired to
   `console.info` stubs instead of the component's own real `undo()`/`redo()` functions — a
   likely live bug worth fixing as part of (not blocking) a future genko increment, not
   this ADR's concern to fix directly.

3. **`builders`/`components` (VRM viewer + morph/bone/motion/voice/emotion/part-composer/
   conversation) → new cljc SSoT, name TBD at that increment's start** (candidates: extend
   `kotoba-lang/kami-engine-sdk-clj` — confirmed via that repo's own README to be a
   *different, unrelated* project (scene/ECS/render-IR/WIT contract for kami-engine, not UI
   builders) so reusing it would misuse its stated scope; more likely a new
   `kami-vrm-controllers-clj` or similar, split from `kotoba-lang/kami-engine`'s monorepo
   the same way `kami-mangaka-genko-clj`→`kami-genko` and `kami-mangaka-genko-clj`'s sibling
   splits were done). Best first sub-increment candidates (pure, no Svelte-runes-as-such
   coupling once extracted, real behavioral subtlety worth pinning in tests): the anatomical
   joint-clamp table (`data/joint-limits.ts`'s `clampBoneDeg`/`clampBoneRad` — pure, already
   has a Rust counterpart per its own doc comment, "matches Rust
   `kami-skeleton::defaultHumanoidConstraints()`"), and `data/emotion-patterns.ts`'s
   `analyzeTextEmotion`/`emotionToMorphWeights` (pure regex+arithmetic, well-isolated,
   already tested indirectly through `createEmotionAnalyzer`). The actual `VrmViewer`/
   `VrmCanvas`/panel components (reactive UI + a real WebGPU/WASM host loop) are the
   highest-risk, do-last part of this module — matching `Canvas.svelte`'s own designation
   in the research as "the highest-risk component to port," ~1370 lines of imperative
   WebGPU/WebGL/DOM code that "will port almost mechanically" once the pure pieces
   underneath it are already cljc.

4. **`gsplat`/`manufacturing`/`document`/`trackpad`** — all four are plain, framework-agnostic
   TypeScript already (zero Svelte runes in any of them), each with real test coverage.
   These are the lowest-risk, most mechanical ports in the whole SDK — good candidates for
   early increments precisely because they carry no UI-reactivity-mapping risk at all, only
   straight logic translation + test-parity. Target SSoT: likely a new
   `kami-industrial-clj`-family split (gsplat is arguably `kami-engine`-core-adjacent instead
   — decide per-module at increment time, not speculatively here).

### Non-negotiable process constraints (carried over from ADR-2607020200, not new)

- **No wholesale rewrite commits.** Each increment ports one function, one component, or one
  small cohesive group — never "the whole module" in one commit — matching
  `manifest/repos.edn` `:manifest-workflow`'s own `:never [:wholesale-regen-commit]` doctrine
  applied here to code, not just manifest pins.
- **Delegate, don't reimplement past the point of reuse.** Where a canonical cljc SSoT
  already exists (`kotoba-lang/turn`/`rt`/`net`/`signal`, `kotoba-lang/kami-genko`), extend
  it; do not create a second, competing implementation of the same protocol/logic.
- **Verify before moving on.** Genko's increments were browser-verified (WebGPU Chrome) since
  they touched a live embedded runtime; increments with no live host yet (like this ADR's
  `turn.ts`-adjacent first step) are JVM/`nbb`-test-verified instead — pick whichever
  verification is real and possible for that increment, but do not skip verification.
- **Host/DOM/WASM stays host; only pure logic moves to cljc**, exactly as ADR-2607020200
  states for genko's oplog/tree/document-model vs. its WebGPU/DOM runtime.

## Consequences

- No single commit or PR "finishes" this migration — it is a long-running, multi-increment
  effort, same as genko's already-real 2-increments-in progress state. This ADR's own
  concrete output is: (a) the full 11-module research/ground-truth (retained in this
  session's transcript, summarized per-module above), (b) the first real increment
  (`kotoba-lang/turn`'s scoped mint/verify, landed), (c) this plan, sequencing candidate next
  increments without over-committing to an exact order.
- Consumer apps (cyber-drill, mangaka, image2metahuman, image2vrm,
  etzhayyim-project-baminiku, sumitsubo, kotodama-host-sdk) are unaffected until they
  explicitly upgrade — the published Svelte package keeps working at its current pinned
  version throughout this migration; nothing here forces an immediate breaking change on
  them.
- Real behavioral subtleties documented during research (not to be silently "fixed" during
  porting unless separately requested): `BoneController.setBone`'s single-axis-quaternion
  overwrite; `createConversationController.idleMicro`'s same-class Z-only clobber;
  `createMotionPlayer`'s Rust-WASM branch being dead-in-practice (kami never passed at the
  real call site); `createVoiceSynth.speak()`'s `speaking` flag not resetting on the
  no-audio success path; `NodeRow.svelte`'s always-▼ collapse arrow; `Toolbar.svelte`'s
  inert paper-texture selector; `FloatingTools`' undo/redo wired to `console.info` stubs
  instead of `Genko.svelte`'s real `undo()`/`redo()`. Each is a "replicate faithfully, flag,
  decide fix-or-not at that increment" item, not something this ADR resolves.

## Alternatives Considered

1. **Full rewrite of all 91 files in one pass, as initially scoped before the ADR-2607020200
   precedent was found.** Rejected once discovered — contradicts the user's own established,
   working, browser-verified incremental methodology; would also risk duplicating/
   conflicting with genko's already-in-flight increments and reinventing what
   `kotoba-lang/turn`/`rt`/`net`/`signal` already are the canonical home for.
2. **Reuse `kotoba-lang/kami-engine-sdk-clj` for the builders/components port.** Rejected —
   that repo's own README states it is a different project (scene/ECS/render-IR/WIT
   contract for kami-engine authoring, not a UI-component/controller SSoT); forcing the VRM
   controller logic into it would misuse its documented scope.

## Amendment (2026-07-06): second increment landed — full `webvr` port

`kotoba-lang/kami-webvr` — the entire `webvr` module (`types.ts`, `incident-pregel.ts`,
`cine-bridge.ts`, `createIncidentVrEngine.svelte.ts`) ported to CLJC/CLJS in one new repo,
not the incremental "delegate one function, keep the TS host" shape genko's `移植#N`
pattern uses. This refines (does not contradict) this ADR's own methodology section: a
full-module port is the right increment size when there's no live embedded-HTML host to
incrementally de-risk against — `webvr`'s only real consumer (`ai-gftd-cyber-drill`)
supplies its own renderer via `onScene` and isn't touched by this port at all, unlike
`genko-embed.ts`'s live production runtime. The bar stayed the same either way: every
original test assertion ported 1:1 before landing (20/20 green — 9 JVM `nbb test` for the
pure `types.cljc`/`incident_pregel.cljc`, 11 CLJS `node-test` for `engine.cljs`/
`cine_bridge.cljs`).

The compiled `INCIDENT_GRAPH` LangGraph `StateGraph` (8 super-steps mirroring
`incident-pregel.ts`'s logic, for LangGraph-Studio parity) was deliberately **not**
ported — confirmed via direct reading of the original source that the real engine drives
state through the pure `applySelection`/`initialState` functions directly, never
`INCIDENT_GRAPH.invoke(...)`, and no test exercises the graph. It was non-functional
documentation scaffolding; can be added later as a thin wrapper around the same pure
functions if LangGraph-Studio visualization is ever actually needed.

**`ai-gftd-cyber-drill` is out of scope entirely** (owner-confirmed 2026-07-06) — not a
pending follow-up, not migrated to `kami-webvr`, not planned to be. `kami-webvr` exists as
a standalone port in its own right; nothing here obligates or schedules migrating its
original TS-side consumer.

## References

- `90-docs/adr/2607020200-mangaka-genko-cljc.md` (the precedent this ADR follows)
- `kotoba-lang/kami-genko` (existing genko SSoT, `shadow-cljs.edn` `:genko` build →
  `globalThis.KamiGenko`)
- `kotoba-lang/turn` commit `2d90bddc09b3f779fb63feabbcbc03f3450a4112` (this ADR's first
  landed increment: `mint-credential-scoped`/`verify-credential-scoped`)
- `manifest/repos.edn` line ~1188 (`:extra-projects` comment on `kotoba-lang/rt`/`turn`/
  `net`/`signal` — "CLJC reimplementation of the Rust crates removed by kotoba-lang/kotoba
  PR #259")
- `kotoba-lang/kami-engine-sdk`'s `src/lib/{builders,components,call,webvr,genko,gsplat,
  manufacturing,trackpad,document,data,types}/*` (full research ground truth, this session)
