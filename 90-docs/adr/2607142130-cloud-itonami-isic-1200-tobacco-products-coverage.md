# ADR-2607142130: cloud-itonami-isic-1200 (tobacco products) operations-coordination coverage

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (Wave 3 production/manufacturing actors), ADR-2607141200 (registry automation ADR), cloud-itonami-isic-1200 repo `docs/adr/0001-architecture.md`

## Context

ADR-2607121000 names tobacco products manufacturing (ISIC Rev.5 1200) as a Wave 3 production/manufacturing coverage target for the cloud-itonami actor fleet. This is the second manufacturing vertical (after ISIC 1010 meat processing) in the fleet's regulated-production scope.

Unlike energy (3512), water (3600), or consumer goods (non-regulated), tobacco manufacturing is subject to strict excise-tax tracking, health-warning labeling compliance, and controlled-substance auditing. The actor ONLY coordinates back-office workflow (batch logging, maintenance scheduling, compliance-concern escalation, shipment coordination) and NEVER:
- Operates processing-line equipment (licensed plant operator exclusive)
- Certifies health warnings (compliance officer exclusive)
- Signs off on excise-tax compliance (compliance officer exclusive)
- Makes or influences marketing or health claims (human/legal exclusive)

These responsibilities remain the responsibility of qualified tobacco manufacturers and licensed compliance officers.

The existing registry entry `{:id "1200" :maturity :spec ...}` referenced a dead link (`gftdcojp/cloud-itonami-C1200`). This ADR records the full implementation, promoting 1200 to `:implemented` status.

## Decision

Implement `cloud-itonami-isic-1200` as a tobacco-manufacturing back-office coordination actor following the established pattern (langgraph-clj StateGraph + independent Governor + Phase 0→3 rollout), adapted to tobacco's unique constraint: SCOPE = COORDINATION, not processing-line control or regulatory certification.

### Architecture summary

**TobaccoOpsAdvisor** — sealed LLM node making proposals for four operations:
- `:log-production-batch` — register new batch to production ledger
- `:schedule-maintenance` — propose equipment maintenance scheduling
- `:flag-compliance-concern` — escalate excise-tax/labeling compliance concern
- `:coordinate-shipment` — propose outbound product shipment coordination

**TobaccoManufacturingGovernor** — independent censor applying:
- **HARD holds** (6 checks):
  1. `no-spec-basis?` — jurisdiction must be cited (US, JP, EU)
  2. `evidence-incomplete?` — required regulatory documents missing
  3. `facility-permit-invalid?` — manufacturing authorization not current
  4. `batch-manifest-incomplete?` — product ID, quantity, ingredients missing
  5. `compliance-concern-unresolved?` — flagged regulatory concerns block work
  6. `already-logged?` or `already-shipment-finalized?` — prevents duplicate actions
- **SOFT escalations** (2 checks):
  - `low-confidence?` — proposals <0.6 confidence escalate
  - `high-stakes?` — real actuations (log/shipment) always escalate for human sign-off

**Phase gates** (0→3 rollout):
- Phase 0/1 (demo/pilot): escalate all real actuations
- Phase 2 (supervised): commit if governor approves; escalate on soft flags
- Phase 3 (full): commit if governor approves (no override)

**Store protocol** with MemStore (dev/test, atom-backed) reference implementation; DatomicStore (langchain.db) is a future seam.

**Audit trail** — every operation logs:
- `:advised` — advisor proposal (confidence, summary)
- `:held` — governor hold (violation details), if applicable
- `:committed` — operation committed (basis, summary), if applicable

### HARD invariants (scope enforcement)

1. **No processing-line equipment control.** All operations are `:propose` only. The advisor never proposes (and the governor never commits) equipment actuation, line speed, temperature, or processing decisions. These remain human operators' (licensed plant staff) exclusive responsibility.

2. **No regulatory certification.** Excise-tax filing compliance and health-warning certification ALWAYS escalate to human compliance officer review. The actor provides transparency and concern-flagging only; it never certifies or signs off on regulatory matters.

