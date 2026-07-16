# ADR-2626000000: cloud-itonami-isic-7990 (Other reservation service and related activities) ticketing/reservation-agency-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (Wave 3, production/robotics), ADR-2607152500
(Wave 4, human-facing/personal services -- authorizes this batch to
proceed in parallel with Wave 3), cloud-itonami-isic-5520 (Camping
grounds, recreational vehicle parks and trailer parks -- the verified
Wave 4 module shape this actor mirrors, independently re-read in full
before use), the `kotoba-lang/industry` registry's `"7990"` catalog
entry

## Context

`kotoba-lang/industry`'s registry carried a `"7990"` entry at
`:maturity :spec` with `:name "Other reservation service and related
activities"` (NOT truncated -- matched the assigned ISIC class exactly,
confirming no ID/name mismatch before any work began) and `:repo
"https://github.com/gftdcojp/cloud-itonami-N7990"` (an old,
never-populated naming scheme). Confirmed no repository exists yet at
either that stale placeholder or the real
`cloud-itonami/cloud-itonami-isic-7990` target (`gh api` 404 for the
latter) before any work began. A separate, redundant 3-digit group
entry `{:id "799" :name "Other reservation service and related
activities" :repo nil :business-id nil :maturity :spec ...}` also
exists in the registry -- a known earlier data-seeding-pass artifact,
deliberately left untouched; only the class-level `"7990"` block was
edited.

