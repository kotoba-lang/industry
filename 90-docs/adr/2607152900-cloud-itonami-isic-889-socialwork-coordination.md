# ADR-2607152900: cloud-itonami-isic-889 — Non-Residential Social Work Coordination

## Status

Accepted. `cloud-itonami-isic-889` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry) to `:implemented` in the registry.

## Context

ISIC Rev.4 889 (Other social work activities without accommodation)
is Wave 4's second actor target — non-residential community social work including
counseling, referrals, and welfare/benefits-application assistance. Existing
registry entry was a bare placeholder with no actor implementation or repo. This is a
fresh, from-scratch scaffold built as the second Wave 4 actor under the
verified-redo discipline established by ADR-2607152500 (Wave 4 rollout
amendment) and exemplified by ADR-2607152300 (ISIC-0520 lignite mining).

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-873` (Residential care for elderly/disabled)'s module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed client directory,
append-only audit ledger). Domain-adapted for non-residential social work:
client-contact logging, appointment scheduling, referral coordination (to
external services — never the receiving service's own decision), benefits-
application assistance tracking (never eligibility determination), and safety/
welfare-concern flagging — never protective-custody/removal decisions,
involuntary commitment, legal representation, clinical diagnosis, or benefits-
eligibility authority override.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-client-contact-note` — routine non-clinical case-contact logging
- `:schedule-appointment` — counseling/referral appointment scheduling
- `:coordinate-referral` — referring a client to an external service (housing,
  medical, legal aid, etc.) — coordination only, never the receiving service's
  own decision
- `:coordinate-benefits-application-assistance` — helping a client fill out /
  track a benefits application — explicitly NEVER an eligibility or award/denial
  decision
- `:flag-safety-concern` — welfare/safety concerns (e.g. suspected abuse,
  neglect, self-harm risk signals) — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Client unverified** — the target client record must exist in the
   store AND be independently `:registered?`/`:verified?` before any
   proposal for it may commit or even escalate. Re-derived from the
   client's own store record every time, never from the proposal's own
   `:client-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches protective-custody/removal/involuntary-commitment/legal/
   clinical-diagnosis/benefits-eligibility territory, is a permanent,
   un-overridable block. Evaluated **unconditionally** on every proposal
   via a lower-cased substring scan of the proposal's own content
   (English + Japanese term list) — never trusting the advisor's own
   framing. The scope-excluded term list is deliberately qualified
   (e.g. "benefits eligibility", "benefits-eligibility", "給付適格性",
   not bare "benefits") rather than over-broad, so this HARD block
   never collides with the actor's own core valid use case — legitimately
   flagging an observed welfare/safety concern via `:flag-safety-concern`
   — a failure mode this ADR's own governor test suite exercises directly
   (`legitimate-safety-concern-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`socialworkops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`socialworkops.store` (MemStore, string-keyed client directory),
`socialworkops.advisor` (SocialWorkAdvisor, mock + a real-LLM seam via
`langchain.model`), `socialworkops.governor` (SocialWorkGovernor),
`socialworkops.phase` (0→3 rollout), `socialworkops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit |
hold | request-approval), `socialworkops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "889" ...}` block, not appended): `:repo`/`:business-id`
  updated from nil to `"cloud-itonami/cloud-itonami-isic-889"`, `:maturity`
  `:spec`→`:implemented`.
- Actor repo `cloud-itonami/cloud-itonami-isic-889` scaffolded and
  pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 41 tests containing 110 assertions, 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 2 warnings
  (simulator namespace conditionals not understood by clj-kondo —
  benign). `clojure -M:run` (`socialworkops.sim` demo) walked all
  scenarios (phase-1 approval-gated commit, phase-3 auto-commit for all
  four non-safety ops, always-escalating safety-concern flag, and all
  four HARD-hold scenarios: unregistered client, unverified client,
  non-`:propose` effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-873/` (module-shape mirror, ADR-2607152700 —
  residential-care sibling, same wave)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"889"` entry (registry update commit: dfd5cb00b4ae43f1126df4ae9f5180fb906dce5c)
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (Wave definition, ISIC Wave 4 as human-services anchor)
- ADR-2607152300 (0520 lignite, verified-redo pattern, 61% defect incident recovery)
