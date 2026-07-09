# ADR-2607092300: cloud-itonami-isic-7110 (Community Architectural and Engineering Practice) deepened to `:implemented`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607092200 (hospitalityops/5510)
- ADR-2607092151 (construction/4211 -- concurrent session)
- ADR-2607091900 (agronomyops/0162)
- ADR-2607091800 (quarryops/0810)
- ADR-2607091700 (freightops/4920)
- ADR-2607091600 (retailops/4711)
- ADR-2607091400 (ictrepair/9511)
- ADR-2607032000 (insurance/real-estate coverage)
- The 92 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

`cloud-itonami-isic-7110` (Community Architectural and Engineering
Practice, ISIC Rev.4 class 7110) was the next `:blueprint`-tier
candidate selected under the standing "pick a new ISIC blueprint
vertical" authorization, in its gftdcojp-origin-registry scope
extension.

A `kotoba-lang` org search for cae/engineering/architecture/cad/
drafting/drawingml-named repos surfaced `cad`, `kami-cad-import`,
`kami-engine-cae-solver` and `drawingml`. All four are verified to be
generic CAE/CAD/geometry technology substrate (the same tier as
`:robotics`, resolved via `kotoba.technology`'s own capability stack),
not a bespoke domain capability library. `com-cadence-eda` is also a
vendor-protocol compat shim. This build returns to self-contained
domain logic.

**First governor-name collision in this window's build sequence**:
this blueprint's own `:itonami.blueprint/governor` keyword was
originally `:practice-governor`, which collides with
`cloud-itonami-isic-8620`'s own (Clinical Practice Governor, medical/
dental practice). Every prior build this window (9511/4711/4920/0810/
0162/5510) was grep-verified unique on first attempt, so this is the
first time the collision-resolution path has actually been exercised.
Since clinical practice and architectural/engineering practice share
nothing beyond the word "practice," this build does not invoke the
fleet's established governor-name-reuse-precedent discipline (which
applies when a build genuinely wants to reuse an existing governor's
identity for a related domain) -- instead it picks a fresh, distinct
keyword.

## Decision

Build the full governed-actor architecture for practiceops/7110,
following the same template as all 92 prior actors:

- **Store**: `practiceops.store`, MemStore + DatomicStore, proven
  parity via contract test.
- **Registry**: `practiceops.registry`, pure DRAFT-certificate
  construction via `unsigned-certificate`, jurisdiction-scoped
  sequence numbering (`JPN-VER-000007`, `JPN-DLV-000007`).
- **Governor**: `:design-governor` (renamed from `:practice-governor`
  after the collision described above; grep-verified unique
  fleet-wide), grounded in this blueprint's own "design" operating
  state.
- **Entity shape**: `commission`, no `:kind` discriminator. Design
  verification and certification delivery are **sequential** acts on
  the same record (verify first, deliver later) -- matching
  `freightops`/4920's, `quarryops`/0810's, `agronomyops`/0162's and
  `hospitalityops`/5510's own sequential shape, not `retailops`/
  4711's alternative-kind `order` shape. `high-stakes` =
  `#{:actuation/verify-design :actuation/deliver-certification}`.

### New HARD checks

1. **`design-outside-scope-violations`** (FLAGSHIP, 82nd distinct
   application of the unconditional-evaluation discipline overall).
   Unconditional: every verification is checked against its own
   `:within-code-scope?` ground truth. Grounded in real building/
   engineering-code law:
   - Japan: 建築基準法 (Building Standards Act), enforced by MLIT/
     prefectural governments.
   - US: International Building Code (IBC), as locally adopted --
     honestly labeled an ICC model code, not federal statute.
   - UK: Building Regulations 2010, as amended by the Building Safety
     Act 2022.
   - Germany: Musterbauordnung (MBO, model building code), as
     implemented per Land.

   A concurrent session independently landed `construction.governor/
   permit-and-inspection-required` on `cloud-itonami-isic-4211` in
   this same window, under its own separate numbering scheme --
   grep-verified no name collision.

