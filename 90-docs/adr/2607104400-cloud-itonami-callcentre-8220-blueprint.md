# ADR-2607104400: cloud-itonami-isic-8220 (Community Call Centre Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607104300 (printsupportops/1812 -- twenty-ninth "author new
  blueprints from `:spec`" build, closed the printing low-friction-
  sibling pair)
- ADR-2607100500 (securityops/8010), ADR-2607103500
  (securitysystemsops/8020), ADR-2607103800 (landscapeops/8130),
  ADR-2607103900 (buildingcleaningops/8121) -- the four prior
  labor-dispatch-shape builds this one also reuses
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the THIRTIETH "author a new blueprint from `:spec` tier"
build. Continuing the portfolio-diversification discipline
established at `ADR-2607103500`, this build moves away from the
just-closed printing family (`1811`/`1812`) into a fresh BPO/customer-
service domain.

### Candidate selection

`"8220"` (Activities of call centres) was selected: scoped to the
customer-contact SERVICE business (staffing, scripting, quality
assurance, data handling for client businesses' inbound/outbound
calls and chats), richly and independently regulated (PCI-DSS where
payment-card data is handled over the phone; ISO 18295 as the
international customer-contact-centre quality standard; COPC
certification as a widely recognized customer-experience quality
framework; telemarketing-specific regulation under the US Telephone
Consumer Protection Act and FTC Telemarketing Sales Rule and national
Do-Not-Call registries; GDPR/CCPA data-protection obligations given
the volume of customer PII processed).

Redundancy screening, verified before selection: `grep -rli
"call.centre|call.center|customer.service.outsourc|bpo"` across every
`blueprint.edn`, `docs/business-model.md` and `README.md` in the
fleet returned completely empty. Distinct from the fleet's telecom-
infrastructure verticals (mobile network operation, VoIP reselling),
which provide the underlying communications carriage rather than the
customer-contact staffing/quality-assurance service. Governor keyword
`:call-centre-governor` grep-verified UNIQUE fleet-wide. The legacy
placeholder `:repo` (`gftdcojp/cloud-itonami-N8220`) was confirmed via
a direct GitHub API 404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:call-centre-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: robotics premise interpreted as voice/chat automation "bots"

Because this vertical is labor-intensive rather than physical-plant-
intensive, the README's own "Robotics premise" section explicitly
addresses how the fleet-wide robotics-premise design (ADR-2607011000)
applies here: automated voice/chat first-line-triage systems --
already colloquially called "bots" in the contact-center industry
itself -- operate under the same actor-proposes/governor-gates
pattern as every physical-robot vertical, rather than inventing an
exception to the premise.

### Decision 4: reuse the labor-dispatch operating-states shape, now FIVE sibling reuses

The registry's own pre-existing `:operating-states` for `"8220"`
(`:intake :register :match :dispatch :follow-up :audit`) is IDENTICAL
to `securityops`/8010, `securitysystemsops`/8020, `landscapeops`/8130
and `buildingcleaningops`/8121's own -- this build reuses that shape
verbatim, adapted for data-handling/compliance-scope concerns (a
data-handling action outside verified compliance scope, an agent
match without a completed registration/training check). This is now
the FIFTH sibling in the fleet reusing this exact shape, extending it
beyond blue-collar/trades labor into an office/BPO context.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-8220` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 135 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N8220"` to `"cloud-itonami-8220"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-8220` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"8220"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching agent-scheduling/routing optimization
needs), and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607104300`'s own `(is (= 29 (:blueprint m)))` assertion updated
to `(is (= 30 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"8220")))`) alongside the twenty-nine prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 29 :spec 511 :total 646}`
  → `{:implemented 106 :blueprint 30 :spec 510 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 183
  assertions, all green.
- `cloud-itonami-isic-8220` joins the twenty-nine prior fresh-
  blueprint picks as a thirtieth candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- Establishes a reusable pattern for interpreting the robotics premise
  in labor-intensive, non-physical-plant verticals (automation "bots"
  gated by the same governor pattern), useful for future BPO-adjacent
  candidates (e.g. `"8219"` photocopying/document preparation).

## Alternatives considered

- **`"7710"` (renting and leasing of motor vehicles)**: a comparable
  fresh-domain candidate; set aside in favor of the richer
  BPO-specific regulatory story `8220` offered.
- **`"8219"` (photocopying, document preparation and other specialized
  office support activities)**: a comparable BPO-adjacent candidate;
  remains available for a future build.

## References

- `kotoba-lang/industry` registry entry `"8220"`.
- `cloud-itonami/cloud-itonami-isic-8220` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-8121/blueprint.edn` (the sibling shape this
  build reuses).
