# ADR-2641000000: cloud-itonami-isic-6010 (radio broadcasting) filled in and promoted to `:implemented`

## Status

Accepted

## Related

- ADR-2620005813 (`cloud-itonami-isic-5813`, pressops — newspaper/
  journal/periodical publishing; the nearest sibling in the media/
  publishing family, mirrored closely for module shape)
- `cloud-itonami-isic-6020`'s own registry promotion (television
  programming and broadcasting activities — the closest structural
  sibling, sharing this entry's identical `:required-technologies`/
  `:operating-states` template and freshly promoted to `:implemented`
  in this same fast-moving window)
- ADR-2607152500 (Wave 4 person-facing-service safety guardrail — the
  governing rule that editorial/on-air-content and emergency-alert
  finalization can never be an auto-commit-eligible op for any actor
  in this fleet)
- `cloud-itonami-isic-6511`'s own `docs/adr/0001-architecture.md`
  (origin of the general governed-actor architecture pattern)

## Context

`cloud-itonami-isic-6010` ("Radio broadcasting") was, unlike a fresh
scaffold, a PRE-EXISTING repository: `gh api repos/cloud-itonami/
cloud-itonami-isic-6010` confirmed it was already published at
`:blueprint` tier from an earlier bulk-scaffolding pass —
`CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/`GOVERNANCE.md`/`LICENSE`/
`README.md`/`SECURITY.md`/`blueprint.edn`/`docs/` only, no
`deps.edn`/`src`/`test`. This build ADDED the missing implementation
module set on top of that existing boilerplate rather than
re-scaffolding the repository (the existing docs were kept, not
recreated).

Before any work began, the registry's own live `:name` for `{:id
"6010" ...}` was independently re-verified against a fresh clone of
`kotoba-lang/industry` and confirmed to read exactly "Radio
broadcasting" (no truncation, no mismatch) — this fleet's own
ID/name-mismatch caution.

The pre-existing `blueprint.edn`/`docs/business-model.md`/
`docs/operator-guide.md` describe a broader aspirational business
(broadcast-license scope management, robotics-assisted transmitter/
studio-equipment inspection, advertising-billing records,
`:itonami.blueprint/robotics true`). This build deliberately
implements a NARROWER, explicitly-scoped slice per this task's own
domain design: a radio-broadcasting OPERATIONS COORDINATION actor.
This actor never holds direct on-air-content authority or emergency-
alert-broadcast authority — see Decision 1 and Decision 2 below. Per
CLAUDE.md's Wave-4 person-facing-service safety guardrail
(ADR-2607152500), radio broadcasting touches editorial-content
authority (on-air content decisions) and Emergency Alert System
decisions, so the closed op allowlist must never include an op that
directly finalizes either — enforced structurally (no such op exists
in the allowlist) AND by an independent text-scan (defense in depth).

## Decision

Build `radioops` (BroadcastOpsAdvisor ⊣ BroadcastOpsGovernor)
implementing the same langgraph-clj StateGraph + independent Governor
+ Phase 0→3 rollout pattern as `pressops`/5813, closed to a
four-member op allowlist, all `:effect :propose`:

- `:log-broadcast-record` — playlist/segment/on-air-log data logging
- `:schedule-broadcast-operation` — programming/segment scheduling proposal
- `:flag-content-concern` — surfaces an FCC-compliance/on-air-incident/
  emergency-alert concern; ALWAYS escalates
- `:coordinate-equipment-maintenance` — transmitter/studio-equipment
  maintenance coordination

This actor is explicitly NOT the on-air-content authority and NOT the
Emergency Alert System authority.

### Decision 1: three HARD governor checks, permanent and un-overridable by any human approval

`radioops.governor/check` HARD-blocks on: (1) `station-unverified` —
the target station record must exist AND be independently
`:registered?`/`:verified?` in the store before any proposal for it
may commit or even escalate, re-derived from the station's own store
record every time, never from the proposal's own self-report; (2)
`effect-not-propose` — any `:effect` other than `:propose` is a claim
to directly actuate/commit outside governance; (3) `scope-excluded` —
folds together an op outside the closed four-op allowlist (structural:
no on-air-content-finalizing or emergency-alert-broadcast-finalizing op
exists in the allowlist at all) AND an independent text-scan of the
proposal's op/summary/rationale/cites/value for finalization-ACTION
phrases touching on-air-content-decision or emergency-alert-broadcast-
decision territory. Both layers are HARD, permanent, un-overridable
blocks — HOLD never reaches human approval, matching this task's own
explicit invariant that no op may directly finalize an editorial-
content or emergency-alert-broadcast decision.

### Decision 2: scope-exclusion terms phrased as finalization ACTIONS or decision-outcome noun phrases, never bare nouns — this fleet's known self-tripping bug class, guarded by construction AND by a dedicated test

Multiple sibling agents in this fleet have independently discovered and
fixed the SAME bug: a governor's own scope-exclusion term list phrased
as a bare noun (e.g. "content", "broadcast", "emergency") accidentally
matches inside the mock advisor's OWN default rationale/disclaimer text
for a legitimate, allowed proposal — causing the actor to self-block on
its own happy path. `radioops.governor/scope-excluded-terms` is phrased
as decision-outcome noun phrases and finalization ACTION phrases ("on-
air content decision", "emergency alert broadcast decision", "finalize
the on-air content") rather than bare nouns — mirroring `pressops`/
5813's own proven phrasing technique, including the JA-side technique
of inserting a separator word (`そのものの`) between a content noun and
`確定` in every negation sentence in the advisor's own rationale text so
no legitimate proposal's negation ever contains the excluded substring
contiguously. `test/radioops/advisor_test.clj`'s
`default-mock-advisor-proposals-never-self-trip-scope-exclusion` runs
the default mock advisor's `infer` across every one of the four
allowlisted ops and asserts none of the resulting proposals trip the
scope-exclusion check — the actual guarantee, not wording care alone.
A companion `out-of-scope-hook-trips-scope-exclusion` test (plus two
targeted JA/EN cases in `governor_test.clj`) confirms the patterns are
not vacuously non-matching.

### Decision 3: `:flag-content-concern` always escalates — TWO independent layers

`radioops.governor/always-escalate-ops` includes `:flag-content-concern`
(confidence/high-stakes gate always escalates), AND
`radioops.phase/phases` never includes `:flag-content-concern` in any
phase's `:auto` set, at any phase, including phase 3 — a structural
invariant, not a rollout milestone still to come. Both
`radioops.phase-test` (including a dedicated
`flag-content-concern-never-in-any-phase-auto-set` structural-invariant
test iterating every phase) and `radioops.governor-contract-test`
assert this independently. `:schedule-broadcast-operation`/
`:coordinate-equipment-maintenance` do NOT get this treatment — they
auto-commit cleanly at phase 3 like `:log-broadcast-record`, since
programming-schedule and equipment-maintenance coordination are
routine back-office proposals, not judgment calls about on-air content
or emergency alerts.

### Decision 4: hand-rolled MemStore, not `kotoba-lang/langchain-store`

`kotoba-lang/langchain-store` (ADR-2607141600) is the newer shared
substrate for the EDN-blob codec + identity-schema + entity field-spec
pattern and the preferred path for NEW stores. This build instead
mirrors `pressops`/5813's own hand-rolled `MemStore` exactly (the
reference this task explicitly named to mirror closely), to stay
byte-for-byte aligned with a VERIFIED-working, independently re-tested
sibling rather than introduce a second store substrate mid-mirror. A
reasonable, low-risk migration follow-up once touched again, per this
workspace's own "touched, migrate incrementally" policy.

### Decision 5: registry.edn diff is `:maturity` only — `:repo`/`:business-id` already correct

The registry entry's `:business-id` was `"cloud-itonami-6010"` (bare,
no `-isic-` infix) before this edit. A live-registry check confirmed
this is NOT a bug: sibling `cloud-itonami-isic-6020` (television
broadcasting — the nearest structural twin, sharing this entry's exact
`:required-technologies`/`:operating-states` template) was promoted to
`:implemented` in this same fast-moving window with the identical bare
`"cloud-itonami-6020"` business-id convention, and `cloud-itonami-isic-
4911`/`4912` (rail transport) also carry the bare, non-`-isic-` form.
`:repo` (`https://github.com/cloud-itonami/cloud-itonami-isic-6010`)
was already correct. This promotion therefore adds ONLY `:maturity
:implemented` to the registry entry, leaving `:repo`/`:business-id`/
`:required-technologies`/`:optional-technologies`/`:operating-states`
untouched — a deliberately minimal, exact-block diff, per this task's
own explicit instructions.

