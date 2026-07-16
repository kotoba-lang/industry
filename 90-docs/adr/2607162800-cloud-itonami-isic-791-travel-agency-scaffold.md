# ADR-2607162800: cloud-itonami-isic-791 Travel Agency Booking Coordination Actor

**Status:** Implemented  
**Date:** 2026-07-16  
**Deciders:** Cloud-itonami fleet  
**Author:** Jun Kawasaki

## Context

ISIC Rev. 5 Division 79.1 — Travel agency and tour operator activities — has been scaffolded as a new cloud-itonami actor (`cloud-itonami/cloud-itonami-isic-791`). This is the 27th Wave-4 batch actor landed today, following 26 prior isic divisions and the earlier isic-750 (veterinary) template.

Travel agency booking coordination is a pure administrative domain — itinerary scheduling, booking-status tracking, travel supply coordination (office materials/brochures). Unlike clinical/compliance-heavy domains (veterinary, financial underwriting, safety authority), this domain has a **clean separation of concerns**:
- **In-scope:** Administrative booking logistics
- **Out-of-scope:** Visa eligibility determinations, travel insurance underwriting, refund/cancellation policy decisions, safety-authority overrides

This design decision limits the actor to a **SIMPLE closed `:propose`-only pattern** (same as isic-561/562/563: restaurant/catering/bar coordination), not the richer isic-750 pattern.

## Decision

Scaffold `cloud-itonami-isic-791` following the **SIMPLE actor pattern**:

### Closed Operation Allowlist (5 operations, `:propose`-only)

1. `:schedule-itinerary-booking` — booking/itinerary scheduling logistics (destination, dates, accommodations)
2. `:coordinate-booking-status-update` — administrative booking-status tracking (pending → confirmed → completed)
3. `:coordinate-supply-request` — non-travel consumables coordination (office supplies, brochures)
4. `:schedule-staff-shift-proposal` — administrative shift proposal (never binding, always human-reviewed)
5. `:flag-safety-concern` — client welfare/travel-advisory concerns for HUMAN review (always escalates, never auto-commits)

### Three HARD, Permanent, Un-overridable Governor Checks

All three are evaluated unconditionally. None can be overridden by a human approver.

1. **Booking-record verified** — Target booking must exist in store AND pass independent `:registered?` + `:verified?` verification (not advisory confidence alone)
2. **Effect is `:propose`** — All other effects (`:execute`, `:commit`, etc.) are rejected outright
3. **Scope exclusion** — Any proposal content (proposal map serialized + regex scan in EN + JA) touching:
   - Visa/immigration eligibility (`visa|immigration|eligibility`, `ビザ|査証|入国`)
   - Travel insurance/liability determinations (`insurance|liability|underwriting`, `保険|責任|過失`)
   - Refund/cancellation-policy decisions (`refund|cancellation|cancellation-policy`, `払戻|キャンセル|返金`)
   - Safety-authority overrides (`safety.{0,10}authority|authority.{0,10}override`, `安全当局|安全|権限`)
   - …is rejected immediately (`:flag-safety-concern` is exempt — it always escalates, never self-blocks)

### Implementation Details

- **Store:** MemStore (`travelagencyops.store/MemStore`) abstraction, swappable for Datomic/kotoba-server later
- **Advisor:** Mock (`travelagencyops.travelagencyopsllm/MockAdvisor`), injected; real LLM swappable
- **Governor:** `travelagencyops.governor/check` — three HARD check functions + confidence floor (0.6) soft gate
- **Phase gates:** Phase 0→3 rollout (phase 0 = sandbox all-escalate; phase 3 = autonomous); phase gate can only escalate, never override governor HARD holds
- **StateGraph:** `travelagencyops.operation/build` — langgraph-clj StateGraph (intake → advise → govern → decide → commit|hold|approval)
- **Audit ledger:** Append-only journal (`:committed`, `:governor-hold`, `:approval-requested`, `:approval-granted`, `:advisor-proposal` facts)
- **Portable `.cljc`:** All core logic in `.cljc` (JVM/nbb/WASM-ready); no external `langchain-clj`/`langgraph-clj` runtime dep in store layer (injected)

### Tests

