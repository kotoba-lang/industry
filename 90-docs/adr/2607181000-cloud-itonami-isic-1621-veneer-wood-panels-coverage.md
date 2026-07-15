# ADR-2607181000: cloud-itonami-isic-1621 (Manufacture of veneer sheets and wood-based panels) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607154200 (cloud-itonami-isic-1610 Sawmilling and planing of wood coverage, mirrored reference implementation)

## Context

cloud-itonami codifies every ISIC industry class as an autonomous
"actor" (LLM/advisor behind an independent Governor, langgraph-clj
StateGraph, append-only audit ledger). This ADR promotes ISIC Rev.5
class 1621 (Manufacture of veneer sheets and wood-based panels) from a
registry `:spec` placeholder to a real, tested implementation, as part
of an ongoing careful, smaller-batch rollout after a prior 18-agent
haiku batch had a 61% defect rate. This is one of 42+ consecutive
agents on the stricter protocol (capable model + mandatory
verification) to succeed since that regression.

ISIC class 1621 is a fresh scaffold — no prior repo or reverted
attempt existed at `cloud-itonami/cloud-itonami-isic-1621` before this
ADR (checked and confirmed 404 via `gh api
repos/cloud-itonami/cloud-itonami-isic-1621` before starting).

Before any implementation work, the registry entry was independently
re-verified fresh (cloned `kotoba-lang/industry` to a uniquely-named
scratch dir, not the shared checkout) to confirm `{:id "1621" :name
"Manufacture of veneer sheets and wood-based panels" ...}` is
genuinely what is registered — this fleet has previously seen agents
mislabel their assigned ISIC class (0892 assumed=salt, actually peat;
0144 assumed=swine, actually sheep-goats), so this check is mandatory,
not optional. Confirmed correct.

Unlike 0210/0220 (Silviculture/Logging), which coordinate FIELD
operations against a forest site under a permit, 1621 coordinates
PLANT operations, same as its closest domain analog 1610 (Sawmilling
and planing of wood). Both are back-office coordination actors for
heavy wood-processing plant equipment with a real physical safety
dimension. 1621 differs from 1610 in one structural respect: 1610's
central process is size reduction (sawing/planing a log into
dimension lumber, kiln-dried); 1621's central process is sheet/panel
formation (peeling or slicing a log into veneer, then gluing/
laminating and pressing sheets into plywood, particleboard, MDF or
OSB). The central ground-truth entity is therefore a production batch
of veneer/panels (grade/volume/output-quality, not grade/volume/
moisture-content) moving through a plant with veneer-lathe/hot-press/
glue-spreader equipment (not saw/planer/kiln equipment) — the "must be
independently verified/registered before any action" HARD invariant
still applies to the SAME two distinct entity kinds (equipment units
and production batches), inherited unchanged from 1610.

This vertical is SELF-CONTAINED — no `kotoba-lang/veneerpanel` library
exists, so domain logic (equipment/batch verification, shipment-
volume recompute, panel/veneer-grade validation, output-quality
plausibility validation) lives as pure functions in
`veneerpanel.registry` and is re-verified independently by the
governor, mirroring the discipline established by 1610's
`sawmilling.registry` and every prior sibling actor.

## Decision

Build `cloud-itonami-isic-1621` from scratch as a governed-actor
implementation of the veneer/wood-panel blueprint, following the
langgraph StateGraph + independent Governor + Phase 0->3 rollout
architecture established across the fleet:

1. **VeneerPanelAdvisor** (`veneerpanel.advisor`, sealed intelligence
   node): proposes plant-operations coordination actions only, never
   commits
   - `:log-production-batch` — veneer/panel batch grade/thickness/output-quality data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — veneer-peeling/pressing/gluing-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface a formaldehyde-emission/equipment-safety/fire-hazard concern (always escalates)
   - `:coordinate-shipment` — outbound panel shipment coordination proposal

