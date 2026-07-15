# ADR-2607998000: cloud-itonami-isic-2420 (Manufacture of basic precious and other non-ferrous metals) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607243200 (cloud-itonami-isic-2432 Casting of non-ferrous metals coverage, closest architectural analog and mirror source)

## Context

ISIC class 2420 (Manufacture of basic precious and other non-ferrous
metals) is a fresh scaffold — no prior repo or reverted attempt
existed at `cloud-itonami/cloud-itonami-isic-2420` before this ADR
(checked and confirmed 404 via `gh api repos/cloud-itonami/cloud-
itonami-isic-2420` before starting). The `kotoba-lang/industry`
registry entry `{:id "2420" :name "Manufacture of basic precious and
other non-ferrous metals" ...}` was verified byte-for-byte from a
fresh read via the GitHub git-data (blob) API (not
`raw.githubusercontent.com`) before any code was written (this fleet
has previously mislabeled an assigned ISIC class from memory rather
than reading the registry).

The closest architectural analog is `cloud-itonami-isic-2432` (Casting
of non-ferrous metals): both are back-office coordination actors for a
fixed processing PLANT with heavy manufacturing equipment and a real
physical safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). The two verticals are, however, distinct
STAGES of the same non-ferrous-metals value chain, not the same plant:
2420 is the PRIMARY production stage — a smelting furnace (blast /
flash-smelting / reverberatory) melts ore/concentrate/scrap and
separates metal from gangue, then a refining stage (anode furnace /
electrolytic refining cell / converter) purifies it into doré bar /
bullion bar / cathode / anode / ingot — while 2432 is a DOWNSTREAM
foundry that takes already-refined/alloyed metal (2420's own output,
or purchased ingot) and melts+pours or die-casts it into shaped parts.
2420 never pours a shaped mold or die-casts a part; 2432 never smelts
ore or electrolytically refines a metal. 2420 is also distinct from
mining/mineral-processing ISIC classes (0710/0729) that produce the
ore/concentrate feed this actor's own smelting furnace consumes — 2420
never extracts ore from the ground. This build mirrors 2432's
architecture closely (module-for-module: `smeltrefine.*` in place of
`nonferrousmfg.*`) but adapts the hazard profile, equipment
vocabulary, and record shape to the PRIMARY smelting-refining plant:
2420's permanent equipment-actuation block guards a smelting furnace,
refining furnace, electrolytic refining cell, **or converter**
(`:actuate-furnace?`, kept as the same field name as 2432 to preserve
the governor-rule shape, documented to cover all four); 2420's
production-batch record declares a `:purity-grade` spanning gold/
silver/copper/aluminum smelting-refining grade families (8 grades
across four families, per ISIC 2420's own combined precious/non-
ferrous scope) rather than 2432's cast-alloy families; 2420's
`valid-output-forms` set is `:dore-bar`/`:bullion-bar`/`:cathode`/
`:anode`/`:ingot` (PRIMARY-PRODUCTION output shapes) rather than
2432's cast-part shapes; and 2420's `:impurity-percent` field (the
batch's own residual-impurity reading, target near zero) replaces
2432's `:defect-rate-percent` (a cast part's own reject-rate reading).

