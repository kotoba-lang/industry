# ADR-2645173414: cloud-itonami-isic-6020 (television programming and broadcasting activities) broadcasting-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse-toposort
wave plan), ADR-2607152500 (Wave 4 rollout-start amendment to
ADR-2607121000's P3->P4 sequencing gate, source of the Wave 4
person-facing-service safety guardrail this ADR follows), ADR-2607121200
(Wave 0 cljs-first safety kernel), skill `build-actor`
(advisor/governor/StateGraph/audit-ledger actor pattern). Mirrors
`cloud-itonami-isic-5913` (Motion picture, video and television programme
distribution activities), whose `filmdistops.*` advisor/governor/phase/
operation/store/sim module shape this ADR follows module-for-module.

## Context

ISIC Rev.5 class `6020` covers television programming and broadcasting
activities -- operating a licensed television broadcast station. Before
any work began, the live `kotoba-lang/industry` registry entry for
`"6020"` was independently re-verified fresh from a clean clone: its
`:name` field read exactly `"Television programming and broadcasting
activities"`, unambiguously matching the target class, with no
truncation.

Unlike most of this fleet's targets, `cloud-itonami/cloud-itonami-isic-6020`
was NOT a fresh-scaffold 404 -- `gh api repos/cloud-itonami/cloud-itonami-isic-6020`
confirmed a PRE-EXISTING repository already at `:blueprint` tier
(`CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/`GOVERNANCE.md`/`LICENSE`/
`README.md`/`SECURITY.md`/`blueprint.edn`/`docs/` only, from an earlier
bulk-scaffolding pass, no `deps.edn`/`src`/`test`). This work ADDED the
missing `deps.edn`/`src`/`test` module set on top of that existing
boilerplate rather than re-scaffolding -- the pre-existing docs
(`README.md`, `blueprint.edn`, `docs/business-model.md`,
`docs/operator-guide.md`) were kept and extended (an "Implementation"
section appended to `README.md`, `:itonami.blueprint/maturity
:implemented` added to `blueprint.edn`), not recreated. The pre-existing
`blueprint.edn` already declared `:itonami.blueprint/governor
:broadcast-license-governor` and `:itonami.blueprint/id
"cloud-itonami-6020"` (note: no `-isic-` infix, unlike most sibling
entries' business-id shape) -- both were carried forward unchanged into
the implementation and the registry `:business-id`, since they were
already internally consistent with each other and no fix was needed.

Television broadcasting touches editorial-content authority (on-air
content decisions) and emergency-alert-system decisions, both squarely
outside this actor's authority. Per ADR-2607152500's Wave 4
person-facing-service guardrail, the closed op allowlist must never
include an op that directly finalizes either decision, and any "flag a
concern" op must always escalate to human sign-off, never appearing in
any phase's `:auto` set.

This batch's sibling agents have independently discovered and fixed the
same recurring bug class across several Wave 4 actors: a governor's
`scope-excluded-terms` list phrased as a bare noun (e.g. "content",
"broadcast", "emergency alert") can accidentally match inside the mock
advisor's own default rationale/disclaimer text for a legitimate,
allowed proposal -- self-blocking the actor on its own happy path. This
is a live risk here because `:flag-content-concern`'s entire purpose is
to talk *about* FCC-compliance/on-air-incident/emergency-alert topics
using exactly the bare nouns a naive term list would ban.

## Decision

Implement `cloud-itonami-isic-6020` with Store, Advisor, Governor,
Phase, Operation, Sim (mirrors `cloud-itonami-isic-5913`'s
`filmdistops.*` module shape module-for-module, renamed to
`tvbroadcastops.*`, `title`/`title-id` renamed to `station`/`station-id`):

- **`tvbroadcastops.store`** -- `Store` protocol + `MemStore`.
  String-keyed `stations` directory (`:station-id`/`:call-sign`/`:name`/
  `:registered?`/`:verified?`), append-only `ledger`, append-only
  `broadcast-log`.
- **`tvbroadcastops.advisor`** -- `Advisor` protocol + `mock-advisor`.
  Four proposal generators, all `:effect :propose`:
  `:log-broadcast-record` (program-schedule/segment/on-air-log data
  logging), `:schedule-broadcast-operation` (programming-block
  scheduling proposal), `:flag-content-concern` (FCC-compliance/on-air-
  incident/emergency-alert concern surfacing), `:coordinate-equipment-
  maintenance` (transmitter/studio-equipment maintenance coordination).
- **`tvbroadcastops.governor`** (`BroadcastLicenseGovernor`, matching
  the pre-existing `blueprint.edn`'s own declared governor name) --
  independent compliance layer, two HARD checks (always hold,
  un-overridable):
  1. `station-unverified` -- target station/license record must exist
     AND be independently `:registered?`/`:verified?` in the store,
     never trusting the proposal's own claim.
  2. `effect-not-propose` -- any `:effect` other than `:propose` is a
     claim to directly actuate/commit outside governance.
  3. `scope-excluded`/`op-not-allowed` -- ANY proposal (regardless of
     op) attempting to finalize an on-air-content decision, or finalize
     an emergency-alert-broadcast decision, is permanently blocked; an
     op outside the closed four-op allowlist is folded into the same
     check.

  **Self-trip discipline (the fix for this batch's known bug class)**:
  `scope-excluded-terms` are phrased as the finalization/execution
  ACTION -- `"finalize the on-air content decision"`, `"authorize the
  emergency alert broadcast"`, `"trigger the emergency alert
  broadcast"`, `"オンエア内容決定を確定"`, `"緊急警報放送を確定"` -- never
  as a bare noun ("content", "broadcast", "emergency alert", "on-air").
  A dedicated regression test
  (`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
  `governor_test.clj`) asserts all four default proposal generators,
  for a clean registered+verified station, never trip `:scope-excluded`
  and never HARD-hold.

  ESCALATE (always human sign-off): `:flag-content-concern` always
  escalates (`always-escalate-ops`), independently confirmed by
  `tvbroadcastops.phase` never including it in any phase's `:auto` set;
  low confidence (< 0.6) also escalates.
- **`tvbroadcastops.phase`** -- 0->3 rollout: phase 0 read-only; phase 1
  `:log-broadcast-record` only, approval-gated; phase 2 adds
  `:schedule-broadcast-operation`/`:coordinate-equipment-maintenance`,
  still approval-gated; phase 3 auto-commits the three non-concern ops
  when governor-clean and confident, `:flag-content-concern` still
  always escalates.
- **`tvbroadcastops.operation`** -- real `langgraph-clj` `StateGraph`
  (intake -> advise -> govern -> decide -> commit|hold|request-approval),
  `interrupt-before #{:request-approval}` for human-in-the-loop resume,
  mirroring `filmdistops.operation` exactly (not a stub).
- **`tvbroadcastops.sim`** -- demo runner (`clojure -M:run`) walking the
  full happy path (all four ops at phase 1 then phase 3), the always-
  escalate content-concern flow, and every HARD-hold scenario
  (unregistered station, unverified station, non-`:propose` effect,
  out-of-scope drift).
- Tests: `store_contract_test`, `advisor_test`, `governor_test` (incl.
  the mandatory self-trip regression), `phase_test`,
  `governor_contract_test` -- 40 tests / 119 assertions, all green.
- All `.cljc` (portable, no JVM-only interop). AGPL-3.0-or-later.
  Pre-existing `README.md`/`GOVERNANCE.md`/`CONTRIBUTING.md`/
  `SECURITY.md`/`CODE_OF_CONDUCT.md`/`blueprint.edn`/`docs/` kept as-is
  except an "Implementation" section appended to `README.md` and
  `:itonami.blueprint/maturity :implemented` added to `blueprint.edn`.

## Scope exclusions (hardcoded in governor checks, not just prose)

- Finalizing an on-air-content decision (what actually airs, when, in
  what form) -- always a hard, permanent block.
- Finalizing an emergency-alert-broadcast decision (whether/when to
  trigger an Emergency Alert System transmission) -- always a hard,
  permanent block.
- Direct transmission dispatch, license-scope approval, and billing-
  record publication without governor approval -- out of scope, not
  represented in the closed op allowlist at all.

## Consequences

(+) ISIC 6020 TV-broadcasting-operations-coordination is genuinely
implemented and fully tested on top of a pre-existing blueprint-tier
repo, without discarding or recreating its existing docs.

(+) The on-air-content-decision and emergency-alert-broadcast-decision
exclusions are hardcoded in governor checks
(`scope-exclusion-violations`), not just asserted in README prose.

(+) The self-trip bug class this batch repeatedly encountered is fixed
by construction (action-phrased terms) and covered by a dedicated
regression test, not just avoided by luck.

(+) `:flag-content-concern` always-escalate is a two-layer invariant
(governor `always-escalate-ops` + phase's permanent absence from every
`:auto` set), matching every sibling Wave 4 actor's own safety
discipline.

(+) Portable `.cljc`, zero JVM-only constructs; `clojure -M:lint` is 0
errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`, matching every sibling actor's current
maturity.

(-) `tvbroadcastops.advisor`'s `mock-advisor` is deterministic, not a
real LLM call; the `Advisor` protocol seam is ready for that swap but it
is not wired in this ADR.

## Verification

- `cloud-itonami-isic-6020`: `clojure -M:test` -> "Ran 40 tests
  containing 119 assertions. 0 failures, 0 errors." `clojure -M:lint`
  -> 0 errors, 0 warnings. `clojure -M:run` demo runs end-to-end: all
  four happy-path ops auto-commit at phase 3, escalate at phase 1;
  `:flag-content-concern` escalates and then commits after approval;
  unregistered/unverified station, non-`:propose` effect, and
  out-of-scope-drift scenarios all HARD-hold as designed.
- Commit `000a30ab5dd939f1e25f474bf831c7914fbfa6ca` pushed to
  `cloud-itonami/cloud-itonami-isic-6020`'s `main` (on top of the
  pre-existing `4850fdc` blueprint-publication commit; no divergence,
  fast-forward push).

## Verification addendum: registry promotion (Step 7, landed)

- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`:
  `"6020"` entry promoted by adding the previously-absent `:maturity`
  key explicitly (`:maturity :implemented`) -- this entry had NO
  `:maturity` key before this edit, resolving to `:blueprint` only via
  the `(:repo industry)` fallback in `kotoba.industry/maturity-of`.
  `:repo`/`:business-id` were already correct (`:business-id
  "cloud-itonami-6020"` already matched the repo's own `blueprint.edn`
  `:itonami.blueprint/id`) and left unchanged, as was
  `:required-technologies`/`:optional-technologies`/`:operating-states`
  -- a deliberately minimal, exact-block diff. Landed via a Contents-API
  single-file PUT (sha-checked optimistic concurrency, freshly
  re-fetched immediately before the PUT), commit
  `d6ff6346568d591d9e1c0397a417faa5aa668db8`, first attempt, no 409
  retries needed. Exact-block edit verified via a before/after diff
  window confirming only the intended `:maturity :implemented,`
  insertion changed, plus sample-verified against `5913`/`6310`/`3512`
  entries also intact.
- `test/kotoba/industry_test.clj`: this is an extremely hot,
  high-concurrency shared file across the fleet. The pre-existing
  `"6020, freshly published, is also :blueprint"` spot-check block was
  updated to a detailed `:implemented` corroboration block, and the
  `:blueprint`/`:implemented` aggregate-count assertions in
  `maturity-summary-counts-tiers` were updated to this promotion's own
  live-recomputed values (`12 -> 11` blueprint, `405 -> 406`
  implemented, via `(kotoba.industry/maturity-summary)` on a freshly
  re-fetched origin/main immediately before each edit). Landed on the
  first PUT attempt, commit `07500719c1e4d8907355a7754e352717f843e3fc`.
  Three FURTHER corrective rounds were required afterward purely to
  absorb concurrent sibling promotions racing this same shared file
  (`7710`/`7721`, then `5610`, then `6010`/`7729`/`7710`'s own detailed
  corroboration edit) -- none of those entries' own registry.edn data
  was touched, only their spot-check `:maturity` assertions and the two
  aggregate counts were mechanically corrected to stay truthful, per
  this fleet's own hot-file discipline. Final landed state (commit
  `67f63c6`, authored by a concurrent sibling agent's own `7710`
  corroboration edit, which independently arrived at and superseded
  this promotion's own final corrective values) has `:blueprint` = 6,
  `:implemented` = 411 fleet-wide, with `"6020"`'s own corroboration
  block (`(is (= :implemented (industry/maturity "6020"))))`, line 98)
  intact throughout every round.
- Post-merge re-verification from a brand-new fresh clone of
  `kotoba-lang/industry` `main` (plus a fresh `../technology` sibling
  clone), commit `67f63c6`: `clojure -M:test` -> "Ran 15 tests
  containing 1063 assertions. 0 failures, 0 errors." `clojure -M:lint`
  -> 0 errors, 0 warnings. No mojibake detected in `registry.edn`
  (valid UTF-8, zero replacement characters). `"6020"`'s own entry
  independently re-confirmed intact:
  `{:id "6020", :name "Television programming and broadcasting
  activities", :repo "https://github.com/cloud-itonami/cloud-itonami-isic-6020",
  :business-id "cloud-itonami-6020", :required-technologies [:robotics
  :identity :forms :dmn :bpmn :audit-ledger :phone], :optional-technologies
  [:optimization], :maturity :implemented, :operating-states [:intake
  :provision :route :bill :support :audit]}`, and sample entries
  `5913`/`6310`/`3512` also independently re-confirmed intact.
