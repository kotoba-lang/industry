# ADR-2630801000: cloud-itonami-isic-8010 — Private Security Operations Coordination

## Status

Accepted. `cloud-itonami-isic-8010` promoted from `:blueprint` (repo
published, docs only, no code) to `:implemented` in the
`kotoba-lang/industry` registry.

## Context

ISIC Rev.5 8010 (Private security activities — guarding, patrol, escort
and monitoring services provided under contract to a client site) already
had a published repo (`cloud-itonami/cloud-itonami-isic-8010`) from an
early bulk-scaffolding pass: `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `LICENSE`, `README.md`, `SECURITY.md`, `blueprint.edn`,
and `docs/{business-model,operator-guide}.md` — no `deps.edn`, no `src`,
no `test`. This is a legitimate `:blueprint`-tier entry, not a fresh
404 target: this ADR fills in the missing implementation on top of the
existing repo rather than recreating it.

**This is a genuinely high-stakes domain**: private security guard
services can involve use-of-force, detention, and armed-response
decisions in the real world. This ADR applies a stricter governor
discipline than most cloud-itonami verticals as a result (see Decision
§2 below) and explicitly rejects the earlier draft boilerplate's framing
(a "robotics-premised" actor whose governor gates a guard/robot
`:dispatch` action, requiring human sign-off only for `:high`/
`:safety-critical` cases) in favor of a structurally narrower one: this
actor never proposes, never gates-with-approval, and never comes anywhere
near dispatch/force/detention — those are always a permanent HARD block,
not a rollout milestone or an approval-gated action.

**Scope**: COORDINATION ONLY, module shape mirrored closely on the
sibling `cloud-itonami-isic-873` (Residential care, ADR-2607152700)
actor — `advisor`/`governor`/`phase`/`operation`/`store`/`sim`,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed post-assignment (site-contract) directory, append-only
audit ledger. Domain-adapted for private security guard services:
patrol/checkpoint/incident-report data logging, guard-shift/post-
assignment scheduling proposals, incident/suspicious-activity concern
flagging (always escalates), and uniform/equipment supply coordination —
never a guard dispatch-to-respond decision, a use-of-force decision, or
a detention/arrest decision. This actor NEVER dispatches a guard to an
incident and NEVER authorizes any physical intervention; it only
coordinates the scheduling/logging/reporting layer around an already
human-directed security operation.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-patrol-record` — patrol/checkpoint/incident-report DATA logging (already-observed events only)
- `:schedule-guard-shift` — guard-shift/post-assignment scheduling PROPOSAL only, never a final binding assignment
- `:coordinate-equipment-supply` — uniform/equipment (radios, flashlights, hi-vis vests) consumable procurement coordination — never weapons or restraint equipment
- `:flag-incident-concern` — suspicious-activity/incident concern surfacing — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Post-assignment unverified** — the target site-contract record must
   exist in the store AND be independently `:registered?`/`:verified?`
   before any proposal for it may commit or even escalate. Re-derived
   from the assignment's own store record every time, never from the
   proposal's own `:assignment-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   claims a guard dispatch-to-respond, use-of-force-authorization,
   detention, or arrest EXECUTION action, is a permanent, un-overridable
   block. Evaluated **unconditionally** on every proposal via a
   lower-cased substring scan of the proposal's own content (English +
   Japanese term list) — never trusting the advisor's own framing.

   Any proposal to directly dispatch a guard to respond, authorize use of
   force, or authorize detention/arrest is always either this permanent
   HARD block or (for the one op that legitimately surfaces such
   concerns for a human) an always-escalate op — never an op eligible
   for auto-commit at any rollout phase.

