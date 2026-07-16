---
id: adr-2607162217-kotoba-lang-io-libp2p-gossip-from-self-fix
title: "ADR-2607162217: kotoba-lang/io-libp2p fixes kotoba.net.gossip's #{from self} duplicate-key crash at the source, removing the transport-layer workaround it required"
status: accepted
doc_type: adr
topic: telecom-independent-substrate-dtn-rcs-mesh-satellite
authoritative: true
last_verified: 2026-07-16
authoritative_for:
  - kotoba-lang/io-libp2p の kotoba.net.gossip #{from self} バグ修正の設計根拠
related:
  - 90-docs/adr/2607162135-kotoba-lang-io-libp2p-real-tcp-transport.md
  - 90-docs/adr/2607162202-kotoba-lang-dtn-gossip-peer-discovery.md
  - orgs/kotoba-lang/io-libp2p
supersedes: []
superseded_by: []
---

# ADR-2607162217: kotoba-lang/io-libp2p fixes kotoba.net.gossip's #{from self} duplicate-key crash at the source, removing the transport-layer workaround it required

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki

## Problem

ADR-2607162135 (Phase 4) found, while building `kotoba-lang/io-libp2p`'s
real TCP gossip transport, that `kotoba.net.gossip/route-message` builds
its fanout-exclude set via literal syntax `#{from self}` — which throws
`Duplicate key` whenever `:from` equals `:self`, exactly the case a
locally-originated `publish!` naturally produces. That ADR's own scope
boundary explicitly forbade modifying `gossip.cljc` while building its
first real consumer, so the fix was a transport-layer workaround
(`safe-from`, substituting `nil` for `:from` before calling
`route-message`) rather than a source fix, and was recorded as a named,
disclosed gap rather than silently absorbed. This ADR is that follow-up:
fix the bug where it actually lives.

## Decision

### Decision 1: `(hash-set from self)` replaces `#{from self}` — one occurrence, precisely located

