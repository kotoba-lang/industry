# ADR-2625000000: cloud-itonami-isic-9820 (undifferentiated service-producing activities of private households for own use) household time-use-survey-programme coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (ISIC/ISCO reverse-toposort wave plan),
ADR-2607152500 (Wave 4 rollout-start amendment, P3/P4 parallel), the
`cloud-itonami-isic-873` (residential elder/disabled care) module-structure
template this ADR mirrors module-for-module.

## Context

ISIC Rev.4 class 9820 ("Undifferentiated service-producing activities of
private households for own use") is a UN System of National Accounts
bookkeeping category, not a market industry. It exists to count
own-account, non-market **service** production a household performs for
itself -- unpaid domestic and care work (cooking, cleaning, childcare,
eldercare, and similar) -- in satellite national accounts for statistical
completeness. It is not part of GDP and there is no real-world "business"
that IS a 9820 entity, unlike ordinary ISIC classes (a restaurant IS an
ISIC 5610 entity; there is no equivalent for 9820). Registry pre-check
confirmed the registry's `:name` for this entry was truncated
(`"Undifferentiated service-producing activities of private ho..."`), a
pre-existing seed-data bug affecting roughly 10% of entries; the full name
is corrected as part of this ADR's registry edit. A separate, redundant
3-digit group entry `{:id "982" ...}` exists in the registry as an
artifact of an earlier data-seeding pass and is intentionally left
untouched -- only the `"9820"` class-level block is in scope here.

Given this residual/statistical nature, inventing a fictitious commercial
"9820 business" (a marketplace for unpaid household labor, a payment
system for domestic chores, etc.) would misrepresent the class. Instead,
following the same honest-framing approach expected of the sibling ISIC
9810 class (own-account **goods** production by households, built
separately and not depended on here), this actor is scoped as an
**operations-coordination actor for a structured unpaid-household-work
time-use survey/tracking programme** -- the genuine, precedented activity
a national statistics office runs to measure the care economy (UN
Statistics Division / OECD time-use survey methodology): registering
participating households, logging their self-reported time-use-diary
data, scheduling enumerator household visits, coordinating programme
support (survey materials, enumerator training), and flagging
domestic-safety/child-elder-welfare concerns an enumerator observes during
a visit for human triage. This is a data-coordination/statistical-support
use case, not a marketplace or an employer of household labor.

This build is part of the cloud-itonami Wave 4 (human-services /
person-facing) fleet under ADR-2607152500's amended P3/P4-parallel
schedule, and inherits that amendment's mandatory guardrail for
person-facing Wave 4 satellites: the closed proposal-op allowlist must
never include anything that directly finalizes a decision touching
personal/domestic safety or welfare, and any concern-flagging op must
always escalate to human sign-off, never appearing in any phase's `:auto`
set.

### A known self-tripping bug class in this actor family, addressed proactively

Multiple sibling actors in this same cloud-itonami-isic-* fleet have
independently discovered and fixed the same defect: a governor's
scope-exclusion term list phrased as a bare noun (e.g. `"welfare"`)
accidentally matches inside the mock advisor's own DEFAULT rationale/
disclaimer text for a legitimate, allowed proposal (which must be free to
say things like "welfare concern" while explicitly disclaiming that it
does not finalize any intervention), causing the actor to self-block its
own happy path. Verified during this build: the `eldercareops` reference
repo (`cloud-itonami-isic-873`, used as this ADR's module-structure
template) still carries an unnoticed live instance of exactly this bug --
its `scope-excluded-terms` includes the bare term `"薬"` (Japanese for
"medicine/drug"), which matches inside its own `:coordinate-supply-request`
advisor rationale text ("...非医薬品消耗品の調達調整のみ。投薬なし。", i.e.
"non-medicinal consumables only. No medication.") and causes that op's
default, legitimate proposal to spuriously HARD-hold -- reproduced with a
short REPL script against a fresh clone of that repo (`gov/check` on
`adv/infer`'s own output returns `:hard? true` with `:scope-excluded` for
`:coordinate-supply-request`, `:hard? false` for the other four ops), even
though `cloud-itonami-isic-873`'s own test suite passes because it never
pipes `advisor/infer`'s literal output through `governor/check` for that
op (its hand-built `governor-test` proposals all use a neutral rationale
string, and `advisor-test` never calls `governor/check`). This ADR does
NOT fix `cloud-itonami-isic-873` (out of scope for this ADR/actor); it is
recorded here as the concrete evidence motivating this actor's own design
choice below.

## Decision

Implement `cloud-itonami-isic-9820` (`timeuseops.*` namespace), mirroring
`cloud-itonami-isic-873`'s module shape module-for-module (Store, Advisor,
Governor, Phase, Operation, Sim -- no separate Facts/Registry namespaces,
matching the simpler reference template specified for this build):

