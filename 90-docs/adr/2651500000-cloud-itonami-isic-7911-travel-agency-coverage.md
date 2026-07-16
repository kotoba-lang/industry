# ADR-2651500000: cloud-itonami-isic-7911 (Travel agency activities) booking-operations-coordination actor -- fill in pre-existing blueprint-tier repo, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (Wave 3, production/robotics), ADR-2607152500
(Wave 4, human-facing/personal services), `cloud-itonami-isic-7990` (Other
reservation service and related activities -- the verified module shape
this actor mirrors, independently re-read in full before use), the
`kotoba-lang/industry` registry's `"7911"` catalog entry. This is one of 6
targets in the LAST batch of a blueprint-tier cleanup sweep across
`kotoba-lang/industry` (sibling target `"7912"` -- Tour operator
activities -- built by a concurrent sibling agent in the same batch).

## Context

`gh api repos/cloud-itonami/cloud-itonami-isic-7911` confirmed a
PRE-EXISTING repository (created 2026-07-09, one push) containing only
boilerplate docs -- `CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/`GOVERNANCE.md`/
`LICENSE`/`README.md`/`SECURITY.md`/`blueprint.edn`/`docs/business-model.md`/
`docs/operator-guide.md` -- and NO `deps.edn`/`src`/`test`, from an earlier
bulk-scaffolding pass. This is a legitimate `:blueprint`-tier registry
entry (repo published, never implemented), NOT a fresh-scaffold 404
target. The repo's own `blueprint.edn` declares
`:itonami.blueprint/id "cloud-itonami-7911"`, matching the registry's
existing `:business-id` exactly (an intentional deviation from the newer
`cloud-itonami-isic-NNNN` business-id shape used by more recently
registered entries -- left unchanged since it already matches the repo's
own declared identity).

`kotoba-lang/industry`'s registry carried a `"7911"` entry with NO
`:maturity` key at all (falling back to `:blueprint` via the `(:repo
industry)` branch of `kotoba.industry/maturity-of`) and `:name "Travel
agency activities"` -- independently verified against a fresh clone before
any work began, matching the assigned ISIC class exactly (not truncated,
not confused with sibling `"7912"` "Tour operator activities", built
separately in this same batch by a concurrent sibling agent). `:repo`
(`https://github.com/cloud-itonami/cloud-itonami-isic-7911`) and
`:business-id` (`cloud-itonami-7911`) were both already correct and left
unchanged.

This is part of Wave 4 (human-facing/personal-services fleet). Travel
agency booking touches consumer-payment and cancellation-policy decisions
-- the same class of person-facing-service risk as ticketing/reservation
services (`cloud-itonami-isic-7990`) -- so per Wave 4's person-facing-
service safety guardrail this actor's closed op allowlist must never
include an op that directly finalizes a payment-dispute resolution or a
refund/cancellation-policy override; those are always either a hard
permanent block or an always-escalate op, never auto-commit-eligible.

## Decision

ADD the missing `deps.edn`/`src`/`test` module set to the existing
`cloud-itonami/cloud-itonami-isic-7911` repo (existing boilerplate docs
kept, not recreated) as a travel-agency booking OPERATIONS COORDINATION
actor (not a payment-dispute or cancellation-policy authority), mirroring
`cloud-itonami-isic-7990`'s verified module shape
(`store`/`advisor`/`governor`/`phase`/`operation`/`sim`) with fresh,
travel-agency-specific domain logic under the `travelagency` namespace:

1. **`travelagency.store`** -- `MemStore` (atom of EDN) behind a `Store`
   protocol; a `bookings` directory keyed by `:booking-id` STRING (a
   traveler's booking/itinerary or an airline/hotel/vendor settlement
   contract under the same registered/verified lifecycle), plus an
   append-only `ledger` and a `coordination-log` of committed records.
