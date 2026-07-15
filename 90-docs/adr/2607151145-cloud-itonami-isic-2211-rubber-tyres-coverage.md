# ADR-2607151145: cloud-itonami-isic-2211 (Manufacture of rubber tyres and tubes) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607171200 (cloud-itonami-isic-2013 Manufacture of plastics and synthetic rubber in primary forms coverage, closest domain analog)

## Context

ISIC class 2211 (Manufacture of rubber tyres and tubes) is a fresh
scaffold — no prior repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-2211` before this ADR (checked and
confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-2211`
before starting). The `kotoba-lang/industry` registry entry `{:id
"2211" :name "Manufacture of rubber tyres and tubes" ...}` was
verified byte-for-byte from a fresh read-only clone before any code
was written (this fleet has previously mislabeled an assigned ISIC
class from memory rather than reading the registry — e.g. 0892 assumed
salt, actually peat; 0144 assumed swine, actually sheep-goats).

The closest domain analog is `cloud-itonami-isic-2013` (Manufacture of
plastics and synthetic rubber in primary forms): both are back-office
coordination actors for a fixed processing PLANT with heavy
manufacturing equipment and a real physical safety dimension, and both
share the same four-op shape (`:log-production-batch`/`:schedule-
maintenance`/`:flag-safety-concern`/`:coordinate-shipment`) and the
same two-entity verified/registered gate structure (equipment for
maintenance scheduling, batch for shipment coordination). The two
verticals are, however, distinct plants: 2013 is the UPSTREAM
chemical-process plant (polymerization/compounding reactors producing
plastics resin and synthetic rubber as pellets/granules/powder/flake/
latex — a PRIMARY FORM), while 2211 is a DOWNSTREAM finished-product
plant (compounding/building/curing/vulcanization lines producing
finished pneumatic and solid tyres and inner tubes). This build
mirrors 2013's architecture closely but adapts the hazard profile and
equipment/product vocabulary to the finished-tyre plant: 2211's
central physical hazard is the building/curing/vulcanization process
(high-temperature, high-pressure curing-press hazard) rather than
2013's chemical monomer-exposure/exothermic-reaction-risk hazard;
2211's permanent equipment-actuation block guards a building/curing/
vulcanization LINE (`:actuate-line?`) rather than a polymerization/
compounding REACTOR (`:actuate-reactor?`); and 2211's production-batch
record declares a `:tyre-category` (closed set spanning passenger/
light-truck/truck-bus/agricultural/off-the-road/motorcycle/aircraft/
industrial tyres) and a `:load-index` (the ETRTO/DOT standardized
load-capacity code, plausibility-checked 0-279) in addition to a
`:defect-rate-percent`, rather than 2013's `:polymer-grade`/`:off-
spec-rate-percent`. 2211's shipment quantity is also tracked in
finished-tyre UNITS (`:units`/`:quantity-units`/`:shipped-units`)
rather than a bulk weight (2013's `kg`), since finished tyres are
counted, not weighed, for freight coordination.

This vertical additionally has a DOMAIN-SPECIFIC permanent block 2013
does not need: manufacture of rubber tyres is subject to DOT (US, 49
CFR 571.139/570) and ECE (EU, UN Regulation No. 30/54/117) tyre-safety
certification regimes. This actor is never the certification
authority — any proposal (regardless of op) that declares
`:issue-certification? true` is a HARD, PERMANENT, unconditional block
(`tyremfg.governor/certification-authority-blocked-violations`), the
same "no phase, no human override" posture as the line-actuation
block.

This vertical is SELF-CONTAINED — no `kotoba-lang/tyremfg` library
exists, so domain logic (equipment/batch verification, shipment-
quantity recompute, tyre-category validation, load-index plausibility
validation, defect-rate plausibility validation) lives as pure
functions in `tyremfg.registry` and is re-verified independently by
the governor, mirroring the discipline established by
`cloud-itonami-isic-2013`'s `resinmfg.registry` and every prior
sibling actor.

## Decision

Build `cloud-itonami-isic-2211` from scratch as a governed-actor
implementation of the rubber-tyre-and-tube-manufacturing blueprint,
following the langgraph StateGraph + independent Governor + Phase 0->3
rollout architecture established across the fleet:

1. **TyreAdvisor** (`tyremfg.advisor`, sealed intelligence node):
   proposes plant-operations coordination actions only, never commits
   - `:log-production-batch` — tyre-build/cure batch, size/load-index, output-quality data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — building/curing-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface a curing-defect/DOT-compliance/equipment-safety concern (always escalates)
   - `:coordinate-shipment` — outbound tyre shipment coordination proposal

2. **Tyre Plant Operations Governor** (`tyremfg.governor`, independent
   validation layer, never trusts the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into twelve concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct building/curing-line-equipment control);
     `:actuate-line? true` on a maintenance schedule (directly
     actuating the building/curing/vulcanization line) is a PERMANENT
     block; `:issue-certification? true` on ANY proposal (self-issuing
     a DOT/ECE tyre-safety certification) is a PERMANENT block; a
     shipment may not push a batch's own recorded shipped unit
     quantity past its own logged production quantity (independently
     recomputed); no double-scheduling the same maintenance record; no
     fabricated `:tyre-category` value; no physically implausible
     `:load-index` value; no physically implausible `:defect-rate-
     percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain —
   high-temperature/high-pressure curing hazard, DOT/ECE tyre-safety
   certification, direct public road-safety consequence downstream):
   - Does NOT control tyre-building or curing/vulcanization-line equipment directly
   - Does NOT make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
   - Does NOT actuate the building/curing/vulcanization line (permanently blocked,
     not a rollout milestone still to come — see `tyremfg.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - Does NOT self-issue a DOT/ECE tyre-safety certification mark (permanently blocked, unconditional)
   - All proposals are `:effect :propose`; actuation and certification are human-/institution-approval-gated

4. **Self-contained domain logic**: `tyremfg.registry` pure functions
   (`equipment-ready?`, `batch-ready?`, `shipment-quantity-exceeded?`,
   `tyre-category-valid?`, `load-index-valid?`, `defect-rate-valid?`)
   are re-verified independently by the governor, following the
   "ground truth, not self-report" discipline established by prior
   actors (most directly `cloud-itonami-isic-2013`'s
   `resinmfg.registry`).

5. **Store** (`tyremfg.store`): a single `MemStore` backend behind a
   `Store` protocol, tracking four entity kinds (batches, equipment,
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
   `github.com/cloud-itonami/cloud-itonami-isic-2211` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Rubber-tyre-and-tube plant-operations back-office coordination is
now genuinely implemented and tested (not merely scaffolded). ISIC
2211 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation, line actuation, or certification self-issuance,
independently corroborated by `tyremfg.phase`'s permanent exclusion of
`:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely tyre-manufacturing-
specific elaboration mirroring 2013's own two-entity-kind gate — this
domain has two distinct ground-truth entity kinds a proposal can
reference, and each is independently re-derived from its own permanent
record, never trusting the proposal's self-report.

(+) The certification-authority-blocked check is a genuinely new
elaboration this vertical needed that no prior sibling actor required
— rubber tyre manufacturing is directly subject to DOT/ECE tyre-safety
certification regimes with public road-safety consequence, so the
governor closes that scope-creep vector explicitly rather than leaving
it implicit in the closed op-allowlist alone.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 77 tests / 214 assertions
across 5 test namespaces (`tyremfg.operation-test`,
`tyremfg.governor-contract-test`, `tyremfg.phase-test`,
`tyremfg.store-contract-test`, `tyremfg.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch/certification-body
systems — scope is deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2211` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-2211`, initial commit
  `ef2beab7cdc8084c7b6d1562400904f5484dc7c3` (confirmed via
  `gh api repos/cloud-itonami/cloud-itonami-isic-2211/commits/main`
  matching the local HEAD SHA).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 77 tests containing 214 assertions. 0 failures, 0 errors.`**
  — independently re-verified from a second, fully fresh clone
  (separate `orgs/kotoba-lang/{langgraph,langchain}` sibling checkouts)
  after the initial push, same result.
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-quantity-
  exceeded, line-actuate-blocked, certification-authority-blocked,
  already-scheduled, invalid-tyre-category, invalid-load-index,
  invalid-defect-rate), with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:tyre-plant-operations-
  governor` is grep-verified UNIQUE fleet-wide (`gh search code
  "tyre-plant-operations-governor" --owner cloud-itonami`, zero hits
  before this repo was created).
- `kotoba-lang/industry` registry entry for `"2211"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2211" ...}` block (no wholesale
  regeneration), landed via GitHub API server-side merge at commit
  `08fa740efd7192ef9bdc2f7745398d70d9bfc8a4` (confirmed as the tip of
  `kotoba-lang/industry`'s `main` via
  `gh api repos/kotoba-lang/industry/git/ref/heads/main`). The live
  `:implemented` count was recomputed fresh immediately before each
  edit (232 -> 236 across several retries as concurrent sibling
  promotions landed first, final value 236 -> 237, each retry
  re-fetching `origin/main` and re-running `clojure -M:test` before
  pushing — never an assumed fixed number) and `industry_test.clj`'s
  own assertion was bumped accordingly and re-run green (`Ran 15 tests
  containing 950 assertions. 0 failures, 0 errors.`) before commit.
  Several attempts hit `409 Merge conflict` from concurrent sibling
  promotions (`cloud-itonami-isic-2431` landed mid-retry); each was
  resolved by re-fetching `origin/main`, re-deriving a fresh branch
  (not `git rebase`, which is forbidden — a clean branch off the new
  `origin/main` tip with the same minimal edit reapplied), and
  retrying the server-side merge, per CLAUDE.md's stale-branch
  guidance. Post-merge re-verification from a brand-new fresh clone
  (plus a fresh `../technology` sibling clone) reproduced the same
  green result (`Ran 15 tests containing 950 assertions. 0 failures, 0
  errors.`) and confirmed zero UTF-8 mojibake (`grep -c "â"
  resources/kotoba/industry/registry.edn` = 0).