1. **`timeuseops.store`** -- `Store` protocol + in-memory `MemStore`,
   String-keyed `households` directory (`:household-id` string key, never
   keyword), append-only `ledger` + `coordination-log`.

2. **`timeuseops.advisor`** -- `Advisor` protocol + `mock-advisor`, four
   proposal generators, all `:effect :propose`, all disclaiming
   finalization/execution authority in their own rationale text without
   ever using the finalization-ACTION phrases the governor scans for (see
   guardrail below).

3. **`timeuseops.governor`** (`TimeUseProgrammeGovernor`) -- independent
   compliance layer with FOUR HARD checks (always hold, no override):
   - `household-unverified` -- request's household-id must resolve to an
     independently `:registered?`/`:verified?` household in the store
     (never trusts the proposal's own claim).
   - `effect-not-propose` -- every proposal's `:effect` must be
     `:propose`; any other value is a HARD block.
   - `op-not-allowed` -- closed four-op allowlist enforced independently
     of the advisor's own claim.
   - `welfare-intervention-finalization-blocked` -- **the mandatory Wave
     4 guardrail**: any proposal (regardless of op) whose content uses
     finalization/execution-ACTION language for a welfare-intervention
     decision (finalizing/authorizing/executing a child- or elder/adult-
     protective action, a custody removal, or a protective-order
     issuance) is HARD, PERMANENT-blocked. This closed op-allowlist never
     includes any op that directly finalizes a welfare-intervention
     decision -- `:flag-welfare-concern` only ever surfaces a concern for
     human triage.

   **Guardrail against the self-tripping bug documented above**: every
   term in `welfare-intervention-finalization-terms` is phrased as the
   finalization/execution ACTION (`"finalize welfare intervention"`,
   `"authorize custody removal"`, `"issue a protective order"`, ...),
   never as a bare noun (`"welfare"`, `"child"`, `"custody"` alone are
   never scanned-for on their own). A dedicated regression test,
   `timeuseops.governor-test/default-advisor-proposals-never-self-trip-
   scope-exclusion`, runs the default mock advisor's own proposal for
   every one of the four allowed ops (including `:flag-welfare-concern`
   itself, whose legitimate rationale explicitly discusses "福祉懸念"/
   welfare concerns) through the governor and asserts none of them is
   ever HARD-blocked by `op-not-allowed` or
   `welfare-intervention-finalization-blocked`.

   ESCALATION invariants (always human sign-off, never auto-commit at any
   phase):
   - `:flag-welfare-concern` -- ALWAYS escalates, any confidence; never a
     member of any phase's `:auto` set (`timeuseops.phase` independently
     enforces the same invariant, two layers not one).
   - `:coordinate-programme-support` whose draft `:value :cost` exceeds
     `support-cost-threshold` (300) -- escalates for human budget sign-off.
   - Confidence below `confidence-floor` (0.6) -- always escalates.

4. **`timeuseops.phase`** -- 0->3 rollout gate: phase 0 read-only; phase 1
   time-use-record logging only (approval-gated); phase 2 adds
   survey-visit scheduling and programme-support coordination
   (approval-gated); phase 3 auto-commits clean, high-confidence,
   under-threshold proposals for the three non-welfare-concern ops --
   `:flag-welfare-concern` is deliberately absent from every phase's
   `:auto` set, including phase 3, permanently.

5. **`timeuseops.operation`** -- langgraph-clj StateGraph composing
   intake -> advise -> govern -> decide -> commit | hold | request-approval,
   with `interrupt-before #{:request-approval}` for real human-in-the-loop
   resume (not a stub -- matches `cloud-itonami-isic-873`'s fully-wired
   StateGraph, not the deferred-wiring stub some other Wave 3/4 siblings
   use).

