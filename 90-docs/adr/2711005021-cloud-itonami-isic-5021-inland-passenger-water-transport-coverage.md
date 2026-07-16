# ADR-2711005021: cloud-itonami-isic-5021 — Inland Passenger Water Transport Dispatch/Scheduling Coordination

## Status

Accepted. `cloud-itonami-isic-5021` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-H5021` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 5021 (Inland passenger water transport) is a Wave 2
(coordination/logistics/trade, ADR-2607121000) target, and this batch's
transition from retail to TRANSPORT classes. Identity independently
verified against a fresh clone of `kotoba-lang/industry` before any work
began, per this fleet's ID/name-mismatch caution: the live `{:id
"5021" ...}` entry's `:name` is exactly "Inland passenger water
transport" — river ferries, lake excursion boats, canal passenger boats.
No class mismatch found. The redundant 3-digit group entry `{:id
"502" ...}` (`:superseded-by ["5020"]`) was left untouched, as was
sibling `{:id "5020" ...}` (Water freight transport, petroleum tanker,
already `:implemented`).

**Passenger-safety dimension (highest-stakes among this batch's
passenger classes, given historical ferry-disaster risk)**: inland
passenger water transport has a direct passenger-safety dimension with a
historically severe failure mode (overloading, capsizing). This actor
coordinates SCHEDULING/DISPATCH LOGISTICS ONLY — it never directly
operates a vessel or overrides a captain's safety judgment, and the
closed op allowlist NEVER includes any op that could finalize a
vessel-seaworthiness clearance, override a certified passenger-capacity
limit, or make a captain-fitness determination. `:flag-safety-concern`
is a hard permanent member of every phase's write set but NEVER a member
of any phase's `:auto` set, at any phase — it always escalates to a
human.

**Scope**: ferry/riverboat dispatch/scheduling OPERATIONS COORDINATION,
NOT direct vessel-seaworthiness authority, passenger-capacity-override
authority, or captain-fitness-determination authority. Mirrored closely
on the sibling `cloud-itonami-isic-4719` (non-specialized-store
retail)'s verified coordination-only module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed directory,
append-only audit ledger) — the reference this build was explicitly
briefed to mirror. Domain-adapted for river/lake/canal passenger-vessel
operators: voyage/ridership/incident-report data logging, ferry-
crossing/timetable scheduling, vessel-maintenance procurement
coordination with a registered maintenance contractor, and safety-
concern flagging (vessel defects, overloading risk, captain-fitness
concerns).

Like ISIC 4719, this vertical adds a second counterparty-verification
entity beyond the primary route/vessel record: `contractor-unverified`
gates `:coordinate-maintenance-order` specifically, on top of the
`route-unverified` check that gates every op in the closed allowlist —
the same "ground truth, not self-report" discipline reapplied to the
vessel-maintenance supply chain.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — voyage/ridership/incident-report data logging
- `:schedule-crossing-operation` — ferry-crossing/timetable scheduling proposal
- `:coordinate-maintenance-order` — vessel-maintenance procurement proposal
- `:flag-safety-concern` — surface a vessel-defect/overloading-risk/captain-fitness concern — **ALWAYS escalates, never in any phase's `:auto` set**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Route unverified** — the target route's vessel identity and
   operator-license status must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the route's own record every time,
   never from the proposal's own `:route-id` claim. This check gates
   EVERY op in the closed allowlist.
2. **Contractor unverified** — for `:coordinate-maintenance-order`
   ONLY, the proposal's own drafted `:value` must name a
   `:contractor-id` that resolves to an independently
   `:registered?`/`:verified?` vessel-maintenance contractor. A missing
   or unverified contractor-id is a HARD block — the flagship genuinely
   new check this vertical adds.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) whose
   rationale/summary/citations/draft value touches directly finalizing a
   vessel-seaworthiness clearance, overriding/exceeding a certified
   passenger-capacity limit, or making a captain-fitness determination,
   is a permanent, un-overridable block. Evaluated **unconditionally** on
   every proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term lists), never trusting the
   advisor's own framing. **Every term is phrased as the
   finalization/execution ACTION** (e.g. "cleared the vessel as
   seaworthy", "exceeded the certified passenger capacity", "determined
   the captain fit for duty"), never a bare noun like "seaworthiness",
   "capacity" or "captain fitness" — this actor's own
   `:flag-safety-concern` rationale legitimately discusses "船体不具合疑い"
   (suspected vessel defect), "過積載リスク" (overloading risk) and "船長の
   体調懸念" (captain health concern) as OBSERVATIONS. A bare-noun term
   list would have self-tripped this actor's own core happy path on
   every single run — this is the exact known bug class this fleet has
   independently rediscovered and fixed across multiple sibling actors
   this session. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence, and never a
  member of any phase's `:auto` set at any phase.
- `:coordinate-maintenance-order` above a $2000 estimated-cost
  threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`ferryops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `safety-concern-holds-when-not-enabled` /
