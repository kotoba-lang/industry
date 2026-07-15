# ADR-2607156500: cloud-itonami-isic-2710 (Manufacture of electric motors, generators, transformers and electricity distribution and control apparatus) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607151145 (cloud-itonami-isic-2211 Manufacture of rubber tyres and tubes coverage, closest domain analog)

## Context

ISIC class 2710 (Manufacture of electric motors, generators,
transformers and electricity distribution and control apparatus) is a
fresh scaffold — no prior repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-2710` before this ADR (checked and
confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-2710`
before starting). The `kotoba-lang/industry` registry entry `{:id
"2710" :name "Manufacture of electric motors, generators, transformers
an..." ...}` was verified byte-for-byte from a fresh read-only clone
before any code was written (this fleet has previously mislabeled an
assigned ISIC class from memory rather than reading the registry —
e.g. 0892 assumed salt, actually peat; 0144 assumed swine, actually
sheep-goats). The pre-existing registry entry's `:repo`/`:business-id`
pointed at a never-created `gftdcojp/cloud-itonami-C2710` placeholder,
not a real prior attempt.

The closest domain analog is `cloud-itonami-isic-2211` (Manufacture of
rubber tyres and tubes): both are back-office coordination actors for
a fixed processing PLANT with heavy manufacturing equipment and a real
physical safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). This build mirrors 2211's architecture
closely but adapts the hazard profile and equipment/product vocabulary
to the electrical-equipment plant: 2710's central physical hazard is
the high-voltage dielectric/hipot withstand-testing process (electric
shock / insulation-failure hazard) rather than 2211's high-temperature/
high-pressure curing-press hazard; 2710's permanent equipment-
actuation block guards winding/assembly/test-bench EQUIPMENT
(`:actuate-equipment?`) rather than a building/curing/vulcanization
LINE (`:actuate-line?`); and 2710's production-batch record declares a
`:product-type` (closed set spanning electric-motor/generator/
transformer/distribution-apparatus/control-apparatus) and a
`:dielectric-test-kv` (the routine hipot/withstand test voltage in kV,
plausibility-checked 0-2500 against IEC 60076-3's lightning-impulse
withstand table) in addition to a `:defect-rate-percent`, rather than
2211's `:tyre-category`/`:load-index`. 2710's shipment quantity is
also tracked in finished-unit UNITS (`:units`/`:quantity-units`/
`:shipped-units`) rather than a bulk weight, the same shape 2211 uses
for finished tyres, since motors/generators/transformers/apparatus are
likewise discrete counted units.

