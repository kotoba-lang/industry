# ADR-2607181100: cloud-itonami-isic-0143 (raising of camels and camelids) camelid-facility-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607152100 (cloud-itonami-isic-0141, cattle-raising
coverage), ADR-2607171200 (cloud-itonami-isic-0142, equine-raising
coverage), ADR-2607011000 (actor pattern & ISIC section coverage)

## Context

`kotoba-lang/industry`'s registry carried `{:id "0143" :name "Raising of
camels and camelids" ...}` at `:maturity :spec`, pointed at a
never-created placeholder repo (`gftdcojp/cloud-itonami-A0143`). No repo
existed at either the placeholder location or the real `cloud-itonami`
org target name (`gh api repos/cloud-itonami/cloud-itonami-isic-0143`
confirmed 404 before any work began). The `{:id "0143" ...}` identity was
independently re-verified against a fresh clone of `kotoba-lang/industry`
before scaffolding, per this fleet's ID/name-mismatch caution (prior
agents in this fleet mislabeled assigned classes, e.g. 0892
assumed=salt/actually=peat, 0144 assumed=swine/actually=sheep-goats).

Camelid-raising operations (ISIC Rev. 4 0143) span camel (dromedary/
bactrian) dairy and pack-animal operations plus llama/alpaca fiber
ranches: herd count/weight/health-check/fiber-yield record-keeping,
veterinary appointment coordination, animal health/welfare concern
escalation, and feed/veterinary-supply/fiber-processing-supply/equipment
procurement. **CRITICAL exclusions**: direct animal handling, veterinary
treatment decisions, and slaughter/culling decisions remain the exclusive
authority of the rancher/herder/veterinarian — this actor only
coordinates back-office record-keeping and logistics, never
animal-domain actuation.

## Decision

Implement a complete camelid-facility-operations-coordination actor
(`cloud-itonami-isic-0143`), mirroring `cloud-itonami-isic-0141`'s
(Raising of cattle and buffaloes) verified module shape module-for-module,
with `cloud-itonami-isic-0142` (Raising of horses and other equines) as a
second cross-check of the same sibling shape:

1. **`camelops.governor`** (Camelid Facility Operations Governor) —
   independent constraint layer with HARD checks (always hold, no
   override):
   - `facility-not-registered` — request's facility-id must resolve to a
     registered facility in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`;
     the governor never directly executes anything.
   - `treatment-or-slaughter-blocked` — `:administer-treatment` and
     `:order-slaughter` are unconditionally, permanently blocked
     regardless of confidence or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `herd-count-invalid` — `:log-herd-record` with a non-positive count
     is rejected.
   - `fiber-yield-invalid` — `:log-herd-record` with a negative fiber
     yield is rejected. This is a genuinely new independently-verified
     check for this fiber-producing (llama/alpaca) vertical, absent from
     0141/0142's sibling shape: zero is a valid observation (a working
     camel or not-yet-sheared animal may log zero fiber), but a negative
     weight is never a real observation.

   ESCALATION invariants (always human sign-off):
   - `:flag-animal-health-concern` — ALWAYS escalates, any confidence.
   - `:order-supplies` above its category cost threshold (default 500;
     `camelops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`camelops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (feed/veterinary-supply/
   fiber-processing-supply/equipment), species classification
   (dromedary-camel/bactrian-camel/llama/alpaca), and use classes
   (dairy/fiber/pack-animal).

3. **`camelops.registry`** — independent, unconditional pure predicates:
   `cost-exceeds-threshold?`, `herd-count-non-positive?`,
   `fiber-yield-invalid?`, `confidence-below-floor?`.

4. **`camelops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-facility` lookup, `add-facility` for tests/simulation.

5. **`camelops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below, including
   `:fiber-yield` on `:log-herd-record` proposals.

6. **`camelops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`camelops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching 0141/0142's own stub status).

8. **`camelops.sim`** — demo runner (`clojure -M:run`) with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-herd-record`, `:schedule-veterinary-visit`,
   `:flag-animal-health-concern`, `:order-supplies`.

10. **Tests** — 35 tests / 106 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0141/0142's own test coverage shape). 5 more
    tests / 16 more assertions than 0141's 30/90 baseline, from the added
    fiber-yield checks, extra species/use-class/supply-category coverage.

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describing the real `camelops.operation`
    `run-operation`/`build` API and `:commit`/`:escalate`/`:hold`
    dispositions. blueprint.edn metadata; standard OSS files
    (CONTRIBUTING, GOVERNANCE, SECURITY, CODE_OF_CONDUCT,
    AGPL-3.0-or-later LICENSE copied verbatim from 0141).

12. All source is portable `.cljc` with no JVM-only interop (verified by
    grep for `java.`/`Exception`/`.indexOf`/`System/` — zero matches —
    and by `clojure -M:lint`, 0 errors / 0 warnings), per this
    superproject's cljs-first runtime-priority rule.

## Consequences

(+) Camelid-raising (ISIC 0143) camelid-facility-operations-coordination
is now genuinely implemented and fully tested from a fresh scaffold — no
prior partial/reverted attempt existed for this class.

(+) Scope boundaries (direct animal handling, veterinary treatment
decisions, slaughter/culling decisions permanently excluded) are
hardcoded in governor checks (`treatment-or-slaughter-blocked`) and
documented in README, not just asserted in prose.

(+) Animal-welfare escalation (`:flag-animal-health-concern` always
human) is a core design invariant, not an add-on.

(+) The fiber-yield invariant (`fiber-yield-invalid`) gives this
fiber-producing vertical a genuinely distinct, independently-verified
physical check beyond the 0141/0142 sibling template, rather than a
label-only rename.

(+) Portable `.cljc` implementation with no JVM-only constructs;
`clojure -M:lint` is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0141/0142's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0143`: `clojure -M:test` → "Ran 35 tests
  containing 106 assertions. 0 failures, 0 errors." `clojure -M:lint` →
  0 errors, 0 warnings. `clojure -M:run` → demo runs end-to-end, returns
  `:disposition :escalate` as expected (phase-0 forces human review of
  all commits).
- Commit `e7168ca8420fa8a6a862b55fb7ae5a74daac28a2` pushed to
  `cloud-itonami/cloud-itonami-isic-0143`'s `main` (fresh repo,
  root-commit).
- Registry entry (`kotoba-lang/industry`) updated in place: `"0143"`
  entry's `:maturity` → `:implemented`, `:repo`/`:business-id` corrected
  from the placeholder `gftdcojp/cloud-itonami-A0143` to the real
  `cloud-itonami/cloud-itonami-isic-0143`; `:required-technologies`
  narrowed to the actually-implemented `[:robotics :identity :forms
  :audit-ledger]` matching the sibling 0141/0142 entries'
  post-implementation shape. `industry_test.clj`'s `maturity-summary`
  `:implemented` assertion bumped 237 → 238 (live-recomputed via
  `(industry/maturity-summary)` immediately before the edit on a
  freshly re-fetched `origin/main`).
- Landed via GitHub API server-side merge commit
  `b622a194f0ef455b47c05e2121945d2102d5761d` onto `kotoba-lang/industry`'s
  `main` (first attempt, no 409 retries needed). Post-merge, `clojure
  -M:test` from a **completely fresh clone** of `kotoba-lang/industry`'s
  `main` → "Ran 15 tests containing 950 assertions. 0 failures, 0
  errors." `grep -c "â" resources/kotoba/industry/registry.edn` → `0`
  (no mojibake).
