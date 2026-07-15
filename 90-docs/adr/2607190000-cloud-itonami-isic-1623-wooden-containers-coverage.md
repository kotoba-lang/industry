# ADR-2607190000: cloud-itonami ISIC 1623 (Manufacture of wooden containers) coverage

## Status

Accepted. `cloud-itonami-isic-1623` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by 54+ prior agents in
this fleet's stricter-protocol batch (capable model + mandatory
verification, after an earlier 18-agent haiku batch had a 61% defect
rate).

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carried ISIC Rev.5 class `"1623"` (`:name "Manufacture of wooden
containers"`) at `:maturity :spec` with a placeholder `:repo`
(`https://github.com/gftdcojp/cloud-itonami-C1623`, never created)
and `:business-id "cloud-itonami-C1623"`. Verified via `gh api
repos/cloud-itonami/cloud-itonami-isic-1623` and `gh api
repos/gftdcojp/cloud-itonami-C1623` (both 404) before any work began:
no prior implementation or reverted attempt existed at either the old
placeholder location or the fleet's live naming convention.

Per the fleet's established convention (confirmed against the
`kotoba-lang/industry` registry's own `"1621"`/`"1622"` entries, both
promoted `:spec -> :implemented` on 2026-07-15), the live repo/
business-id convention is `cloud-itonami/cloud-itonami-isic-<id>` /
`cloud-itonami-isic-<id>`, not the old `gftdcojp/cloud-itonami-C<id>`
placeholder shape.

## Decision

