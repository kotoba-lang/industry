# ADR-2607091900: cloud-itonami-isic-0162 (Community Agronomy Support) deepened to `:implemented`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607091800 (quarryops/0810 -- no capability-library precedent)
- ADR-2607091700 (freightops/4920 -- self-caught field-sync error)
- ADR-2607091600 (retailops/4711 -- first capability-library wrap)
- ADR-2607091400 (ictrepair/9511)
- ADR-2607032000 (insurance/real-estate coverage)
- The 89 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

`cloud-itonami-isic-0162` (Community Agronomy Support, ISIC Rev.4
class 0162) was the next `:blueprint`-tier candidate selected under
the standing "pick a new ISIC blueprint vertical" authorization, in
its gftdcojp-origin-registry scope extension. This is the **first
agriculture-sector (ISIC section A) actor in the fleet** -- every
other 01xx entry (crop growing, animal raising, mixed farming, etc.)
remains `:spec`-tier.

Before designing fresh domain logic, this build checked for a bespoke
`kotoba-lang/<domain>` capability library via the GitHub search API --
`agronomy`, `agriculture`, `farm`, `crop` and `soil` all returned zero
hits in the `kotoba-lang` org. `kotoba-lang/robotics` was investigated
and ruled out (matching `quarryops`/0810's own precedent): it is the
generic cross-cutting robotics contract every cloud-itonami vertical
implicitly depends on, not a domain-specific library. This build
therefore returns to self-contained domain logic.

## Decision

Build the full governed-actor architecture for agronomyops/0162,
following the same template as all 89 prior actors:

- **Store**: `agronomyops.store`, MemStore + DatomicStore, proven
  parity via contract test.
- **Registry**: `agronomyops.registry`, pure DRAFT-certificate
  construction via `unsigned-certificate`, jurisdiction-scoped
  sequence numbering (`JPN-SPL-000007`, `JPN-TRT-000007`).
- **Governor**: `:agronomy-governor` (grep-verified unique
  fleet-wide -- no naming-collision precedent question).
- **Entity shape**: `visit`, no `:kind` discriminator.
  Sample-collection and treatment-application are **sequential** acts
  on the same record (sample first, treat later) -- matching
  `freightops`/4920's and `quarryops`/0810's own sequential shape, not
  `retailops`/4711's alternative-kind `order` shape. `high-stakes` =
  `#{:actuation/collect-sample :actuation/apply-treatment}`.

### New HARD checks

1. **`treatment-product-unapproved-violations`** (FLAGSHIP, 78th
   distinct application of the unconditional-evaluation discipline
   overall). Unconditional: every treatment is checked against its
   own `:approved-for-crop?` ground truth. Grounded in real
   agrochemical-registration law:
   - US: FIFRA, enforced by the EPA.
   - UK: Plant Protection Products Regulations 2011, enforced by the
     HSE's Chemicals Regulation Division.
   - Germany: Pflanzenschutzgesetz, enforced by the BVL.
   - Japan: 農薬取締法 (Agricultural Chemicals Regulation Act),
     enforced by MAFF.

2. **`water-source-buffer-violation-violations`** (79th distinct
   application overall, the **ninth conditional variant** -- after
   socialresearch/7220's, bizassoc/9411's, training/8549's,
   furniture/9524's, specialtyrepair/9529's, leathergoods/9523's,
   ictrepair/9511's and quarryops/0810's own, at 63rd, 64th, 66th,
   67th, 68th, 69th, 71st and 77th). Conditional on the visit's own
   `:near-water-source?` ground truth -- a field with no water source
   nearby has no buffer-zone requirement at all, exercised via a
   dedicated no-op test. Grounded in real water-buffer-zone law, all
   four seeded jurisdictions honestly having a real regime:
   - US: FIFRA label buffer-zone requirements / Clean Water Act NPDES
     Pesticide General Permit, enforced by the EPA.
   - UK: buffer-zone conditions in product authorization /
     Environmental Permitting Regulations 2016, enforced by the
     Environment Agency.
   - Germany: Pflanzenschutz-Anwendungsverordnung (PflSchAnwV),
     enforced by the Länder's plant protection services.
   - Japan: 水質汚濁防止法 (Water Pollution Prevention Act) plus MAFF/
     environment-ministry drift-prevention guidance.

### Reapplied discipline (not claimed as new)

- **`dose-matches-claim?`**: re-derives the claimed treatment dose
  (`area × label-rate`) independently and flags any mismatch -- the
  same ground-truth-recompute discipline `quarryops.registry`'s own
  `royalty-matches-claim?`, `leathergoods`/9523's and `retailops`/
  4711's own cost/total-matching checks establish.

### Field sync -- a genuine gap found and fixed

Unlike `quarryops`/0810's clean fix (only `:maturity` needed adding),
this repo's `blueprint.edn` was **missing `:optional-technologies
[:optimization]` entirely** -- the `kotoba-lang/industry` registry's
own entry for `"0162"` has it, but `blueprint.edn` had no
`:optional-technologies` key at all. This was found by reading the
full registry entry carefully before concluding a field was absent,
applying the lesson from `freightops`/4920's own self-caught
field-sync error (that mistake stemmed from an incomplete/truncated
read; this time the full entry was read first). Fixed cleanly in the
same commit as the `:maturity` flip, not as a follow-up correction.

## Consequences

- Fleet maturity: `{:implemented 89 :blueprint 9 :spec 545 :total
  643}` → `{:implemented 90 :blueprint 8 :spec 545 :total 643}`,
  verified against `(kotoba.industry/maturity-summary)` ground truth
  and kept in exact sync in `docs/cloud-itonami.md`.
- 39 tests / 176 assertions in the child repo, lint clean; the demo
  (`clojure -M:dev:run`) confirmed all six distinct HARD-hold rules
  firing correctly via the audit-ledger output.
- `kotoba-lang/industry`'s own full suite re-run with the local-root
  technology override before committing -- 7 tests / 137 assertions,
  all green.
- `test/kotoba/industry_test.clj`'s still-blueprint example reference
  (`"9101"`) did not need to change.

## Scope note

This build stays within the current 3-tier maturity model
(`:spec`/`:blueprint`/`:implemented`). `cloud-itonami-isic-4211`
(Community Building Construction), which is `:partially-implemented`,
remains explicitly out of scope for this build pattern.

## Alternatives considered

- **Wrapping `kotoba-lang/robotics` as an agronomy-specific capability
  library**: rejected -- it is the generic cross-cutting robotics
  contract every vertical already depends on, matching `quarryops`/
  0810's own reasoning.
- **An unconditional water-source-buffer check**: rejected -- a field
  with no water source nearby has no buffer-zone concern at all.
- **Fabricating a jurisdiction gap** to match some prior siblings' own
  honest single-jurisdiction gap: rejected -- the same honesty
  discipline that forbids fabricating coverage also forbids
  under-reporting it.

## References

- `kotoba-lang/industry` registry entry `"0162"`.
- `cloud-itonami/cloud-itonami-isic-0162` repo, `ADR-0001`.
- Federal Insecticide, Fungicide, and Rodenticide Act (FIFRA), 7
  U.S.C. §136 et seq. (US); Plant Protection Products Regulations
  2011 / Environmental Permitting Regulations 2016 (UK);
  Pflanzenschutzgesetz / Pflanzenschutz-Anwendungsverordnung
  (Germany); 農薬取締法 / 水質汚濁防止法 (Japan).
