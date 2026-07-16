# ADR-2626000000: cloud-itonami-isic-7722 (Renting of video tapes and disks) video-rental-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse-toposort
wave plan), ADR-2607152500 (Wave 4 rollout-start amendment to
ADR-2607121000's P3->P4 sequencing gate, defining the person-facing-
service safety guardrail this actor is built under), ADR-2607121200
(Wave 0 cljs-first safety kernel), skill `build-actor` (advisor/
governor/StateGraph/audit-ledger actor pattern). Mirrors
`cloud-itonami-isic-5913` (Motion picture, video and television
programme distribution activities), whose `filmdistops.*` module shape
this ADR follows module-for-module as `vidrentalops.*`.

## Context

ISIC Rev.4 class 7722 covers renting of video tapes and disks -- a
video/disk rental storefront (checkout/return desk operations, new-
release inventory restocking, customer service, supplier procurement
coordination). Before scaffolding, the live `kotoba-lang/industry`
registry entry for `"7722"` was independently verified fresh (not
assumed from this task's premise) by cloning the registry repo directly:
its `:name` field read `"Renting of video tapes and disks"`, an exact
match. No existing repo was found at `cloud-itonami/cloud-itonami-isic-
7722` (`gh api` 404) or at the registry's placeholder `:repo`
(`https://github.com/gftdcojp/cloud-itonami-N7722`, itself a bogus
placeholder -- wrong org, wrong id shape, never a real repo, also
confirmed 404), so this is a fresh scaffold, not a repair.

A video/disk rental business touches two person-facing-service risk
dimensions squarely within ADR-2607152500's Wave 4 guardrail: **age-
rating admission enforcement** (whether a customer may check out an
age-restricted title) and **consumer-liability decisions** (who owes
what for a lost or damaged rental item). Both are decisions this actor
must coordinate the back office around, never make itself. Per
ADR-2607152500, the closed op allowlist must never include an op that
directly finalizes a content-rating admission override or a damage-
liability determination -- those are always either a hard permanent
block or an always-escalate op, never auto-commit-eligible; any "flag a
concern" op must always escalate to human sign-off, never appearing in
any phase's `:auto` set.

This fleet has independently discovered and re-fixed the same recurring
bug class across multiple Wave 4 actors: a governor's own
`scope-excluded-terms` list phrased as a bare noun (e.g. "rating",
"liability") can accidentally match inside the mock advisor's own
default rationale/disclaimer text for a legitimate, allowed proposal --
self-blocking the actor on its own happy path. This is a live risk here
because `:flag-customer-concern`'s entire purpose is to talk *about*
age-rating admission doubts and damage/liability disputes using exactly
those bare nouns.

## Decision

Implement `cloud-itonami-isic-7722` with Store, Advisor, Governor,
Phase, Operation, Sim (mirrors `cloud-itonami-isic-5913`'s
`filmdistops.*` module shape module-for-module, renamed to
`vidrentalops.*`):

- **`vidrentalops.store`** -- `Store` protocol + `MemStore`. String-keyed
  `accounts` directory (`:account-id`/`:name`/`:registered?`/
  `:verified?`, representing a rental-desk/branch operating account),
  append-only `ledger`, append-only `rental-log`.
- **`vidrentalops.advisor`** -- `Advisor` protocol + `mock-advisor`. Four
  proposal generators, all `:effect :propose`: `:log-rental-record`
  (checkout/return/inventory-condition metadata logging),
  `:schedule-restocking-operation` (new-release/inventory-restock
  scheduling proposal), `:flag-customer-concern` (age-rating admission
  doubt / damage-loss dispute / fraud-suspicion surfacing),
  `:coordinate-supply-order` (supplier inventory-procurement
  coordination).
- **`vidrentalops.governor`** (`VidRentalGovernor`) -- independent
  compliance layer, two HARD checks (always hold, un-overridable):
  1. `account-unverified` -- target rental-account/inventory record
     must exist AND be independently `:registered?`/`:verified?` in the
     store, never trusting the proposal's own claim.
  2. `effect-not-propose` -- any `:effect` other than `:propose` is a
     claim to directly actuate/commit outside governance.
  3. `scope-excluded`/`op-not-allowed` -- ANY proposal (regardless of
     op) attempting to finalize a content-rating admission override, or
     finalize a damage-liability determination, is permanently blocked;
     an op outside the closed four-op allowlist is folded into the same
     check.

  **Self-trip discipline (per this fleet's known bug class)**:
  `scope-excluded-terms` are phrased as the finalization/execution
  ACTION -- `"finalize the age-rating admission override"`, `"finalize
  the liability determination"`, `"grant admission despite the
  rating"`, Japanese equivalents (`"損害賠償責任を確定"` etc.) -- never as
  a bare noun ("rating", "age", "liability", "damage"). A dedicated
  regression test (`default-mock-advisor-proposals-never-self-trip-
  scope-exclusion` in `governor_test.clj`) asserts all four default
  proposal generators, for a clean registered+verified account, never
  trip `:scope-excluded` and never HARD-hold.

  ESCALATE (always human sign-off): `:flag-customer-concern` always
  escalates (`always-escalate-ops`), independently confirmed by
  `vidrentalops.phase` never including it in any phase's `:auto` set;
  low confidence (< 0.6) also escalates.
- **`vidrentalops.phase`** -- 0->3 rollout: phase 0 read-only; phase 1
  `:log-rental-record` only, approval-gated; phase 2 adds
  `:schedule-restocking-operation`/`:coordinate-supply-order`, still
  approval-gated; phase 3 auto-commits the three non-concern ops when
  governor-clean and confident, `:flag-customer-concern` still always
  escalates.
- **`vidrentalops.operation`** -- real `langgraph-clj` `StateGraph`
  (intake -> advise -> govern -> decide -> commit|hold|request-approval),
  `interrupt-before #{:request-approval}` for human-in-the-loop resume,
  mirroring `filmdistops.operation` exactly (not a stub).
- **`vidrentalops.sim`** -- demo runner (`clojure -M:run`) walking the
  full happy path (all four ops at phase 1 then phase 3), the always-
  escalate customer-concern flow, and every HARD-hold scenario
  (unregistered account, unverified account, non-`:propose` effect,
  out-of-scope drift).
- Tests: `store_contract_test`, `advisor_test`, `governor_test` (incl.
  the mandatory self-trip regression), `phase_test`,
  `governor_contract_test` -- 40 tests / 119 assertions, all green.
- All `.cljc` (portable, no JVM-only interop). AGPL-3.0-or-later.
  README/GOVERNANCE/CONTRIBUTING/SECURITY/CODE_OF_CONDUCT all written
  fresh for this domain.

## Scope exclusions (hardcoded in governor checks, not just prose)

- Finalizing a content-rating admission override (waiving or
  overriding an age-rating/content-rating check to admit a rental) --
  always a hard, permanent block.
- Finalizing a damage-liability determination (who owes what for lost
  or damaged rental inventory) -- always a hard, permanent block.
- Payment capture/refund, contract negotiation, and binding
  rental-agreement execution -- out of scope, not represented in the
  closed op allowlist at all.

## Consequences

(+) ISIC 7722 video-rental-operations-coordination is genuinely
implemented and fully tested.

(+) The content-rating-admission-override and damage-liability-
determination exclusions are hardcoded in governor checks
(`scope-exclusion-violations`), not just asserted in README prose.

(+) The self-trip bug class this fleet has repeatedly encountered is
fixed by construction (action-phrased terms) and covered by a
dedicated regression test, not just avoided by luck.

(+) `:flag-customer-concern` always-escalate is a two-layer invariant
(governor `always-escalate-ops` + phase's permanent absence from every
`:auto` set), matching every sibling Wave 4 actor's own safety
discipline.

(+) Portable `.cljc`, zero JVM-only constructs; `clojure -M:lint` is 0
errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up;
tests use in-memory `MemStore`, matching every sibling actor's current
maturity.

(-) `vidrentalops.advisor`'s `mock-advisor` is deterministic, not a
real LLM call; the `Advisor` protocol seam is ready for that swap but
it is not wired in this ADR.

## Verification

- `cloud-itonami-isic-7722`: `clojure -M:test` -> "Ran 40 tests
  containing 119 assertions. 0 failures, 0 errors." Independently
  re-run from a brand-new fresh clone (`git clone --depth 1`) with the
  identical result. `clojure -M:lint` -> 0 errors, 0 warnings.
  `clojure -M:run` demo runs end-to-end: `:log-rental-record`/
  `:schedule-restocking-operation`/`:coordinate-supply-order`
  auto-commit at phase 3, escalate at phase 1; `:flag-customer-concern`
  escalates and then commits after approval; unregistered/unverified
  account, non-`:propose` effect, and out-of-scope-drift scenarios all
  HARD-hold as designed (spot-checked via disposition extraction from
  the demo output and the three distinct `:rule` violation types
  observed: `:account-unverified`, `:effect-not-propose`,
  `:scope-excluded`).
- Commit `8711e8cfbb53a8c0f85b362db1213e7b508c9384` pushed to
  `cloud-itonami/cloud-itonami-isic-7722`'s `main` (the repo's only
  commit; fresh `gh repo create`, no prior history).

## Verification addendum: registry promotion (Step 7, landed)

- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`:
  `"7722"` entry promoted `:spec` -> `:implemented` (also
  de-placeholdered `:repo`/`:business-id` from the stale
  `gftdcojp/cloud-itonami-N7722` to `cloud-itonami/cloud-itonami-isic-
  7722`, trimmed `:required-technologies` from the stale `[:robotics
  :identity :forms :dmn :bpmn :audit-ledger :labor]` placeholder set to
  the coordination-only shape actually implemented `[:identity :forms
  :dmn :bpmn :audit-ledger]` matching sibling `cloud-itonami-isic-5913`,
  updated `:operating-states` from the stale telecom-flavored
  placeholder to `[:intake :advise :govern :approve :commit :audit]`
  matching the actor state machine). Landed via a Contents-API
  single-file PUT (sha-checked optimistic concurrency); the first PUT
  attempt hit a concurrent sha-drift on this exact file (a sibling
  agent's own promotion of a *different* registry entry landed between
  the pre-PUT fetch and the PUT), immediately re-fetched fresh content
  and rebuilt the edit before retrying per this fleet's hot-contention
  discipline -- the second attempt succeeded, commit
  `e78644b95402a5e074a7f01a5820e14f4560afbd`. Exact-block edit verified
  via a prefix/suffix byte-identity scan (single contiguous changed
  region matching only the intended fields; everything outside the
  target block, including sample entries `5913`/`873`/`6310`/`5811`,
  confirmed unchanged both by the scan and by direct re-fetch).
- `test/kotoba/industry_test.clj`: dedicated corroboration `testing`
  block added for `"7722"`. This file and `registry.edn` are both
  extremely hot, high-concurrency shared files across the fleet --
  three consecutive sha-drift retries were needed (each time
  re-fetching fresh content, rebuilding the edit against a dynamically
  located "final implemented-count assertion" anchor rather than a
  hardcoded prior count, and re-validating `clojure -M:test` green
  before the next PUT attempt) before landing on the fourth attempt,
  commit `61b544eba533cf9f7d13ef00e3026089e49c2218`. The pinned
  `:implemented` count assertion required no delta at landing time
  (recomputed live via `(kotoba.industry/maturity-summary)` on a fresh
  `git reset --hard origin/main` immediately before each attempt): it
  was `392` at the successful attempt, already reflecting this
  promotion's own `+1` plus numerous concurrent sibling promotions
  landed in the same window (not individually re-narrated here, their
  own responsibility to corroborate).
- Post-merge re-verification from a brand-new fresh clone of
  `kotoba-lang/industry` `main` (plus a fresh `../technology` sibling
  clone): `clojure -M:test` -> "Ran 15 tests containing 1053
  assertions. 0 failures, 0 errors." `clojure -M:lint` -> 0 errors, 0
  warnings. No mojibake detected in `registry.edn`. `"7722"`'s own
  entry independently re-confirmed `:implemented` with the correct
  `:repo`/`:business-id`/`:required-technologies`/`:operating-states`,
  and sample entries `5913`/`873`/`6310`/`5811` also confirmed intact.
  Live `(kotoba.industry/maturity-summary)` at this final
  re-verification: `{:total 649 :spec 233 :blueprint 24 :implemented
  392}`.
