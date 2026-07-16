# ADR-2610000000: cloud-itonami-isic-1430 (manufacture of knitted and crocheted apparel) plant-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (ISIC reverse-toposort rollout plan),
ADR-2607011000 (actor pattern & ISIC section coverage), the sibling
`cloud-itonami-isic-1410` (wearing apparel except fur — cut-and-sew,
already `:implemented`) whose module shape this repo mirrors closely.

## Context

ISIC 1430 ("Manufacture of knitted and crocheted apparel") had no
existing implementation — `gh api repos/cloud-itonami/cloud-itonami-isic-1430`
returned 404 before this task began, confirming a fresh scaffold.

Before any work began, the live `kotoba-lang/industry` registry
(`resources/kotoba/industry/registry.edn`) was cloned fresh and the
`{:id "1430" ...}` block read directly: `:name "Manufacture of knitted
and crocheted apparel"` confirmed to match the task premise, distinct
from sibling `"1410"` ("Manufacture of wearing apparel, except fur
apparel", cut-and-sew from woven/knit fabric, already `:implemented`).
1430 covers garments knitted or crocheted directly to shape — circular-
and flat-knitting machine lines that produce shaped garment panels (or
whole garments) which are then linked together, distinct from 1410's
cut-and-sew process.

The registry's pre-existing `"1430"` entry carried stale placeholder
metadata typical of the seed data: `:maturity :spec`, `:repo
"https://github.com/gftdcojp/cloud-itonami-C1430"`, `:business-id
"cloud-itonami-C1430"` — wrong org (`gftdcojp` instead of
`cloud-itonami`) and wrong naming convention (`C1430` instead of the
fleet-standard `isic-1430`). Both were corrected in place as part of
this task's registry edit (not a separate change).

Built by reading `cloud-itonami-isic-1410` (`apparel.*`, wearing apparel
except fur) in full as the module-structure template, adapting from
cut-and-sew apparel to knitwear manufacturing (`knitwear.*` namespace;
circular/flat-knitting-line vocabulary instead of cutting/sewing-line
vocabulary). Note: 1410's own README.md/blueprint.edn still read
`:blueprint`/"no actor implementation yet" despite the repo's actual
`clojure -M:test`-passing implementation and the registry's
`:maturity :implemented` for `"1410"` — a pre-existing documentation
staleness bug in the reference repo, not reproduced here (this repo's
docs describe its actual `:implemented` status).

## Decision

Implement a complete knitwear-manufacturing plant-operations-coordination
actor (`cloud-itonami-isic-1430`), mirroring `cloud-itonami-isic-1410`'s
module shape (`knitwear.*` namespace: `advisor`, `facts`, `governor`,
`phase`, `registry`, `sim`, `store`), deliberately **not** direct
knitting-line control authority:

1. **`knitwear.governor`** — independent compliance layer with HARD
   invariants (a human approver CANNOT override, always `:holds? true`):
   - `op-not-allowed` — closed 4-op proposal allowlist enforced
     independently of the advisor's claim.
   - `effect-not-propose` — every proposal's `:effect` must be
     `:propose`; this actor never actuates directly.
   - `plant-not-verified` / `batch-not-verified` — the plant and
     production (knitting/linking) batch record behind an action must be
     independently verified/registered before the action is allowed
     (covers both direct batch-logging and shipment-coordination, the
     latter resolved through the shipment record to its underlying
     batch and that batch's plant).
   - `process-control-forbidden` — any proposal mentioning
     knitting-line-equipment-control language (gauge, tension, needle,
     carriage, cam, cylinder, feeder, take-down, stitch-length, RPM,
     etc.) is a hard, permanent block. Those decisions remain the
     exclusive authority of licensed knitting engineers/technicians.

   ESCALATE invariants (always human sign-off, not an outright block):
   - `safety-concern-escalates` — `:proposal/flag-safety-concern` ALWAYS
     escalates, regardless of confidence.
   - `escalate` — low confidence (< 0.6) OR high-stakes actuation
     (`:actuation/coordinate-shipment`).

2. **`knitwear.facts`** — per-jurisdiction compliance catalog (Vietnam,
   Bangladesh, USA) with real official spec-basis citations (labor
   codes, garment-quality/labeling standards, tariff compliance, and
   ISO 11111-1:2016 textile-machinery safety) — honest, non-exhaustive
   coverage reporting, never fabricated.

3. **`knitwear.registry`** — proposal-drafting helpers + independent
   hard-invariant/protected-operation predicates, sharing the exact same
   closed op-allowlist as the governor.

4. **`knitwear.store`** — in-memory reference store (plants, production
   batches, shipments, maintenance log) with verification-guard
   accessors, including a `shipment-batch-verified?` helper that
   correctly resolves shipment -> batch -> plant (a deliberate
   correction of a latent gap observed in 1410's reference governor,
   where its `:actuation/coordinate-shipment` plant/batch check looked
   up the shipment ID directly in the batch table instead of resolving
   through the shipment record).

5. **`knitwear.advisor`** — mock advisor drafting all four ops below,
   `:effect :propose` only.

6. **`knitwear.phase`** — langgraph-clj-style phase table
   (advisor -> governor -> decision -> hold/complete).

7. **`knitwear.sim`** — demo runner (`clojure -M:dev:run`) with a
   working `-main` exercising all four scenarios.

8. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:proposal/log-production-batch` (knitting/linking batch,
   output-quality data logging), `:proposal/schedule-maintenance`
   (knitting-line-equipment maintenance scheduling),
   `:proposal/flag-safety-concern` (equipment-safety/quality-defect
   concern, ALWAYS escalates), `:actuation/coordinate-shipment`
   (outbound shipment coordination, high-stakes actuation).

9. **Tests** — 31 tests / 107 assertions green (facts, governor, phase,
   store suites).

10. **Documentation** — README.md describing the actual `:implemented`
    actor and its governor invariants; blueprint.edn
    (`:isic-rev5 "1430"`, `:maturity :implemented`); standard OSS files
    (CONTRIBUTING, GOVERNANCE, SECURITY, CODE_OF_CONDUCT,
    AGPL-3.0-or-later LICENSE copied verbatim from 1410). All source
    `.cljc`, no JVM-only interop (`clojure.string/lower-case` used
    instead of `.toLowerCase` for cljs portability).

## Consequences

(+) Knitted/crocheted apparel manufacturing (ISIC 1430)
plant-operations-coordination is now genuinely implemented and fully
tested, distinct from and non-duplicative of sibling 1410's cut-and-sew
scope.

(+) Scope boundary (this actor coordinates plant operations/logistics/
compliance paperwork; it never operates knitting-line equipment or sets
knitting parameters) is hardcoded in the governor's
`process-control-forbidden` check and documented in README, not just
asserted in prose.

(+) Safety-concern escalation (`:proposal/flag-safety-concern` always
human) and high-stakes shipment escalation are core design invariants,
structurally separate from the hard, non-overridable invariants (closed
op-allowlist, propose-only effect, plant/batch verification, equipment-
control block).

(+) Portable `.cljc` implementation with no JVM-only constructs;
`clojure -M:lint` is 0 errors (only benign clj-kondo "unused docstring"
style warnings on `deftest` doc-strings, same as any sibling actor repo
in this fleet).

(+) Registry `"1430"` entry's stale placeholder metadata
(`gftdcojp/cloud-itonami-C1430`) corrected to the fleet-standard
`cloud-itonami/cloud-itonami-isic-1430` naming as part of the same
change that flips `:maturity` to `:implemented`.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring is phase-table scaffolding
(`knitwear.phase`), matching 1410's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-1430`: `clojure -M:test` -> "Ran 31 tests
  containing 107 assertions. 0 failures, 0 errors." (initial build,
  scratch dir, and re-verified after a fresh post-merge clone).
  `clojure -M:lint` -> 0 errors, 35 benign docstring warnings.
- Registry entry (`kotoba-lang/industry`): `"1430"` entry's `:maturity`
  `:spec` -> `:implemented`, `:repo`/`:business-id` corrected from the
  `gftdcojp/cloud-itonami-C1430` placeholder to
  `cloud-itonami/cloud-itonami-isic-1430`, ADR reference added. See the
  commit/merge SHAs and fresh-clone post-merge re-verification recorded
  in this task's final report (not duplicated here to avoid a second,
  possibly-drifting source of truth — the git history on both
  `com-junkawasaki/root` and `kotoba-lang/industry` `main` is the
  authoritative record).
