# ADR-2660000000: cloud-itonami-isic-7912 — Tour Operator Operations Coordination

## Status

Accepted. `cloud-itonami-isic-7912` promoted from no `:maturity` key
(resolves to `:blueprint` via the `kotoba.industry/maturity-of` fallback,
since the entry already carries a `:repo`) to `:implemented` in the
`kotoba-lang/industry` registry. This is the LAST batch of the
blueprint-tier cleanup sweep — after this ADR lands, the registry has
zero remaining nil-maturity/blueprint-tier gaps (see Consequences).

## Context

ISIC Rev.5 7912 (Tour operator activities) is a Wave 4
(human-facing/personal-services) target under ADR-2607121000's
reverse-toposort rollout plan and ADR-2607152500's Wave 4 rollout
amendment (Wave 4 authorized to proceed in parallel with Wave 3, with an
explicit person-facing-service safety guardrail). Identity independently
verified against a fresh clone of `kotoba-lang/industry` before any work
began: the live `{:id "7912" ...}` entry's `:name` is exactly "Tour
operator activities" — no mismatch, and distinct from the sibling
`{:id "7911" ...}` ("Travel agency activities") built by a sibling agent
in this same batch (at the time this session checked, `7911`'s own repo
was still at the pre-existing blueprint-only tip, unbuilt).

