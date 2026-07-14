# ADR-2607154000: cloud-itonami-isic-0145 (raising of swine/pigs) swine-farm-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607152100 (cloud-itonami-isic-0141 cattle-raising
coverage, module-structure template), ADR-2607122200 (ISIC Wave 3
food/agriculture coverage), ADR-2607011000 (actor pattern & ISIC section
coverage)

## Context

`cloud-itonami/cloud-itonami-isic-0144` did not exist prior to this ADR
(verified via `gh repo view` before starting — no failed prior attempt to
recover from). The task assignment specified "ISIC class 0144 (Raising of
swine/pigs)". **This numeric code was incorrect**: per the authoritative
`kotoba-lang/industry` registry (`resources/kotoba/industry/registry.edn`,
which matches the real UN ISIC Rev. 4 standard), `"0144"` is **"Raising of
sheep and goats"** and `"0145"` is **"Raising of swine/pigs"**. This was
discovered mid-build, immediately before the registry-edit step, by
reading the registry's actual `0141`/`0144`/`0145` entries side by side
(0141 correctly matches "Raising of cattle and buffaloes" and its own
`cloud-itonami-isic-0141` repo, confirming the registry — not the task
label — is the reliable ground truth).

Rather than write incorrect data into the shared `kotoba-lang/industry`
registry (which would have required labeling `"0144"`, sheep/goats, as
`:implemented` with a swine-content repo/business-id — an internal
inconsistency, and the kind of defect this stricter verification protocol
exists to catch), the GitHub repo initially created as
`cloud-itonami-isic-0144` was **renamed to `cloud-itonami-isic-0145`**
(GitHub preserves history and sets up a redirect from the old name), all
in-repo ISIC-code references (`blueprint.edn`, `README.md`,
`docs/business-model.md`, `src/swineops/facts.cljc`) were corrected
0144→0145, and the fix was committed/pushed/re-verified before any
registry work touched `kotoba-lang/industry`. The registry's own `"0144"`
(sheep/goats) entry is untouched by this ADR — a distinct, unrelated ISIC
class for separate future coverage.

Built by reading `cloud-itonami-isic-0141` (raising of cattle and
buffaloes) in full as the module-structure template and independently
re-running `clojure -M:test` before and after every push, following the
same stricter verification protocol as the preceding batch of
ISIC-coverage actors in this rollout.

Swine-farm operations (ISIC Rev. 4 0145) span: herd count/weight/
health-check/farrowing record-keeping, veterinary appointment
coordination, animal health/biosecurity concern escalation (e.g.
suspected African Swine Fever — ASF), and feed/veterinary-supply/
biosecurity-equipment procurement. **CRITICAL exclusions**: direct animal
handling, veterinary treatment decisions, and slaughter/culling decisions
remain the exclusive authority of the farm operator/veterinarian — this
actor only coordinates back-office record-keeping and logistics, never
animal-domain actuation, and it never itself declares a disease outbreak
or contacts animal-health authorities.

## Decision

Implement a complete swine-farm-operations-coordination actor
(`cloud-itonami-isic-0145`), mirroring `cloud-itonami-isic-0141`'s
(`cattleops.*`) module shape module-for-module, adapted from
cattle-ranching to swine-farming (barn/pen management instead of pasture,
biosecurity concepts like ASF instead of generic disease):

