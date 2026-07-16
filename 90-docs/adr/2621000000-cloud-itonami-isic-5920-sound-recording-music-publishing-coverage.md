# ADR-2621000000: cloud-itonami-isic-5920 — Sound Recording and Music Publishing Operations Coordination

## Status

Accepted. `cloud-itonami-isic-5920` promoted from `:spec` (registry
placeholder, `:repo` pointing at the stale `gftdcojp/cloud-itonami-J5920`
target that was never created) to `:implemented` in the
`kotoba-lang/industry` registry.

## Context

ISIC Rev.4 5920 (Sound recording and music publishing activities) is part
of Wave 4's human-facing/personal-services fleet (ADR-2607152500 amendment
to ADR-2607121000). No prior repository existed at either the stale
`gftdcojp/cloud-itonami-J5920` placeholder or the real `cloud-itonami` org
target (`gh api` 404 confirmed for both before this work began). The
registry entry's identity (`{:id "5920" :name "Sound recording and music
publishing activities"}`) was independently verified against a fresh clone
before any work began, per this fleet's ID/name-mismatch caution — this
entry's `:name` is NOT truncated (no trailing `"..."`), an exact match.

**Scope**: OPERATIONS COORDINATION ONLY, mirrored module-for-module on
`cloud-itonami-isic-5914` (Motion picture projection activities,
ADR-2617000000), itself mirrored on `cloud-itonami-isic-873`'s verified
Wave 4 person-facing-service module shape (advisor/governor/phase/
operation/store/sim, `langgraph-clj` StateGraph, independent Governor,
phase 0→3 rollout, string-keyed target directory, append-only audit
ledger). Domain-adapted for recording-studio and music-publishing
back-office operations: recording-session/track/catalog data logging,
studio-session/mastering scheduling proposals, outbound release/
distribution coordination, and rights-conflict/sample-clearance/royalty-
dispute concern flagging — never directly finalizing a rights-licensing
grant or a royalty-payment determination.

**Wave 4 person-facing-service safety guardrail (ADR-2607152500)**: music
publishing has a direct rights/royalty-licensing dimension. Per this ADR's
own guardrail, the closed op allowlist below contains no op that itself
finalizes a rights-licensing grant or a royalty-payment determination —
such decisions are always either a hard, permanent governor block (if an
advisor drifts into attempting one) or handled entirely by the real rights
authority (label/publisher legal, rights holder, or their designated
licensing/royalty process), outside this actor's authority. The only op
through which this actor may touch rights/royalty territory at all,
`:flag-rights-concern`, is structurally an always-escalate op, never
auto-commit-eligible at any phase.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-production-record` — recording-session/track/catalog data logging
- `:schedule-production-operation` — studio-session/mastering scheduling proposal
- `:coordinate-release` — outbound release/distribution coordination
- `:flag-rights-concern` — rights-conflict/sample-clearance/royalty-dispute concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Catalog entry unverified** — the target catalog entry (the recording/
   work + its artist-contract record) must exist in the store AND be
   independently `:registered?`/`:verified?` before any proposal for it
   may commit or even escalate. Re-derived from the catalog entry's own
   store record every time, never from the proposal's own `:catalog-id`
   claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   directly finalizes a rights-licensing grant or a royalty-payment
   determination, is a permanent, un-overridable block. Evaluated
   **unconditionally** on every proposal via a lower-cased substring scan
   of the proposal's own content (English + Japanese term list) — never
   trusting the advisor's own framing.

   **CRITICAL, per this fleet's own repeatedly-discovered self-tripping
   bug class**: `scope-excluded-terms` is deliberately phrased as the
   finalization/execution ACTION ("finalize the rights license",
   "authorize the royalty payment"), never as a bare noun ("license",
   "royalty"). A bare-noun phrasing would self-trip on this actor's own
   core valid use cases — every proposal generator in `musicops.advisor`
   legitimately talks ABOUT rights and royalties in its own disclaimer
   rationale ("this proposal never finalizes a rights license or royalty
   payment"), and `:flag-rights-concern`'s entire purpose is to report raw
   observations that legitimately mention sample clearance, rights
   disputes, and royalty splits. This governor test suite exercises the
   distinction directly via `legitimate-rights-concern-is-not-scope-
   excluded` (a concern report describing an observed sample-clearance/
   royalty-split/rights-dispute situation must never trip the gate) and,
   as a dedicated regression test required by this fleet's own known-bug-
   pattern caution, `default-mock-advisor-proposals-never-self-trip-scope-
   exclusion` (every op the default mock advisor can produce, exercised
   end-to-end through `governor/check`, must never self-trip on its own
   default rationale/summary/disclaimer text).

Escalation (SOFT, always human sign-off, only reached when the governor is
otherwise clean):

- `:flag-rights-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`musicops.phase`'s 0→3 rollout table independently agrees:
`:flag-rights-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `rights-concern-never-in-any-auto-set`).

### 3. Module shape

`musicops.store` (MemStore, string-keyed catalog directory), `musicops.
advisor` (MusicOpsAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?`
test hook that deliberately drafts rights-license-finalization/royalty-
payment-determination-scope content so the governor's scope scan can be
exercised end to end), `musicops.governor` (MusicOpsGovernor), `musicops.
phase` (0→3 rollout), `musicops.operation` (the `langgraph-clj` StateGraph:
intake → advise → govern → decide → commit | hold | request-approval),
`musicops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "5920" ...}` block, not appended): `:repo`/`:business-id` updated
  from the stale `gftdcojp/cloud-itonami-J5920` placeholder to
  `"cloud-itonami/cloud-itonami-isic-5920"`, `:maturity` `:spec`→
  `:implemented`, `:required-technologies` trimmed from the stale
  `[:robotics :identity :forms :dmn :bpmn :audit-ledger :phone]` placeholder
  set to the coordination-only shape actually implemented
  `[:identity :forms :dmn :bpmn :audit-ledger]` (matching sibling
  `cloud-itonami-isic-5914`'s own required-technologies), `:operating-states`
  updated from the stale telecom-flavored `[:intake :provision :route :bill
  :support :audit]` placeholder to match this actor's own state machine
  (`[:intake :advise :govern :approve :commit :audit]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-5920` scaffolded and pushed
  to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 42 tests containing 125 assertions. 0 failures, 0 errors.`**
  (`clojure -M:dev:test`, both at push time and again from a fresh
  post-push clone). `clojure -M:lint`: 0 errors, 0 warnings.

## References

- `cloud-itonami-isic-5914/` (module-shape mirror, ADR-2617000000)
- `cloud-itonami-isic-873/` (original Wave 4 module-shape precedent, ADR-2607152700)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"5920"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort plan)
