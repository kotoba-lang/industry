# ADR-2607154200: cloud-itonami-isic-1610 (Sawmilling and planing of wood) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607153500 (cloud-itonami-isic-0220 Logging coverage, closest domain analog)

## Context

ISIC class 1610 (Sawmilling and planing of wood) is a fresh scaffold —
no prior repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-1610` before this ADR (checked and
confirmed empty via `gh repo view` before starting).

Unlike 0210 (Silviculture) and 0220 (Logging), which coordinate FIELD
operations against a forest site under a permit, 1610 coordinates
PLANT operations: a fixed sawmill facility with saws, planers and
kilns processing logs into graded, dried lumber. The closest domain
analog is still 0220 (back-office coordination of a heavy-equipment,
safety-critical operation with a physical actuation boundary the actor
must never cross), but the central ground-truth entity here is a
**production batch** moving through a **plant** rather than a
**site** being harvested under a **permit**, and the "must be
independently verified/registered before any action" HARD invariant
applies to TWO distinct entity kinds (equipment units and production
batches) rather than one.

This vertical is SELF-CONTAINED — no `kotoba-lang/sawmilling` library
exists, so domain logic (equipment/batch verification, shipment-volume
recompute, lumber-grade validation, moisture-content plausibility
validation) lives as pure functions in `sawmilling.registry` and is
re-verified independently by the governor, mirroring the discipline
established by 0220's `logging.registry` and every prior sibling
actor.

## Decision

Build `cloud-itonami-isic-1610` from scratch as a governed-actor
implementation of the sawmilling blueprint, following the langgraph
StateGraph + independent Governor + Phase 0->3 rollout architecture
established across the fleet:

1. **SawmillAdvisor** (`sawmilling.advisor`, sealed intelligence node):
   proposes plant-operations coordination actions only, never commits
   - `:log-production-batch` — lumber-grade/volume/moisture-content data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — saw-blade/planer/kiln maintenance scheduling proposal
   - `:flag-safety-concern` — surface an equipment/kiln-fire/dust-hazard concern (always escalates)
   - `:coordinate-shipment` — outbound lumber shipment coordination proposal

2. **Sawmill Plant Operations Governor** (`sawmilling.governor`,
   independent validation layer, never trusts the advisor's own
   self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into ten concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct saw/planer/kiln-equipment control);
     `:finalize? true` on a maintenance schedule (finalizing a kiln
     schedule) is a PERMANENT block; a shipment may not push a batch's
     own recorded shipped volume past its own logged production volume
     (independently recomputed); no double-scheduling the same
     maintenance record; no fabricated `:grade` value; no physically
     implausible `:moisture-content-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — saw-blade
   injury risk, kiln-fire risk, airborne wood-dust hazard):
   - Does NOT control saw blades, planers, or kiln equipment directly
   - Does NOT make plant-safety or hazard decisions (exclusive to the human plant supervisor)
   - Does NOT authorize/finalize a kiln schedule (permanently blocked, not a
     rollout milestone still to come — see `sawmilling.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `sawmilling.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-volume-
   exceeded?`, `grade-valid?`, `moisture-content-valid?`) are
   re-verified independently by the governor, following the "ground
   truth, not self-report" discipline established by prior actors
   (most directly `cloud-itonami-isic-0220`'s `logging.registry`).

5. **Store** (`sawmilling.store`): a single `MemStore` backend behind
   a `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 0220,
   this build does NOT ship a second Datomic-backed store — a second
   backend can be added later behind the same protocol without
   changing any caller.

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked
   via `langgraph.graph/run*`, not `.invoke`), append-only audit
   ledger, full test coverage, demo driver. Full module set:
   `deps.edn`, `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later),
   `README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
   `CONTRIBUTING.md`, `SECURITY.md`, `docs/adr/0001-architecture.md`.
   All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-1610` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Sawmill plant-operations back-office coordination is now genuinely
implemented and tested (not merely scaffolded). ISIC 1610 moves from
`:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or kiln-schedule finalization, independently corroborated by
`sawmilling.phase`'s permanent exclusion of `:schedule-maintenance`
from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely sawmilling-specific
elaboration beyond a straight port of 0220's single site/permit gate —
this domain actually has two distinct ground-truth entity kinds a
proposal can reference, and each is independently re-derived from its
own permanent record, never trusting the proposal's self-report.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 71 tests / 197 assertions
across 5 test namespaces (`sawmilling.operation-test`,
`sawmilling.governor-contract-test`, `sawmilling.phase-test`,
`sawmilling.store-contract-test`, `sawmilling.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-1610` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-1610`, initial commit
  `e528229eb7f842b363822b39a7919ccda7d1f639` (confirmed via
  `git merge-base --is-ancestor` against `origin/main`).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 71 tests containing 197 assertions. 0 failures, 0 errors.`**
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-volume-exceeded,
  kiln-finalize-blocked, already-scheduled, invalid-grade,
  invalid-moisture-content), with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:sawmill-plant-operations-
  governor` is grep-verified UNIQUE fleet-wide (`gh search code
  "sawmill" --owner cloud-itonami`, zero hits before this repo was
  created).
- `kotoba-lang/industry` registry entry for `"1610"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "1610" ...}` block (no wholesale
  regeneration) — see the registry commit/merge SHA recorded alongside
  this ADR's landing, and the post-merge re-verification re-run of
  `clojure -M:test` from an independent fresh clone.
