# ADR-2611210000: cloud-itonami-isic-0899 — Other Mining and Quarrying Operations Coordination

## Status

Accepted. `cloud-itonami-isic-0899` promoted from `:spec` (stale
placeholder `gftdcojp/cloud-itonami-B0899` entry, never scaffolded) to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 0899 (Other mining and quarrying n.e.c.) had no prior actor
implementation — the registry entry pointed at a placeholder repo
(`https://github.com/gftdcojp/cloud-itonami-B0899`) that was never
created. This is a fresh, from-scratch scaffold (not a redo of a broken
prior attempt), built as part of a smaller, more carefully verified
batch after an earlier 18-agent haiku batch produced a 61% defect rate
across several `cloud-itonami-isic-*` actors that day.

Before any work, the live registry was independently re-verified
against a fresh clone of `kotoba-lang/industry`:
`resources/kotoba/industry/registry.edn` contains `{:id "0899" :name
"Other mining and quarrying n.e.c." :repo
"https://github.com/gftdcojp/cloud-itonami-B0899" :business-id
"cloud-itonami-B0899" :maturity :spec ...}` — confirmed matching the
assigned ISIC class, distinct from already-`:implemented` siblings
`0892` (Extraction of peat), `0893` (Extraction of salt), and `0721`
(Mining of uranium and thorium ores).

**Scope**: COORDINATION ONLY, mirrored closely on sibling
`cloud-itonami-isic-0893` (Extraction of salt)'s verified module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout, string-keyed site
directory to avoid the string-vs-keyword map-key bug class a prior
sibling scaffold attempt hit). ISIC 0899 is a **residual (n.e.c.)**
mineral-extraction category — gemstones, quartz, feldspar, abrasives,
and other minerals not classified elsewhere in division 08. This
actor's chosen **concrete illustration** of the category is
**quartz/feldspar quarrying for industrial abrasives** (grinding/
blasting media, ceramics, glass-batch feedstock), documented plainly in
README.md; the coordination layer generalizes to any single-commodity
quarry site in this residual category. Coordination surface:
extraction-record logging (volume/quality-grade), extraction/blasting
**scheduling proposals** (never blast execution itself),
environmental-concern flagging (dust/blast-vibration/land-reclamation),
and outbound-shipment coordination — never direct extraction-equipment
control (blast/drill-pattern sequencing, excavator/loader operation,
crusher/conveyor control, haul-truck dispatch control) or
environmental-permit-issuing-authority decisions.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-extraction-record` — extraction-volume/quality-grade data logging
- `:schedule-extraction-operation` — extraction/blasting scheduling proposal
- `:flag-environmental-concern` — surface a dust/blast-vibration/land-
  reclamation concern — **ALWAYS escalates**
- `:coordinate-shipment` — outbound material shipment coordination

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Site/permit unverified** — the target site record must exist in
   the store AND be independently `:registered?`/`:verified?` before
   any proposal for it may commit or even escalate. Re-derived from
   the site's own store record every time, never from the proposal's
   own `:site-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches extraction-equipment-control (blast-pattern/blast-
   sequencing/drill-pattern/drill-and-blast/excavator-operation/
   loader-operation/crusher-control/conveyor-control/haul-truck-
   dispatch-control/bench-sequencing) or environmental-permit-issuing-
   authority territory (permit issuance/suspension, license suspension,
   compliance enforcement, environmental-permit decision), is a
   permanent, un-overridable block. Evaluated **unconditionally** on
   every proposal via a lower-cased substring scan of the proposal's
   own content (English + Japanese term list) — never trusting the
   advisor's own framing. **Deliberate design note**: the scope-
   excluded term list intentionally does NOT include the bare word
   "blast"/"発破" — only phrase-qualified execution/control terms
   ("blast pattern", "blast sequencing", "drill-and-blast", ...) —
   because this actor's own allowlisted `:schedule-extraction-
   operation` op legitimately describes "extraction/blasting
   scheduling"; a bare-word "blast" exclusion term would have
   self-blocked every legitimate scheduling proposal (caught during
   this session's own `clojure -M:test` run — the first draft used
   bare "blast"/"発破" terms mirrored directly from
   `cloud-itonami-isic-0893`'s salt-specific term list, and 6 of 45
   tests failed until the terms were phrase-qualified to distinguish
   "schedule a blast" from "blast pattern/sequencing" direct-control
   content).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-environmental-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`quarryops.phase`'s 0→3 rollout table independently agrees:
`:flag-environmental-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`quarryops.store` (MemStore, string-keyed site directory seeded with a
quartz quarry, a feldspar quarry, and an unverified/permit-lapsed
quartz quarry), `quarryops.advisor` (QuarryOpsAdvisor, mock + a
real-LLM seam via `langchain.model`, plus an `:out-of-scope?` test hook
that deliberately drafts crusher-control/blast-pattern scope content so
the governor's scope scan can be exercised end to end),
`quarryops.governor` (QuarrySiteGovernor), `quarryops.phase` (0→3
rollout), `quarryops.operation` (the `langgraph-clj` StateGraph: intake
→ advise → govern → decide → commit | hold | request-approval),
`quarryops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "0899" ...}` block, not appended): `:repo`/`:business-id`
  updated from the stale `gftdcojp/cloud-itonami-B0899` placeholder to
  `cloud-itonami/cloud-itonami-isic-0899`, `:maturity :implemented`.
- Actor repo `cloud-itonami/cloud-itonami-isic-0899` scaffolded and
  pushed to `main` (`570f972de810aa760c5dc0367e6dd850e0722392`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 45 tests containing 136 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`, both at push time and again from a fresh
  post-merge clone). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` demo driver exercises all 4 ops plus the HARD-hold
  and escalation scenarios end to end with no exceptions.

## References

- `cloud-itonami-isic-0893/` (module-shape mirror, salt extraction)
- `cloud-itonami-isic-0891/src/chemmineops/` (original module-shape precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"0899"` entry
- ADR-2607121000 (ISIC Wave rollout plan — 0899 is ISIC division 08,
  Wave 3 production/mining)
