# ADR-2609300000: cloud-itonami-isic-0144 (raising of sheep and goats) flock-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607154000 (cloud-itonami-isic-0145 swine-raising
coverage — module-structure template and origin of the 0144/0145
code-mislabel discovery that this ADR resolves), ADR-2607152100
(cloud-itonami-isic-0141 cattle-raising coverage), ADR-2607122200 (ISIC
Wave 3 food/agriculture coverage), ADR-2607011000 (actor pattern & ISIC
section coverage)

## Context

A prior agent, tasked with what it believed was ISIC 0144, discovered
mid-build that the numeric code was mislabeled: per the authoritative
`kotoba-lang/industry` registry
(`resources/kotoba/industry/registry.edn`, matching the real UN ISIC
Rev. 4 standard), `"0144"` is **"Raising of sheep and goats"** and
`"0145"` is **"Raising of swine/pigs"**. That agent's actual (correctly
built, swine-content) repo was renamed `cloud-itonami-isic-0144` →
`cloud-itonami-isic-0145` to fix the mislabel (ADR-2607154000), leaving
`kotoba-lang/industry`'s `"0144"` entry (`:maturity :spec`, placeholder
`:repo`/`:business-id` `gftdcojp/cloud-itonami-A0144`) genuinely
untouched and unimplemented — a distinct ISIC class explicitly deferred
for future coverage.

This ADR is that future coverage. Before any work began, the live
`kotoba-lang/industry` registry was re-verified fresh (`{:id "0144" ...}`
block read directly, not assumed from the prior ADR's account):
`:name "Raising of sheep and goats"` confirmed to match the task premise.

GitHub's rename-redirect made `gh api repos/cloud-itonami/cloud-itonami-isic-0144`
return the **renamed swine repo's data** (`full_name:
"cloud-itonami/cloud-itonami-isic-0145"`) — a false-positive "exists"
signal for the *old* name, not a real conflict for the *now-free*
`cloud-itonami-isic-0144` name. `gh repo create
cloud-itonami/cloud-itonami-isic-0144` succeeded immediately on the
first attempt (no grace-period block encountered; the
`cloud-itonami-isic-0144-livestock` fallback name was not needed).

Sheep-and-goat farm operations (ISIC Rev. 4 0144) span: flock husbandry
record-keeping (feeding/breeding/shearing/health-check batch data,
including lambing/kidding offspring counts and fleece/shearing weight),
grazing-rotation/shearing/breeding operation scheduling, animal
health/welfare concern escalation (e.g. suspected Scrapie or
Bluetongue), and feed/veterinary-supply/shearing-equipment procurement.
**CRITICAL exclusions**: direct animal handling, veterinary treatment
decisions, and culling decisions remain the exclusive authority of the
farm operator/veterinarian — this actor only coordinates back-office
record-keeping and logistics, never animal-domain actuation, and it
never itself declares a disease outbreak or contacts animal-health
authorities.

Built by reading `cloud-itonami-isic-0145` (`swineops.*`, raising of
swine/pigs) in full as the module-structure template — the correct,
already-corrected sibling this task was explicitly pointed at — and
independently re-running `clojure -M:test` before and after every push,
following the same stricter verification protocol as the preceding
batch of ISIC-coverage actors in this rollout.

## Decision

Implement a complete flock-operations-coordination actor
(`cloud-itonami-isic-0144`), mirroring `cloud-itonami-isic-0145`'s
(`swineops.*`) module shape module-for-module, adapted from
swine-farming to sheep-and-goat farming (`flockops.*` namespace;
paddock/pen management; health/welfare vocabulary specialized to
sheep/goat notifiable diseases instead of ASF/CSF/PRRS):

1. **`flockops.governor`** (`FlockOperationsGovernor`) — independent
   constraint layer with HARD checks (always hold, no override):
   - `facility-not-registered` — request's facility-id must resolve to a
     registered facility (paddock/pen complex) in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`;
     the governor never directly executes anything.
   - `treatment-or-culling-blocked` — `:administer-treatment` and
     `:finalize-culling-decision` are unconditionally, permanently
     blocked regardless of confidence or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `flock-count-invalid` — `:log-husbandry-record` with a non-positive
     count is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-animal-health-concern` — ALWAYS escalates, any confidence
     (e.g. suspected Scrapie/Bluetongue risk).
   - `:order-supplies` above its category cost threshold (default 500;
     `flockops.facts/supply-categories` gives category-specific
     thresholds, e.g. 800 for shearing-equipment).
   - low confidence (< 0.7).

2. **`flockops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (feed, veterinary-supply,
   shearing-equipment), breed classification with species tag (suffolk/
   merino — sheep; saanen/boer — goat), and a health/welfare-concern
   reference vocabulary (Scrapie, Bluetongue, FMD, Foot Rot) — purely
   descriptive, never changes the Governor's disposition since every
   flagged concern already always escalates.

3. **`flockops.registry`** — independent, unconditional pure predicates:
   `cost-exceeds-threshold?`, `flock-count-non-positive?`,
   `confidence-below-floor?`.

4. **`flockops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-facility` lookup, `add-facility` for tests/simulation.

5. **`flockops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`flockops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`flockops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching 0145's own stub status).

8. **`flockops.sim`** — demo runner (`clojure -M:run`) with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-husbandry-record`, `:schedule-farm-operation`,
   `:flag-animal-health-concern`, `:order-supplies`.

10. **Tests** — 31 tests / 103 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0145's own test coverage shape — extra
    assertions vs. 0145 come from an added breed-species-classification
    `are` block in `facts_test`).

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`flockops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata
    (`:isic-rev5 "0144"`); standard OSS files (CONTRIBUTING, GOVERNANCE,
    SECURITY, CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim
    from 0145).

## Consequences

(+) Sheep-and-goat raising (ISIC 0144) flock-operations-coordination is
now genuinely implemented and fully tested — closing the gap explicitly
left open by ADR-2607154000.

(+) Scope boundaries (direct animal handling, veterinary treatment
decisions, culling decisions permanently excluded) are hardcoded in
governor checks (`treatment-or-culling-blocked`) and documented in
README, not just asserted in prose.

(+) Animal-health/welfare escalation (`:flag-animal-health-concern`
always human, e.g. suspected Scrapie or Bluetongue) is a core design
invariant, not an add-on — and the actor is structurally incapable of
declaring an outbreak or contacting authorities on its own.

(+) Portable `.cljc` implementation with no JVM-only constructs;
`clojure -M:lint` is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0145's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0144`: `clojure -M:test` → "Ran 31 tests
  containing 103 assertions. 0 failures, 0 errors." (initial build,
  scratch dir). `clojure -M:lint` → 0 errors, 0 warnings.
- Registry entry (`kotoba-lang/industry`): `"0144"` entry's `:maturity`
  `:spec` → `:implemented`, `:repo`/`:business-id` corrected from the
  `gftdcojp/cloud-itonami-A0144` placeholder to
  `cloud-itonami/cloud-itonami-isic-0144`, ADR reference added. See the
  commit/merge SHAs and fresh-clone post-merge re-verification recorded
  in this task's final report (not duplicated here to avoid a second,
  possibly-drifting source of truth — the git history on both
  `com-junkawasaki/root` and `kotoba-lang/industry` `main` is the
  authoritative record).
