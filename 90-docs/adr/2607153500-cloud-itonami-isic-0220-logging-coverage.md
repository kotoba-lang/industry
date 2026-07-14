# ADR-2607153500: cloud-itonami-isic-0220 (Logging) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607142200 (cloud-itonami-isic-0210 Silviculture coverage, closest domain analog)

## Context

ISIC class 0220 (Logging) is the felling/skidding/hauling sibling of
0210 (Silviculture): back-office coordination of harvest-record data
logging, field-operation scheduling, safety-concern flagging, and
supply procurement for a logging operation.

A prior attempt scaffolded the `cloud-itonami-isic-0220` repo (commit
`9894cca`, "Initial scaffold: LoggingAdvisor ⊣ Logging Coordination
Governor actor") but shipped only
`src/logging/{advisor,governor,operation,phase,sim,store}.cljc` and a
single test file, with NO `deps.edn` and NO `registry.cljc` — the
`kotoba-lang/industry` registry entry for `"0220"` records this
explicitly: "REVERTED 2026-07-14: missing deps.edn AND missing
registry.cljc/facts.cljc -- cannot verify test claims without
deps.edn. Needs a verified from-scratch redo." That prior attempt also
used `System.currentTimeMillis` (JVM-only, not cljs-portable) and a
different, unspecified op set
(`:schedule-crew-dispatch`/`:flag-safety-hazard`/`:coordinate-timber-
shipment`) than this build's spec. This ADR and the underlying
implementation are that from-scratch redo.

Like 0210, this vertical is SELF-CONTAINED — no `kotoba-lang/logging`
library exists, so domain logic (site/permit verification, permit-
allowance recompute, species validation, supply-budget verification)
lives as pure functions in `logging.registry` and is re-verified
independently by the governor, mirroring the discipline established by
0210's `forestry.registry` and every prior sibling actor.

## Decision

Complete `cloud-itonami-isic-0220` as a governed-actor implementation
of the logging blueprint, following the langgraph StateGraph +
independent Governor + Phase 0->3 rollout architecture established
across the fleet:

1. **LoggingAdvisor** (`logging.advisor`, sealed intelligence node):
   proposes coordination actions only, never commits
   - `:log-harvest-record` — timber volume/species/site harvest data logging (administrative, not an operational decision)
   - `:schedule-field-operation` — felling/skidding/hauling scheduling proposal
   - `:flag-safety-concern` — surface a terrain/weather/equipment-hazard concern (always escalates)
   - `:order-supplies` — fuel/equipment/permit-fee procurement proposal

2. **Logging Coordination Governor** (`logging.governor`, independent
   validation layer, never trusts the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into nine concrete checks): site/permit record must be
     independently verified/registered (`:verified?` AND permit
     `:issued`) before any action; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct felling/skidding-equipment control);
     `:finalize? true` on a field-operation schedule (finalizing a
     harvest-cut plan) is a PERMANENT block; a `:felling` operation may
     not push the site's own recorded harvest volume past its own
     permitted allowable-cut (independently recomputed); no
     double-scheduling the same field-operation record; no fabricated
     `:species` value; a supply order's claimed total must
     independently recompute correctly
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; supply orders whose
     independently-recomputed total exceeds a cost threshold; low
     confidence

