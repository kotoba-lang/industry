# ADR-2607161700: cloud-itonami-isic-4210 (Construction of roads and railways) operations-coordination actor -- full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-4211 (Community Building Construction --
the robotics-premise construction-domain reference this actor follows
structurally, narrowed), cloud-itonami-isic-4311 (Demolition -- the
closest domain analog, same coordination-only shape, the reference this
actor mirrors most closely), ADR-2607011000 (ISIC section coverage), the
`kotoba-lang/industry` registry's `"4210"` catalog entry

## Context

`kotoba-lang/industry`'s registry carried `"4210"` (`{:id "4210" :name
"Construction of roads and railways" ...}`) as `:maturity :spec`, with a
stale `:repo` pointing at a non-existent `gftdcojp/cloud-itonami-F4210`
placeholder and a robotics-oriented `:required-technologies` list
(`[:robotics :identity :forms :dmn :bpmn :audit-ledger :cae]`) left over
from an earlier, never-implemented sketch. No repository existed at
either the stale `gftdcojp` location or the fleet's actual
`cloud-itonami` org (`gh api repos/cloud-itonami/cloud-itonami-isic-4210`
returned 404) -- this was a genuine fresh-scaffold gap, not a
mislabeling of an already-implemented class. The ID/name match was
independently re-verified against a fresh, read-only clone of
`kotoba-lang/industry` before any implementation work began, per this
fleet's post-incident verification discipline (two prior agents had
previously mislabeled their assigned ISIC class: 0892 assumed to be salt
when it is peat, 0144 assumed to be swine when it is sheep-goats).

This ADR documents the actual, verified implementation that closes that
gap.

## Decision

Implement `cloud-itonami-isic-4210` as a road/railway-construction-
project OPERATIONS COORDINATION actor, mirroring `cloud-itonami-isic-
4311` (Demolition)'s verified coordination-only pattern in shape, and
`cloud-itonami-isic-4211` (Community Building Construction)'s robotics-
premise actor pattern structurally but narrowed -- **this actor holds no
heavy-equipment-control authority and no engineering-design/grade-plan-
finalization authority**, both permanent structural blocks, never a
rollout milestone still to come.

1. **`roadrail.governor`** (Road-Rail Governor) -- independent
   compliance layer, EIGHT hard checks, all un-overridable by human
   approval:
   - unknown op (outside the closed 4-op allowlist) -- structural
   - `:effect` not `:propose` -- structural, defense-in-depth
   - forbidden action class -- a proposal's `:value` carrying
     `:equipment-control?` / `:direct-actuation?` / `:finalizes-
     engineering-design?` / `:finalizes-grade-plan?` true -- structural,
     permanent, un-overridable by any human approval
   - site/permit record not independently verified/registered
     (`:site-verified?` ground truth, set only via a separately-
     committed `:log-site-record`) -- for
     `:schedule-construction-operation` / `:flag-safety-concern` /
     `:order-supplies`
   - legal-basis missing for `:schedule-construction-operation`
     (`roadrail.facts` per-jurisdiction catalog)
   - utility-locate incomplete (`:utility-locate-completed?` ground
     truth) for `:schedule-construction-operation`
   - notification lead time insufficient -- independently recomputed
     from the site's own recorded `:notification-lead-hours-actual`
     against the jurisdiction's regulatory minimum, quantitative
     jurisdictions only (JPN/USA); never fires for the honestly
     `:qualitative` DEU/EU jurisdiction
   - unresolved safety concern on file (`:safety-concern-unresolved?`
     ground truth) for `:schedule-construction-operation`

   Soft escalations (human sign-off required, not a rejection):
   `:flag-safety-concern` and `:schedule-construction-operation` are
   BOTH unconditional members of `high-stakes` -- they always escalate
   to a human, at every phase, regardless of confidence or governor
   cleanliness (belt-and-suspenders with `roadrail.phase`, which also
   never places either in any phase's `:auto` set); `:order-supplies`
   escalates when its cost exceeds `supply-order-cost-threshold-usd`
   (5000) or confidence is below `confidence-floor` (0.6).

2. **`roadrail.facts`** -- per-jurisdiction (JPN/USA/DEU) legal-basis
   catalog, all citations verified against primary sources (e-Gov 法令
   検索, eCFR/osha.gov, gesetze-im-internet.de/EUR-Lex) before being
   written, the same honest-coverage discipline `construction.facts`/
   `demolition.facts` established:
   - JPN: utility-strike prevention -- 労働安全衛生規則（昭和47年労働省令
     第32号）第355条; traffic control -- 道路交通法第77条第1項第1号
     （道路使用許可）; notification lead time -- 建設リサイクル法第10条・
     同法施行令第2条第1項第4号（土木工作物、請負代金500万円以上、着手7日前
     ＝168時間までの届出）, `:quantitative`, 168 hours.
   - USA: utility-strike prevention -- 29 CFR 1926.651(b) (OSHA
     excavation standard, 24-hour utility-locate-response floor,
     re-expressed as a minimum recorded lead pause -- see `roadrail.
     facts` ns docstring for the honest modeling translation); traffic
     control -- 23 CFR Part 630 Subpart J + MUTCD Part 6 (23 CFR
     655.603); permit -- 23 CFR 645.213 (utility use-and-occupancy
     agreement); `:quantitative`, 24 hours.
   - DEU (EU proxy): utility-strike prevention -- Council Directive
     92/57/EEC Art.3; traffic control -- StVO §45 Abs.6; honestly
     `:qualitative` -- no fixed EU-wide numeric lead time, never
     fabricated.
   - Scope note: this R0 catalog's citations are grounded in the
     public-right-of-way legal exposure common to BOTH road and railway
     construction (utility-strike prevention, traffic-control/road-
     occupancy permitting). Railway-specific technical/track-safety-
     standard regimes (e.g. USA's FRA Track Safety Standards, 49 CFR
     Part 213) are real but explicitly out of scope for this slice --
     documented as a future extension in the `roadrail.facts` ns
     docstring, never fabricated in.
3. **`roadrail.store`** -- `Store` protocol, `MemStore` (default) +
   `DatomicStore` (via `langchain.db` + `langchain-store.core`,
   ADR-2607141600 -- no hand-rolled EDN-blob codec), proven to satisfy
   the same contract test.
4. **`roadrail.advisor`** (Road-Rail Advisor) -- mock deterministic
   advisor + `llm-advisor` seam for a real `langchain.model/ChatModel`;
   structurally cannot generate ops outside the governor's closed
   allowlist; every proposal carries `:effect :propose`.
5. **`roadrail.notify`** -- mail (Resend) + phone (Twilio) safety-
   concern notice dispatch, mock-by-default, fired only after human
   approval (never on an auto-commit).
6. **`roadrail.registry`** -- pure-function site-record-log/schedule-
   proposal/safety-concern-flag/supply-order-proposal record
   construction + rendered safety-concern notice document, citing the
   jurisdiction's utility-locate legal basis inline.
7. **`roadrail.phase`** -- 0->3 staged rollout; `:log-site-record` and
   `:order-supplies` (below the cost threshold) may auto-commit at
   phase 3; `:schedule-construction-operation`/`:flag-safety-concern`
   are deliberately absent from every phase's `:auto` set.
8. **`roadrail.operation`** -- langgraph-clj StateGraph OperationActor:
   `intake -> advise -> govern -> decide -> commit | hold | request-
   approval`, one graph run = one auditable operation, checkpointed,
   `interrupt-before #{:request-approval}` for human-in-the-loop.
