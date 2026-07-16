# ADR-2620005813: cloud-itonami-isic-5813 (Publishing of newspapers, journals and periodicals) newspaper/journal/periodical-publishing operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (Wave 3, production/robotics), ADR-2607152500
(Wave 4, human-facing/personal services -- authorizes this batch to
proceed in parallel with Wave 3), cloud-itonami-isic-5811 (Book
publishing -- the reference module shape this actor mirrors,
independently re-read in full before use), the `kotoba-lang/industry`
registry's `"5813"` catalog entry

## Context

`kotoba-lang/industry`'s registry carried a `"5813"` entry at
`:maturity :spec` with `:name "Publishing of newspapers, journals and
periodicals"` (matching the assigned ISIC class, verified via fresh
clone before any work began) and `:repo
"https://github.com/gftdcojp/cloud-itonami-J5813"` (an old,
never-populated naming scheme). Confirmed no repo exists yet at either
that stale placeholder or the real
`cloud-itonami/cloud-itonami-isic-5813` target (`gh api` 404 for the
latter) before any work began. A separate, redundant 3-digit group
entry `{:id "581" ...}` also exists at `:maturity :spec`, covering
book/directory/newspaper publishing broadly, with a genuinely truncated
`:name` field ending in `"..."` -- this is a known, pre-existing
seed-data artifact from an earlier data-seeding pass, distinct from the
`"5813"` class-level entry, and was deliberately NOT touched or
promoted by this change.

