# ADR-2608210000: cloud-itonami-isic-1709 (Manufacture of other articles of paper and paperboard) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607161600 (cloud-itonami-isic-1701 Manufacture of pulp, paper and paperboard coverage), ADR-2608110000 (cloud-itonami-isic-1702 Manufacture of corrugated paper and paperboard and of containers of paper and paperboard coverage, closest domain analog)

## Context

ISIC class 1709 (Manufacture of other articles of paper and
paperboard) is a fresh scaffold — no prior repo or reverted attempt
existed at `cloud-itonami/cloud-itonami-isic-1709` before this ADR
(confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-1709`
before starting). The `kotoba-lang/industry` registry entry was
independently re-verified against a fresh clone before any work
began: `{:id "1709" ...}` in the live registry has `:name` literally
`"Manufacture of other articles of paper and paperboard"` (exact
match, not truncated, not a mismatched premise), but the registry
entry's own `:repo`/`:business-id` fields were a stale placeholder
(`https://github.com/gftdcojp/cloud-itonami-C1709` /
`cloud-itonami-C1709`) that did not match this fleet's actual naming
convention (`cloud-itonami/cloud-itonami-isic-<id>` /
`cloud-itonami-isic-<id>`) — corrected as part of this task's own
exact-text edit of the `"1709"` block (the only entry touched).

ISIC 1709 is structurally different from every prior paper-vertical
sibling in this fleet: it is a RESIDUAL "not elsewhere classified"
category (paper tableware, filter paper, wallpaper, and similar
converted paper goods not covered by 1701 pulp/paper/paperboard or
1702 corrugated paper/paperboard containers), not a single product
line. This build resolves that by picking ONE concrete illustration —
molded-pulp/paperboard paper-tableware manufacturing (paper plates,
bowls and trays formed on a pulp-molding press; coated and uncoated
paperboard cups finished on a die-cutting/folding line) — documented
plainly in the README, while keeping the domain logic's closed grade
allowlist (`paperarticles.registry/valid-product-grades`) broad enough
to include representative grades from filter paper and wallpaper base
stock too, so the actor's applicability across the category is visible
in code, not just asserted in prose.

