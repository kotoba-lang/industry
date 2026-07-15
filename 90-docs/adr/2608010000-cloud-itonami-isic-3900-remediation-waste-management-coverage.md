# ADR-2608010000: cloud-itonami ISIC 3900 (Remediation activities and other waste management services) coverage

- Status: Accepted
- Date: 2026-07-15
- Deciders: Jun Kawasaki (agent-executed, standing authorization per CLAUDE.md)

## Context

`kotoba-lang/industry`'s `registry.edn` tracks one `cloud-itonami-isic-*`
actor per ISIC Rev.4 class. ISIC 3900 ("Remediation activities and
other waste management services" — contaminated-site cleanup,
soil/groundwater remediation) was registered at `:maturity :spec` with
a placeholder `:repo`/`:business-id`
(`https://github.com/gftdcojp/cloud-itonami-E3900` /
`cloud-itonami-E3900`). No repository existed at that placeholder URL
or at the canonical `cloud-itonami/cloud-itonami-isic-3900` name
(confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-3900`
404 before starting this work).

This ADR documents scaffolding and landing that actor, following the
established pattern from `cloud-itonami-isic-3821` (Treatment and
disposal of non-hazardous waste) and the sibling `cloud-itonami-isic-3822`
(hazardous waste) — both already `:implemented`.

ISIC 3900 is distinct from those siblings: it covers **remediation
activities** (contaminated-site cleanup, soil excavation/treatment,
groundwater pump-and-treat, site-closure verification support), not
ongoing waste treatment/disposal facility operations.

## Decision

Scaffold and land `cloud-itonami/cloud-itonami-isic-3900` as a
langgraph-clj StateGraph actor:
`RemediationOpsAdvisor ⊣ SiteRemediationOpsGovernor`.

**Domain**: a site-remediation PROJECT OPERATIONS COORDINATION actor —
explicitly NOT direct excavation/treatment-equipment control
authority, NOT a regulatory site-closure-certification authority.

**Closed proposal-op allowlist** (all `:effect :propose`):
- `:log-remediation-record` — excavation/treatment-volume,
  contaminant-level sampling-data logging
- `:schedule-remediation-operation` — excavation/treatment/
  monitoring-well scheduling proposal
- `:flag-contamination-concern` — surface a contaminant-spread/
  exposure-risk concern — **ALWAYS escalates**
- `:coordinate-disposal` — treated-material/contaminated-soil
  disposal-site coordination

**HARD invariants** (always `:hold`, never human-overridable):
1. Site unverified — target site must be independently
   `:registered?`/`:verified?` in the store before any proposal
   commits or escalates (never trusts the proposal's own claim).
2. `:effect` must be `:propose` only.
3. Scope exclusion — any proposal touching excavation/treatment-
   equipment control or a regulatory site-closure-certification
   decision is a permanent, un-overridable block, independent of op
   or confidence. An op outside the closed 4-op allowlist folds into
   this same check.

**ESCALATE** (always human sign-off): `:flag-contamination-concern`
always escalates; low advisor confidence (`< 0.6`).

**Rollout phases** (`remediationops.phase`, 0→3): phase 0 read-only →
phase 1 remediation-record logging (approval-gated) → phase 2 adds
remediation-operation scheduling + disposal coordination
(approval-gated) → phase 3 supervised-auto (those three ops may
auto-commit when governor-clean and confident).
`:flag-contamination-concern` is permanently absent from every
phase's `:auto` set — a structural fact enforced independently by
both `remediationops.governor/always-escalate-ops` and
`remediationops.phase`.

## Verification

Built in a uniquely-named scratch directory (never inside the shared
`orgs/kotoba-lang/industry` checkout), all source `.cljc` (no JVM-only
interop). Full module set mirrored from `cloud-itonami-isic-3821`:
`store` / `advisor` / `governor` / `phase` / `operation` / `sim` +
5 test namespaces (`advisor-test`, `governor-test`,
`governor-contract-test`, `phase-test`, `store-contract-test`).

```
Ran 45 tests containing 135 assertions.
0 failures, 0 errors.
```

`clojure -M:lint` (clj-kondo): `errors: 0, warnings: 0`.

Pushed to `https://github.com/cloud-itonami/cloud-itonami-isic-3900`
(new repo, `gh repo create --push`), landed on `main` at
`9da92ddfe97ae64c4a545f066de95343eb5baf3b` (verified via
`git merge-base --is-ancestor` against `origin/main`).

## Registry follow-up

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
`{:id "3900" ...}` entry is updated in-place (exact-text block edit
only) to `:maturity :implemented`, correct `:repo`
(`https://github.com/cloud-itonami/cloud-itonami-isic-3900`) and
`:business-id` (`cloud-itonami-isic-3900`), and
`:required-technologies` narrowed from the spec-stage placeholder
`[:robotics :identity :forms :dmn :bpmn :audit-ledger :telemetry]` to
`[:identity :forms :dmn :bpmn :audit-ledger]` (no robotics/telemetry —
this is a back-office coordination actor, mirroring the same
correction ISIC 3821 made at its own implementation time). The
`industry_test.clj` `:implemented` count assertion is bumped to match
the recomputed `kotoba.industry/maturity-summary` count. See the
companion registry-side commit for the merged SHA.

## Consequences

- ISIC 3900 now has a real, tested, `:implemented` actor consistent
  with the rest of the `cloud-itonami-isic-*` fleet's governance
  discipline (independent Governor, closed op-allowlist, append-only
  audit ledger, staged rollout phases).
- No robotics/direct-actuation capability is granted to this actor —
  by design, matching the "operations coordination only" charter.
