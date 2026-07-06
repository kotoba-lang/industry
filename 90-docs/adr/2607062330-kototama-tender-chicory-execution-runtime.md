# ADR-2607062330: `kototama.tender` — the Chicory/JVM execution runtime ADR-2607022900 decided on, landed

## Status

Accepted (implemented)

## Context

Owner directive (2026-07-06, recorded in `CLAUDE.md`): `.cljc` runtime
priority going forward is **kototama (WASM runtime) > ClojureScript >
nbb > JVM**. Investigating what that actually requires found `kototama`
itself was not ready to be "first" for anything: the repo implemented
only `kototama.contract` — pure, side-effect-free `HostCaps`/
`RuntimeLimits`/`validate-import-surface` data validation, zero deps,
**no execution anywhere**. `contract.cljc`'s own docstring already says
imports are "accepted only when the contract grants and runtime limits
allow it," but nothing made that decision load-bearing — a caller could
call `validate-import-surface`, get `{:ok? true}`, and there was
nowhere to actually run a guest under that grant.

Prior ADRs had already resolved the *how*, just not built it:

- **ADR-2607022400**: kototama = the Wasm execution runtime, adopting
  Solo5's *tender* pattern (kototama = tender, the Wasm component it
  runs = guest). Declared as a design decision only.
- **ADR-2607022700**: initially planned the tender's native adapter as
  Rust, with a rule this ADR keeps: **the tender never decides
  capability grants itself** (that authority lives elsewhere; the
  tender only enforces a decision already made).
- **ADR-2607022900**: overturned the Rust assumption — `kotoba-lang/
  kotoba`'s `wasm_exec.clj` already proved a pure-JVM Wasm host works
  via `com.dylibso.chicory`, so **the tender's execution layer is JVM/
  Clojure via Chicory, not Rust/wasmtime**. This ADR's own "Follow-up"
  section claimed quota/fuel/etc. had already landed in
  `aiueos-cljc-contract` PRs #2–#12 — **verified false during this
  investigation**: no such files, dependency, or commits exist in that
  repo's checkout or full git history. Treat that ADR's implementation
  claims as stale; this ADR is the first one that actually ships
  running code for the tender.

"Tender" itself had zero code artifacts anywhere (grepped the whole
org) — pure vocabulary describing an architectural role, not an
existing type to extend.

## Decision

**New namespace `kototama.tender`** (`kototama/src/kototama/tender.clj`,
`:clj`-only — Chicory itself is a JVM library, no reason to pretend
portability). Written fresh, not vendored from `kotoba.wasm-exec`: that
namespace is tightly coupled to kotoba's own `kgraph`/capability
vocabulary (`kotoba.runtime/capability-contract`, `:kotoba.policy/
capabilities`), and depending on it would run a second, incompatible
capability model next to kototama's own `actor:host` ABI — exactly the
"semantic authority duplication" ADR-2607022700 rules out. The generic
Chicory plumbing (`host-fn`/`instantiate`/`call-main`/`run-main`/
`fuel-listener`/memory ptr-len helpers) is the same *shape* as
`wasm_exec.clj`'s because there is only one sane way to wire a
`HostFunction` — proof-of-pattern, not shared code.

### Fail-closed, two layers

1. **Pre-flight** (`instantiate`, before any `Instance` exists):
   `contract/validate-import-surface` runs against the caller-declared
   `requested-imports` (the specific guest's needed subset of the 8
   contract imports) and `HostCaps`. Not `:ok?` → throw immediately,
   the guest's bytes are never even parsed.
2. **Per-call** (inside every wired `HostFunction`): `ensure-granted!`
   re-checks the same grant. Redundant given step 1 already ran, but no
   code path — including a caller who builds the host-function list a
   different way — can skip the check by construction.

### Two distinct denial shapes, deliberately not unified

- **Grant violation** (`denied!`, `:grant/missing`) — a structural
  authority breach (the guest reached for something it was never
  granted). Hard `throw`, aborting the whole `main` call. This is not a
  guest-recoverable condition.
