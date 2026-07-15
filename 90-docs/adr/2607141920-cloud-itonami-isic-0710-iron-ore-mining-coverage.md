# ADR-2607141920: cloud-itonami-isic-0710 — Iron Ore Mining Operations Coordinator

## Status

Accepted. `cloud-itonami-isic-0710` promoted from `:blueprint` to `:implemented`
in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.5 0710 (Mining of iron ores) adds a 90th actor to the cloud-itonami
fleet, implementing the iron ore mining operations coordination blueprint. The
actor is an LLM-backed operations coordinator (IronOps-LLM ⊣ Iron Ore Governor)
for production logging, maintenance scheduling, safety concern flagging, and
shipment coordination.

**Scope**: COORDINATION ONLY. The actor does NOT propose extraction authority,
blasting operations, or mine-safety-authority decisions—those are permanently
excluded by the Governor and escalate to human specialists.

## Decision

### 1. Scope boundary: coordination-only actor

This actor proposes four coordination operations:
- `:propose/log-production` — ore output/grade data logging
- `:propose/schedule-maintenance` — equipment maintenance scheduling
- `:propose/flag-safety-concern` — surface mine-safety concerns (always escalates)
- `:propose/coordinate-shipment` — outbound ore shipment coordination

Permanently forbidden operations (HARD blocks, no override):
- `:extraction/extract` — extraction authority
- `:extraction/blast` — blasting operations
- `:authority/safety-clearance` — mine-safety-authority decisions

This mirrors the cloud-itonami architecture discipline: extracted verticals
(retail/4711, freight/4920, now mining-ops/0710) handle coordination and
proposals only; specialist verticals or human authorities handle extraction
and safety authority.

### 2. Governor rules: five checks, all HARD

1. **Forbidden operation** — any proposal attempting extraction/blasting/
   authority is rejected unconditionally.
2. **Spec-basis citation** — all proposals must cite official sources, not
   invent requirements.
3. **Site/mine record verification** — all operations require a verified
   (`:verified? true`) site/mine record to exist.
4. **Safety concern escalation** — `:propose/flag-safety-concern` ALWAYS
   escalates to human, even if clean on all other checks.
5. **Confidence floor** — low confidence (< 0.6) escalates to human.

### 3. Comparison with sibling actors

**Quarrying (0810)**: dual-actuation shape (extract, then ship). This actor
does both real extraction and real shipment. IronOps-0710 does neither; both
are proposals for human coordination.

**Retail (4711)**, **Freight (4920)**: similar coordination-bounded shape.
IronOps-0710 follows the same pattern: propose and coordinate, never execute
capital extraction.

## Consequences

- 90th actor in the cloud-itonami fleet (89 implemented before this build).
- Establishes a permanent scope boundary: no extraction/blasting authority
  in future mining-ops actors (can be referenced as precedent).
- Registry entry updated: repo pinned to `https://github.com/cloud-itonami/
  cloud-itonami-isic-0710`, maturity set to `:implemented`.
- Test suite: 16 tests / 35 assertions covering store contract, governor
  rules, safety escalation, site verification, and forbidden operations.
- Demo: walks three coordination scenarios (production logging, safety
  escalation, shipment coordination) and three forbidden-operation blocks.

## References

- `cloud-itonami-isic-0810/docs/adr/0001-architecture.md` (quarrying,
  template for extraction-bounded scope reasoning)
- `cloud-itonami-isic-0710/docs/adr/0001-architecture.md` (local ADR,
  full architecture decision record)
- Federal Mine Safety and Health Act (Mine Act), 30 U.S.C. §801 et seq. (US)
- Work Health and Safety Regulations (Australia)
- 鉱山保安法 (Mine Safety Act) (Japan)