3. **No marketing or health claims.** The actor never proposes, reviews, or commits any text, claims, or content related to product marketing, health benefits, or harm reduction. These are human/legal/compliance exclusive.

4. **Facility registration required.** All operations require batch-id to reference a batch with valid facility permit, preventing operations at unregistered/unlicensed plants.

### Entity shape and operations

**Batch entity:**
- `:batch-id` (immutable key, e.g., "BATCH-2026-001")
- `:product-type` (one of `:cigarettes`, `:cigars`, `:pipe-tobacco`, `:smokeless`)
- `:jurisdiction` (regulatory jurisdiction: "US", "JP", "EU")
- `:batch-quantity` (number of units)
- `:ingredients-list` (vector of ingredient names)
- `:facility-permit-valid?` (boolean, current manufacturing authorization)
- `:evidence-checklist` (vector of required regulatory documents present)
- `:compliance-concern-raised?` (boolean, unresolved concern flag)
- `:production-logged?` (boolean, batch logged to production records)
- `:shipment-finalized?` (boolean, shipment coordination complete)

**Ops matrix:**

| Op | Effect | Always escalates? | Prerequisite |
|----|--------|-------------------|--------------|
| `:log-production-batch` | `:propose` | High-stakes, yes | Batch manifest complete, facility valid, no open concerns |
| `:schedule-maintenance` | `:propose` | No (low-stakes) | Batch registered |
| `:flag-compliance-concern` | `:propose` | **Always** | Batch registered |
| `:coordinate-shipment` | `:propose` | High-stakes, yes | Batch logged, no open concerns |

## Consequences

(+) Tobacco manufacturing's coordination workflow is now formally covered by the cloud-itonami actor pattern, with audit trail and independent governance discipline matching every prior sibling (energy, water, insurance, plant propagation, etc.).

(+) Scope is tight and explicit: coordination-only, zero processing-line control or regulatory certification authority. HARD invariants prevent silent scope creep (no equipment actuation, no health-warning signing, no marketing claims ever pass the governor).

(+) HARD check for unresolved compliance concerns prevents batches from proceeding with flagged/unreviewed regulatory issues, matching tobacco industry's own compliance discipline (a compliance officer should not be able to silently log a batch with flagged excise-tax or labeling concerns).

(+) Facility permit check ensures only licensed, registered plants can log batches, preventing gray-market or unlicensed operations.

(+) Audit trail is complete and chronological. Every decision is logged with advisor reasoning, governor verdict, and outcome, enabling compliance audit and regulatory retrospectives.

(+) High-stakes escalation (batch logging, shipment coordination) ensures human sign-off for all real actuations, matching regulated industry's governance needs.

(+) Phase gates allow demo/pilot phases to escalate cautiously; Phase 2 allows selective autonomy once governance is proven; Phase 3 enables full auto-commitment when confidence is warranted.

(-) No real-time monitoring of production-line conditions (temperature, humidity, equipment status). The actor responds to escalated concerns, not forecasts them.

(-) No integration with external excise-tax filing systems. Tax tracking is logged; actual filing remains a human/compliance-officer function.

(-) No integration with health-warning design systems. The actor flags labeling concerns; actual warning text is human/legal/compliance exclusive.

## Verification

- `cloud-itonami-isic-1200` repository created at
  `https://github.com/cloud-itonami/cloud-itonami-isic-1200`
- Commit `d6d6f0f` (main branch)
- Test suite: 26 tests green (governor holds, spec-basis, facility-permit, batch-manifest, compliance-concern, already-logged, already-shipment-finalized, registry functions, facts lookups, phase transitions)
- All source code is `.cljc` (portable to JVM / ClojureScript / nbb)
- Demo (`clj -M:dev:run`) walks batch lifecycle
- Repository README documents operations, HARD invariants, and audit trail
- Repository docs include GOVERNANCE.md (compliance-only escalation rules) and SECURITY.md (audit ledger immutability, HARD invariants never bypassed)
- Registry entry updated: `{:id "1200" :maturity :implemented :repo
  "https://github.com/cloud-itonami/cloud-itonami-isic-1200" ...}`
  (see ADR-2607141200 registry-update trace for merge SHA)
