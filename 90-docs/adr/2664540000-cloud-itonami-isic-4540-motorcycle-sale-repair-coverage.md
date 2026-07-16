# ADR-2664540000: cloud-itonami-isic-4540 (Sale, maintenance and repair of motorcycles and related parts and accessories) motorcycle-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse-toposort
wave plan), ADR-2607152500 (Wave 4 rollout-start amendment to
ADR-2607121000's P3->P4 sequencing gate, defining the person-facing-
service safety guardrail this actor is built under), ADR-2607121200
(Wave 0 cljs-first safety kernel), skill `build-actor` (advisor/
governor/StateGraph/audit-ledger actor pattern). Mirrors
`cloud-itonami-isic-7710` (Renting and leasing of motor vehicles),
whose `vehiclerentalops.*` module shape this ADR follows
module-for-module as `motorcycleops.*`.

## Context

ISIC Rev.5 class 4540 covers sale, maintenance and repair of
motorcycles and related parts and accessories -- motorcycle
dealership/workshop operations (sale/repair-order and parts-used
logging, bay/technician service-scheduling, safety-concern flagging,
parts/inventory procurement coordination). Before scaffolding, the
live `kotoba-lang/industry` registry entry for `"4540"` was
independently re-verified fresh (not assumed from this task's
premise) by cloning the registry repo directly: its `:name` field
read a truncated `"Sale, maintenance and repair of motorcycles and
related par..."`, whose prefix exactly matched the expected full ISIC
class name; de-truncated to the full name
`"Sale, maintenance and repair of motorcycles and related parts and
accessories"` as part of this same exact-block edit. A separate,
redundant 3-digit group registry entry `{:id "454"}` exists with a
similarly truncated name (`:repo nil`, `:maturity :spec`) -- a known
artifact of an earlier data-seeding pass, left untouched, only the
`"4540"` class-level block was edited.

`gh api repos/cloud-itonami/cloud-itonami-isic-4540` confirmed no
repository existed at all (404) -- this was a fresh scaffold, not a
fill-in of pre-existing boilerplate.

Motorcycle repair/maintenance touches a person-facing-service risk
dimension squarely within ADR-2607152500's Wave 4 guardrail, but a
narrower one than typical: unlike sibling actors that must exclude
TWO decision areas (e.g. driver-eligibility AND vehicle-safety-
clearance), this actor's domain design (given by this task) specifies
exactly ONE permanently-excluded decision area -- **finalizing a
roadworthiness-clearance decision** (certifying a motorcycle safe to
return to the road/customer after service or sale). Motorcycle
repair/maintenance has a DIRECT road-safety dimension: a badly
serviced motorcycle can kill its rider, so this exclusion was treated
with the same rigor as any two-topic sibling. Per ADR-2607152500, the
closed op allowlist must never include an op that directly finalizes
this decision -- it is always either a hard permanent block or an
always-escalate op, never auto-commit-eligible; the `:flag-safety-
concern` op must always escalate to human sign-off, never appearing
in any phase's `:auto` set.

This fleet has independently discovered and re-fixed the same
recurring bug class across multiple Wave 4 actors: a governor's own
`scope-excluded-terms` list phrased as a bare noun can accidentally
match inside the mock advisor's own default rationale/disclaimer text
for a legitimate, allowed proposal -- self-blocking the actor on its
own happy path. **This bug was independently re-triggered and fixed
during this actor's own build** (not merely avoided by design): an
initial Japanese scope-excluded term `"公道走行可否を確定"` matched a
substring inside the `:schedule-service-operation` advisor's own
default disclaimer text (`"...公道走行可否を確定させるものではない"`,
i.e. explicitly stating it does NOT finalize roadworthiness) -- caught
by a real `clojure -M:test` failure, not by inspection, and fixed by
rephrasing the disclaimer sentence (not by weakening the term list)
so the two no longer share the offending substring.

## Decision

