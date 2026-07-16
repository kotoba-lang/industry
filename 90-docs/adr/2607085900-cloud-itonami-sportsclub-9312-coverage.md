# ADR-2607085900: `cloud-itonami-isic-9312` (activities of sports clubs) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607080100 (`cloud-itonami-isic-9412`, association -- `complaint-unresolved-violations` origin)
- ADR-2607083600 (`cloud-itonami-isic-9609`, personalservice -- `cooling-off-period-not-elapsed?` origin)
- ADR-2607085800 (`cloud-itonami-isic-9329`, other amusement/recreation)
- `cloud-itonami-isic-9312/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-9312` publishes an OSS business blueprint for
activities of sports clubs: membership-based athletic and sporting
clubs. Like every prior vertical in this fleet, the blueprint text
alone is not an implementation -- this ADR records the governed-actor
build that promotes `cloud-itonami-isic-9312` from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry, the fifty-
ninth vertical built outside ADR-2607032000's original insurance/
real-estate batch.

## Problem

1. `cloud-itonami-isic-9312`'s blueprint described a real-world
   sports-club operating model (member enrollment intake, membership-
   governance verification, membership-suspension/expulsion
   finalization) but had no governed-actor implementation: no Store,
   no Governor, no rollout phasing, no tests.
2. The blueprint's own text names only ONE real-world act
   ("finalizing a membership suspension or expulsion") -- this build
   needed the single-actuation shape.
3. Before selecting this vertical, a survey of all 12 remaining
   `:blueprint`-tier candidates found that MOST were structurally
   blocked: 9 of the other 11 declare a `:itonami.blueprint/governor`
   keyword that is an EXACT collision with an already-implemented
   sibling's own governor name (confirmed via direct `blueprint.edn`
   comparison, not just a "similar domain" judgment). This needed to
   be verified rigorously and documented, since it materially narrows
   the fleet's remaining genuinely-buildable candidate pool.
4. Given the check-family space for a membership-suspension/expulsion
   actuation shape is already well covered by `association`/9412's
   and `personalservice`/9609's own established concepts, this
   build's own checks needed to be honestly characterized as reuses
   rather than straining for an unearned novelty claim.

## Decision

1. **Single-actuation shape.** The blueprint's own README/business-
   model.md/operator-guide.md text consistently names only ONE
   real-world act, with "suspension" and "expulsion" treated as ONE
   conceptual act. Matching every prior single-actuation sibling's
   shape, `high-stakes` is the one-member set `#{:actuation/finalize-
   membership-action}` -- a POSITIVE actuation, matching this fleet's
   majority shape (`3600`/`6190` are the two NEGATIVE-actuation
   exceptions).
2. **Entity and op shape.** Primary entity `member`. Four ops:
   `:member/intake`, `:eligibility/verify`, `:conduct/screen`,
   `:actuation/finalize-membership-action` (high-stakes).
3. **`disciplinary-complaint-unresolved-violations` -- an honest
   reuse of `association`/9412's own concept, not claimed as new.**
   The 58th distinct application of the unconditional-evaluation
   discipline overall (`casualty.governor/sanctions-violations`'s
   original fix; most recently `recreation.governor/emergency-
   egress-obstructed-violations` at 57th). Gates `:conduct/screen` and
   the actuation.
4. **`appeal-window-still-open?` -- an honest reuse of
   `personalservice`/9609's MINIMUM-threshold sufficiency SHAPE, for
   a genuinely different real-world concept.** The tenth instance of
   that family overall, comparing a member's own `:days-since-
   suspension-notice` against its own `:minimum-appeal-window-days`
   -- grounded in nonprofit membership-organization due-process law
   (Germany's BGB §35, California Corporations Code §7341, Japan's
   一般社団法人及び一般財団法人に関する法律 第25条), not a consumer
   cooling-off right. Gates only the actuation.
5. **Dedicated double-actuation-guard boolean.** `:membership-action-
   finalized?` on the `member` record, never a single `:status`
   value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/sportsclub/store_contract_test.clj`. The per-entity accessor
   is safely named `member` directly.
7. **Phase 0→3 rollout** -- Phase 3's `:auto` set is
   `{:member/intake}` only; the actuation is permanently excluded from
   every phase's `:auto` set.
8. **No bespoke domain capability lib** -- this blueprint's own
   `:itonami.blueprint/required-technologies` names no domain-
   specific capability beyond the generic stack.
9. **Mock + LLM advisor pair** -- `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed** -- the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Seventy-fourth implemented actor in this fleet's registry (73
  implemented immediately before this build).
- Documents an honest reuse of `association`/9412's own concept
  (58th unconditional-eval grounding), not claimed as new.
- Documents an honest reuse of `personalservice`/9609's MINIMUM-
  threshold sufficiency SHAPE (10th instance), for a genuinely
  different domain concept, not claimed as a new shape.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/sportsclub/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 73 → 74, `:blueprint` 12 → 11,
  `:spec` 545 unchanged (sum 630; see Scope note below on the
  `:total` figure).
- Test status: 30 tests / 132 assertions, lint clean, demo verified
  end-to-end (one clean single-actuation lifecycle plus four
  HARD-hold cases).
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing -- 7 tests / 121 assertions,
  all green.
- **Fleet-wide finding**: of the 11 other `:blueprint`-tier candidates
  remaining at build time, 9 (`7220`, `8522`, `8549`, `9411`, `9512`,
  `9522`, `9523`, `9524`, `9529`) are structurally blocked by an
  exact governor-name collision with an already-implemented sibling
  -- confirmed via direct `blueprint.edn` comparison, not inference.
  This narrows the pool of genuinely distinct next candidates
  considerably and should be re-checked (not assumed stale) at the
  next vertical-selection point, since the registry is edited
  concurrently by other sessions.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Scope note

This ADR's `:fleet-maturity-before`/`:fleet-maturity-after` `:total`
field (630) reflects the actual observed sum of `:implemented` +
`:blueprint` + `:spec` in `registry.edn` at build time, rather than
`docs/cloud-itonami.md`'s "Total entries: 643" figure -- the same
clarification ADR-2607085700's and ADR-2607085800's own scope notes
already recorded: that 643 figure tracks a separate, pre-existing,
wider ISIC-class/group count, not per-vertical maturity accounting.
Not introduced or worsened by this build; not in scope to reconcile
here.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Building `cloud-itonami-isic-9411` (business/employers membership organizations) | Its own `:association-governance-governor` is an EXACT collision with `association`/9412's already-implemented governor name; also a live test fixture requiring a repoint if selected |
| Building one of the repair-category candidates (9512/9522/9523/9524/9529) | All five declare the IDENTICAL `:repair-shop-governor` keyword already used by `repairshop`/9521 |
| Forcing a "genuinely new" check to avoid an all-reuse build | The check-family space for this actuation shape is already well covered by `association`/9412's and `personalservice`/9609's own concepts; honest reuse characterization is more defensible than an unearned novelty claim |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-9312/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9312/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9312/docs/business-model.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9412/src/association/governor.cljc` (`complaint-unresolved-violations` origin)
- `orgs/cloud-itonami/cloud-itonami-isic-9609/src/personalservice/registry.cljc` (`cooling-off-period-not-elapsed?` origin)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"9312"`)
