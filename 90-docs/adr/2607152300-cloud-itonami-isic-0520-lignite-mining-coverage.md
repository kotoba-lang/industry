# ADR-2607152300: cloud-itonami-isic-0520 — Lignite Mining Operations Coordination

## Status

Accepted. `cloud-itonami-isic-0520` promoted from `:spec` (stale
placeholder `gftdcojp/cloud-itonami-B0520` entry, never scaffolded) to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 0520 (Mining of lignite) had no prior actor implementation —
the registry entry pointed at a placeholder repo
(`https://github.com/gftdcojp/cloud-itonami-B0520`) that was never
created. This is a fresh, from-scratch scaffold (not a redo of a
broken prior attempt), built as part of a smaller, more carefully
verified batch after an earlier 18-agent haiku batch produced a 61%
defect rate (empty implementations, missing modules, false "all tests
green" claims) across several `cloud-itonami-isic-*` actors that day.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-0510` (Mining of hard coal)'s verified-redo module
shape (ADR-2607152100: advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed site directory to avoid the exact string-vs-keyword
map-key bug class ADR-2607152100 documents). Domain-adapted for
lignite's typically surface/opencast mining method: production-record
logging, maintenance scheduling, safety-concern flagging (subsidence,
dust, groundwater — analogous to hard coal's gas/structural/
ventilation concerns), and outbound-shipment coordination — never
direct extraction sequencing, blasting/overburden-removal sequencing,
or mine-safety-authority decisions.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-production-record` — output/tonnage data logging
- `:schedule-maintenance` — equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a mine-safety concern (subsidence,
  dust, groundwater) — **ALWAYS escalates**
- `:coordinate-shipment` — outbound lignite shipment coordination

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Site unverified** — the target mine/site record must exist in the
   store AND be independently `:registered?`/`:verified?` before any
   proposal for it may commit or even escalate. Re-derived from the
   site's own store record every time, never from the proposal's own
   `:site-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches blasting/drilling-pattern/cutting-schedule/extraction-
   sequencing/overburden-removal-sequencing/excavation-sequencing/
   subsidence-control-decision/dust-suppression-override/groundwater-
   drawdown-control/mine-safety-authority (permit issuance, license
   suspension, compliance enforcement) territory, is a permanent,
   un-overridable block. Evaluated **unconditionally** on every
   proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the
   advisor's own framing. The subsidence/dust/groundwater EXCLUSION
   terms are deliberately qualified ("...control decision",
   "...override", "...drawdown control") rather than bare keywords, so
   this HARD block never collides with the actor's own core valid use
   case — legitimately flagging an observed subsidence/dust/
   groundwater concern via `:flag-safety-concern` — a failure mode
   this ADR's own governor test suite exercises directly
   (`legitimate-subsidence-flag-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`ligniteops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`ligniteops.store` (MemStore, string-keyed site directory),
`ligniteops.advisor` (LigniteOpsAdvisor, mock + a real-LLM seam via
`langchain.model`, plus an `:out-of-scope?` test hook that
deliberately drafts blasting/overburden-removal-scope content so the
governor's scope scan can be exercised end to end),
`ligniteops.governor` (LigniteMiningGovernor), `ligniteops.phase` (0→3
rollout), `ligniteops.operation` (the `langgraph-clj` StateGraph:
intake → advise → govern → decide → commit | hold | request-approval),
`ligniteops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "0520" ...}` block, not appended): `:repo`/`:business-id`
  updated from the stale `gftdcojp/cloud-itonami-B0520` placeholder to
  `cloud-itonami/cloud-itonami-isic-0520`, `:maturity :implemented`.
- Actor repo `cloud-itonami/cloud-itonami-isic-0520` scaffolded and
  pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 45 tests containing 136 assertions, 0 failures, 0 errors.`**
  (`clojure -M:test`, both at push time and again from a fresh
  post-merge clone). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`ligniteops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for all three
  non-safety ops, always-escalating safety-concern flag, and all four
  HARD-hold scenarios: unregistered site, unverified site, non-
  `:propose` effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-0510/` (module-shape mirror, ADR-2607152100 —
  string-vs-keyword site-directory bug-class precedent avoided here
  from the start)
- `cloud-itonami-isic-0891/src/chemmineops/` (original module-shape
  precedent both 0510 and 0520 mirror)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"0520"` entry
- ADR-2607121000 (ISIC Wave rollout plan — 0520 is ISIC division 05,
  Wave 3 production/mining)
- Federal Mine Safety and Health Act (Mine Act), 30 U.S.C. §801 et seq. (US, MSHA)
- 鉱山保安法 (Mine Safety Act) (Japan, METI)
