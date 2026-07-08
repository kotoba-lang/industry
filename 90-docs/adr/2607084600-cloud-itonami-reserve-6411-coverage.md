# ADR-2607084600: `cloud-itonami-isic-6411` (central banking) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607084300 (`cloud-itonami-isic-8790`, other residential care activities)
- ADR-2607084400 (`cloud-itonami-isic-8542`, cultural education)
- `cloud-itonami-isic-6411/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-6411` publishes an OSS business blueprint for
central banking: currency issuance, reserve-account administration,
and interbank settlement operations for member/commercial banks. Like
every prior vertical in this fleet, the blueprint text alone is not
an implementation — this ADR records the governed-actor build that
promotes `cloud-itonami-isic-6411` from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry, the fifty-
fifth vertical built outside ADR-2607032000's original insurance/
real-estate batch.

## Problem

1. `cloud-itonami-isic-6411`'s blueprint described a real-world
   central-banking operating model (member-bank intake, account
   verification, reserve-account opening, settlement-batch release)
   but had no governed-actor implementation: no Store, no Governor,
   no rollout phasing, no tests.
2. The blueprint's own operator-guide names TWO real-world acts
   together ("opening a reserve account or releasing an interbank
   settlement batch") — this build needed to resolve whether that is
   one conceptual act or two distinct actuations.
3. Central banking carries a genuine, real-world compliance concern
   distinct from every prior sibling: correspondent-banking due
   diligence (does a member bank's own correspondent relationships
   satisfy AML/sanctions due-diligence obligations) — no dedicated
   check for this concept exists anywhere else in this fleet.
4. The blueprint's own `:required-technologies` uniquely names
   `:banking` (`kotoba-lang/banking`) — this build needed to resolve
   whether to add a real code dependency on it or follow this fleet's
   established "implement the specific check directly" precedent.

## Decision

1. **Dual-actuation-on-one-entity shape.** Matching `nursing`/8710's,
   `laundry`/9601's, `holdco`/6420's and `residential`/8790's
   precedent — opening a reserve account (an onboarding act) and
   releasing a settlement batch (a recurring transactional act) are
   conceptually distinct real-world acts on the SAME member-bank
   entity. `high-stakes` is the two-member set `#{:actuation/open-
   reserve-account :actuation/release-settlement-batch}`, each with
   its own history collection, sequence counter and dedicated double-
   actuation-guard boolean.
2. **Entity and op shape.** Primary entity `member`. Five ops:
   `:member/intake`, `:account/verify`, `:duediligence/screen`,
   `:actuation/open-reserve-account` (high-stakes), `:actuation/
   release-settlement-batch` (high-stakes).
3. **`correspondent-banking-due-diligence-unresolved-violations` —
   the 54th unconditional-evaluation grounding, a genuinely new
   concept.** Grep-verified absent (zero hits for "correspondent-
   bank"/"correspondent-due-diligence" across every prior sibling).
   Grounded in FATF correspondent-banking guidance, Basel Committee
   correspondent-banking due-diligence standards, and US Bank Secrecy
   Act 31 CFR §1010.610. Gates `:duediligence/screen` and both
   actuations.
4. **`reserve-ratio-insufficient?` — an honest tenth MINIMUM-
   threshold instance, gating account-opening only.** Grounded in US
   Regulation D reserve requirements and ECB minimum reserves under
   Regulation (EC) No 1745/2003.
5. **`settlement-batch-exceeds-available-reserve-balance?` — an
   honest eleventh MAXIMUM-ceiling instance, gating settlement-
   release only.** A direct mapping onto real-time gross settlement
   (RTGS) liquidity-sufficiency practice. Deliberately a DIFFERENT
   check from Decision 4, since each actuation has its own distinct
   real-world ground truth.
6. **TWO dedicated double-actuation-guard booleans.** `:reserve-
   account-opened?` and `:settlement-batch-released?`, never a single
   `:status` value, each with its own history/sequence.
7. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/reserve/store_contract_test.clj`. The per-entity accessor is
   safely named `member` directly.
8. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:member/
   intake}` only; both actuations are permanently excluded from every
   phase's `:auto` set.
9. **No bespoke domain capability lib as a code dependency, despite
   `:banking` being named.** Following `banking`/6419's, `research`/
   `aerospace`/`fab`'s and `holdco`/6420's precedent, this R0
   implementation does NOT add a real `kotoba-lang/banking`
   dependency — the specific ground-truth checks a governor needs
   (plain numeric comparisons and a boolean flag) are implemented
   directly.
10. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
    `llm-advisor` with a defensive EDN-proposal parser.

## Consequences

- Sixty-ninth actor in this fleet (68 implemented before this
  build).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (correspondent-banking-due-diligence-unresolved), grep-
  verified absent from every prior sibling before the claim was
  finalized.
- Documents an honest TENTH MINIMUM-threshold instance and an honest
  ELEVENTH MAXIMUM-ceiling instance, each gating a different one of
  this actor's two actuations, neither claimed as new.
- Confirms the dual-actuation-on-one-entity shape generalizes to a
  5th instance in this fleet (nursing, laundry, holdco, residential,
  reserve).
- `MemStore` ‖ `DatomicStore` parity is proven by `test/reserve/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 68 → 69, `:blueprint` 16 → 15,
  `:spec` 546 unchanged, total 643.
- Test status: 41 tests / 190 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 117 assertions,
  all green, no pre-existing fixture referenced ISIC "6411".
- No real `kotoba-lang/banking` dependency added, matching this
  fleet's established narrow-scope-R0 precedent.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A single-actuation shape (account-opening + settlement-release as one act) | Unlike `sports`/8541's ambiguous either/or phrasing, these are two conceptually distinct real-world acts recurring at different points in a member's lifecycle |
| A single shared ground-truth check gating both actuations | Reserve-ratio sufficiency (an eligibility concern) and settlement-balance sufficiency (a per-transaction liquidity concern) are genuinely different facts about a member bank |
| Adding a real `kotoba-lang/banking` dependency | Following `banking`/6419's, `research`/`aerospace`/`fab`'s and `holdco`/6420's precedent, the specific checks can be implemented directly without an external capability library |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-6411/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-6411/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-6411/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"6411"`)
