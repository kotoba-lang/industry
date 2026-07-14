# ADR-2607141800: `cloud-itonami-isic-6130` (Satellite telecommunications activities) published fresh and deepened to `:implemented` -- last ISIC Wave 0 class-level gap alongside `6391`

- Status: Accepted (2026-07-14)
- Related: `cloud-itonami-isic-6190` (Community Telecommunications Access,
  wired/VoIP-reseller telecom, module-shape template), `cloud-itonami-
  isic-6120` (Community Mobile Network Infrastructure Operations,
  terrestrial mobile-network-operator telecom, closest sibling and
  second-application precedent for the format-check family),
  ADR-2607121000 (ISIC Wave 0 reverse-toposort plan), ADR-2607102000
  (`6120`'s own fresh-blueprint build), ADR-2607080700 (`6190`'s own
  `:blueprint` -> `:implemented` deepening), ADR-2607131000 (direct
  spec->implemented promotion precedent, e.g. 4620/2910)

## Context

ISIC Wave 0 (the highest-priority "money/info root" wave: banking,
insurance, telecom, data) was down to its last two class-level gaps:
`"6130"` (Satellite telecommunications activities) and `"6391"` (News
agency activities, left untouched here -- a possible concurrent
sibling build). Unlike the last several promotions in this fleet,
`"6130"` had NO existing blueprint repo to build on: the only prior
reference (`gftdcojp/cloud-itonami-J6130`, the legacy pre-rename
naming convention) was confirmed a dead link (404) before starting.
This is a fresh scaffold from zero, not a `:blueprint` -> `:implemented`
promotion.

## Decision

1. `cloud-itonami/cloud-itonami-isic-6130` (public, AGPL-3.0-or-later)
   published as a full governed-actor repo in one build -- README,
   `blueprint.edn`, `docs/business-model.md`, `docs/operator-guide.md`,
   community files (GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY),
   `src`/`test`/`deps.edn`/`docs/adr/0001-architecture.md` -- rather
   than a `:blueprint`-only stage followed by a later implementation
   pass, matching this fleet's existing direct `:spec` ->
   `:implemented` promotion precedent (e.g. `4620`, `2910`;
   ADR-2607131000).
2. **Satellite Operations Advisor ⊣ Satellite Network Governor**
   (`:satellite-network-governor`, grep-verified unique fleet-wide) --
   `satcom.*` namespaces, module shape mirrors `cloud-itonami-isic-
   6190`'s `telecom.*` and `cloud-itonami-isic-6120`'s
   `wirelesstelecom.*` exactly (`facts`/`governor`/`operation`/
   `phase`/`registry`/`sim`/`store`/`satcomadvisor`), the same
   langgraph-clj StateGraph skeleton every actor in this fleet uses.
   Scope: a licensed SATELLITE-NETWORK operator (VSAT/broadband-via-
   satellite and satellite-phone terminals, ITU frequency/orbital-slot
   coordination) -- distinct from `6190`'s VoIP/reseller scope and
   `6120`'s TERRESTRIAL mobile-spectrum scope.
3. `satcom.facts` cites each jurisdiction's satellite/space-services
   regulator AND its role as the ITU Notifying Administration (Radio
   Regulations Article 9/11 coordination filings) -- a genuinely
   distinct, internationally-coordinated regulatory concern from a
   terrestrial numbering plan (`6190`) or a terrestrial mobile-
   spectrum license (`6120`), seeded for JPN/USA/GBR/DEU.
4. `satellite-number-invalid-format?` is the THIRD application of this
   fleet's format/syntactic-validity check family, first established
   by `telecom.registry/e164-invalid-format?` (`6190`) and reused a
   second time by `wirelesstelecom.registry/msisdn-invalid-format?`
   (`6120`). A genuine third application, not a new family: real
   Global Mobile Satellite System satellite-phone numbers (Inmarsat/
   Iridium-class, ITU country codes +870/+881 per ITU-T E.164/E.168)
   ARE themselves E.164-formatted, so the SAME structural check shape
   is reused here for a genuinely different real-world identifier.
5. `coordination-dispute-unresolved-violations` is the satellite
   analog of `6190`'s `billing-dispute-unresolved-violations` and
   `6120`'s `license-dispute-unresolved-violations` -- evaluated
   UNCONDITIONALLY so the screening op (`:coordination/screen`) itself
   can HARD-hold on its own finding, exercised in tests/demo DIRECTLY
   against an already-flagged terminal (never via an actuation op
   against an unscreened one), the same discipline this fleet's ADRs
   have reaffirmed sibling-to-sibling.