This is part of Wave 4 (human-facing/personal-services fleet, ISIC
sections I/P/Q/R/S/T), running in parallel with the ongoing Wave 3
(production/robotics) rollout per ADR-2607152500. Newspaper/journal/
periodical publishing touches editorial-content authority and
legal-risk (defamation, sourcing-integrity) decisions, so this actor is
deliberately scoped to back-office operations coordination only, with a
closed op allowlist that structurally excludes finalizing an
editorial-content decision, a legal-risk clearance decision, and a
source-verification sign-off decision -- those remain exclusively
human/institutional decisions, never an auto-commit-eligible op, per
Wave 4's person-facing-service safety guardrail. `:flag-content-concern`
(surfacing a defamation/sourcing-integrity/content-risk concern) is
always an escalate-to-human op, never a member of any phase's `:auto`
set.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-5813` as a newspaper/journal/
periodical-publishing OPERATIONS COORDINATION actor (not
editorial-content-decision authority, not a legal-risk-clearance
authority, not a source-verification-sign-off authority), mirroring
`cloud-itonami-isic-5811`'s verified module shape
(`store`/`advisor`/`governor`/`phase`/`operation`/`sim`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY, AGPL-3.0-or-later) with fresh, periodical-
publishing-specific domain logic under the `pressops` namespace:

1. **`pressops.store`** -- `MemStore` (atom of EDN) behind a `Store`
   protocol; a `publications` directory keyed by `:publication-id`
   STRING (a newspaper/journal/periodical masthead under the
   publishing house's own record), plus an append-only `ledger` and a
   `coordination-log` of committed records.
2. **`pressops.advisor`** ("PressAdvisor") -- deterministic mock
   advisor drafting exactly four kinds of proposal: production-record
   logging (issue/edition/print-run/ISSN-assignment data),
   production-operation scheduling (editing/layout/print-run
   scheduling), distribution coordination (outbound
   distribution/circulation coordination), and content-concern
   flagging (defamation/sourcing-integrity/factual-accuracy risk).
   Every proposal's `:effect` is always `:propose`; every output is
   censored downstream by the governor.
3. **`pressops.governor`** ("PressGovernor") -- three HARD checks, all
   permanent and un-overridable: (1) publication-unverified -- the
   target publication record must exist AND be independently
   `:registered?`/`:verified?` in the store before ANY proposal for it
   may commit or escalate; (2) effect-not-propose -- any `:effect`
   other than `:propose` is HARD-blocked; (3) scope-exclusion -- any
   proposal (regardless of op) whose op/summary/rationale/cites/value
   touches finalizing-an-editorial-content-decision,
   legal-risk-clearance, or source-verification-sign-off territory is
   a HARD, PERMANENT block, unconditionally evaluated on every
   proposal; an op outside the closed four-op allowlist is folded into
   this same check. One ESCALATE (soft) gate: `:flag-content-concern`
   ALWAYS escalates to a human regardless of confidence, as does low
   confidence generally.
4. **`pressops.phase`** -- Phase 0->3 staged rollout;
   `:flag-content-concern` is permanently ABSENT from every phase's
   `:auto` set (structural fact, not a rollout milestone still to
   come) -- only `:log-production-record`/`:schedule-production-
   operation`/`:coordinate-distribution` may auto-commit at phase 3
   when governor-clean.
5. **`pressops.operation`** ("OperationActor") -- langgraph-clj
   StateGraph, `intake -> advise -> govern -> decide -> commit | hold |
   request-approval`, `interrupt-before #{:request-approval}` for
   human-in-the-loop sign-off, invoked exclusively via
   `langgraph.graph/run*`.
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-5811`'s shape (`:test`/`:lint`/`:run`/`:dev`
   aliases pinned to the same `kotoba-lang/langgraph` git SHA
   `a332a770a0d2b5193f81b54483bb954fb29ef8d7`, `itonami.blueprint/*`
   metadata, scope/design/testing README sections).

### Known self-tripping bug class, avoided by construction

Per this fleet's documented recurring bug pattern, `pressops.governor`'s
`scope-excluded-terms` list is deliberately phrased as the
finalization/execution ACTION ("a legal-risk clearance", "a
source-verification sign-off"), never as a bare noun ("legal risk",
"source verification", "sourcing"). A bare-noun phrasing would
self-trip on this actor's own legitimate `:flag-content-concern`
proposals, which routinely discuss sourcing-integrity and legal-risk
topics as raw observations, never as finalized decisions. A dedicated
regression test
(`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
`test/pressops/advisor_test.clj`) independently confirms every op's own
default (clean) advisor-generated proposal text clears the governor's
scope-exclusion scan, exercised directly rather than assumed.

### What this actor does NOT do

Finalizing an editorial-content decision (what a story/issue actually
says, whether it runs as written), issuing a legal-risk clearance
decision (defamation/libel risk sign-off), and issuing a
source-verification sign-off decision (declaring sourcing/fact-checking
of a story complete and cleared) all remain exclusively
human/institutional decisions, permanently, with no actor or
human-approval override path -- enforced structurally by the
governor's closed op/effect allowlists and the unconditional
scope-exclusion check, not just documented. `:flag-content-concern` is
a "surface the concern" op only; it can never self-clear the concern it
raises, and is never a member of any phase's `:auto` set.

## Verification

- `cloud-itonami-isic-5813`: `clojure -M:test` -- raw final line: `Ran
  44 tests containing 122 assertions.` / `0 failures, 0 errors.` (first
  run, no bugs to fix; re-run green a second time from a brand-new
  fresh clone after push, using the pinned git-sha dependency with no
  `:dev` local-root overrides).
- `clojure -M:lint` -- 0 errors, 0 warnings.
- `clojure -M:run` demo narrative exercises proposal submission,
  escalation/approval on every write op, and every HARD-hold scenario
  directly (unregistered publication, unverified publication,
  non-`:propose` effect, editorial-content/legal-clearance/
  source-verification-sign-off scope drift) -- ran clean (exit 0), no
  exceptions.
- All source under `src/`/`test/` is `.cljc`, no JVM-only interop; the
  actor graph is invoked exclusively via `langgraph.graph/run*`.
- Repo created fresh (`gh repo create` + push, public visibility
  matching the reference and `blueprint.edn`'s `:status :public-oss`),
  initial commit `dc53739` on `cloud-itonami-isic-5813`'s `main` (no
  prior history), confirmed via a brand-new fresh clone.
- `kotoba-lang/industry` registry `"5813"` entry updated in place
  (exact-text edit of the literal `{:id "5813" ...}` block only,
  diff-verified confined to that block: `:repo`/`:business-id`
  corrected from the never-populated `gftdcojp/cloud-itonami-J5813`
  naming to `cloud-itonami/cloud-itonami-isic-5813` /
  `cloud-itonami-isic-5813`, `:required-technologies` normalized from a
  stale 7-item `[:robotics :identity :forms :dmn :bpmn :audit-ledger
  :phone]` `:spec` placeholder to the coordination-only shape actually
  implemented `[:identity :forms :dmn :bpmn :audit-ledger]` (matching
  sibling `cloud-itonami-isic-5811`'s own `:required-technologies`),
  `:maturity` `:spec` -> `:implemented`; `:name` was already the full,
  correct "Publishing of newspapers, journals and periodicals" (not
  affected by the ~10%-of-entries truncated-name seed-data bug, so no
  de-truncation was needed; the separate `"581"` group entry's own
  genuinely-truncated `:name` was left untouched, per this fleet's
  caution against conflating class-level and group-level entries).
  Landed via a Contents API single-file PUT (sha-checked optimistic
  concurrency, fresh fetch immediately before the PUT), commit
  `fe502548df443dac159a4f86607ff598e7e28104`, first attempt succeeded
  (no sha drift observed for this narrow single-block edit).
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion
  bumped, live-recomputed via `(kotoba.industry/maturity-summary)`
  against freshly re-fetched content immediately before each PUT
  attempt (never a reused/stale buffer) -- this file is an extremely
  hot, high-concurrency shared file across the fleet. This promotion's
  own narration block plus the 379 -> 380 count bump landed via commit
  `6da9e21b80e3b9d748e9e7507297b90720d9d555` on the first attempt (no
  409). **Transparency note on concurrent fleet drift**: the very next
  fresh post-merge re-verification clone (per step below) showed the
  live count had already drifted to 382 (two further sibling
  promotions landed in the interim); this agent independently corrected
  it to 382 via a second Contents API PUT (commit
  `7077fe617b260db37e41cc0810b7bf24d7e445bf`), succeeding on the first
  attempt. A THIRD fresh clone immediately after that showed the count
  had drifted again to 383 -- but by the time this agent re-fetched the
  live file to correct it a second time, a concurrent sibling agent had
  already independently landed the `382 -> 383` fix first, confirmed
  identical to this agent's own live-recomputed true count, so no
  further PUT from this agent was needed. Documented here in full
  rather than omitted, per this fleet's honest-reporting norm.
- Full `kotoba-lang/industry` suite re-run green after all edits (fresh
  clone, plus a fresh `kotoba-lang/technology` sibling clone for
  `deps.edn` resolution): `Ran 15 tests containing 1046 assertions.` /
  `0 failures, 0 errors.`
- Final post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone of both `cloud-itonami-isic-5813` and
  `kotoba-lang/industry`, plus a fresh `kotoba-lang/technology`
  sibling): `clojure -M:test` re-run green in both repos; the
  registry's `"5813"` entry confirmed `:maturity :implemented` with the
  corrected `:repo`/`:business-id`/`:required-technologies`, and a
  sample of sibling entries (`873`/`6310`/`581`/`2513`/`5811`)
  confirmed untouched (the `581` group entry's own pre-existing
  truncated `:name` confirmed still present and NOT altered by this
  change). `grep -c` for the UTF-8 replacement character against
  `resources/kotoba/industry/registry.edn` returned `0` (no file-wide
  mojibake). `clojure -M:lint` on the re-cloned `industry` repo: 0
  errors, 0 warnings.

## Consequences

(+) `cloud-itonami-isic-5813` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
`:spec` placeholder pointing at a repo that never existed.

(+) `kotoba-lang/industry` registry `"5813"` entry promoted to
`:maturity :implemented`.

(+) This actor is a further concrete example (after `cloud-itonami-isic-
5811`) of Wave 4's person-facing-service safety guardrail applied to a
different sensitive-decision axis: instead of book publishing's
editorial-content/legal-risk-clearance/rights-licensing-grant
exclusions, this actor structurally excludes editorial-content-decision,
legal-risk-clearance, and source-verification-sign-off authority (the
sourcing-integrity risk axis distinctive to news/periodical journalism
rather than a rights-grant one), all folded into one unconditional
scope-exclusion check that never trusts the advisor's own framing of
its intent, with scope-excluded terms deliberately phrased as
finalization/execution actions (not bare nouns) to avoid this fleet's
documented self-tripping bug class from the start.

(-) Still a simulation/proposal layer, not a real newsroom/publishing-
operations system. Editorial decisions, legal clearance, and
source-verification sign-off remain human-/institution-controlled via
external channels.

(-) No integration with real publishing-house systems (editorial
workflow/CMS, print-run scheduling, ISSN registries, fact-checking/
sourcing databases, distribution/circulation partner APIs) -- this is a
standalone coordinator blueprint, matching every prior sibling actor's
own stated limitation.
