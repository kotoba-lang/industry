# ADR-2607210100: cloud-itonami ISIC 2593 coverage — HardwareShopAdvisor ⊣ Hardware Shop Plant Operations Governor

## Status

Accepted. `cloud-itonami-isic-2593` implemented as a fresh from-scratch
actor repository under the `cloud-itonami` org and promoted from
`:spec` to `:implemented` in the `kotoba-lang/industry` registry,
following the verified fresh-scaffold + mandatory-verification
protocol used across this fleet's careful, smaller-batch rollout
(after the prior 18-agent haiku batch's 61% defect rate, 78+
consecutive agents on the stricter protocol — capable model +
mandatory `clojure -M:test` verification — have all succeeded; this is
the next one).

## Context

`kotoba-lang/industry`'s registry entry `{:id "2593" ...}` carries
`:name "Manufacture of cutlery, hand tools and general hardware"`
(ISIC Rev.5 2593) — verified against a fresh clone of
`kotoba-lang/industry` before any implementation work began, per this
fleet's mandatory ID/name-match discipline (multiple prior agents in
this fleet have mislabeled their assigned ISIC class; this run
confirmed the live `:name` matches the assigned scope before writing
anything). `gh api repos/cloud-itonami/cloud-itonami-isic-2593` and
`gh api repos/gftdcojp/cloud-itonami-C2593` (the registry's
pre-existing placeholder `:repo` value) both 404'd before this repo
was created — confirmed fresh scaffold, no prior repo to adopt or
merge into.

ISIC 2593 is a specific product-family class within ISIC group 259,
distinct from two other classes in the same group: `cloud-itonami-
isic-2591` (Forging, pressing, stamping and roll-forming of metal —
the primary heavy metal-forming process, still `:spec` in the registry
at the time this build began) and `cloud-itonami-isic-2592` (Treatment
and coating of metals — a distinct surface-finishing process, still
`:spec`). It is also distinct from `cloud-itonami-isic-2599`
(Manufacture of other fabricated metal products n.e.c. — the residual
stamping/pressing/wire-forming shop, `:implemented` per ADR-2607210000).
This actor covers a shop that forges, grinds, heat-treats and finishes
stock into cutlery (knives/forks/spoons), hand tools (hammers/
wrenches/screwdrivers/pliers) and general hardware (locks, hinges,
fasteners, garden tools).

The reference architecture mirrored closely is `cloud-itonami-isic-
2599` (Manufacture of other fabricated metal products n.e.c., 71
tests / 195 assertions in that repo at the time this build began) —
the closest architectural analog: both are back-office coordination
actors for a fixed processing PLANT with heavy manufacturing equipment
and a real physical safety dimension, sharing the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure. 2593's own hazard profile (sharp-edge
laceration risk from freshly forged/ground blades and edges,
forging-hammer pinch/crush hazard, grinding-wheel/abrasive-dust
exposure, heat-treatment-furnace burn/radiant-heat exposure) is
distinct from 2599's sheet-metal stamping/pressing/wire-forming
hazards — the full domain adaptation (equipment/product vocabulary,
permanent-block field name, hazard language) is recorded in the child
repo's own `docs/adr/0001-architecture.md`.

## Decision

### Decision 1: Scope — a cutlery/hand-tool/hardware-shop PLANT OPERATIONS COORDINATION actor, not forging/grinding-line control authority

`cloud-itonami-isic-2593` is `HardwareShopAdvisor ⊣ Hardware Shop
Plant Operations Governor`, a langgraph-clj StateGraph with an
append-only audit ledger (same pattern as every actor in this fleet —
see skill `build-actor`). Closed allowlist of four ops, all `:effect
:propose` only:
- `:log-production-batch` — forging/grinding/heat-treatment batch, output-quality data logging
- `:schedule-maintenance` — forging/grinding-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a materials-safety/equipment-safety (sharp-edge, heat-treatment furnace) concern, ALWAYS escalates
- `:coordinate-shipment` — outbound product shipment coordination

### Decision 2: HARD invariants (no override)

