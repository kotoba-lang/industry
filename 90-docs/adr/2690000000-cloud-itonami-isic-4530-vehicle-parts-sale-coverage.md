# ADR-2690000000: cloud-itonami ISIC 4530 -- Sale of motor vehicle parts and accessories coverage

- Status: Accepted (2026-07)
- Related: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse-toposort
  plan -- Wave 2, coordination/logistics/trade), `cloud-itonami-isic-4510`
  (Sale of motor vehicles, the closest sibling in ISIC division 45),
  `cloud-itonami-isic-4730` (Retail sale of automotive fuel, the most
  recent self-contained-vertical port), `cloud-itonami-isic-6511`
  (UnderwritingGovernor, the reference `kotoba-lang/langchain-store`
  entity-store adopter), `90-docs/adr/2607141600-*` (langchain-store
  extraction)
- Repo: [`cloud-itonami/cloud-itonami-isic-4530`](https://github.com/cloud-itonami/cloud-itonami-isic-4530)
  (`78622e0183cf8187070d24f6fc7791db1dd3fb6f` on `main`)

## Context

`kotoba-lang/industry`'s registry carries ISIC Rev.5 class `4530` --
verified live `:name` "Sale of motor vehicle parts and accessories"
(auto-parts retail/wholesale, distinct from sibling class `4520`
motor-vehicle repair and `4540` motorcycle sale/repair, built by other
agents in the same Wave-2 batch) -- at `:maturity :spec` with no repo.
This ADR promotes it to `:implemented` with a fresh actor scaffold, no
pre-existing repo (confirmed via `gh api repos/cloud-itonami/cloud-
itonami-isic-4530` 404 before starting).

This is Wave 2 of the reverse-toposort plan (ADR-2607121000):
coordination/logistics/trade classes, after Waves 3/4 (class-level
scope) completed.

## Problem

An auto-parts retailer/wholesaler needs four distinct kinds of
operational coordination -- sales/inventory-movement logging,
restocking-schedule coordination, part-compatibility/counterfeit/
recall concern surfacing, and supplier procurement-order coordination
-- and an LLM has no grounding for any of them, and critically no
structural reason not to drift into acting as if it WERE a
compatibility-certification or recall-remediation authority itself.
The design problem is "seal the LLM inside a trust boundary that can
only ever propose, verify every party it proposes on behalf of, and
make directly finalizing a compatibility certification a permanent,
structurally-absent capability."

## Decision

Scaffold `cloud-itonami-isic-4530` as an auto-parts-retail OPERATIONS
COORDINATION actor -- **PartsOpsAdvisor ⊣ AutoPartsOpsGovernor** --
following the same langgraph-clj StateGraph + independent Governor +
append-only audit ledger + Phase 0→3 rollout pattern as every prior
actor in this fleet.

### 1. Closed 4-op proposal allowlist, `:effect :propose` only

`:log-sales-record` / `:schedule-restocking-operation` /
`:flag-compatibility-concern` / `:coordinate-supply-order`. Every
branch of `autoparts.partsopsadvisor` hard-codes `:effect :propose` --
there is no code path that can ever emit a different effect, and
`autoparts.registry` has no certificate-issuing function of any kind
(the structural absence, not merely a governor rule, is the actual
boundary).

### 2. AutoPartsOpsGovernor: 4 HARD checks + 3 SOFT escalate rules

HARD (un-overridable): closed-op-allowlist · verified-party-gate
(storefront AND, for supply orders, the target vendor must be
independently verified/registered) · effect-propose-only ·
compatibility-certification-finalization-block (a PERMANENT block).
SOFT (human may approve): confidence floor · `:flag-compatibility-
concern` ALWAYS escalates · `:coordinate-supply-order` above a cost
threshold escalates.

### 3. The compatibility-certification-finalization block is structured-field-only, by design

Multiple sibling actors in this fleet have independently discovered
and fixed the SAME bug class: a governor's scope-exclusion term phrased
as a bare noun (e.g. "compatibility", "certification") accidentally
matches inside the mock advisor's own DEFAULT rationale/disclaimer
text for a legitimate, in-scope proposal, causing the actor to
self-block on its own happy path.
`compatibility-certification-finalization-violations` checks only an
exact `:op` match against a banned-ops set and exact boolean flags
inside `:value` -- both phrased as the finalization/execution ACTION,
never a bare noun -- and never scans `:rationale`/`:summary` prose.
`test/autoparts/governor_contract_test.cljc`'s
`default-mock-advisor-proposals-never-self-trip-scope-exclusion` is the
executable regression proof, run against the mock advisor's own
default proposal for all four in-scope ops on clean, verified demo
data.

### 4. Store adopts BOTH `kotoba-lang/langchain-store` patterns

`autoparts.store` uses the entity-store pattern (`ls/map->tx` /
`ls/pull->map` / `ls/pull-pattern`) for `storefront`/`vendor`, and the
seq-keyed event-stream pattern (`ls/read-stream` / `ls/append-blob!`)
for the audit ledger and the four coordination logs -- no hand-rolled
`enc`/`dec*` codec.

## Consequences

- (+) Auto-parts retail/wholesale operations coordination gets the
  same governed, auditable-actor treatment as motor-vehicle sale
  (`4510`) and automotive-fuel retail (`4730`), without centralizing
  liability in one vendor.
- (+) The scope-exclusion invariant is regression-tested by both a
  dedicated adversarial unit test and the mandatory happy-path
  non-self-trip test.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by
  `test/autoparts/store_contract_test.cljc`.
- (-) No real POS / warehouse-management / procurement-system
  integration in this R0 -- each operator's responsibility.
- (-) `autoparts.facts`' citation catalog seeds only 2 real sources
  (NHTSA recalls + a structural operator-registered fitment-catalog
  class), reported honestly, not a governor-enforced gate in this R0.
- 24 tests / 115 assertions, 0 failures / 0 errors (`clojure -M:dev:test`,
  fresh scratch checkout). clj-kondo clean (`clojure -M:lint`).

## Registry

`kotoba-lang/industry`'s `"4530"` entry promoted `:spec` -> `:implemented`,
`:repo` corrected to `https://github.com/cloud-itonami/cloud-itonami-isic-4530`,
`:business-id` corrected to `cloud-itonami-isic-4530` (previously
pointed at a non-existent `gftdcojp/cloud-itonami-G4530` placeholder).
Landed via a Contents-API single-file PUT (sha-checked optimistic
concurrency, immediately re-fetched fresh content before the PUT per
this fleet's hot-contention discipline, exact-block edit only verified
via an exact old/new substring occurrence count of 1 PLUS
sample-verified against the `"4510"`/`"4711"` entries and the
redundant `"453"` 3-digit group entry, all confirmed intact after the
PUT, no mojibake detected). A dedicated `cloud-itonami-isic-4530-is-
implemented` test was added to `kotoba-lang/industry`'s own
`test/kotoba/industry_test.clj` (a very hot, shared, append-only file
-- re-fetched fresh immediately before each PUT throughout, several
concurrent sibling agents' own edits observed and worked around
without touching their content). Registry maturity, live-recomputed
via `(kotoba.industry/maturity-summary)` against a freshly re-fetched
origin/main from a brand-new post-merge clone (not assumed): `{:total
649, :spec 226, :blueprint 0, :implemented 423}` -- this entry's own
`:spec -> :implemented` promotion is one of several concurrent sibling
fleet agents' promotions folded into this snapshot. `clojure -M:test`
from that fresh clone (plus a freshly re-cloned `kotoba-lang/technology`
sibling): 17 tests / 1073 assertions, 0 failures / 0 errors. No
mojibake detected in `registry.edn`.
The redundant 3-digit group entry `"453"` was left untouched.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Let `:coordinate-supply-order` auto-commit at any cost when clean | ❌ | The task's own escalation spec requires a cost threshold; unconditional auto-eligibility would let a large, unreviewed spend commitment slip through at phase 3 |
| Also exclude `:coordinate-supply-order` from every phase's `:auto` set | ❌ | Over-broad: supply orders escalate only ABOVE a cost threshold, not always -- a below-threshold, clean, verified order auto-committing at phase 3 is intended behavior |
| Check the compatibility-certification-finalization block by scanning `:rationale`/`:summary` text | ❌ | The exact documented self-trip bug class this fleet has independently hit and fixed more than once |
| Give `autoparts.registry` a certificate-issuing function, gated only by the governor | ❌ | Leaves a latent capability a governor bug/bypass could exercise; better for there to be no certificate-builder to call at all |
