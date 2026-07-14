# ADR-2607152100: cloud-itonami-isic-0510 — Hard Coal Mining Operations Coordination (verified redo)

## Status

Accepted. `cloud-itonami-isic-0510` promoted from `:spec` (REVERTED) to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 0510 (Mining of hard coal) already had a scaffold repo
(`cloud-itonami/cloud-itonami-isic-0510`) from an earlier same-day attempt.
That attempt was promoted to `:implemented` on the promoting agent's own
claim of "all tests green," but an independent audit re-ran the suite
directly and found it genuinely broken: `clojure -M:test` reported 16
tests, **10 failures**. The registry entry was reverted back to `:spec`
with the failure recorded on the entry.

Root cause of the 10 failures: the `mining.store` `MemStore` test
fixtures keyed the site directory with **keyword** site-ids
(`{:coal-mine-1 site-1}`) while every governor/advisor lookup used the
**string** `:site-id` off the proposal (`"coal-mine-1"`). `(get sites
"coal-mine-1")` on a map keyed by `:coal-mine-1` silently returned `nil`
on every call, so every proposal — including every intended-clean
happy-path case — was misclassified as a HARD `:site-unregistered`
hold. Several negative tests (`unregistered site`, `wrong owner`)
happened to still pass because they *expected* a hold, for the wrong
reason; the five positive/escalation tests did not.

This ADR documents a from-scratch, independently re-verified redo (this
session ran `clojure -M:test` directly and pasted the raw output — see
Consequences), not a re-promotion of the broken code.

**Scope**: COORDINATION ONLY, mirrored on `cloud-itonami-isic-0891`
(Mining of chemical and fertilizer minerals)'s governed-actor module
shape (advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout) and on the sibling
`cloud-itonami-isic-0710` (Iron Ore Mining)'s coordination-only scope
boundary, narrowed further than the earlier scaffold's own `README`
already declared: production-record logging, maintenance scheduling,
safety-concern flagging, and outbound-shipment coordination — never
direct extraction sequencing, blasting, or mine-safety-authority
decisions.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-production-record` — output/tonnage data logging
- `:schedule-maintenance` — equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a mine-safety concern (gas,
  structural, ventilation) — **ALWAYS escalates**
- `:coordinate-shipment` — outbound coal shipment coordination

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Site unverified** — the target mine/site record must exist in the
   store AND be independently `:registered?`/`:verified?` before any
   proposal for it may commit or even escalate. Re-derived from the
   site's own store record every time, never from the proposal's own
   `:site-id` claim — this is the exact discipline the earlier
   scaffold's bug violated in spirit (it *looked up* the site but the
   lookup silently failed).
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches blasting/drilling-pattern/cutting-schedule/extraction-
   sequencing/ventilation-control/gas-monitoring-override/mine-safety-
   authority (permit issuance, license suspension, compliance
   enforcement) territory, is a permanent, un-overridable block.
   Evaluated **unconditionally** on every proposal via a lower-cased
   substring scan of the proposal's own content (English + Japanese
   term list) — never trusting the advisor's own framing.

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`coalops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape (renamed `mining.*` → `coalops.*`)

`coalops.store` (MemStore, string-keyed site directory — the fix for
the root-cause bug), `coalops.advisor` (CoalOpsAdvisor, mock + a real-
LLM seam via `langchain.model`, plus an `:out-of-scope?` test hook that
deliberately drafts blasting-scope content so the governor's scope scan
can be exercised end to end), `coalops.governor` (CoalMiningGovernor),
`coalops.phase` (0→3 rollout), `coalops.operation` (the `langgraph-clj`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval), `coalops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "0510" ...}` block, not appended): `REVERTED` comment removed,
  `:maturity :implemented`.
- Actor repo (`cloud-itonami/cloud-itonami-isic-0510`) rewritten and
  pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 43 tests containing 133 assertions, 0 failures, 0 errors.`**
  (`clojure -M:test`, both at push time and again from a fresh
  post-merge clone). `clojure -M:lint`: 0 errors, 0 warnings.
- Establishes a concrete, checked-in example of the exact bug class
  (string-vs-keyword map-key mismatch silently defeating a lookup) for
  future actor builds in this fleet to grep for in their own store
  contract tests.

## References

- `cloud-itonami-isic-0891/src/chemmineops/` (module-shape mirror)
- `cloud-itonami-isic-0710/docs/adr/` (coordination-only scope-boundary
  precedent, same-day sibling)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"0510"` entry (revert comment, now corrected)
- Federal Mine Safety and Health Act (Mine Act), 30 U.S.C. §801 et seq. (US, MSHA)
- 鉱山保安法 (Mine Safety Act) (Japan, METI)
