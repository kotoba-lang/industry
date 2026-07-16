# ADR-2607102800: cloud-itonami-isic-4912 (Community Freight Rail Transport) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607102600 (radiobroadcastops/6010 -- fifteenth "author new
  blueprints from `:spec`" build, low-friction sibling of tvbroadcastops/6020)
- ADR-2607102400 (ferryops/5011 -- closed out the passenger air/rail/
  water transport-mode family)
- ADR-2607102200 (railops/4911 -- passenger rail, the sibling this
  build extends into a freight variant)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the SIXTEENTH "author a new blueprint from `:spec` tier"
build. `ADR-2607102400`'s own Consequences section noted the passenger
air/rail/water transport-mode family as complete; this build extends
the transport domain into a freight variant, picking up freight rail
as a natural companion to the now-closed passenger-rail sibling
(`"4911"`).

### Candidate selection

`"4912"` (Freight rail transport) was selected: a richly and
DISTINCTLY regulated activity relative to both of its nearest
neighbors. Verified via direct reads and grep before selection:

- distinct from `cloud-itonami-isic-4911` (passenger rail, ADR-
  2607102200): freight rail is frequently regulated by a SEPARATE
  economic regulator from passenger rail (e.g. the US Surface
  Transportation Board's own freight-specific economic oversight,
  distinct from the FRA's passenger-focused safety rules under 49 CFR
  Parts 200-299; Japan's 鉄道事業法 covers rail broadly but freight
  carries its own 貨物利用運送事業法 licensing regime; Germany
  separates EBA safety oversight from Bundesnetzagentur economic
  regulation of freight track access; the UK's ORR performs economic
  regulation of freight access distinct from passenger franchise
  regulation).
- distinct from `cloud-itonami-isic-4920` (road freight, already
  `:implemented`): a different transport mode entirely.
- freight rail carries its own hazardous-materials-by-rail transport
  regime not applicable to passenger service (US 49 CFR Part 174).
- no redundancy with any existing vertical (`grep -rln "freight.rail\|
  rail.freight"` across every `blueprint.edn` in the fleet returned
  empty).
- governor keyword `:rail-freight-governor` grep-verified UNIQUE
  fleet-wide (distinct from `4911`'s own `:rail-safety-governor`).
- the legacy placeholder `:repo` (`gftdcojp/cloud-itonami-H4912`) was
  confirmed via a direct GitHub API 404 check to never have actually
  existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:rail-freight-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword; distinct from `railops`/4911's own
`:rail-safety-governor`.

### Decision 3: reuse the booking/transit/delivery/reconciliation shape established at ADR-2607102100

The "Core Contract" and Trust Controls text follows the SAME framing
`aviationops`/5110 established and `railops`/4911 and `ferryops`/5011
both reused (`:intake :book :transit :deliver :reconcile :audit`),
adapted for freight-rail-specific safety and hazmat-scope concerns (a
service dispatch outside a verified safety-management-system scope, a
hazmat consignment outside a verified transport scope, a maintenance
release without inspection) -- the same legitimate shape-reuse
precedent established repeatedly this scope.

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-4912` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 121 prior/sibling actors, rather than the legacy
`H####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-H4912"` to `"cloud-itonami-4912"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-4912` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"4912"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching consignment/routing-optimization
needs), and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again

`ADR-2607102600`'s own `(is (= 15 (:blueprint m)))` assertion updated
to `(is (= 16 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"4912")))`) alongside the fifteen prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 15 :spec 525 :total 646}`
  → `{:implemented 106 :blueprint 16 :spec 524 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 169
  assertions, all green.
- `cloud-itonami-isic-4912` joins the fifteen prior fresh-blueprint
  picks as a sixteenth candidate available for a future `:blueprint`->
  `:implemented` promotion pass.
- The passenger transport-mode family (air/rail/water) plus this
  freight-rail extension together demonstrate that reusing an
  established shape across both passenger and freight variants of the
  same physical mode is legitimate shape-reuse, not insufficient
  novelty -- distinguished each time by a genuinely separate
  regulatory/economic-oversight framing, verified by direct source
  reads rather than assumed from naming alone.

## Alternatives considered

- No other candidate was seriously considered for this build; freight
  rail was a direct, low-friction extension flagged implicitly by the
  existing sibling relationship with `railops`/4911, following the
  same "low-friction sibling pick" pattern established at ADR-2607101600
  (electrical/plumbing trades) and ADR-2607102600 (TV/radio
  broadcasting).

## References

- `kotoba-lang/industry` registry entry `"4912"`.
- `cloud-itonami/cloud-itonami-isic-4912` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-4911/blueprint.edn` (the sibling shape this
  build reuses).
