# ADR-2607172100: cloud-itonami-isic-0119 (growing of other non-perennial crops) field-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage),
ADR-2607153000 (cloud-itonami-isic-0113, module-structure template)

## Context

This is part of an ongoing careful, smaller-batch rollout (capable model +
mandatory verification per agent) after a prior 18-agent haiku batch had a
61% defect rate (empty implementations, missing modules, false "all
green" claims). 42+ consecutive agents on this stricter protocol have
succeeded before this one. Built by reading `cloud-itonami-isic-0113`
(growing of vegetables and melons, roots and tubers, 30 tests / 99
assertions) in full as the module-structure template, with `clojure
-M:test` independently re-run from fresh clones both before and after
push.

Before any implementation work, the `kotoba-lang/industry` registry entry
was independently re-verified (fresh clone, not trusted from the task
assignment): `{:id "0119" :name "Growing of other non-perennial crops"
...}` is genuinely what is registered — no ID/name mismatch (several
prior agents in this fleet had mislabeled their assigned ISIC class, e.g.
0892 assumed=salt actually=peat, 0144 assumed=swine actually=sheep-goats).
A fresh `gh api` check also confirmed no prior repository existed at
either `cloud-itonami/cloud-itonami-isic-0119` or the stale placeholder
`gftdcojp/cloud-itonami-A0119` referenced by the old registry entry (404
confirmed).

Other-non-perennial-crop-growing operations (ISIC Rev. 4 0119, covering
fodder/forage crops, flower and vegetable seed crops, cut flowers and
ornamental plants for cutting, and other unclassified annual crops — not
cereals [0111], rice [0112], sugar cane/beet [0114], tobacco [0115],
fibre crops [0116], or grapes [0121], all separate classes) span: field
record logging (planting, harvest yield, soil-test), field-operation
(planting/spraying/irrigation/harvest) scheduling coordination, crop
pest/disease/frost-damage concern escalation, and seed/fertilizer/
equipment procurement. **CRITICAL exclusions**: direct field-equipment
operation and finalizing a pesticide-application decision remain the
exclusive authority of the farmer/agronomist — this actor only
coordinates back-office record-keeping and logistics, never field-domain
actuation or agronomic decision-making.

## Decision

Implement a complete field-operations-coordination actor
(`cloud-itonami-isic-0119`), mirroring `cloud-itonami-isic-0113`'s module
shape module-for-module (namespace `othercropops.*` in place of
`vegops.*`). The task's domain-design section specified the identical
closed op-allowlist and hard-invariant shape as 0113 verbatim, so — unlike
0114 (`ratoon-cycle-invalid?`) or 0116 (`quality-grade-invalid`) — no new
domain-specific Governor gate was added; this vertical differentiates
itself purely in its crop catalog (`othercropops.facts`), not in a new
independent-verification rule:

1. **`othercropops.governor`** (`FieldOperationsGovernor`) — independent
   constraint layer with HARD checks (always hold, no override):
   - `field-not-registered` — request's field-id must resolve to a
     registered field in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`; the
     governor never directly executes anything.
   - `equipment-or-pesticide-decision-blocked` —
     `:operate-field-equipment` and `:finalize-pesticide-application`
     are unconditionally, permanently blocked regardless of confidence
     or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `field-record-invalid` — `:log-field-record` with a non-positive
     acreage is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` — ALWAYS escalates, any confidence.
   - `:order-supplies` above its category cost threshold (default 500;
     `othercropops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`othercropops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (seed, fertilizer, equipment) and
   other-non-perennial-crop classification (fodder-corn, alfalfa, clover,
   forage-kale, cut-flower, ornamental-plant, flower-seed, vegetable-seed
   — excludes cereals, rice, sugar cane/beet, tobacco, fibre crops,
   grapes).

3. **`othercropops.registry`** — independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `acreage-non-positive?`,
   `confidence-below-floor?`.

4. **`othercropops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-field` lookup, `add-field` for tests/simulation.

5. **`othercropops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`othercropops.phase`** — 0→3 rollout phase gate: phase-0 forces
   every would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even
   when clean; phase-2/3 pass the Governor's disposition through
   unchanged.

7. **`othercropops.operation`** — composes advisor → governor →
   phase-gate into one synchronous operation run (langgraph-clj
   StateGraph wiring deferred, matching 0113's own stub status).

8. **`othercropops.sim`** — demo runner (`clojure -M:run`) with a working
   `-main`; verified end-to-end (`:disposition :escalate`, phase-0
   forces human review as expected).

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-field-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** — 30 tests / 102 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0113's own test coverage shape). Assertion
    count is 3 higher than 0113's 99 because `othercropops.facts_test`
    adds explicit out-of-scope checks for sugar-cane/beet (0114),
    tobacco (0115), and fibre crops (0116) on top of 0113's own
    cereals/rice/grapes exclusion checks.

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`othercropops.operation`'s real `run-operation`/`build` functions
    and `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn
    metadata; standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0113).

## Consequences

(+) Other-non-perennial-crop growing (ISIC 0119) field-operations-
coordination is now genuinely implemented and fully tested.

(+) Scope boundaries (direct field-equipment operation and finalizing
pesticide-application decisions permanently excluded) are hardcoded in
governor checks (`equipment-or-pesticide-decision-blocked`) and
documented in README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern` always human) is
a core design invariant, not an add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs
(`System/currentTimeMillis`, `Date`, etc. never used); `clojure -M:lint`
is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0113's own stub status; production
integration pending.

(-) No new domain-specific Governor gate was added for this vertical
(unlike 0114/0116's ratoon-cycle/quality-grade checks) — the task's
domain-design section specified the same hard-invariant/escalation shape
as 0113 verbatim, so this actor is a direct crop-catalog adaptation
rather than a novel gate.

## Verification

- `cloud-itonami-isic-0119`: `clojure -M:test` → "Ran 30 tests
  containing 102 assertions. 0 failures, 0 errors." `clojure -M:lint` →
  0 errors, 0 warnings.
- Commit `ee9d1608e680e7f833c02f564142ee94ef3d582f` pushed to
  `cloud-itonami/cloud-itonami-isic-0119`'s `main` (fresh repo,
  root-commit). Confirmed via `gh api
  repos/cloud-itonami/cloud-itonami-isic-0119/commits/main` matching the
  local commit SHA.
- Registry entry (`kotoba-lang/industry`) updated in place: `"0119"`
  entry's `:maturity` `:spec` → `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-A0119` placeholder to
  `cloud-itonami/cloud-itonami-isic-0119`, and this ADR referenced.
  `industry_test.clj`'s `maturity-summary` assertion live-recomputed
  231 → 232: before editing, `clojure -M:test` was independently run
  against `origin/main`'s unedited HEAD to confirm the existing pinned
  `231` assertion was still green (ruling out a raw `grep -c
  ":maturity :implemented"` undercount before trusting the +1 delta —
  the registry has one `:implemented?` boolean special-case entry
  `maturity-of` accounts for that a plain grep misses), then the edited
  working tree was independently re-tested before commit (`clojure
  -M:test` → "Ran 15 tests containing 949 assertions. 0 failures, 0
  errors.").
- Landed via GitHub API server-side merge onto `kotoba-lang/industry`'s
  `main`, succeeding on the **first attempt** (no 409 retries needed):
  merge commit `0cf855aeeb8b6a84c714525c932f69f24d8e184e`. Stray
  intermediate branch `isic-0119-implemented` deleted after landing.
- Post-merge re-verification from a **brand-new fresh clone** of
  `kotoba-lang/industry`'s `main` (with `kotoba-lang/technology`
  re-cloned as its `../technology` sibling, per `industry`'s own
  `deps.edn` local/root dependency): `clojure -M:test` → "Ran 15 tests
  containing 949 assertions. 0 failures, 0 errors." `clojure -M:lint` →
  0 errors, 0 warnings. `grep -c "â"
  resources/kotoba/industry/registry.edn` → `0` (no file-wide UTF-8
  mojibake).