## Concurrency

`test/kotoba/industry_test.clj` is a very hot, shared, append-only
file. This session's registry.edn PUT itself needed two attempts (the
blob sha it was based on drifted once between fetch and PUT, due to a
concurrent sibling promoting a different entry — the edit was rebuilt
against the freshly re-fetched content and sample-verified before
retrying, per this fleet's own hot-contention discipline). The
`industry_test.clj` PUT needed three re-fetch cycles: the file's blob
sha drifted from a concurrent sibling agent's own edits corroborating
`cloud-itonami-isic-5610` and `cloud-itonami-isic-7729`, and — most
notably — by the third re-fetch, a concurrent sibling agent had
already written a GENERIC one-line corroboration stub for THIS
session's own `"6010"` entry ("promoted to :implemented by a
CONCURRENT sibling fleet agent"), evidently believing this session's
work was itself a concurrent sibling's. This session replaced that
generic stub with its own detailed, authoritative "6010" testing
block (since this session did the actual 6010 implementation work),
leaving that sibling's own already-correct tier-count re-sync
(`:blueprint`/`:implemented` numeric assertions) and its own
`cloud-itonami-isic-7710`/`cloud-itonami-isic-7729` corroboration
blocks untouched — the same reconciliation pattern ADR-2628080000's
own Concurrency section established for the `4912`/`4911` pair.

