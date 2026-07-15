# ADR-2613300000: cloud-itonami ISIC 2829 (Manufacture of other special-purpose machinery) actor coverage

## Status

Accepted. `cloud-itonami-isic-2829` scaffolded fresh (no prior repo
existed — confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-2829`
404 before this work began) and landed at
`https://github.com/cloud-itonami/cloud-itonami-isic-2829`, following
the verified fresh-scaffold protocol established across this fleet
(closest architectural sibling: `cloud-itonami-isic-2823`, machinery
for metallurgy). This is part of the final batch of Wave 3 — after
this batch, only the two deliberately-scoped-out sensitive classes
(2520 weapons/ammunition, 3040 military fighting vehicles) remain
unimplemented in the entire wave.

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carried `{:id "2829", :name "Manufacture of other special-purpose
machinery", ...}` at `:maturity :spec` with a stale placeholder
`:repo`/`:business-id` (`https://github.com/gftdcojp/cloud-itonami-C2829`
/ `cloud-itonami-C2829`) predating the current `cloud-itonami-isic-<id>`
naming convention. The live `:name` was independently re-verified
against a fresh clone before any work began, per this fleet's ID/name-
mismatch caution, and confirmed to match "Manufacture of other
special-purpose machinery" exactly. This work implements the
blueprint as a real, tested `cloud-itonami-isic-<id>` actor repo and
corrects the registry entry's `:repo`/`:business-id` fields to match.

ISIC 2829 is the RESIDUAL n.e.c. ("not elsewhere classified")
special-purpose-machinery category — it covers a heterogeneous set of
machine types (e.g. printing machinery, plastics/rubber-working
machinery, mining/construction-adjacent specialty machines) rather
than one coherent plant shape, and is distinct from siblings 2812
(fluid power equipment), 2815 (ovens/furnaces and furnace burners),
2819 (other general-purpose machinery), and 2823 (machinery for
metallurgy), all already `:implemented` in this fleet. Rather than
model the whole heterogeneous n.e.c. bucket, this build picks ONE
concrete illustrative product line, documented plainly in the actor
repo's own README: **industrial printing-press manufacturing** —
offset presses, flexographic presses, gravure presses, digital-inkjet
presses, screen-printing presses, and die-cutting presses. The closest
sibling already in this fleet is `cloud-itonami-isic-2823`
(Manufacture of machinery for metallurgy) — both are back-office
coordination actors for a fixed manufacturing plant with QC-tested,
discrete-unit finished-goods output and a real physical/consumer
safety dimension, sharing the same four-op shape and the same
two-entity verified/registered gate structure, but with distinct
hazard profiles: 2823's is heavy static/dynamic-load and moving-part
crush/pinch/structural-failure hazard from rolling-mill/casting/
forging/extrusion machinery, while 2829's illustrative printing-press
line is nip-point/pinch/crush hazard at impression cylinders, feeder/
delivery sections and web-transport paths.

The full architecture, module set, governor rules, tests and
verification runs are documented in the actor repo's own
`docs/adr/0001-architecture.md`
(https://github.com/cloud-itonami/cloud-itonami-isic-2829/blob/main/docs/adr/0001-architecture.md).
This superproject ADR records the coverage decision and the exact
commands/outputs used to verify it landed cleanly, per this
superproject's own verification-discipline conventions.

## Decision

Scaffold `cloud-itonami-isic-2829` mirroring the `cloud-itonami-isic-2823`
(machinery for metallurgy) architecture closely — same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/
`:coordinate-shipment`, all `:effect :propose` only), same langgraph-clj
StateGraph + independent Governor + Phase 0->3 rollout pattern, same
two-entity (equipment + batch) independently-verified/registered gate
structure — but retargeted to the printing-press plant's own hazard
profile and quality vocabulary:

- Namespace prefix `printpressmfg` (grep-verified UNIQUE fleet-wide:
  `gh search code "printpressmfg" --owner cloud-itonami`, zero hits
  before this repo was created).
- Governor keyword `:special-purpose-machinery-plant-operations-governor`
  (grep-verified UNIQUE fleet-wide: `gh search code
  "special-purpose-machinery-plant-operations-governor" --owner
  cloud-itonami`, zero hits before this repo was created).
- Product-type closed set: `:offset-press`/`:flexographic-press`/
  `:gravure-press`/`:digital-inkjet-press`/`:screen-printing-press`/
  `:die-cutting-press`.
- Quality-plausibility field replacing 2823's `:load-test-tonnes`:
  `:registration-accuracy-mm` (0.0 to 5.0mm, proof-run color-
  registration misalignment) — independently re-verified by the
  governor against a physically plausible range informed by real
  industrial printing-press commissioning practice (production-quality
  color registration is typically held well under 0.1mm; even a badly
  out-of-tolerance press being flagged for rework rarely reports
  misalignment beyond a few millimetres), never taken on the advisor's
  self-report. `:defect-rate-percent` (0.0-100.0%) is retained
  unchanged from 2823's own shape.
- Equipment kinds `:fabrication-line`/`:press-commissioning-bench`
  replacing 2823's `:fabrication-line`/`:assembly-test-bench`.
- Permanent equipment-actuation block guards fabrication/assembly-
  line equipment (`:actuate-equipment? true` on `:schedule-
  maintenance`), matching 2823's own permanent actuation-block
  pattern.
- Permanent machinery-safety-certification-authority block
  (`:issue-certification? true`, any op) retargeted to the printing-
  press-specific safety-conformity regime: EU Machinery Directive
  2006/42/EC CE marking (shared with 2823) and ANSI B65.1 (Safety
  Standard for Printing Press Systems) in place of 2823's ANSI B11.19
  (machine-safeguarding).
- Safety-concern flagging covers equipment-safety/quality-defect
  concerns (e.g. impression-cylinder nip-point hazard); ALWAYS
  escalates, matching every sibling actor's own safety-concern
  posture.

Full rule-by-rule detail (twelve concrete governor checks elaborating
four HARD invariants) lives in the actor repo's own
`docs/adr/0001-architecture.md` and `src/printpressmfg/governor.cljc`
docstring — not duplicated here.

## Verification

Actor repo, built from
`/private/tmp/.../scratchpad/2829work/build/orgs/cloud-itonami/cloud-itonami-isic-2829`
(a scratch dir mirroring the workspace's `orgs/cloud-itonami/<repo>`
layout, sibling to freshly-cloned `orgs/kotoba-lang/{langgraph,langchain}`,
so `deps.edn`'s `:local/root` paths resolve exactly as they would in
the monorepo checkout):

```
$ clojure -M:test
Ran 70 tests containing 201 assertions.
0 failures, 0 errors.

$ clojure -M:lint
linting took 506ms, errors: 0, warnings: 0

$ clojure -M:dev:run
(full demo narrative: happy-path commit/escalate/approve for all four
ops, then all eleven HARD-hold scenarios exercised directly -- not-
propose-effect, unknown-op, equipment-not-verified, batch-not-
verified, shipment-quantity-exceeded, equipment-actuate-blocked,
certification-authority-blocked, already-scheduled, invalid-product-
type, invalid-registration-accuracy, invalid-defect-rate -- no
exceptions)
```

Pushed to `main` at commit `0e4ca94b361230cf74168e8e07e8812e4b2ddca7`
(`cloud-itonami/cloud-itonami-isic-2829`), independently confirmed via
`gh api repos/cloud-itonami/cloud-itonami-isic-2829/commits/main`.
Re-verified from an INDEPENDENT fresh clone (same sibling layout) --
see the task's final report for the exact re-verification
`clojure -M:test` output.

`kotoba-lang/industry` registry: `"2829"` entry promoted `:spec` ->
`:implemented`, `:repo`/`:business-id` corrected from the stale
`gftdcojp/cloud-itonami-C2829` placeholder to
`https://github.com/cloud-itonami/cloud-itonami-isic-2829` /
`cloud-itonami-isic-2829`, exact in-place edit of only the `"2829"`
entry's block (no other entry touched). `test/kotoba/industry_test.clj`
maturity-count assertion bumped to the freshly recomputed true
`:implemented` count (via `kotoba.industry/maturity-summary`, not
`grep -c`) at edit time — see the registry repo's own commit/PR for
the exact before/after counts and `clojure -M:test` output landed via
`gh api repos/kotoba-lang/industry/merges` (or Contents-API fallback).

## Consequences

(+) ISIC 2829 now has a documented, governed, auditable plant-
operations-coordination actor, following this fleet's established
pattern, cleanly scoped to ONE concrete illustrative product line
(industrial printing-press manufacturing) within an otherwise
heterogeneous n.e.c. residual category.

(+) The "coordination, not control" boundary is a hard, permanent,
unconditional governor block plus a structural phase-table absence for
`:schedule-maintenance` -- two independent layers agree, not just
asserted in prose.

(+) Safety-concern escalation (equipment-safety/quality-defect,
including impression-cylinder nip-point hazard) is a core design
invariant; the actor structurally cannot auto-decide a safety concern
at any confidence or phase.

(+) The registry's stale pre-`cloud-itonami-isic-<id>`-convention
placeholder repo/business-id for this entry is corrected as part of
this promotion.

(+) Portable `.cljc`, zero JVM-only constructs, clj-kondo 0 errors / 0
warnings.

(-) Still a simulation/proposal layer -- single `MemStore` backend,
mock advisor, no real plant-management database integration. Same
limitation as every sibling actor in this fleet at this stage.

(-) This build models only ONE illustrative product line (printing-
press manufacturing) out of ISIC 2829's genuinely heterogeneous n.e.c.
scope (plastics/rubber-working machinery, mining/construction-adjacent
specialty machines are NOT modeled) -- documented plainly as a scope
limitation, not silently generalized.