1. **`swineops.governor`** (`SwineFarmOperationsGovernor`) — independent
   constraint layer with HARD checks (always hold, no override):
   - `facility-not-registered` — request's facility-id must resolve to a
     registered facility (barn/pen complex) in the Store.
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
   - `:flag-animal-health-concern` — ALWAYS escalates, any confidence
     (e.g. suspected ASF risk).
   - `:order-supplies` above its category cost threshold (default 500;
     `swineops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for biosecurity-equipment).
   - low confidence (< 0.7).

2. **`swineops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (feed, veterinary-supply,
   biosecurity-equipment), breed classification (landrace, duroc,
   yorkshire, berkshire), and a biosecurity/notifiable-disease reference
   vocabulary (ASF, CSF, FMD, PRRS) — purely descriptive, never changes
   the Governor's disposition since every flagged concern already always
   escalates.

3. **`swineops.registry`** — independent, unconditional pure predicates:
   `cost-exceeds-threshold?`, `herd-count-non-positive?`,
   `confidence-below-floor?`.

4. **`swineops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-facility` lookup, `add-facility` for tests/simulation.

5. **`swineops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`swineops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`swineops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching `cattleops.operation`'s own stub status).

8. **`swineops.sim`** — demo runner (`clojure -M:run`) with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-herd-record`, `:schedule-veterinary-visit`,
   `:flag-animal-health-concern`, `:order-supplies`.

10. **Tests** — 31 tests / 99 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/`sim_test`,
    matching 0141's own test coverage shape — one extra facts_test
    `deftest` vs. 0141 for the added breed/biosecurity-concern lookups).

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`swineops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata
    (`:isic-rev5 "0145"`); standard OSS files (CONTRIBUTING, GOVERNANCE,
    SECURITY, CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim
    from 0141).

## Consequences

(+) Swine-raising (ISIC 0145) swine-farm-operations-coordination is now
genuinely implemented and fully tested.

(+) The 0144/0145 code mislabel was caught before it could corrupt the
shared `kotoba-lang/industry` registry — the registry's `"0144"` (sheep
and goats) entry is untouched, and `"0145"` (swine/pigs) now correctly
points at the real, tested actor repo.

(+) Scope boundaries (direct animal handling, veterinary treatment
decisions, slaughter/culling decisions permanently excluded) are
hardcoded in governor checks (`treatment-or-slaughter-blocked`) and
documented in README, not just asserted in prose.

(+) Animal-health/biosecurity escalation (`:flag-animal-health-concern`
always human, e.g. suspected ASF) is a core design invariant, not an
add-on — and the actor is structurally incapable of declaring an
outbreak or contacting authorities on its own.

(+) Portable `.cljc` implementation with no JVM-only constructs;
`clojure -M:lint` is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0141's own stub status; production
integration pending.

(-) ISIC 0144 (Raising of sheep and goats) remains unimplemented
(`:spec`, `gftdcojp/cloud-itonami-A0144` placeholder) — genuinely a
different, separate ISIC class from this ADR's scope, left for a future
agent/ADR.

## Verification

- `cloud-itonami-isic-0145` (created as `cloud-itonami-isic-0144`, then
  `gh repo rename`d to the correct code before any registry work):
  `clojure -M:test` → "Ran 31 tests containing 99 assertions. 0 failures,
  0 errors." Independently re-verified from a **fresh clone** of
  `origin/main` after the ISIC-code-correction push (commit `cf69c67`,
  fast-forward from initial scaffold commit `86d8605`). `clojure -M:lint`
  → 0 errors, 0 warnings (also confirmed on the fresh clone). `clojure
  -M:run` → demo runs end-to-end, returns `:disposition :escalate` as
  expected (phase-0 forces human review of all commits).
- Registry entry (`kotoba-lang/industry`): `"0145"` entry's `:maturity`
  `:spec` → `:implemented`, `:repo`/`:business-id` corrected to
  `cloud-itonami/cloud-itonami-isic-0145`, ADR reference added; `"0144"`
  entry left byte-for-byte untouched (verified via `git diff` scoping
  before push). `industry_test.clj`'s hardcoded `:implemented` count was
  live-recomputed immediately before each landing attempt (not assumed):
  205 (fetched baseline) → two concurrent-agent 409s during landing
  (`cloud-itonami-isic-0114` sugar-cane and `cloud-itonami-isic-3700`
  sewerage landed first, then `cloud-itonami-isic-1610` sawmilling landed
  during a third attempt) → final count 209, landed via GitHub API
  server-side merge commit `16d3c1e31903ae8ba0c4bda296bd5a4db9d0df69`
  onto `kotoba-lang/industry`'s `main` (two 409 retries, each resolved by
  a fresh `origin/main` cherry-pick + conflict resolution, never a
  rebase). Post-merge, from a **completely fresh clone** of
  `kotoba-lang/industry`'s `main` (with a fresh `../technology` sibling
  clone) → "Ran 15 tests containing 943 assertions. 0 failures, 0
  errors." `grep -c "â" resources/kotoba/industry/registry.edn` → `0`
  (no UTF-8 mojibake).