Implement `cloud-itonami-isic-4540` with Store, Advisor, Governor,
Phase, Operation, Sim (mirrors `cloud-itonami-isic-7710`'s
`vehiclerentalops.*` module shape module-for-module, renamed to
`motorcycleops.*`):

- **`motorcycleops.store`** -- `Store` protocol + `MemStore`.
  String-keyed `accounts` directory (`:account-id`/`:name`/
  `:registered?`/`:verified?`, representing a dealership/workshop
  customer or fleet service account), append-only `ledger`,
  append-only `service-log`.
- **`motorcycleops.advisor`** -- `Advisor` protocol + `mock-advisor`.
  Four proposal generators, all `:effect :propose`:
  `:log-service-record` (sale/repair-order and parts-used metadata
  logging), `:schedule-service-operation` (bay/technician
  service-scheduling proposal), `:flag-safety-concern` (defect/
  recall/unsafe-repair concern surfacing), `:coordinate-parts-order`
  (parts/inventory procurement coordination).
- **`motorcycleops.governor`** (`MotorcycleOpsGovernor`) --
  independent compliance layer, HARD checks (always hold,
  un-overridable):
  1. `account-unverified` -- target sale/repair-order account must
     exist AND be independently `:registered?`/`:verified?` in the
     store, never trusting the proposal's own claim.
  2. `effect-not-propose` -- any `:effect` other than `:propose` is a
     claim to directly actuate/commit outside governance.
  3. `scope-excluded`/`op-not-allowed` -- ANY proposal (regardless of
     op) attempting to finalize a roadworthiness-clearance decision is
     permanently blocked; an op outside the closed four-op allowlist
     is folded into the same check.

  **Self-trip discipline (per this fleet's known bug class, and
  independently re-triggered during this build -- see Context)**:
  `scope-excluded-terms` are phrased as the finalization/execution
  ACTION -- `"finalize the roadworthiness clearance"`, `"certify the
  motorcycle roadworthy despite the known defect"`, `"release the
  motorcycle despite the recall"`, Japanese equivalents (e.g.
  `"整備不良のまま公道復帰を確定"`) -- never as a bare noun
  ("roadworthiness", "safety", "clearance", "defect"). A dedicated
  regression test
  (`default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  in `governor_test.clj`) asserts all four default proposal
  generators, for a clean registered+verified account, never trip
  `:scope-excluded` and never HARD-hold.

  ESCALATE (always human sign-off): `:flag-safety-concern` always
  escalates (`always-escalate-ops`), independently confirmed by
  `motorcycleops.phase` never including it in any phase's `:auto`
  set; a `:coordinate-parts-order` proposal whose draft `:cost`
  exceeds `parts-order-cost-threshold` (300000, currency-agnostic
  demo units) also always escalates via `high-stakes?`, independently
  confirmed by a dedicated `phase_test.clj` case
  (`high-cost-parts-order-stays-escalate-through-phase-gate`) that a
  governor-side escalate survives the phase gate even for an
  otherwise auto-eligible op; low confidence (< 0.6) also escalates.
- **`motorcycleops.phase`** -- 0->3 rollout: phase 0 read-only; phase
  1 `:log-service-record` only, approval-gated; phase 2 adds
  `:schedule-service-operation`/`:coordinate-parts-order`, still
  approval-gated; phase 3 auto-commits the three non-concern ops when
  governor-clean and confident (a high-cost parts order still
  escalates via the governor's own cost-threshold check, independent
  of `:auto` membership), `:flag-safety-concern` still always
  escalates.
- **`motorcycleops.operation`** -- real `langgraph-clj` `StateGraph`
  (intake -> advise -> govern -> decide -> commit|hold|
  request-approval), `interrupt-before #{:request-approval}` for
  human-in-the-loop resume, mirroring `vehiclerentalops.operation`
  exactly (not a stub).
- **`motorcycleops.sim`** -- demo runner (`clojure -M:run`) walking
  the full happy path (all four ops at phase 1 then phase 3, including
  both a low-cost and a high-cost parts-order), the always-escalate
  safety-concern flow, and every HARD-hold scenario (unregistered
  account, unverified account, non-`:propose` effect, out-of-scope
  drift).
- Tests: `store_contract_test`, `advisor_test`, `governor_test` (incl.
  the mandatory self-trip regression and dedicated cost-threshold
  escalation tests), `phase_test`, `governor_contract_test` -- 46
  tests / 135 assertions, all green.
- All `.cljc` (portable, no JVM-only interop). AGPL-3.0-or-later.
  Fresh README/GOVERNANCE/CONTRIBUTING/SECURITY/CODE_OF_CONDUCT/
  `blueprint.edn`/`docs/` scaffolded for a new repo.

## Scope exclusions (hardcoded in governor checks, not just prose)

- Finalizing a roadworthiness-clearance decision (certifying a
  motorcycle safe to return to the road/customer after service or
  sale) -- always a hard, permanent block. No proposal op in the
  closed allowlist performs this action; `:flag-safety-concern` only
  ever surfaces a concern for human triage, and always escalates.
- A `:coordinate-parts-order` proposal above the cost threshold --
  always escalates to a human, regardless of phase or governor
  cleanliness otherwise.
- Payment capture/refund, contract negotiation, and binding purchase-
  order execution -- out of scope, not represented in the closed op
  allowlist at all.

## Consequences

(+) ISIC 4540 motorcycle-sale-repair-operations-coordination is
genuinely implemented and fully tested as a fresh scaffold.

(+) The roadworthiness-clearance-finalization exclusion is hardcoded
in governor checks (`scope-exclusion-violations`), not just asserted
in README prose.

(+) The self-trip bug class this fleet has repeatedly encountered was
independently re-triggered by this actor's own domain vocabulary and
fixed by construction (rephrasing the colliding disclaimer sentence,
not weakening the term list), covered by a dedicated regression test
-- direct empirical evidence the discipline this task's CRITICAL
notes warned about is a real, recurring risk, not a hypothetical one.

(+) `:flag-safety-concern` always-escalate is a two-layer invariant
(governor `always-escalate-ops` + phase's permanent absence from
every `:auto` set), matching every sibling Wave 4 actor's own safety
discipline. The cost-threshold escalation for `:coordinate-parts-
order` is independently tested to survive the phase gate even when
the op is otherwise `:auto`-eligible.

(+) Portable `.cljc`, zero JVM-only constructs; `clojure -M:lint` is 0
errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up;
tests use in-memory `MemStore`, matching every sibling actor's
current maturity.

(-) `motorcycleops.advisor`'s `mock-advisor` is deterministic, not a
real LLM call; the `Advisor` protocol seam is ready for that swap but
it is not wired in this ADR.

## Verification

- `cloud-itonami-isic-4540`: `clojure -M:test` (pinned git/sha) ->
  "Ran 46 tests containing 135 assertions. 0 failures, 0 errors."
  `clojure -M:lint` -> 0 errors, 0 warnings. `clojure -M:run` demo
  walked all 10 scenarios (both auto-commit and escalate paths for
  every op, every HARD-hold case) without exception.
- Commit `a09b9e6fca2d9823257e0ef068206c547884b3cc` pushed directly to
  a freshly-created `cloud-itonami/cloud-itonami-isic-4540`'s `main`
  (new repo, no prior history, plain push, no PR needed since this
  repo has no branch-protection gate).
- Post-push re-verification from a brand-new fresh clone of
  `cloud-itonami/cloud-itonami-isic-4540` `main`: `clojure -M:test` ->
  identical "Ran 46 tests containing 135 assertions. 0 failures, 0
  errors."

## Verification addendum: registry promotion (Step 7, landed)

- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`:
  `"4540"` entry promoted `:maturity :spec` -> `:implemented`; `:repo`
  and `:business-id` corrected from the stale
  `gftdcojp/cloud-itonami-G4540` placeholder to
  `cloud-itonami/cloud-itonami-isic-4540` /
  `cloud-itonami-isic-4540`; `:name` de-truncated to the full ISIC
  class name. `:required-technologies`/`:optional-technologies`/
  `:operating-states` were already correct and left unchanged. The
  separate 3-digit group entry `{:id "454"}` was confirmed untouched,
  as were sibling entries `4520`/`4530` (both still `:spec` at this
  snapshot, expected to be promoted by sibling agents in this same
  batch). Landed via a Contents-API single-file PUT (sha-checked
  optimistic concurrency, content freshly re-fetched immediately
  before the PUT, exact-block edit verified via a python
  single-occurrence substring check plus sample-verified against
  `454`/`4520`/`4530` entries also intact, no mojibake detected,
  landed on the first attempt), commit
  `98f3a7ea0540cbbec09aa32bd31e4bb195300fad`.
- `test/kotoba/industry_test.clj`: this file and `registry.edn` are
  both extremely hot, high-concurrency shared files across the fleet
  -- confirmed empirically during this promotion's own edit window.
  Added a dedicated corroboration `testing` block for `"4540"`
  (including a first-person account of the self-trip bug this build
  independently re-triggered and fixed, per this task's CRITICAL
  discipline). The pinned `(:implemented m)` count assertion required
  FOUR successive corrections during this promotion's own landing
  window alone, each triggered by a genuinely concurrent sibling
  promotion landing between this promotion's read and its own PUT,
  not by any error in this promotion's own method: `417 -> 418` (this
  promotion's own `+1`) -> `418 -> 419` (concurrent
  `cloud-itonami-isic-4721`, `+1`) -> `419 -> 420` (concurrent
  `cloud-itonami-isic-4520`, this task's own sibling ISIC class,
  `+1`) -> `420 -> 423` (three further concurrent sibling promotions
  including `cloud-itonami-isic-4530` and `cloud-itonami-isic-4661`,
  `+3`). Each correction was live-recomputed via a brand-new fresh
  clone's `(kotoba.industry/maturity-summary)` immediately before
  landing, never assumed from a stale buffer, and this promotion's own
  `"4540"` entry and several spot-check samples (`454`, `7722`,
  `6310`) were independently re-confirmed intact after every round.
  Landed via four successive Contents-API single-file PUTs, each on
  its first attempt (sha-checked optimistic concurrency, content
  freshly re-fetched immediately before each PUT): commits
  `a7e21e46f33b7ad6f4e954ea203bc75a19fb6f02`,
  `c6dd5538e895b9360f3dd3b45774237b3c61d3cb`,
  `2792725a3115fe9acb19408b3614dbfd53133840`,
  `a0236f446364cb4d6e9846a9b250f51593dc805b`.
- Post-merge re-verification from a brand-new fresh clone of
  `kotoba-lang/industry` `main` (plus a fresh `../technology` sibling
  clone), taken immediately after the fourth count-fix landed:
  `clojure -M:test` -> "Ran 15 tests containing 1064 assertions. 0
  failures, 0 errors." `clojure -M:lint` -> 0 errors, 0 warnings. No
  mojibake detected in `registry.edn` (zero U+FFFD replacement
  characters). `"4540"`'s own entry independently re-confirmed
  `:implemented` with the correct `:repo`/`:business-id`, the
  separate `"454"` group entry re-confirmed untouched at `:spec`, and
  sample entries `7722`/`6310` also confirmed intact. Live
  `(kotoba.industry/maturity-summary)` at this final re-verification:
  `{:total 649, :spec 226, :blueprint 0, :implemented 423}` -- given
  the observed churn rate (three further concurrent promotions landed
  within the final verification window alone), the pinned count
  assertion may already be marginally stale again by the time a
  subsequent reader inspects it; this is expected, previously-
  documented fleet behavior (see `ADR-2650000000`'s own addendum) and
  not a defect in this promotion's own method -- the mechanical fix is
  a one-line assertion bump, safely repeatable by any agent that
  re-runs `(kotoba.industry/maturity-summary)` fresh.