## Consequences

- `cloud-itonami-isic-6010` promoted from `:blueprint` to
  `:implemented`. Fleet maturity immediately before this promotion's
  own registry edit (live-recomputed via
  `(kotoba.industry/maturity-summary)` against a freshly re-fetched
  `origin/main`, which already reflected the concurrent sibling
  `cloud-itonami-isic-6020` promotion): `{:total 649, :spec 232,
  :blueprint 11, :implemented 406}`. This was an UNUSUALLY
  fast-moving window: `test/kotoba/industry_test.clj`'s own sha
  drifted three times between fetch and PUT (concurrent siblings
  promoting `cloud-itonami-isic-5610`, `cloud-itonami-isic-7729`, and
  `cloud-itonami-isic-7710`, one of which had already written a
  generic corroboration stub for THIS session's own `"6010"` entry by
  the time of this session's own PUT — replaced with this session's
  detailed, authoritative block since this session did the actual
  6010 implementation work, per the same pattern ADR-2628080000's own
  Concurrency section established for `4912`/`4911`). By the time of
  this promotion's own final PUT and independent fresh-clone
  verification, live-recomputed fleet maturity (which include those
  concurrent siblings' own promotions, not only this one) reached
  `{:total 649, :spec 232, :blueprint 6, :implemented 411}`.
- 45 tests / 126 assertions pass in the actor repo (`clojure -M:test`);
  clj-kondo 0 errors / 0 warnings; the demo (`clojure -M:run`) walks
  one clean record-log (phase 1, approval) + record-log/schedule/
  equipment-maintenance (phase 3, auto-commit) + content-concern-flag
  (always-escalate) lifecycle, plus four HARD-hold scenarios
  (unregistered station, unverified station, non-`:propose` effect,
  scope-excluded content), end-to-end, with no exceptions — 52 commit /
  6 escalate / 36 hold disposition events observed across the full
  demo transcript, matching every scripted scenario.
- Fully portable `.cljc` with no JVM-only interop anywhere in `src/`,
  per this fleet's cljs-first runtime-priority rule.
- Establishes, for the radio-broadcasting domain specifically, the
  same "operations coordination, not on-air-content/emergency-alert
  authority" pattern several other Wave-4 media/publishing siblings in
  this fleet (`cloud-itonami-isic-5813`, `cloud-itonami-isic-5920`,
  `cloud-itonami-isic-6020`) have independently converged on: closed
  four-op allowlist, literal `:effect :propose`, scope-exclusion
  phrased as finalization-decision noun phrases never bare nouns, a
  dedicated self-trip regression test, and a "flag a concern" op that
  is always escalate-only, never auto-eligible, at any phase.

## Alternatives considered

- **Implementing the broader pre-existing blueprint scope**
  (broadcast-license issuance, robotics-assisted transmitter
  inspection, advertising-billing records, `kotoba-lang/robotics`/
  `kotoba-lang/phone` capability-layer wiring). Deferred: out of scope
  for this task's own explicit domain design, which scopes this actor
  to operations coordination only; the broader blueprint vision remains
  documented in the repo's own `docs/business-model.md`/
  `docs/operator-guide.md`, untouched by this build.
- **Adopting `kotoba-lang/langchain-store` immediately.** Deferred per
  Decision 4 above — a reasonable near-term follow-up, not a rejection.
- **"Correcting" `:business-id` to the `-isic-` infixed form.**
  Rejected after confirming the bare form is an established,
  independently-precedented convention (Decision 5), not a defect.

## References

- `cloud-itonami-isic-5813/README.md`/`src/pressops/*` (nearest sibling
  in the media/publishing family; mirrored closely per this task's own
  explicit instruction)
- `kotoba-lang/langchain-store` (ADR-2607141600; deferred adoption)
- ADR-2607152500 (Wave 4 person-facing-service safety guardrail)
- US Federal Communications Commission, 47 C.F.R. Part 73 (radio
  broadcast services) and 47 C.F.R. Part 11 (Emergency Alert System)
- Ofcom Broadcasting Code (UK)
- 放送法 (Japan Broadcast Act) / 電波法 (Japan Radio Act)
