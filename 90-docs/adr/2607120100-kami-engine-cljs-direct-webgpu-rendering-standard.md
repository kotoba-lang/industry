# ADR-2607120100: kami-engine app rendering standard is cljs-direct WebGPU (org-w3-webgpu) where no existing engine boundary is reused

- **Status**: accepted (2026-07-12)
- **Related**: ADR-2607051400 (kami-engine WebGPU SDK consolidation — created
  `org-w3-webgpu` as the raw 1:1 cljs WebGPU binding), ADR-2607052000
  (kami-engine-sdk Svelte retirement → cljc/Reagent), ADR-2607032100
  (consolidate engine into kami-engine umbrella), ADR-2607100030 (kotoba
  wasm host imports — the deterministic *guest-logic* sandbox this ADR
  explicitly does NOT touch), ADR-2607100100 (kami-app-amenominaka — the
  first standalone app on this exact cljs→org-w3-webgpu path),
  `gftdcojp/network-isekai` local ADR-0001/ADR-0002/ADR-0033 (the
  renderer-side instance of this same decision, made concretely there).

## Context

CLAUDE.md's 2026-07-10 runtime-priority addendum ranks `kotoba wasm runtime
> clojurewasm > ClojureScript > nbb`, demotes JVM/bb to last resort, and
separately forbids writing *new* Rust crates to fill an app's rendering
needs. It explicitly carves out the opposite case: if an existing Rust
engine already exposes a WASM/JS boundary, calling it as-is is fine — the
prohibition is on filling a rendering gap *by writing new Rust*, not on
using Rust that already exists and is already exposed.

Investigating "does kami-engine already go through wasm for rendering"
turned up a split picture, and this ADR treats the two halves differently:

- **`gftdcojp/network-isekai`'s renderer (`kami-webgpu` → `w3.webgpu`,
  i.e. `org-w3-webgpu`) is already fully cljs-direct** — no Rust, no wasm,
  no string marshaling. This was true in the code before this ADR; it just
  wasn't true in that repo's own docs (ADR-0001 §1 / ADR-0002 still
  described a Rust `kami-web` renderer whose source was removed upstream
  2026-07-01, issue #49). network-isekai's local ADR-0033 closes that
  documentation gap directly and moves `at6-texan` onto the same pure-3D
  path `royale` already used. There was never an existing Rust engine to
  reuse here (the source was deleted upstream with no replacement), so the
  cljs path is not an alternative to a maintained Rust one — it's the only
  one that exists.
- **`kotoba-lang/kami-engine-sdk`** (renamed 2026-07-10 from
  `kami-engine-sdk-clj`; unrelated to the Svelte package that briefly held
  the `kami-engine-sdk` name, now `kami-engine-sdk-svelte`, archived) is a
  *separate* SDK from network-isekai. Its `kami.gpu/IGpuBackend` protocol
  has one real implementation, `kami.backend.browser`, driving a Rust
  `kami-clj-host` wasm-bindgen module → `kami-render` (Rust/wgpu) →
  WebGPU. Initially this read as "the wasm/Rust gap CLAUDE.md warns
  about" — but the SDK's own `ARCHITECTURE.md` ("Decision summary: the
  three forks") states this is a **deliberate, reasoned design fork**:
  *"Render runtime: Keep the Rust backend... 'Not Rust' applies to
  everything above the GPU — the SDK, gameplay, scene model — not to the
  GPU driver itself,"* and explicitly lists as a non-goal: *"Not a wgpu
  re-implementation in clj/cljs... We do not re-implement wgpu in
  Clojure."* `kami-render` is battle-tested (7 scene pipelines, WGSL,
  bootstrap policy) and already exposes a wasm-bindgen boundary
  (`kami-clj-host`) that `kami.backend.browser` calls as-is — this is
  exactly the case CLAUDE.md's carve-out describes, not a violation of it.
  Writing a parallel `kami.backend.web` from raw `w3.webgpu` primitives
  would mean re-implementing mesh/material/shader/texture/text
  registration and frame submission from scratch — i.e. re-implementing a
  renderer — which is precisely what this SDK's own non-goals rule out,
  and would be scope creep unjustified by CLAUDE.md's actual rule.
- **`kami-app-amenominaka`** (ADR-2607100100) already established the
  precedent of calling `kami.webgpu`/`w3.webgpu` directly from a
  standalone app, outside network-isekai, verified in real Chrome — a
  case where (like network-isekai) there was no existing battle-tested
  Rust renderer to reuse.
- **`kami-engine-web`/`kami-engine-render`** (umbrella scaffold repos) are
  both single-file stubs — "CLJC restoration is pending" — with no WebGPU
  dependency of either kind yet, and no existing Rust renderer bound to
  either.

`kotoba.kami-host`'s wasm-hosted ECS (ADR-2607100030) is a different
concern entirely: a capability-guarded, deterministic *guest-logic*
sandbox for `.kotoba`-authored game rules, not a rendering technology.
Nothing here changes that boundary — it stays wasm-hosted by design, and
was never the renderer in the first place.

## Decision

1. **Where no existing, maintained rendering engine is already bound in,
   the standard app-level rendering path for the kami-engine family is
   cljs `kami.webgpu`/`w3.webgpu` (`org-w3-webgpu`) direct — no new Rust,
   no new wasm-bindgen GPU backend.** This is already true for
   network-isekai (all games) and kami-app-amenominaka; it becomes the
   documented default for new rendering work, consistent with CLAUDE.md's
   cljs-first runtime priority and no-*new*-Rust-for-rendering rule.
2. **`kami-engine-sdk`'s `kami.backend.browser` (Rust wasm-bindgen
   `kami-clj-host`→`kami-render`) is NOT demoted or replaced.** It is a
   legitimate, explicitly-reasoned exception under CLAUDE.md's own
   carve-out (an existing exposed Rust/WASM boundary, called as-is, not a
   new Rust crate) and under the SDK's own `ARCHITECTURE.md` non-goal
   ("not a wgpu re-implementation in clj/cljs"). No new
   `kami.backend.web` is introduced by this ADR — writing one would
   duplicate a battle-tested renderer for no stated benefit and
   contradict the SDK's own documented design.
3. **`kotoba.kami-host`'s wasm-hosted deterministic ECS is explicitly
   out of scope and unaffected.** It solves a different problem
   (sandboxed, capability-guarded guest logic) and stays wasm-hosted;
   nothing in this ADR argues for moving guest game *logic* off wasm, and
   it never argued for moving *rendering* off an already-reused Rust
   engine either.
4. **`kami-engine-web`/`kami-engine-render` are left as stub scaffolds,
   explicitly deferred.** If and when they're filled in, new modules
   there should default to `w3.webgpu`/`kami.webgpu` unless a specific,
   existing, battle-tested rendering engine is being deliberately reused
   (per decision 2's carve-out) — not undertaken in this pass.

## Consequences

- No behavior or code change for network-isekai, kami-app-amenominaka, or
  `kami-engine-sdk` — this ADR documents and formalizes the pattern
  already present across the ecosystem, and corrects one repo's stale
  docs (network-isekai's ADR-0001 §1/ADR-0002, closed by its local
  ADR-0033) rather than mandating new engineering.
- Future kami-engine rendering work should ask first "is there already a
  battle-tested, exposed engine boundary to reuse?" — if yes (as with
  `kami-render`), call it as-is; if no (as with network-isekai and
  kami-app-amenominaka), default to `w3.webgpu`/`kami.webgpu`. Neither
  case ever calls for writing a *new* Rust crate.

## Follow-up

- `kami-engine-web`/`kami-engine-render` scaffolding (deferred).
- `kami-engine-sdk-svelte`'s own Rust/wgpu-wasm VRM viewer path (separate,
  already mid-retirement under ADR-2607052000's Svelte→cljc migration) is
  untouched by this ADR.
- The west manifest still registers this SDK repo under its pre-rename
  name `kami-engine-sdk-clj` (`orgs/kotoba-lang/kami-engine-sdk-clj` →
  `git@github.com:kotoba-lang/kami-engine-sdk-clj`, which GitHub now
  redirects to `kotoba-lang/kami-engine-sdk`); updating the manifest entry
  to the current name is a separate, low-urgency follow-up, not done here.
