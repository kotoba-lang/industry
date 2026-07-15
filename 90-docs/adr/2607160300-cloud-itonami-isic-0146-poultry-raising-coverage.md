# ADR-2607160300: cloud-itonami-isic-0146 (raising of poultry) poultry-farm-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage),
ADR-2607152100 (cloud-itonami-isic-0141, cattle-raising),
ADR-2607154000 (cloud-itonami-isic-0145, swine-raising)

## Context

`kotoba-lang/industry`'s registry entry for ISIC Rev. 4 class `0146`
(`{:id "0146" :name "Raising of poultry" ...}`) sat at `:maturity :spec`
with no repository. Verified against a fresh, independent clone of
`kotoba-lang/industry` before any work began (per this fleet's
ID/name-mismatch caution, given two prior agents in this rollout
mislabeled their assigned ISIC class). No prior `cloud-itonami-isic-0146`
repository existed (`gh api repos/cloud-itonami/cloud-itonami-isic-0146`
returned 404).

Poultry-raising operations (ISIC Rev. 4 0146) span: flock count/weight/
mortality/egg-production record-keeping, veterinary appointment
coordination, animal health/biosecurity concern escalation (e.g.
suspected Highly Pathogenic Avian Influenza / HPAI), and feed/
veterinary-supply/biosecurity-equipment procurement. **CRITICAL
exclusions**: direct animal handling, veterinary treatment decisions, and
culling/depopulation decisions remain the exclusive authority of the farm
operator/veterinarian — this actor only coordinates back-office
record-keeping and logistics, never animal-domain actuation.

## Decision

Implement a complete poultry-farm-operations-coordination actor
(`cloud-itonami-isic-0146`), mirroring `cloud-itonami-isic-0145`'s
(swine-raising) module shape module-for-module, adapted to poultry
(broiler/layer) domain vocabulary:

1. **`poultryops.governor`** (`PoultryFarmOperationsGovernor`) —
   independent constraint layer with HARD checks (always hold, no
   override):
   - `facility-not-registered` — request's facility-id must resolve to a
     registered facility (barn/house) in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`;
     the governor never directly executes anything.
   - `treatment-or-culling-blocked` — `:administer-treatment` and
     `:order-culling` are unconditionally, permanently blocked
     regardless of confidence or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `flock-count-invalid` — `:log-flock-record` with a non-positive
     count is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-animal-health-concern` — ALWAYS escalates, any confidence
     (e.g. suspected HPAI).
   - `:order-supplies` above its category cost threshold (default 500;
     `poultryops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for biosecurity-equipment).
   - low confidence (< 0.7).

2. **`poultryops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (feed/veterinary-supply/
   biosecurity-equipment), poultry breeds spanning broiler (Cobb 500,
   Ross 308) and layer (White Leghorn, ISA Brown) types, and
   biosecurity/notifiable-disease vocabulary (HPAI, Newcastle Disease,
   Infectious Bronchitis, Infectious Bursal Disease).

3. **`poultryops.registry`** — independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `flock-count-non-positive?`,
   `confidence-below-floor?`.

4. **`poultryops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-facility` lookup, `add-facility` for tests/simulation.

5. **`poultryops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`poultryops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`poultryops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching `swineops.operation`'s own stub status).

8. **`poultryops.sim`** — demo runner (`clojure -M:run`), with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-flock-record`, `:schedule-veterinary-visit`,
   `:flag-animal-health-concern`, `:order-supplies`.

10. **Tests** — 32 tests / 103 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/`sim_test`,
    matching 0145's own test coverage shape, plus one extra `facts_test`
    deftest for poultry-specific broiler/layer `:flock-type`
    classification not present in the swine reference).

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`poultryops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0145).

## Consequences

(+) Poultry-raising (ISIC 0146) poultry-farm-operations-coordination is
now genuinely implemented and fully tested.

(+) Scope boundaries (direct animal handling, veterinary treatment
decisions, culling/depopulation decisions permanently excluded) are
hardcoded in governor checks (`treatment-or-culling-blocked`) and
documented in README, not just asserted in prose.

(+) Animal-welfare/biosecurity escalation (`:flag-animal-health-concern`
always human, e.g. suspected HPAI) is a core design invariant, not an
add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs
(`System/currentTimeMillis`, `Date`, etc. never used); `clojure -M:lint`
is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0145's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0146`: `clojure -M:test` → "Ran 32 tests
  containing 103 assertions. 0 failures, 0 errors." Independently
  re-verified from a fresh clone of `origin/main` after push.
- `clojure -M:lint` → 0 errors, 0 warnings. `clojure -M:run` → demo
  runs end-to-end, returns `:disposition :escalate` as expected
  (phase-0 forces human review of all commits).
- Commit `3c44ca283f28facfb4e04aa75fa0e7e9b0dcc08f` pushed to
  `cloud-itonami/cloud-itonami-isic-0146`'s `main` (fresh repo, initial
  commit; `git merge-base --is-ancestor` confirmed it landed on
  `origin/main`).
- Registry entry (`kotoba-lang/industry`) updated in place: `"0146"`
  entry's `:repo`/`:business-id` set to the new repo, `:maturity` →
  `:implemented`, `:required-technologies` trimmed to
  `[:robotics :identity :forms :audit-ledger]` (matching the
  `:implemented`-tier convention used by 0141/0145, rather than the
  7-item placeholder list every still-`:spec` entry carries), comment
  updated to describe the implementation and cite this ADR. Landed via
  GitHub API server-side merge commit
  `971d83a6d72ab553614830eeb0179ecedc060ddb` onto `main` (two 409
  retries: concurrent agents' ISIC-0122 and ISIC-1074 promotions each
  landed first and bumped the registry's own maturity-tier counter,
  213→214→215; rebased onto each in turn, then landed 215→216). Full
  suite live-recomputed each retry from a freshly re-fetched
  `origin/main` rather than assuming a fixed number.
- Post-merge re-verification from a **completely fresh clone** of
  `kotoba-lang/industry`'s `main` (HEAD at the merge commit itself) plus
  a fresh `kotoba-lang/technology` sibling clone: `clojure -M:test` →
  "Ran 15 tests containing 945 assertions. 0 failures, 0 errors."
  `grep -c "â" resources/kotoba/industry/registry.edn` → `0` (no
  UTF-8 mojibake).
