# ADR-2607171200: cloud-itonami-isic-2013 (Manufacture of plastics and synthetic rubber in primary forms) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607162200 (cloud-itonami-isic-2220 Manufacture of plastics products coverage, closest domain analog)

## Context

ISIC class 2013 (Manufacture of plastics and synthetic rubber in
primary forms) is a fresh scaffold — no prior repo or reverted attempt
existed at `cloud-itonami/cloud-itonami-isic-2013` before this ADR
(checked and confirmed 404 via
`gh api repos/cloud-itonami/cloud-itonami-isic-2013` before starting).
The `kotoba-lang/industry` registry entry `{:id "2013" :name
"Manufacture of plastics and synthetic rubber in primary forms" ...}`
was verified byte-for-byte from a fresh read-only clone before any
code was written (this fleet has previously mislabeled an assigned
ISIC class from memory rather than reading the registry — e.g. 0892
assumed salt, actually peat; 0144 assumed swine, actually
sheep-goats).

The closest domain analog is `cloud-itonami-isic-2220` (Manufacture of
plastics products): both are back-office coordination actors for a
fixed processing PLANT with heavy manufacturing equipment and a real
physical safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). The two verticals are, however, distinct
plants at distinct points in the value chain: 2013 is the UPSTREAM
chemical-process plant (polymerization/compounding reactors producing
plastics resin and synthetic rubber as pellets/granules/powder/flake/
latex — a PRIMARY FORM), while 2220 is the DOWNSTREAM plant that
molds/extrudes/blow-molds those primary forms into finished products.
This build mirrors 2220's architecture closely but adapts the hazard
profile and equipment/product vocabulary to the upstream chemical-
process plant: 2013's central physical hazard is chemical (monomer
exposure, exothermic runaway-reaction risk during polymerization)
rather than 2220's thermal/pressure molding hazard; 2013's permanent
equipment-actuation block guards a polymerization/compounding REACTOR
(`:actuate-reactor?`) rather than a molding/extrusion LINE
(`:actuate-line?`); and 2013's production-batch record declares a
`:polymer-grade` (spanning both thermoplastic resins and synthetic
rubbers, per ISIC 2013's own combined scope) and an `:off-spec-rate-
percent`, rather than 2220's `:resin-type`/`:reject-rate-percent`
(2220's batch is always a finished thermoplastic product, never a
synthetic rubber).

This vertical is SELF-CONTAINED — no `kotoba-lang/resinmfg` library
exists, so domain logic (equipment/batch verification, shipment-weight
recompute, polymer-grade validation, off-spec-rate plausibility
validation) lives as pure functions in `resinmfg.registry` and is
re-verified independently by the governor, mirroring the discipline
established by `cloud-itonami-isic-2220`'s `plasticsmfg.registry` and
every prior sibling actor.

## Decision

Build `cloud-itonami-isic-2013` from scratch as a governed-actor
implementation of the primary-forms plastics-and-synthetic-rubber-
manufacturing blueprint, following the langgraph StateGraph +
independent Governor + Phase 0->3 rollout architecture established
across the fleet:

1. **ResinAdvisor** (`resinmfg.advisor`, sealed intelligence node):
   proposes plant-operations coordination actions only, never commits
   - `:log-production-batch` — polymer-grade/weight/off-spec-rate data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — polymerization/compounding-reactor maintenance scheduling proposal
   - `:flag-safety-concern` — surface a chemical-hazard (monomer exposure, exothermic-reaction risk)/equipment-safety concern (always escalates)
   - `:coordinate-shipment` — outbound resin/synthetic-rubber shipment coordination proposal

2. **Primary Forms Plant Operations Governor** (`resinmfg.governor`,
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
     effects (no direct reactor/polymerization-line-equipment
     control); `:actuate-reactor? true` on a maintenance schedule
     (directly actuating the polymerization/compounding reactor) is a
     PERMANENT block; a shipment may not push a batch's own recorded
     shipped weight past its own logged production weight
     (independently recomputed); no double-scheduling the same
     maintenance record; no fabricated `:polymer-grade` value; no
     physically implausible `:off-spec-rate-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — monomer
   exposure, exothermic runaway-reaction risk during polymerization,
   chemical process hazard):
   - Does NOT control polymerization or compounding reactor/line equipment directly
   - Does NOT make plant-safety or chemical-safety decisions (exclusive to the human plant supervisor)
   - Does NOT actuate the polymerization/compounding reactor (permanently blocked,
     not a rollout milestone still to come — see `resinmfg.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `resinmfg.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-weight-
   exceeded?`, `polymer-grade-valid?`, `off-spec-rate-valid?`) are
   re-verified independently by the governor, following the "ground
   truth, not self-report" discipline established by prior actors
   (most directly `cloud-itonami-isic-2220`'s `plasticsmfg.registry`).

5. **Store** (`resinmfg.store`): a single `MemStore` backend behind a
   `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 2220,
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
   `github.com/cloud-itonami/cloud-itonami-isic-2013` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Primary-forms plastics-and-synthetic-rubber plant-operations
back-office coordination is now genuinely implemented and tested (not
merely scaffolded). ISIC 2013 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or reactor actuation, independently corroborated by
`resinmfg.phase`'s permanent exclusion of `:schedule-maintenance` from
every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely primary-forms-
manufacturing-specific elaboration mirroring 2220's own two-entity-
kind gate — this domain has two distinct ground-truth entity kinds a
proposal can reference, and each is independently re-derived from its
own permanent record, never trusting the proposal's self-report.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 71 tests / 204 assertions
across 5 test namespaces (`resinmfg.operation-test`,
`resinmfg.governor-contract-test`, `resinmfg.phase-test`,
`resinmfg.store-contract-test`, `resinmfg.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2013` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-2013`, initial commit
  `43fd1f6e5d1e74e4e61389d782253b6dd3f2efea` (confirmed via
  `gh api repos/cloud-itonami/cloud-itonami-isic-2013/git/ref/heads/main`
  matching the local HEAD SHA).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 71 tests containing 204 assertions. 0 failures, 0 errors.`**
  — independently re-verified from a second, fully fresh clone
  (separate `orgs/kotoba-lang/{langgraph,langchain}` sibling checkouts)
  after the initial push, same result.
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-weight-exceeded,
  reactor-actuate-blocked, already-scheduled, invalid-polymer-grade,
  invalid-off-spec-rate), with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:primary-forms-plant-
  operations-governor` is grep-verified UNIQUE fleet-wide
  (`gh search code "primary-forms-plant-operations-governor" --owner
  cloud-itonami`, zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"2013"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2013" ...}` block (no wholesale
  regeneration), landed via GitHub API server-side merge at commit
  `7ce3fe801787edbf079955271945330ffc40b4d1` (confirmed as the tip of
  `kotoba-lang/industry`'s `main` via
  `gh api repos/kotoba-lang/industry/git/ref/heads/main`). The live
  `:implemented` count was recomputed fresh immediately before the
  edit (226 -> 227 -> 228 -> 229 across several retries as concurrent
  sibling promotions landed first, each retry re-fetching
  `origin/main` and re-running `clojure -M:test` before pushing —
  never an assumed fixed number) and `industry_test.clj`'s own
  assertion was bumped accordingly and re-run green
  (`Ran 15 tests containing 948 assertions. 0 failures, 0 errors.`)
  before commit. Post-merge re-verification from a brand-new fresh
  clone (plus a fresh `../technology` sibling clone) reproduced the
  same green result and confirmed zero UTF-8 mojibake
  (`grep -c "â" resources/kotoba/industry/registry.edn` = 0).
