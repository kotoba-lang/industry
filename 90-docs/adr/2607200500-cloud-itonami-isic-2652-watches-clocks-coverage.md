# ADR-2607200500: cloud-itonami-isic-2652 (Manufacture of watches and clocks) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607156500 (cloud-itonami-isic-2710 Manufacture of electric motors, generators, transformers and electricity distribution and control apparatus coverage, closest domain analog / architecture mirrored closely)

## Context

ISIC class 2652 (Manufacture of watches and clocks) is a fresh
scaffold — no prior repo existed at
`cloud-itonami/cloud-itonami-isic-2652` before this ADR (checked and
confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-2652`
before starting). The `kotoba-lang/industry` registry entry `{:id
"2652" :name "Manufacture of watches and clocks" ...}` was verified
byte-for-byte from a fresh read-only clone before any code was written
(this fleet has previously mislabeled an assigned ISIC class from
memory rather than reading the registry, so this check was done
explicitly rather than assumed).

The closest domain analog is `cloud-itonami-isic-2710` (Manufacture of
electric motors, generators, transformers and electricity distribution
and control apparatus): both are back-office coordination actors for a
fixed processing PLANT with precision manufacturing/assembly/test-bench
equipment and a real physical safety dimension, and both share the
same four-op shape (`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination). This build mirrors
2710's architecture closely but adapts the hazard profile and
equipment/product vocabulary to the watch/clock plant: 2652's central
physical hazard is materials-safety (battery/mercury-cell leakage or
handling hazard in quartz movements), not 2710's high-voltage
dielectric/hipot withstand-testing hazard (electric shock/insulation-
failure risk); 2652's permanent equipment-actuation block guards
movement-assembly/casing/regulation/testing EQUIPMENT
(`:actuate-equipment?`) rather than 2710's winding/assembly/test-bench
equipment; and 2652's production-batch record declares a
`:product-type` (closed set spanning mechanical-watch/automatic-watch/
quartz-watch/chronograph-watch/wall-clock/mantel-clock/movement) and
an `:accuracy-test-seconds-per-day` (a physically plausible rate-test
reading, plausibility-checked -60 to +60 s/day against general
horological regulation practice and grounded against ISO 3159's much
tighter COSC chronometer band of -4/+6 s/day) in addition to a
`:defect-rate-percent`, rather than 2710's `:dielectric-test-kv`.
2652's shipment quantity is tracked in finished-product UNITS
(`:units`/`:quantity-units`/`:shipped-units`), the same counted-not-
weighed shape as 2710's finished motors/generators/transformers/
apparatus.

This vertical additionally has a DOMAIN-SPECIFIC permanent block, and
a different certification regime than 2710: manufacture of watches and
clocks is subject to voluntary precision/accuracy certification
regimes (most notably COSC chronometer certification under ISO 3159).
This actor is never the certification authority — any proposal
(regardless of op) that declares `:issue-certification? true` is a
HARD, PERMANENT, unconditional block
(`watchmfg.governor/certification-authority-blocked-violations`), the
same "no phase, no human override" posture as 2710's electrical-
safety-certification block.

This vertical is SELF-CONTAINED — no `kotoba-lang/watchmfg` library
exists, so domain logic (equipment/batch verification, shipment-
quantity recompute, product-type validation, accuracy-test-result
plausibility validation, defect-rate plausibility validation) lives as
pure functions in `watchmfg.registry` and is re-verified independently
by the governor, mirroring the discipline established by
`cloud-itonami-isic-2710`'s `elecequipmfg.registry` and every prior
sibling actor.

## Decision

Build `cloud-itonami-isic-2652` from scratch as a governed-actor
implementation of the watch/clock-manufacturing blueprint, following
the langgraph StateGraph + independent Governor + Phase 0->3 rollout
architecture established across the fleet:

1. **WatchOpsAdvisor** (`watchmfg.advisor`, sealed intelligence node):
   proposes plant-operations coordination actions only, never commits
   - `:log-production-batch` — movement-assembly/casing batch, output-quality/accuracy-test-result data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — movement-assembly/casing/regulation/testing-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface a materials-safety (battery/mercury-cell)/precision-defect concern (always escalates)
   - `:coordinate-shipment` — outbound product shipment coordination proposal