2. **`travelagency.advisor`** ("TravelAgencyAdvisor") -- deterministic
   mock advisor drafting exactly four kinds of proposal: booking/
   itinerary/payment-status record logging (`:log-booking-record`),
   booking/confirmation scheduling (`:schedule-booking-operation`),
   airline/hotel/vendor settlement coordination
   (`:coordinate-vendor-settlement`), and payment-dispute/cancellation/
   fraud concern flagging (`:flag-transaction-concern`). Every proposal's
   `:effect` is always `:propose`; every output is censored downstream by
   the governor.
3. **`travelagency.governor`** ("TravelAgencyGovernor") -- three HARD
   checks, all permanent and un-overridable: (1) booking-unverified -- the
   target booking/client record must exist AND be independently
   `:registered?`/`:verified?` in the store before ANY proposal for it may
   commit or escalate; (2) effect-not-propose -- any `:effect` other than
   `:propose` is HARD-blocked; (3) scope-exclusion -- any proposal
   (regardless of op) whose op/summary/rationale/cites/value touches
   directly finalizing a payment-dispute resolution or directly finalizing
   a refund/cancellation-policy override is a HARD, PERMANENT block,
   unconditionally evaluated on every proposal; an op outside the closed
   four-op allowlist is folded into this same check. Two ESCALATE (soft)
   gates: `:flag-transaction-concern` ALWAYS escalates to a human
   regardless of confidence, and a `:coordinate-vendor-settlement`
   proposal whose estimated settlement amount exceeds a $5,000 threshold
   likewise always escalates, regardless of confidence; low confidence
   generally escalates too.
4. **`travelagency.phase`** -- Phase 0->3 staged rollout;
   `:flag-transaction-concern` is permanently ABSENT from every phase's
   `:auto` set (structural fact, not a rollout milestone still to come) --
   only `:log-booking-record`/`:schedule-booking-operation`/
   `:coordinate-vendor-settlement` may auto-commit at phase 3 when
   governor-clean.
5. **`travelagency.operation`** ("OperationActor") -- langgraph-clj
   StateGraph, `intake -> advise -> govern -> decide -> commit | hold |
   request-approval`, `interrupt-before #{:request-approval}` for
   human-in-the-loop sign-off, invoked exclusively via
   `langgraph.graph/run*`.
6. **`deps.edn`** -- mirrors `cloud-itonami-isic-7990`'s shape
   (`:test`/`:lint`/`:run`/`:dev` aliases, the `:dev` alias overriding
   `io.github.kotoba-lang/langgraph` and `io.github.kotoba-lang/langchain`
   to `../../kotoba-lang/langgraph`/`../../kotoba-lang/langchain` sibling
   local roots, same pinned `kotoba-lang/langgraph` git SHA
   `a332a770a0d2b5193f81b54483bb954fb29ef8d7`). Existing `blueprint.edn`/
   `README.md`/`GOVERNANCE.md`/`CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/
   `SECURITY.md`/`docs/` kept unchanged; a `.gitignore` (`target/`/
   `.cpcache/`/swap/DS_Store files) was added since none existed.

### Scope-exclusion phrasing discipline (fleet-wide self-tripping bug avoided proactively)

Multiple sibling agents in this fleet have independently discovered and
fixed the same bug class: a scope-exclusion term list phrased as a bare
noun (e.g. "payment", "dispute", "refund", "cancellation") accidentally
matches inside the mock advisor's own DEFAULT rationale/disclaimer text
for a legitimate, allowed proposal, causing the actor to self-block on its
own happy path. This is an especially acute risk here because
`:flag-transaction-concern`'s entire purpose is to discuss payment-
dispute/cancellation/fraud topics using exactly those bare nouns. This
build avoided that class from the start rather than discovering it via a
failing test: `travelagency.governor/scope-excluded-terms` are phrased
exclusively as the finalization/execution ACTION ("finalize the payment
dispute resolution", "grant the refund override", "override the
cancellation policy", "issue a chargeback determination"), never as the
bare noun alone. A dedicated regression test,
`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
`test/travelagency/governor_test.clj`, independently confirms every
allowed op's own default (clean) advisor-generated proposal text --
including `:flag-transaction-concern`'s own default proposal deliberately
constructed to mention a payment dispute and a refund/cancellation-policy
question as raw observation -- clears the governor's scope-exclusion scan
and never HARD-holds. A second test,
`legitimate-transaction-concern-is-not-scope-excluded`, exercises the same
invariant directly against a hand-built concern proposal.

