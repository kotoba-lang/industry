# ADR-2607102500: cloud-itonami-isic-6020 (Community Television Broadcasting Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607102400 (ferryops/5011 -- thirteenth "author new blueprints
  from `:spec`" build, closed out the transport-mode family)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the FOURTEENTH "author a new blueprint from `:spec` tier"
build, and the first requiring a fresh domain scan since the
transport-mode family (air, rail, water) closed out at
`ADR-2607102400`.

### Candidate selection and redundancy screening

`"6020"` (Television programming and broadcasting activities) was
selected: a universally well-known, richly regulated media business
(broadcast licensing law -- Japan's 放送法 with MIC oversight and
政治的公平性/balanced-programming requirements under 放送法4条, the US's
FCC license under 47 U.S.C. §301 et seq. with the Equal Time Rule and
Children's Television Act, the UK's Ofcom license under the
Broadcasting Code's due-impartiality rules, Germany's
Landesmedienanstalten licensing under the Rundfunkstaatsvertrag).

Two potential-overlap candidates were checked directly before
committing: `cloud-itonami-iso3166-jpn-mic` ("Independent MIC Telecom &
Broadcasting Licensing Compliance Service — Japan (MIC)") is a
SEPARATE ISO3166-classified market-entry-compliance-service sub-fleet
member (`:robotics false`), not an ISIC-numbered operator business --
no overlap. `cloud-itonami-isic-9000` ("Creative, arts and
entertainment activities", `:content-and-booking-governor`,
`:implemented`) covers performance/creative booking, not
broadcast-STATION-operator content transmission -- also no overlap.
No redundancy confirmed via grep across every `blueprint.edn` for any
other broadcasting-related governor keyword. The legacy placeholder
`:repo` (`gftdcojp/cloud-itonami-J6020`) was confirmed via a direct
GitHub API 404 check to never have actually existed. No bespoke
capability library exists for broadcasting specifically; `:phone` is
the SAME shared telephony-records capability every telecom-adjacent
vertical already uses (call-in shows, viewer contact lines).

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:broadcast-license-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: reuse the telecom-service shape for a genuinely distinct content-broadcasting business

The "Core Contract" and Trust Controls text follows the SAME
`:intake :provision :route :bill :support :audit` shape already used
by `cloud-itonami-isic-6120`/`6190`, adapted for broadcast-specific
concerns (transmission outside a verified license scope, programming
that would violate a public-interest requirement, rather than
connectivity/billing concerns) -- a legitimate reuse for a business
that is fundamentally different in kind (one-to-many content
transmission vs. bidirectional communication service), matching the
established shape-reuse precedent.

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-6020` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 119 prior/sibling actors, rather than the legacy
`J####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-J6020"` to `"cloud-itonami-6020"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-6020` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"6020"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again

`ADR-2607102400`'s own `(is (= 13 (:blueprint m)))` assertion updated
to `(is (= 14 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-
state corroboration assertion (`(is (= :blueprint (industry/maturity
"6020")))`) alongside the thirteen prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 13 :spec 527 :total 646}`
  → `{:implemented 106 :blueprint 14 :spec 526 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 167
  assertions, all green.
- `cloud-itonami-isic-6020` joins the thirteen prior fresh-blueprint
  picks as a fourteenth candidate available for a future `:blueprint`->
  `:implemented` promotion pass.
- `"6010"` (Radio broadcasting) is a natural, clearly-distinct future
  candidate sharing the same shape and licensing-domain flavor,
  following the SAME low-friction-sibling-pick pattern
  `plumbingops`/4322 established for `electricalops`/4321.

## Alternatives considered

- **`"6010"` (Radio broadcasting)**: a comparably strong candidate; set
  aside in favor of television for this pass (a richer, more
  universally recognized regulatory profile), remains available for a
  future build as a natural sibling.

## References

- `kotoba-lang/industry` registry entry `"6020"`.
- `cloud-itonami/cloud-itonami-isic-6020` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-iso3166-jpn-mic/blueprint.edn` and
  `cloud-itonami-isic-9000/blueprint.edn` (confirmed no overlap with
  either).
