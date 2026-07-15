# ADR-2607171200: cloud-itonami-isic-0142 (raising of horses and other equines) equine-facility-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607152100 (cloud-itonami-isic-0141 cattle-raising
coverage, direct module-shape template), ADR-2607122200 (ISIC Wave 3
food/agriculture coverage), ADR-2607011000 (actor pattern & ISIC section
coverage)

## Context

ISIC Rev. 4 class 0142 ("Raising of horses and other equines") had no
`cloud-itonami` implementation; the registry entry was `:maturity :spec`
with no repo. Registry entry verified fresh from a clean clone of
`kotoba-lang/industry` before any work began: `{:id "0142" :name "Raising
of horses and other equines" ...}` — matches the assigned scope exactly,
no mismatch (a prior fleet incident mislabeled 0892/0144 against their
actual registry names).

Equine-facility operations (herd count/weight/health-check/breeding data
logging, veterinary appointment coordination, animal health/welfare
concern escalation, feed/veterinary-supply/tack procurement) are
distinct from cattle/swine raising in one respect: this actor's closed
op-allowlist blocks not just direct treatment administration but also
any proposal to make a **breeding or culling decision** directly (horses
are commonly raised for breeding/working/riding purposes, not
principally for slaughter) — those decisions remain the stable
operator/veterinarian's exclusive authority, same as slaughter/culling
decisions are exclusively human in the cattle/swine actors. **Racing and
gambling activities are explicitly out of scope** — this actor covers
raising/breeding operations coordination only, with no corresponding op
in the closed allowlist for anything racing/wagering-related.

## Decision

Implement a complete equine-facility-operations-coordination actor
(`cloud-itonami-isic-0142`), mirroring `cloud-itonami-isic-0141`'s
(cattleops) / `cloud-itonami-isic-0145`'s (swineops) module shape
module-for-module, with the `equineops` namespace:

1. **`equineops.governor`** (Equine Facility Operations Governor) —
   independent constraint layer with HARD checks (always hold, no
   override):
   - `facility-not-registered` — request's facility-id must resolve to a
     registered facility/stable in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`;
     the governor never directly executes anything.
   - `treatment-or-breeding-culling-blocked` — `:administer-treatment`
     and `:order-breeding-culling-decision` are unconditionally,
     permanently blocked regardless of confidence or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `herd-count-invalid` — `:log-herd-record` with a non-positive count
     is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-animal-health-concern` — ALWAYS escalates, any confidence
     (e.g. suspected equine influenza, colic, strangles, laminitis).
   - `:order-supplies` above its category cost threshold (default 500;
     `equineops.facts/supply-categories` gives category-specific
     thresholds: feed/veterinary-supply 500, tack 1000).
   - low confidence (< 0.7).

2. **`equineops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (feed, veterinary-supply, tack),
   species classification (horse, donkey, mule), and a health-concern
   vocabulary (equine influenza, colic, strangles, laminitis) that is
   purely descriptive — citing any concern never changes the always-
   escalate disposition.

3. **`equineops.registry`** — independent, unconditional pure predicates:
   `cost-exceeds-threshold?`, `herd-count-non-positive?`,
   `confidence-below-floor?`.

4. **`equineops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-facility` lookup, `add-facility` for tests/simulation.

5. **`equineops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`equineops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`equineops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching cattleops/swineops's own stub status).

8. **`equineops.sim`** — demo runner (`clojure -M:run`), working `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-herd-record`, `:schedule-veterinary-visit`,
   `:flag-animal-health-concern`, `:order-supplies`.

10. **Tests** — 31 tests / 96 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0141/0145's own test coverage shape). Includes
    an additional `hard-violations-breeding-culling-blocked` test beyond
    the 0141/0145 shape, to independently verify the equine-specific
    blocked op.

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented
    `equineops.operation` `run-operation`/`build` API and
    `:commit`/`:escalate`/`:hold` dispositions. blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0141).

## Consequences

(+) Equine-raising (ISIC 0142) equine-facility-operations-coordination is
now genuinely implemented and fully tested.

(+) Scope boundaries (direct animal handling, veterinary treatment
decisions, breeding/culling decisions permanently excluded; racing/
gambling activities categorically out of scope) are hardcoded in
governor checks (`treatment-or-breeding-culling-blocked`, closed
op-allowlist) and documented in README, not just asserted in prose.

(+) Animal-welfare escalation (`:flag-animal-health-concern` always
human) is a core design invariant, not an add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs;
`clojure -M:lint` is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0141/0145's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0142`: `clojure -M:test` → "Ran 31 tests
  containing 96 assertions. 0 failures, 0 errors." Independently
  re-verified from a fresh clone of `origin/main` after push.
- `clojure -M:lint` → 0 errors, 0 warnings. `clojure -M:run` → demo
  runs end-to-end, returns `:disposition :escalate` as expected
  (phase-0 forces human review of all commits).
- Commit `2e9c7fe9c90a90f0b05051cf0563d5c302beccba` pushed to
  `cloud-itonami/cloud-itonami-isic-0142`'s `main` (new repo, initial
  commit; confirmed as `origin/main` tip via GitHub API
  `repos/.../commits/main`).
- Registry entry (`kotoba-lang/industry`) updated in place: `"0142"`
  entry's `:maturity` → `:implemented`, `:repo`/`:business-id` corrected
  from the never-created `gftdcojp/cloud-itonami-A0142` placeholder to
  the real `cloud-itonami/cloud-itonami-isic-0142`, ADR reference added.
  `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  225 → 226 (recomputed live via the test runner's own failure diff on
  the freshly re-fetched `origin/main`, not assumed — the pre-bump run
  failed with `expected: (= 225 (:implemented m)) actual: (not (= 225
  226))`, confirming the true post-edit count). Re-ran `clojure -M:test`
  on the exact edited `industry_test.clj` before commit: "Ran 15 tests
  containing 947 assertions. 0 failures, 0 errors."
- Landed via GitHub API server-side merge (`repos/kotoba-lang/industry/
  merges`, first attempt, no 409 encountered) onto `main`: merge commit
  `2fa72bcbdcc0e1525484b53eac35dfba95f3e949` (confirmed as `origin/main`
  tip via GitHub API `repos/.../commits/main`).
- Post-merge re-verification: fresh clone of `kotoba-lang/industry`'s
  `main` into a brand-new temp dir (plus a fresh `../technology` sibling
  clone) → `clojure -M:test` → "Ran 15 tests containing 947 assertions.
  0 failures, 0 errors." `grep -c "â" resources/kotoba/industry/
  registry.edn` → `0` (no UTF-8 mojibake).
