# ADR-2607092200: cloud-itonami-isic-5510 (Community Accommodation Operations) deepened to `:implemented`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607092151 (construction/4211 -- concurrent session, robot-dispatch slice completed)
- ADR-2607091900 (agronomyops/0162 -- first agriculture-sector actor)
- ADR-2607091800 (quarryops/0810 -- no capability-library precedent)
- ADR-2607091700 (freightops/4920 -- self-caught field-sync error)
- ADR-2607091600 (retailops/4711 -- first capability-library wrap)
- ADR-2607091400 (ictrepair/9511)
- ADR-2607032000 (insurance/real-estate coverage)
- The 90 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

`cloud-itonami-isic-5510` (Community Accommodation Operations, ISIC
Rev.4 class 5510) was the next `:blueprint`-tier candidate selected
under the standing "pick a new ISIC blueprint vertical" authorization,
in its gftdcojp-origin-registry scope extension.

**Mid-build discovery**: a concurrent session independently completed
`cloud-itonami-isic-4211`'s (Community Building Construction) physical
robot-dispatch slice on top of its existing disaster/severe-weather
safety slice, promoting it cleanly to `:implemented` (superproject
ADR-2607092151). This resolves the long-tracked "4211
partially-implemented special case" that every prior ADR in this
window had explicitly deferred as out of scope for the pick-and-build
pattern. No action was needed from this build beyond accounting for
the updated fleet-maturity-before figure (91 implemented / 7
blueprint, not 90/8) when computing this build's own figures.

A `kotoba-lang` org search for hospitality/hotel/accommodation/pms/
booking/reservation-named repos surfaced `com-opera-pms` and
`com-shares-pms` -- both verified via their own README provenance
notes to be clean-room API-compatibility shims for specific commercial
PMS vendor protocols (relocated from `etzhayyim/root/20-actors/
*-compat` per ADR-2606302300), not a bespoke domain capability
library. This build returns to self-contained domain logic.

## Decision

Build the full governed-actor architecture for hospitalityops/5510,
following the same template as all 90 prior actors:

- **Store**: `hospitalityops.store`, MemStore + DatomicStore, proven
  parity via contract test.
- **Registry**: `hospitalityops.registry`, pure DRAFT-certificate
  construction via `unsigned-certificate`, jurisdiction-scoped
  sequence numbering (`JPN-CHI-000007`, `JPN-CHO-000007`).
- **Governor**: `:hospitality-governor` (grep-verified unique
  fleet-wide -- no naming-collision precedent question).
- **Entity shape**: `stay`, no `:kind` discriminator. Check-in and
  check-out are **sequential** acts on the same record (check in
  first, check out later) -- matching `freightops`/4920's,
  `quarryops`/0810's and `agronomyops`/0162's own sequential shape,
  not `retailops`/4711's alternative-kind `order` shape.
  `high-stakes` = `#{:actuation/check-in-guest :actuation/
  check-out-guest}`.

### New HARD checks

1. **`guest-registration-incomplete-violations`** (FLAGSHIP, 80th
   distinct application of the unconditional-evaluation discipline
   overall). Unconditional: every check-in is checked against its own
   `:guest-id-verified?` ground truth. Grounded in real guest-
   registration law:
   - Japan: 旅館業法第6条 (Hotel Business Act Article 6), enforced by
     prefectural governments/health centers.
   - UK: Immigration (Hotel Records) Order 1972, enforced under the
     Immigration Act 1971.
   - Germany: Bundesmeldegesetz §29, enforced by local Meldebehörden.

   **Honest single-jurisdiction gap for the US**: unlike the recent
   streak of full four-jurisdiction sub-citations
   (`quarryops`/0810's own blast-safety, `agronomyops`/0162's own
   water-buffer), guest registration in the US is governed by
   fragmented state-by-state "innkeeper" statutes rather than one
   uniform national law -- no US sub-citation is asserted, reported
   honestly rather than straining for a shaky single-state citation.

