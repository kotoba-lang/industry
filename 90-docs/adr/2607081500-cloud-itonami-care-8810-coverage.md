# ADR-2607081500: `cloud-itonami-isic-8810` (community care coordination) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607081100 (`cloud-itonami-isic-9420`, trade union)
- ADR-2607081200 (`cloud-itonami-isic-9491`, religious congregation)
- ADR-2607081300 (`cloud-itonami-isic-2610`, semiconductor fab)
- ADR-2607081400 (`cloud-itonami-isic-3512`, community renewable energy)
- `cloud-itonami-isic-8810/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-8810` publishes an OSS business blueprint for
community care coordination: check-ins, benefits navigation, caregiver
scheduling and non-residential support operations for older persons
and persons with disabilities. Like every prior vertical in this
fleet, the blueprint text alone is not an implementation — this ADR
records the governed-actor build that promotes
`cloud-itonami-isic-8810` from `:blueprint` to `:implemented` in the
`kotoba-lang/industry` registry, the thirty-sixth vertical built
outside ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-8810`'s blueprint described a real-world care-
   coordination operating model (case intake, care-plan assessment,
   safeguarding, in-home check-in dispatch, case closure) but had no
   governed-actor implementation: no Store, no Governor, no rollout
   phasing, no tests.
2. The blueprint's own Trust Controls and operator-guide walkthrough
   name concrete hold conditions (a suppressed safeguarding signal, an
   overridden consent, an unreviewed eligibility/discharge decision,
   an overloaded caregiver) and two high-stakes acts (dispatching a
   check-in, closing a case) — these needed a concrete, testable HARD-
   check mapping, not just prose.
3. `blueprint.edn` carried a stale pre-rename `:itonami.blueprint/id`
   (`"cloud-itonami-8810"`, missing the `isic-` infix) and was
   entirely missing `:required-technologies`/`:optional-technologies`,
   both needing reconciliation against the `kotoba-lang/industry`
   registry's own stated values before the promotion could be
   considered internally consistent. `docs/business-model.md` also
   carried a stale caveat paragraph claiming these fields were not yet
   populated.
4. This blueprint's own governor is literally named "Safeguarding
   Governor," and a prior sibling (`congregation`, ISIC 9491) already
   established a "safeguarding concern" unconditional-evaluation
   check — this build needed to determine, by reading the sibling's
   actual source rather than assuming from the shared word
   "safeguarding," whether its own check was a genuine reuse or a
   distinct concept.

## Decision

1. **Entity and op shape.** Primary entity `case` (matching the
   blueprint's own operator-guide language, "Case 014... Mr. Sato").
   Five ops: `:case/intake`, `:careplan/verify`, `:safeguarding/
   screen`, `:actuation/dispatch-checkin` (high-stakes), and
   `:actuation/close-case` (high-stakes) — a dual-actuation-on-one-
   entity shape grounded directly in the operator-guide's own
   "Execute"/"closing a case" walkthrough.
2. **`caregiver-workload-exceeds-maximum?` — 5th MAXIMUM-ceiling
   check.** Following `facility`, `school`, `card` and `recovery`,
   this applies the same ceiling-only comparison to a case's assigned
   caregiver's own recorded current caseload against their own
   recorded maximum caseload, gating only `:actuation/dispatch-
   checkin`.
3. **`safeguarding-signal-unresolved-violations` — 34th unconditional-
   evaluation screening grounding, and the 2nd "safeguarding"-shaped
   one, correctly distinguished from `congregation`'s.**
   `congregation.governor`'s source was read directly (not just
   grepped) to confirm its `safeguarding-concern-unresolved-
   violations` verifies a MATTER-level allegation against a person.
   This actor's check verifies a signal surfaced by the check-in visit
   itself (e.g. a missed-medication report escalating to a neglect
   concern) — a related but distinct shape, named
   `safeguarding-signal-unresolved` (not `-concern-unresolved`) to
   keep the two textually distinguishable. Gates both `:safeguarding/
   screen` and `:actuation/close-case`.
4. **Dedicated double-actuation-guard booleans.**
   `:checkin-dispatched?`/`:case-closed?` on the `case` record, never a
   single `:status` value.
5. **A naming lesson: `case-of`, not `case`.** The Store protocol's
   per-entity accessor could not be named `case` because `case` is a
   Clojure special form used by every backend's `commit-record!` for
   effect dispatch — caught and renamed before any test was written.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/care/store_contract_test.clj`.
7. **Phase 0→3 rollout** — Phase 3 `:auto` set is `{:case/intake}`
   only; both actuations are permanently excluded from every phase's
   `:auto` set.
8. **No bespoke domain capability lib** — runs on the generic
   robotics/identity/forms/dmn/bpmn/audit-ledger/optimization stack.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **`blueprint.edn` field-sync fixes** — corrected the stale
    `:itonami.blueprint/id` and added the missing `:required-
    technologies`/`:optional-technologies` fields; removed a stale
    caveat paragraph in `docs/business-model.md`. A concurrent
    session's unrelated `:itonami.blueprint/game` addition
    (network-isekai playable-prototype reference) was merged in
    cleanly via a trivial manual conflict resolution.

## Consequences

- Fiftieth actor in this fleet (49 implemented before this build).
- Confirms the MAXIMUM-ceiling check family generalizes to a fifth,
  genuinely distinct domain (caregiver-workload safety).
- Establishes the second grounding of the "safeguarding" unconditional-
  evaluation shape, correctly distinguished from `congregation`'s via
  direct source reading, not just a name grep.
- `MemStore` ‖ `DatomicStore` parity proven by contract test.
- Two pre-existing `blueprint.edn` inconsistencies and one stale doc
  caveat fixed as in-scope minor consistency work.
- Fleet maturity: `:implemented` 49 → 50, `:blueprint` 35 → 34,
  `:spec` 546 unchanged, total 643.
- Test status: 36 tests / 173 assertions, lint clean, demo verified
  end-to-end.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Reuse `congregation.governor/safeguarding-concern-unresolved-violations`'s exact name | The two concepts are related but not identical (matter-level allegation vs. check-in-surfaced signal); reusing the identical name would misrepresent a distinct grounding as copy-paste reuse |
| A single "case-safety" check merging caregiver-workload and safeguarding-signal concerns | Caregiver workload is a ground-truth numeric recompute; safeguarding-signal status is an unconditionally-evaluated flag needing the screening op to self-hold — merging loses that property |
| Name the protocol accessor `case` to match the entity name exactly | Collides with Clojure's `case` special form, used by every backend's `commit-record!` for effect dispatch — renamed to `case-of` before any dependent code was written |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8810/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8810/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8810/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"8810"`)
