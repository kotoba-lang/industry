# ADR-2607159700: cloud-itonami-isic-2817 (Manufacture of office machinery and equipment (except computers and peripheral equipment)) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607156500 (cloud-itonami-isic-2710 Manufacture of electric motors, generators, transformers and electricity distribution and control apparatus coverage, closest domain analog)

## Context

ISIC class 2817 (Manufacture of office machinery and equipment (except
computers and peripheral equipment)) is a fresh scaffold — no prior
repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-2817` before this ADR (checked and
confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-2817`
before starting). The `kotoba-lang/industry` registry entry `{:id
"2817" :name "Manufacture of office machinery and equipment" ...}`
was verified byte-for-byte from a fresh read-only clone before any
code was written. Its `:name` field is an abbreviated form of the
official ISIC Rev.4 name — it is missing the trailing "(except
computers and peripheral equipment)" exclusion clause that other
registry entries with an exclusion clause elsewhere in the same file
do preserve (e.g. "Manufacture of wearing apparel, except fur
apparel"). The `:id "2817"` itself, the surrounding sequential entries
(`"2816"` Manufacture of lifting and handling equipment / `"2818"`
Manufacture of power-driven hand tools — both correctly sequenced
within ISIC division 28 group 281 "general-purpose machinery"), and
the complete absence of any computer/peripheral-equipment framing
confirm this is genuinely ISIC 2817 with an abbreviated registry
`:name` string, not a misassigned class (this fleet has previously
mislabeled an assigned ISIC class from memory rather than reading the
registry — e.g. 0892 assumed salt, actually peat; 0144 assumed swine,
actually sheep-goats — so this distinction was checked explicitly
rather than assumed). The pre-existing registry entry's
`:repo`/`:business-id` pointed at a never-created
`gftdcojp/cloud-itonami-C2817` placeholder (confirmed 404), not a real
prior attempt. This build corrects the registry `:name` field to the
full official ISIC Rev.4 name as part of the same in-place `:spec` ->
`:implemented` edit.

The closest domain analog is `cloud-itonami-isic-2710` (Manufacture of
electric motors, generators, transformers and electricity distribution
and control apparatus): both are back-office coordination actors for a
fixed processing PLANT with precision manufacturing/assembly/test-bench
equipment and a real physical safety dimension, and both share the same
four-op shape (`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination), plus the same
domain-specific certification-authority permanent block shape (2710
blocks self-issued electrical-safety marks; this build blocks
self-issued UL/CE-type safety-certification marks). This build mirrors
2710's architecture closely (adapted, in turn, from `cloud-itonami-
isic-2652`'s watch/clock precision-assembly pattern) but retargets the
equipment/product vocabulary to the office-machinery plant: 2817's
central physical hazard is electrical safety of mains-powered
non-computer office equipment (typewriters, calculators, cash
registers, photocopiers, duplicating machines, postage meters, adding
machines — explicitly EXCLUDING computers and peripheral equipment,
which fall under ISIC 2620), expressed as a `:dielectric-withstand-
test-kv` reading (plausibility-checked 0.0-5.0 kV against general
low-voltage office-equipment hipot-test practice, e.g. IEC 62368-1/UL
62368-1 class-I withstand tests) rather than 2710's higher-voltage
`:dielectric-test-kv` (0-2500, IEC 60076-3 lightning-impulse table);
2817's permanent equipment-actuation block guards assembly/test-bench
EQUIPMENT (`:actuate-equipment?`), the same field/shape 2710 uses;
2817's production-batch record declares a `:product-type` (closed set
spanning typewriter/calculator/cash-register/photocopier/duplicating-
machine/postage-meter/adding-machine) and a `:defect-rate-percent`,
the same two-field-plus-product-type shape every prior sibling
established; and 2817's shipment quantity is tracked in finished-unit
UNITS (`:units`/`:quantity-units`/`:shipped-units`), the same shape
2710/2652 use for finished discrete-counted products.

This vertical is SELF-CONTAINED — no `kotoba-lang/officemach` library
exists, so domain logic (equipment/batch verification, shipment-
quantity recompute, product-type validation, dielectric-withstand-
test plausibility validation, defect-rate plausibility validation)
lives as pure functions in `officemach.registry` and is re-verified
independently by the governor, mirroring the discipline established
by `cloud-itonami-isic-2710`'s `elecequipmfg.registry` and every prior
sibling actor.

## Decision

Build `cloud-itonami-isic-2817` from scratch as a governed-actor
implementation of the office-machinery-manufacturing blueprint,
following the langgraph StateGraph + independent Governor + Phase 0->3
rollout architecture established across the fleet:

1. **OfficeMachOpsAdvisor** (`officemach.advisor`, sealed intelligence
   node): proposes plant-operations coordination actions only, never
   commits
   - `:log-production-batch` — assembly/test batch, output-quality/test-result data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — assembly/test-bench-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface an electrical-safety/mechanical-safety/UL-CE-compliance concern (always escalates)
   - `:coordinate-shipment` — outbound office-machinery shipment coordination proposal

2. **Office Machinery Plant Operations Governor**
   (`officemach.governor`, independent validation layer, never trusts
   the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into twelve concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct assembly/test-bench-equipment control);
     `:actuate-equipment? true` on a maintenance schedule (directly
     actuating assembly/test-bench equipment) is a PERMANENT block;
     `:issue-certification? true` on ANY proposal (self-issuing a
     safety-certification mark) is a PERMANENT block; a shipment may
     not push a batch's own recorded shipped unit quantity past its
     own logged production quantity (independently recomputed); no
     double-scheduling the same maintenance record; no fabricated
     `:product-type` value; no physically implausible
     `:dielectric-withstand-test-kv` value; no physically implausible
     `:defect-rate-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — mains
   electrical-safety hazard, precision-defect risk, safety
   certification, downstream consumer-safety and product-quality
   consequence):
   - Does NOT control assembly or test-bench equipment directly
   - Does NOT make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
   - Does NOT actuate assembly/test-bench equipment (permanently blocked,
     not a rollout milestone still to come — see `officemach.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - Does NOT self-issue a safety-certification mark (e.g. UL/CE — permanently blocked, unconditional)
   - All proposals are `:effect :propose`; actuation and certification are human-/institution-approval-gated

4. **Self-contained domain logic**: `officemach.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-quantity-
   exceeded?`, `product-type-valid?`, `dielectric-withstand-test-kv-
   valid?`, `defect-rate-valid?`) are re-verified independently by the
   governor, following the "ground truth, not self-report" discipline
   established by prior actors (most directly `cloud-itonami-isic-
   2710`'s `elecequipmfg.registry`).

5. **Store** (`officemach.store`): a single `MemStore` backend behind
   a `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 2710,
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
   `github.com/cloud-itonami/cloud-itonami-isic-2817` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Office-machinery plant-operations back-office coordination is now
genuinely implemented and tested (not merely scaffolded). ISIC 2817
moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation, equipment actuation, or certification self-issuance,
independently corroborated by `officemach.phase`'s permanent exclusion
of `:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely office-machinery-
manufacturing-specific elaboration mirroring 2710's own two-entity-kind
gate — this domain has two distinct ground-truth entity kinds a
proposal can reference, and each is independently re-derived from its
own permanent record, never trusting the proposal's self-report.

(+) The certification-authority-blocked check is directly adapted from
2710's own UL/CE/IEC elaboration to this vertical's own non-computer
office-machinery product line — the governor closes that scope-creep
vector explicitly rather than leaving it implicit in the closed
op-allowlist alone.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 76 tests / 212 assertions
across 5 test namespaces (`officemach.operation-test`,
`officemach.governor-contract-test`, `officemach.phase-test`,
`officemach.store-contract-test`, `officemach.registry-test`).

(+) The registry's own abbreviated `:name` field for `"2817"` was
corrected to the full official ISIC Rev.4 name
("Manufacture of office machinery and equipment (except computers and
peripheral equipment)") as part of the same in-place edit that
promoted `:maturity` — a byproduct fix, not scope creep, since it is
the same single `{:id "2817" ...}` block already being edited.

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch/certification-body
systems — scope is deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2817` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-2817`, initial commit
  `f3bcd38d023ad700891f4c067211294ed6a864ff` (confirmed via
  `gh api repos/cloud-itonami/cloud-itonami-isic-2817/git/refs/heads/main`
  matching the local `git rev-parse HEAD`).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 76 tests containing 212 assertions. 0 failures, 0 errors.`**
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-quantity-
  exceeded, equipment-actuate-blocked, certification-authority-
  blocked, already-scheduled, invalid-product-type, invalid-
  dielectric-withstand-test-kv, invalid-defect-rate), with no
  exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:office-machinery-plant-
  operations-governor` is grep-verified UNIQUE fleet-wide (`gh search
  code "office-machinery-plant-operations-governor" --owner
  cloud-itonami`, zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"2817"` updated in place
  from `:spec` to `:maturity :implemented` (plus `:name` corrected to
  the full official ISIC Rev.4 name and `:repo`/`:business-id`
  corrected to the real repo) via an exact-text in-place edit of the
  single `{:id "2817" ...}` block (no wholesale regeneration) — see
  the registry-side re-verification section of this ADR's companion
  commit for the exact landed commit SHA and post-merge fresh-clone
  re-verification output.