**Self-trip discipline (a known bug pattern in this actor family)**:
multiple sibling actors in this fleet have independently discovered and
fixed the same bug class — a scope-exclusion term list phrased as a bare
noun (e.g. `"force"`, `"拘束"`) can accidentally match inside the mock
advisor's own DEFAULT rationale/disclaimer text for a legitimate, allowed
proposal, causing the actor to self-block its own happy path. This ADR's
implementation applies two independent mitigations: (a) every
`scope-excluded-terms` entry is phrased as the finalization/execution
ACTION (`"authorize the use of force"`, `"dispatch a guard to respond"`,
`"実力行使を許可"`), never a bare topic noun; (b) the default mock
advisor's four proposal generators never restate the excluded concepts
at all, even to disclaim them (belt and suspenders — the safest fix for
a disclaimer trap is not writing the disclaimer sentence that could
collide). A dedicated regression test,
`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
`guardops.governor-test`, asserts every default proposal for every
allowed op clears `scope-exclusion-violations` against a verified
assignment — this test was required to pass, and did on first run,
before the build was considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-incident-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`guardops.phase`'s 0→3 rollout table independently agrees:
`:flag-incident-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant (a
dedicated structural test, `incident-concern-never-in-any-auto-set`,
asserts this directly against the `phases` table rather than only
against specific phase numbers).

### 3. Module shape

`guardops.store` (MemStore, string-keyed post-assignment directory),
`guardops.advisor` (GuardOpsAdvisor, mock + a real-LLM seam, plus an
`:out-of-scope?` test hook that deliberately drafts dispatch/use-of-force
content so the governor's scope scan can be exercised end to end),
`guardops.governor` (GuardOpsGovernor), `guardops.phase` (0→3 rollout),
`guardops.operation` (the `langgraph-clj` StateGraph: intake → advise →
govern → decide → commit | hold | request-approval), `guardops.sim`
(demo driver, `clojure -M:run`).

### 4. Documentation correction (not just addition)

The pre-existing boilerplate `README.md`/`GOVERNANCE.md`/
`docs/business-model.md`/`docs/operator-guide.md` described a broader,
"robotics-premised" design where the governor gates guard/robot dispatch
itself and requires human sign-off only for `:high`/`:safety-critical`
actions. This is inconsistent with the stricter scope actually
implemented (dispatch/force/detention are never proposed or gated at
all, only permanently blocked or, for concern-flagging, always
escalated) and was corrected in place rather than left standing —
leaving contradictory documentation in a domain this safety-adjacent
would be actively misleading. `blueprint.edn`'s `:robotics` field was
also corrected from `true` to `false` and `:required-technologies`
trimmed (`:robotics`/`:labor` removed) to match, mirroring the same
correction ADR-2607152700 made for `cloud-itonami-isic-873`.
`CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/`LICENSE`/`SECURITY.md` (generic,
no domain-specific claims) were left untouched.

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "8010" ...}` block, not appended): `:maturity` added
  (`:implemented`, previously absent — resolved to `:blueprint` via the
  `:repo`-present fallback), `:required-technologies` trimmed
  (`:robotics`/`:labor` removed — coordination-only, matching the
  `cloud-itonami-isic-873` precedent), `:operating-states` updated to
  `[:intake :advise :govern :approve :commit :audit]` to match the
  actor's actual state machine and this fleet's coordination-actor
  convention (removing the literal `:dispatch` state, which is
  antithetical to this actor's scope). `:repo`/`:business-id` were
  already correct and left unchanged.
- Actor repo `cloud-itonami/cloud-itonami-isic-8010` filled in (existing
  blueprint-tier repo, not recreated) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 43 tests containing 127 assertions, 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`guardops.sim` demo) walked all scenarios (phase-1
  approval-gated commit, phase-3 auto-commit for the three non-escalating
  ops, always-escalating incident-concern flag, and all four HARD-hold
  scenarios: unregistered assignment, unverified assignment, non-
  `:propose` effect, and scope-excluded dispatch/force content) without
  error — 52 commits, 6 escalations, 36 holds across the run.

## References

- `cloud-itonami-isic-873/` (module-shape mirror, ADR-2607152700 —
  coordination-only reference actor, also the source of the same
  documentation-correction pattern applied here)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"8010"` entry
- ADR-2607121000 (Wave definition)
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
