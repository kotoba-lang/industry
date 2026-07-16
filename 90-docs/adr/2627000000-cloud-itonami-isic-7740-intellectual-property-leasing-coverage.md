# ADR-2627000000: cloud-itonami-isic-7740 (leasing of intellectual property and similar products, except copyrighted works) IP-leasing-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse-toposort
wave plan), ADR-2607152500 (Wave 4 rollout-start amendment to
ADR-2607121000's P3->P4 sequencing gate), ADR-2607121200 (Wave 0
cljs-first safety kernel), skill `build-actor` (advisor/governor/
StateGraph/audit-ledger actor pattern). Mirrors
`cloud-itonami-isic-5913` (Motion picture, video and television
programme distribution activities), a verified Wave 4 actor whose
module structure this ADR follows module-for-module.

## Context

ISIC Rev.4 class 7740 covers leasing of intellectual property and
similar products, EXCEPT copyrighted works -- i.e. patents, trademarks,
and franchise rights; copyrighted works such as books, music, and film
are covered by different ISIC classes. Before scaffolding, the live
`kotoba-lang/industry` registry entry for `"7740"` was independently
verified fresh (not assumed from this task's premise) by cloning
`kotoba-lang/industry` from scratch: its `:name` field read `"Leasing
of intellectual property and similar products, exce..."` (truncated
with a literal `"..."`, a pre-existing seed-data bug affecting roughly
10% of registry entries) which unambiguously matches "Leasing of
intellectual property and similar products, except copyrighted works".
A separate, redundant 3-digit group entry `{:id "774" ...}` with a
similar truncated name sits at `:maturity :spec` and was deliberately
left untouched, per this fleet's caution against conflating
class-level and group-level registry entries. No existing repo was
found at `cloud-itonami/cloud-itonami-isic-7740` (`gh api` 404) or at
the registry's placeholder `:repo`
(`https://github.com/gftdcojp/cloud-itonami-N7740`, itself a
pre-existing bogus placeholder value -- wrong org, wrong id shape --
never a real repo), so this is a fresh scaffold, not a repair.

IP leasing touches licensing-grant and royalty-rate-determination
decisions, both squarely human/legal authority. Per ADR-2607152500's
Wave 4 person-facing-service guardrail, the closed op allowlist must
never include an op that directly finalizes either decision, and any
"flag a concern" op must always escalate to human sign-off, never
appearing in any phase's `:auto` set.

This batch's sibling agents have independently discovered and fixed the
same recurring bug class across several Wave 4 actors: a governor's
`scope-excluded-terms` list phrased as a bare noun (e.g. "license",
"royalty") can accidentally match inside the mock advisor's own default
rationale/disclaimer text for a legitimate, allowed proposal --
self-blocking the actor on its own happy path. This is a live risk here
because `:flag-infringement-concern`'s entire purpose is to talk *about*
licensing/infringement topics using exactly the bare nouns a naive term
list would ban, and `:log-license-record`/`:coordinate-royalty-reporting`
routinely disclaim "not a licensing-grant decision"/"not a royalty-rate
decision" in their own default rationale text. This build avoided the
bug from the start via a pre-write substring-collision scan (every
default advisor rationale/summary string checked against the full
`scope-excluded-terms` list before running any test), then independently
confirmed by a dedicated regression test.

## Decision

Implement `cloud-itonami-isic-7740` with Store, Advisor, Governor,
Phase, Operation, Sim (mirrors `cloud-itonami-isic-5913`'s
`filmdistops.*` module shape module-for-module, renamed to
`ipleaseops.*`):

- **`ipleaseops.store`** -- `Store` protocol + `MemStore`. String-keyed
  `licenses` directory (`:license-id`/`:name`/`:registered?`/
  `:verified?`), append-only `ledger`, append-only `licensing-log`.
- **`ipleaseops.advisor`** -- `Advisor` protocol + `mock-advisor`. Four
  proposal generators, all `:effect :propose`: `:log-license-record`
  (IP-portfolio/licensee/usage-data metadata logging),
  `:schedule-review-operation` (license-compliance-review scheduling
  proposal), `:flag-infringement-concern` (licensing-infringement/
  misuse concern surfacing), `:coordinate-royalty-reporting`
  (royalty-report/payment-tracking coordination).
- **`ipleaseops.governor`** (`IPLeaseGovernor`) -- independent
  compliance layer, three HARD checks (always hold, un-overridable):
  1. `license-unverified` -- target IP-portfolio/license-agreement
     record must exist AND be independently `:registered?`/
     `:verified?` in the store, never trusting the proposal's own
     claim.
  2. `effect-not-propose` -- any `:effect` other than `:propose` is a
     claim to directly actuate/commit outside governance.
  3. `scope-excluded`/`op-not-allowed` -- ANY proposal (regardless of
     op) attempting to finalize a licensing grant, or finalize a
     royalty-rate determination, is permanently blocked; an op outside
     the closed four-op allowlist is folded into the same check.

  **Self-trip discipline (the fix for this batch's known bug class)**:
  `scope-excluded-terms` are phrased as the finalization/execution
  ACTION -- `"finalize the licensing grant"`, `"set the royalty rate"`,
  `"grant the license"`, `"ライセンス許諾を確定"`, `"ロイヤルティ料率を確定"`
  -- never as a bare noun ("license", "licensing", "royalty", "rate").
  Every default advisor rationale/summary string was checked against
  this term list via a substring-collision scan before any test was
  run. A dedicated regression test
  (`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
  `governor_test.clj`) asserts all four default proposal generators,
  for a clean registered+verified license record, never trip
  `:scope-excluded` and never HARD-hold.

  ESCALATE (always human sign-off): `:flag-infringement-concern` always
  escalates (`always-escalate-ops`), independently confirmed by
  `ipleaseops.phase` never including it in any phase's `:auto` set;
  low confidence (< 0.6) also escalates.
- **`ipleaseops.phase`** -- 0->3 rollout: phase 0 read-only; phase 1
  `:log-license-record` only, approval-gated; phase 2 adds
  `:schedule-review-operation`/`:coordinate-royalty-reporting`, still
  approval-gated; phase 3 auto-commits the three non-concern ops when
  governor-clean and confident, `:flag-infringement-concern` still
  always escalates.
- **`ipleaseops.operation`** -- real `langgraph-clj` `StateGraph`
  (intake -> advise -> govern -> decide -> commit|hold|request-approval),
  `interrupt-before #{:request-approval}` for human-in-the-loop resume,
  mirroring `filmdistops.operation` exactly (not a stub).
- **`ipleaseops.sim`** -- demo runner (`clojure -M:run`) walking the
  full happy path (all four ops at phase 1 then phase 3), the always-
  escalate infringement-concern flow, and every HARD-hold scenario
  (unregistered license record, unverified license record, non-
  `:propose` effect, out-of-scope drift).
- Tests: `store_contract_test`, `advisor_test`, `governor_test` (incl.
  the mandatory self-trip regression), `phase_test`,
  `governor_contract_test` -- 40 tests / 119 assertions, all green.
- All `.cljc` (portable, no JVM-only interop). AGPL-3.0-or-later.
  README/GOVERNANCE/CONTRIBUTING/SECURITY/CODE_OF_CONDUCT all written
  fresh for this domain.

## Scope exclusions (hardcoded in governor checks, not just prose)

- Finalizing a licensing grant (who is licensed to practice a patent,
  use a trademark, or operate a franchise, under what terms) -- always
  a hard, permanent block.
- Finalizing a royalty-rate determination (locking in what rate a
  licensee actually pays) -- always a hard, permanent block.
- License-agreement negotiation, contract drafting, and any legally
  binding execution -- out of scope, not represented in the closed op
  allowlist at all.

## Consequences

(+) ISIC 7740 IP-leasing-operations-coordination is genuinely
implemented and fully tested.

(+) The licensing-grant and royalty-rate-determination exclusions are
hardcoded in governor checks (`scope-exclusion-violations`), not just
asserted in README prose.

(+) The self-trip bug class this batch repeatedly encountered is fixed
by construction (action-phrased terms, pre-write collision scan) and
covered by a dedicated regression test, not just avoided by luck.

(+) `:flag-infringement-concern` always-escalate is a two-layer
invariant (governor `always-escalate-ops` + phase's permanent absence
from every `:auto` set), matching every sibling Wave 4 actor's own
safety discipline.

(+) Portable `.cljc`, zero JVM-only constructs; `clojure -M:lint` is 0
errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up;
tests use in-memory `MemStore`, matching every sibling actor's current
maturity.

(-) `ipleaseops.advisor`'s `mock-advisor` is deterministic, not a real
LLM call; the `Advisor` protocol seam is ready for that swap but it is
not wired in this ADR.

## Verification

- `cloud-itonami-isic-7740`: `clojure -M:test` -> "Ran 40 tests
  containing 119 assertions. 0 failures, 0 errors." `clojure -M:lint`
  -> 0 errors, 0 warnings. `clojure -M:run` demo runs end-to-end: all
  four happy-path ops auto-commit at phase 3, escalate at phase 1;
  `:flag-infringement-concern` escalates and then commits after
  approval; unregistered/unverified license record, non-`:propose`
  effect, and out-of-scope-drift scenarios all HARD-hold as designed.
  Independently re-verified from a brand-new fresh clone: identical
  "Ran 40 tests containing 119 assertions. 0 failures, 0 errors."
- Commit `9ce4ca91f0468d25edcf468a6c74c84e26b2e7bc` pushed to
  `cloud-itonami/cloud-itonami-isic-7740`'s `main` (the repo's only
  commit; fresh `gh repo create`, no prior history).

## Verification addendum: registry promotion (Step 7, landed)

- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`:
  `"7740"` entry promoted `:spec` -> `:implemented` (also de-truncated
  `:name` to the full ISIC Rev.4 name, de-placeholdered
  `:repo`/`:business-id` from the stale
  `gftdcojp/cloud-itonami-N7740` to
  `cloud-itonami/cloud-itonami-isic-7740`, trimmed
  `:required-technologies` from the stale 7-item `:spec` placeholder
  `[:robotics :identity :forms :dmn :bpmn :audit-ledger :labor]` to
  `[:identity :forms :dmn :bpmn :audit-ledger]` matching sibling
  `cloud-itonami-isic-5913`'s own shape, `:operating-states` updated
  from the stale placeholder to `[:intake :register :review :flag
  :report :audit]`). A first attempt via feature-branch + server-side
  merge (`gh api .../merges`) hit a 409 merge conflict from concurrent
  sibling fleet activity on the same file and was abandoned in favor of
  the direct-to-main Contents-API single-file PUT pattern (sha-checked
  optimistic concurrency, fresh re-fetch immediately before each
  attempt), which landed on the first direct-PUT attempt, commit
  `68941372dd92d4e03be8b2fe37c544ddad96f137`. Exact-block edit verified
  via a byte-exact prefix/suffix scan against the immediately-prior
  fresh fetch (everything outside the target block unchanged).
- `test/kotoba/industry_test.clj`: dedicated corroboration `testing`
  block added for `"7740"`. The pinned `:implemented` count assertion
  was live-recomputed via `(kotoba.industry/maturity-summary)`
  immediately before each attempt (this file and `registry.edn` are
  both extremely hot, high-concurrency shared files across the fleet --
  the live count moved from 385 to 390 to 392 across successive
  re-fetches within this single promotion's own edit window, purely
  from concurrent sibling promotions landing in the interim, not this
  promotion's own drift). Landed on the first direct-to-main PUT
  attempt at count 392, commit `b3c095788f342bee8904e4b9d890c4cc7ce3d61c`.
  Verified via a brand-new GET immediately after the PUT that both the
  new `"7740"` block and sample sibling blocks (`5913`, `5629`, `5813`)
  were all still present.
- Post-merge re-verification from a brand-new fresh clone of
  `kotoba-lang/industry` `main` (plus a fresh `../technology` sibling
  clone): `clojure -M:test` -> "Ran 15 tests containing 1053
  assertions. 0 failures, 0 errors." `clojure -M:lint` -> 0 errors, 0
  warnings. No mojibake detected in `registry.edn`. `"7740"`'s own
  entry, and sample entries `5913`/`5629`/`5813`/`774` (the untouched
  group entry), all independently re-confirmed intact.
