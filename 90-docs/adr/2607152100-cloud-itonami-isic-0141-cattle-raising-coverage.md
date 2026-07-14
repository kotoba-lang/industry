# ADR-2607152100: cloud-itonami-isic-0141 (raising of cattle and buffaloes) ranch-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage)

## Context

A prior attempt today scaffolded `cloud-itonami/cloud-itonami-isic-0141`
(docs/, test/, README.md) but pushed **no `src/` and no `deps.edn`** while
falsely claiming the actor was complete and tested. This was caught in
audit and reverted. This ADR codifies the corrected, actually-implemented
version, built by reading `cloud-itonami-isic-1010` (meat processing) in
full as the module-structure template and independently re-running
`clojure -M:test` before and after every push.

Cattle-raising operations (ISIC Rev. 4 0141) span: herd count/weight/
health-check record-keeping, veterinary appointment coordination, animal
health/welfare concern escalation, and feed/veterinary-supply/equipment
procurement. **CRITICAL exclusions**: direct animal handling, veterinary
treatment decisions, and slaughter/culling decisions remain the exclusive
authority of the rancher/veterinarian — this actor only coordinates
back-office record-keeping and logistics, never animal-domain actuation.

## Decision

Implement a complete ranch-operations-coordination actor
(`cloud-itonami-isic-0141`), mirroring `cloud-itonami-isic-1010`'s module
shape module-for-module:

1. **`cattleops.governor`** (`RanchingOperationsGovernor`) — independent
   constraint layer with HARD checks (always hold, no override):
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

   ESCALATION invariants (always human sign-off):
   - `:flag-animal-health-concern` — ALWAYS escalates, any confidence.
   - `:order-supplies` above its category cost threshold (default 500;
     `cattleops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`cattleops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (feed, veterinary-supply, equipment)
   and species classification (cattle, buffalo).

3. **`cattleops.registry`** — independent, unconditional pure predicates:
   `cost-exceeds-threshold?`, `herd-count-non-positive?`,
   `confidence-below-floor?`.

4. **`cattleops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-facility` lookup, `add-facility` for tests/simulation.

5. **`cattleops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`cattleops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`cattleops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching `meatprocessing.operation`'s own stub status).

8. **`cattleops.sim`** — demo runner (`clojure -M:run`), with a working
   `-main` (the reference 1010 repo's own `-M:run` alias is latent-broken
   — missing a `clojure.set` require in `facts.cljc` — this
   implementation does not inherit that defect).

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-herd-record`, `:schedule-veterinary-visit`,
   `:flag-animal-health-concern`, `:order-supplies`.

10. **Tests** — 30 tests / 90 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/`sim_test`,
    matching 1010's own test coverage shape).

11. **Documentation** — README.md/docs/operator-guide.md corrected to
    describe the actual implemented API (the reverted prior attempt's
    docs referenced a nonexistent `cattleops.actor`/`build-graph`/
    `approve!` surface; this version documents `cattleops.operation`'s
    real `run-operation`/`build` functions and `:commit`/`:escalate`/
    `:hold` dispositions instead). blueprint.edn metadata; standard OSS
    files (CONTRIBUTING, GOVERNANCE, SECURITY, CODE_OF_CONDUCT,
    AGPL-3.0-or-later LICENSE copied verbatim from 1010).

## Consequences

(+) Cattle-raising (ISIC 0141) ranch-operations-coordination is now
genuinely implemented and fully tested — closing the gap left by the
reverted prior attempt.

(+) Scope boundaries (direct animal handling, veterinary treatment
decisions, slaughter/culling decisions permanently excluded) are
hardcoded in governor checks (`treatment-or-slaughter-blocked`) and
documented in README, not just asserted in prose.

(+) Animal-welfare escalation (`:flag-animal-health-concern` always
human) is a core design invariant, not an add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs
(`System/currentTimeMillis`, `Date`, etc. never used); `clojure -M:lint`
is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 1010's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0141`: `clojure -M:test` → "Ran 30 tests
  containing 90 assertions. 0 failures, 0 errors." Independently
  re-verified from a fresh clone of `origin/main` after push.
- `clojure -M:lint` → 0 errors, 0 warnings. `clojure -M:run` → demo
  runs end-to-end, returns `:disposition :escalate` as expected
  (phase-0 forces human review of all commits).
- Commit `1c91fca` pushed to `cloud-itonami/cloud-itonami-isic-0141`'s
  `main` (fast-forward from prior scaffold commit `6ff6b1d`).
- Registry entry (`kotoba-lang/industry`) updated in place: `"0141"`
  entry's `:maturity` → `:implemented`, `REVERTED` comment removed
  (`:repo`/`:business-id` were already correct). Landed via GitHub API
  server-side merge commit `4f55cacb28a0ec9503844a01aa423aec1fc76687`
  onto `main` (one 409 retry: a concurrent agent's ISIC-0311 promotion
  landed first and bumped the registry's own maturity-tier counter from
  187→188; rebased onto that, then landed 188→189). Post-merge,
  `clojure -M:test` from a **completely fresh clone** of
  `kotoba-lang/industry`'s `main` → "Ran 15 tests containing 941
  assertions. 0 failures, 0 errors."
