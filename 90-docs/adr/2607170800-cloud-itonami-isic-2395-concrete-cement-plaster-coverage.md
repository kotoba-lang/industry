# ADR-2607170800: cloud-itonami-isic-2395 (Manufacture of articles of concrete, cement and plaster) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607181600 (cloud-itonami-isic-2392 Manufacture of clay building materials coverage, closest architectural analog)

## Context

ISIC class 2395 (Manufacture of articles of concrete, cement and
plaster) is a fresh scaffold — no prior repo or reverted attempt
existed at `cloud-itonami/cloud-itonami-isic-2395` before this ADR
(checked and confirmed 404 via `gh api
repos/cloud-itonami/cloud-itonami-isic-2395` before starting). The
`kotoba-lang/industry` registry entry `{:id "2395" :name "Manufacture
of articles of concrete, cement and plaster" ...}` was verified
byte-for-byte from a fresh read-only clone before any code was written
(this fleet has previously mislabeled an assigned ISIC class from
memory rather than reading the registry).

The closest architectural analog is `cloud-itonami-isic-2392`
(Manufacture of clay building materials): both are back-office
coordination actors for a fixed processing PLANT with heavy
manufacturing equipment and a real physical safety dimension, and both
share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). The two verticals are, however, distinct
plants with distinct hazard profiles: 2392's central physical hazard
is kiln-firing heat and clay/silica-dust exposure (clay winning/pugging
-> extrusion or press molding -> drying -> kiln-firing), while 2395's
is cement/silica-dust exposure at batching and mold-stripping plus
curing-heat/steam-burn hazard at the curing chamber or autoclave
(mixing/batching -> molding/casting -> curing), and mixing/batching-
plant pinch-point hazard. This build mirrors 2392's architecture
closely but adapts the hazard profile and equipment/product vocabulary
to the concrete/cement/plaster products plant: 2395's permanent
equipment-actuation block guards a mixing/batching plant or molding
line (`:actuate-mixing-line?`) rather than an extrusion press/kiln
line (`:actuate-kiln-line?`); and 2395's production-batch record
declares a `:product-type` spanning precast concrete panel, concrete
pipe, concrete masonry block, paving slab, plasterboard, fiber-cement
sheet, concrete roof tile and concrete post families (per ISIC 2395's
own combined scope), plus the same `:dimensional-deviation-percent`
and `:defect-rate-percent` field shape as 2392's own record.

`cloud-itonami-isic-2395` is also distinct from
`cloud-itonami-isic-2392` (Manufacture of clay building materials, a
distinct plant that wins/pugs clay and fires it in a kiln rather than
mixing/casting a cementitious mix) and from
`cloud-itonami-isic-2396` (Cutting, shaping and finishing of stone, a
distinct vertical that cuts and finishes natural stone rather than
casting a manufactured concrete/cement/plaster mix) — neither of which
this build depends on or wraps.

This vertical is SELF-CONTAINED — no `kotoba-lang/concretemfg` library
exists, so domain logic (equipment/batch verification, shipment-weight
recompute, product-type validation, dimensional-deviation plausibility
validation, defect-rate plausibility validation) lives as pure
functions in `concretemfg.registry` and is re-verified independently
by the governor, mirroring the discipline established by
`cloud-itonami-isic-2392`'s `claymfg.registry` and every prior sibling
actor.

## Decision

Build `cloud-itonami-isic-2395` from scratch as a governed-actor
implementation of the concrete/cement/plaster products plant
blueprint, following the langgraph StateGraph + independent Governor +
Phase 0->3 rollout architecture established across the fleet:

1. **ConcreteAdvisor** (`concretemfg.advisor`, sealed intelligence
   node): proposes plant-operations coordination actions only, never
   commits
   - `:log-production-batch` — product-type/weight/dimensional-deviation/defect-rate data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — mixing/batching-plant or molding-line-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface a materials-safety/equipment-safety concern (silica dust, curing-heat), always escalates
   - `:coordinate-shipment` — outbound concrete/cement/plaster product shipment coordination proposal