Scaffolded and implemented `cloud-itonami/cloud-itonami-isic-1623`
(https://github.com/cloud-itonami/cloud-itonami-isic-1623) as a
fresh, from-scratch actor repo: WoodenContainerAdvisor ⊣ Wooden
Container Shop Plant Operations Governor, langgraph-clj StateGraph,
append-only audit ledger, mirroring `cloud-itonami-isic-1621`
(Manufacture of veneer sheets and wood-based panels) and
`cloud-itonami-isic-1622` (Manufacture of builders' carpentry and
joinery)'s verified module shape module-for-module:

- crate/pallet-nailing-machine and stave-jointer (cooperage) equipment
  in place of veneer-lathe/hot-press/glue-spreader (1621) or
  panel-saw/CNC-router/tenoning-machine/edge-bander/finishing-line
  (1622) equipment.
- dimensional-spec/unit-count/output-quality production-batch fields
  (same shape as both analogs) with a wooden-container-specific closed
  dimensional-spec set: `:crate-standard` / `:crate-heavy-duty` /
  `:pallet-block-style` / `:pallet-stringer-style` /
  `:barrel-tight-cooperage` / `:barrel-slack-cooperage`.
- a cutting/assembly-line-finalize permanent block in place of
  1621's press-cycle-finalize or 1622's cutting-line-finalize.
- safety-concern flagging that covers materials-safety/equipment-
  safety/**ISPM-15 heat-treatment-compliance** -- a domain-specific
  addition neither 1621 nor 1622 has, reflecting that wooden shipping
  containers (crates, pallets, barrels) crossing international
  borders are subject to the International Standards for
  Phytosanitary Measures No. 15 heat-treatment/marking requirement.
  This is still routed through the SAME always-escalates,
  never-auto-committed path every sibling actor's own safety-concern
  flag uses -- no new escalation tier, no confidence threshold below
  escalation.

Full module set: `deps.edn`, `blueprint.edn`,
`src/woodcontainer/{advisor,governor,operation,phase,registry,sim,
store}.cljc`, `test/woodcontainer/{governor_contract,operation,phase,
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
proposal-effect allowlist (no direct cutting/assembly-line-equipment
control), permanent cutting/assembly-line-finalize block, independent
equipment verification/registration before maintenance scheduling,
independent batch verification/registration before shipment
coordination, independent shipment-unit-count recompute against the
batch's own logged unit count, double-schedule guard,
dimensional-spec validation, and output-quality plausibility
validation.

`:flag-safety-concern` ALWAYS escalates to a human plant supervisor
(never auto-committed, no confidence threshold or phase override);
`:log-production-batch` is the only op eligible to auto-commit, and
only at phase 3 when governor-clean -- the same phase-3 `:auto` set
shape (`#{:log-production-batch}`) every prior sibling actor
establishes.

The `:itonami.blueprint/governor` keyword,
`:wooden-container-shop-plant-operations-governor`, is grep-verified
unique fleet-wide (zero hits via `gh search code
"wooden-container-shop-plant-operations-governor" --owner
cloud-itonami` before this repo was created).

## Verification

- `clojure -M:test` at commit `9616a36f5a2fff6bc4116a633c20521f894bafa9`
  (initial commit on `main`): `Ran 71 tests containing 195 assertions.
  0 failures, 0 errors.`
- `clojure -M:lint`: `linting took 374ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` demo runs end-to-end with no exceptions,
  exercising every op's happy path (with human approval where the
  governor/phase gate escalates) and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-unit-count-exceeded,
  cutting-assembly-line-finalize-blocked, already-scheduled,
  invalid-dimensional-spec, invalid-output-quality).
- Pushed to `main` at `cloud-itonami/cloud-itonami-isic-1623`;
  confirmed landed via `gh api repos/cloud-itonami/cloud-itonami-isic-1623/commits/main`
  (`.sha` == the local commit SHA above).
- `kotoba-lang/industry` registry `"1623"` entry updated in place
  (exact-text edit of the existing `{:id "1623" ...}` block only):
  `:maturity :spec -> :implemented`, `:repo
  "https://github.com/cloud-itonami/cloud-itonami-isic-1623"`,
  `:business-id "cloud-itonami-isic-1623"`, landed on
  `kotoba-lang/industry`'s `main` via server-side merge (see registry
  commit / PR referenced in the registry entry's own comment for the
  merge SHA). `industry_test.clj`'s implemented-count assertion
  recomputed from the live registry via
  `kotoba.industry/maturity-summary` (not `grep -c`) and bumped to
  match; full `kotoba-lang/industry` suite re-run green after the
  bump.
- Post-merge re-verification: re-fetched `kotoba-lang/industry`
  `main`, re-cloned into a fresh temp dir (with a fresh
  `../technology` sibling clone), re-ran `clojure -M:test` — green.
  `grep -c "â" resources/kotoba/industry/registry.edn` == 0 (no
  mojibake introduced by the in-place edit).

## Consequences

(+) ISIC 1623 (wooden containers: crates, pallets, barrels/cooperage)
now has a governed, auditable plant-operations-coordination
blueprint, matching the fleet's established governed-actor pattern.

(+) `kotoba-lang/industry`'s registry accurately reflects a real,
tested implementation instead of a `:spec`-only placeholder pointing
at a repo that was never created.

(+) The ISPM-15 heat-treatment-compliance dimension is captured
explicitly in the safety-concern-flag docstrings/ADR context, so a
future maintainer extending this actor (or a sibling wood-products
actor) has a concrete precedent for domain-specific compliance
concerns riding the same always-escalates path rather than inventing
a new escalation tier.

(-) Still a simulation/proposal layer, not a real plant-operations
control system, matching every sibling actor in this fleet.

(-) No integration with real shop-management databases (equipment
telemetry, batch tracking, freight dispatch, actual ISPM-15
certification-body systems) — this is a standalone coordinator
blueprint.

## References

- `cloud-itonami/cloud-itonami-isic-1623` (this ADR's subject).
- `cloud-itonami/cloud-itonami-isic-1621` (Manufacture of veneer
  sheets and wood-based panels) and `cloud-itonami/
  cloud-itonami-isic-1622` (Manufacture of builders' carpentry and
  joinery) — closest domain analogs, mirrored module-for-module.
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"1623"` entry.
- This repo's own `docs/adr/0001-architecture.md` (architecture
  decision record, actor-repo-local).
