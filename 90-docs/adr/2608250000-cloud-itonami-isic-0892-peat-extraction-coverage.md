# ADR-2608250000: cloud-itonami-isic-0892 — Peat Extraction Operations Coordination

## Status

Accepted. `cloud-itonami-isic-0892` promoted from `:spec` (stale
placeholder `gftdcojp/cloud-itonami-B0892` entry, never scaffolded) to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 0892 (Extraction of peat) had no prior actor implementation
— the registry entry pointed at a placeholder repo
(`https://github.com/gftdcojp/cloud-itonami-B0892`) that was never
created. This is a fresh, from-scratch scaffold, built as part of a
smaller, more carefully verified batch after an earlier 18-agent haiku
batch produced a 61% defect rate across several `cloud-itonami-isic-*`
actors that day. A prior sibling agent working the neighboring class
"0892" under the mistaken assumption it was salt extraction verified
the live registry first, found `{:id "0892" :name "Extraction of
peat"}`, and correctly identified `"0893"` (Extraction of salt,
subsequently implemented via ADR-2607152700) as the true salt slot
without touching "0892". This ADR's own author independently
re-verified the same fact against a fresh clone of `kotoba-lang/
industry` before proceeding: `resources/kotoba/industry/registry.edn`
line 2717 reads `{:id "0892" :name "Extraction of peat" :repo
"https://github.com/gftdcojp/cloud-itonami-B0892" :business-id
"cloud-itonami-B0892" :maturity :spec ...}` — confirmed.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-0893` (Extraction of salt)'s verified module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout, string-keyed site
directory to avoid the string-vs-keyword map-key bug class
`cloud-itonami-isic-0510`'s own prior scaffold attempt hit). Domain-
adapted for peat extraction: ISIC 0892 itself covers more than one
extraction method (milled peat via mechanical harrowing/ridging/
vacuum-harvesting of the dried bog surface, and sod/block-cut peat via
cutting and lifting solid blocks from the bog face), so this actor's
demo/test site directory seeds one site of each kind deliberately
rather than coupling the design to a single method. Coordination
surface: extraction-record logging (harvest-volume/moisture-content),
harvest/drying/baling operation scheduling, environmental-concern
flagging (bog-drainage/wetland-impact/fire-risk), and outbound baled-
peat shipment coordination — never direct extraction-equipment control
(milling/harrowing/ridging/vacuum-harvester control, sod-/block-
cutting/baling-machine control, drainage-ditch/sluice-gate/water-table
control) or environmental-permit-issuing-authority decisions.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-extraction-record` — harvest-volume/moisture-content data logging
- `:schedule-extraction-operation` — harvest/drying/baling scheduling proposal
- `:flag-environmental-concern` — surface a bog-drainage/wetland-impact/
  fire-risk concern — **ALWAYS escalates**
- `:coordinate-shipment` — outbound baled-peat shipment coordination

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Site unverified** — the target site record (site + environmental
   permit) must exist in the store AND be independently `:registered?`/
   `:verified?` before any proposal for it may commit or even
   escalate. Re-derived from the site's own store record every time,
   never from the proposal's own `:site-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches extraction-equipment-control (either method family:
   milling-machine/harrowing-machine/ridging-machine/vacuum-harvester
   control for milled peat, or sod-cutting/block-cutting/baling-
   machine control for sod/block-cut peat) or bog-drainage/
   environmental-permit-issuing-authority territory (drainage-ditch
   control, sluice-gate control, water-table control, drainage-canal
   control, weir control, fire-suppression control, permit issuance,
   license suspension, compliance enforcement), is a permanent,
   un-overridable block. Evaluated **unconditionally** on every
   proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the
   advisor's own framing. The observation-vs-control-decision
   distinction is deliberate: the excluded control-verb terms above
   never collide with the actor's own core valid use case —
   legitimately flagging an observed bog-drainage/wetland-impact
   concern via `:flag-environmental-concern` — a failure mode this
   ADR's own governor test suite exercises directly
   (`legitimate-environmental-flag-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-environmental-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`peatops.phase`'s 0→3 rollout table independently agrees:
`:flag-environmental-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`peatops.store` (MemStore, string-keyed site directory seeded with a
milled-peat site, a sod-peat site, and an unverified sod-peat site),
`peatops.advisor` (PeatOpsAdvisor, mock + a real-LLM seam via
`langchain.model`, plus an `:out-of-scope?` test hook that
deliberately drafts vacuum-harvester-control/drainage-ditch-control
scope content so the governor's scope scan can be exercised end to
end), `peatops.governor` (PeatExtractionGovernor), `peatops.phase`
(0→3 rollout), `peatops.operation` (the `langgraph-clj` StateGraph:
intake → advise → govern → decide → commit | hold | request-approval),
`peatops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "0892" ...}` block, not appended): `:repo`/`:business-id`
  updated from the stale `gftdcojp/cloud-itonami-B0892` placeholder to
  `cloud-itonami/cloud-itonami-isic-0892`, `:maturity :implemented`,
  `:required-technologies` narrowed to the non-robotics coordination
  set (`[:identity :forms :dmn :bpmn :audit-ledger]`, dropping
  `:robotics`/`:telemetry` — this actor never directly actuates
  extraction equipment).
- Actor repo `cloud-itonami/cloud-itonami-isic-0892` scaffolded and
  pushed to `main` (`d978ec6bb9a5eb265ecabd87c1186d362486dbd5`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 45 tests containing 136 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`, both at push time and again from a fresh
  post-push clone). `clojure -M:lint`: 0 errors, 0 warnings.

## References

- `cloud-itonami-isic-0893/` (module-shape mirror, VERIFIED working,
  ADR-2607152700)
- `cloud-itonami-isic-0520/` / `cloud-itonami-isic-0891/src/
  chemmineops/` (original module-shape precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"0892"` entry
- ADR-2607121000 (ISIC Wave rollout plan — 0892 is ISIC division 08,
  Wave 3 production/mining)
