# ADR-2607191500: cloud-itonami-isic-2640 (Manufacture of consumer electronics) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607156500 (cloud-itonami-isic-2710 Manufacture of electric motors, generators, transformers and electricity distribution and control apparatus coverage, closest domain analog)

## Context

ISIC class 2640 (Manufacture of consumer electronics) is a fresh
scaffold — no prior repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-2640` before this ADR (checked and
confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-2640`
before starting). The `kotoba-lang/industry` registry entry `{:id
"2640" :name "Manufacture of consumer electronics" ...}` was verified
byte-for-byte from a fresh read-only clone before any code was written
(this fleet has previously mislabeled an assigned ISIC class from
memory rather than reading the registry — e.g. 0892 assumed salt,
actually peat; 0144 assumed swine, actually sheep-goats). The `:name`
matched the assigned scope exactly, so no premise mismatch. The
pre-existing registry entry's `:repo`/`:business-id` pointed at a
never-created `gftdcojp/cloud-itonami-C2640` placeholder, not a real
prior attempt.

The closest domain analog is `cloud-itonami-isic-2710` (Manufacture of
electric motors, generators, transformers and electricity distribution
and control apparatus): both are back-office coordination actors for a
fixed processing PLANT with electronics-assembly/test equipment and a
real physical safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). This build mirrors 2710's architecture
closely but adapts the equipment/product vocabulary to the consumer-
electronics plant: 2640's central equipment is a surface-mount-
technology (SMT) PCB-assembly line (component placement + reflow
soldering) and a final-assembly/test bench, rather than 2710's
winding/insulation/high-voltage-dielectric-test line for electric
motors/generators/transformers; 2640's permanent equipment-actuation
block guards SMT/assembly/test-bench EQUIPMENT
(`:actuate-equipment?`, same field name, same posture) rather than
2710's winding/assembly/test-bench equipment; and 2640's production-
batch record declares a `:product-type` (closed set spanning
television/audio-device/video-device/smart-speaker/wearable-device)
and a `:dielectric-test-kv` (the routine hipot/withstand safety-test
voltage in kV for mains-connected units, plausibility-checked 0-5 kV
against IEC 62368-1's electric-strength test-voltage tables for audio/
video/ICT equipment, a much lower ceiling than 2710's 0-2500 kV power-
transformer range since consumer electronics are low-power/low-voltage
by comparison) in addition to a `:defect-rate-percent`, rather than
2710's `:dielectric-test-kv` grounded in IEC 60076-3's power-
transformer lightning-impulse withstand table. 2640's shipment
quantity is also tracked in finished-unit UNITS (`:units`/`:quantity-
units`/`:shipped-units`) rather than a bulk weight, the same shape
2710 uses for finished motors/generators/transformers/apparatus, since
consumer-electronics products are likewise discrete counted units.