1. Shop/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action.
2. `:effect` must be `:propose` only (never a direct-write bypass).
3. Any proposal touching forging/grinding-line-equipment control is a hard, permanent block (including a dedicated `:actuate-forge-grind-line? true` permanent-block field on `:schedule-maintenance` proposals).
4. The op allowlist is closed — the four ops above only.

Elaborated into ten concrete governor checks (propose-only effect,
closed op allowlist, closed proposal-effect allowlist, permanent
forge/grind-line-actuate block, independent equipment verification
before maintenance scheduling, double-schedule guard, independent
batch verification before shipment coordination, independent
shipment-weight recompute, product-category validation, defect-rate
plausibility validation) — see child repo README/ADR for the full
enumeration.

### Decision 3: ESCALATE — always human sign-off

`:flag-safety-concern` ALWAYS escalates to a human shop supervisor,
regardless of confidence — never auto-decided, no threshold below
escalation. Low-confidence proposals also escalate (SOFT, human may
approve). `:log-production-batch` is the only op eligible to
auto-commit, and only at phase 3 when governor-clean (no physical/
financial risk in a production-batch data-logging patch).

### Decision 4: cljs-first / portable — no JVM interop

All source is `.cljc`, invoked exclusively via `langgraph.graph/run*`
(not `.invoke`, which is not cljs-portable), per this repository's
mandatory `.cljc`/`.kotoba` runtime priority (kotoba wasm →
clojurewasm → ClojureScript → nbb, JVM/bb downgraded to compat-layer
status). No JVM-only interop anywhere in `src/` or `test/`.

## Consequences

(+) ISIC 2593 (Manufacture of cutlery, hand tools and general
hardware) now has a governed, auditable, forkable OSS plant-operations
coordination actor, closing a gap in the ISIC 259 group alongside the
still-`:spec` 2591/2592 siblings and the already-`:implemented` 2599.

(+) `kotoba-lang/industry` registry entry `2593` promoted from `:spec`
to `:implemented`, with `:repo`/`:business-id` corrected from the
legacy `gftdcojp/cloud-itonami-C2593` placeholder naming to the
`cloud-itonami/cloud-itonami-isic-2593` convention used by every other
`:implemented` entry in this fleet (e.g. 2431, 2599).

(-) Still a simulation/proposal layer — no integration with real
plant-management databases (equipment telemetry, batch tracking,
freight dispatch). Equipment actuation and forge/grind-line operation
remain human-controlled via external channels, as intended.

## Verification

- `cloud-itonami-isic-2593` repo: `https://github.com/cloud-itonami/cloud-itonami-isic-2593`
- `clojure -M:test` (fresh scratch build, before merge to `kotoba-lang/industry`):
  `Ran 71 tests containing 195 assertions. 0 failures, 0 errors.`
- `clojure -M:lint`: `linting took 1216ms, errors: 0, warnings: 0`
- `clojure -M:dev:run` demo: exercises proposal submission, escalation,
  and every HARD-hold scenario directly (not-propose-effect,
  unknown-op, equipment-not-verified, batch-not-verified,
  shipment-weight-exceeded, forge-grind-line-actuate-blocked,
  already-scheduled, invalid-product-category, invalid-defect-rate).
- `kotoba-lang/industry` registry `2593` entry edited in place
  (exact-text, not a parse/reserialize) from `:maturity :spec` to
  `:maturity :implemented`, `:repo`/`:business-id` corrected to the
  `cloud-itonami-isic-2593` convention, ADR reference added.
- `industry_test.clj`'s `:implemented` count assertion bumped to the
  true recomputed count (via `kotoba.industry/maturity-summary`) and
  the full `industry` test suite re-run green before push.
- Post-merge re-verification: fresh re-clone of both
  `cloud-itonami-isic-2593` and `kotoba-lang/industry` (+
  `kotoba-lang/technology` sibling), `clojure -M:test` re-run in both,
  and the `2593` registry entry specifically re-checked as
  `:implemented` (not silently reverted by a concurrent broad rewrite
  from another session) — raw output recorded in this ADR's PR/commit
  trail and the task's final report.
