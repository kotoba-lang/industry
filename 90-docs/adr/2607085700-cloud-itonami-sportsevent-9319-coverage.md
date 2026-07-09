# ADR-2607085700: `cloud-itonami-isic-9319` (other sports activities) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607084600 (`cloud-itonami-isic-6411`, central banking)
- ADR-2607084700 (`cloud-itonami-isic-6110`, network operator — promoted concurrently by another session, different ADR track)
- ADR-2607085600 (`cloud-itonami-isic-7490`, other professional services)
- `cloud-itonami-isic-9319/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-9319` publishes an OSS business blueprint for
other sports activities not elsewhere classified: independent race
organizing, sports officiating and event-timing services. Like every
prior vertical in this fleet, the blueprint text alone is not an
implementation — this ADR records the governed-actor build that
promotes `cloud-itonami-isic-9319` from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry, the fifty-
seventh vertical built outside ADR-2607032000's original insurance/
real-estate batch.

## Problem

1. `cloud-itonami-isic-9319`'s blueprint described a real-world
   sports-event operating model (participant/event intake, ruling
   verification, official-ruling/result issuance) but had no
   governed-actor implementation: no Store, no Governor, no rollout
   phasing, no tests.
2. The blueprint's own text names only ONE real-world act
   ("finalizing an official ruling or result") — this build needed
   the single-actuation shape.
3. Anti-doping-control resolution (a real, load-bearing concern named
   by this blueprint's own facts/citations — WADA Code Article 5/7)
   had no dedicated check anywhere in this fleet — a genuinely new
   unconditional-evaluation concept was needed, verified absent via
   grep before being claimed as new.
4. This fleet already has an eleven-instance-deep MAXIMUM-ceiling
   check family (including `navigator`/8691's specific elapsed-time-
   exceeds-validity-window sub-pattern) — this build's own timing-
   calibration check needed to honestly characterize its own reuse
   rather than overclaim novelty.

## Decision

1. **Single-actuation shape.** The blueprint's own README/business-
   model.md/operator-guide.md text consistently names only ONE
   real-world act, so `sports`/8541's either/or-naming precedent
   was not even needed here. Matching `leasing`/`underwriting`/
   `testlab`/`clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`/`advertising`/`polling`/
   `research`/`design`/`sports`/`alliedhealth`/`photo`/
   `personalservice`/`edsupport`/`cultural`/`proserv`'s single-
   actuation shape, `high-stakes` is the one-member set
   `#{:actuation/finalize-ruling}`.
2. **Entity and op shape.** Primary entity `participant`. Four ops:
   `:participant/intake`, `:ruling/verify`, `:antidoping/screen`,
   `:actuation/finalize-ruling` (high-stakes).
3. **`anti-doping-control-unresolved-violations` — the 56th
   unconditional-evaluation grounding, a genuinely new concept.**
   Grep-verified absent (zero hits for "doping"/"anti-doping" across
   every prior sibling). Grounded in WADA Code Article 5/7 (testing
   and results management) and World Athletics Technical Rules Rule
   17. Gates `:antidoping/screen` and the actuation.
4. **`timing-calibration-overdue-violations` — an honest twelfth
   MAXIMUM-ceiling instance, not claimed as new.** Specifically the
   SECOND instance of `navigator.registry/eligibility-window-elapsed-
   exceeds-validity?`'s own elapsed-time-exceeds-validity-window
   sub-pattern. Grounded in World Athletics Technical Rules Rule 17
   photo-finish/timing-system calibration requirements. Gates only
   the actuation (a pure ground-truth recompute).
5. **Dedicated double-actuation-guard boolean.** `:ruling-finalized?`
   on the `participant` record, never a single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/sportsevent/store_contract_test.clj`. The per-entity
   accessor is safely named `participant` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is
   `{:participant/intake}` only; the actuation is permanently
   excluded from every phase's `:auto` set.
8. **No bespoke domain capability lib** — this blueprint's own
   `:itonami.blueprint/required-technologies` names no domain-
   specific capability beyond the generic stack.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed** — the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Seventy-second implemented actor in this fleet's registry (71
  implemented immediately before this build, reflecting `cloud-
  itonami-isic-6110`'s concurrent promotion by another session in
  addition to the seventy actors this fleet's own "coverage" ADR
  track had built through `proserv`/7490).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (anti-doping-control-unresolved), grep-verified absent from
  every prior sibling before the claim was finalized.
- Documents an honest twelfth MAXIMUM-ceiling instance (and second
  instance of `navigator`/8691's own sub-pattern), not claimed as new.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/sportsevent/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 71 → 72, `:blueprint` 14 → 13,
  `:spec` 545 unchanged (sum 630; see Scope note below on the
  `:total` figure).
- Test status: 30 tests / 132 assertions, lint clean, demo verified
  end-to-end (one clean single-actuation lifecycle plus four
  HARD-hold cases).
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 119 assertions,
  all green.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Scope note

This ADR's `:fleet-maturity-before`/`:fleet-maturity-after` `:total`
field (630) reflects the actual observed sum of `:implemented` +
`:blueprint` + `:spec` in `registry.edn` at build time, rather than
`docs/cloud-itonami.md`'s "Total entries: 643" figure — that 643
figure was confirmed (by direct count) to already diverge from the
tier sum before this build touched anything, tracking a separate,
pre-existing, wider ISIC-class/group count. Not introduced or
worsened by this build; not in scope to reconcile here.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape (splitting "ruling" and "result" into two acts) | The blueprint's own text consistently names only ONE real-world act; inventing a second would not be grounded in the blueprint's own text |
| Reusing `sports`/8541's check-family design | `sports`/8541 covers instructional certification (attendance hours, coaching-staff background checks); `sportsevent`/9319 covers event officiating/timing integrity — genuinely distinct real-world concerns |
| Framing `timing-calibration-overdue?` as a wholly new concept | Structurally identical to `navigator`/8691's elapsed-time-exceeds-validity-window shape; honest reuse characterization matches this fleet's precedent-verification discipline |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-9319/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9319/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9319/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"9319"`)
