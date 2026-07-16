# ADR-2607152700: cloud-itonami-isic-0893 — Salt Extraction Operations Coordination

## Status

Accepted. `cloud-itonami-isic-0893` promoted from `:spec` (stale
placeholder `gftdcojp/cloud-itonami-B0893` entry, never scaffolded) to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 0893 (Extraction of salt) had no prior actor implementation
— the registry entry pointed at a placeholder repo
(`https://github.com/gftdcojp/cloud-itonami-B0893`) that was never
created. This is a fresh, from-scratch scaffold (not a redo of a
broken prior attempt), built as part of a smaller, more carefully
verified batch after an earlier 18-agent haiku batch produced a 61%
defect rate across several `cloud-itonami-isic-*` actors that day.

A sibling agent working the neighboring class was originally assigned
"0892" under the assumption it was salt extraction; it verified the
live registry first and found `{:id "0892" :name "Extraction of
peat"}` — a different ISIC class entirely — and correctly halted
without writing anything, identifying `"0893"` as the true salt-
extraction slot. This ADR's own author independently re-verified the
same fact against a fresh clone of `kotoba-lang/industry` before
proceeding: `resources/kotoba/industry/registry.edn` line 2919 reads
`{:id "0893" :name "Extraction of salt" :repo
"https://github.com/gftdcojp/cloud-itonami-B0893" :business-id
"cloud-itonami-B0893" :maturity :spec ...}` — confirmed.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-0520` (Mining of lignite)'s verified module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout, string-keyed site
directory to avoid the string-vs-keyword map-key bug class
`cloud-itonami-isic-0510`'s own prior scaffold attempt hit). Domain-
adapted for salt extraction: ISIC 0893 itself covers more than one
extraction method (rock-salt dry/underground mining, and solution
mining/evaporation of brine or sea water), so this actor's demo/test
site directory seeds one site of each kind deliberately rather than
coupling the design to a single method. Coordination surface:
production-record logging (output/tonnage/purity), maintenance
scheduling, safety-concern flagging (subsidence for rock-salt sites,
brine-containment for solution-mining sites), and outbound-shipment
coordination — never direct extraction-equipment control (continuous-
miner operation, drill-and-blast/room-and-pillar sequencing, brine
well-pump control, evaporation-pond gate/valve control) or site-
safety-authority decisions.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-production-record` — output/tonnage/purity data logging
- `:schedule-maintenance` — equipment/pond maintenance scheduling proposal
- `:flag-safety-concern` — surface a site-safety concern (subsidence
  for rock-salt sites, brine-containment for solution-mining sites) —
  **ALWAYS escalates**
- `:coordinate-shipment` — outbound salt shipment coordination

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Site unverified** — the target site record must exist in the
   store AND be independently `:registered?`/`:verified?` before any
   proposal for it may commit or even escalate. Re-derived from the
   site's own store record every time, never from the proposal's own
   `:site-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches extraction-equipment-control (either method family:
   blasting/drill-and-blast/drilling-pattern/room-and-pillar/
   continuous-miner/excavation-sequencing for rock-salt, or brine-
   pump-control/injection-well-control/extraction-well-control/cavern-
   pressure-control/pond-gate-control/pond-valve-control/harvester-
   operation for solution mining) or site-safety(-authority) territory
   (subsidence-control-decision, brine-containment-control, cavern-
   integrity-control, permit issuance, license suspension, compliance
   enforcement), is a permanent, un-overridable block. Evaluated
   **unconditionally** on every proposal via a lower-cased substring
   scan of the proposal's own content (English + Japanese term list) —
   never trusting the advisor's own framing. The subsidence/brine-
   containment EXCLUSION terms are deliberately qualified ("...control
   decision", "...containment control") rather than bare keywords, so
   this HARD block never collides with the actor's own core valid use
   case — legitimately flagging an observed subsidence/brine-
   containment concern via `:flag-safety-concern` — a failure mode
   this ADR's own governor test suite exercises directly
   (`legitimate-brine-containment-flag-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`saltops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`saltops.store` (MemStore, string-keyed site directory seeded with a
rock-salt site, a solution-mining site, and an unverified solar-
evaporation site), `saltops.advisor` (SaltOpsAdvisor, mock + a
real-LLM seam via `langchain.model`, plus an `:out-of-scope?` test
hook that deliberately drafts brine-well-pump-control/drill-and-blast
scope content so the governor's scope scan can be exercised end to
end), `saltops.governor` (SaltExtractionGovernor), `saltops.phase`
(0→3 rollout), `saltops.operation` (the `langgraph-clj` StateGraph:
intake → advise → govern → decide → commit | hold | request-approval),
`saltops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "0893" ...}` block, not appended): `:repo`/`:business-id`
  updated from the stale `gftdcojp/cloud-itonami-B0893` placeholder to
  `cloud-itonami/cloud-itonami-isic-0893`, `:maturity :implemented`.
- Actor repo `cloud-itonami/cloud-itonami-isic-0893` scaffolded and
  pushed to `main` (`8b52527b40b774d552f8c9b7ff05cf649debd25f`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 45 tests containing 136 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`, both at push time and again from a fresh
  post-merge clone). `clojure -M:lint`: 0 errors, 0 warnings.

## References

- `cloud-itonami-isic-0520/` (module-shape mirror)
- `cloud-itonami-isic-0891/src/chemmineops/` (original module-shape precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"0893"` entry
- ADR-2607121000 (ISIC Wave rollout plan — 0893 is ISIC division 08,
  Wave 3 production/mining)
