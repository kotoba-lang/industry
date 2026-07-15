# ADR-2607072400: aiueos ⊣ kototama — the decision/execution boundary, stated once

## Status

Accepted

## Context

The aiueos/kototama relationship has been decided piecemeal across five
ADRs and seven addenda (ADR-2607022200, ADR-2607022700, ADR-2607022900,
ADR-2607062330 and its addenda 1/6/7, ADR-2607062400) — and twice along
the way, someone (this assistant, on both occasions) asserted a specific
aiueos execution implementation existed without having actually opened
the repo to check: ADR-2607022900's "already landed in aiueos-cljc-
contract PRs #2–#12" and ADR-2607062330's original addendum's "aiueos.
execute/aiueos.launcher already implements a similar-looking Chicory-
hosted execution path" — both verified false later in the same thread of
ADRs that made the claims. The actual, current architecture is sound and
has been for a while; what's missing is a single place that states it
without also carrying the history of getting it wrong twice. This ADR is
that statement — not a new decision, a *consolidation* of decisions
already made, now that ADR-2607062330 addendum 7 (kototama#24,
`kototama.aiueos-adapter`) closed the last open piece.

## Decision

**Two layers, one boundary, never crossed in either direction.**

### Layer 1 — `aiueos`: capability decision authority

Owns: manifest validation, the capability graph (who exports what),
policy reasoning (`aiueos.policy/verify-component`), the grant/deny
decision itself (`aiueos.broker/verify-one`/`verify-system`), audit
trail, signing/trust elevation. Every decision is a policy-decision map
(`:aiueos/decision :grant` or `:deny` — ADR-2607022200's "never a silent
pass"), always audited, whether granted or denied.

`aiueos` **owns zero execution, permanently, by design — not a gap.**
`aiueos.broker`'s own docstring is explicit: the retired Rust broker's
`launch`/`boot`/`materialize_and_run` (which really did run wasmtime,
verified by real audit logs of real runs it once produced) was "always
`:provider/execute` ... a native/host adapter concern (ADR-2607022200
Layer 3), never CLJC authority. `.kotoba` compiles TO Wasm; it cannot
itself host other Wasm components." Nothing in `aiueos` should ever gain
a Chicory, wasmtime, or any other Wasm-hosting dependency. If a future
change proposes one, that proposal contradicts this ADR and needs a new
one to override it explicitly — it should not happen by accretion.

Two entry points to the SAME decision, for two different caller shapes:
- `aiueos.decide` / `nbb decide` — a subprocess a non-JVM host (Rust,
  Node, ...) shells out to, newline-delimited EDN over stdio
  (ADR-2607022700's "decision subprocess" design).
- `aiueos.cli/command-result` — the same decision, called in-process,
  for a host that's already JVM/Clojure (what `aiueos.decide/
  handle-request` itself calls one layer down).

### Layer 3 — `kototama`: the Wasm execution runtime ("tender")

Owns: hosting and running a granted Wasm guest. `kototama.tender`
(JVM/Chicory, ADR-2607022900) and the browser-native substrate
(`actor-host.js` in `wasm-webcomponent`, ADR-2607061630/2607062400) both
implement `kototama.contract`'s `actor:host` ABI — the same fail-closed
pre-flight + per-call grant check design, whichever substrate hosts a
given guest.

`kototama` **never decides a grant itself, ever** (ADR-2607022700's
rule, unchanged since the day it was written). Every `HostCaps` value
`kototama.tender/instantiate` enforces was either handed to it directly
(a caller/test that already knows what it wants to grant) or derived
from a real decision via the boundary below — there is no third path,
and no code inside `kototama.tender` or `kototama.contract` reasons
about policy, trust, or manifests.

### The boundary — `kototama.aiueos-adapter`

The **only** sanctioned integration point (kototama#24,
ADR-2607062330 addendum 7). Calls `aiueos.cli/command-result` (Layer 1,
real decision) and translates the answer into a `kototama.contract/
host-caps` value (Layer 3's input shape) — nothing more. It does not
re-derive, cache, or second-guess the decision; a `:deny` becomes
`:grants #{}`, a `:grant` becomes `:grants` exactly what was asked for.
No other file in either repo should call across this boundary. Today it
covers `log-write`/`clock-monotonic`/`random-bytes` — the `actor:host`
imports aiueos's own default kernel capabilities recognize;
`gen-keypair`/`sign`/`verify`/`sha256-hex`/`http-post`/`log-read` have
no aiueos-kernel-capability counterpart and stay caller-supplied
`HostCaps`, on purpose (aiueos's kernel-capability vocabulary was never
meant to cover kototama's own cross-substrate crypto/identity surface —
extending it to would be aiueos absorbing a `kototama.contract` concern
it doesn't own, the same kind of boundary violation this ADR exists to
prevent in the other direction).

## Consequences

- (+) One place to read the whole relationship, instead of piecing it
  together across 5 ADRs and 7 addenda.
- (+) The failure mode that produced two false "aiueos already executes"
  claims is closed off structurally, not just corrected after the fact:
  this ADR states plainly that aiueos owning execution would be a
  *change*, not a *discovery*, so a future claim that it already does
  should be treated as suspicious by construction.
- (+) `kotoba-lang → kototama → aiueos` (ADR-2607022400's original
  stack) is now fully wired end to end: `kotoba wasm emit` compiles,
  `kototama.tender`/`actor-host.js` execute, `aiueos` decides, the
  adapter connects decide to execute. No stage of that stack is still a
  stub or a test-only fixture.
- (−) The device-access quartet (`pci-config`/`dma-map`/`irq-subscribe`/
  `mmio-map`) — aiueos's own Layer 2/3 hardware surface — remains
  entirely unimplemented on both sides (pure data in `aiueos.surface`,
  a permanent stub in both `kototama.tender` and `kotoba.wasm-exec`).
  This ADR doesn't change that; a privileged/hypervisor host is still a
  separate, unstarted effort, correctly out of scope for a JVM process
  either way.
- (−) `kototama.aiueos-adapter`'s coverage (3 of 8 `actor:host` imports)
  is intentionally partial, not a TODO — widening it means widening
  aiueos's own kernel-capability vocabulary first (a Layer 1 decision,
  not something the adapter should paper over).

## Follow-up

- None load-bearing. `pci-config`/`dma-map`/`irq-subscribe`/`mmio-map`
  remain tracked as a separate, unstarted privileged-host effort
  (ADR-2607030900), not owned by either `aiueos` or `kototama` today.

## One-line summary

**aiueos decides (Layer 1, capability/policy/audit authority, zero
execution, permanently by design) — kototama executes (Layer 3, the
Wasm tender, zero decision-making, permanently by design) —
`kototama.aiueos-adapter` is the one boundary between them. Stated once,
consolidating five ADRs and seven addenda, after two false "aiueos
already executes" claims were made and corrected across that same
history — the architecture was never actually broken, only scattered.**
