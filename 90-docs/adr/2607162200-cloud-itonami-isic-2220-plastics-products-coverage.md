# ADR-2607162200: cloud-itonami-isic-2220 (Manufacture of plastics products) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607154200 (cloud-itonami-isic-1610 Sawmilling coverage, closest domain analog)

## Context

ISIC class 2220 (Manufacture of plastics products) is a fresh scaffold
— no prior repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-2220` before this ADR (checked and
confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-2220`
before starting). The `kotoba-lang/industry` registry entry `{:id
"2220" :name "Manufacture of plastics products" ...}` was verified
byte-for-byte from a fresh read-only clone before any code was written
(this fleet has previously mislabeled an assigned ISIC class from
memory rather than reading the registry — e.g. 0892 assumed salt,
actually peat).

The closest domain analog is `cloud-itonami-isic-1610` (Sawmilling and
planing of wood): both are back-office coordination actors for a
fixed processing PLANT with heavy manufacturing equipment and a real
physical safety dimension (1610: saw-blade injury/kiln-fire/wood-dust
hazard; 2220: injection-molding/extrusion/blow-molding thermal-and-
pressure hazard, resin fume/VOC hazard). This build mirrors 1610's
architecture closely — same four-op shape, same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination), same permanent-block
pattern for a proposal that would directly actuate the production
line (1610: `:finalize? true` on a kiln schedule; 2220:
`:actuate-line? true` on the molding/extrusion line).

This vertical is SELF-CONTAINED — no `kotoba-lang/plasticsmfg` library
exists, so domain logic (equipment/batch verification, shipment-weight
recompute, resin-type validation, reject-rate plausibility
validation) lives as pure functions in `plasticsmfg.registry` and is
re-verified independently by the governor, mirroring the discipline
established by 1610's `sawmilling.registry` and every prior sibling
actor.

## Decision

Build `cloud-itonami-isic-2220` from scratch as a governed-actor
implementation of the plastics-products-manufacturing blueprint,
following the langgraph StateGraph + independent Governor + Phase
0->3 rollout architecture established across the fleet:

1. **PlasticsAdvisor** (`plasticsmfg.advisor`, sealed intelligence
   node): proposes plant-operations coordination actions only, never
   commits
   - `:log-production-batch` — resin-type/weight/reject-rate data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — injection-molding/extrusion/blow-molding-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface a materials-safety/fume-hazard/equipment-safety concern (always escalates)
   - `:coordinate-shipment` — outbound plastics-product shipment coordination proposal

2. **Plastics Plant Operations Governor** (`plasticsmfg.governor`,
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
     effects (no direct molding/extrusion/blow-molding-line-equipment
     control); `:actuate-line? true` on a maintenance schedule
     (directly actuating the molding/extrusion line) is a PERMANENT
     block; a shipment may not push a batch's own recorded shipped
     weight past its own logged production weight (independently
     recomputed); no double-scheduling the same maintenance record; no
     fabricated `:resin-type` value; no physically implausible
     `:reject-rate-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — molding/
   extrusion thermal-and-pressure hazard, resin fume/VOC respiratory
   hazard):
   - Does NOT control injection-molding, extrusion, or blow-molding line equipment directly
   - Does NOT make plant-safety or materials-safety decisions (exclusive to the human plant supervisor)
   - Does NOT actuate the molding/extrusion line (permanently blocked,
     not a rollout milestone still to come — see `plasticsmfg.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `plasticsmfg.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-weight-
   exceeded?`, `resin-type-valid?`, `reject-rate-valid?`) are
   re-verified independently by the governor, following the "ground
   truth, not self-report" discipline established by prior actors
   (most directly `cloud-itonami-isic-1610`'s `sawmilling.registry`).

5. **Store** (`plasticsmfg.store`): a single `MemStore` backend behind
   a `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 1610,
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
   `github.com/cloud-itonami/cloud-itonami-isic-2220` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Plastics-products plant-operations back-office coordination is
now genuinely implemented and tested (not merely scaffolded). ISIC
2220 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or line actuation, independently corroborated by
`plasticsmfg.phase`'s permanent exclusion of `:schedule-maintenance`
from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely plastics-manufacturing-
specific elaboration mirroring 1610's own two-entity-kind gate — this
domain has two distinct ground-truth entity kinds a proposal can
reference, and each is independently re-derived from its own
permanent record, never trusting the proposal's self-report.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 71 tests / 198 assertions
across 5 test namespaces (`plasticsmfg.operation-test`,
`plasticsmfg.governor-contract-test`, `plasticsmfg.phase-test`,
`plasticsmfg.store-contract-test`, `plasticsmfg.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2220` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-2220`, initial commit
  `e43c3481ced5aae98b96360f73a2b4ac538a91d1` (confirmed via
  `gh api repos/cloud-itonami/cloud-itonami-isic-2220/commits/main`
  matching the local HEAD SHA).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 71 tests containing 198 assertions. 0 failures, 0 errors.`**
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-weight-exceeded,
  line-actuate-blocked, already-scheduled, invalid-resin-type,
  invalid-reject-rate), with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:plastics-plant-operations-
  governor` is grep-verified UNIQUE fleet-wide (`gh search code
  "plastics-plant-operations-governor" --owner cloud-itonami`, zero
  hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"2220"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2220" ...}` block (no wholesale
  regeneration) — see the registry commit/merge SHA recorded alongside
  this ADR's landing, and the post-merge re-verification re-run of
  `clojure -M:test` from an independent fresh clone.
