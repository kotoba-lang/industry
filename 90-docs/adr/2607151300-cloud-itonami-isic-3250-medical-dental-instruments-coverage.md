# ADR-2607151300: cloud-itonami-isic-3250 (Manufacture of medical and dental instruments and supplies) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607156500 (cloud-itonami-isic-2710 Electric motors,
generators, transformers coverage); ADR referenced in
`cloud-itonami-isic-2211`'s own repo (closest domain analog, back-
office plant-operations-coordination actor pattern)

## Context

The `kotoba-lang/industry` registry carries a `:spec`-maturity entry
for ISIC 3250 ("Manufacture of medical and dental instruments and
supplies"). No repository existed for it prior to this ADR (confirmed
via `gh api repos/gftdcojp/cloud-itonami-C3250` and
`repos/cloud-itonami/cloud-itonami-isic-3250` both returning 404
before scaffold). This ADR records a fresh from-scratch scaffold of a
governed "actor" implementing this ISIC class, following the same
protocol as the prior verified promotions in this fleet (most
recently `cloud-itonami-isic-2211`/`cloud-itonami-isic-2710`), as part
of the ongoing careful, smaller-batch rollout that replaced an earlier
18-agent haiku batch which had a 61% defect rate.

Per the registry's live `:name` field, ISIC 3250 is verified to be
"Manufacture of medical and dental instruments and supplies" — this
matches the assumed premise (no mislabeling of the kind found in prior
fleet runs, e.g. 0892 assumed=salt/actually=peat, 0144
assumed=swine/actually=sheep-goats).

## Decision

Scaffolded `cloud-itonami/cloud-itonami-isic-3250`: MedInstrAdvisor ⊣
Medical Instrument Plant Operations Governor, a langgraph-clj
StateGraph actor with an append-only audit ledger, mirroring
`cloud-itonami-isic-2211`'s (rubber tyres and tubes) verified
architecture closely, adapted to the medical/dental-instrument plant
hazard profile. Full rationale, module-by-module design, and the
domain-analog comparison to `cloud-itonami-isic-2211` live in the
child repo's own `docs/adr/0001-architecture.md`.

### Scope: plant operations coordination, not instrument-line control

This is a plant OPERATIONS COORDINATION actor, NOT direct machining/
molding/sterilization-line control authority, and NOT a regulatory-
clearance authority (FDA 510(k) / CE-mark). Four ops, all `:effect
:propose` only:

- `:log-production-batch` — machining/molding/sterilization batch,
  output-quality/lot-traceability data logging
- `:schedule-maintenance` — machining/molding/sterilization-equipment
  maintenance scheduling proposal
- `:flag-safety-concern` — surface a sterility-validation-failure/
  materials-biocompatibility/device-defect concern, ALWAYS escalates
- `:coordinate-shipment` — outbound product shipment coordination

### Governor rules (12 concrete checks elaborating 4 HARD invariants)

HARD (always `:hold`, no override):
1. Plant/batch record (equipment for maintenance, batch for shipment)
   must be independently verified/registered before any action is
   taken against it (`equipment-not-verified` / `batch-not-verified`),
   and a shipment's quantity must independently recompute within the
   batch's own logged production quantity
   (`shipment-quantity-exceeded`).
2. The request's own `:effect` must be `:propose`
   (`not-propose-effect`); `:op` must be in the closed four-op
   allowlist (`unknown-op`); the proposal's own `:effect` must be one
   of the four propose-shaped effects (`equipment-control-blocked`).
3. Directly actuating machining/molding/sterilization equipment
   (`:actuate-equipment? true`) is a PERMANENT, unconditional block
   (`actuate-equipment-blocked`); self-issuing an FDA 510(k) clearance
   / CE conformity mark (`:issue-clearance? true`, any op) is a
   PERMANENT, unconditional block (`clearance-authority-blocked`).
4. No double-scheduling the same maintenance record
   (`already-scheduled`); no fabricated `:device-class`
   (`invalid-device-class`); no physically/regulatorily implausible
   `:sterility-assurance-level` (`invalid-sterility-assurance-level`);
   no physically implausible `:nonconformance-rate-percent`
   (`invalid-nonconformance-rate`).

ESCALATE (SOFT, human sign-off, human may approve): `:flag-safety-
concern` always escalates regardless of confidence; low-confidence
proposals escalate.

## Consequences

(+) Medical-and-dental-instrument plant operations back-office now has
a documented, governed, auditable coordination layer.

(+) Scope is bounded: equipment-actuation and regulatory-clearance
self-issuance are permanently, unconditionally blocked — no phase and
no human approval can override either.

(-) Still a simulation/proposal layer; no integration with real plant-
management databases, freight dispatch, or regulatory-body APIs.

## Verification

- `clojure -M:test` (fresh clone, `kotoba-lang/langgraph` +
  `kotoba-lang/langchain` as `../../kotoba-lang/*` siblings):
  `Ran 77 tests containing 209 assertions. 0 failures, 0 errors.`
- `clojure -M:lint` (clj-kondo): `linting took 530ms, errors: 0,
  warnings: 0`.
- `clojure -M:dev:run` demo narrative exercises the full happy path
  (batch logging auto-commit, maintenance-schedule/safety-concern/
  shipment-coordination escalate+approve) and every HARD-hold scenario
  directly: not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, clearance-authority-blocked, already-scheduled,
  invalid-device-class, invalid-sterility-assurance-level,
  invalid-nonconformance-rate. Exit code 0, no exceptions.
- All source is `.cljc` — no JVM-only interop; the actor graph is
  invoked exclusively via `langgraph.graph/run*`.
- Repo: <https://github.com/cloud-itonami/cloud-itonami-isic-3250>,
  commit `1e47fddadfc32a1236f6c2332ede357850c2a969` on `main`,
  confirmed landed on `origin/main` via
  `gh api repos/cloud-itonami/cloud-itonami-isic-3250/commits/main`.
- `kotoba-lang/industry` registry: `"3250"` entry's `:maturity`
  updated `:spec` -> `:implemented`, `:repo` ->
  `https://github.com/cloud-itonami/cloud-itonami-isic-3250`,
  `:business-id` -> `cloud-itonami-isic-3250`. Merge SHA and
  post-merge fresh-clone re-verification recorded in the companion
  `.edn` file for this ADR once the registry PR lands (see that
  file's `:registry-merge` key).
