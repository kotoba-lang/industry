# ADR-2607091800: cloud-itonami-isic-0810 (Community Quarry and Stone Supply) deepened to `:implemented`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607071849 (repairshop/9521, origin of the governed-actor template)
- ADR-2607091400 (ictrepair/9511)
- ADR-2607091600 (retailops/4711 — first capability-library wrap)
- ADR-2607091700 (freightops/4920 — second capability-library wrap, self-caught field-sync error)
- ADR-2607032000 (insurance/real-estate coverage)
- The 88 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

`cloud-itonami-isic-0810` (Community Quarry and Stone Supply, ISIC Rev.4 class
0810) was the next `:blueprint`-tier candidate selected under the standing
"pick a new ISIC blueprint vertical" authorization, in its gftdcojp-origin-
registry scope extension (explicitly approved by the user after the original
`cloud-itonami/cloud-itonami-isic-*` fleet was exhausted at 85 actors).

Before designing fresh domain logic, this build explicitly checked for a
bespoke `kotoba-lang/<domain>` capability library — the discipline established
by retailops/4711 (`kotoba-lang/retail`) and freightops/4920
(`kotoba-lang/logistics`), both genuinely wrapping real pre-existing
libraries. For quarrying, no such library exists. `kotoba-lang/robotics` was
investigated as a candidate and explicitly **ruled out**: it is the generic
cross-cutting robotics contract (missions/actions/safety-stops/telemetry
proofs) every cloud-itonami vertical implicitly depends on, not a
quarry-specific domain library. This build therefore correctly returns to
self-contained domain logic, same as every actor before retailops/4711.

## Decision

Build the full governed-actor architecture for quarryops/0810, following the
same template as all 88 prior actors:

- **Store**: `quarryops.store`, MemStore + DatomicStore, proven parity via
  contract test.
- **Registry**: `quarryops.registry`, pure DRAFT-certificate construction via
  `unsigned-certificate`, jurisdiction-scoped sequence numbering
  (`JPN-EXT-000007`, `JPN-SHP-000007`).
- **Governor**: `:quarry-governor` (grep-verified unique fleet-wide — no
  naming-collision precedent question).
- **Entity shape**: `extraction`, no `:kind` discriminator. Extraction and
  shipment are **sequential** acts on the same record (extract first, ship
  later) — matching freightops/4920's own `shipment` shape, not retailops/4711's
  alternative-kind `order` shape. `high-stakes` = `#{:actuation/extract-material
  :actuation/ship-consignment}`.

### New HARD checks

1. **`extraction-permit-invalid-violations`** (FLAGSHIP, 76th distinct
   application of the unconditional-evaluation discipline overall).
   Unconditional: every extraction is checked against its own
   `:permit-valid?` ground truth. Grounded directly in the blueprint's own
   text ("extraction outside permit is blocked") and real mine-safety law:
   - US: the Mine Act, enforced by MSHA.
   - UK: the Quarries Regulations 1999, enforced by the HSE.
   - Germany: the Bundesberggesetz (BBergG), enforced by the Länder's
     Bergämter.
   - Japan: 鉱山保安法 (Mine Safety Act), enforced by METI's Industrial
     Safety Group.

2. **`blast-safety-clearance-unconfirmed-violations`** (77th distinct
   application overall, the **eighth conditional variant** — after
   socialresearch/7220's, bizassoc/9411's, training/8549's, furniture/9524's,
   specialtyrepair/9529's, leathergoods/9523's and ictrepair/9511's own, at
   63rd, 64th, 66th, 67th, 68th, 69th and 71st). Conditional on the
   extraction's own `:involves-blasting?` ground truth — mechanical
   extraction methods (e.g. diamond-wire cutting) have no blast-safety
   concern at all, so the check is a genuine no-op for them, exercised via a
   dedicated test. Grounded in real explosives/blast-safety law, all four
   seeded jurisdictions honestly having a real regime here:
   - US: 30 C.F.R. Part 56 Subpart E, enforced by MSHA.
   - UK: Quarries Regulations 1999 shot-firing provisions / Explosives
     Regulations 2014, enforced by the HSE.
   - Germany: Sprengstoffgesetz (SprengG).
   - Japan: 火薬類取締法 (Explosives Control Act).

### Reapplied discipline (not claimed as new)

- **`royalty-mismatch-violations`**: re-derives the claimed royalty
  (`quantity × royalty-rate`) independently and flags any mismatch — the same
  ground-truth-recompute discipline leathergoods/9523's, specialtyrepair/9529's
  and retailops/4711's own cost/total-matching checks establish. No code is
  shared (different domain); only the discipline is reused.

### Field sync

`blueprint.edn`'s `:required-technologies` already matched the
`kotoba-lang/industry` registry entry for `"0810"` exactly — a clean fix (only
`:maturity :implemented` needed adding), unlike freightops/4920's own
self-caught-and-corrected `:optimization` mistake.

## Consequences

- Fleet maturity: `{:implemented 88 :blueprint 10 :spec 545 :total 643}` →
  `{:implemented 89 :blueprint 9 :spec 545 :total 643}`, verified against
  `(kotoba.industry/maturity-summary)` ground truth and kept in exact sync in
  `docs/cloud-itonami.md`.
- 39 tests / 176 assertions in the child repo; `kotoba-lang/industry`'s own
  full suite re-run with the local-root technology override before
  committing — 7 tests / 136 assertions, all green.
- `test/kotoba/industry_test.clj`'s still-blueprint example reference
  (`"9101"`, set during ictrepair/9511's own promotion) did not need to
  change, since `"0810"` was never the referenced example.
- The standing docstring-embedded-literal-quote parse-check on
  `governor.cljc` caught a genuine stray-closing-paren syntax bug in
  `extraction-permit-invalid-violations` before any test run, confirming the
  proactive parse-check discipline continues to earn its keep even for bug
  shapes unlike its original motivating case.

## Scope note

This build stays within the current 3-tier maturity model
(`:spec`/`:blueprint`/`:implemented`). `cloud-itonami-isic-4211` (Community
Building Construction), which is `:partially-implemented`, remains explicitly
out of scope for this build pattern — it needs a different kind of follow-up
(completing an existing partial implementation and/or a maturity-model change
to add a fourth tier), not a fresh "pick and build" vertical.

## Alternatives considered

- **Wrapping `kotoba-lang/robotics` as a quarry-specific capability
  library**: rejected — it is the generic cross-cutting robotics contract
  every vertical already depends on, not domain-specific to quarrying.
  Documented explicitly so future builds don't need to re-derive this.
- **Alternative-kind entity shape for `extraction`** (mirroring retailops/4711's
  `order` with a `:kind` field): rejected — extract and ship are genuinely
  sequential acts on the same record, not alternative acts, matching
  freightops/4920's own reasoning for `shipment`.

## References

- `kotoba-lang/industry` registry entry `"0810"`.
- `cloud-itonami/cloud-itonami-isic-0810` repo, `ADR-0001`.
- US Mine Act / MSHA; UK Quarries Regulations 1999 / HSE; Germany's
  Bundesberggesetz; Japan's 鉱山保安法.
- US 30 C.F.R. Part 56 Subpart E; UK Explosives Regulations 2014; Germany's
  Sprengstoffgesetz; Japan's 火薬類取締法.
