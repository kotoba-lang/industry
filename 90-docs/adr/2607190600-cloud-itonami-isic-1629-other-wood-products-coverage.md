# ADR-2607190600: cloud-itonami ISIC 1629 (Manufacture of other products of wood; manufacture of articles of cork, straw and plaiting materials) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607181000 (cloud-itonami-isic-1621 Manufacture of veneer sheets and wood-based panels coverage); ADR-2607181100 (cloud-itonami-isic-1622 Manufacture of builders' carpentry and joinery coverage); ADR-2607190000 (cloud-itonami-isic-1623 Manufacture of wooden containers coverage)

## Status

Accepted. `cloud-itonami-isic-1629` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by 55+ prior agents in
this fleet's stricter-protocol batch (capable model + mandatory
verification, after an earlier 18-agent haiku batch had a 61% defect
rate).

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carried ISIC Rev.5 class `"1629"` (`:name "Manufacture of other
products of wood"`) at `:maturity :spec` with a placeholder `:repo`
(`https://github.com/gftdcojp/cloud-itonami-C1629`, never created)
and `:business-id "cloud-itonami-C1629"`. Verified via `gh api
repos/cloud-itonami/cloud-itonami-isic-1629` and `gh api
repos/gftdcojp/cloud-itonami-C1629` (both 404) before any work began:
no prior implementation or reverted attempt existed at either the old
placeholder location or the fleet's live naming convention.

Per the fleet's established convention (confirmed against the
`kotoba-lang/industry` registry's own `"1621"`/`"1622"`/`"1623"`
entries, all three promoted `:spec -> :implemented` on 2026-07-15),
the live repo/business-id convention is
`cloud-itonami/cloud-itonami-isic-<id>` / `cloud-itonami-isic-<id>`,
not the old `gftdcojp/cloud-itonami-C<id>` placeholder shape.

## Decision

Scaffolded and implemented `cloud-itonami/cloud-itonami-isic-1629`
(https://github.com/cloud-itonami/cloud-itonami-isic-1629) as a
fresh, from-scratch actor repo: WoodCorkStrawAdvisor ⊣ Wood, Cork &
Straw Products Shop Plant Operations Governor, langgraph-clj
StateGraph, append-only audit ledger, mirroring
`cloud-itonami-isic-1621` (Manufacture of veneer sheets and
wood-based panels), `cloud-itonami-isic-1622` (Manufacture of
builders' carpentry and joinery), and `cloud-itonami-isic-1623`
(Manufacture of wooden containers)'s verified module shape
module-for-module, with one structural difference this ADR records:

- ISIC 1629 is explicitly a **residual/miscellaneous class**
  ("manufacture of other products of wood; manufacture of articles of
  cork, straw and plaiting materials") spanning THREE distinct
  product families made on THREE distinct equipment kinds, rather
  than the single product family / single equipment kind each of
  1621/1622/1623 centers on: wooden tools/handles turned on a
  handle-turning lathe (**cutting**), cork stoppers formed on a
  cork-stopper molding press (**molding**), and wicker/basketry items
  formed on a wicker-weaving loom (**weaving**).
- product-spec/unit-count/output-quality production-batch fields
  (same shape as all three analogs) with a wood/cork/straw-specific
  closed product-spec set: `:handle-standard` / `:handle-heavy-duty`
  / `:cork-stopper-standard-bore` / `:cork-stopper-wide-bore` /
  `:wicker-small-weave` / `:wicker-large-weave`.
- a cutting/molding-line-finalize permanent block in place of 1621's
  press-cycle-finalize, 1622's cutting-line-finalize, or 1623's
  cutting/assembly-line-finalize.
- safety-concern flagging scoped to plain materials-safety/
  equipment-safety concerns, WITHOUT 1623's domain-specific ISPM-15
  heat-treatment-compliance dimension -- the residual product range
  covered here (tool handles, cork stoppers, basketry) is not itself
  a wood-packaging-material class subject to international-freight
  phytosanitary heat-treatment marking, so no equivalent compliance
  concept was invented. Still routed through the SAME
  always-escalates, never-auto-committed path every sibling actor's
  own safety-concern flag uses -- no new escalation tier, no
  confidence threshold below escalation.

Full module set: `deps.edn`, `blueprint.edn`,
`src/woodcork/{advisor,governor,operation,phase,registry,sim,
store}.cljc`, `test/woodcork/{governor_contract,operation,phase,
registry,store_contract}_test.cljc`, `docs/adr/0001-architecture.md`,
`README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
`CONTRIBUTING.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later).

All source is `.cljc` (cljs-first / portable — no JVM-only interop),
matching this workspace's runtime-priority rule
(`kotoba wasm runtime` > `clojurewasm` > `ClojureScript` > `nbb` >
JVM/bb).

Four ops (`:log-production-batch` / `:schedule-maintenance` /
`:flag-safety-concern` / `:coordinate-shipment`), all `:effect
:propose` only. Ten concrete governor checks elaborate four HARD
invariants: propose-only effect, closed op allowlist, closed
proposal-effect allowlist (no direct cutting/molding-line-equipment
control), permanent cutting/molding-line-finalize block, independent
equipment verification/registration before maintenance scheduling,
independent batch verification/registration before shipment
coordination, independent shipment-unit-count recompute against the
batch's own logged unit count, double-schedule guard, product-spec
validation, and output-quality plausibility validation.

`:flag-safety-concern` ALWAYS escalates to a human plant supervisor
(never auto-committed, no confidence threshold or phase override);
`:log-production-batch` is the only op eligible to auto-commit, and
only at phase 3 when governor-clean -- the same phase-3 `:auto` set
shape (`#{:log-production-batch}`) every prior sibling actor
establishes.

The `:itonami.blueprint/governor` keyword,
`:wood-cork-straw-products-shop-plant-operations-governor`, is
grep-verified unique fleet-wide (zero hits via `gh search code
"wood-cork-straw-products-shop-plant-operations-governor" --owner
cloud-itonami` before this repo was created).

## Verification

- `clojure -M:test` at commit `913df5b17cd461c47a73ee64bbf90003f84b0d10`
  (initial commit on `main`): `Ran 71 tests containing 195 assertions.
  0 failures, 0 errors.`
- `clojure -M:lint`: `linting took 447ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` demo runs end-to-end with no exceptions,
  exercising every op's happy path (with human approval where the
  governor/phase gate escalates) and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-unit-count-exceeded,
  cutting-molding-line-finalize-blocked, already-scheduled,
  invalid-product-spec, invalid-output-quality).
