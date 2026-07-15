# ADR-2607921091: cloud-itonami ISIC 3091 coverage — MotoAdvisor ⊣ Motorcycle Plant Operations Governor

## Status

Accepted. `cloud-itonami-isic-3091` implemented as a fresh
from-scratch actor repository under the `cloud-itonami` org and
promoted from `:spec` to `:implemented` in the `kotoba-lang/industry`
registry, following the verified fresh-scaffold + mandatory-
verification protocol used across this fleet's careful,
smaller-batch rollout (after the prior 18-agent haiku batch's 61%
defect rate, 84+ consecutive agents on the stricter protocol —
capable model + mandatory `clojure -M:test` verification — have all
succeeded; this is the next one).

## Context

`kotoba-lang/industry`'s registry entry `{:id "3091" ...}` carries
`:name "Manufacture of motorcycles"` (ISIC Rev.5 3091) — verified
against a fresh clone of `kotoba-lang/industry` before any
implementation work began, per this fleet's mandatory ID/name-match
discipline (multiple prior agents in this fleet have mislabeled their
assigned ISIC class; this run confirmed the live `:name` matches the
assigned scope before writing anything). `gh api repos/cloud-itonami/
cloud-itonami-isic-3091` 404'd before this repo was created —
confirmed fresh scaffold, no prior repo to adopt or merge into. The
registry's pre-existing `:repo` value was the legacy placeholder
`https://github.com/gftdcojp/cloud-itonami-C3091` (never created —
the standard "spec, not yet implemented" placeholder pattern this
fleet uses fleet-wide), not a real repo to migrate from.

The reference architecture mirrored closely is `cloud-itonami-isic-3092`
(Manufacture of bicycles and invalid carriages, 77 tests / 215
assertions in that repo) — the closest architectural analog: both are
back-office coordination actors for a fixed frame-welding/assembly/
test-bench-inspection plant with a real physical safety dimension,
sharing the same four-op shape (`:log-production-batch`/`:schedule-
maintenance`/`:flag-safety-concern`/`:coordinate-shipment`) and the
same two-entity verified/registered gate structure. 3091's own hazard
AND certification-regime profile is distinct from 3092's: engine-
assembly and emissions-dyno-test-bench inspection (rather than 3092's
structural/brake-only test bench), a `:product-category` closed set
spanning standard/sport/cruiser/touring/adventure/naked/off-road
motorcycles plus scooters/mopeds/electric motorcycles, an
`:engine-displacement-cc` plausibility check (0-3000, electric
motorcycles legitimately report 0) in place of 3092's
`:weight-capacity-kg`, and a permanent ECE R78 (motorcycle brake
system) / ECE R40 (motorcycle emissions) type-approval-certification-
authority block in place of 3092's ISO 4210/ISO 7176 block. The full
domain adaptation (equipment/product vocabulary, hazard language) is
recorded in the child repo's own `docs/adr/0001-architecture.md`.

## Decision

### Decision 1: Scope — a motorcycle-plant PLANT OPERATIONS COORDINATION actor, not welding/assembly-line control authority, NOT a type-approval authority

`cloud-itonami-isic-3091` is `MotoAdvisor ⊣ Motorcycle Plant
Operations Governor`, a langgraph-clj StateGraph with an append-only
audit ledger (same pattern as every actor in this fleet — see skill
`build-actor`). Closed allowlist of four ops, all `:effect :propose`
only:
- `:log-production-batch` — frame-weld/engine-assembly/final-assembly batch, output-quality/test-result data logging
- `:schedule-maintenance` — welding/assembly/test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a frame-weld-defect/brake-safety/emissions-compliance concern, ALWAYS escalates
- `:coordinate-shipment` — outbound product shipment coordination

### Decision 2: HARD invariants (no override)

1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action.
2. `:effect` must be `:propose` only (never a direct-write bypass).
3. Any proposal touching welding/assembly/test-bench-equipment control or a type-approval/safety-certification-authority decision is a hard, permanent block.
4. The op allowlist is closed — the four ops above only.

Elaborated into twelve concrete governor checks (propose-only effect,
closed op allowlist, closed proposal-effect allowlist, permanent
equipment-actuate block, permanent type-approval-authority block,
independent equipment verification before maintenance scheduling,
double-schedule guard, independent batch verification before shipment
coordination, independent shipment-quantity recompute, product-
category validation, engine-displacement plausibility validation,
weld-defect-rate plausibility validation) — see child repo README/ADR
for the full enumeration.

### Decision 3: ESCALATE — always human sign-off

`:flag-safety-concern` ALWAYS escalates to a human plant supervisor,
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

(+) ISIC 3091 (Manufacture of motorcycles) now has a governed,
auditable, forkable OSS plant-operations coordination actor with an
explicit, structural, permanently unconditional block against ever
self-issuing an ECE R78/ECE R40 motorcycle type-approval
certification — a regulatory-authority boundary distinct from the
prior bicycle/wheelchair actor's ISO 4210/ISO 7176 boundary.

(+) `kotoba-lang/industry` registry entry `3091` promoted from
`:spec` to `:implemented`, with `:repo`/`:business-id` corrected from
the legacy `gftdcojp/cloud-itonami-C3091` placeholder naming to the
`cloud-itonami/cloud-itonami-isic-3091` convention used by every other
`:implemented` entry in this fleet (e.g. 3092, 2211).

(-) Still a simulation/proposal layer — no integration with real
plant-management databases (equipment telemetry, batch tracking,
freight dispatch, or an authoritative type-approval-body database).
Equipment actuation and welding/assembly-line operation remain
human-controlled via external channels, and motorcycle type-approval
remains a regulatory-authority process entirely outside this actor,
as intended.

## Verification

- `cloud-itonami-isic-3091` repo: `https://github.com/cloud-itonami/cloud-itonami-isic-3091`
- `clojure -M:test` (fresh clone, before merge to `kotoba-lang/industry`):
  `Ran 77 tests containing 215 assertions. 0 failures, 0 errors.`
- `clojure -M:lint`: `linting took 595ms, errors: 0, warnings: 0`
- `clojure -M:dev:run` demo: exercises proposal submission, escalation,
  and every HARD-hold scenario directly (not-propose-effect,
  unknown-op, equipment-not-verified, batch-not-verified,
  shipment-quantity-exceeded, equipment-actuate-blocked,
  certification-authority-blocked, already-scheduled,
  invalid-product-category, invalid-engine-displacement,
  invalid-defect-rate).
- `kotoba-lang/industry` registry `3091` entry edited in place
  (exact-text, not a parse/reserialize) from `:maturity :spec` to
  `:maturity :implemented`, `:repo`/`:business-id` corrected to the
  `cloud-itonami-isic-3091` convention, ADR reference added.
- `industry_test.clj`'s `:implemented` count assertion bumped to the
  true recomputed count (via `kotoba.industry/maturity-summary`) and
  the full `industry` test suite re-run green before push.
- Post-merge re-verification: fresh re-clone of both
  `cloud-itonami-isic-3091` and `kotoba-lang/industry` (+
  `kotoba-lang/technology` sibling), `clojure -M:test` re-run in both,
  and the `3091` registry entry specifically re-checked as
  `:implemented` (not silently reverted by a concurrent broad rewrite
  from another session) — raw output recorded in this ADR's commit
  trail and the task's final report.
