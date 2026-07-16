# ADR-2617500000: cloud-itonami-isic-5913 (motion picture, video and television programme distribution activities) distribution-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse-toposort
wave plan), ADR-2607152500 (Wave 4 rollout-start amendment to
ADR-2607121000's P3->P4 sequencing gate), ADR-2607121200 (Wave 0
cljs-first safety kernel), skill `build-actor` (advisor/governor/
StateGraph/audit-ledger actor pattern). Mirrors
`cloud-itonami-isic-873` (Residential Care Activities for the Elderly
and Disabled), the Wave 4 human-facing/personal-services flagship whose
module structure this ADR follows module-for-module.

## Context

ISIC Rev.4 class 5913 covers motion picture, video and television
programme **distribution** activities -- distinct from its siblings in
the same division: 5911 (production), 5912 (post-production), and 5914
(projection), each expected to be built by separate sibling agents in
this same Wave 4 batch. Before scaffolding, the live
`kotoba-lang/industry` registry entry for `"5913"` was independently
verified fresh (not assumed from this task's premise): its `:name`
field read `"Motion picture, video and television programme
distribution..."` (truncated with a literal `"..."`, a pre-existing
seed-data bug affecting roughly 10% of registry entries) which
unambiguously matches "Motion picture, video and television programme
distribution activities" and is distinct from the verified `:name`
values of 5911 ("...production a...", truncated), 5912
("...post-product...", truncated), and 5914 ("Motion picture
projection activities", not truncated). No existing repo was found at
`cloud-itonami/cloud-itonami-isic-5913` (`gh api` 404) or at the
registry's placeholder `:repo`
(`https://github.com/gftdcojp/cloud-itonami-J5913`, itself a
pre-existing bogus placeholder value -- wrong org, wrong id shape --
never a real repo), so this is a fresh scaffold, not a repair.

Distribution touches rights/licensing-grant decisions and territory/
window-clearance decisions, both squarely human/legal authority. Per
ADR-2607152500's Wave 4 person-facing-service guardrail, the closed op
allowlist must never include an op that directly finalizes either
decision, and any "flag a concern" op must always escalate to human
sign-off, never appearing in any phase's `:auto` set.

This batch's sibling agents independently discovered and fixed the same
recurring bug class across several Wave 4 actors: a governor's
`scope-excluded-terms` list phrased as a bare noun (e.g. "restraint",
"license") can accidentally match inside the mock advisor's own default
rationale/disclaimer text for a legitimate, allowed proposal --
self-blocking the actor on its own happy path. This is a live risk here
because `:flag-rights-concern`'s entire purpose is to talk *about*
rights/territory/clearance topics using exactly the bare nouns a naive
term list would ban.

## Decision

Implement `cloud-itonami-isic-5913` with Store, Advisor, Governor,
Phase, Operation, Sim (mirrors `cloud-itonami-isic-873`'s
`eldercareops.*` module shape module-for-module, renamed to
`filmdistops.*`):

- **`filmdistops.store`** -- `Store` protocol + `MemStore`. String-keyed
  `titles` directory (`:title-id`/`:name`/`:registered?`/`:verified?`),
  append-only `ledger`, append-only `distribution-log`.
- **`filmdistops.advisor`** -- `Advisor` protocol + `mock-advisor`. Four
  proposal generators, all `:effect :propose`:
  `:log-distribution-record` (title/territory/window metadata logging),
  `:schedule-release-operation` (release-date/marketing-window
  scheduling proposal), `:flag-rights-concern` (licensing-conflict/
  territory-clearance/piracy concern surfacing), `:coordinate-delivery`
  (deliverable handoff to exhibitor/platform coordination).
- **`filmdistops.governor`** (`FilmDistGovernor`) -- independent
  compliance layer, two HARD checks (always hold, un-overridable):
  1. `title-unverified` -- target title/contract record must exist AND
     be independently `:registered?`/`:verified?` in the store, never
     trusting the proposal's own claim.
  2. `effect-not-propose` -- any `:effect` other than `:propose` is a
     claim to directly actuate/commit outside governance.
  3. `scope-excluded`/`op-not-allowed` -- ANY proposal (regardless of
     op) attempting to finalize a rights-licensing grant, or finalize a
     distribution-window/territory-clearance decision, is permanently
     blocked; an op outside the closed four-op allowlist is folded into
     the same check.

  **Self-trip discipline (the fix for this batch's known bug class)**:
  `scope-excluded-terms` are phrased as the finalization/execution
  ACTION -- `"finalize the rights license"`, `"clear the distribution
  window"`, `"grant the license"`, `"権利許諾を確定"`,
  `"配給ウィンドウを確定"` -- never as a bare noun ("rights", "license",
  "window", "territory", "clearance"). A dedicated regression test
  (`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
  `governor_test.clj`) asserts all four default proposal generators,
  for a clean registered+verified title, never trip `:scope-excluded`
  and never HARD-hold.

  ESCALATE (always human sign-off): `:flag-rights-concern` always
  escalates (`always-escalate-ops`), independently confirmed by
  `filmdistops.phase` never including it in any phase's `:auto` set;
  low confidence (< 0.6) also escalates.
- **`filmdistops.phase`** -- 0->3 rollout: phase 0 read-only; phase 1
  `:log-distribution-record` only, approval-gated; phase 2 adds
  `:schedule-release-operation`/`:coordinate-delivery`, still
  approval-gated; phase 3 auto-commits the three non-concern ops when
  governor-clean and confident, `:flag-rights-concern` still always
  escalates.
- **`filmdistops.operation`** -- real `langgraph-clj` `StateGraph`
  (intake -> advise -> govern -> decide -> commit|hold|request-approval),
  `interrupt-before #{:request-approval}` for human-in-the-loop resume,
  mirroring `eldercareops.operation` exactly (not a stub).
- **`filmdistops.sim`** -- demo runner (`clojure -M:run`) walking the
  full happy path (all four ops at phase 1 then phase 3), the always-
  escalate rights-concern flow, and every HARD-hold scenario
  (unregistered title, unverified title, non-`:propose` effect,
  out-of-scope drift).
- Tests: `store_contract_test`, `advisor_test`, `governor_test` (incl.
  the mandatory self-trip regression), `phase_test`,
  `governor_contract_test` -- 40 tests / 119 assertions, all green.
- All `.cljc` (portable, no JVM-only interop). AGPL-3.0-or-later.
  README/GOVERNANCE/CONTRIBUTING/SECURITY/CODE_OF_CONDUCT all written
  fresh for this domain (the `cloud-itonami-isic-873` reference repo's
  own GOVERNANCE.md/CONTRIBUTING.md were found to still carry
  copy-paste leftovers from an unrelated mining-actor template --
  `cloud-itonami-isic-0520`, extraction/mine-safety boilerplate -- that
  bug was deliberately NOT propagated into this repo).

## Scope exclusions (hardcoded in governor checks, not just prose)

- Finalizing a rights-licensing grant (who is licensed to distribute a
  title, in what territory, under what terms) -- always a hard,
  permanent block.
- Finalizing a distribution-window or territory-clearance decision
  (locking in which window/territory a title actually releases in) --
  always a hard, permanent block.
- Contract negotiation, minimum-guarantee/advance determination, and
  binding distribution-agreement execution -- out of scope, not
  represented in the closed op allowlist at all.

## Consequences

(+) ISIC 5913 distribution-operations-coordination is genuinely
implemented and fully tested, distinct from and non-overlapping with
sibling classes 5911/5912/5914.

(+) The rights-licensing-grant and window/territory-clearance
exclusions are hardcoded in governor checks
(`scope-exclusion-violations`), not just asserted in README prose.

(+) The self-trip bug class this batch repeatedly encountered is fixed
by construction (action-phrased terms) and covered by a dedicated
regression test, not just avoided by luck.

(+) `:flag-rights-concern` always-escalate is a two-layer invariant
(governor `always-escalate-ops` + phase's permanent absence from every
`:auto` set), matching every sibling Wave 4 actor's own safety
discipline.

(+) Portable `.cljc`, zero JVM-only constructs; `clojure -M:lint` is 0
errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up;
tests use in-memory `MemStore`, matching every sibling actor's current
maturity.

(-) `filmdistops.advisor`'s `mock-advisor` is deterministic, not a real
LLM call; the `Advisor` protocol seam is ready for that swap but it is
not wired in this ADR.

## Verification

See the Verification addendum appended below once the
`kotoba-lang/industry` registry promotion (Step 7) lands, recording the
registry merge commit and post-merge re-verification. Actor-repo
verification recorded at scaffold time:

- `cloud-itonami-isic-5913`: `clojure -M:test` -> "Ran 40 tests
  containing 119 assertions. 0 failures, 0 errors." (also independently
  re-run via `clojure -M:dev:test` with local `../../kotoba-lang/
  langgraph` and `../../kotoba-lang/langchain` overrides -- identical
  result). `clojure -M:lint` -> 0 errors, 0 warnings. `clojure -M:dev:run`
  demo runs end-to-end: all four happy-path ops auto-commit at phase 3,
  escalate at phase 1; `:flag-rights-concern` escalates and then commits
  after approval; unregistered/unverified title, non-`:propose` effect,
  and out-of-scope-drift scenarios all HARD-hold as designed.
- Commit `3ae9f6cdd32a77e62f4866f411e1af4a49e498ae` pushed to
  `cloud-itonami/cloud-itonami-isic-5913`'s `main` (the repo's only
  commit; fresh `gh repo create`, no prior history).