2. **Wood Panel Plant Operations Governor** (`veneerpanel.governor`,
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
     effects (no direct veneer-peeling/pressing/gluing-line-equipment
     control); `:finalize? true` on a maintenance schedule (finalizing
     a press-cycle run) is a PERMANENT block; a shipment may not push a
     batch's own recorded shipped volume past its own logged
     production volume (independently recomputed); no double-
     scheduling the same maintenance record; no fabricated `:grade`
     value; no physically implausible `:output-quality-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — veneer-
   lathe/rotary-knife injury risk, hot-press platen burn/crush risk,
   urea-/phenol-formaldehyde resin fume inhalation hazard, wood-dust
   fire/explosion hazard):
   - Does NOT control veneer-peeling, pressing, or gluing-line equipment directly
   - Does NOT make plant-safety or hazard decisions (exclusive to the human plant supervisor)
   - Does NOT authorize/finalize a press-cycle run (permanently blocked, not a
     rollout milestone still to come — see `veneerpanel.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `veneerpanel.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-volume-
   exceeded?`, `grade-valid?`, `output-quality-valid?`) are
   re-verified independently by the governor, following the "ground
   truth, not self-report" discipline established by prior actors
   (most directly `cloud-itonami-isic-1610`'s `sawmilling.registry`).
   Grade set (`:veneer-a`/`:veneer-b`/`:veneer-c`/`:veneer-d`/
   `:structural-i`/`:structural-ii`/`:osb-3`/`:mdf-standard`) is
   informed by US HPVA veneer face/back grading, APA structural-panel
   rating, EN 300 OSB class and EN 622 MDF class.

5. **Store** (`veneerpanel.store`): a single `MemStore` backend behind
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
   `github.com/cloud-itonami/cloud-itonami-isic-1621` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Wood-panel plant-operations back-office coordination is now
genuinely implemented and tested (not merely scaffolded). ISIC 1621
moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or press-cycle finalization, independently corroborated by
`veneerpanel.phase`'s permanent exclusion of `:schedule-maintenance`
from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) carry over unchanged from 1610's own
elaboration — this domain has the same two distinct ground-truth
entity kinds a proposal can reference, and each is independently
re-derived from its own permanent record, never trusting the
proposal's self-report.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 71 tests / 197 assertions
across 5 test namespaces (`veneerpanel.operation-test`,
`veneerpanel.governor-contract-test`, `veneerpanel.phase-test`,
`veneerpanel.store-contract-test`, `veneerpanel.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification (e.g. actual formaldehyde-emission sensor readings
against CARB Phase 2 / EPA TSCA Title VI thresholds).

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-1621` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-1621`, initial commit
  `52c224cc809f4711afc339af61f71e16b8ace105` (confirmed as the tip of
  `origin/main` via `gh api repos/cloud-itonami/
  cloud-itonami-isic-1621/commits/main`).
- Scaffolded and tested from a uniquely-named scratch dir
  (`/tmp/1621-scratch/orgs/cloud-itonami/cloud-itonami-isic-1621`,
  mirrored under an `orgs/cloud-itonami/` ↔ `orgs/kotoba-lang/`
  sibling layout so `deps.edn`'s `:local/root` paths resolve against
  the existing local `kotoba-lang/langgraph` and `kotoba-lang/
  langchain` checkouts read-only), never the shared superproject
  checkout.
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 71 tests containing 197 assertions. 0 failures, 0 errors.`**
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-volume-exceeded,
  press-finalize-blocked, already-scheduled, invalid-grade,
  invalid-output-quality), with no exceptions (exit 0).
- All source is `.cljc` (portable, grepped clean of JVM-only interop);
  the actor graph is invoked exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:wood-panel-plant-operations-
  governor` is grep-verified UNIQUE fleet-wide (`gh search code
  "wood-panel-plant-operations-governor" --owner cloud-itonami`, zero
  hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"1621"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "1621" ...}` block (no wholesale
  regeneration) — see the registry commit/merge SHA recorded alongside
  this ADR's landing, and the post-merge re-verification re-run of
  `clojure -M:test` from an independent fresh clone.