2. **`guest-disclosure-authorization-unconfirmed-violations`** (81st
   distinct application overall, the **tenth conditional variant** --
   after socialresearch/7220's, bizassoc/9411's, training/8549's,
   furniture/9524's, specialtyrepair/9529's, leathergoods/9523's,
   ictrepair/9511's, quarryops/0810's and agronomyops/0162's own, at
   63rd, 64th, 66th, 67th, 68th, 69th, 71st, 77th and 79th).
   Conditional on the stay's own `:disclosure-requested?` ground
   truth -- most stays have no pending third-party disclosure request
   at all. Directly grounded in the blueprint's own text ("guest
   disclosures require approval").

### Reapplied discipline (not claimed as new)

- **`folio-total-matches-claim?`**: re-derives the claimed folio total
  (`nights × rate`) independently and flags any mismatch -- the same
  ground-truth-recompute discipline `agronomyops.registry`'s own
  `dose-matches-claim?`, `quarryops.registry`'s own `royalty-matches-
  claim?`, `leathergoods`/9523's and `retailops`/4711's own cost/
  total-matching checks establish.

### Field sync -- the same gap pattern as agronomyops/0162's own

This repo's `blueprint.edn` had the correct `:required-technologies`
matching the `kotoba-lang/industry` registry's own entry for `"5510"`
exactly, but was **missing `:optional-technologies [:optimization]`
entirely** -- the same gap pattern `agronomyops`/0162's own build
found. Fixed cleanly in the same commit as the `:maturity` flip.

### Self-caught-and-corrected error: an ADR-reference comment

A genuine self-caught error occurred in the industry-registry
promotion step: the `registry.edn` comment for `"5510"` initially
referenced a speculative ADR id (`2607092000`), written before
checking the actual next-available superproject ADR id. After the
concurrent session's own 4211 ADR (`2607092151`) landed on
superproject main, the correct next id turned out to be `2607092200`
(this ADR's own id). Caught by re-checking `ls 90-docs/adr/` for the
true next-available id after the industry promotion had already
merged -- fixed via a dedicated follow-up commit (comment-only, no
functional/pin change), following the same self-caught-and-corrected-
error transparency discipline `freightops`/4920's own `:optimization`
mistake established. This is the second such self-caught error in
this window's build sequence, and the first to occur in an ADR
forward-reference rather than a technology-requirements field.

## Consequences

- Fleet maturity: `{:implemented 91 :blueprint 7 :spec 545 :total
  643}` → `{:implemented 92 :blueprint 6 :spec 545 :total 643}`,
  verified against `(kotoba.industry/maturity-summary)` ground truth
  and kept in exact sync in `docs/cloud-itonami.md`.
- 39 tests / 175 assertions in the child repo, lint clean; the demo
  (`clojure -M:dev:run`) confirmed all six distinct HARD-hold rules
  firing correctly via the audit-ledger output.
- `kotoba-lang/industry`'s own full suite re-run with the local-root
  technology override before committing -- 7 tests / 139 assertions,
  all green.
- `test/kotoba/industry_test.clj`'s still-blueprint example reference
  (`"9101"`) did not need to change.

## Scope note

4211 is no longer an exception requiring separate follow-up -- a
concurrent session completed its robot-dispatch slice (see Context
above). No verticals remain excluded from the standard pick-and-build
pattern.

## Alternatives considered

- **Treating `com-opera-pms`/`com-shares-pms` as this vertical's
  capability library**: rejected -- they are vendor-protocol
  compatibility shims, matching `quarryops`/0810's and `agronomyops`/
  0162's own investigated-and-ruled-out precedent.
- **An unconditional guest-disclosure-authorization check**: rejected
  -- most stays have no pending disclosure request at all.
- **Fabricating a US guest-registration citation** to preserve the
  recent full-coverage streak: rejected -- the same honesty
  discipline that forbids fabricating coverage also forbids straining
  for a shaky citation just to look complete.

## References

- `kotoba-lang/industry` registry entry `"5510"`.
- `cloud-itonami/cloud-itonami-isic-5510` repo, `ADR-0001`.
- Hotel Business Act (旅館業法) Article 6 (Japan); Immigration (Hotel
  Records) Order 1972 (UK); Bundesmeldegesetz (BMG) §29 (Germany);
  Hotel and Motel Fire Safety Act of 1990, 15 U.S.C. §2225 (US).
