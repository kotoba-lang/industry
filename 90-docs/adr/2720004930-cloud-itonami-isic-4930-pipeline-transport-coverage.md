# ADR-2720004930: cloud-itonami-isic-4930 — Transport via Pipeline Operations Coordination (Administrative/Logistics Only)

## Status

Accepted. `cloud-itonami-isic-4930` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-H4930` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 4930 (Transport via pipeline) is a Wave 2
(coordination/logistics/trade, ADR-2607121000) target, and the first
class in this wave's transition from retail to TRANSPORT classes.
Identity independently verified against a fresh clone of
`kotoba-lang/industry` before any work began, per this fleet's ID/
name-mismatch caution: the live `{:id "4930" ...}` entry's `:name` is
exactly "Transport via pipeline" — no class mismatch found. There is no
separate 3-digit group entry for 4930 specifically (`{:id "493" ...}`
exists in the registry as a distinct, redundant group-level entry and
was left untouched, per instruction, regardless of its own content).

**Distinct from sibling `cloud-itonami-isic-4950`** ("Transport via
pipeline (petroleum)", already `:implemented`, its own
`:pipeline-integrity-governor` and `pipeline.*` namespace): 4950 is a
more specific petroleum-batch-custody class whose actor covers batch
intake through pipeline-integrity/custody/bonding-grounding regulatory
assessment, batch dispatch and delivery settlement — a genuine
dual-actuation shape, though even there dispatch/settlement are never
autonomous at any phase, always human sign-off. 4930 (this ADR) is the
generic "Transport via pipeline" class and was deliberately scoped
**narrower and more conservative than 4950**: a purely
administrative/logistics-coordination actor with **no dispatch or
delivery-settlement op at all** — see Decision §1.

## HIGH-HAZARD CRITICAL INFRASTRUCTURE — the strictest safety guardrail applied in this batch

Pipeline transport (oil, gas, chemical products via pipeline) carries
catastrophic-failure-mode risk (explosion, toxic release, environmental
contamination) — the highest-stakes domain among this batch's transport
classes. This actor is **coordination/monitoring-only by construction**:
the closed op allowlist contains **no op that could even loosely be
read as adjusting pipeline operating parameters**, not even as a gated
"propose" op. Unlike some sibling actors in this fleet (which propose
but never auto-commit a sensitive action), this actor structurally does
not have a proposal-generator for anything resembling operational
control — there is nothing to gate, because the capability was never
built.

## Decision

Mirrored closely on the sibling `cloud-itonami-isic-4719`
(non-specialized-store retail)'s verified coordination-only module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed directory,
append-only audit ledger) — the reference this build was explicitly
briefed to mirror for structure only, substantially re-domained for
high-hazard pipeline administration.

### 1. Closed proposal-op allowlist, all `:effect :propose` — administrative/logistics ONLY

- `:log-throughput-record` — flow-volume/billing telemetry record
  logging (read-only observation recording, not a control action)
- `:schedule-inspection-operation` — pipeline-integrity-inspection /
  right-of-way maintenance-crew scheduling proposal
- `:coordinate-maintenance-order` — maintenance-crew/equipment
  procurement proposal, HARD-gated on registered/verified contractor
- `:flag-integrity-concern` — surface a leak-detection/pressure-anomaly/
  corrosion/integrity-inspection-failure concern — **ALWAYS escalates
  immediately**

**Deliberately absent, permanently, not as a rollout gap**: any op that
adjusts, overrides or reports a finalized change to pipeline pressure,
flow rate, valve state, or emergency-shutoff status. No such op exists
in the advisor's proposal-generator set, the governor's allowlist, or
the phase table's write/auto sets — there is no code path that could
ever produce this kind of proposal, let alone auto-commit one.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Segment unverified** — the target pipeline-segment record
   (operator-license + registration) must exist in the store AND be
   independently `:registered?`/`:verified?` before any proposal for it
   may commit or even escalate. Re-derived from the segment's own
   record every time, never from the proposal's own `:segment-id`
   claim.
2. **Contractor unverified** (flagship new check this vertical adds) —
   for `:coordinate-maintenance-order` ONLY, the proposal's own drafted
   `:value` must name a `:contractor-id` that resolves to an
   independently `:registered?`/`:verified?` maintenance-contractor
   record — the same "ground truth, not self-report" discipline as
   check 1, reapplied to the maintenance counterparty.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence. This actor structurally
   has no legitimate `:effect` other than `:propose`, ever.
4. **Scope exclusion / op not allowed** — any proposal (regardless of
   op) whose rationale/summary/citations/draft value touches directly
   finalizing a pipeline-integrity-safety-clearance, adjusting/
   overriding a pressure or flow-rate parameter, or overriding/
   bypassing/disabling an emergency shutoff, is a permanent,
   un-overridable HARD block, evaluated **unconditionally** on every
   proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the
   advisor's own framing. An op outside the closed four-op allowlist is
   the same failure mode, folded into this same check.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "adjusted the pipeline pressure", "overrode the emergency
   shutoff", "圧力を調整した", "緊急遮断を解除した"), never as a bare noun
   (bare "pressure", "shutoff", "valve" or "clearance") that could
   accidentally match inside this same namespace's own default
   mock-advisor text. This mattered concretely here:
   `pipelineops.advisor`'s own default `:flag-integrity-concern`
   rationale legitimately discusses "圧力異常" (pressure anomaly) and
   "漏洩検知" (leak detection) as OBSERVATIONS — a bare-noun term list
   would have self-tripped this actor's own core happy path on every
   single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-integrity-concern` — always, immediately, regardless of
  confidence. Safety-critical, not merely a business-process
  convenience.