This vertical additionally has a DOMAIN-SPECIFIC safety-concern
vocabulary 2710 does not need in the same form: manufacture of
consumer electronics carries a battery-safety hazard (lithium-ion/
lithium-polymer battery packs in wearables/portable audio devices)
alongside the electrical-safety hazard 2710 shares, plus a RoHS-
compliance dimension (Restriction of Hazardous Substances in
electronic components) specific to consumer-electronics regulatory
regimes rather than 2710's UL/CE/IEC electrical-safety-only regimes.
`:flag-safety-concern` therefore surfaces a "battery-safety/
electrical-safety/RoHS-compliance concern" (vs. 2710's "insulation-
failure/electrical-safety/high-voltage-test-hazard concern"), and this
actor is never the certification authority — any proposal (regardless
of op) that declares `:issue-certification? true` is a HARD,
PERMANENT, unconditional block
(`consumerelec.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture as 2710's own
electrical-safety-certification block, widened to consumer-
electronics certification regimes (e.g. UL 62368-1, CE marking under
the EU Radio Equipment Directive / Low Voltage Directive, FCC Part 15,
RoHS Directive 2011/65/EU).

This vertical is SELF-CONTAINED — no `kotoba-lang/consumerelec`
library exists, so domain logic (equipment/batch verification,
shipment-quantity recompute, product-type validation, dielectric-
safety-test-voltage plausibility validation, defect-rate plausibility
validation) lives as pure functions in `consumerelec.registry` and is
re-verified independently by the governor, mirroring the discipline
established by `cloud-itonami-isic-2710`'s `elecequipmfg.registry` and
every prior sibling actor.

## Decision

Build `cloud-itonami-isic-2640` from scratch as a governed-actor
implementation of the consumer-electronics-manufacturing blueprint,
following the langgraph StateGraph + independent Governor + Phase 0->3
rollout architecture established across the fleet:

1. **ConsumerElecAdvisor** (`consumerelec.advisor`, sealed intelligence
   node): proposes plant-operations coordination actions only, never
   commits
   - `:log-production-batch` — SMT/assembly batch, output-quality/test-result data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — SMT/assembly/test-bench-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface a battery-safety/electrical-safety/RoHS-compliance concern (always escalates)
   - `:coordinate-shipment` — outbound consumer-electronics product shipment coordination proposal

2. **Consumer Electronics Plant Operations Governor**
   (`consumerelec.governor`, independent validation layer, never
   trusts the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into twelve concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct SMT/assembly/test-bench-equipment control);
     `:actuate-equipment? true` on a maintenance schedule (directly
     actuating SMT/assembly/test-bench equipment) is a PERMANENT
     block; `:issue-certification? true` on ANY proposal (self-issuing
     a consumer-electronics safety-certification mark) is a PERMANENT
     block; a shipment may not push a batch's own recorded shipped
     unit quantity past its own logged production quantity
     (independently recomputed); no double-scheduling the same
     maintenance record; no fabricated `:product-type` value; no
     physically implausible `:dielectric-test-kv` value; no physically
     implausible `:defect-rate-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — battery-
   safety hazard, electrical-safety hazard, RoHS-compliance
   requirement, downstream product-safety and worker-safety
   consequence):
   - Does NOT control SMT, assembly, or test-bench equipment directly
   - Does NOT make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
   - Does NOT actuate SMT/assembly/test-bench equipment (permanently blocked,
     not a rollout milestone still to come — see `consumerelec.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - Does NOT self-issue a consumer-electronics safety-certification mark (e.g. UL/CE/FCC/RoHS — permanently blocked, unconditional)
   - All proposals are `:effect :propose`; actuation and certification are human-/institution-approval-gated

4. **Self-contained domain logic**: `consumerelec.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-quantity-
   exceeded?`, `product-type-valid?`, `dielectric-test-kv-valid?`,
   `defect-rate-valid?`) are re-verified independently by the
   governor, following the "ground truth, not self-report" discipline
   established by prior actors (most directly `cloud-itonami-isic-
   2710`'s `elecequipmfg.registry`).

5. **Store** (`consumerelec.store`): a single `MemStore` backend
   behind a `Store` protocol, tracking four entity kinds (batches,
   equipment, maintenance, shipments) plus the append-only ledger.
   Like 2710, this build does NOT ship a second Datomic-backed store —
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
   `github.com/cloud-itonami/cloud-itonami-isic-2640` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Consumer-electronics plant-operations back-office coordination is
now genuinely implemented and tested (not merely scaffolded). ISIC
2640 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation, equipment actuation, or certification self-issuance,
independently corroborated by `consumerelec.phase`'s permanent
exclusion of `:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely consumer-electronics-
manufacturing-specific elaboration mirroring 2710's own two-entity-
kind gate — this domain has two distinct ground-truth entity kinds a
proposal can reference, and each is independently re-derived from its
own permanent record, never trusting the proposal's self-report.

(+) The certification-authority-blocked check is directly adapted from
2710's own UL/CE/IEC elaboration to this vertical's own consumer-
electronics safety-certification regimes (UL/CE/FCC/RoHS) — the
governor closes that scope-creep vector explicitly rather than leaving
it implicit in the closed op-allowlist alone.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 77 tests / 210 assertions
across 5 test namespaces (`consumerelec.operation-test`,
`consumerelec.governor-contract-test`, `consumerelec.phase-test`,
`consumerelec.store-contract-test`, `consumerelec.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch/certification-body
systems — scope is deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2640` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-2640`, initial commit
  `89975c9bdd4d6ff4f40028db622c14d45b9babb0` (confirmed via
  `gh api repos/cloud-itonami/cloud-itonami-isic-2640/compare/<sha>...main`
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
- `:itonami.blueprint/governor` keyword `:consumer-electronics-plant-
  operations-governor` is grep-verified UNIQUE fleet-wide (`gh search
  code "consumer-electronics-plant-operations-governor" --owner
  cloud-itonami`, zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"2640"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2640" ...}` block (no wholesale
  regeneration). See the registry-promotion commit message / PR for
  the exact landed commit SHA and the recomputed `:implemented` count.
  Post-merge re-verification (fresh independent clone into a new temp
  dir, plus a fresh `kotoba-lang/technology` sibling clone) is recorded
  there too.