2. **Watch & Clock Plant Operations Governor** (`watchmfg.governor`,
   independent validation layer, never trusts the advisor's own
   self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into twelve concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct movement-assembly/casing/regulation/testing-
     equipment control); `:actuate-equipment? true` on a maintenance
     schedule (directly actuating movement-assembly/testing equipment)
     is a PERMANENT block; `:issue-certification? true` on ANY
     proposal (self-issuing a COSC/ISO 3159 chronometer/accuracy
     certification) is a PERMANENT block; a shipment may not push a
     batch's own recorded shipped unit quantity past its own logged
     production quantity (independently recomputed); no double-
     scheduling the same maintenance record; no fabricated
     `:product-type` value; no physically implausible
     `:accuracy-test-seconds-per-day` value; no physically implausible
     `:defect-rate-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — battery/
   mercury-cell materials-safety hazard, precision-defect risk, COSC/
   ISO 3159 chronometer/accuracy certification, downstream consumer-
   safety and precision-quality consequence):
   - Does NOT control movement-assembly, casing, regulation, or testing equipment directly
   - Does NOT make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
   - Does NOT actuate movement-assembly/casing/regulation/testing equipment (permanently blocked,
     not a rollout milestone still to come — see `watchmfg.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - Does NOT self-issue a COSC/ISO 3159 chronometer/accuracy certification mark (permanently blocked, unconditional)
   - All proposals are `:effect :propose`; actuation and certification are human-/institution-approval-gated

4. **Self-contained domain logic**: `watchmfg.registry` pure functions
   (`equipment-ready?`, `batch-ready?`, `shipment-quantity-exceeded?`,
   `product-type-valid?`, `accuracy-test-seconds-per-day-valid?`,
   `defect-rate-valid?`) are re-verified independently by the
   governor, following the "ground truth, not self-report" discipline
   established by prior actors (most directly
   `cloud-itonami-isic-2710`'s `elecequipmfg.registry`).

5. **Store** (`watchmfg.store`): a single `MemStore` backend behind a
   `Store` protocol, tracking four entity kinds (batches, equipment,
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
   `github.com/cloud-itonami/cloud-itonami-isic-2652` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Watch/clock plant-operations back-office coordination is now
genuinely implemented and tested (not merely scaffolded). ISIC 2652
moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation, equipment actuation, or certification self-issuance,
independently corroborated by `watchmfg.phase`'s permanent exclusion
of `:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely watch/clock-
manufacturing-specific elaboration mirroring 2710's own two-entity-
kind gate — this domain has two distinct ground-truth entity kinds a
proposal can reference, and each is independently re-derived from its
own permanent record, never trusting the proposal's self-report.

(+) The certification-authority-blocked check is a genuinely new
elaboration this vertical needed with its own certification regime
(COSC/ISO 3159, distinct from 2710's UL/CE/IEC) — watch/clock
manufacturing is directly subject to voluntary precision/accuracy
certification with direct consumer-facing quality-claim consequence,
so the governor closes that scope-creep vector explicitly rather than
leaving it implicit in the closed op-allowlist alone.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 76 tests / 213 assertions
across 5 test namespaces (`watchmfg.operation-test`,
`watchmfg.governor-contract-test`, `watchmfg.phase-test`,
`watchmfg.store-contract-test`, `watchmfg.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch/certification-body
systems — scope is deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2652` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-2652`, initial commit
  `886d4c0aaa3227758c9080c3e2255924f65bf73a` (confirmed via
  `git merge-base --is-ancestor <local-head> origin/main` against a
  fresh `git fetch`).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 76 tests containing 213 assertions. 0 failures, 0 errors.`**
- `clojure -M:lint`: `linting took 1682ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-quantity-
  exceeded, equipment-actuate-blocked, certification-authority-
  blocked, already-scheduled, invalid-product-type, invalid-accuracy-
  test-seconds-per-day, invalid-defect-rate), with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:watch-clock-plant-
  operations-governor` is grep-verified UNIQUE fleet-wide (`gh search
  code "watch-clock-plant-operations-governor" --owner cloud-itonami`,
  zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"2652"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2652" ...}` block (no wholesale
  regeneration); `:repo`/`:business-id` corrected to point at
  `cloud-itonami/cloud-itonami-isic-2652`. The live `:implemented`
  count was recomputed fresh via `kotoba.industry/maturity-summary`
  (never `grep -c`) immediately before bumping the aggregate count
  assertion in `industry_test.clj`.
- Post-merge re-verification from a brand-new fresh clone (plus a
  fresh `../technology` sibling clone) reproduced a green result and
  confirmed zero UTF-8 mojibake (`grep -c "â"
  resources/kotoba/industry/registry.edn` = 0) and correct entry shape
  (`(kotoba.industry/maturity "2652")` => `:implemented`).