`safety-concern-escalates-when-enabled` /
`safety-concern-never-in-any-phase-auto-set`). The high-cost
maintenance-order escalate gate requires no extra phase-layer code: the
governor's own `high-stakes?` already turns the base disposition into
`:escalate` before the phase gate runs, so phase 3's `:auto` membership
for `:coordinate-maintenance-order` never applies to an over-threshold
order (exercised by `high-cost-maintenance-order-always-escalates` /
`low-cost-maintenance-order-auto-commits`).

### 3. Module shape

`ferryops.store` (MemStore, string-keyed `routes` and `contractors`
directories, append-only ledger), `ferryops.advisor`
(FerryDispatchAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?`
test hook that deliberately drafts seaworthiness-clearance/passenger-
capacity-override/captain-fitness-determination-scope content so the
governor's scope scan can be exercised end to end), `ferryops.governor`
(FerryDispatchGovernor), `ferryops.phase` (0→3 rollout),
`ferryops.operation` (the `langgraph-clj` StateGraph: intake → advise →
govern → decide → commit | hold | request-approval), `ferryops.sim`
(demo driver, `clojure -M:run`).

**Governor-keyword collision check performed before this build was
considered done**: `:ferry-dispatch-governor` verified via `gh api
search/code -f q="ferry-dispatch-governor org:cloud-itonami"` returning
zero hits, and `ferryops` namespace likewise verified with zero hits,
including against sibling ISIC 5012 (maritime freight, built
concurrently in this same batch — repo not yet created at check time, no
collision). No rename needed.

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "5021" ...}` block only, not appended, not touching any other
  entry, not touching the redundant `{:id "502" ...}` group entry, not
  touching sibling `{:id "5020" ...}` or `{:id "5022" ...}`):
  `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-H5021` /
  `cloud-itonami-H5021` to
  `https://github.com/cloud-itonami/cloud-itonami-isic-5021` /
  `cloud-itonami-isic-5021`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :logistics]`, already matching the pre-
  existing spec entry).
- Actor repo `cloud-itonami/cloud-itonami-isic-5021` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 56 tests containing 166 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`, and independently re-verified with `clojure
  -M:dev:test`). `clojure -M:lint`: 0 errors, 0 warnings. `clojure
  -M:dev:run` (`ferryops.sim` demo) walked all scenarios (phase-1
  approval-gated commit, phase-3 auto-commit for the three non-safety
  ops, always-escalating safety-concern flag, always-escalating
  over-threshold maintenance order, and four HARD-hold scenarios:
  unregistered route, route registered but unverified, maintenance-order
  naming an unverified contractor, non-`:propose` effect, and
  scope-excluded content) without error.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — the reference this
  build was explicitly briefed to mirror, verified working)
- `cloud-itonami-isic-4789/` (ADR-2710004789 — closest recent same-fleet
  sibling with a comparable ADR pair structure; read for template only,
  not structurally mirrored beyond the shared coordination-only pattern)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"5021"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade; this ADR marks the batch's transition
  from retail to TRANSPORT classes)