2. **Concrete Plant Operations Governor** (`concretemfg.governor`,
   independent validation layer, never trusts the advisor's own
   self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into eleven concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct mixing/molding-line-equipment control);
     `:actuate-mixing-line? true` on a maintenance schedule (directly
     actuating the mixing/batching plant or molding line) is a
     PERMANENT block; a shipment may not push a batch's own recorded
     shipped weight past its own logged production weight
     (independently recomputed); no double-scheduling the same
     maintenance record; no fabricated `:product-type` value; no
     physically implausible `:dimensional-deviation-percent` value; no
     physically implausible `:defect-rate-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — cement/
   silica-dust exposure, curing-heat/steam-burn hazard, mixing/
   batching-plant pinch-point hazard, heavy precast-unit handling
   hazard):
   - Does NOT control the mixing/batching plant or molding line equipment directly
   - Does NOT make plant-safety or materials-safety decisions (exclusive to the human plant supervisor)
   - Does NOT actuate the mixing/batching plant or molding line (permanently blocked,
     not a rollout milestone still to come — see `concretemfg.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `concretemfg.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-weight-
   exceeded?`, `product-type-valid?`, `dimensional-deviation-valid?`,
   `defect-rate-valid?`) are re-verified independently by the
   governor, following the "ground truth, not self-report" discipline
   established by prior actors (most directly
   `cloud-itonami-isic-2392`'s `claymfg.registry`).

5. **Store** (`concretemfg.store`): a single `MemStore` backend behind
   a `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 2392,
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
   `github.com/cloud-itonami/cloud-itonami-isic-2395` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Concrete/cement/plaster products plant-operations back-office
coordination is now genuinely implemented and tested (not merely
scaffolded). ISIC 2395 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or mixing/molding-line actuation, independently corroborated
by `concretemfg.phase`'s permanent exclusion of `:schedule-maintenance`
from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely concrete-plant-
specific elaboration mirroring 2392's own two-entity-kind gate — this
domain has two distinct ground-truth entity kinds a proposal can
reference, and each is independently re-derived from its own permanent
record, never trusting the proposal's self-report.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 76 tests / 209 assertions
across 5 test namespaces (`concretemfg.operation-test`,
`concretemfg.governor-contract-test`, `concretemfg.phase-test`,
`concretemfg.store-contract-test`, `concretemfg.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2395` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed directly to
  `main` at `github.com/cloud-itonami/cloud-itonami-isic-2395`
  (`gh repo create ... --source=. --remote=origin`, single initial
  commit `07a5f2f22107ad6ae9d15e09b1f948c8b501ab87`, confirmed as the
  tip of `cloud-itonami-isic-2395`'s `main` via `git merge-base
  --is-ancestor` against `origin/main`).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 76 tests containing 209 assertions. 0 failures, 0 errors.`**
  — independently re-verified from a second, fully fresh clone
  (separate `kotoba-lang/{langgraph,langchain}` sibling checkouts)
  after the initial push, same result.
- `clojure -M:lint`: `linting took 462ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-weight-exceeded,
  mixing-line-actuate-blocked, already-scheduled, invalid-product-type,
  invalid-dimensional-deviation, invalid-defect-rate), with no
  exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:concrete-plant-operations-
  governor` is grep-verified UNIQUE fleet-wide (`gh search code
  "concrete-plant-operations-governor" --owner cloud-itonami`, zero
  hits before this repo was created); `concretemfg` namespace prefix
  likewise grep-verified UNIQUE (`gh search code "concretemfg" --owner
  cloud-itonami`, zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"2395"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2395" ...}` block (verified exactly one
  occurrence before replace; diff confirmed scoped to only that block;
  `:repo`/`:business-id` also corrected from the stale never-created
  `gftdcojp/cloud-itonami-C2395` placeholder to the real
  `cloud-itonami/cloud-itonami-isic-2395`), landed via a feature-branch
  push + GitHub API server-side merge (`gh api
  repos/kotoba-lang/industry/merges`) at commit
  `ef15489da943bc45795abd62a639affecbd091e1` (confirmed as the tip of
  `kotoba-lang/industry`'s `main` via `gh api
  repos/kotoba-lang/industry/commits/main`; merged cleanly on the
  first attempt, no 409). The live `:implemented` count was
  recomputed fresh immediately before the edit via
  `(kotoba.industry/maturity-summary)` on a freshly re-fetched
  `origin/main` tip (`{:total 648, :spec 325, :blueprint 25,
  :implemented 298}`, i.e. 297 pre-edit + this promotion's own +1) and
  `industry_test.clj`'s own assertion was bumped 296 -> 298
  accordingly (one further sibling promotion had landed concurrently
  between the assertion's last recorded value and this edit) and
  re-run green (`Ran 15 tests containing 977 assertions. 0 failures, 0
  errors.`) before commit. Note: during this promotion an unrelated
  pre-existing EDN corruption in the registry (a different agent's
  in-flight edit near the `{:id "931"}`/`{:id "932"}` boundary,
  breaking `clojure.edn` parsing for the whole file) was observed on
  an initial fetch but had already been repaired by that other agent's
  own follow-up commit (`23e165052b0feacd42b037dee214feec6cc19f40`,
  landed ~43 seconds before this promotion's own base fetch) before
  this promotion's edit was built — no repair action was needed or
  taken by this ADR's own work, and this promotion's base commit is
  that already-repaired tip. Post-merge re-verification from a
  brand-new fresh clone (plus a fresh `technology` sibling clone)
  reproduced the same green result (`Ran 15 tests containing 977
  assertions. 0 failures, 0 errors.`), confirmed the `"2395"` entry
  survived with `:maturity :implemented`, and confirmed zero UTF-8
  mojibake (`grep -c "â" resources/kotoba/industry/registry.edn` = 0).
