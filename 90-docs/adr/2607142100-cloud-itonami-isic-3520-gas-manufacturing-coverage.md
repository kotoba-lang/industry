# ADR-2607142100: cloud-itonami-isic-3520 — Gas Manufacturing Operations Actor

**Status**: ACCEPTED

**Context**

ISIC Rev.5 3520 (Manufacture of Gas) covers the production, treatment, and
distribution of gas fuels to end consumers. Community gas operators — municipal
utilities, cooperatives, and independent licensed operators — need auditable
supply provisioning and suspension systems with hard safety gates, especially
to prevent disconnection of life-support and critical-infrastructure meters
(hospitals, fire stations, water treatment, emergency services).

The cloud-itonami pattern (LLM Advisor ⊣ independent Governor + audit ledger)
proven in 48+ prior verticals (electric power, energy, finance, insurance, HR,
telecom, manufacturing, etc.) applies directly: a Gas Supply Advisor drafts
provisioning and suspension proposals, a Gas Safety Governor enforces hard
checks (spec-basis citations, evidence completeness, protected-recipient
gates), and an immutable audit ledger records every decision.

**Decision**

Scaffold and implement `cloud-itonami-isic-3520` (Manufacture of gas) as
a full actor in the established pattern:

1. **Advisor** (`gas.advisor`): drafts supply provisioning and suspension
   proposals (mock advisor for demo, extensible for LLM integration).

2. **Governor** (`gas.governor`): FIVE HARD gates that cannot be overridden:
   - Spec-basis: must cite official jurisdiction requirements
   - Evidence complete: required checklist must be satisfied before actuation
   - Protected recipient: life-support/critical-infrastructure meters can
     NEVER be suspended
   - Already provisioned/suspended: double-operation guards (dedicated
     boolean flags, not `:status` value)
   - Confidence floor / actuation gate: high-stakes operations escalate to human

3. **Store** (`gas.store`): MemStore (demo) and DatomicStore (production)
   backends, same protocol contract, immutable audit ledger.

4. **Facts** (`gas.facts`): per-jurisdiction (JPN, USA, GBR) gas-supply
   requirements with official spec-basis citations; honest coverage reporting
   (3 of ~194 jurisdictions seeded, starting catalog).

5. **Phase** (`gas.phase`): lifecycle phases (read-only → intake →
   verification → supervised) with critical invariant: neither
   `:actuation/provision-supply` nor `:actuation/suspend-supply` is in any
   phase's `:auto` set (actuation always human-approved).

6. **Registry** (`gas.registry`): draft provision/suspension records and
   protected-recipient violation guard.

7. **Tests**: governor contract (spec-basis, protected-recipient, double-
   operation guards, escalation), phase invariants (actuation never auto),
   facts coverage (jurisdiction citations), store contract (ledger append,
   sequence counters, guard flags).

**Implementation details**

- **Protected recipient policy**: Meters serving hospitals, fire stations,
  water sanitation, emergency services, nursing homes/schools are marked
  `:protected-recipient? true` and can NEVER be suspended, not even with
  human approval (HARD gate enforced in governor, tested in full suite).
- **Negative actuation**: Suspension is a negative actuation (like 3600 water
  safety and 6190 telecom); provision is positive. Both require human
  sign-off (governor confidence gate + phase table).
- **Cljs-first portable**: all source is `.cljc`, zero JVM-only constructs.
  Standalone forkable: deps.edn uses git coordinates for langgraph-clj,
  local/root only for monorepo `:dev-local` alias.
- **Honest coverage**: starting catalog (3 seeded jurisdictions) with explicit
  coverage reporting; no fabricated requirements (ADR-2607022900 discipline).
- **Test suite**: 21 tests, 59 assertions; spec-basis, evidence-incomplete,
  protected-recipient, already-provisioned/suspended guards, confidence
  escalation, phase invariants, facts jurisdiction coverage, store contract
  (ledger, sequence, retrieval).

**Consequences**

- Certified gas operators can fork and deploy independently, with auditable
  decision records and hard safety gates protecting critical infrastructure.
- All further gas-supply-specific extensions (smart metering, demand response,
  carbon tracking, etc.) are additive: new gates with new tests, following
  the same "governor re-verifies before actuation" discipline.
- Two NEGATIVE actuation verticals (3600 water, 6190 telecom, now 3520 gas)
  establish the pattern for safety-critical suspension/disconnection as a
  discipline: can-never-override protected-recipient gates, unconditional
  evaluation, two independent enforcement layers.

**Addendum 1** (2026-07-14, timestamp collision resolution)

Same-day concurrent ADRs (8422 at 2607142000, 3520 at 2607142100) covering
separate ISIC classes with no interaction; no conflict.

---

Repo: [`cloud-itonami/cloud-itonami-isic-3520`](https://github.com/cloud-itonami/cloud-itonami-isic-3520)
Registry: [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
