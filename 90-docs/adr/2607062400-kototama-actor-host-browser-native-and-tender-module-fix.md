# ADR-2607062400: kototama's `actor:host` ABI lands browser-native (priority target), `kototama.tender`'s module-name bug fixed

## Status

Accepted (implemented)

## Context

Direct follow-up to ADR-2607062330 (`kototama.tender`, the JVM/Chicory
execution runtime). Two things surfaced immediately after that landing:

1. **A real correctness bug**: `kototama.tender`'s `HostFunction`s were
   wired under Wasm import module `"kototama"`. Every actual `.kotoba`
   -compiled guest imports from module `"kotoba"` (hardcoded in
   `kotoba.runtime`'s WASM encoder, verified directly against source) —
   never `"kototama"`. `kototama.tender`'s own tests only ever passed
   because its hand-written WAT fixtures used the same (wrong) module
   name, so they linked against themselves but never against anything a
   real compiler would emit.
2. **A scope/priority question from the owner**: `kototama.tender`
   (JVM/Chicory) is, per the same-day CLAUDE.md directive, the LOWEST
   priority runtime target (`.cljc` priority: kototama > cljs > nbb >
   jvm). Before investing further in the JVM path, the owner asked
   whether `aiueos.execute`/`aiueos.launcher` (found, on investigation,
   to already be a more mature, independently-built duplicate of the
   same Chicory-hosted capability-gated execution idea — flagged as an
   unresolved "naming/purpose collision" in its own merge PR) should
   supersede `kototama.tender` outright. Clarifying the actual
   relationship surfaced that **kototama has two substrates**: JVM/
   Chicory (ADR-2607022900) and browser-native `WebAssembly.instantiate`
   (ADR-2607061630) — `aiueos.execute` is JVM-only and has no bearing on
   the browser substrate at all. The owner's decision: `kototama.contract`
   's `actor:host` ABI should target **the browser substrate first**
   (matching the stated priority), not be retired in favor of aiueos's
   JVM-only manifest vocabulary. `aiueos` vs `kototama.tender`
   consolidation on the JVM side remains open, deliberately deferred.

## Decision

### 1. `kototama.tender` module-name fix

`"kototama"` → `"kotoba"` in `tender.clj`'s `host-fn` and every WAT test
fixture. Field names (`gen_keypair`, `sign`, `verify`, `sha256_hex`,
`http_post`, `log_read`, `log_append`, `now`) don't collide with
`kotoba.wasm-exec`'s own (`kgraph_assert`, `has_capability`, ...) under
the same module, so a guest can import from both host surfaces. 15
tests/31 assertions still green after the rename.

### 2. `actor-host.js` — `kototama.contract`'s ABI, browser-native

New file in `kotoba-lang/wasm-webcomponent` (not a new repo — matches
that library's existing zero-build-step, dependency-free, CDN-servable
convention, same shape as its `kgraph.js`). Ports `kototama.contract`'s
`HostCaps`/`RuntimeLimits`/`validate-import-surface` to plain JS
(`hostCaps`/`validateImportSurface`), with the identical fail-closed
two-layer design `kototama.tender` established: pre-flight rejection
(before `WebAssembly.instantiateStreaming` even runs — `actorHostImports`
throws inside `KotobaWasmElement.createImports`, which the element's own
`connectedCallback` `try`/`catch` already renders as an error) plus a
per-call grant re-check, and RuntimeLimits exhaustion as an in-band `-1`
(not a throw) so a well-behaved guest can see it and back off.

**Implements 4 of the 8 `actor:host` imports** — `now`, `sha256-hex`
(hand-rolled, zero-dependency, synchronous SHA-256 — verified against
FIPS test vectors, not just self-consistency), `log-read`/`log-append!`
(an injectable byte store). **`gen-keypair`/`sign`/`verify`/`http-post`
are explicitly NOT implemented**, documented in the module's own header
comment, not silently dropped: a WebAssembly host-import function must
return synchronously, and both the Web Crypto API (`SubtleCrypto`, every
method async) and `fetch` are fundamentally asynchronous in a standard
browser without JS Promise Integration. A hand-rolled synchronous
Ed25519 was considered and rejected for this landing — real
cryptographic code with real correctness stakes, not something to rush
alongside three other pivots in one session.

Verified against a real native-WebAssembly round trip
(`examples/actor-host/actor-host-demo.wasm`, `wasm-tools`-assembled, same
module `"kotoba"` convention) — not mocked. 14 checks green in
`test/verify-actor-host.mjs`.

### 3. kototama's `web/` page: generated via `kotoba-lang/html`/`css` + `nbb`

Per the owner's direction to apply the `.cljc` priority rule to
*authoring* this page, not just guest-side logic: `web/index.html` is no
longer hand-written HTML. `web/generate.cljs` (an `nbb` script — the
3rd-priority tier, chosen over a JVM Clojure script for a lightweight
static-generation task) builds the page as Hiccup EDN, rendered via
`kotoba-lang/html`'s `html.core/render` and styled via `kotoba-lang/css`'s
`css.core/style-node`. The `<script type="module">` WebComponent-wiring
block is genuine executable JS, passed through verbatim via
`[:hiccup/raw ...]` (the same escape hatch `css.core/style-node` itself
uses to embed a rendered stylesheet). The generated `index.html` is still
a plain static file — no build step for a browser visiting the page,
only for regenerating it after an edit.

Added a third demo section (`actor-host-demo.wasm`, wired to
`actor-host.js`) and `verify-actor-host.mjs` (same jsdelivr-fetch smoke
test pattern `verify-kgraph.mjs` already used), bumping both files' pinned
`wasm-webcomponent` commit together.

## Consequences

- (+) `kototama.tender` can now actually link against a real compiled
  `.kotoba` guest — the module-name bug would have silently made every
  real-world use fail while every test stayed green.
- (+) `kototama.contract`'s ABI has its first priority-target
  implementation (browser-native), not just the lowest-priority JVM one.
- (+) The `kototama.tender` vs `aiueos.execute` (JVM/Chicory) duplication
  is now on record rather than silently rediscovered later, with an
  explicit decision to defer consolidation rather than guess at it.
- (+) `web/`'s authoring workflow now demonstrates the `.cljc` priority
  rule for something other than guest logic — a static page.
- (−) `actor-host.js` only covers half the `actor:host` surface. A guest
  needing `gen-keypair`/`sign`/`verify`/`http-post` in the browser has no
  path today short of JSPI (not broadly available) or a from-scratch
  synchronous crypto implementation (a real undertaking, not attempted).
- (−) `aiueos.execute`/`kototama.tender` consolidation on the JVM side is
  unresolved (deliberately deferred, not forgotten — see Follow-up).

## Follow-up

- Decide `aiueos.execute` vs `kototama.tender` consolidation on the JVM
  substrate (out of scope here — the browser substrate this ADR covers
  is unaffected either way).
- A synchronous, hand-rolled (or JSPI-based, once available) Ed25519 for
  `actor-host.js`'s `gen-keypair`/`sign`/`verify`.
- `http-post`'s browser story likely needs a fundamentally different
  shape (e.g. queue-and-poll across two host-import calls) rather than a
  single synchronous call, if it's ever wanted.

## One-line summary

**Fixed `kototama.tender`'s host-import module name (`"kototama"` →
`"kotoba"`, without which it could never link a real compiled guest);
landed `kototama.contract`'s `actor:host` ABI on kototama's
browser-native substrate (`actor-host.js` in `wasm-webcomponent`, 4 of 8
imports — the synchronous ones, honestly scoped) per the owner's
`.cljc` priority rule; and regenerated kototama's `web/` demo page via
`kotoba-lang/html`/`css` under `nbb`, applying that same priority rule to
authoring the page itself.**
