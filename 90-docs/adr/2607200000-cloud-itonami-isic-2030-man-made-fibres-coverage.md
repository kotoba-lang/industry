# ADR-2607200000: cloud-itonami-isic-2030 (Manufacture of man-made fibres) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607171200 (cloud-itonami-isic-2013 Plastics and synthetic rubber in primary forms coverage, closest domain analog)

## Context

ISIC class 2030 (Manufacture of man-made fibres) is a fresh scaffold —
no prior repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-2030` before this ADR (checked and
confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-2030`
before starting). The `kotoba-lang/industry` registry entry's own
`:name` for `{:id "2030" ...}` was verified fresh from a clean clone
to read exactly "Manufacture of man-made fibres" before any code was
written, per this fleet's mislabeling-prevention protocol.

The closest domain analog is `cloud-itonami-isic-2013` (Manufacture of
plastics and synthetic rubber in primary forms): both are upstream
polymer-process PLANTS producing a PRIMARY FORM (resin pellets/
granules/latex vs. fibre filament/staple/tow) rather than a finished
downstream product, and both share the same governed-actor shape (a
verified/registered equipment+batch gate and a permanent
equipment-actuation block). 2030 is genuinely distinct, however:
2013's plant polymerizes/compounds monomers into resin/rubber via
polymerization/compounding reactors, while 2030's plant spins/
extrudes/draws a synthetic polymer melt/solution (polyester, nylon,
acrylic, polypropylene, spandex, aramid) or a regenerated-cellulose
dope (viscose rayon, acetate, lyocell, modal, cupro) into continuous
filament, staple fibre, or tow via spinning/extrusion lines. 2013's
central hazard is monomer exposure / exothermic polymerization
runaway; 2030's is chemical-solvent exposure (e.g. carbon disulfide in
viscose wet-spinning, DMF in dry-spinning of acrylic or spandex) and
spinning/extrusion-line thermal/mechanical hazard.

This vertical is SELF-CONTAINED — no `kotoba-lang/fibremfg` library
exists, so domain logic (equipment/batch verification, shipment-weight
recompute, fibre-type validation, denier plausibility validation)
lives as pure functions in `fibremfg.registry` and is re-verified
independently by the governor, mirroring the discipline established by
2013's `resinmfg.registry` and every prior sibling actor.

## Decision

Build `cloud-itonami-isic-2030` from scratch as a governed-actor
implementation of the man-made-fibre blueprint, following the
langgraph StateGraph + independent Governor + Phase 0->3 rollout
architecture established across the fleet:

1. **FibreAdvisor** (`fibremfg.advisor`, sealed intelligence node):
   proposes plant-operations coordination actions only, never commits
   - `:log-production-batch` — spinning/extrusion batch, denier/tenacity, output-quality data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — spinning/extrusion-line maintenance scheduling proposal
   - `:flag-safety-concern` — surface a chemical-hazard (solvent exposure)/equipment-safety concern (always escalates)
   - `:coordinate-shipment` — outbound fibre shipment coordination proposal

2. **Man-Made Fibre Plant Operations Governor** (`fibremfg.governor`,
   independent validation layer, never trusts the advisor's own
   self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into ten concrete checks): plant/batch record
     (equipment for maintenance, batch for shipment) must be
     independently verified/registered before any action is taken
     against it; the request's own `:effect` must be `:propose`;
     `:op` must be in the closed four-op allowlist; the proposal's own
     `:effect` must be one of the four propose-shaped effects (no
     direct spinning/extrusion-line-equipment control);
     `:actuate-line? true` on a maintenance schedule is a PERMANENT,
     unconditional block; a shipment may not push a batch's own
     recorded shipped weight past its own logged production weight
     (independently recomputed); no double-scheduling the same
     maintenance record; no fabricated `:fibre-type` value; no
     physically implausible `:denier` value
   - ESCALATE (human sign-off, overridable): `:flag-safety-concern`
     always escalates regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — chemical-
   solvent exposure, spinning/extrusion-line thermal/mechanical
   hazard):
   - Does NOT control spinning or extrusion-line equipment directly
   - Does NOT make plant-safety or chemical-safety decisions (exclusive to the human plant supervisor)
   - Does NOT actuate the spinning/extrusion line (permanently blocked, not a
     rollout milestone still to come — see `fibremfg.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `fibremfg.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-weight-
   exceeded?`, `fibre-type-valid?`, `denier-valid?`) are re-verified
   independently by the governor, following the "ground truth, not
   self-report" discipline established by prior actors (most directly
   `cloud-itonami-isic-2013`'s `resinmfg.registry`).

5. **Store** (`fibremfg.store`): a single `MemStore` backend behind
   a `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 2013,
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
   `github.com/cloud-itonami/cloud-itonami-isic-2030` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Man-made-fibre plant-operations back-office coordination is now
genuinely implemented and tested (not merely scaffolded). ISIC 2030
moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or spinning/extrusion-line actuation, independently
corroborated by `fibremfg.phase`'s permanent exclusion of
`:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely fibre-specific
elaboration mirroring 2013's own equipment+batch gate structure — this
domain has two distinct ground-truth entity kinds a proposal can
reference, each independently re-derived from its own permanent
record, never trusting the proposal's self-report.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 71 tests / 202 assertions
across 5 test namespaces (`fibremfg.operation-test`,
`fibremfg.governor-contract-test`, `fibremfg.phase-test`,
`fibremfg.store-contract-test`, `fibremfg.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2030` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-2030`, initial commit
  `8d99332fb78ccc674301afe79348baacd3223c52` (confirmed via
  `gh api repos/cloud-itonami/cloud-itonami-isic-2030/commits/main`
  matching the local commit SHA).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 71 tests containing 202 assertions. 0 failures, 0 errors.`**
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-weight-exceeded,
  line-actuate-blocked, already-scheduled, invalid-fibre-type,
  invalid-denier), with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword
  `:man-made-fibre-plant-operations-governor` is grep-verified UNIQUE
  fleet-wide (`gh search code "man-made-fibre-plant-operations-governor"
  --owner cloud-itonami`, zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"2030"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2030" ...}` block (no wholesale
  regeneration) — see the registry commit/merge SHA recorded alongside
  this ADR's landing, and the post-merge re-verification re-run of
  `clojure -M:test` from an independent fresh clone.
