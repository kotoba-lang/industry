# ADR-2613000000: cloud-itonami-isic-2029 (Manufacture of other chemical products n.e.c.) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-2021 (Manufacture of pesticides and other
agrochemical products -- the reference module shape this actor mirrors,
independently re-read in full before use), cloud-itonami-isic-2022
(Manufacture of paints, varnishes and similar coatings -- consulted for
its viscosity-plausibility precedent), the `kotoba-lang/industry`
registry's `"2029"` catalog entry (previously `:spec`, no populated repo)

## Context

`kotoba-lang/industry`'s registry carried a `"2029"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C2029` -- a
placeholder repo link with no actual implementation behind it (confirmed
404 via `gh api` for `cloud-itonami/cloud-itonami-isic-2029` itself before
any work began). This is part of an ongoing careful, smaller-batch rollout
(one ISIC class per agent, a capable model, mandatory verification) after
a prior 18-agent haiku batch produced a 61% defect rate on other ISIC
classes; 138+ consecutive agents on this stricter protocol had all
succeeded since. This ADR covers ISIC 2029 only.

Before any work began, the registry entry's identity was independently
verified against a fresh clone of `kotoba-lang/industry`, per this
fleet's caution against ID/name mismatches (prior agents in this fleet
had mislabeled other ISIC classes). The live `:name` field for `"2029"`
read exactly `"Manufacture of other chemical products n.e.c."` -- not
truncated, and distinct from every other 20xx sibling already
`:implemented` in the registry (2011 basic chemicals, 2012 fertilizers,
2013 plastics/synthetic rubber, 2021 pesticides/agrochemicals, 2022
paints/coatings, 2023 soap/detergents, 2030 man-made fibres).