9. **`roadrail.sim`** -- demo driver exercising the full coordination
   episode plus every HARD hold and both cross-jurisdiction paths.

### What this actor does NOT do

Explicitly documented (README, this ADR, `roadrail.governor`/`roadrail.
phase` ns docstrings): no heavy-equipment-control commands, no direct
actuation, no finalizing an engineering design or grade plan (earthwork
cross-sections, track/road alignment, pavement/ballast structural
design). These remain the licensed civil engineer / site supervisor's
exclusive authority, permanently, with no actor or human-approval
override path -- matching the CLAUDE.md instruction that this actor
coordinates potential equipment dispatch and never directly actuates,
and that no new Rust/robot-control code be written for it.

### Two real bugs found and fixed during first-pass implementation

- The USA jurisdiction catalog entry initially combined its ROW-permit
  citation (23 CFR 645.213) and its work-zone traffic-control citation
  (23 CFR 630 Subpart J / MUTCD) into a single `:permit-basis` field,
  omitting a distinct `:traffic-control-basis`/`:traffic-control-
  provenance` pair. `roadrail.advisor`'s `:schedule-construction-
  operation` cites `(:traffic-control-provenance sb)` as the proposal's
  `:spec-basis` value; with that key absent it resolved to `nil`,
  tripping `roadrail.governor`'s `legal-basis-missing-violations` check
  even for a fully clean USA site (`usa-cross-jurisdiction-happy-path-
  always-escalates-then-approved` failed with an unexpected `:hold`).
  Fixed by adding the missing `:traffic-control-basis`/`-provenance`
  pair, verified against the real 23 CFR 630 Subpart J text (already
  independently sourced), and re-running the test suite green.
- `roadrail.store`'s `DatomicStore` `site-spec` field-spec omitted
  `:grading-percent-complete` (the domain's `:log-site-record` progress
  field, analogous to `demolition.store`'s `:hazmat-detected?`), so a
  patch through `DatomicStore` silently dropped it while `MemStore`'s
  generic `merge` round-tripped it fine -- caught by the shared
  `store_contract_test.clj` run against both backends. Fixed by adding
  the field to `site-spec`.

## Verification

- `cloud-itonami-isic-4210`: `clojure -M:test` -- raw final line: `Ran
  68 tests containing 258 assertions.` / `0 failures, 0 errors.`
  (facts catalog honesty/coverage; governor contract covering all eight
  hard checks individually, both escalation-then-approval and
  approval-rejection paths, auto-commit at phase 3 for `:log-site-
  record`/`:order-supplies` below threshold, cost-threshold escalation,
  notify-only-after-approval, closed-allowlist enforcement, forbidden-
  action-class markers exercised directly against `governor/check`;
  phase-table structural invariants; registry record construction +
  validation + notice rendering; store contract run against BOTH
  MemStore and DatomicStore).
- `clojure -M:lint` -- 0 errors, 1 warning (`clojure.string` flagged
  unused in `roadrail.notify` because all its usages are inside `#?(:clj
  ...)` branches -- confirmed to be the SAME pre-existing warning in the
  `cloud-itonami-isic-4311` reference's `demolition.notify`, not a
  regression).
- `clojure -M:dev:run` -- runs cleanly end-to-end: full happy-path
  episode (log -> schedule -> flag -> resolve -> reschedule -> order
  supplies below/above threshold), all six standalone HARD-hold
  scenarios (uncovered jurisdiction, unverified site, incomplete
  utility locate, insufficient notification lead time, unresolved
  safety concern, op outside the closed allowlist), plus USA and DEU
  cross-jurisdiction schedule walkthroughs -- no exceptions.
- Commit `cc2ef3da9d6012f5e92465f8c9075a3d5c4e70cf` pushed directly to
  `cloud-itonami-isic-4210`'s `main` (fresh repository, initial commit).
- Post-merge re-verification (fresh `git clone --depth 1` into a NEW
  scratch directory, `kotoba-lang/technology` cloned as a `../
  technology` sibling per this fleet's standard verification protocol):
  re-ran `clojure -M:test` against the clean clone -- same green
  result, raw output pasted in the closing report.

## Consequences

(+) `cloud-itonami-isic-4210` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor in this
fleet, closing a genuine fresh-scaffold gap (no prior repository, no
prior fabricated report to correct).
(+) `kotoba-lang/industry` registry `"4210"` entry updated in place to
`:maturity :implemented`, corrected `:repo`/`:business-id` (from the
stale non-existent `gftdcojp/cloud-itonami-F4210` to the real
`cloud-itonami/cloud-itonami-isic-4210`), corrected
`:required-technologies` (dropped the unused robotics-oriented
`:robotics`/`:dmn`/`:bpmn`/`:cae` list left over from the never-
implemented sketch, in favor of what is actually implemented:
`:identity`/`:forms`/`:audit-ledger`/`:notifications`), and an ADR
reference.
(+) A verified, citation-backed `roadrail.facts` catalog exists for
three jurisdictions (JPN/USA/DEU) covering the public-right-of-way legal
exposure common to both road and railway construction -- extending
coverage to more jurisdictions, or adding a railway-specific technical-
standard catalog (FRA 49 CFR 213/214 and equivalents), is additive
future work, not required for this ADR's verification bar.
(-) `roadrail.facts` does not model railway-specific track-safety-
standard regimes (only the shared public-right-of-way/utility-strike/
traffic-control legal basis); the ns docstring explicitly flags this
scope boundary so it is not mistaken for broader coverage than it is.
(-) No real LLM is wired into the default advisor (mock-by-default,
matching every sibling actor in this fleet); `roadrail.advisor/llm-
advisor` is the documented seam for swapping in a real
`langchain.model/ChatModel`.