### What this actor does NOT do

Directly finalizing a payment-dispute resolution (chargebacks, refund
disputes) and directly finalizing a refund/cancellation-policy override
both remain exclusively human/institutional decisions, permanently, with
no actor or human-approval override path -- enforced structurally by the
governor's closed op/effect allowlists and the unconditional
scope-exclusion check, not just documented. `:flag-transaction-concern` is
a "surface the concern" op only; it can never self-resolve the concern it
raises, and is never a member of any phase's `:auto` set.

## Verification

- `cloud-itonami-isic-7911`: `clojure -M:dev:test` -- raw final line:
  `Ran 49 tests containing 142 assertions.` / `0 failures, 0 errors.`
  (re-run green a second time from a brand-new fresh clone after push,
  identical result). `clojure -M:lint` -- 0 errors, 0 warnings.
  `clojure -M:dev:run` demo driver runs end-to-end with no exceptions.
- All source under `src/`/`test/` is `.cljc`, no JVM-only interop; the
  actor graph is invoked exclusively via `langgraph.graph/run*`.
- Pushed to the EXISTING repo's `main` (no repo creation, no LICENSE
  reconciliation needed): commit
  `d33f1db2d4da4119f1ee7e94aadb2516040cc156`, confirmed via a brand-new
  fresh clone (`git log`/`git ls-tree` matches `gh api
  repos/cloud-itonami/cloud-itonami-isic-7911/commits/main`).
- `kotoba-lang/industry` registry `"7911"` entry updated in place
  (exact-text edit of the literal `{:id "7911" ...}` block only -- Python
  exact-substring-count-verified confined to a single occurrence before
  the PUT, no other entry's block touched, sample-verified against
  `3512`/`8010`/`7912` also intact): added `:maturity :implemented`
  explicitly (previously absent, resolving to `:blueprint` only via the
  `(:repo industry)` fallback); `:repo`/`:business-id`/
  `:required-technologies`/`:optional-technologies`/`:operating-states`
  were already correct and left unchanged. Landed via a Contents-API
  single-file PUT (sha-checked optimistic concurrency, sha re-verified
  fresh immediately before the PUT with no drift, succeeded on the first
  attempt), commit `8239df504c22f904194869f257bf644286bffdc1`.
- `test/kotoba/industry_test.clj` (extremely hot, high-concurrency shared
  file across the fleet) -- content re-fetched fresh immediately before
  building the edit (sha re-verified with no drift): added a dedicated
  `"7911"` testing block (`is (= :implemented (industry/maturity
  "7911"))`); fixed two STALE spot-checks this promotion's own registry
  edit made incorrect -- a pre-existing `"cloud-itonami-isic-7911,
  freshly published, is also :blueprint (live-state corroboration)"` test
  (converted to a superseded-by-detailed-block comment, matching this
  file's own established convention for promoted entries) and the
  `maturity-summary` tier-count assertions (`:blueprint` 6 -> 5,
  `:implemented` 411 -> 412), each with an explanatory delta comment.
  Landed via a Contents-API single-file PUT (sha-checked, no drift),
  commit `595f1b55b73ebed5a29d55daae361c8bbc2f1840`.
- Full `kotoba-lang/industry` suite re-run green (fresh clone, plus a
  fresh `kotoba-lang/technology` sibling clone for `deps.edn` resolution)
  immediately before the test-file PUT, and again from a brand-new
  post-merge scratch clone: `Ran 15 tests containing 1063 assertions.` /
  `0 failures, 0 errors.` (both runs identical).
- Post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone of both `cloud-itonami-isic-7911` and
  `kotoba-lang/industry`, plus a fresh `kotoba-lang/technology` sibling):
  `cloud-itonami-isic-7911`: `Ran 49 tests containing 142 assertions.` /
  `0 failures, 0 errors.`; `kotoba-lang/industry`: `Ran 15 tests
  containing 1063 assertions.` / `0 failures, 0 errors.`. The registry's
  `"7911"` entry confirmed `:maturity :implemented`, sample sibling
  entries (`3512`/`8010`/`7912`) confirmed intact, and a Python scan for
  the UTF-8 replacement character against
  `resources/kotoba/industry/registry.edn` returned `0` (no mojibake).