- Pushed to `main` at `cloud-itonami/cloud-itonami-isic-1629`;
  confirmed landed via `gh api repos/cloud-itonami/cloud-itonami-isic-1629/commits/main`
  (`.sha` == the local commit SHA above).
- `kotoba-lang/industry` registry `"1629"` entry updated in place
  (exact-text edit of the existing `{:id "1629" ...}` block only):
  `:maturity :spec -> :implemented`, `:repo
  "https://github.com/cloud-itonami/cloud-itonami-isic-1629"`,
  `:business-id "cloud-itonami-isic-1629"`, landed on
  `kotoba-lang/industry`'s `main` via server-side merge (see registry
  entry's own comment for the merge SHA). `industry_test.clj`'s
  implemented-count assertion recomputed from the live registry via
  `kotoba.industry/maturity-summary` (not `grep -c`) and bumped to
  match; full `kotoba-lang/industry` suite re-run green after the
  bump.
- Post-merge re-verification: re-fetched `kotoba-lang/industry`
  `main`, re-cloned into a fresh temp dir (with a fresh
  `../technology` sibling clone), re-ran `clojure -M:test` — green.
  `grep -c "â" resources/kotoba/industry/registry.edn` == 0 (no
  mojibake introduced by the in-place edit).

## Consequences

(+) ISIC 1629 (miscellaneous wood/cork/straw products: wooden tools/
handles, cork stoppers, wicker/basketry) now has a governed, auditable
plant-operations-coordination blueprint, matching the fleet's
established governed-actor pattern.

(+) `kotoba-lang/industry`'s registry accurately reflects a real,
tested implementation instead of a `:spec`-only placeholder pointing
at a repo that was never created.

(+) The residual/multi-product-family nature of ISIC 1629 is captured
explicitly in the module docstrings/ADR context (three distinct
equipment kinds -- cutting, molding, weaving -- gated by the SAME
governor invariants), so a future maintainer extending this actor (or
a sibling wood-products actor) has a concrete precedent for a
multi-product-family residual class riding the same two-entity
verified/registered gate rather than inventing per-product-family
governor branches.

(-) Still a simulation/proposal layer, not a real plant-operations
control system, matching every sibling actor in this fleet.

(-) No integration with real shop-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## References

- `cloud-itonami/cloud-itonami-isic-1629` (this ADR's subject).
- `cloud-itonami/cloud-itonami-isic-1621` (Manufacture of veneer
  sheets and wood-based panels), `cloud-itonami/
  cloud-itonami-isic-1622` (Manufacture of builders' carpentry and
  joinery), and `cloud-itonami/cloud-itonami-isic-1623` (Manufacture
  of wooden containers) — closest domain analogs, mirrored
  module-for-module.
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"1629"` entry.
- This repo's own `docs/adr/0001-architecture.md` (architecture
  decision record, actor-repo-local).
