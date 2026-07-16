# ADR-2607103400: cloud-itonami-isic-5229 (Community Freight Forwarding and Customs Brokerage) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607103300 (landtransportops/5221 -- twentieth "author new
  blueprints from `:spec`" build, closed the transport-support mode
  trio)
- ADR-2607103200 (portauthorityops/5222), ADR-2607103100
  (airportops/5223), ADR-2607102900 (cargohandlingops/5224 -- opened
  the "transport support" family)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWENTY-FIRST "author a new blueprint from `:spec` tier"
build, and the FIFTH and FINAL "transport support" pick in this
specific family (following cargo handling, airport operations, port
authority and land-transport support).

### Candidate selection

`"5229"` (Other transportation support activities) was selected,
scoped to its real-world standard interpretation: freight forwarding
and customs brokerage. This is a distinctly, richly regulated
profession (the US requires a Customs Broker License under 19 CFR
Part 111; Japan's 通関業法 Customs Business Act licenses 通関士
customs specialists separately from carriers and terminal operators;
the EU's Union Customs Code requires Authorised Economic Operator /
customs representative status; freight forwarders carry their own
professional liability and bonding regimes distinct from carrier
cargo liability, e.g. FIATA-model forwarder liability terms).

Two candidates were screened and set aside before this selection:
`"5320"` (courier activities) was checked via `grep -rli "courier|
parcel|last.mile"` across the fleet, which returned 23 hits -- almost
all generic uses of "courier"/"last-mile" as a verb/descriptor for a
robot-carried item (e.g. `cloud-itonami-isic-6511`'s own "medical-exam
sample courier"), but ONE genuine overlap risk: `cloud-itonami-isic-
4920`'s own README explicitly claims "regional or last-mile carrier"
and "last-mile robot" as part of its OWN scope. Because resolving this
overlap would require careful scoping work this pass did not have
room for, `"5320"` (and its sibling `"5310"` postal) remain
deliberately deferred, continuing the precedent `ADR-2607102900`
established. `"5229"` was picked instead: `grep -rli "freight.forward|
customs.broker|customs.clearance"` across the ENTIRE fleet returned
completely empty -- no redundancy risk at all. Distinct from every
CARRIER vertical this scope (`4911`/`4912`/`4920`/`5110`/`5011`/`5020`)
and from `cloud-itonami-isic-5224` (cargo handling, a terminal
SERVICE): a freight forwarder ARRANGES carriage across multiple
carriers/modes and clears customs, without owning transport assets or
operating a terminal. Governor keyword `:freight-forwarding-governor`
grep-verified UNIQUE fleet-wide. The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-H5229`) was confirmed via a direct GitHub API
404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:freight-forwarding-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing this vertical from carriers and cargo handling

Following the same discipline established repeatedly this scope: the
README's own "Scope note" section directly names every relevant
carrier vertical and `cloud-itonami-isic-5224`, and explains the
intermediary-vs-asset-owner distinction, rather than merely asserting
non-redundancy.

### Decision 4: reuse the booking/transit/delivery/reconciliation shape

The "Core Contract" and Trust Controls text follows the SAME framing
established across every transport-family build this scope
(`:intake :book :transit :deliver :reconcile :audit`, matching the
registry's own pre-existing `:operating-states` for `"5229"`),
reframed for customs-compliance concerns (a customs declaration
outside a verified compliance scope, a consolidated shipment
dispatched without a completed document-verification pass, a
reconciliation record without verified evidence).

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-5229` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 126 prior/sibling actors, rather than the legacy
`H####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-H5229"` to `"cloud-itonami-5229"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-5229` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"5229"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching multi-carrier routing-optimization
needs), and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607103300`'s own `(is (= 20 (:blueprint m)))` assertion updated
to `(is (= 21 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"5229")))`) alongside the twenty prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 20 :spec 520 :total 646}`
  → `{:implemented 106 :blueprint 21 :spec 519 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 174
  assertions, all green.
- `cloud-itonami-isic-5229` joins the twenty prior fresh-blueprint
  picks as a twenty-first candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- The entire "transport support" family opened at `ADR-2607102900` is
  now complete except for the deliberately-deferred `"5310"`/`"5320"`
  (postal/courier) pair, which requires careful scoping against
  `cloud-itonami-isic-4920`'s own last-mile claim before it can be
  safely picked up.

## Alternatives considered

- **`"5320"` (courier activities)**: screened via `grep -rli "courier|
  parcel|last.mile"` (23 hits, mostly generic robot-descriptor usage);
  set aside specifically because `cloud-itonami-isic-4920`'s own
  README claims "last-mile carrier"/"last-mile robot" scope, creating
  a genuine redundancy risk that needs a dedicated scoping pass.
- **`"5310"` (postal activities)**: still deferred per
  `ADR-2607102900`'s own reasoning -- carries a distinct
  universal-service-obligation regulatory framing.

## References

- `kotoba-lang/industry` registry entry `"5229"`.
- `cloud-itonami/cloud-itonami-isic-5229` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-5221/blueprint.edn` and `cloud-itonami-isic-5224/
  blueprint.edn` (the sibling shape and scope-note pattern this build
  reuses).