This is part of Wave 4 (human-facing/personal-services fleet, ISIC
sections I/P/Q/R/S/T), running in parallel with the ongoing Wave 3
(production/robotics) rollout per ADR-2607152500. ISIC 7990 covers
ticketing agencies and event-reservation services not elsewhere
classified as travel-agency/tour-operator activities (ISIC 7911/7912).
Ticketing/reservation services touch consumer-payment and
event-access decisions, so per Wave 4's person-facing-service safety
guardrail this actor's closed op allowlist must never include an op
that directly finalizes a payment-dispute resolution or an
access-eligibility override -- those are always either a hard
permanent block or an always-escalate op, never auto-commit-eligible.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-7990` as a ticketing/
reservation-agency OPERATIONS COORDINATION actor (not a
payment-dispute or access-eligibility authority), mirroring
`cloud-itonami-isic-5520`'s verified module shape
(`store`/`advisor`/`governor`/`phase`/`operation`/`sim`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY, AGPL-3.0-or-later) with fresh,
ticketing/reservation-specific domain logic under the
`reservationops` namespace:

1. **`reservationops.store`** -- `MemStore` (atom of EDN) behind a
   `Store` protocol; a `reservations` directory keyed by
   `:reservation-id` STRING (a customer booking/ticket reservation or
   a vendor/venue settlement contract under the same
   registered/verified lifecycle), plus an append-only `ledger` and a
   `coordination-log` of committed records.
2. **`reservationops.advisor`** ("ReservationOpsAdvisor") --
   deterministic mock advisor drafting exactly four kinds of proposal:
   booking/ticket-issuance record logging (`:log-reservation-record`),
   inventory/seat-allocation scheduling
   (`:schedule-allocation-operation`), vendor/venue settlement
   coordination (`:coordinate-vendor-settlement`), and
   payment-dispute/fraud/access-eligibility-concern flagging
   (`:flag-transaction-concern`). Every proposal's `:effect` is always
   `:propose`; every output is censored downstream by the governor.
3. **`reservationops.governor`** ("ReservationGovernor") -- three HARD
   checks, all permanent and un-overridable: (1) reservation-unverified
   -- the target reservation/vendor-contract record must exist AND be
   independently `:registered?`/`:verified?` in the store before ANY
   proposal for it may commit or escalate; (2) effect-not-propose --
   any `:effect` other than `:propose` is HARD-blocked; (3)
   scope-exclusion -- any proposal (regardless of op) whose
   op/summary/rationale/cites/value touches directly finalizing a
   payment-dispute resolution or directly finalizing an
   access-eligibility override is a HARD, PERMANENT block,
   unconditionally evaluated on every proposal; an op outside the
   closed four-op allowlist is folded into this same check. Two
   ESCALATE (soft) gates: `:flag-transaction-concern` ALWAYS escalates
   to a human regardless of confidence, and a
   `:coordinate-vendor-settlement` proposal whose estimated settlement
   amount exceeds a $5,000 threshold likewise always escalates,
   regardless of confidence; low confidence generally escalates too.
4. **`reservationops.phase`** -- Phase 0->3 staged rollout;
   `:flag-transaction-concern` is permanently ABSENT from every
   phase's `:auto` set (structural fact, not a rollout milestone still
   to come) -- only `:log-reservation-record`/`:schedule-allocation-
   operation`/`:coordinate-vendor-settlement` may auto-commit at phase
   3 when governor-clean.
5. **`reservationops.operation`** ("OperationActor") -- langgraph-clj
   StateGraph, `intake -> advise -> govern -> decide -> commit | hold |
   request-approval`, `interrupt-before #{:request-approval}` for
   human-in-the-loop sign-off, invoked exclusively via
   `langgraph.graph/run*`.
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-5520`'s shape (`:test`/`:lint`/`:run`/`:dev`
   aliases pinned to the same `kotoba-lang/langgraph` git SHA
   `a332a770a0d2b5193f81b54483bb954fb29ef8d7`, confirmed to still be
   the live `main` HEAD of `kotoba-lang/langgraph` at build time,
   `itonami.blueprint/*` metadata, scope/design/testing README
   sections).

### Scope-exclusion phrasing discipline (fleet-wide self-tripping bug avoided proactively)

Multiple sibling agents in this fleet have independently discovered
and fixed the same bug class: a scope-exclusion term list phrased as a
bare noun (e.g. "payment", "dispute", "eligibility", "access")
accidentally matches inside the mock advisor's own DEFAULT
rationale/disclaimer text for a legitimate, allowed proposal, causing
the actor to self-block on its own happy path. This is an especially
acute risk here because `:flag-transaction-concern`'s entire purpose
is to discuss payment-dispute/fraud/access-eligibility topics using
exactly those bare nouns. This build avoided that class from the start
rather than discovering it via a failing test:
`reservationops.governor/scope-excluded-terms` are phrased exclusively
as the finalization/execution ACTION ("finalize the payment dispute
resolution", "grant the access eligibility override", "issue a
chargeback determination"), never as the bare noun alone. A dedicated
regression test, `default-mock-advisor-proposals-never-self-trip-
scope-exclusion` in `test/reservationops/governor_test.clj`,
independently confirms every allowed op's own default (clean)
advisor-generated proposal text -- including `:flag-transaction-
concern`'s own default proposal deliberately constructed to mention a
payment dispute and an access-eligibility question as raw observation
-- clears the governor's scope-exclusion scan and never HARD-holds. A
second test, `legitimate-transaction-concern-is-not-scope-excluded`,
exercises the same invariant directly against a hand-built concern
proposal.

### What this actor does NOT do

Directly finalizing a payment-dispute resolution (chargebacks, refund
disputes) and directly finalizing an access-eligibility override
(ticket/reservation redemption or event-access eligibility) both
remain exclusively human/institutional decisions, permanently, with no
actor or human-approval override path -- enforced structurally by the
governor's closed op/effect allowlists and the unconditional
scope-exclusion check, not just documented. `:flag-transaction-concern`
is a "surface the concern" op only; it can never self-resolve the
concern it raises, and is never a member of any phase's `:auto` set.

## Verification

- `cloud-itonami-isic-7990`: `clojure -M:test` -- raw final line:
  `Ran 49 tests containing 142 assertions.` / `0 failures, 0 errors.`
  (re-run green a second time from a brand-new fresh clone after push,
  identical result).
- `clojure -M:lint` -- 0 errors, 0 warnings.
- All source under `src/`/`test/` is `.cljc`, no JVM-only interop; the
  actor graph is invoked exclusively via `langgraph.graph/run*`.
- Repo created fresh (`gh repo create` + push, public visibility
  matching the reference and `blueprint.edn`'s `:status :public-oss`).
  `gh repo create --license agpl-3.0` auto-seeded a one-file "Initial
  commit" (LICENSE only) on the remote before the local push landed;
  reconciled with a local `--allow-unrelated-histories` merge
  (`-X ours` on the trivially line-wrap-different LICENSE text, no
  rebase, no force-push), landing merge commit
  `8a4dde9156bf0f49fe3eb91bcf67aa81de9685cc` on `main`, confirmed via a
  brand-new fresh clone (`git log --oneline -1` matches `gh api
  repos/cloud-itonami/cloud-itonami-isic-7990/commits/main`).
- `kotoba-lang/industry` registry `"7990"` entry updated in place
  (exact-text edit of the literal `{:id "7990" ...}` block only, no
  other entry's block touched, byte-offset prefix/suffix-scan-verified
  confined to the intended region): `:repo`/`:business-id` corrected
  from the never-populated `gftdcojp/cloud-itonami-N7990` naming to
  `cloud-itonami/cloud-itonami-isic-7990` / `cloud-itonami-isic-7990`,
  `:required-technologies` trimmed from the stale 7-item `:spec`
  placeholder `[:robotics :identity :forms :dmn :bpmn :audit-ledger
  :labor]` to the coordination-only shape actually implemented
  `[:identity :forms :dmn :bpmn :audit-ledger]` (matching sibling
  `cloud-itonami-isic-5520`/`873`'s own `:required-technologies`),
  `:maturity` `:spec` -> `:implemented`; `:name`/`:operating-states`
  deliberately left unchanged (name already exact, operating-states
  matching this fleet's established convention of not touching that
  field on promotion when it already fits). Landed via a Contents API
  single-file PUT (sha-checked optimistic concurrency; the sha used
  was re-verified fresh immediately before the PUT and matched with no
  drift, succeeded on the first attempt), commit
  `5469e819c259a0fa72168261bba7a7dc881f69dc`.
- `test/kotoba/industry_test.clj`'s dedicated `"7990"` testing block
  added, with the `maturity-summary` assertion left at the true live
  count at PUT time. This file is an extremely hot, high-concurrency
  shared file across the fleet (dozens of sibling promotions landing
  within minutes of each other during this work); the first two PUT
  attempts hit sha drift from concurrent sibling activity on this
  exact file and were rebuilt from a freshly re-fetched buffer per
  this fleet's hot-contention discipline (never a reused/stale buffer)
  before the third attempt landed, commit
  `f2b890255dbd012912d65f962ec40120e742b739`, bumping the count
  assertion to the true live `392` (reflecting many concurrent sibling
  promotions landed in the same window, not just this one's own +1).
- Full `kotoba-lang/industry` suite re-run green after all edits (fresh
  clone, plus a fresh `kotoba-lang/technology` sibling clone for
  `deps.edn` resolution), immediately before the final test-file PUT:
  `Ran 15 tests containing 1052 assertions.` / `0 failures, 0 errors.`
- Final post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone of both `cloud-itonami-isic-7990` and
  `kotoba-lang/industry`, plus a fresh `kotoba-lang/technology`
  sibling): `clojure -M:test` re-run green in both repos --
  `cloud-itonami-isic-7990`: `Ran 49 tests containing 142 assertions.`
  / `0 failures, 0 errors.`; `kotoba-lang/industry`: `Ran 15 tests
  containing 1054 assertions.` / `0 failures, 0 errors.` (count grew
  by 2 assertions from further concurrent sibling test-block additions
  landed in the interim, unrelated to this promotion). The registry's
  `"7990"` entry confirmed `:maturity :implemented` with the corrected
  `:repo`/`:business-id`/`:required-technologies`, the separate `"799"`
  group entry confirmed still `:spec` and untouched, and a sample of
  sibling entries (`5520`/`873`/`6310`) confirmed intact. Python scan
  for the UTF-8 replacement character against
  `resources/kotoba/industry/registry.edn` returned `0` (no file-wide
  mojibake).

## Consequences

(+) `cloud-itonami-isic-7990` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing
a `:spec` placeholder pointing at a repo that never existed.

(+) `kotoba-lang/industry` registry `"7990"` entry promoted to
`:maturity :implemented`.

(+) This actor is another concrete example of Wave 4's person-facing-
service safety guardrail applied to a distinct sensitive-decision
axis: consumer-payment/event-access authority (payment-dispute
resolution, access-eligibility override) rather than clinical-care,
editorial-content, or on-set physical-safety authority, folded into
one unconditional scope-exclusion check that never trusts the
advisor's own framing of its intent, and phrased from the start as
finalization-ACTION terms (not bare nouns) to avoid the fleet-known
self-tripping bug class -- validated directly by a dedicated
regression test rather than merely asserted.

(-) Still a simulation/proposal layer, not a real
ticketing/reservation-management system. Payment-dispute resolution,
access-eligibility determinations, and actual fund settlement remain
human-/institution-controlled via external channels (payment
processors, box-office/venue access-control systems).

(-) No integration with real ticketing/reservation-management systems
(box-office point-of-sale, seat-map/inventory engines, payment
processors, venue access-control) -- this is a standalone coordinator
blueprint, matching every prior sibling actor's own stated limitation.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Bundle payment-dispute resolution as an always-escalate op the actor can still draft | ❌ | Wave 4's guardrail requires that no op directly finalize a payment-dispute-resolution or access-eligibility-override decision; even an escalate-only "draft a dispute resolution" op would imply the actor participates in constructing that decision, so it is excluded entirely via the closed op allowlist plus the HARD scope-exclusion check, not merely gated |
| Phrase scope-exclusion terms as bare nouns ("payment", "dispute", "eligibility", "access") for simplicity | ❌ | Directly the fleet-known self-tripping bug class -- a legitimate `:flag-transaction-concern` proposal must be free to say "payment dispute" or "access eligibility" when reporting a real concern; phrasing exclusions as the finalization ACTION avoids the collision structurally, verified by a dedicated regression test rather than left to chance |
| Keep `cloud-itonami-isic-7990` at `:spec` only | ❌ | Wave 4 is actively building out the human-facing-services fleet in parallel with Wave 3; this class was unclaimed and ready for a fresh scaffold, mirroring the now-standard `cloud-itonami-isic-5520` shape |

## References

- ADR-2607121000 (Wave 3, production/robotics)
- ADR-2607152500 (Wave 4, human-facing/personal services)
- `cloud-itonami-isic-5520` (Camping grounds, recreational vehicle
  parks and trailer parks -- the verified Wave 4 module shape this
  actor mirrors)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