**Not a fresh scaffold.** `cloud-itonami/cloud-itonami-isic-7912` already
existed as a legitimate `:blueprint`-tier repo (published 2026-07-10,
`CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/`GOVERNANCE.md`/`LICENSE`/
`README.md`/`SECURITY.md`/`blueprint.edn`/`docs/business-model.md`/
`docs/operator-guide.md` — no `deps.edn`, no `src`, no `test`). That
pre-existing README already frames the vertical on a richer premise than
this ADR's actor implements: package-tour design/assembly, organizer
(vs. booking-intermediary) liability, bonding/insolvency-protection
compliance scope (e.g. UK ATOL, EU Package Travel Directive 2015/2302),
robotics-assisted itinerary assembly, and a "Tour Operator Governor"
gating package release/component booking/reconciliation-record
publication. None of that pre-existing boilerplate — README, blueprint.edn's narrative fields, docs/ — was rewritten or removed; only
`blueprint.edn`'s trailing maturity fields were appended and the missing
code/test module set was added on top.

**Scope of the actor implemented here**: OPERATIONS COORDINATION ONLY,
mirrored closely on the verified, independently-re-tested sibling
`cloud-itonami-isic-5520` (Camping grounds/RV parks/trailer parks
operations coordination) module shape (advisor/governor/phase/operation/
store/sim, `langgraph-clj` StateGraph, independent Governor, phase 0→3
rollout, string-keyed directory, append-only audit ledger). Domain-adapted
for tour-operator back-office coordination: itinerary/participant-manifest/
excursion-record logging, itinerary/guide/vendor scheduling, local-vendor/
guide settlement coordination, and traveler-safety-concern flagging
(hazard/incident/medical concern) — never finalizing a traveler-safety-
clearance decision, issuing a resumption/go-ahead order for a held
excursion, or overriding a traveler-safety authority's decision. The
richer package-design/bonding/robotics-dispatch capability described in
the pre-existing blueprint is a separate, not-yet-wired concern this
actor's coordination proposals could eventually feed into, always gated
the same way — this ADR does not implement package release, component
booking, or robot dispatch.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-tour-record` — itinerary/participant-manifest/excursion data logging
- `:schedule-tour-operation` — itinerary/guide/vendor scheduling proposal
- `:coordinate-vendor-settlement` — local-vendor/guide settlement coordination
- `:flag-traveler-safety-concern` — surface a hazard/incident/medical concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the Wave 4 person-facing-service safety guardrail (ADR-2607152500):
tour operations have a direct traveler-safety dimension (excursion
safety, guide-led activities), so the closed op allowlist NEVER includes
any op that directly finalizes a traveler-safety-clearance decision
(e.g. clearing an excursion as safe after a reported hazard) — that is
always either a hard permanent block (see check 3) or, for the one
"flag a concern" op, an always-escalate op, never an auto-commit-eligible
op in any phase's `:auto` set.

1. **Tour unverified** — the target tour-booking record must exist in
   the store AND be independently `:registered?`/`:verified?` before any
   proposal for it may commit or even escalate. Re-derived from the
   tour's own store record every time, never from the proposal's own
   `:tour-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a traveler-safety-clearance decision,
   directly issuing a resumption/go-ahead order for a held excursion, or
   overriding a traveler-safety authority's decision, is a permanent,
   un-overridable block. Evaluated **unconditionally** on every proposal
   via a lower-cased substring scan of the proposal's own content
   (English + Japanese term list) — never trusting the advisor's own
   framing.

   Per this fleet's known self-tripping bug class — a governor's own
   scope-exclusion term list phrased as a bare noun (e.g. "safety")
   accidentally matching inside the mock advisor's own DEFAULT
   rationale/disclaimer text for a legitimate, allowed proposal — every
   term in `touroperatorops.governor/scope-excluded-terms` is phrased as
   the finalization/execution ACTION (e.g. "finalize the excursion-safety
   clearance", "安全クリアランスを確定"), never a bare noun. A dedicated
   regression test,
   `touroperatorops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`,
   runs the default mock advisor's own proposal for every allowed op
   (including `:flag-traveler-safety-concern`, whose entire job is to
   talk about traveler-safety concerns) through the governor and asserts
   none of them ever trip `:scope-excluded` or `:op-not-allowed`. A
   companion test, `legitimate-traveler-safety-concern-is-not-scope-excluded`,
   confirms describing a held excursion / blocked route as raw
   observation does not trip the gate either — only a proposal claiming
   to actually *finalize* the clearance/resumption/override is blocked.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-traveler-safety-concern` — always, regardless of confidence.
- `:coordinate-vendor-settlement` above a $2,000 estimated-amount threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`touroperatorops.phase`'s 0→3 rollout table independently agrees:
`:flag-traveler-safety-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant
(exercised directly by `traveler-safety-concern-holds-when-not-enabled` /
`traveler-safety-concern-escalates-when-enabled` /
`traveler-safety-concern-never-in-any-auto-set`). The high-cost
vendor-settlement escalate gate requires no extra phase-layer code: the
governor's own `high-stakes?` already turns the base disposition into
`:escalate` before the phase gate runs, so phase 3's `:auto` membership
for `:coordinate-vendor-settlement` never applies to an over-threshold
settlement (exercised by `high-cost-vendor-settlement-always-escalates` /
`low-cost-vendor-settlement-does-not-force-escalation`).

### 3. Module shape

`touroperatorops.store` (MemStore, string-keyed tour directory),
`touroperatorops.advisor` (TourOperatorOpsAdvisor, mock + a real-LLM seam,
plus an `:out-of-scope?` test hook that deliberately drafts
traveler-safety-clearance-finalization/excursion-resumption-scope content
so the governor's scope scan can be exercised end to end),
`touroperatorops.governor` (TourOperatorGovernor), `touroperatorops.phase`
(0→3 rollout), `touroperatorops.operation` (the `langgraph-clj`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval), `touroperatorops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Actor repo `cloud-itonami/cloud-itonami-isic-7912` (pre-existing
  `:blueprint`-tier repo, NOT freshly scaffolded) filled in: `deps.edn`,
  `.gitignore`, full `src/touroperatorops/*.cljc` +
  `test/touroperatorops/*.clj` module set added on top of the existing
  boilerplate docs, `README.md` and `blueprint.edn`'s narrative fields,
  which are preserved unchanged; `blueprint.edn` gained trailing
  `:itonami.blueprint/maturity :implemented` + `:db/id -1` fields,
  matching the format already used by other implemented actors in this
  fleet (e.g. `cloud-itonami-isic-8010`). Committed and pushed directly
  to `main` (fast-forward from `e9a7421`, no divergence):
  `f08265b9aa80060e6de6077776d1bb1264b05823`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 49 tests containing 142 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint` (clj-kondo): 0 errors, 0
  warnings. `clojure -M:run` (demo driver) exercises every scenario
  (phase-1 approval-gated commit, phase-3 auto-commit for all three
  non-safety ops, always-escalating safety-concern flag, over-threshold
  settlement escalation, and all three permanent HARD-hold paths:
  unregistered tour, unverified tour, non-`:propose` effect, and
  scope-excluded content drift) end to end without error.
- Registry entry updated in place (exact-text edit of the existing
  `{:id "7912" ...}` block only, not appended, not touching any other
  entry): `:maturity :implemented` added (previously absent — the entry
  resolved to `:blueprint` only via the `:repo`-present fallback in
  `kotoba.industry/maturity-of`). `:repo` and `:business-id` were already
  correct (`https://github.com/cloud-itonami/cloud-itonami-isic-7912` /
  `cloud-itonami-7912`, matching the pre-existing `blueprint.edn`'s own
  `:itonami.blueprint/id`) and were left unchanged, as were
  `:required-technologies`/`:optional-technologies`/`:operating-states`.
  Landed via a Contents-API single-file PUT directly to `kotoba-lang/
  industry`'s `main` (commit `5ce8835cc3b5c23f7f8ea76fb25d845f2efc4937`),
  not a branch+merge — an initial branch+merge attempt (`gh api .../
  merges`) hit a false-positive `409 Merge conflict` even though the
  concurrent sibling edit touched a textually disjoint part of the
  registry: `registry.edn` is serialized as one single very long line, so
  git's line-granularity 3-way merge treats ANY two concurrent edits
  anywhere in the file as colliding on "the same line," regardless of
  whether the actual text spans overlap. Direct Contents-API PUT (sha-
  checked optimistic concurrency on the whole-file blob, not a line
  diff) sidesteps that failure mode entirely, consistent with this
  fleet's existing manifest/west.yml guidance for hot generated files.