- Live `(kotoba.industry/maturity-summary)` at merge time: `{:total 649,
  :spec 232, :blueprint 5, :implemented 412}`. Five entries remain
  nil-maturity (fallback `:blueprint`) at this snapshot --
  `7912`/`8121`/`8130`/`8219`/`8220` -- the other five targets of this
  same 6-target blueprint-tier cleanup batch, expected to close out via
  concurrent sibling agents in this fleet run (this ADR does not assert
  the fleet-wide nil-maturity count has reached zero; only that this
  batch's own `"7911"` target has).

## Consequences

(+) `cloud-itonami-isic-7911` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing an
empty blueprint-only repo.

(+) `kotoba-lang/industry` registry `"7911"` entry promoted to
`:maturity :implemented`.

(+) This actor is another concrete example of Wave 4's person-facing-
service safety guardrail applied to the consumer-payment/cancellation-
policy authority axis (payment-dispute resolution, refund/cancellation-
policy override), folded into one unconditional scope-exclusion check
that never trusts the advisor's own framing of its intent, and phrased
from the start as finalization-ACTION terms (not bare nouns) to avoid the
fleet-known self-tripping bug class -- validated directly by a dedicated
regression test rather than merely asserted.

(-) Still a simulation/proposal layer, not a real travel-booking/GDS
system. Payment-dispute resolution, refund/cancellation-policy
determinations, and actual fund settlement remain human-/institution-
controlled via external channels (payment processors, GDS/booking
platforms, bonding/consumer-protection regulators).

(-) No integration with real booking systems (GDS, airline/hotel
reservation systems, payment processors) -- this is a standalone
coordinator blueprint, matching every prior sibling actor's own stated
limitation.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Bundle payment-dispute/refund-override resolution as an always-escalate op the actor can still draft | ❌ | Wave 4's guardrail requires that no op directly finalize a payment-dispute-resolution or refund/cancellation-policy-override decision; even an escalate-only "draft a resolution" op would imply the actor participates in constructing that decision, so it is excluded entirely via the closed op allowlist plus the HARD scope-exclusion check, not merely gated |
| Phrase scope-exclusion terms as bare nouns ("payment", "dispute", "refund", "cancellation") for simplicity | ❌ | Directly the fleet-known self-tripping bug class -- a legitimate `:flag-transaction-concern` proposal must be free to say "payment dispute" or "refund under the cancellation policy" when reporting a real concern; phrasing exclusions as the finalization ACTION avoids the collision structurally, verified by a dedicated regression test rather than left to chance |
| Re-scaffold the repo from scratch (new `gh repo create`) | ❌ | The repo already exists at `:blueprint` tier with legitimate boilerplate docs (CLAUDE.md's own briefing confirmed this via `gh api` before any work began); re-creating would destroy existing README/GOVERNANCE/docs content for no benefit -- the correct action is to ADD the missing module set on top |

## References

- ADR-2607121000 (Wave 3, production/robotics)
- ADR-2607152500 (Wave 4, human-facing/personal services)
- `cloud-itonami-isic-7990` (Other reservation service and related
  activities -- the verified Wave 4 module shape this actor mirrors)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
