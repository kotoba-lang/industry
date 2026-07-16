---
id: adr-2607162020-kotoba-lang-turn-allocation-state-machine
title: "ADR-2607162020: kotoba-lang/org-ietf-turn gains a pure TURN allocation/permission/channel-binding state machine + ChannelData framing (RFC 8656 §5-§12.4, Phase 3a — still zero socket I/O)"
status: accepted
doc_type: adr
topic: telecom-independent-substrate-dtn-rcs-mesh-satellite
authoritative: true
last_verified: 2026-07-16
authoritative_for:
  - kotoba-lang/org-ietf-turn の allocation/permission/channel-binding state machine（RFC 8656 §5-§9）の設計根拠
  - kotoba.turn.channeldata（§12.4）・kotoba.turn.demux の設計根拠
related:
  - 90-docs/adr/2607161956-kotoba-lang-bytes-shared-primitives-extraction.md
  - orgs/kotoba-lang/org-ietf-turn
  - orgs/kotoba-lang/bytes
supersedes: []
superseded_by: []
---

# ADR-2607162020: kotoba-lang/org-ietf-turn gains a pure TURN allocation/permission/channel-binding state machine + ChannelData framing (RFC 8656 §5-§12.4, Phase 3a — still zero socket I/O)

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki

## Problem

`kotoba-lang/org-ietf-turn`'s own founding design doc
(`docs/ADR-kotoba-turn-relay.md`) named the message-layer codec (STUN
header/attributes, ephemeral-credential mint/verify — already
implemented) as usable "today ... as a credential-minting service and a
STUN message codec," while explicitly listing as deferred follow-up work:
the "allocation / permission / channel-binding state machine (RFC 8656
§5–§11)," "ChannelData framing (RFC 8656 §12.4)," and "the STUN/ChannelData
demux." This ADR is Phase 3a of the net/turn shared-lib consolidation
(following ADR-2607161956's Phase 1 `kotoba-lang/bytes` extraction and the
parallel Phase 2 `kotoba-lang/wire` work): the state-machine half of what a
real TURN relay needs, still deliberately with zero socket I/O — matching
this repo's own established "message/state layer first, listener I/O is a
separate later phase" discipline, the same discipline `kotoba-lang/dtn`
followed (bundle model before transport, ADR-2607161743 before
ADR-2607161817).

## Decision

### Decision 1: `kotoba.turn.allocation` — deterministic, `now`-injected, matching the deleted Rust reference's design

Per the founding ADR's own note that the original Rust `allocation.rs` was
"deterministic + `now`-injected," `allocate`/`expired?`/`refresh`/
`create-permission`/`permission-active?`/`channel-bind`/
`channel-for-peer`/`peer-for-channel` all take an explicit `now` timestamp
from the caller — no function in this namespace reads a wall clock itself.
Permissions carry an independently-expiring lifetime (RFC 8656 §9 default
300s) from the allocation's own lifetime (§5 default 600s) — verified with
a permission expiring at 10,000ms while its allocation remains valid to
6,000,000ms. Channel bindings are restricted to the RFC-mandated
`0x4000`-`0x7FFF` range, rejecting out-of-range numbers rather than
silently accepting them. `refresh` with `lifetime-s 0` (RFC 8656 §7.3's
deletion signal) returns `nil` rather than encoding "deleted" as a data
value — the caller removes the allocation from wherever it's stored, kept
the same way `kotoba.dtn.router/route-decision` returns plain outcome
values for its caller to act on rather than performing side effects
itself.

### Decision 2: `channel-for-peer`/`peer-for-channel` gained a required `now` parameter — a deviation from the original task spec, and the right one

The task that produced this code specified these two lookups without a
`now` argument; the implementing agent added one anyway, because the same
task also required "check expiry in the lookup, not just presence" — which
is impossible without a timestamp, and every other expiry-aware function
in the same namespace already takes `now`. This ADR ratifies that
deviation: an inconsistent API (some expiry-checking functions
`now`-injected, two silently not) would have been a worse outcome than a
literal-spec mismatch caught and fixed during implementation.

### Decision 3: `kotoba.turn.channeldata` (§12.4) and `kotoba.turn.demux` reuse `kotoba.bytes`, not new byte-codec logic

ChannelData's 4-byte header (2-byte channel number + 2-byte length) is
encoded/decoded via `kotoba.bytes/u16->bytes`/`bytes->u16` — exactly the
dependency-efficiency outcome ADR-2607161956's Phase 1 extraction was
performed to enable, now demonstrated by a second consumer. `decode`
returns `nil` on malformed/too-short input (a 3-byte header, a payload
truncated mid-declaration) rather than throwing, matching `stun.cljc`'s
own established malformed-input convention. `demux/classify-datagram`
implements the real RFC 8656 §12.4 leading-bits classification (STUN
messages' top two bits are always `00`; ChannelData channel numbers occupy
the `01` range) rather than a heuristic like "try STUN parsing, catch and
fall back" — verified against real bytes built via this repo's own
`stun/encode-header` and the new `channeldata/encode`, not hand-crafted
magic-number literals, so a change to either codec's actual wire shape
would surface here as a test failure rather than silently drifting from
the classification logic's assumptions.