2. **`professional-seal-invalid-violations`** (83rd distinct
   application overall, the **eleventh conditional variant** -- after
   socialresearch/7220's, bizassoc/9411's, training/8549's,
   furniture/9524's, specialtyrepair/9529's, leathergoods/9523's,
   ictrepair/9511's, quarryops/0810's, agronomyops/0162's and
   hospitalityops/5510's own, at 63rd, 64th, 66th, 67th, 68th, 69th,
   71st, 77th, 79th and 81st). Conditional on the commission's own
   `:requires-professional-seal?` ground truth -- not every
   deliverable legally requires a professional seal. Explicitly
   distinguished from `clinic.governor`'s own
   `credential-not-current-violations` (a clinician's treatment-time
   license currency, not a sealed deliverable's legal issuance
   authority). Grounded in real professional-licensure/seal-authority
   law, all four jurisdictions honestly having a real regime:
   - Japan: 建築士法 (Architects and Building Engineers Act) Article 20.
   - US: state Professional Engineering Practice Acts (NCEES Model
     Law/Rules).
   - UK: Architects Act 1997, enforced by the Architects Registration
     Board.
   - Germany: state Architekten-/Ingenieurgesetze, enforced by Länder
     chambers.

### Reapplied discipline (not claimed as new)

- **`fee-total-matches-claim?`**: re-derives the claimed fee (`hours ×
  rate`) independently and flags any mismatch -- the same
  ground-truth-recompute discipline `hospitalityops.registry`'s own
  `folio-total-matches-claim?`, `agronomyops.registry`'s own
  `dose-matches-claim?`, `quarryops.registry`'s own `royalty-matches-
  claim?` and `retailops.registry`'s own cost/total-matching checks
  establish.

### Field sync -- the same gap pattern as agronomyops/0162's and hospitalityops/5510's own

This repo's `blueprint.edn` had the correct `:required-technologies`
matching the `kotoba-lang/industry` registry's own entry for `"7110"`
exactly, but was **missing `:optional-technologies [:cfd
:optimization]` entirely**. Fixed cleanly in the same commit as the
`:maturity` flip and the `:governor` rename.

## Consequences

- Fleet maturity: `{:implemented 92 :blueprint 6 :spec 545 :total
  643}` → `{:implemented 93 :blueprint 5 :spec 545 :total 643}`,
  verified against `(kotoba.industry/maturity-summary)` ground truth
  and kept in exact sync in `docs/cloud-itonami.md`.
- 39 tests / 176 assertions in the child repo, lint clean; the demo
  (`clojure -M:dev:run`) confirmed all six distinct HARD-hold rules
  firing correctly via the audit-ledger output.
- `kotoba-lang/industry`'s own full suite re-run with the local-root
  technology override before committing -- 7 tests / 140 assertions,
  all green.
- `test/kotoba/industry_test.clj`'s still-blueprint example reference
  (`"9101"`) did not need to change.

## Alternatives considered

- **Keeping `:practice-governor` and documenting a reuse precedent
  with `clinic.governor`**: rejected -- clinical practice and
  architectural/engineering practice share nothing beyond the word
  "practice."
- **Treating `cad`/`kami-cad-import`/`kami-engine-cae-solver`/
  `drawingml` as this vertical's capability library**: rejected --
  generic CAE/CAD technology substrate, not domain-specific business
  logic.
- **An unconditional professional-seal check**: rejected -- some
  deliverables (preliminary/internal design memos) have no seal
  requirement at all.

## References

- `kotoba-lang/industry` registry entry `"7110"`.
- `cloud-itonami/cloud-itonami-isic-7110` repo, `ADR-0001`.
- 建築基準法 (Building Standards Act); 建築士法 (Architects and Building
  Engineers Act) Article 20 (Japan).
- International Building Code (IBC), as locally adopted; NCEES Model
  Law/Rules (US).
- Building Regulations 2010 / Building Safety Act 2022; Architects
  Act 1997 (UK).
- Musterbauordnung (MBO); state Architekten-/Ingenieurgesetze
  (Germany).
