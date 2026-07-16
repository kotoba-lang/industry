# ADR-2607082800: `cloud-itonami-isic-8541` (sports and recreation education) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607082300 (`cloud-itonami-isic-7410`, specialized design activities)
- ADR-2607082600 (`cloud-itonami-isic-8710`, residential nursing care facilities)
- `cloud-itonami-isic-8541/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-8541` publishes an OSS business blueprint for
sports and recreation education: instruction in athletics, fitness and
recreational skills. Like every prior vertical in this fleet, the
blueprint text alone is not an implementation — this ADR records the
governed-actor build that promotes `cloud-itonami-isic-8541` from
`:blueprint` to `:implemented` in the `kotoba-lang/industry` registry,
the forty-fifth vertical built outside ADR-2607032000's original
insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-8541`'s blueprint described a real-world
   sports-academy operating model (participant intake, program
   verification, background-check screening, certification
   finalization) but had no governed-actor implementation: no Store,
   no Governor, no rollout phasing, no tests.
2. The blueprint's own text names only ONE real-world act ("finalizing
   a certification or safety-relevant progress record") — this build
   needed the single-actuation shape.
3. ISIC `8541` is itself an EDUCATION class, exactly like `secondary`/
   8521 — a decision was needed on whether to honestly reuse
   `secondary`'s existing `attendance-hours-insufficient?` concept or
   invent a new name for the same real-world requirement.
4. The blueprint's operator-guide implies youth-facing instruction
   (participants enrolled in athletics/fitness programs) — a decision
   was needed on which existing background/credential-screening
   concept in this fleet most precisely matches that context.

## Decision

1. **Single-actuation shape.** Matching `leasing`/`underwriting`/
   `testlab`/`clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`/`advertising`/`polling`/
   `research`/`design`'s single-actuation shape, `high-stakes` is the
   one-member set `#{:actuation/finalize-certification}`.
2. **Entity and op shape.** Primary entity `participant`. Four ops:
   `:participant/intake`, `:program/verify`, `:background-check/
   screen`, `:actuation/finalize-certification` (high-stakes).
3. **`attendance-hours-insufficient?` — literal reuse for the 8th
   MINIMUM-threshold instance.** LITERALLY reuses `secondary.registry/
   attendance-hours-insufficient?`'s exact concept and field names
   (`:attendance-hours-completed`/`:attendance-hours-required`) — the
   SAME real-world requirement genuinely recurs in this education
   vertical. Gates only `:actuation/finalize-certification`.
4. **`background-check-not-cleared` — honestly reused (NOT new) as
   the 43rd unconditional-evaluation grounding.** A LITERAL reuse of
   `school.governor/background-check-not-cleared-violations`'s own
   concept (the SECOND instance), distinct from the more widely-reused
   `credential-not-current` concept (professional license currency) —
   background-check clearance concerns youth-safety/criminal-history
   screening, the more precise match for youth-facing coaching staff.
   Gates `:background-check/screen` and the actuation.
5. **Dedicated double-actuation-guard boolean.** `:certified?` on the
   `participant` record, never a single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/sports/store_contract_test.clj`. The per-entity accessor is
   safely named `participant` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:participant/
   intake}` only; the actuation is permanently excluded from every
   phase's `:auto` set.
8. **No bespoke domain capability lib** — this blueprint's own
   `:itonami.blueprint/required-technologies` names no domain-specific
   capability beyond the generic stack.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed** — the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Fifty-ninth actor in this fleet (58 implemented before this build).
- Confirms the MINIMUM-threshold sufficiency check family generalizes
  to an 8th instance via honest literal reuse (not a novel concept).
- Confirms `background-check-not-cleared` generalizes to a 2nd literal
  instance, continuing the unconditional-evaluation discipline's count
  to 43.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/sports/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 58 → 59, `:blueprint` 26 → 25,
  `:spec` 546 unchanged, total 643.
- Test status: 30 tests / 135 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 107 assertions,
  all green, no pre-existing fixture referenced ISIC "8541".
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape (splitting "certification" and "safety-relevant progress record" into two acts) | The blueprint's own text treats these as an either/or phrasing of ONE kind of act, not two distinct real-world acts |
| Inventing a new name for the attendance-hours concept | Genuinely the SAME requirement as `secondary`/8521's own concept for the same education-class family — literal reuse is more honest |
| Reusing `credential-not-current` for the coaching-staff screening concept | Genuinely a different concern (professional license currency vs. youth-safety background/criminal-history clearance) — `school.governor`'s `background-check-not-cleared` is the more precise match |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8541/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8541/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8541/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"8541"`)