3. **Scope boundary** (critical, safety-critical domain — heavy
   felling/skidding equipment, falling timber):
   - Does NOT control felling equipment, skidders, feller-bunchers, or loaders directly
   - Does NOT make crew-safety or site-hazard decisions (exclusive to the human logging crew supervisor/forester)
   - Does NOT authorize/finalize a harvest-cut plan (permanently blocked, not a
     rollout milestone still to come — see `logging.phase`:
     `:schedule-field-operation` is never a member of any phase's `:auto`
     set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `logging.registry` pure functions
   (`site-permit-ready?`, `permit-allowance-exceeded?`, `species-
   valid?`, `order-total-matches-claim?`/`order-exceeds-threshold?`)
   are re-verified independently by the governor, following the
   "ground truth, not self-report" discipline established by prior
   actors (most directly `cloud-itonami-isic-0210`'s
   `forestry.registry`).

5. **Store** (`logging.store`): a single `MemStore` backend behind a
   `Store` protocol. Like 0210, this build does NOT ship a second
   Datomic-backed store — this vertical's SSoT needs no
   jurisdiction-scoped parity requirement driving one, and a second
   backend can be added later behind the same protocol without changing
   any caller.

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop — the prior REVERTED attempt used
   `System.currentTimeMillis`), langgraph-clj StateGraph (invoked via
   `langgraph.graph/run*`, not `.invoke`), append-only audit ledger, full
   test coverage, demo driver. Full module set: `deps.edn`,
   `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later), `README.md`,
   `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
   `SECURITY.md`. All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-0220` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Logging back-office coordination is now genuinely implemented and
tested (not merely scaffolded). ISIC 0220 moves from
`:spec`/reverted-attempt to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or harvest-cut-plan finalization, independently corroborated
by `logging.phase`'s permanent exclusion of `:schedule-field-operation`
from every phase's `:auto` set.

(+) The permit-allowance recompute (`logging.registry/permit-
allowance-exceeded?`) is a genuinely logging-specific ground-truth
check beyond a straight port of 0210's stand-maturity check: it
independently re-derives whether a felling proposal's own claimed
volume would exceed the site's own recorded permitted allowable cut,
never trusting the proposal's self-reported volume.

(+) The repo is standalone (forkable outside the workspace), matching the
pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are now present and exercised by 70 tests / 175 assertions
across 5 test namespaces (`logging.operation-test`,
`logging.governor-contract-test`, `logging.phase-test`,
`logging.store-contract-test`, `logging.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real permit
registries/equipment telemetry/crew-location systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation and the supply-order cost threshold are
simplified placeholders; a real deployment would tie these to
domain-specific thresholds (hazard-severity classification, equipment
cost tiers, etc.).

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-0220` repo: full module set (governor/store/
  advisor/registry/operation/phase/sim + deps.edn + blueprint.edn +
  LICENSE + governance docs) completed and pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-0220`, merge commit
  `e39e6558713899ede4f1d41b97c60a236500921f` (parent `9894ccaa`, the
  prior REVERTED attempt's scaffold-only commit; build commit
  `ae61b284b502d69dbfa55a7bba49851030919dab`).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 70 tests containing 175 assertions. 0 failures, 0 errors.`** —
  verified both immediately after push and again from an independent
  fresh clone at the merge commit.
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  site-not-verified/permit-pending, permit-allowance-exceeded,
  harvest-finalize-blocked, already-scheduled, invalid-species,
  order-total-mismatch), and the ESCALATE-not-HOLD over-threshold
  order-supplies case, with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:logging-coordination-
  governor` is grep-verified UNIQUE fleet-wide (GitHub code search
  across `org:cloud-itonami`, zero other hits).
- `kotoba-lang/industry` registry entry for `"0220"` updated in place
  from its reverted/`:spec` state to `:maturity :implemented` via an
  exact-text in-place edit of the single `{:id "0220" ...}` block (no
  wholesale regeneration), landed at merge commit
  `13155e9b3951d02b909c50127f60256b7149af87` (5th server-side-merge
  attempt; the first 4 hit genuine `409 Merge conflict` from heavy
  concurrent-agent contention on the shared `industry_test.clj`
  `:implemented`-count assertion line, resolved each time by
  re-fetching `origin/main` and reapplying the edit against the fresh
  tip, never by editing a conflict marker), plus a 1-line cosmetic
  indentation follow-up at merge commit
  `9a954dec2b6056a6dba192404d8d756c4f3e47ab`.
- The true fleet-wide `:implemented` count (computed via
  `kotoba.industry/maturity-summary`, not raw grep — grep undercounts
  entries whose maturity defaults from `:repo` presence, and the
  substring `":maturity :implemented"` is always present from OTHER
  entries so a whole-file substring check cannot detect this entry's
  own state) was 199 when first checked, but heavy concurrent-agent
  churn (multiple sibling ISIC promotions landing during the 409-retry
  window: 0112, 0322, 0893, 1030, and others) meant the count actually
  live-recomputed to 204 immediately before this promotion's
  successful 5th attempt, 205 after — NOT the naively-expected
  199 -> 200. `clojure -M:test`: **`Ran 15 tests containing 942
  assertions. 0 failures, 0 errors.`**, re-verified from an
  independent fresh clone (with a fresh `kotoba-lang/technology`
  sibling clone) at the final merge commit
  `9a954dec2b6056a6dba192404d8d756c4f3e47ab`. `grep -c "â"
  resources/kotoba/industry/registry.edn` = 0 (no file-wide UTF-8
  mojibake).