6. **`timeuseops.sim`** -- demo runner (`clojure -M:run`) walking clean
   auto-commit paths, the over-threshold escalation, the always-escalate
   welfare-concern flag, and all four HARD-hold scenarios (unregistered
   household, unverified household, non-`:propose` effect, and the
   welfare-intervention-finalization poison-injection test hook).

7. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-time-use-record`, `:schedule-survey-visit`,
   `:coordinate-programme-support`, `:flag-welfare-concern`.

8. **Tests** -- 45 tests / 137 assertions green: `advisor-test`,
   `governor-test` (including the dedicated self-trip regression test and
   positive-detection tests for every HARD check and both escalation
   gates), `phase-test`, `governor-contract-test` (full-graph integration,
   including an over-threshold programme-support escalation flow),
   `store-contract-test`.

9. **Documentation** -- README.md with an explicit "Scope" section stating
   the residual/statistical framing honestly (own-account non-market
   production, not a marketplace, parallels the sibling ISIC 9810 goods
   class without depending on its code); blueprint.edn metadata; standard
   OSS files (CONTRIBUTING, GOVERNANCE, SECURITY, CODE_OF_CONDUCT --
   written fresh for this actor's actual scope, not copied verbatim from
   an unrelated domain: the `cloud-itonami-isic-873` reference repo's own
   GOVERNANCE.md/CONTRIBUTING.md were found to still describe a different,
   unrelated ISIC 0520 lignite-mining actor, a copy-paste artifact this
   ADR's build deliberately avoided repeating); AGPL-3.0-or-later LICENSE
   (copied verbatim, generic license text with no repo-specific content).

## Consequences

(+) ISIC 9820 now has an honest, precedented real-world framing (a
national-statistics-office time-use survey programme) instead of a
fabricated marketplace business, documented explicitly in the README so
future readers are not misled about what this actor coordinates.

(+) The mandatory Wave 4 person-facing guardrail is hardcoded in a
dedicated, independently-testable governor rule
(`welfare-intervention-finalization-blocked`) with terms phrased to avoid
the self-tripping bug class this fleet has repeatedly hit, verified via a
dedicated regression test plus positive-detection tests confirming the
block still fires on genuinely poisoned content.

(+) `:flag-welfare-concern` is structurally excluded from every phase's
auto-commit set, enforced independently by both the governor
(`always-escalate-ops`) and the phase gate (`phases`), so no future
rollout-phase change can silently make it auto-commit-eligible.

(+) Portable `.cljc` source, zero JVM-only constructs; `clj-kondo` 0
errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up;
tests/demo use in-memory `MemStore`.

(-) This ADR documents but does not fix the `cloud-itonami-isic-873`
self-tripping bug found during research (bare-noun `"薬"` term matching
its own `:coordinate-supply-request` advisor rationale) -- that repo is
out of scope for this ADR; flagging it here so a future pass on that
specific repo has a concrete repro.

## Verification

- `cloud-itonami-isic-9820`: `clojure -M:test` -> "Ran 45 tests containing
  137 assertions. 0 failures, 0 errors." `clojure -M:lint` -> 0 errors, 0
  warnings. `clojure -M:run` -> demo runs end-to-end; the
  welfare-intervention-finalization poison scenario correctly returns
  `:disposition :hold` with `:violations [{:rule
  :welfare-intervention-finalization-blocked ...}]`.
- Independently re-verified from a fresh clone of `origin/main` after
  push: same "Ran 45 tests containing 137 assertions. 0 failures, 0
  errors."
- Commit pushed to `cloud-itonami/cloud-itonami-isic-9820`'s `main` (see
  this ADR's `.edn` sidecar for the exact SHA).
- Registry entry (`kotoba-lang/industry`) update: `"9820"` entry's
  `:maturity` `:spec` -> `:implemented`, `:name` corrected from its
  truncated seed-data form to the full "Undifferentiated service-producing
  activities of private households for own use", `:repo`/`:business-id`
  corrected from the stale placeholder
  (`https://github.com/gftdcojp/cloud-itonami-T9820`) to
  `https://github.com/cloud-itonami/cloud-itonami-isic-9820` /
  `cloud-itonami-isic-9820`. `test/kotoba/industry_test.clj`'s pinned
  `:implemented` count recomputed live via `(kotoba.industry/maturity-
  summary)` immediately before commit (not assumed from a stale comment)
  and bumped accordingly -- see this ADR's `.edn` sidecar for the exact
  before/after counts and the registry merge commit SHA.