- `test/kotoba/industry_test.clj` (`kotoba-lang/industry`, commit
  `973f413825d8107095e6322e2788093122597210`) updated with this
  promotion's own detailed `7912` corroboration block, plus mechanical
  (not-our-own-work) corrections for `7911`/`8121`/`8220`/`8219` spot
  checks that had gone stale from concurrent sibling landings during
  this session's own multi-attempt PUT sequence, and the
  `maturity-summary` tier-count assertions bumped to the true
  live-recomputed values.
- This was the LAST batch of the blueprint-tier cleanup sweep (6 targets:
  `8130`/`8121`/`8220`/`8219`/`7911`/`7912`). Re-verified against a
  brand-new fresh clone of `kotoba-lang/industry` + `kotoba-lang/
  technology` after all six landed: `clojure -M:test` ->
  **`Ran 15 tests containing 1063 assertions. 0 failures, 0 errors.`**
  Live `(kotoba.industry/maturity-summary)` on that fresh clone:
  **`{:total 649, :spec 232, :blueprint 0, :implemented 417}`** — the
  fleet-wide `:blueprint`-tier (nil-maturity, unresolved-blueprint) count
  has reached **zero**. No mojibake/null-bytes/BOM found in
  `registry.edn` on re-inspection.

## References

- `cloud-itonami-isic-5520/` (module-shape mirror, ADR-2607121200-era
  Wave 4 precedent — verified working, independently re-tested)
- `cloud-itonami-isic-7911/` (sibling Wave 4 travel-industry precedent,
  built in the same batch by a sibling agent — distinct booking-
  intermediary scope, see the pre-existing README's "Scope note")
- `cloud-itonami-isic-5610/` (ADR-2650005610, same Wave 4
  coordination-only pattern, same self-tripping-bug regression-test
  discipline)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"7912"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan)