The closest domain analogs are `cloud-itonami-isic-1701` (Manufacture
of pulp, paper and paperboard) and `cloud-itonami-isic-1702`
(Manufacture of corrugated paper and paperboard and of containers of
paper and paperboard): all three are back-office coordination actors
for a fixed processing PLANT (not a field site) with converting-line
equipment, a real physical-safety dimension, and a central
ground-truth **production batch** entity independently gated alongside
an **equipment** entity. Like 1702 (and unlike 1701's chemical/
mechanical pulping process, which carries a distinct, separately-
regulated environmental-discharge concern), this task's brief calls
for exactly ONE permanent block ("any proposal touching converting-
line-equipment control is a hard, permanent block"), not 1701's two
independent permanent blocks.

This vertical is SELF-CONTAINED — no `kotoba-lang/paperarticles`
library exists (verified), so domain logic (equipment/batch
verification, shipment-quantity recompute, product-grade validation,
basis-weight (grammage) and moisture-content plausibility validation)
lives as pure functions in `paperarticles.registry` and is re-verified
independently by the governor, mirroring the discipline established by
1701's `pulppaper.registry` and 1702's `corrugated.registry`.

## Decision

Build `cloud-itonami-isic-1709` from scratch as a governed-actor
implementation of the paper-articles-n.e.c. blueprint, following the
langgraph StateGraph + independent Governor + Phase 0->3 rollout
architecture established across the fleet:

1. **PaperArticlesAdvisor** (`paperarticles.advisor`, sealed
   intelligence node): proposes plant-operations coordination actions
   only, never commits
   - `:log-production-batch` — converting/forming batch, output-quality (basis-weight, moisture-content) data logging
   - `:schedule-maintenance` — converting-line-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface an equipment-safety/quality-defect concern (always escalates)
   - `:coordinate-shipment` — outbound product shipment coordination proposal

2. **Paper Articles Plant Operations Governor**
   (`paperarticles.governor`, independent validation layer, never
   trusts the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into ten concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects — any proposal touching converting-line-equipment control
     is PERMANENTLY blocked; a shipment may not push a batch's own
     recorded shipped quantity past its own logged production quantity
     (independently recomputed); no double-scheduling the same
     maintenance record; no fabricated `:grade` value; no physically
     implausible `:basis-weight-gsm` or `:moisture-content-pct` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary**:
   - Does NOT control the pulp-molding press, die-cutter, or any converting-line equipment directly
   - Does NOT make plant-safety or quality-defect-disposition decisions (exclusive to the human plant supervisor)
   - All proposals are `:effect :propose`; actuation is human-approval-gated
   - `:schedule-maintenance` is never a member of any phase's `:auto` set (see `paperarticles.phase`)

4. **Self-contained domain logic**: `paperarticles.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-quantity-
   exceeded?`, `grade-valid?`, `basis-weight-valid?`, `moisture-
   content-valid?`) are re-verified independently by the governor,
   following the "ground truth, not self-report" discipline
   established by prior actors (most directly `cloud-itonami-isic-
   1701`'s `pulppaper.registry` and `cloud-itonami-isic-1702`'s
   `corrugated.registry`).

5. **Store** (`paperarticles.store`): a single `MemStore` backend
   behind a `Store` protocol, tracking four entity kinds (batches,
   equipment, maintenance, shipments) plus the append-only ledger.
   Like 1701/1702, this build does NOT ship a second Datomic-backed
   store — a second backend can be added later behind the same
   protocol without changing any caller.

6. **A direct governor-level test for the permanent equipment-control
   block**: because the deterministic mock advisor always emits a
   fixed `:effect` per op, `equipment-control-blocked-violations` is
   not reachable via the normal actor-graph path with the shipped
   advisor — it is exercised directly against
   `paperarticles.governor/check` in
   `equipment-control-blocked-is-held-and-permanently-blocked`, the
   same way a compromised/hallucinating advisor's output would be
   censored (mirroring 1701's and 1702's own unreached generic-
   allowlist checks).

7. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked
   via `langgraph.graph/run*`, not `.invoke`), append-only audit
   ledger, full test coverage, demo driver. Full module set:
   `deps.edn`, `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later),
   `README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
   `CONTRIBUTING.md`, `SECURITY.md`, `docs/adr/0001-architecture.md`.
   All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-1709` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Paper-articles-n.e.c. plant-operations back-office coordination is
now genuinely implemented and tested (not merely scaffolded). ISIC
1709 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation, independently corroborated by `paperarticles.phase`'s
permanent exclusion of `:schedule-maintenance` from every phase's
`:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) mirror 1701's/1702's own dual-gate
structure — this domain also has two distinct ground-truth entity
kinds a proposal can reference, and each is independently re-derived
from its own permanent record, never trusting the proposal's
self-report.

(+) Deliberately scoped to a SINGLE permanent block (unlike 1701's
two), matching this task brief precisely rather than importing 1701's
effluent-discharge axis, which has no domain analog here.

(+) Quality-plausibility discipline is explicit: basis-weight
(grammage) and moisture-content readings are independently
range-checked, never trusted as self-reported sensor data.

(+) The residual/n.e.c. ISIC category is resolved concretely (paper
tableware as the chosen illustration) without narrowing the actor's
applicability to the category's other product lines (filter paper,
wallpaper) — the grade allowlist spans all three.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 76 tests / 210 assertions
across 5 test namespaces (`paperarticles.operation-test`,
`paperarticles.governor-contract-test`, `paperarticles.phase-test`,
`paperarticles.store-contract-test`, `paperarticles.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-1709` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-1709`, initial commit
  `f4861a17cfc1fc95862ab7ca9013cc8ebdabad9d` (confirmed matches
  `origin/main` HEAD via the GitHub API immediately after push).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`),
  run from the pushed build directory:
  **`Ran 76 tests containing 210 assertions. 0 failures, 0 errors.`**
  Independently re-run from a brand-new fresh clone after the registry
  merge landed (see the registry commit/merge SHA below) — see the
  raw re-verification output recorded alongside this task's own
  report.
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-quantity-
  exceeded, already-scheduled, invalid-grade, invalid-basis-weight,
  invalid-moisture-content), plus a direct governor-level check for
  equipment-control-blocked, with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:paper-articles-plant-
  operations-governor` is grep-verified UNIQUE fleet-wide (`gh search
  code "paper-articles-plant-operations-governor" --owner
  cloud-itonami`, zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"1709"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "1709" ...}` block (no wholesale
  regeneration; the block's own stale `:repo`/`:business-id` fields
  were also corrected to the fleet's actual naming convention as part
  of this same exact-text edit) — see the registry commit/merge SHA
  recorded alongside this ADR's landing, and the post-merge
  re-verification re-run of `clojure -M:test` from an independent
  fresh clone.