- **Limit exhaustion** (`RuntimeLimits`, e.g. `:max-http-posts`/
  `:max-log-append-bytes`) — an ordinary, expected quota condition.
  Signaled **in-band as `-1`**, the same convention `write-bytes!`'s
  buffer-overflow case already uses, so a well-behaved guest sees it
  and can back off instead of the entire `main` invocation crashing on
  an uncaught Java exception it has no way to handle. (First test draft
  conflated the two — hard-threw on limit exhaustion — and a real
  end-to-end WAT test caught the mismatch immediately: the guest never
  got control back to return anything.)

Chicory has no native concept of "N calls of category X" or "N bytes
moved" — that accounting is `kototama.tender`'s own responsibility, via
one `(atom {:http-posts 0 :log-read-bytes 0 :log-append-bytes 0})` per
`Instance`, closed over by each `HostFunction`.

### All 8 `kototama.contract/import-surface` imports wired

`gen-keypair`/`sign`/`verify` (via `kotoba-lang/ed25519`'s existing,
tested primitives — reused, not reimplemented), `sha256-hex` (JDK
`MessageDigest`, no extra dep needed), `http-post` (`java.net.http.
HttpClient`, synchronous — a Chicory host function call has no async
contract), `log-read`/`log-append!` (an injectable `{:read-fn
:append-fn}` port — kototama itself owns no storage backend, matching
its own "don't become the semantic authority" stance from the README),
`now` (`System/currentTimeMillis`).

### Verified against real Wasm binaries, not mocked

`kototama.tender-test` shells out to `wasm-tools` (Bytecode Alliance)
to assemble small WAT fixtures into real Wasm bytes, then runs them
through the actual `Parser`/`Instance`/`HostFunction` Chicory pipeline
— same end-to-end discipline `kotoba.wasm-exec_test.clj` already
established for kotoba's own tender-shaped host. Covers: `sha256-hex`
against a known digest, a real `gen-keypair`→`sign`→`verify` round
trip, pre-flight grant rejection (no Instance built), the fuel listener
trapping an infinite loop, and `log-append!`'s byte-limit denying past
its cap while staying within it succeeds. 15 tests/31 assertions green.

## Consequences

- (+) "kototama first" now has something real to be first *for* —
  before this landing, "target kototama" had no execution path at all,
  only a validation function nothing called into.
- (+) `contract.cljc`'s pure-data design is preserved exactly as
  written (it still does zero execution) — `tender.clj` is a consumer
  of it, not a rewrite.
- (+) ADR-2607022900's stale "implemented in aiueos-cljc-contract"
  claims are now corrected on the record, and this ADR is the first
  with actually-running code to point to.
- (−) No device-access quartet (`pci-config`/`dma-map`/`irq-subscribe`/
  `mmio-map`) — ADR-2607022900 already scoped that out as needing a
  privileged/hypervisor layer independent of language choice; unchanged
  here.
- (−) `http-post` is a blocking synchronous call inside a Wasm host
  function — acceptable for a first landing, but a guest that calls it
  in a tight loop ties up the calling JVM thread for the request
  duration; no timeout/cancellation wired beyond the fuel limit
  (instruction count, not wall-clock).
- (−) `kototama.tender` is `:clj`-only, same as its Chicory dependency
  — no `:cljs`/kototama-in-the-browser story from this landing (the
  separate `web/` WebComponent PoC, ADR-2607061630, is a genuinely
  different, additive execution premise — native `WebAssembly.
  instantiate` with zero host-import ABI — and is unaffected).

## Follow-up

- `aiueos`'s broker/policy layer actually calling `kototama.tender/
  instantiate` with real `HostCaps` it derived, closing the "aiueos
  decides, kototama enforces" loop end to end (today `HostCaps` is
  caller-supplied in tests, not yet sourced from a real policy decision
  service).
- A non-blocking `http-post` (or a documented "guests needing network
  should not busy-loop on it" constraint) if a guest workload actually
  needs concurrent outbound calls.
- The privileged device-access layer ADR-2607030900 left unresolved.

## One-line summary

**`kototama.tender` is the Chicory/JVM execution layer ADR-2607022400/
2607022900 specified but never shipped — every `kototama.contract`
import is wired to a real, fail-closed (pre-flight + per-call grant
check, in-band `-1` on quota exhaustion vs. hard-throw on a grant
violation) `HostFunction`, verified against real Wasm binaries via
`wasm-tools`-assembled WAT fixtures, not mocked.**
