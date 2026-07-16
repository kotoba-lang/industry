# ADR-2607081600: `cloud-itonami-isic-8691` (health access navigation) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607081200 (`cloud-itonami-isic-9491`, religious congregation)
- ADR-2607081300 (`cloud-itonami-isic-2610`, semiconductor fab)
- ADR-2607081400 (`cloud-itonami-isic-3512`, community renewable energy)
- ADR-2607081500 (`cloud-itonami-isic-8810`, community care coordination)
- `cloud-itonami-isic-8691/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-8691` publishes an OSS business blueprint for
health-access navigation: care navigation, referral coordination,
appointment support and evidence-backed health-access workflows,
helping people find and enroll in the right health-benefits program or
care provider. Like every prior vertical in this fleet, the blueprint
text alone is not an implementation — this ADR records the governed-
actor build that promotes `cloud-itonami-isic-8691` from `:blueprint`
to `:implemented` in the `kotoba-lang/industry` registry, the thirty-
seventh vertical built outside ADR-2607032000's original insurance/
real-estate batch.

## Problem

1. `cloud-itonami-isic-8691`'s blueprint described a real-world health-
   access-navigation operating model (seeker intake, eligibility
   assessment, urgent-risk screening, referral finalization, health-
   information disclosure) but had no governed-actor implementation:
   no Store, no Governor, no rollout phasing, no tests.
2. The blueprint's own Trust Controls name concrete hold conditions (no
   diagnosis by the LLM, urgent-risk escalation cannot be suppressed,
   health-data disclosure requires consent and purpose, referral
   recommendations cite source eligibility rules) and two high-stakes
   acts (finalizing a referral, disclosing health information) —
   these needed a concrete, testable HARD-check mapping, not just
   prose.
3. `blueprint.edn` carried a stale pre-rename `:itonami.blueprint/id`
   (`"cloud-itonami-8691"`, missing the `isic-` infix) and was
   entirely missing `:required-technologies`/`:optional-technologies`,
   both needing reconciliation against the `kotoba-lang/industry`
   registry's own stated values before the promotion could be
   considered internally consistent.
4. This fleet already has 23 distinct `-unresolved-violations` checks,
   including two semantically adjacent to a health/safety-risk concept
   (`casework`'s fraud-risk-flag, `care`'s/`congregation`'s
   safeguarding concepts) — this build needed to determine, by reading
   those siblings' actual source rather than assuming from a shared
   word ("risk"/"unresolved"), whether its own screening check was a
   genuine reuse or a distinct concept.

## Decision

1. **Entity and op shape.** Primary entity `seeker` (a person seeking
   health-access navigation). Five ops: `:seeker/intake`,
   `:eligibility/verify`, `:risk/screen`, `:actuation/finalize-
   referral` (high-stakes), and `:actuation/disclose-health-
   information` (high-stakes) — a dual-actuation-on-one-entity shape
   grounded directly in the blueprint's own Core Contract ("cannot
   diagnose, conceal urgent risk, or disclose health information
   outside consent and purpose").
2. **`eligibility-window-elapsed-exceeds-validity?` — 6th MAXIMUM-
   ceiling check.** Following `facility`, `school`, `card`, `recovery`
   and `care`, this applies the same ceiling-only comparison to a
   seeker's elapsed days since eligibility was determined against
   their own recorded validity-window days, gating only `:actuation/
   finalize-referral`.
3. **`urgent-health-risk-unresolved-violations` — 35th unconditional-
   evaluation screening grounding, explicitly distinguished from two
   adjacent siblings.** Every prior sibling's `-unresolved-violations`
   check name was grepped (23 found) before this claim was finalized.
   The two semantically closest were read directly: `casework.
   governor/risk-flag-unresolved-violations` verifies a fraud/
   misrepresentation risk; `care.governor/safeguarding-signal-
   unresolved-violations`/`congregation.governor/safeguarding-concern-
   unresolved-violations` verify neglect/abuse-adjacent safeguarding
   concerns. This actor's check verifies a distinct concept: an urgent
   HEALTH/safety risk surfaced during triage, grounded in the
   blueprint's own "urgent-risk escalation cannot be suppressed" Trust
   Control. Gates both `:risk/screen` and `:actuation/finalize-
   referral`.
4. **Dedicated double-actuation-guard booleans.**
   `:referral-finalized?`/`:disclosure-made?` on the `seeker` record,
   never a single `:status` value.
5. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/navigator/store_contract_test.clj`. The per-entity accessor
   is safely named `seeker` directly — unlike `care.store`'s `case`
   entity (ISIC 8810, this fleet's immediately prior build), which
   needed a `case-of` rename to avoid colliding with Clojure's `case`
   special form, `seeker` has no such collision.
6. **Phase 0→3 rollout** — Phase 3 `:auto` set is `{:seeker/intake}`
   only; both actuations are permanently excluded from every phase's
   `:auto` set.
7. **No bespoke domain capability lib** — runs on the generic
   robotics/identity/forms/dmn/bpmn/audit-ledger/optimization stack.
8. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
9. **`blueprint.edn` field-sync fixes** — corrected the stale
   `:itonami.blueprint/id` and added the missing `:required-
   technologies`/`:optional-technologies` fields to match the
   `kotoba-lang/industry` registry's own entry for `"8691"`.

## Consequences

- Fifty-first actor in this fleet (50 implemented before this build).
- Confirms the MAXIMUM-ceiling check family generalizes to a sixth,
  genuinely distinct domain (eligibility-window currency).
- Establishes a genuinely new unconditional-evaluation-screening
  concept (urgent-health-risk), explicitly distinguished from two
  semantically adjacent siblings by reading their actual source.
- `MemStore` ‖ `DatomicStore` parity proven by contract test.
- Two pre-existing `blueprint.edn` inconsistencies fixed as in-scope
  minor consistency work.
- Fleet maturity: `:implemented` 50 → 51, `:blueprint` 34 → 33,
  `:spec` 546 unchanged, total 643.
- Test status: 36 tests / 173 assertions, lint clean, demo verified
  end-to-end.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Reuse `casework.governor/risk-flag-unresolved-violations`'s name | Casework's check is a fraud/misrepresentation-risk concept, not a health/safety-risk concept — confirmed by reading source, not just the shared word "risk" |
| A single "seeker-safety" check merging eligibility-window and urgent-health-risk concerns | Eligibility-window elapsed is a ground-truth numeric recompute; urgent-health-risk status is an unconditionally-evaluated flag needing the screening op to self-hold — merging loses that property |
| A third actuation for "urgent-risk escalation" | The unconditional `urgent-health-risk-unresolved` HARD-hold already forces escalation (referral cannot finalize until re-screened) — a separate actuation would duplicate the same invariant with a less-established shape |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8691/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8691/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8691/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"8691"`)
