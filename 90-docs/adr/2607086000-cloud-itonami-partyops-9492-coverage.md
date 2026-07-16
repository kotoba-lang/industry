# ADR-2607086000: `cloud-itonami-isic-9492` (activities of political organizations) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607081100 (`cloud-itonami-isic-9420`, union -- `strike-vote-share-insufficient?` origin)
- ADR-2607085900 (`cloud-itonami-isic-9312`, sports clubs -- governor-name-collision survey origin)
- `cloud-itonami-isic-9492/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-9492` publishes an OSS business blueprint for
activities of political organizations: political party and political-
advocacy-group administration. Like every prior vertical in this
fleet, the blueprint text alone is not an implementation -- this ADR
records the governed-actor build that promotes `cloud-itonami-isic-
9492` from `:blueprint` to `:implemented` in the `kotoba-lang/
industry` registry, the sixtieth vertical built outside
ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-9492`'s blueprint described a real-world
   political-organization operating model (member/supporter intake,
   campaign-finance-disclosure verification, position/endorsement
   publication) but had no governed-actor implementation: no Store,
   no Governor, no rollout phasing, no tests.
2. The blueprint's own text names only ONE real-world act
   ("publishing a public political position or endorsement") -- this
   build needed the single-actuation shape.
3. `sportsclub`/9312's own ADR recorded that 9 of the 11 remaining
   `:blueprint`-tier candidates at that time were blocked by exact
   governor-name collisions with already-implemented siblings. This
   build needed to re-confirm that survey was still accurate (the
   registry is edited concurrently by other sessions) before choosing
   between the two remaining clean candidates (9492, 9499).
4. Campaign-finance-disclosure/disclaimer requirements are a real,
   well-documented, universal regulatory concern for political
   communications, but had no dedicated check anywhere in this
   fleet -- this needed to be verified absent via grep before being
   claimed as new.

## Decision

1. **Single-actuation shape.** The blueprint's own README/business-
   model.md/operator-guide.md text consistently names only ONE
   real-world act, with "position" and "endorsement" treated as ONE
   conceptual act. Matching every prior single-actuation sibling's
   shape, `high-stakes` is the one-member set `#{:actuation/publish-
   position}` -- a POSITIVE actuation, matching this fleet's majority
   shape (`3600`/`6190` are the two NEGATIVE-actuation exceptions).
2. **Candidate re-selection.** Re-confirmed `sportsclub`/9312's
   survey: 9 of 11 remaining candidates still blocked by exact
   governor-name collisions. Between the two clean candidates (9492,
   9499), 9492 was chosen for its richer, more specific regulatory
   grounding (campaign-finance-disclosure law) over 9499's generic
   "publishing a public position" text.
3. **Entity and op shape.** Primary entity `position` (covering both
   policy-position statements and endorsements). Four ops:
   `:member/intake`, `:position/verify`, `:disclaimer/screen`,
   `:actuation/publish-position` (high-stakes).
4. **`campaign-finance-disclaimer-missing-violations` -- the 59th
   unconditional-evaluation grounding, a genuinely new concept.**
   Grep-verified absent (zero hits for "disclaimer"/"imprint"/
   "campaign-finance" across every prior sibling). Grounded in US
   FECA 52 U.S.C. §30120, UK PPERA imprint rules, Germany's
   Impressumspflicht (MStV §18). Gates `:disclaimer/screen` and the
   actuation.
5. **`member-consensus-share-insufficient?` -- an honest reuse of
   this fleet's ratio-based check family, not claimed as new.** The
   fourth instance overall (`leasing`/9420... 1st, `behavioral` 2nd,
   `union` 3rd), reusing `union`/9420's exact quotient-comparison
   shape (votes-in-favor/votes-cast vs. required threshold), MINIMUM-
   floor direction. Gates only the actuation.
6. **Dedicated double-actuation-guard boolean.** `:published?` on the
   `position` record, never a single `:status` value.
7. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/partyops/store_contract_test.clj`. The per-entity accessor
   is safely named `position` directly.
8. **Phase 0→3 rollout** -- Phase 3's `:auto` set is
   `{:member/intake}` only; the actuation is permanently excluded
   from every phase's `:auto` set.
9. **No bespoke domain capability lib** -- this blueprint's own
   `:itonami.blueprint/required-technologies` names no domain-
   specific capability beyond the generic stack.
10. **No `blueprint.edn` field-sync fixes needed** -- the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Seventy-fifth implemented actor in this fleet's registry (74
  implemented immediately before this build).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (campaign-finance-disclaimer-missing), grep-verified absent
  from every prior sibling before the claim was finalized.
- Documents an honest reuse of this fleet's ratio-based check family
  (member-consensus-share-insufficient, the 4th instance), not
  claimed as new.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/partyops/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 74 → 75, `:blueprint` 11 → 10,
  `:spec` 545 unchanged (sum 630; see Scope note below on the
  `:total` figure).
- Test status: 30 tests / 131 assertions, lint clean, demo verified
  end-to-end (one clean single-actuation lifecycle plus four
  HARD-hold cases).
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing -- 7 tests / 122 assertions,
  all green.
- **Fleet-wide finding, narrowed further**: only `9499` (other
  membership organizations n.e.c.) now remains as a genuinely clean
  `:blueprint`-tier candidate not blocked by an exact governor-name
  collision with an already-implemented sibling. This should be
  re-verified (not assumed stale) at the next vertical-selection
  point.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Scope note

This ADR's `:fleet-maturity-before`/`:fleet-maturity-after` `:total`
field (630) reflects the actual observed sum of `:implemented` +
`:blueprint` + `:spec` in `registry.edn` at build time, rather than
`docs/cloud-itonami.md`'s "Total entries: 643" figure -- the same
clarification ADR-2607085700's, ADR-2607085800's and ADR-2607085900's
own scope notes already recorded: that 643 figure tracks a separate,
pre-existing, wider ISIC-class/group count, not per-vertical maturity
accounting. Not introduced or worsened by this build; not in scope to
reconcile here.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Building `cloud-itonami-isic-9499` (other membership organizations n.e.c.) | Also genuinely clean (no collision), but its own generic "publishing a public position" text offered no comparably specific regulatory hook to design a new check against |
| Framing `member-consensus-share-insufficient?` as a new concept | Structurally identical to `union.registry/strike-vote-share-insufficient?`'s own ratio-comparison shape; honest reuse characterization matches this fleet's precedent-verification discipline |
| A dual-actuation shape (splitting "position" and "endorsement" into two acts) | The blueprint's own text consistently names only ONE real-world act |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-9492/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9492/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9492/docs/business-model.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9420/src/union/registry.cljc` (`strike-vote-share-insufficient?` origin)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"9492"`)