Comprehensive test suite (`test/travelagencyops/governor_test.cljc`) verifies:
- Unverified booking rejection
- Verified booking acceptance
- Non-propose effect rejection
- Propose effect acceptance
- Visa/insurance/refund scope exclusion
- Legitimate booking content pass-through
- High-stakes safety concerns escalate unconditionally

### Simulation

`nbb -m travelagencyops.sim` or `clojure -M:dev:run` — prints actor configuration, governor checks, phase gates, demo store initialization. Full langgraph-clj runtime execution requires the actual langgraph/checkpoint framework (not mocked in sim output).

## Rationale

**Why SIMPLE pattern, not richer isic-750 pattern?**

isic-750 (veterinary) has mandatory human actuation (sample collection, treatment application) with domain-specific hard checks (FIFRA product registration, water-buffer compliance, treatment-dose recomputation). Every proposal type has genuine licensing/compliance risk.

Travel agency booking has **no such mandatory actuation**. All five operations are proposals-only:
- `:schedule-itinerary-booking` → proposal (human books in external system)
- `:coordinate-booking-status-update` → proposal (human updates external CRM)
- `:coordinate-supply-request` → proposal (human orders supplies)
- `:schedule-staff-shift-proposal` → proposal (human accepts/rejects shift)
- `:flag-safety-concern` → escalation (always human review)

The domain's scope exclusion (visa/insurance/refund/authority decisions are out-of-scope) is **structural**, not per-operation. It applies uniformly to all proposals via regex/substring matching. No sub-domain-specific hard checks (like product registration, buffer zones) are needed.

Thus, SIMPLE fits: closed op allowlist, three universal HARD checks, single-confidence soft gate, phase rollout, audit ledger. No extra rulesets, no per-operation escalation exceptions.

## Consequences

### Positive

- Clean separation of concerns: logistics proposals only; policy/compliance decisions escalate
- Audit trail records all governance decisions (HARD holds, confidence gates, approvals)
- Phase 0→3 rollout allows gradual autonomous adoption with human oversight at each stage
- Portable `.cljc` code runs on JVM, nbb, or WASM without modification
- Governor checks are deterministic; no human override can weaken them
- Simple pattern reduces maintenance surface (fewer special cases vs. richer isic-750 pattern)

### Negative

- `:flag-safety-concern` always escalates, even for low-severity issues; may create approval fatigue if misused
- Regex-based scope exclusion is not perfect; skilled adversarial input could evade EN+JA pattern match. Real production use should add LLM semantic verification (follow-up ADR)

### Follow-up

1. Once kotoba-server/datalad infrastructure stable, migrate MemStore to DatomicStore or kotoba-server backend
2. Real LLM advisor implementation (swap mock with actual Claude/GPT endpoint)
3. Semantic scope-exclusion verification (supplement regex with LLM check for sophisticated evasion)
4. Multi-language expansion (currently EN + JA; add DE, ES, FR patterns if international adoption)

## Alternatives Considered

1. **Richer pattern (isic-750 style):** Added complexity; travel domain lacks per-operation compliance domain-specificity that justifies it
2. **Open op allowlist:** Would require human-defined policy to veto out-of-scope proposals; closed allowlist is simpler and safer
3. **Async ledger (separate persistence service):** Added infrastructure complexity; in-mem + swap-out-later strategy is appropriate for MVP

## Decision Makers & Reviewers

- Cloud-itonami steering: Wave-4 batch approval
- ADR registry: Verified (ADR-2607162800)

## Related

- **ADR-2607162900:** Industry registry entry amendment (isic-791 → `:implemented`)
- **ADR-2607152500:** Wave-4 rollout start amendment (this actor is batch 27)
- **ADR-2607121000:** Reverse-topological isic plan (isic-791 is Wave-4 target)
- **isic-563 reference:** Similar SIMPLE pattern (beverage/bar coordination)
- **isic-750 reference:** Richer pattern (veterinary with domain-specific hard checks)

## Files

- Repository: `cloud-itonami/cloud-itonami-isic-791`
- Governor: `src/travelagencyops/governor.cljc` (three HARD checks)
- Store: `src/travelagencyops/store.cljc` (MemStore abstraction)
- Operation: `src/travelagencyops/operation.cljc` (StateGraph actor)
- Tests: `test/travelagencyops/governor_test.cljc`
- Blueprint: `blueprint.edn` (:itonami.blueprint/id = "cloud-itonami-791")