### Decision 4: explicitly still no socket listener, no request-validation wiring — named, not silently absent

This ADR provides the *state model* an allocation/permission/channel-bind
request handler would consult and mutate; it does not itself parse an
incoming Allocate/Refresh/CreatePermission/ChannelBind STUN request into a
call against this state machine, and it does not open a UDP socket. Those
remain a later, separate phase — consistent with how `kotoba-lang/dtn`'s
own bundle model (ADR-2607161743) preceded its transport (ADR-2607161817)
by a full ADR cycle, not bundled into one change.

## Verification

- `clojure -M:test` → 41 tests / 94 assertions (was 22/43), 0 failures, 0 errors. Concrete per-namespace evidence (not just pass/fail): `channel-bind` rejects `0x3FFF` and `0x8000`, accepts `0x4000` and `0x7FFF`; `channeldata` round-trips a 137-byte (non-4-byte-aligned) payload exactly despite wire-level padding, and returns `nil` (not a throw) on a 3-byte header or a payload truncated to 6 of 9 declared bytes; `demux/classify-datagram` correctly classifies a real STUN message, a real ChannelData frame, and returns `:unknown` for empty/garbage/too-short input. Independently re-run from a cold clone by the orchestrating session — identical results.
- `clojure -M:lint` → 0 errors, 0 warnings, both before and after (independently re-run).
- Pushed to `github.com/kotoba-lang/org-ietf-turn` (public), commit `d8541b4059018768280da64660519a5d8a65a959`.
- `manifest/west.yml`: pin advanced via `--entry org-ietf-turn` (1-line diff, `ce6cee2876f4→d8541b405901`, `verify-west-pins: 1 件の pin 変更をすべて検証 OK`), landed via an isolated sibling-path worktree + GitHub API server-side merge, matching every prior ADR in this series.

## Consequences

- `kotoba-lang/org-ietf-turn` now has a real, tested RFC 8656 relay state
  model on top of its pre-existing message codec — the two halves needed
  before a listener phase can do anything are both real now, not just the
  message layer.
- The dependency-efficiency goal behind Phase 1's `kotoba-lang/bytes`
  extraction is now demonstrated by a second, independent consumer
  (`channeldata.cljc`) beyond the original `stun.cljc`/`credential.cljc` —
  not a one-off refactor benefit.
- Still explicitly undone: the actual UDP/TCP listener, wiring STUN
  Allocate/Refresh/CreatePermission/ChannelBind request parsing to this
  state machine's mutation functions, long-term credential mechanism,
  IPv6 addressing, DoS/quota limits — all pre-existing named gaps from the
  founding ADR, none newly introduced or newly claimed-closed here.
- This ADR ran fully independently of the concurrent Phase 2
  (`kotoba-lang/wire` + `kotoba-lang/dtn` transport refactor) work — no
  file, repo, or manifest-entry overlap — demonstrating the phased plan's
  intended parallelism.

## Alternatives considered

- **Deferring `channel-for-peer`/`peer-for-channel`'s `now` parameter to a
  later ADR to match the original task literally.** Rejected: shipping a
  known-inconsistent API (most functions `now`-injected, two silently
  reading nothing and therefore silently wrong about expiry) for the sake
  of spec-literalism would be worse than documenting a corrected,
  consistent one — see Decision 2.
- **A heuristic STUN-vs-ChannelData classifier ("try STUN parse, catch
  exception, assume ChannelData") instead of the real bit-level check.**
  Rejected: RFC 8656 §12.4 specifies an exact, cheap, unambiguous
  classification; a try/catch heuristic would be slower, less clearly
  correct, and harder to verify against the spec directly — see
  Decision 3.
- **Encoding "allocation deleted" as an explicit `{:deleted true}` value
  instead of `nil`.** Rejected: `nil` composes more simply with how a
  caller would already be checking a lookup/atom-swap result, and matches
  the "absence, not a sentinel" convention already used elsewhere in this
  ADR series (e.g. `kotoba.dtn.gateway/bundle->sms` returning `nil` rather
  than an error-sentinel on shape mismatch).

## References

- `90-docs/adr/2607161956-kotoba-lang-bytes-shared-primitives-extraction.md`
- `orgs/kotoba-lang/org-ietf-turn/README.md` — https://github.com/kotoba-lang/org-ietf-turn
- `orgs/kotoba-lang/org-ietf-turn/docs/ADR-kotoba-turn-relay.md`
- `orgs/kotoba-lang/org-ietf-turn/src/kotoba/turn/allocation.cljc`
- `orgs/kotoba-lang/org-ietf-turn/src/kotoba/turn/channeldata.cljc`
- `orgs/kotoba-lang/org-ietf-turn/src/kotoba/turn/demux.cljc`
