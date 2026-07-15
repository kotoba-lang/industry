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

## Addendum (2026-07-06, same day): `aiueos.execute` vs `kototama.tender` — deliberately NOT consolidated

**Correction (2026-07-07, see addendum 6): the premise below is false.**
`aiueos.execute`/`aiueos.launcher` do not exist anywhere in this org.
Read on for what this addendum originally claimed, kept verbatim for the
record, then see addendum 6 for what investigating it for real found.

Investigating the "aiueos calls kototama.tender" follow-up above surfaced
that `aiueos.execute`/`aiueos.launcher` (in `aiueos-cljc-contract`'s real
`main`, not the stale local worktree that was checked out) already
implements a similar-looking Chicory-hosted, capability-gated execution
path — independently built, more mature in places (a STABLE
`withMemoryLimits`-based memory cap predates this ADR's own later
memory-pages addition; topic pub/sub gating via `aiueos.topic`). Its own
merge PR flagged this as an unresolved "naming/purpose collision."

**Decision: the two stay separate, on purpose, not consolidated.**
- `kototama.tender` enforces `kototama.contract`'s `actor:host` ABI — a
  vocabulary shared with the BROWSER-native substrate
  (`actor-host.js` in `wasm-webcomponent`, ADR-2607062400). This is the
  cross-substrate contract kototama itself owns.