- `:coordinate-maintenance-order` above a $5,000 estimated-cost
  threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`pipelineops.phase`'s 0→3 rollout table independently agrees:
`:flag-integrity-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant
(exercised directly by `integrity-concern-holds-when-not-enabled` /
`integrity-concern-escalates-when-enabled` /
`integrity-concern-never-in-any-phase-auto-set`). The high-cost
maintenance-order escalate gate requires no extra phase-layer code: the
governor's own `high-stakes?` already turns the base disposition into
`:escalate` before the phase gate runs.

### 3. Governor-keyword collision check (performed before landing)

`:pipeline-transport-governor` — `gh api search/code` returned zero
hits fleet-wide before adoption. Deliberately distinct from sibling
ISIC 4950's own `:pipeline-integrity-governor` (verified by reading
4950's live `blueprint.edn` directly) and from the `pipelineops.*`
namespace, also verified with zero hits, versus 4950's own `pipeline.*`
namespace.

### 4. Module shape

`pipelineops.store` (MemStore, string-keyed `segments`/`contractors`
directories), `pipelineops.advisor` (PipelineTransportAdvisor, mock +
a real-LLM seam, plus an `:out-of-scope?` test hook that deliberately
drafts pipeline-operational-control-scope content so the governor's
scope scan can be exercised end to end), `pipelineops.governor`
(PipelineTransportGovernor), `pipelineops.phase` (0→3 rollout),
`pipelineops.operation` (the `langgraph-clj` StateGraph: intake →
advise → govern → decide → commit | hold | request-approval),
`pipelineops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4930" ...}` block only, not appended, not touching any other
  entry, not touching the redundant `{:id "493" ...}` group entry):
  `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-H4930` /
  `cloud-itonami-H4930` to
  `https://github.com/cloud-itonami/cloud-itonami-isic-4930` /
  `cloud-itonami-isic-4930`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :logistics]`, matching the registry's
  existing declaration).
- Actor repo `cloud-itonami/cloud-itonami-isic-4930` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 56 tests containing 166 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`pipelineops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating integrity-concern flag,
  always-escalating over-threshold maintenance order, and five
  HARD-hold scenarios: unregistered segment, segment-unverified,
  contractor-unverified maintenance order, non-`:propose` effect, and
  scope-excluded content) without error.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — the reference this
  build was explicitly briefed to mirror, verified working)
- `cloud-itonami-isic-4950/` (adjacent, more specific sibling class —
  read for governor-keyword/namespace collision check and to confirm
  this class is deliberately narrower in scope, not structurally
  mirrored beyond the shared actor pattern)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4930"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