This vertical additionally has a DOMAIN-SPECIFIC permanent block 2211
does not need in the same form: manufacture of electric motors,
generators, transformers and electricity distribution/control
apparatus is subject to electrical-safety certification regimes (e.g.
UL 1004/60034 series, IEC 60034/60076, CE marking under the EU Low
Voltage Directive) rather than 2211's DOT/ECE tyre-safety regimes.
This actor is never the certification authority — any proposal
(regardless of op) that declares `:issue-certification? true` is a
HARD, PERMANENT, unconditional block
(`elecequipmfg.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture as the equipment-
actuation block.

This vertical is SELF-CONTAINED — no `kotoba-lang/elecequipmfg`
library exists, so domain logic (equipment/batch verification,
shipment-quantity recompute, product-type validation, dielectric-
test-voltage plausibility validation, defect-rate plausibility
validation) lives as pure functions in `elecequipmfg.registry` and is
re-verified independently by the governor, mirroring the discipline
established by `cloud-itonami-isic-2211`'s `tyremfg.registry` and
every prior sibling actor.

## Decision

Build `cloud-itonami-isic-2710` from scratch as a governed-actor
implementation of the electric-motor/generator/transformer/
distribution-and-control-apparatus-manufacturing blueprint, following
the langgraph StateGraph + independent Governor + Phase 0->3 rollout
architecture established across the fleet:

1. **ElecEquipAdvisor** (`elecequipmfg.advisor`, sealed intelligence
   node): proposes plant-operations coordination actions only, never
   commits
   - `:log-production-batch` — winding/assembly batch, output-quality/test-result data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — winding/assembly/test-bench-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface an insulation-failure/electrical-safety/high-voltage-test-hazard concern (always escalates)
   - `:coordinate-shipment` — outbound electrical-equipment shipment coordination proposal

2. **Electrical Equipment Plant Operations Governor**
   (`elecequipmfg.governor`, independent validation layer, never
   trusts the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into twelve concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct winding/assembly/test-bench-equipment
     control); `:actuate-equipment? true` on a maintenance schedule
     (directly actuating winding/assembly/test-bench equipment) is a
     PERMANENT block; `:issue-certification? true` on ANY proposal
     (self-issuing an electrical-safety certification mark) is a
     PERMANENT block; a shipment may not push a batch's own recorded
     shipped unit quantity past its own logged production quantity
     (independently recomputed); no double-scheduling the same
     maintenance record; no fabricated `:product-type` value; no
     physically implausible `:dielectric-test-kv` value; no physically
     implausible `:defect-rate-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — high-voltage
   dielectric/hipot test hazard, electrical-safety certification,
   downstream grid-reliability and worker-safety consequence):
   - Does NOT control winding, assembly, or test-bench equipment directly
   - Does NOT make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
   - Does NOT actuate winding/assembly/test-bench equipment (permanently blocked,
     not a rollout milestone still to come — see `elecequipmfg.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - Does NOT self-issue an electrical-safety certification mark (e.g. UL/CE/IEC — permanently blocked, unconditional)
   - All proposals are `:effect :propose`; actuation and certification are human-/institution-approval-gated

4. **Self-contained domain logic**: `elecequipmfg.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-quantity-
   exceeded?`, `product-type-valid?`, `dielectric-test-kv-valid?`,
   `defect-rate-valid?`) are re-verified independently by the
   governor, following the "ground truth, not self-report" discipline
   established by prior actors (most directly `cloud-itonami-isic-
   2211`'s `tyremfg.registry`).

5. **Store** (`elecequipmfg.store`): a single `MemStore` backend
   behind a `Store` protocol, tracking four entity kinds (batches,
   equipment, maintenance, shipments) plus the append-only ledger.
   Like 2211, this build does NOT ship a second Datomic-backed store —
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
   `github.com/cloud-itonami/cloud-itonami-isic-2710` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Electric-motor/generator/transformer/distribution-and-control-
apparatus plant-operations back-office coordination is now genuinely
implemented and tested (not merely scaffolded). ISIC 2710 moves from
`:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation, equipment actuation, or certification self-issuance,
independently corroborated by `elecequipmfg.phase`'s permanent
exclusion of `:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely electrical-equipment-
manufacturing-specific elaboration mirroring 2211's own two-entity-
kind gate — this domain has two distinct ground-truth entity kinds a
proposal can reference, and each is independently re-derived from its
own permanent record, never trusting the proposal's self-report.

(+) The certification-authority-blocked check is directly adapted from
2211's own DOT/ECE elaboration to this vertical's own electrical-
safety certification regimes (UL/CE/IEC) — the governor closes that
scope-creep vector explicitly rather than leaving it implicit in the
closed op-allowlist alone.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 77 tests / 210 assertions
across 5 test namespaces (`elecequipmfg.operation-test`,
`elecequipmfg.governor-contract-test`, `elecequipmfg.phase-test`,
`elecequipmfg.store-contract-test`, `elecequipmfg.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch/certification-body
systems — scope is deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2710` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-2710`, initial commit
  `20e66ecbbdf6696c36b5a186da75656779f3a6d2` (confirmed via
  `gh api repos/cloud-itonami/cloud-itonami-isic-2710/compare/<sha>...main`
  reporting `status: identical, ahead_by: 0, behind_by: 0`).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 77 tests containing 210 assertions. 0 failures, 0 errors.`**
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-quantity-
  exceeded, equipment-actuate-blocked, certification-authority-
  blocked, already-scheduled, invalid-product-type, invalid-
  dielectric-test-kv, invalid-defect-rate), with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:electrical-equipment-plant-
  operations-governor` is grep-verified UNIQUE fleet-wide (`gh search
  code "electrical-equipment-plant-operations-governor" --owner
  cloud-itonami`, zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"2710"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2710" ...}` block (no wholesale
  regeneration), landed via GitHub API server-side merge at commit
  `20850bd15026055f308b39ccc64f9bd3a10d00ee` (confirmed as the tip of
  `kotoba-lang/industry`'s `main` via `gh api
  repos/kotoba-lang/industry/compare/20850bd...main` reporting
  `status: identical, ahead_by: 0, behind_by: 0`). The live
  `:implemented` count was recomputed fresh immediately before the
  successful edit (240 -> 241, several earlier attempts hit `409 Merge
  conflict` from concurrent sibling promotions landing mid-retry —
  each was resolved by re-fetching `origin/main`, re-deriving a fresh
  branch off the new tip [not `git rebase`, which is forbidden], and
  reapplying the same minimal edit) and `industry_test.clj`'s own
  assertion was bumped accordingly and re-run green (`Ran 15 tests
  containing 950 assertions. 0 failures, 0 errors.`) before each
  commit attempt. Post-merge re-verification from a brand-new fresh
  clone (plus a fresh `../technology` sibling clone) reproduced the
  same green result (`Ran 15 tests containing 950 assertions. 0
  failures, 0 errors.`) and confirmed zero UTF-8 mojibake (`grep -c
  "â" resources/kotoba/industry/registry.edn` = 0).
