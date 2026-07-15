# ADR-2607181100: cloud-itonami-isic-1622 (Manufacture of builders' carpentry and joinery) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607181000 (cloud-itonami-isic-1621 Manufacture of veneer sheets and wood-based panels coverage, mirrored reference implementation); ADR-2607154200 (cloud-itonami-isic-1610 Sawmilling and planing of wood coverage)

## Context

cloud-itonami codifies every ISIC industry class as an autonomous
"actor" (LLM/advisor behind an independent Governor, langgraph-clj
StateGraph, append-only audit ledger). This ADR promotes ISIC Rev.5
class 1622 (Manufacture of builders' carpentry and joinery) from a
registry `:spec` placeholder to a real, tested implementation, as part
of an ongoing careful, smaller-batch rollout after a prior 18-agent
haiku batch had a 61% defect rate. This is one of 48+ consecutive
agents on the stricter protocol (capable model + mandatory
verification) to succeed since that regression.

ISIC class 1622 is a fresh scaffold — no prior repo or reverted
attempt existed at `cloud-itonami/cloud-itonami-isic-1622` before this
ADR (checked and confirmed 404 via `gh api
repos/cloud-itonami/cloud-itonami-isic-1622` before starting).

Before any implementation work, the registry entry was independently
re-verified fresh (cloned `kotoba-lang/industry` to a uniquely-named
scratch dir, not the shared checkout) to confirm `{:id "1622" :name
"Manufacture of builders' carpentry and joinery" ...}` is genuinely
what is registered — this fleet has previously seen agents mislabel
their assigned ISIC class (0892 assumed=salt, actually peat; 0144
assumed=swine, actually sheep-goats), so this check is mandatory, not
optional. Confirmed correct.

Like 1610 (Sawmilling and planing of wood) and 1621 (veneer sheets and
wood-based panels), 1622 coordinates PLANT operations for a heavy
wood-processing facility with a real physical safety dimension. 1622
differs from both in one structural respect: 1610's central process is
size reduction (sawing/planing a log into dimension lumber) and 1621's
central process is sheet/panel formation (peeling/slicing a log into
veneer, then pressing sheets into panels); 1622's central process is
**assembly/joinery of finished building components** — millwork:
cutting, mortise-and-tenon or dowel joinery, and finishing of doors,
window frames, staircases and structural wood components (e.g.
glue-laminated beams) manufactured for construction. The central
ground-truth entity is therefore a production batch of millwork units
(dimensional-spec/unit-count/output-quality, not grade/volume) moving
through a shop with panel-saw/CNC-router/tenoning-machine/edge-bander/
finishing-line equipment (not saw/planer/kiln or veneer-lathe/hot-
press/glue-spreader equipment) — the "must be independently verified/
registered before any action" HARD invariant still applies to the SAME
two distinct entity kinds (equipment units and production batches),
inherited unchanged from 1610 and 1621.

This vertical is SELF-CONTAINED — no `kotoba-lang/millwork` library
exists, so domain logic (equipment/batch verification, shipment-
unit-count recompute, dimensional-spec validation, output-quality
plausibility validation) lives as pure functions in
`millwork.registry` and is re-verified independently by the governor,
mirroring the discipline established by 1621's `veneerpanel.registry`
and every prior sibling actor.

## Decision

Build `cloud-itonami-isic-1622` from scratch as a governed-actor
implementation of the builders'-carpentry-and-joinery blueprint,
following the langgraph StateGraph + independent Governor + Phase 0->3
rollout architecture established across the fleet:

1. **MillworkAdvisor** (`millwork.advisor`, sealed intelligence node):
   proposes plant-operations coordination actions only, never commits
   - `:log-production-batch` — millwork batch dimensional-spec/output-quality data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — cutting/joinery/finishing-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface a materials-safety/equipment-safety/dust-hazard concern (always escalates)
   - `:coordinate-shipment` — outbound millwork shipment coordination proposal

2. **Millwork Shop Plant Operations Governor** (`millwork.governor`,
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
     effects (no direct cutting/joinery/finishing-line-equipment
     control); `:finalize? true` on a maintenance schedule (finalizing
     a cutting/joinery/finishing-line run) is a PERMANENT block; a
     shipment may not push a batch's own recorded shipped unit count
     past its own logged production unit count (independently
     recomputed); no double-scheduling the same maintenance record; no
     fabricated `:dimensional-spec` value; no physically implausible
     `:output-quality-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — panel-saw
   and CNC-router blade/cutter injury risk, tenoning/mortising-machine
   crush/entrapment risk, wood-dust fire/explosion hazard, finishing-
   line solvent/coating fume inhalation hazard):
   - Does NOT control cutting, joinery, or finishing-line equipment directly
   - Does NOT make plant-safety or hazard decisions (exclusive to the human plant supervisor)
   - Does NOT authorize/finalize a cutting/joinery/finishing-line run
     (permanently blocked, not a rollout milestone still to come — see
     `millwork.phase`: `:schedule-maintenance` is never a member of any
     phase's `:auto` set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `millwork.registry` pure functions
   (`equipment-ready?`, `batch-ready?`, `shipment-unit-count-
   exceeded?`, `dimensional-spec-valid?`, `output-quality-valid?`) are
   re-verified independently by the governor, following the "ground
   truth, not self-report" discipline established by prior actors
   (most directly `cloud-itonami-isic-1621`'s `veneerpanel.registry`).
   Dimensional-spec set (`:door-single-leaf`/`:door-double-leaf`/
   `:window-casement`/`:window-fixed-pane`/`:staircase-straight-
   flight`/`:structural-glulam-beam`) spans the product range ISIC
   1622 covers.

5. **Store** (`millwork.store`): a single `MemStore` backend behind a
   `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 1610 and
   1621, this build does NOT ship a second Datomic-backed store — a
   second backend can be added later behind the same protocol without
   changing any caller.

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked
   via `langgraph.graph/run*`, not `.invoke`), append-only audit
   ledger, full test coverage, demo driver. Full module set:
   `deps.edn`, `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later),
   `README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
   `CONTRIBUTING.md`, `SECURITY.md`, `docs/adr/0001-architecture.md`.
   All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-1622` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Builders'-carpentry-and-joinery (millwork) plant-operations
back-office coordination is now genuinely implemented and tested (not
merely scaffolded). ISIC 1622 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or cutting/joinery/finishing-line-run finalization,
independently corroborated by `millwork.phase`'s permanent exclusion
of `:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) carry over unchanged from 1610's and
1621's own elaboration — this domain has the same two distinct
ground-truth entity kinds a proposal can reference, and each is
independently re-derived from its own permanent record, never trusting
the proposal's self-report.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 71 tests / 195 assertions
across 5 test namespaces (`millwork.operation-test`,
`millwork.governor-contract-test`, `millwork.phase-test`,
`millwork.store-contract-test`, `millwork.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification (e.g. actual wood-dust concentration sensor readings
against OSHA/NIOSH combustible-dust thresholds).

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-1622` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-1622`, initial commit
  `5e807b900bb902c5d35589b43381e9e3e162677e` (confirmed as the tip of
  `origin/main` via `gh api repos/cloud-itonami/
  cloud-itonami-isic-1622/commits/main`).
- Scaffolded and tested from a uniquely-named scratch dir (containing
  "1622" in its path, mirrored under a `cloud-itonami/` ↔
  `kotoba-lang/` sibling layout so `deps.edn`'s `:local/root` paths
  resolve against freshly-cloned, read-only `kotoba-lang/langgraph`
  and `kotoba-lang/langchain` checkouts), never the shared superproject
  checkout.
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 71 tests containing 195 assertions. 0 failures, 0 errors.`**
  Re-run from a completely independent second fresh clone of the
  pushed repo (plus fresh `kotoba-lang/langgraph`/`langchain`
  siblings): same result.
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified,
  shipment-unit-count-exceeded, cutting-line-finalize-blocked,
  already-scheduled, invalid-dimensional-spec, invalid-output-quality),
  with no exceptions (exit 0).
- All source is `.cljc` (portable, grepped clean of JVM-only interop);
  the actor graph is invoked exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:millwork-shop-plant-
  operations-governor` is grep-verified UNIQUE fleet-wide (`gh search
  code "millwork-shop-plant-operations-governor" --owner
  cloud-itonami`, zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"1622"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "1622" ...}` block (no wholesale
  regeneration) — see the registry commit/merge SHA recorded alongside
  this ADR's landing, and the post-merge re-verification re-run of
  `clojure -M:test` from an independent fresh clone.
