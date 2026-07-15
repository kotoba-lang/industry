# ADR-2607210000: cloud-itonami ISIC 2599 coverage — MetalFabAdvisor ⊣ Metal Fabrication Plant Operations Governor

## Status

Accepted. `cloud-itonami-isic-2599` implemented as a fresh from-scratch
actor repository under the `cloud-itonami` org and promoted from
`:spec` to `:implemented` in the `kotoba-lang/industry` registry,
following the verified fresh-scaffold + mandatory-verification
protocol used across this fleet's careful, smaller-batch rollout
(after the prior 18-agent haiku batch's 61% defect rate, 72+
consecutive agents on the stricter protocol — capable model +
mandatory `clojure -M:test` verification — have all succeeded; this is
the next one).

## Context

`kotoba-lang/industry`'s registry entry `{:id "2599" ...}` carries
`:name "Manufacture of other fabricated metal products n.e.c."` (ISIC
Rev.5 2599) — verified against a fresh clone of `kotoba-lang/industry`
before any implementation work began, per this fleet's mandatory
ID/name-match discipline (multiple prior agents in this fleet have
mislabeled their assigned ISIC class; this run confirmed the live
`:name` matches the assigned scope before writing anything).
`gh api repos/cloud-itonami/cloud-itonami-isic-2599` and
`gh api repos/gftdcojp/cloud-itonami-C2599` (the registry's
pre-existing placeholder `:repo` value) both 404'd before this repo
was created — confirmed fresh scaffold, no prior repo to adopt or
merge into.

ISIC 2599 is the RESIDUAL "other fabricated metal products n.e.c."
class within ISIC group 259, distinct from three sibling classes in
the same group (none `:implemented` in this fleet yet, per the
registry): `cloud-itonami-isic-2591` (Forging, pressing, stamping and
roll-forming of metal — the primary heavy metal-forming process),
`cloud-itonami-isic-2592` (Treatment and coating of metals — a
distinct surface-finishing process), and `cloud-itonami-isic-2593`
(Manufacture of cutlery, hand tools and general hardware — a distinct,
more specific product family). This actor covers a metal-fabrication
shop that stamps, presses and wire-forms sheet metal and wire stock
into finished miscellaneous metal goods (stamped/pressed sheet-metal
parts, wire products, metal household goods) not elsewhere classified
in the 259 group.

The reference architecture mirrored closely is
`cloud-itonami-isic-2431` (Casting of iron and steel, 71 tests / 195
assertions in that repo at the time this build began) — the closest
architectural analog: both are back-office coordination actors for a
fixed processing PLANT with heavy manufacturing equipment and a real
physical safety dimension, sharing the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure. 2599's own hazard profile (sharp-edge/burr
laceration risk from freshly stamped/pressed sheet metal,
stamping-press pinch-point/crush hazard, wire-forming-machine
entanglement hazard, coating/lubricant/degreasing chemical exposure)
is distinct from 2431's molten-metal handling — the full domain
adaptation (equipment/product vocabulary, permanent-block field name,
hazard language) is recorded in the child repo's own
`docs/adr/0001-architecture.md`.

## Decision

### Decision 1: Scope — a metal-fabrication-shop PLANT OPERATIONS COORDINATION actor, not stamping/pressing-line control authority

`cloud-itonami-isic-2599` is `MetalFabAdvisor ⊣ Metal Fabrication Plant
Operations Governor`, a langgraph-clj StateGraph with an append-only
audit ledger (same pattern as every actor in this fleet — see skill
`build-actor`). Closed allowlist of four ops, all `:effect :propose`
only:
- `:log-production-batch` — stamping/pressing/wire-forming batch, output-quality data logging
- `:schedule-maintenance` — stamping/pressing-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a materials-safety/equipment-safety concern, ALWAYS escalates
- `:coordinate-shipment` — outbound product shipment coordination

### Decision 2: HARD invariants (no override)

1. Shop/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action.
2. `:effect` must be `:propose` only (never a direct-write bypass).
3. Any proposal touching stamping/pressing-line-equipment control is a hard, permanent block (including a dedicated `:actuate-press-line? true` permanent-block field on `:schedule-maintenance` proposals).
4. The op allowlist is closed — the four ops above only.

Elaborated into ten concrete governor checks (propose-only effect,
closed op allowlist, closed proposal-effect allowlist, permanent
press-line-actuate block, independent equipment verification before
maintenance scheduling, double-schedule guard, independent batch
verification before shipment coordination, independent shipment-
weight recompute, product-category validation, defect-rate
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

(+) ISIC 2599 (Manufacture of other fabricated metal products n.e.c.)
now has a governed, auditable, forkable OSS plant-operations
coordination actor, closing a gap in the ISIC 259 group alongside the
still-`:spec` 2591/2592/2593 siblings.

(+) `kotoba-lang/industry` registry entry `2599` promoted from `:spec`
to `:implemented`, with `:repo`/`:business-id` corrected from the
legacy `gftdcojp/cloud-itonami-C2599` placeholder naming to the
`cloud-itonami/cloud-itonami-isic-2599` convention used by every other
`:implemented` entry in this fleet (e.g. 2431, 2211).

(-) Still a simulation/proposal layer — no integration with real
plant-management databases (equipment telemetry, batch tracking,
freight dispatch). Equipment actuation and press-line operation remain
human-controlled via external channels, as intended.

## Verification

- `cloud-itonami-isic-2599` repo: `https://github.com/cloud-itonami/cloud-itonami-isic-2599`
- `clojure -M:test` (fresh clone, before merge to `kotoba-lang/industry`):
  `Ran 71 tests containing 195 assertions. 0 failures, 0 errors.`
- `clojure -M:lint`: `linting took 1206ms, errors: 0, warnings: 0`
- `clojure -M:dev:run` demo: exercises proposal submission, escalation,
  and every HARD-hold scenario directly (not-propose-effect,
  unknown-op, equipment-not-verified, batch-not-verified,
  shipment-weight-exceeded, press-line-actuate-blocked,
  already-scheduled, invalid-product-category, invalid-defect-rate).
- `kotoba-lang/industry` registry `2599` entry edited in place
  (exact-text, not a parse/reserialize) from `:maturity :spec` to
  `:maturity :implemented`, `:repo`/`:business-id` corrected to the
  `cloud-itonami-isic-2599` convention, ADR reference added.
- `industry_test.clj`'s `:implemented` count assertion bumped to the
  true recomputed count (via `kotoba.industry/maturity-summary`) and
  the full `industry` test suite re-run green before push.
- Post-merge re-verification: fresh re-clone of both
  `cloud-itonami-isic-2599` and `kotoba-lang/industry` (+
  `kotoba-lang/technology` sibling), `clojure -M:test` re-run in both,
  and the `2599` registry entry specifically re-checked as
  `:implemented` (not silently reverted by a concurrent broad rewrite
  from another session) — raw output recorded in this ADR's PR/commit
  trail and the task's final report.