The crash-prone construction existed in exactly one place:
`src/kotoba/net/gossip.cljc:146`, inside `route-message`'s call to
`gossip-fanout`. `gossip-fanout` itself was already safe — its own
`exclude` parameter is passed through `(set exclude)`, which (unlike the
`#{...}` reader-macro literal form) never throws on runtime-equal
elements. Only the CALL SITE constructing the argument via `#{from self}`
was the actual problem. `(hash-set from self)` — the function form,
which silently dedupes rather than throwing — replaces it with identical
logical semantics (the exclude set still contains exactly `from` and
`self`, deduped when they're equal) and zero behavior change for the
non-equal case.

### Decision 2: verified with a real reproduction, before and after — not just "tests pass"

The bug was reproduced directly (not merely inferred from the prior ADR's
description) both under `nbb` and under plain JVM `clojure`, confirming
it's a genuine cross-platform Clojure/ClojureScript runtime behavior, not
an nbb-specific quirk: `(let [from "a" self "a"] #{from self})` throws
`Duplicate key: a` on both. After the fix, the same call shape via
`kotoba.net.gossip/route-message` with `:from` = `:self` returns cleanly.
This before/after reproduction was independently re-run by the
orchestrating session against the actual pushed commit, not merely
trusted from the implementing agent's report — same "trust but verify"
discipline every prior ADR in this series has applied.

### Decision 3: `safe-from` removed from `kotoba.net.transport.tcp`, with an explicit check that the transmitted wire envelope never depended on it

Before removing the workaround, the implementing agent verified precisely
what `safe-from`'s `nil`-substitution was scoped to: it only affected the
LOCAL fanout-decision call into `route-message`, never the envelope
actually transmitted over the wire (`deliver-gossip!` constructs the
outbound envelope via `envelope/gossip-envelope node-id topic payload`,
using `node-id` directly, independent of whatever `route-message` was
called with). This confirms removing `safe-from` cannot regress what
peers actually receive on the wire — only removes now-unnecessary
crash-avoidance around the local routing decision. `handle-gossip!` and
`publish!` now pass real `:from`/`:self` values directly.

### Decision 4: real regression coverage across the dependency boundary, not just within `io-libp2p` itself

Because `kotoba-lang/dtn`'s `kotoba.dtn.discovery` (ADR-2607162202)
depends on `io-libp2p` and specifically exercises the self-originated
`publish!` path this bug/fix concerns, this ADR's verification includes
re-running `dtn`'s discovery demo — an entirely separate repo — against
the fixed `io-libp2p`, not only `io-libp2p`'s own test suite and demo.
This is the strongest available proof that the fix is genuinely backward
compatible for a real downstream consumer, not merely internally
consistent.

## Verification

- Direct reproduction, independently re-run by the orchestrating session against the pushed commit: `(g/route-message (-> (g/empty-peer-state) (g/add-peer "p1" #{"t"})) (g/empty-seen-cache 10) {:topic "t" :payload "x" :from "p1" :self "p1"})` → returns `{:seen-cache {...} :forward []}` cleanly, no exception (was `Duplicate key: p1` before this ADR).
- `clojure -M:test` → 12 tests / 56 assertions (was 53; +3 new, covering `:from = :self` including the case where that shared id isn't itself a subscribed peer), 0 failures, 0 errors. Independently re-run from a cold clone.
- `clojure -M:lint` → 0 errors, exactly 1 warning (the same pre-existing, unrelated unused `clojure.string` require in `gossip.cljc` already disclosed in ADR-2607162135 — confirmed unchanged, no new warnings introduced). Independently re-run.
- `io-libp2p`'s own E2E demo (`test/kotoba/net/transport/tcp_demo.cljs`, unmodified) → `RESULT: 3/3 scenarios passed`, exit 0, run 4 times by the implementing agent with no flakiness. Independently re-run once more by the orchestrating session — identical result.
- **Cross-repo regression check**: `kotoba-lang/dtn`'s discovery demo (`test/kotoba/dtn/discovery_demo.cljs`, unmodified, depends on `io-libp2p` per ADR-2607162202) → `RESULT: 3/3 scenarios passed`, exit 0, independently run by the orchestrating session against the fixed `io-libp2p` — confirms the fix is genuinely non-breaking for a real downstream consumer, not just self-consistent within `io-libp2p`.
- Pushed to `github.com/kotoba-lang/io-libp2p` (public), commit `86475deaa761f91435262b7d60861d07c68abdb8`.
- `manifest/west.yml`: pin advanced via `--entry io-libp2p` (1-line diff, `b87f2605737d→86475deaa761`, `verify-west-pins: 1 件の pin 変更をすべて検証 OK`), landed via an isolated sibling-path worktree + GitHub API server-side merge, matching every prior ADR in this series.

## Consequences

- The last remaining "found but not fixed, only worked around" item this
  ADR series had accumulated (named in ADR-2607162135's Decision 2 and
  reiterated in ADR-2607162202's Consequences and Alternatives) is now
  closed at the source. Every real bug this series discovered while
  building real I/O across `dtn`/`org-ietf-turn`/`io-libp2p` has now
  either been fixed where found or explicitly, permanently documented as
  an intentional scope boundary (not a forgotten TODO).
- Any FUTURE consumer of `kotoba-lang/io-libp2p`'s gossip layer that
  publishes self-originated messages (a natural, common pattern — any node
  announcing its own presence or state, as `kotoba.dtn.discovery` already
  does) no longer needs to independently rediscover and work around this
  crash; the fix benefits every consumer, not just the two that happened
  to hit it during this series.
- `kotoba.net.transport.tcp` is simpler (one fewer workaround function to
  maintain and explain) with identical observable behavior — a pure
  simplification enabled by fixing the actual root cause.
- The one remaining disclosed limitation from ADR-2607162135
  (`goog.crypt.Sha256` needing an nbb-specific shim, since nbb doesn't
  bundle Google Closure Library) is unaffected by this ADR and remains a
  real, intentional environment-adaptation, not a bug.

## Alternatives considered

- **Leaving the `safe-from` workaround in place indefinitely**, treating
  the disclosed gap as acceptable permanent scope. Rejected: the fix
  location was precisely identified, small, and low-risk (one call site,
  verified semantically identical before/after) — the cost of fixing it
  properly was low relative to the ongoing cost of every future consumer
  needing to know about and work around a crash in a shared library.
- **A broader defensive rewrite of `gossip.cljc`'s exclude-set handling**
  (e.g. changing `gossip-fanout`'s own signature or validation).
  Rejected: `gossip-fanout` was already safe; only its caller's argument
  construction needed fixing. Touching more than the actual bug site would
  have widened this ADR's verification surface for no benefit.
- **Verifying only within `io-libp2p` itself**, treating `dtn`'s discovery
  demo re-run as optional/redundant since `io-libp2p`'s own test suite
  passed. Rejected: this series' own established discipline (ADR-2607162135
  Decision 4, "the strongest available proof ... a real downstream
  consumer, not just internally consistent") explicitly favors
  cross-repo regression checks when a real one is available and cheap to
  run, which it was here.

## References

- `90-docs/adr/2607162135-kotoba-lang-io-libp2p-real-tcp-transport.md` (where the bug was first found and worked around)
- `90-docs/adr/2607162202-kotoba-lang-dtn-gossip-peer-discovery.md` (the downstream consumer used for cross-repo regression verification)
- `orgs/kotoba-lang/io-libp2p/README.md` — https://github.com/kotoba-lang/io-libp2p
- `orgs/kotoba-lang/io-libp2p/src/kotoba/net/gossip.cljc`