ISIC 2029 is a **residual "not elsewhere classified" category**: it
covers whatever chemical-product manufacturing does not fit any more
specific ISIC 20xx class (e.g. adhesives/glues, essential oils, matches,
photographic chemicals) rather than one coherent plant shape. Per this
task's own guidance, this build does not attempt to model the whole
heterogeneous n.e.c. bucket -- it adopts ONE concrete illustrative
product line, documented plainly in the README, and scopes every domain
fact to that illustration.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-2029` as a specialty-chemicals
PLANT OPERATIONS COORDINATION actor (not direct reactor/mixing-line
control authority, and NOT a chemical-safety-certification authority),
mirroring `cloud-itonami-isic-2021`'s verified module shape
(`advisor`/`governor`/`operation`/`phase`/`registry`/`sim`/`store`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY/`docs/adr/0001-architecture.md`) with fresh,
adhesives-specific domain logic under the `adhesivemfg` namespace.
**Chosen concrete illustration: industrial/consumer adhesives and glues
manufacturing** -- reaction/mixing via a batch reactor or mixing tank,
plus filling/packaging lines, producing hot-melt, solvent-based,
water-based, pressure-sensitive, reactive-polyurethane, epoxy,
cyanoacrylate, and rubber-based adhesive/glue products.

1. **`adhesivemfg.registry`** -- pure-function domain logic: equipment/
   batch verification, shipment-weight recompute, the closed
   `valid-product-types` set (eight adhesive product types), viscosity
   plausibility (0-1,000,000 cP, reusing 2022 `paintmfg.registry`'s
   viscosity-range precedent), purity plausibility (0-100%), and a
   closed `purity-spec-floor-pct` table -- a representative per-
   product-category MINIMUM purity/solids-content spec, the mirror
   image of 2021's active-ingredient-concentration label CEILING
   (`active-ingredient-exceeds-label-limit?`), since an adhesive
   batch's efficacy requires AT LEAST its product type's own minimum
   purity/solids content, not a maximum.
2. **`adhesivemfg.store`** -- `MemStore` SSoT (batches/equipment/
   maintenance/shipments/safety-concerns/ledger), seeded with two
   verified+registered batches (one with shipping headroom, one nearly
   fully shipped) and one unverified/unregistered batch, one
   verified+registered reactor unit and one unverified/unregistered
   mixing-tank unit -- identical shape to 2021's `pesticidemfg.store`,
   renamed equipment `:kind` from blending-tank/high-shear-mixer to
   `:reactor`/`:mixing-tank`.
3. **`adhesivemfg.governor`** (`Adhesive Plant Operations Governor`) --
   thirteen concrete HARD checks elaborating four HARD invariants
   (propose-only effect, closed op allowlist, closed proposal-effect
   allowlist, permanent reactor/mixing-line-equipment-actuation block,
   permanent chemical-safety-certification-decision block, equipment/
   batch independent verification gates, shipment-weight recompute,
   double-schedule guard, product-type validation, viscosity
   plausibility, purity plausibility, purity-below-spec-floor) plus one
   SOFT confidence/high-stakes escalation gate -- mirroring 2021's
   `pesticidemfg.governor` module-for-module, with
   `certification-decision-blocked-violations` (triggered by a
   `:log-production-batch` proposal's `:value` declaring
   `:decide-certification? true`) replacing 2021's domain-specific
   `registration-decision-blocked-violations` (pesticide-registration/
   label-approval), since this vertical's regulatory boundary is a
   chemical-safety certification (e.g. GHS/CLP hazard classification,
   an SDS certification, a third-party product-safety mark) rather
   than a product registration.
4. **`adhesivemfg.phase`** -- Phase 0->3 rollout, `:schedule-
   maintenance`/`:flag-safety-concern`/`:coordinate-shipment` never in
   any phase's `:auto` set; only `:log-production-batch` auto-commits
   at phase 3 when governor-clean -- identical structure to 2021.
5. **`adhesivemfg.advisor`** (`AdhesiveAdvisor`) -- deterministic mock
   advisor (default) + `llm-advisor` (real `langchain.model` backing),
   proposal-only, never trusted by the governor for any ground-truth
   check.
6. **`adhesivemfg.operation`** (`AdhesiveOperationActor`) -- the
   langgraph-clj StateGraph itself: intake -> advise -> govern -> decide
   -> commit | hold | request-approval, `interrupt-before
   #{:request-approval}` for human-in-the-loop approval, identical graph
   topology to 2021's `pesticidemfg.operation`.
7. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-2021`'s shape (`:test`/`:lint`/`:run`/`:dev`
   aliases pinning `io.github.kotoba-lang/langgraph` and
   `io.github.kotoba-lang/langchain` via `:local/root` directly in the
   top-level `:deps`, `itonami.blueprint/*` metadata). `:itonami.
   blueprint/robotics` is `true`, matching 2021 and 2022 (a robotics-
   assisted reactor/mixing station may perform the physical work under
   this actor's coordination, gated by the governor).

### Scope: one illustration, not the whole n.e.c. bucket

Rather than build a generic "any n.e.c. chemical product" actor, this
build picks adhesives/glues manufacturing as ONE concrete illustration
and scopes every domain fact (product types, viscosity range, purity
spec floors) to it, documented plainly in the README's "Scope" section.
A future actor covering a different n.e.c. product family (essential
oils, matches, photographic chemicals, etc.) would be a separate build,
not an extension of this one.

### What this actor does NOT do

Directly controlling the reactor or mixing-line equipment, or actuating
the reactor/mixing/filling/packaging line, remains the exclusive
authority of the human plant supervisor, permanently, with no actor or
human-approval override path -- enforced by the closed proposal-effect
allowlist plus the `line-actuate-blocked-violations` HARD, unconditional
block. Deciding or granting a chemical-safety certification is
EXCLUSIVELY a certification authority's call, never this actor's --
enforced by `certification-decision-blocked-violations`, HARD,
PERMANENT, unconditional, with no phase or human-approval override
path either. Both blocks are grep-verified fleet-wide unique
(`adhesivemfg` namespace and `:adhesive-plant-operations-governor`
governor keyword, zero hits via `gh search code --owner cloud-itonami`
before this repo was created).

## Verification

- `cloud-itonami-isic-2029`: `clojure -M:test` -- raw final line: `Ran 85
  tests containing 231 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- `linting took 722ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` -- all four happy-path scenarios (clean batch log
  auto-commit, maintenance scheduling escalate/approve, safety-concern
  escalate/approve, shipment coordination escalate/approve) plus all
  thirteen HARD-hold scenarios (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-weight-exceeded,
  line-actuate-blocked, certification-decision-blocked,
  already-scheduled, invalid-product-type, invalid-viscosity,
  invalid-purity, purity-below-spec-floor) exercised directly, one
  request per scenario, exit code 0, zero exceptions.
- All source under `src/`/`test/` is `.cljc` with no JVM-only interop;
  the actor graph is invoked exclusively via `langgraph.graph/run*`.
- Repo created fresh (`gh repo create` + push), initial commit
  `59a0e96fe486f5f4dc0f70fff04296374b1a7e08` on
  `cloud-itonami-isic-2029`'s `main` (no prior history).
- `kotoba-lang/industry` registry `"2029"` entry updated in place (exact-
  text edit of the literal `{:id "2029" ...}` block only, no other
  entry's block touched): `:repo`/`:business-id` corrected from the
  never-populated `gftdcojp/cloud-itonami-C2029` placeholder to
  `cloud-itonami/cloud-itonami-isic-2029` / `cloud-itonami-isic-2029`;
  `:maturity` `:spec` -> `:implemented`; `:name`/`:required-
  technologies`/`:optional-technologies`/`:operating-states` left as
  already-registered (no truncation bug on this entry).
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  (live-recomputed via `(industry/maturity-summary)` against a freshly
  re-fetched `origin/main` immediately before the edit, not assumed --
  this is an extremely hot, high-concurrency shared file).
- Full `kotoba-lang/industry` suite re-run green after the registry edit
  (fresh clone, plus a fresh `kotoba-lang/technology` sibling clone for
  `deps.edn` resolution).
- Final post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone of both `cloud-itonami-isic-2029` and
  `kotoba-lang/industry`, plus a fresh `kotoba-lang/technology` sibling):
  `clojure -M:test` re-run green in the actor repo; the registry's
  `"2029"` entry confirmed `:maturity :implemented` with the corrected
  `:repo`/`:business-id`, and a sample of 2-3 unrelated entries confirmed
  untouched. Checked for file-wide UTF-8 mojibake in `registry.edn`
  (none found).

## Consequences

(+) `cloud-itonami-isic-2029` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"2029"` entry promoted to `:maturity
:implemented`.
(+) `adhesivemfg.registry`'s purity-spec-floor table demonstrates the
"floor, not ceiling" variant of the ground-truth quality-check pattern
2021/2022 established with their own label-ceiling/VOC-ceiling checks --
a reusable pattern for any future ISIC class in this fleet whose product
quality is bounded by a MINIMUM spec rather than a maximum.
(+) `adhesivemfg.governor`'s `certification-decision-blocked-violations`
demonstrates the domain-specific-permanent-block pattern adapted from a
product-registration authority (2021) to a certification authority --
reusable for any future ISIC class whose regulatory boundary is a
certification/marking scheme rather than a registration/label-approval
scheme.
(+) The "one concrete illustrative product line, not the whole n.e.c.
bucket" scope decision keeps this build falsifiable and testable instead
of gesturing at an unbounded residual category, and is documented as a
reusable precedent for other residual ("n.e.c.", "other ...") ISIC
classes this fleet will eventually reach.
(-) This build does not cover ISIC 2029's other product families
(essential oils, matches, photographic chemicals, etc.) -- a future
actor covering a different n.e.c. product family would be a separate
build, not an extension of this one.
(-) None known beyond the standard cloud-itonami actor scope
(coordination, not control; simulation/proposal layer, not a real
plant-operations control system).
