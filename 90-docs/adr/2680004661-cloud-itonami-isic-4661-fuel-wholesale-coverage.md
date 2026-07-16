# ADR-2680004661: cloud-itonami ISIC 4661 (Wholesale of solid, liquid and gaseous fuels and related products) coverage

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization per `CLAUDE.md`)
**Scope**: `orgs/kotoba-lang/industry`, `cloud-itonami/cloud-itonami-isic-4661` (new repo)

## Context

ADR-2607121000 establishes the cloud-itonami Wave 2 (coordination/
logistics/trade) rollout. This ADR promotes ISIC 4661 ("Wholesale of
solid, liquid and gaseous fuels and related products") from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry.

The live registry entry's own `:name` was found genuinely truncated
with a literal `"..."` (`"Wholesale of solid, liquid and gaseous fuels
and related pr..."`), a known ~10% pre-existing seed-data bug across
this registry; this ADR's registry edit de-truncates it to the full
class name as part of the normal exact-block edit.

Fuel wholesale (petroleum, LPG, and other combustible products) carries
a direct hazmat-handling and storage-safety dimension. Per the task's
own hazmat-handling directive, this build's closed op allowlist NEVER
includes any op that directly finalizes a hazmat-storage-safety
clearance or a tank/pipeline-integrity decision — both are permanent,
unconditional HARD blocks, never auto-commit-eligible, and the one
"flag a concern" op (`:flag-safety-concern`) always escalates and is
never a member of any phase's `:auto` set.

### Relationship to `cloud-itonami-isic-4671`

`cloud-itonami-isic-4671` ("Wholesale of solid, liquid and gaseous
fuels", `:implemented`, ISIC Rev.5 numbering per its own blueprint)
already exists in this fleet and covers the SAME underlying real-world
business (fuel wholesale) under a DIFFERENT ISIC code and a
DIFFERENT architectural shape: a `:fuel-trading-governor` centered on
counterparty credit clearance, sanctions screening (OFAC/equivalent),
and contract-on-file verification ahead of a real bulk-fuel delivery
dispatch or invoice settlement — a fuel-TRADING vertical.

This repository (`cloud-itonami-isic-4661`, ISIC Rev.4 numbering, the
code the live `kotoba-lang/industry` registry entry actually carries)
is architecturally distinct: a `:fuel-depot-operations-governor`
centered on a fuel-wholesale DEPOT's own back-office operations
coordination — shipment-record logging, delivery-operation scheduling,
safety-concern flagging, and supplier-order coordination against the
depot's own operating-license and tank-capacity ground truth, with NO
counterparty-credit or sanctions-screening dimension. The two repos'
own namespace prefixes (`fueltrade` vs `fueldepot`) and governor
keywords (`:fuel-trading-governor` vs `:fuel-depot-operations-
governor`) are grep-verified disjoint and unique fleet-wide (`gh
search code "fueldepot" --owner cloud-itonami` and `gh search code
"fuel-depot-operations-governor" --owner cloud-itonami`, zero hits
before this repo was created). This ADR does not touch
`cloud-itonami-isic-4671` or its own registry entry (id `"4671"`) in
any way.

This is not a new discovery: ADR-2607100400 (the petroleum
supply-chain fleet ADR that originally built `cloud-itonami-isic-4671`)
already recorded, in its own `:isic-code-mismatch-finding`, that the
actor "built as 4671" has no real ISIC Rev.4 equivalent under that
code — its real Rev.4 code is 4661, named exactly "Wholesale of solid,
liquid and gaseous fuels and related products" — and that resolution
was to register `4671` under its own published project-internal code
while leaving the genuine Rev.4 entry (id `"4661"`) "separately
registered at `:spec`, untouched" for a future actor. This ADR is that
future actor, landing at the point Wave 2 (ADR-2607121000) reaches it.

### A known self-tripping bug pattern in this actor family

Multiple sibling agents in this fleet have independently discovered
and fixed the same bug class: a governor's own scope-exclusion term
list phrased as a bare noun (e.g. bare "safety") can accidentally
match inside the mock advisor's own DEFAULT rationale/disclaimer text
for a legitimate, allowed proposal, causing the actor to self-block on
its own happy path. This build avoids the bug class structurally: both
permanent scope-exclusion checks in `fueldepot.governor`
(`tank-integrity-clearance-finalize-blocked-violations`,
`hazmat-storage-safety-clearance-finalize-blocked-violations`) test a
dedicated boolean flag on the proposal's own `:value`
(`:finalize-tank-integrity-clearance?` /
`:finalize-hazmat-storage-safety-clearance?`), phrased as the
finalization/execution ACTION rather than a bare noun, never a
text-search over free-form rationale. A dedicated regression test
(`default-mock-advisor-proposals-never-self-trip-scope-exclusion-
checks`) asserts this directly against every op's own default
mock-advisor proposal, including one whose legitimate safety-concern
rationale text deliberately contains the words "integrity" and
"safety".

## Decision

Build `cloud-itonami-isic-4661` as a governed actor (FuelDepotAdvisor
⊣ Fuel Depot Operations Governor, langgraph-clj StateGraph, append-only
audit ledger), mirroring the closest structural analog in this fleet,
`cloud-itonami-isic-2029` (plant/depot operations coordination, single
verified/registered central-entity gate, four propose-only ops, Phase
0->3 rollout with exactly one auto-eligible op at phase 3).

Four ops, closed allowlist, all `:effect :propose`:
- `:log-shipment-record` — fuel-receipt/dispatch/inventory-level data logging (administrative; auto-commit-eligible at phase 3 when clean)
- `:schedule-delivery-operation` — tanker-truck/pipeline delivery-operation scheduling proposal (never auto-eligible)
- `:flag-safety-concern` — surface a leak/spill/tank-integrity concern (ALWAYS escalates, never auto-eligible at any phase)
- `:coordinate-supplier-order` — supplier fuel-procurement order coordination proposal (never auto-eligible; orders at/above a 250,000 USD threshold, independently recomputed, ALWAYS escalate regardless of confidence)

HARD invariants (always `:hold`, no override):
- Depot/license record must be independently verified/registered before any action (`:schedule-delivery-operation` and `:coordinate-supplier-order` both gate on the same `depot-ready?` check)
- `:effect` must be `:propose` only (request-level, evaluated first)
- Closed op allowlist (the four ops above) and closed proposal-effect allowlist (no direct tank/pipeline-equipment control)
- Finalizing a tank/pipeline-integrity clearance (`:finalize-tank-integrity-clearance? true`) — PERMANENT, unconditional block
- Finalizing a hazmat-storage-safety clearance (`:finalize-hazmat-storage-safety-clearance? true`) — PERMANENT, unconditional block
- A delivery's own claimed quantity must independently recompute within the depot's own logged tank capacity
- No double-scheduling the same delivery operation; no double-coordinating the same supplier order
- No fabricated `:fuel-type`; no physically implausible `:quantity-liters`

Full ADR (architecture, decision rationale, consequences, verification)
lives in the child repo: `cloud-itonami-isic-4661`'s own
`docs/adr/0001-architecture.md`.

## Verification

Fresh clone, independent re-run (see report for exact raw output):

```
clojure -M:test
Ran 75 tests containing 209 assertions.
0 failures, 0 errors.
```

`clojure -M:lint`: clean (0 errors, 0 warnings). `clojure -M:dev:run`
demo narrative runs to completion with no exceptions, exercising the
full happy path and every HARD-hold/escalation scenario directly.

`kotoba-lang/industry` registry entry `{:id "4661" ...}` updated
in-place (exact-block edit only): `:name` de-truncated to the full
ISIC class name, `:maturity :spec` → `:implemented`, `:repo` and
`:business-id` corrected to `https://github.com/cloud-itonami/
cloud-itonami-isic-4661` / `cloud-itonami-isic-4661` (previously
carried a stale placeholder `gftdcojp/cloud-itonami-G4661`),
`:operating-states` corrected from a leftover `:spec`-era retail-shaped
placeholder (`[:intake :stock :sell :reconcile :reorder :audit]`, which
did not describe this actor's actual StateGraph) to the actual
langgraph-clj node sequence (`[:intake :advise :govern :approve
:commit :audit]`, matching sibling actors' own implemented entries),
ADR reference added.

## Consequences

(+) ISIC 4661 fuel-wholesale-depot operations now has a documented,
governed, auditable coordination actor, structurally distinct from and
non-duplicative of `cloud-itonami-isic-4671`'s own fuel-trading
vertical.

(+) The registry's own pre-existing truncation and stale-repo-pointer
bugs on this entry are corrected as part of landing this actor.

(-) Still a simulation/proposal layer — see the child repo's own ADR
"Consequences" section for the full list of what remains out of scope
(no real depot-management/SCADA integration, no real hazmat-
storage-safety-certification database).