- `aiueos.execute` enforces `aiueos`'s own manifest/policy/broker
  vocabulary (`:aiueos/quota`, `:aiueos/limits`, `:aiueos/publishes`/
  `:subscribes`) — JVM-only, no browser counterpart, and never intended
  to have one (topic pub/sub and the device-access quartet are OS-broker
  concerns, not something a Wasm guest's browser-side ABI should need).

They are two different capability vocabularies solving two different
problems that both happen to use Chicory to run Wasm — not one thing
duplicated. Forcing them into one would mean either dragging aiueos's
OS-broker vocabulary (topics, device access) into kototama's cross-
substrate ABI, which the browser substrate has no way to honor, or
stripping `kototama.contract`'s browser-shared ABI down to only what
aiueos's manifest vocabulary already expresses — both are worse than
documenting the boundary and moving on. `aiueos`'s own broker calling
into `kototama.tender/instantiate` (the follow-up bullet above) remains
open, but as a policy-adapter integration (aiueos's decision translated
into a `HostCaps` value), not a code-level merge of the two execution
namespaces.

## Addendum 4 (2026-07-06, same day): `:now`/`:log-append!` renamed to `:clock-monotonic`/`:log-write`

Preparing addendum 3's follow-up (extending `kotoba-core-contracts`'
closed host-import table for a real `.kotoba`-source E2E test) surfaced
that a concurrent session had already independently registered
`clock-monotonic`/`log-write` in that same shared table, for `aiueos`'s
kernel-capability vocabulary, with wire signatures identical to
`kototama.contract`'s own `:now`/`:log-append!`. Decision: rename
kototama's side to reuse the existing names rather than register the same
operation twice under different names. Landed across `kototama.contract`/
`kototama.tender`/both test files (kototama#21), the browser-native
`actor-host.js` counterpart (`wasm-webcomponent`#3), and kototama's
`web/` demo + pin bump (kototama#22). Full detail in
ADR-2607062400's addendum.

## Addendum 5 (2026-07-06, same day): addendum 3's E2E gap closed — a real `.kotoba`-compiled guest now runs through `kototama.tender`

Addendum 3 found `kotoba wasm emit` could not call any of
`kototama.contract`'s `actor:host` imports — the compiler resolves
host-import calls against `kotoba-core-contracts`' closed
`capability_contract.edn` table, and (before addendum 4's rename) none
of the 8 imports had an entry there at all. This addendum closes that
gap end to end, across three repos:

1. **`kotoba-core-contracts`** (kotoba-core-contracts#3): registered the
   6 imports net-new after addendum 4's rename (`log-write`/
   `clock-monotonic` already existed) — `gen-keypair`/`sign`/`verify`/
   `sha256-hex`/`http-post`/`log-read`, capability ids 219–224
   (`identity/keypair`, `identity/sign`, `identity/verify`,
   `hash/sha256`, `http/post`, `log/read`), field names/param/result
   shapes copied 1:1 from `kototama.tender`'s actual `HostFunction`
   wiring so nothing would need reconciling later.
2. **`kotoba`** (kotoba#288): bumped its `kotoba-core-contracts` pin,
   added one minimal `.kotoba` demo + granting policy per new import
   (`test/kotoba/actor_host_test.clj`, mirroring
   `kotoba.aiueos-kernel-caps-test`'s exact shape — denies without a
   policy, compiles to a real Wasm binary with one). Confirmed by
   actually attempting the compile (not just reading the source) that
   `kotoba.runtime`'s `op->kind`/`cap-passing-imports` need NO changes
   for these ops: they don't participate in the `cap-acquire`/`<op>-with`
   capability-passing extension (an aiueos-specific feature) — a plain
   `:capability`-gated `host-imports` entry is sufficient, same as
   `kgraph-assert!`/`clipboard-write`.
3. **`kototama`** (kototama#23): checked in the actual `.wasm` bytes
   `kotoba wasm emit` produced (not a hand-written WAT string) as test
   fixtures, alongside their `.kotoba` source for provenance, and ran
   them through `kototama.tender/instantiate`/`run-main`. The
   `sha256-hex` fixture computes and returns `sha256("")` correctly
   through the real host function; the `gen-keypair` fixture writes a
   real 32-byte seed + 32-byte derived pubkey. This is the actual
   end-to-end proof this ADR's original text could only gesture at —
   a genuinely independent compiler's output linking against a
   genuinely independent execution runtime, with no shape mismatch,
   checked into CI so it can't silently regress.

## Addendum 6 (2026-07-07): the "keep separate" decision's premise was false — `aiueos.execute`/`aiueos.launcher` don't exist

The owner asked to revisit the "keep separate" decision above. Investigating
it for real (reading the actual current `orgs/kotoba-lang/aiueos-cljc-contract`
checkout, not trusting the prior addendum's claim) found there is nothing to
revisit: **`aiueos.execute` and `aiueos.launcher` do not exist anywhere in
this org.**

- `aiueos-cljc-contract`'s `src/aiueos/` contains only `audit.cljc`,
  `broker.cljc`, `cli.cljc`, `contract.cljc`, `graph.cljc`, `manifest.cljc`,
  `policy.cljc`, `signing.cljc`, `surface.cljc`, `topic.cljc` — pure `.cljc`
  data/validation (manifest normalization, policy contract, signing,
  topic-id derivation). No `execute.cljc`, no `launcher.cljc`, no
  `com.dylibso.chicory` dependency in `deps.edn`, zero hits for
  `Chicory`/`HostFunction`/`withMemoryLimits`/`Instance.Builder` anywhere in
  the checkout. This repo has zero commits since 2026-07-02 — nothing
  changed there even once since before the addendum above was written.
- The actual Chicory-based execution code the prior addendum was comparing
  against is `kotoba.launcher`/`kotoba.wasm-exec` in `kotoba-lang/kotoba`'s
  `src/kotoba/{launcher,wasm_exec}.clj` — a different repo, with its own
  vocabulary (`kotoba.runtime/capability-contract`, `:kotoba.policy/
  capabilities`), that only *references* `aiueos.policy/default-kernel-caps`
  by name in a comment (registering host-import primitives *for* aiueos's
  kernel-capability set, per ADR-2607022700) — not aiueos-owned execution
  code being run.
- `aiueos.contract.cljc`'s actual keys (`:aiueos/limits`, `:aiueos/quota`,
  `:aiueos/publishes`, `:aiueos/subscribes`, `:aiueos/topics`, `:aiueos/
  imports`, `:aiueos/exports`, `:aiueos/effects`, `:aiueos/device`,
  `:aiueos/capability`) and the device-access quartet in `surface.cljc`
  (`pci-config`/`dma-map`/`irq-subscribe`/`mmio-map`, string provider names)
  are pure data — never wired to a `HostFunction`, a `Chicory` `Instance`,
  or anything else that executes. There is no wire-shape overlap with
  `kototama.tender`'s 8 imports to reconcile, because there is no second
  execution path to compare against.

**This is the same pattern ADR-2607022900 already caught once** (that ADR's
own "already landed in aiueos-cljc-contract PRs #2–#12" claim, verified
false by ADR-2607062330's original text above). The prior addendum
repeated the mistake — asserting a specific, detailed-sounding
implementation existed ("more mature in places... a STABLE
`withMemoryLimits`-based memory cap... its own merge PR flagged this...")
without having actually opened the repo to check. Recorded here rather
than quietly re-editing the addendum above, so the failure mode (fabricated
implementation-status claims about aiueos specifically, twice now) is on
the record and not silently overwritten.

**Consequence for the open follow-up** ("aiueos's own broker calling into
`kototama.tender/instantiate`, translating aiueos's decision into a
`HostCaps` value"): still open, unaffected in substance — building that
adapter was never blocked by whether aiueos already had its own execution
path, only by nobody having written the adapter. Not attempted in this
addendum; tracked as before.

## Addendum 7 (2026-07-07): the open follow-up, closed — `kototama.aiueos-adapter`

New namespace `kototama.aiueos-adapter` (`kototama` repo, kototama#24)
translates a REAL aiueos decision into a `kototama.contract/host-caps`
value. `aiueos.decide`'s documented "V1 integration" is a `nbb decide`
subprocess a native host shells out to (for hosts that aren't already
JVM/Clojure); since kototama already is, the adapter instead depends on
`io.github.kotoba-lang/aiueos` directly and calls `aiueos.cli/
command-result` in-process — the exact pure function `aiueos.decide/
handle-request` itself calls one layer down, skipping only the EDN-line
marshaling a subprocess boundary needs and an in-process call doesn't.
`kototama.tender` still never computes a grant itself (ADR-2607022700's
rule, unchanged) — the adapter only removes the need for every caller
to hand-build `HostCaps` from a decision aiueos already made.

Covers the subset of `actor:host` imports aiueos's own default kernel
capabilities recognize today (`log-write`/`clock-monotonic`/
`random-bytes`); `gen-keypair`/`sign`/`verify`/`sha256-hex`/`http-post`/
`log-read` have no aiueos-kernel-capability counterpart and still take
caller-supplied `HostCaps`, unchanged.

Verified end to end, not just internal translation consistency: a real
`:grant` from `aiueos.cli/command-result` (aiueos's own broker, its own
audit trail) drives a real Chicory-hosted WASM guest through
`kototama.tender` to a real result; a real `:deny` (an unsigned
component under an `:aiueos/require-signed` policy overlay — aiueos's
own signature-authenticity check, ADR-0003) is what `kototama.tender`'s
own pre-flight rejects. No test-hardcoded `HostCaps` anywhere in either
path. 25 tests/50 assertions green.

## Addendum 8 (2026-07-08, documenting 2026-07-07 work): a 9th import — `llm-infer` — landed, full detail in ADR-2607072530

`kototama.contract`'s import surface grew a 9th member: `llm-infer`
(`llm_infer`, capability id 225 in `kotoba-core-contracts`'
`capability_contract.edn`). `(prompt-ptr prompt-len out-ptr out-cap) ->
bytes-written|-1`, gated `#{:network}` like `http-post`, metered against
a new `:max-llm-infers` limit (default 0, same "fully denied unless a
caller's HostCaps explicitly raises the limit" convention every other
metered import here uses). Unlike every other host-fn in this
namespace, it's built with an injectable `llm-client`
(`{:infer-fn (fn [prompt] text-or-nil)}`) rather than calling
`HttpClient` inline directly — `default-llm-client` resolves an API key
from the same env-var chain `cloud_itonami.runtime/model-config` uses
and calls the real Anthropic Messages API in production; tests inject a
fake `:infer-fn`, never touching the real network. A missing API key,
non-2xx response, network error, or malformed reply are all
indistinguishable in-band `-1` (fail-closed, same as every other quota/
overflow case here) — a well-behaved guest can't tell WHY it was
denied, which is the point (it never gets to probe the host's
credential state).

This landed as part of a larger, business-context-driven effort
(cloud-itonami's `isic-6511` underwriting governor compiled to a real
`.kotoba` decision module and deployed — without a JVM — to a real
`murakumo` fleet node) documented in full in its own ADR rather than
duplicated here: see **ADR-2607072530** for the complete story,
including the parallel browser/Node `wasm-webcomponent` `actor-host.js`
implementation, the real fleet deployment, and the `.kotoba` compiler
bug it found (`and`/`or`/`when` pass the safety gate but fail WASM code
generation with `unsupported-op` — worked around with nested `if`,
compiler fix tracked as follow-up there, not here).

## One-line summary

**`kototama.tender` is the Chicory/JVM execution layer ADR-2607022400/
2607022900 specified but never shipped — every `kototama.contract`
import is wired to a real, fail-closed (pre-flight + per-call grant
check, in-band `-1` on quota exhaustion vs. hard-throw on a grant
violation) `HostFunction`, verified against real Wasm binaries via
`wasm-tools`-assembled WAT fixtures, not mocked.**
