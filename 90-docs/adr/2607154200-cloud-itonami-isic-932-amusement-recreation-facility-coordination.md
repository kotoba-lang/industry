# ADR-2607154200: cloud-itonami-isic-932 — Amusement/Recreation Facility Operations Coordination

## Status

Accepted. `cloud-itonami-isic-932` promoted from `:spec` (placeholder in
`kotoba-lang/industry` registry) to `:implemented`. Third Wave 4 actor in
the ISIC-90/92/93 arts/entertainment/recreation cluster, following
`cloud-itonami-isic-900` (performing-arts venue coordination) and
`cloud-itonami-isic-931` (sports facility coordination).

## Context

ISIC Rev.4 932 (Other amusement and recreation activities) covers amusement
parks, arcades and recreational facilities not elsewhere classified. This
actor is built as a fresh scaffold under the verified-redo discipline
established by ADR-2607152500 (Wave 4 rollout amendment), closely mirrored
on `cloud-itonami-isic-931`'s module shape and scope-conservatism
constraints (advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout).

**Scope**: ADMINISTRATIVE COORDINATION ONLY for an amusement/recreation
facility's back office — facility/attraction-area booking, maintenance
scheduling logistics, guest-services coordination — never ride-safety
inspection sign-offs, operational-readiness (go/no-go) decisions,
pricing/programming policy, or any safety-authority override.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:schedule-facility-booking` — venue/attraction-area booking scheduling
  (private events, group bookings) — explicitly NOT a ride-operational-
  readiness decision
- `:coordinate-maintenance-schedule-proposal` — administrative
  maintenance-scheduling PROPOSAL only (when a technician visits) —
  explicitly NOT a safety-inspection sign-off or ride-certification
  decision, never binding
- `:coordinate-supply-request` — non-safety-critical consumables
  (guest-services/office supplies) — explicitly NEVER safety equipment
  or ride-mechanical parts
- `:coordinate-guest-services-logistics` — guest check-in/ticketing/
  wayfinding logistics coordination
- `:flag-safety-concern` — facility/ride/guest safety concerns (equipment
  malfunction report, crowd-safety issue) — **ALWAYS escalates**, never
  auto-commits at any phase

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope, not a separate "unknown op"
carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Facility/booking-record unverified** — the target facility must exist
   in the store AND be independently `:registered?`/`:verified?` before
   any proposal for it may commit or even escalate. Re-derived from the
   facility's own store record every time, never from the proposal's own
   claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches ride-safety-inspection/certification, operational-readiness
   (go/no-go), pricing/programming policy, or safety-authority territory,
   is a permanent, un-overridable block. Evaluated unconditionally via a
   lower-cased substring scan of the proposal's own content (English +
   Japanese term list), qualified so this HARD block never collides with
   the actor's own core valid use case — legitimately flagging an
   observed safety concern via `:flag-safety-concern` (which is exempt
   from the scan since it always escalates rather than auto-committing).

Escalation (SOFT, always human sign-off, only reached when the governor is
otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence.

`amusementfacilityops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's auto-commit set,
at any phase.

### 3. Module shape

`amusementfacilityops.store` (MemStore, string-keyed facility/booking
directory, append-only ledger), `amusementfacilityops.advisor`
(mock + real-LLM seam), `amusementfacilityops.governor`
(three HARD checks above), `amusementfacilityops.phase` (0→3 rollout),
`amusementfacilityops.operation` (`langgraph-clj` StateGraph: intake →
advise → govern → decide → commit | hold | escalate),
`amusementfacilityops.sim` (demo driver, 5 scenarios).

## Consequences

- Registry entry updated (`:id "932"` block): `:repo` set to
  `"https://github.com/cloud-itonami/cloud-itonami-isic-932"`,
  `:business-id` to `"cloud-itonami-isic-932"`, `:maturity` to
  `:implemented`, `:robotics` stripped from `:required-technologies`
  (coordination-only, no robotics tech).
- Actor repo `cloud-itonami/cloud-itonami-isic-932` scaffolded and pushed
  to `main`, public visibility.
- Test suite (store, governor, operation, phase namespaces) run directly
  by the building session: all green, 0 failures/errors. `sim` demo
  walked all 5 scenarios (happy path, unverified facility, scope
  exclusion, safety-concern escalation, guest-services supply) without
  error.
- Registry edit independently validated via a real EDN parser
  (`clojure.edn/read-string` through nbb) both before and after the
  edit — 648 industries before and after, no corruption — following the
  lesson from the isic-931 batch, where an earlier exact-string-replace
  accidentally consumed the following entry's opening and left the file
  syntactically invalid EDN (caught and repaired separately).
- This ADR pair itself was authored and landed in a follow-up step after
  the original building session left it uncommitted to `main` (pushed to
  a local-only worktree branch that was never actually pushed/merged);
  content here is reconstructed from the live actor repo's source
  (`governor.cljc` etc.) and the building session's own report, not
  invented.

## References

- `cloud-itonami-isic-931` (module-shape mirror, ADR-2607154100 —
  sports facility coordination, immediate sibling)
- `cloud-itonami-isic-900` (ADR-2607153800 — performing-arts venue
  coordination, cluster opener)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"932"` entry
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (Wave definition, ISIC 932 within arts/entertainment/
  recreation)