This vertical has NO pre-existing `kotoba-lang/smeltrefine`-style
capability library to wrap (verified: `gh api "search/repositories?
q=org:kotoba-lang+smelt"` and the same query for `+refine` both return
zero results). This build therefore uses self-contained domain logic —
pure functions in `smeltrefine.registry` (equipment/batch
verification, shipment-weight recompute, purity-grade validation,
impurity-rate plausibility validation) are re-verified independently
by the governor, the same "ground truth, not self-report" discipline
established across prior actors (most directly
`cloud-itonami-isic-2432`'s `nonferrousmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:precious-nonferrous-smelting-refining-plant-operations-governor`, is
grep-verified UNIQUE fleet-wide (`gh api "search/code?q=precious-
nonferrous-smelting-refining-plant-operations-governor"` returned
`"total_count":0`, zero hits before this repo was created).

## Decision

Build `cloud-itonami-isic-2420` from scratch as a governed-actor
implementation of the precious & non-ferrous metals smelting-refining
blueprint, following the langgraph StateGraph + independent Governor +
Phase 0->3 rollout architecture established across the fleet:

1. **SmeltRefineAdvisor** (`smeltrefine.advisor`, sealed intelligence
   node): proposes plant-operations coordination actions only, never
   commits
   - `:log-production-batch` — purity-grade (troy-ounce/kg for gold/silver, kg/tonne for copper/aluminum)/weight/impurity-rate data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — smelting-furnace/refining-furnace/electrolytic-refining-cell/converter maintenance scheduling proposal
   - `:flag-safety-concern` — surface a molten-metal-hazard (splash/burn, furnace/converter off-gas exposure)/toxic-fume-exposure (heavy-metal dust, electrolytic-cell acid mist)/environmental concern (stack emissions, effluent, tailings/slag release) (always escalates)
   - `:coordinate-shipment` — outbound refined-metal shipment coordination proposal

2. **Precious & Non-Ferrous Metals Smelting-Refining Plant Operations
   Governor** (`smeltrefine.governor`, independent validation layer,
   never trusts the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into ten concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct smelting/refining-furnace-equipment control);
     `:actuate-furnace? true` on a maintenance schedule (directly
     actuating the smelting furnace, refining furnace, electrolytic
     refining cell, or converter) is a PERMANENT block; a shipment may
     not push a batch's own recorded shipped weight past its own
     logged production weight (independently recomputed); no
     double-scheduling the same maintenance record; no fabricated
     `:purity-grade` value; no physically implausible
     `:impurity-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — molten-metal
   splash/burn risk, furnace/converter off-gas exposure, toxic
   heavy-metal-fume exposure, electrolytic-refining-cell acid-mist
   exposure, environmental release):
   - Does NOT control the smelting furnace, refining furnace, electrolytic refining cell, or converter directly
   - Does NOT make plant-safety, molten-metal-safety, or environmental-release decisions (exclusive to the human plant supervisor)
   - Does NOT actuate the smelting furnace, refining furnace, electrolytic refining cell, or converter (permanently blocked,
     not a rollout milestone still to come — see `smeltrefine.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - `:coordinate-shipment` is likewise never a member of any phase's `:auto` set (a refined precious/non-ferrous metal shipment, especially gold/silver bullion, carries an unusually high per-kilogram value in this vertical — always human shipping-approver sign-off, even when clean and within weight)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `smeltrefine.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-weight-
   exceeded?`, `purity-grade-valid?`, `impurity-valid?`) are
   re-verified independently by the governor, following the "ground
   truth, not self-report" discipline established by prior actors
   (most directly `cloud-itonami-isic-2432`'s `nonferrousmfg.registry`).

5. **Store** (`smeltrefine.store`): a single `MemStore` backend
   behind a `Store` protocol, tracking four entity kinds (batches,
   equipment, maintenance, shipments) plus the append-only ledger.
   Like 2432, this build does NOT ship a second Datomic-backed store —
   a second backend can be added later behind the same protocol
   without changing any caller.

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked
   via `langgraph.graph/run*`, not `.invoke`), append-only audit
   ledger, full test coverage, demo driver. Full module set:
   `deps.edn`, `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later),
   `README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
   `CONTRIBUTING.md`, `SECURITY.md`, `docs/adr/0001-architecture.md`.
   All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-2420` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Precious & non-ferrous metals smelting-refining plant-operations
back-office coordination is now genuinely implemented and tested (not
merely scaffolded). ISIC 2420 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or smelting/refining-furnace-equipment actuation,
independently corroborated by `smeltrefine.phase`'s permanent
exclusion of `:schedule-maintenance` and `:coordinate-shipment` from
every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely smelting-refining-
plant-specific elaboration mirroring 2432's own two-entity-kind gate —
this domain has two distinct ground-truth entity kinds a proposal can
reference, and each is independently re-derived from its own permanent
record, never trusting the proposal's self-report.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 71 tests / 197 assertions
across 5 test namespaces (`smeltrefine.operation-test`,
`smeltrefine.governor-contract-test`, `smeltrefine.phase-test`,
`smeltrefine.store-contract-test`, `smeltrefine.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch/assay-lab systems
— scope is deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2420` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed directly to
  `main` at `github.com/cloud-itonami/cloud-itonami-isic-2420` (no
  GitHub-generated LICENSE-only seed commit to reconcile — `gh repo
  create --source=.` pushed this repo's own commit as the initial
  commit), commit `7a91fb87043bc31d14225098057829333d54b82c` (confirmed
  as the tip of `cloud-itonami-isic-2420`'s `main` via `gh api
  repos/cloud-itonami/cloud-itonami-isic-2420/commits/main` matching
  the local HEAD SHA).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 71 tests containing 197 assertions. 0 failures, 0 errors.`**
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-weight-exceeded,
  furnace-actuate-blocked, already-scheduled, invalid-purity-grade,
  invalid-impurity-rate), with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:precious-nonferrous-
  smelting-refining-plant-operations-governor` is grep-verified
  UNIQUE fleet-wide (`gh api "search/code?q=precious-nonferrous-
  smelting-refining-plant-operations-governor"`, `"total_count":0`
  before this repo was created).
- `kotoba-lang/industry` registry entry for `"2420"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2420" ...}` block (no wholesale
  regeneration; `:repo`/`:business-id` also corrected from the stale
  never-created `gftdcojp/cloud-itonami-C2420` placeholder to the real
  `cloud-itonami/cloud-itonami-isic-2420`). See this ADR's own commit
  trail / the `kotoba-lang/industry` repo history for the exact landing
  commit SHA and the fresh-clone post-merge re-verification output
  (this file is written before that step per this fleet's protocol
  ordering; the registry edit's own PR/merge and re-verification output
  are recorded in the task's final report and in `industry_test.clj`'s
  own testing-block narration on `kotoba-lang/industry`'s `main`).
