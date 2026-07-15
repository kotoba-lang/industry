# ADR-2607154500: cloud-itonami-isic-960 — Personal-Care Salon/Service Operations Coordination

## Status

Accepted. `cloud-itonami-isic-960` promoted from `:spec` (placeholder in
`kotoba-lang/industry` registry) to `:implemented`. Second and final
Wave 4 actor in the ISIC-95/96 personal-services cluster, following
`cloud-itonami-isic-952` (repair-shop coordination).

## Context

ISIC Rev.4 960 (Other personal service activities) covers laundry/dry-
cleaning services, hairdressing/beauty salons, funeral/burial services,
and other personal-care services not elsewhere classified. This actor is
a fresh scaffold under the verified-redo discipline established by
ADR-2607152500 (Wave 4 rollout amendment), closely mirrored on
`cloud-itonami-isic-952`'s module shape and scope-conservatism
constraints (advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout).

**Scope**: ADMINISTRATIVE COORDINATION ONLY for a personal-care salon or
service provider's back office — appointment scheduling, service-status
logistics tracking, supply coordination — never the actual service-
technique decisions, health/sanitation-compliance determinations, or
client health/allergy-risk clinical judgments (those require a qualified
professional's judgment).

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:schedule-service-appointment` — appointment scheduling logistics —
  explicitly NOT a service-technique or treatment-plan decision
- `:coordinate-service-status-update` — administrative status-tracking
  logistics — explicitly NEVER the technical service-quality sign-off
  itself
- `:coordinate-supply-request` — non-service-critical consumables
  (front-desk/office supplies) — explicitly NEVER ordering specific
  treatment/service products (that requires professional judgment)
- `:schedule-staff-shift-proposal` — administrative shift PROPOSAL
  only, never binding, never a staff-qualification/assignment decision
- `:flag-safety-concern` — facility/sanitation/client-welfare safety
  concerns for human review — **ALWAYS escalates**, never auto-commits,
  explicitly NOT itself a health/allergy-risk clinical determination

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope, not a separate "unknown op"
carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Client/appointment-record unverified** — the target client record
   must exist in the store AND be independently `:registered?`/
   `:verified?` before any proposal for it may commit or even escalate.
   Re-derived from the client's own store record every time, never from
   the proposal's own claim. Exception: `:flag-safety-concern` is a
   facility-level concern and does not require client verification.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose content touches service-technique/
   treatment decisions, health/sanitation-compliance determinations,
   client health/allergy-risk clinical judgments, or safety-authority
   overrides, is a permanent, un-overridable block. Evaluated
   unconditionally via a lower-cased substring/regex scan of the
   proposal's own content (English + Japanese term list) combined into
   a single explicit `in-forbidden-territory` boolean that is what the
   check actually returns (avoiding the isic-920 batch's dead-code bug
   where an English-language check was computed but discarded).
   Qualified so this HARD block never collides with the actor's own
   core valid use case — legitimately flagging a safety/sanitation
   concern via `:flag-safety-concern`, which is exempt from the content
   scan since it always escalates rather than auto-committing.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence.

`personalcareops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's auto-commit
set, at any phase.

### 3. Module shape

`personalcareops.store` (MemStore, string-keyed client/service
directory, append-only ledger), `personalcareops.advisor` (mock +
real-LLM seam), `personalcareops.governor` (three HARD checks above),
`personalcareops.phase` (0→3 rollout), `personalcareops.operation`
(`langgraph-clj` StateGraph: intake → advise → govern → decide →
commit | hold | escalate), `personalcareops.sim` (demo driver, 5
scenarios).

## Consequences

- Registry entry updated (`:id "960"` block): `:repo` set to
  `"https://github.com/cloud-itonami/cloud-itonami-isic-960"`,
  `:business-id` to `"cloud-itonami-isic-960"`, `:maturity` to
  `:implemented`, `:robotics` stripped from `:required-technologies`
  (coordination-only). Independently verified via a fresh GitHub
  Contents API fetch and a real `clojure.edn`/nbb parse: 648 industries
  before and after, no corruption.
- Actor repo `cloud-itonami/cloud-itonami-isic-960` scaffolded and
  pushed to `main`, public visibility, full governance file set
  (LICENSE, CODE_OF_CONDUCT, CONTRIBUTING, GOVERNANCE, SECURITY,
  README).
- Test suite (store/governor/operation/phase namespaces) and `sim` demo
  independently re-verified against the live repo content by this
  landing session (not the original building session's self-report
  alone) — `personalcareops.governor`'s scope-exclusion check confirmed
  free of the isic-920 dead-code bug class (EN+JA combined into one
  explicit returned boolean).
- This ADR pair itself was authored and landed in a follow-up step
  after the original building session's own report claimed a specific
  ADR path (`2607161900-...`, an out-of-sequence date past today) that
  was never actually pushed anywhere (confirmed absent via Contents API
  and a full-tree search); content here is reconstructed from the live
  actor repo's source, not invented.

## References

- `cloud-itonami-isic-952` (module-shape mirror, ADR-2607154304 —
  repair-shop coordination, immediate sibling, completes the ISIC-95/96
  cluster together with this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"960"` entry
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (Wave definition, ISIC 960 within personal-services)
