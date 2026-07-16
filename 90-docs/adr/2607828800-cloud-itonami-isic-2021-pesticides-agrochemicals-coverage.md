# ADR-2607828800: cloud-itonami ISIC 2021 coverage — PesticideAdvisor ⊣ Pesticide & Agrochemical Plant Operations Governor

## Status

Accepted. `cloud-itonami-isic-2021` implemented as a fresh from-scratch
actor repository under the `cloud-itonami` org and promoted from
`:spec` to `:implemented` in the `kotoba-lang/industry` registry,
following the verified fresh-scaffold + mandatory-verification
protocol used across this fleet's careful, smaller-batch rollout
(after the prior 18-agent haiku batch's 61% defect rate, 78+
consecutive agents on the stricter protocol — capable model +
mandatory `clojure -M:test` verification — have all succeeded; this is
the next one).

## Context

`kotoba-lang/industry`'s registry entry `{:id "2021" ...}` carries
`:name "Manufacture of pesticides and other agrochemical products"`
(ISIC Rev.5 2021) — verified against a fresh clone of
`kotoba-lang/industry` before any implementation work began, per this
fleet's mandatory ID/name-match discipline (multiple prior agents in
this fleet have mislabeled their assigned ISIC class; this run
confirmed the live `:name` matches the assigned scope before writing
anything). `gh api repos/cloud-itonami/cloud-itonami-isic-2021` 404'd
before this repo was created — confirmed fresh scaffold, no prior repo
to adopt or merge into. The registry's pre-existing `:repo` value was
the legacy placeholder `https://github.com/gftdcojp/cloud-itonami-C2021`
(never created — the standard "spec, not yet implemented" placeholder
pattern this fleet uses fleet-wide), not a real repo to migrate from.

The reference architecture mirrored closely is `cloud-itonami-isic-2022`
(Manufacture of paints, varnishes and similar coatings, printing ink
and mastics, 83 tests / 238 assertions in that repo at the time this
build began) — the closest architectural analog: both are back-office
coordination actors for a fixed processing PLANT with heavy
manufacturing equipment and a real physical safety dimension, sharing
the same four-op shape (`:log-production-batch`/`:schedule-
maintenance`/`:flag-safety-concern`/`:coordinate-shipment`) and the
same two-entity verified/registered gate structure. 2021's own hazard
AND regulatory profile is distinct: active-ingredient toxicity/
exposure-risk and environmental-contamination hazard during
formulation/mixing (rather than 2022's solvent-VOC/flammability
hazard), AND — uniquely in this fleet so far — an explicit
pesticide-registration/label-approval regulatory structure this actor
must stay permanently OUT of deciding, in addition to an
active-ingredient-concentration label-ceiling obligation analogous to
2022's VOC-content ceiling. The full domain adaptation (equipment/
product vocabulary, the new permanent registration-decision block,
hazard language) is recorded in the child repo's own
`docs/adr/0001-architecture.md`.

## Decision

### Decision 1: Scope — an agrochemical-plant PLANT OPERATIONS COORDINATION actor, not formulation/mixing-line control authority, NOT a pesticide-registration authority

`cloud-itonami-isic-2021` is `PesticideAdvisor ⊣ Pesticide &
Agrochemical Plant Operations Governor`, a langgraph-clj StateGraph
with an append-only audit ledger (same pattern as every actor in this
fleet — see skill `build-actor`). Closed allowlist of four ops, all
`:effect :propose` only:
- `:log-production-batch` — formulation/mixing batch, active-ingredient-concentration data logging
- `:schedule-maintenance` — formulation/mixing-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a chemical-hazard (toxicity/exposure risk)/environmental-contamination concern, ALWAYS escalates
- `:coordinate-shipment` — outbound product shipment coordination

### Decision 2: HARD invariants (no override)

1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action.
2. `:effect` must be `:propose` only (never a direct-write bypass).
3. Any proposal touching formulation/mixing-line-equipment control OR a pesticide-registration/label-approval-authority decision is a hard, permanent block — TWO independent permanent blocks: a dedicated `:actuate-line? true` block on `:schedule-maintenance` proposals (equipment control), and a dedicated `:decide-registration? true` block on `:log-production-batch` proposals (regulatory-authority decision-making), the domain-specific twin structurally identical in severity/permanence to the first.
4. The op allowlist is closed — the four ops above only.

Elaborated into twelve concrete governor checks (propose-only effect,
closed op allowlist, closed proposal-effect allowlist, permanent
line-actuate block, permanent registration-decision block, independent
equipment verification before maintenance scheduling, double-schedule
guard, independent batch verification before shipment coordination,
independent shipment-weight recompute, product-type validation,
active-ingredient-concentration plausibility validation,
active-ingredient-concentration label-ceiling validation) — see child
repo README/ADR for the full enumeration.

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

(+) ISIC 2021 (Manufacture of pesticides and other agrochemical
products) now has a governed, auditable, forkable OSS plant-operations
coordination actor with an explicit, structural, permanently
unconditional block against ever deciding a pesticide registration or
product-label approval — a regulatory-authority boundary this fleet
had not yet needed to encode before this class.

(+) `kotoba-lang/industry` registry entry `2021` promoted from `:spec`
to `:implemented`, with `:repo`/`:business-id` corrected from the
legacy `gftdcojp/cloud-itonami-C2021` placeholder naming to the
`cloud-itonami/cloud-itonami-isic-2021` convention used by every other
`:implemented` entry in this fleet (e.g. 2013, 2022).

(-) Still a simulation/proposal layer — no integration with real
plant-management databases (equipment telemetry, batch tracking,
freight dispatch, or an authoritative multi-jurisdiction pesticide-
registration/label database). Equipment actuation and formulation/
mixing-line operation remain human-controlled via external channels,
and pesticide registration/label approval remains a regulatory-
authority process entirely outside this actor, as intended.

## Verification

- `cloud-itonami-isic-2021` repo: `https://github.com/cloud-itonami/cloud-itonami-isic-2021`
- `clojure -M:test` (fresh clone, before merge to `kotoba-lang/industry`):
  `Ran 80 tests containing 223 assertions. 0 failures, 0 errors.`
- `clojure -M:lint`: `linting took 1368ms, errors: 0, warnings: 0`
- `clojure -M:dev:run` demo: exercises proposal submission, escalation,
  and every HARD-hold scenario directly (not-propose-effect,
  unknown-op, equipment-not-verified, batch-not-verified,
  shipment-weight-exceeded, line-actuate-blocked,
  registration-decision-blocked, already-scheduled,
  invalid-product-type, invalid-active-ingredient,
  active-ingredient-exceeds-label-limit).
- `kotoba-lang/industry` registry `2021` entry edited in place
  (exact-text, not a parse/reserialize) from `:maturity :spec` to
  `:maturity :implemented`, `:repo`/`:business-id` corrected to the
  `cloud-itonami-isic-2021` convention, ADR reference added.
- `industry_test.clj`'s `:implemented` count assertion bumped to the
  true recomputed count (via `kotoba.industry/maturity-summary`) and
  the full `industry` test suite re-run green before push.
- Post-merge re-verification: fresh re-clone of both
  `cloud-itonami-isic-2021` and `kotoba-lang/industry` (+
  `kotoba-lang/technology` sibling), `clojure -M:test` re-run in both,
  and the `2021` registry entry specifically re-checked as
  `:implemented` (not silently reverted by a concurrent broad rewrite
  from another session) — raw output recorded in this ADR's PR/commit
  trail and the task's final report.
