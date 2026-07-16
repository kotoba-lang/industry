# ADR-2651082200: cloud-itonami-isic-8220 — Call Centre Operations Coordination

## Status

Accepted. `cloud-itonami-isic-8220` promoted from no `:maturity` key
(resolves to `:blueprint` via the `kotoba.industry/maturity-of` fallback,
since the entry already carries a `:repo`) to `:implemented` in the
`kotoba-lang/industry` registry.

## Context

This is the last of a 6-target blueprint-tier cleanup batch (alongside
ISIC 7911, 7912, 8121, 8130, 8219) intended to bring the
`kotoba-lang/industry` registry's remaining nil-maturity/blueprint-tier
gap to zero. Identity independently verified against a fresh clone of
`kotoba-lang/industry` before any work began: the live `{:id "8220" ...}`
entry's `:name` is exactly "Activities of call centres" — no mismatch.

**Not a fresh scaffold.** `cloud-itonami/cloud-itonami-isic-8220` already
existed as a legitimate `:blueprint`-tier repo (published by an earlier
bulk-scaffolding pass, commit `2ed6cb2`:
`CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/`GOVERNANCE.md`/`LICENSE`/
`README.md`/`SECURITY.md`/`blueprint.edn`/`docs/business-model.md`/
`docs/operator-guide.md` — no `deps.edn`, no `src`, no `test`). That
blueprint frames the vertical on cloud-itonami's general robotics-premise
physical-work model (automated voice/chat "bots" performing first-line
intake triage/screen-pop ahead of human-agent handoff, gated by a "Call
Centre Governor") and is preserved as-is by this ADR's changes — none of
the existing boilerplate docs, `blueprint.edn` narrative, or `docs/` were
rewritten or removed; `blueprint.edn` only gained a
`:itonami.blueprint/maturity :implemented` key.

**Scope of the actor implemented here**: COORDINATION ONLY, mirrored
closely on the verified, independently-re-tested sibling
`cloud-itonami-isic-5812` (Directories/Mailing-List Publishing Operations
Coordination, ADR-2616000000) module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed directory,
append-only audit ledger). Domain-adapted for call-centre operations run
on behalf of client businesses (inbound customer support and outbound
telemarketing/collections campaigns): call-volume/handling-time/outcome
data logging, agent-shift staffing-operation scheduling,
telephony-equipment procurement coordination, and
privacy/consent-concern flagging — never finalizing a
data-privacy-compliance decision, resolving a do-not-call-list override,
or adjudicating a consent-withdrawal request. This domain touches
personal-data handling and customer-consent decisions directly (inbound
support and outbound telemarketing routinely process personal contact
data and are subject to do-not-call/consent-withdrawal regimes), so the
closed op allowlist never includes an op that directly finalizes such a
decision — always a hard permanent block or an always-escalate op, never
auto-commit-eligible (see Decision §2). The robotics-dispatch capability
layer described in the pre-existing blueprint (automated voice/chat
triage bots) is a separate, not-yet-wired concern this actor's
coordination proposals could eventually feed into — this ADR does not
implement robot dispatch.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-call-record` — call-volume/handling-time/outcome data logging
- `:schedule-staffing-operation` — agent-shift scheduling proposal
- `:coordinate-equipment-supply` — telephony-equipment procurement coordination
- `:flag-privacy-concern` — surface a consent-withdrawal/do-not-call/data-breach concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the Wave 4 person-facing-service safety guardrail (ADR-2607152500):
the closed op allowlist NEVER includes any op that directly finalizes a
data-privacy-compliance decision (a do-not-call-list override
resolution, a consent-withdrawal adjudication) — that is always either a
hard permanent block (check 3) or, for the one "flag a concern" op, an
always-escalate op, never an auto-commit-eligible op in any phase's
`:auto` set.

1. **Campaign unverified** — the target client-contract/campaign record
   must exist in the store AND be independently `:registered?`/
   `:verified?` before any proposal for it may commit or even escalate.
   Re-derived from the campaign's own store record every time, never
   from the proposal's own `:campaign-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches finalizing a do-not-call-list override, adjudicating a
   consent-withdrawal request, a GDPR/CCPA compliance ruling, or
   finalizing a data-breach determination, is a permanent,
   un-overridable block. Evaluated **unconditionally** on every proposal
   via a lower-cased substring scan of the proposal's own content
   (English + Japanese term list) — never trusting the advisor's own
   framing.

   Per this fleet's known self-tripping bug class — a governor's own
   scope-exclusion term list phrased as a bare noun (e.g. "consent")
   accidentally matching inside the mock advisor's own DEFAULT
   rationale/disclaimer text for a legitimate, allowed proposal — every
   term in `callcentreops.governor/scope-excluded-terms` is phrased as
   the finalization/execution ACTION (e.g. "finalized the do-not-call
   override", "consent withdrawal resolved", "発信禁止リスト解除確定"),
   never a bare noun. A dedicated regression test,
   `callcentreops.governor-test/no-default-proposal-self-trips-scope-exclusion`,
   runs the default mock advisor's own proposal for every allowed op
   (including `:flag-privacy-concern`, whose entire job is to talk about
   privacy concerns) through the governor and asserts none of them ever
   trip `:scope-excluded` or `:op-not-allowed`.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-privacy-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`callcentreops.phase`'s 0→3 rollout table independently agrees:
`:flag-privacy-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `privacy-concern-holds-when-not-enabled` /
`privacy-concern-escalates-when-enabled` /
`flag-privacy-concern-never-in-any-auto-set`).

### 3. Module shape

`callcentreops.store` (MemStore, string-keyed campaign directory),
`callcentreops.advisor` (CallCentreAdvisor, mock + a real-LLM seam, plus
an `:out-of-scope?` test hook that deliberately drafts
do-not-call-override-finalization/consent-withdrawal-resolution-scope
content so the governor's scope scan can be exercised end to end),
`callcentreops.governor` (CallCentreGovernor), `callcentreops.phase`
(0→3 rollout), `callcentreops.operation` (the `langgraph-clj` StateGraph:
intake → advise → govern → decide → commit | hold | request-approval),
`callcentreops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Actor repo `cloud-itonami/cloud-itonami-isic-8220` (pre-existing
  `:blueprint`-tier repo, NOT freshly scaffolded) filled in: `deps.edn`,
  `.gitignore`, full `src/callcentreops/*.cljc` + `test/callcentreops/*.clj`
  module set added on top of the existing boilerplate docs/blueprint.edn,
  which are preserved unchanged (only `blueprint.edn` gained a
  `:itonami.blueprint/maturity :implemented` key). Committed and pushed
  directly to `main` (fast-forward from `2ed6cb2`, no divergence):
  `2d52a7728813d7af4103d72e4f6b7b4d2c15290a`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 45 tests containing 141 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clj-kondo` (`clojure -M:lint`) clean: 0 errors,
  0 warnings. `clojure -M:run` demo driver exercised end to end: two
  clean auto-commits, one phase-1-escalate-then-approve flow, one
  always-escalate privacy-concern flow, and all three HARD-hold
  scenarios (unregistered campaign, unverified campaign, non-`:propose`
  effect, scope-excluded content) all resolved as designed.
- Registry entry updated in place (exact-text edit of the existing
  `{:id "8220" ...}` block only, not appended, not touching any other
  entry): `:maturity :implemented` added (previously absent — the entry
  resolved to `:blueprint` only via the `:repo`-present fallback in
  `kotoba.industry/maturity-of`). `:repo` and `:business-id` were already
  correct (`https://github.com/cloud-itonami/cloud-itonami-isic-8220` /
  `cloud-itonami-8220`, matching the existing `blueprint.edn`'s own
  `:itonami.blueprint/id` and the same non-`isic-`-infixed business-id
  convention already used by ~90 other registry entries including sibling
  `cloud-itonami-isic-8219`) and were left unchanged, as were
  `:required-technologies`/`:optional-technologies`/`:operating-states`.

## References

- `cloud-itonami-isic-5812/` (module-shape mirror, ADR-2616000000 —
  verified working, independently re-tested precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"8220"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan)
- skill `build-actor` (advisor/governor/StateGraph/audit-ledger actor pattern)