6. Dual actuation (`:actuation/provision-capacity`, `:actuation/
   suspend-service`), each with its own history collection, sequence
   counter and dedicated double-actuation-guard boolean
   (`:capacity-provisioned?`/`:service-suspended?`, never a `:status`
   value). `:actuation/suspend-service` is this fleet's FOURTH negative
   actuation (after `3600`'s alert suppression, `6190`'s billing-record
   suppression, `6120`'s service suspension) -- withholding ongoing
   connectivity, not issuing a new record.
7. Real transponder/payload telemetry, ground-station command-and-
   control, lawful-intercept and law-enforcement-ordered suspension
   are OUT OF SCOPE by construction -- no op, HARD check or advisor
   dispatch branch exists for any of them, mirroring `6120`'s own
   explicit "lawful-intercept and emergency paths remain outside LLM
   control" posture.
8. `kotoba-lang/industry`'s registry: `"6130"` `:maturity :spec` ->
   `:implemented` DIRECTLY (no intermediate `:blueprint`), `:repo`/
   `:business-id` updated from the dead `gftdcojp/cloud-itonami-J6130`
   placeholder to the real `cloud-itonami/cloud-itonami-isic-6130`.
   Fleet-wide maturity moves 148 -> 149 implemented (`:blueprint`
   unchanged at 41). `test/kotoba/industry_test.clj`'s live-state
   corroboration and `maturity-summary` assertions updated to match.
   Landed via a 2-parent GitHub API server-side merge (no local
   rebase/force-push), `35ea8c516d84e467eebc9e9b750436e0e38a6f46`.
9. This closes the LAST ISIC Wave 0 class-level gap alongside `"6391"`
   (deliberately left untouched here -- a possible concurrent sibling
   build; per this task's scope boundary, no other ISIC/ISCO entry was
   touched).

## Consequences

- (+) ISIC Wave 0 (the highest-priority "money/info root" wave) is now
  at, or one entry away from, full class-level `:implemented` coverage
  depending on `"6391"`'s independent status.
- (+) Confirms the negative-actuation pattern generalizes a FOURTH
  time across unrelated domains (water-safety alerting, wired-telecom
  billing, terrestrial-mobile service continuity, satellite service
  continuity) -- not a one-off quirk of any single domain.
- (+) Confirms the format/syntactic-validity check family generalizes
  to a THIRD real-world identifier (a satellite GMSS terminal number)
  without modification to its check shape.
- (+) `MemStore` ‖ `DatomicStore` parity proven by
  `test/satcom/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern every sibling actor uses.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `satcom.facts/coverage`
  reports this honestly.
- (-) No real transponder/payload telemetry, ground-station command-
  and-control, or lawful-intercept infrastructure is modeled -- see
  `cloud-itonami-isic-6130`'s own ADR-0001 and README coverage table
  for the full honest-scope accounting.
- Fleet-wide: 149 actors now `:implemented` out of the registry's full
  entry count; the next "pick a new ISIC/ISCO vertical" build remains
  free to select from any remaining `:spec`-tier entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Publish `:blueprint`-only first, defer the actor implementation to a later pass | ❌ | No independent blueprint-stage work was already in flight for `"6130"` (unlike `6120`'s own history); this fleet's direct spec->implemented precedent (ADR-2607131000) applies cleanly and avoids a registry-churn-only intermediate commit |
| Model orbital-slot longitude as the format-check ground truth instead of a GMSS satellite-phone number | ❌ | ITU-assigned GEO orbital slots are a discrete registry entry (ITU Master International Frequency Register), not a simple numeric range -- less honest than reusing the E.164-format check, which is genuinely accurate to real GMSS numbering |
| Model ground-station command-and-control or law-enforcement-ordered suspension as governed actuations | ❌ | Mirrors `6120`'s own rejection of the analogous choice: these classes of act are outside the actor entirely, per this repo's own published Trust Controls |
| See `cloud-itonami-isic-6130`'s own ADR-0001 Alternatives table for further build-level decisions | -- | (format-check family choice, negative-actuation framing, direct-promotion rationale, etc.) |

## References

- `cloud-itonami-isic-6130/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `cloud-itonami-isic-6190/docs/adr/0001-architecture.md` /
  `cloud-itonami-isic-6120/docs/adr/0001-architecture.md` (closest
  siblings, module-shape templates)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
- ADR-2607121000 (ISIC Wave 0 reverse-toposort plan)
- ADR-2607131000 (direct spec->implemented promotion precedent)

## Verification Notes

- `cloud-itonami-isic-6130`: `clojure -M:dev:test` 36 tests / 173
  assertions, 0 failures. `clojure -M:lint` 0 errors / 0 warnings.
  `clojure -M:dev:run` demo verified end-to-end (one clean dual-
  actuation lifecycle plus five HARD-hold cases: no spec-basis,
  malformed satellite number, unresolved ITU-coordination dispute
  screened directly, double provisioning, double suspension -- none
  reach a human). Initial commit `812b15aa3916abee2fd14771e41b3ba329886cfc`,
  pushed to `github.com/cloud-itonami/cloud-itonami-isic-6130`
  (public, confirmed via `private: false`).
- `kotoba-lang/industry`: registry + test updated, `clojure -M:test`
  15 tests / 940 assertions, 0 failures; `clojure -M:lint` 0 errors /
  0 warnings. Landed via 2-parent GitHub API server-side merge
  `35ea8c516d84e467eebc9e9b750436e0e38a6f46` (parents
  `50dfceca504bfb4478b7d79b497dba21f2eb6747` and
  `d793f26ee3499544b2765f0a88ce408bf29acce7`), branch deleted after
  merge.

## Addendum 1 (2026-07-14): timestamp renumbered 2607141500 -> 2607141800

This ADR was initially drafted at timestamp `2607141500`, matching the
then-latest `90-docs/adr/` entries seen at survey time. Before landing,
a fresh `origin/main` sync revealed a concurrent session had already
claimed `2607141500`
(`etzhayyim-substrate-rollout-live-murakumo-verification`) and several
further entries up to `2607141700` -- so this ADR was renumbered to the
first free slot, `2607141800`. The `kotoba-lang/industry` registry
entry and test-file comments (landed at `35ea8c516d84e467eebc9e9b750436e0e38a6f46`,
before the renumbering was caught) referenced the stale `2607141500`
ID; corrected in a small follow-up commit,
`387b2e84ab2e12c173a3ef70de72e604d35eed7b` (2-parent server-side
merge, comment-only change, no behavior/registry-value change,
re-verified 15 tests / 940 assertions green).
