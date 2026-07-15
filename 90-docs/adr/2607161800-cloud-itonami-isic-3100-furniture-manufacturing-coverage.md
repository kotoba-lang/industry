# ADR-2607161800: cloud-itonami-isic-3100 (Manufacture of furniture) plant-operations-coordination actor -- full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1610 (Sawmilling and planing of wood --
the closest domain analog and the reference this actor mirrors most
closely, same wood-products plant-operations-coordination shape), the
`kotoba-lang/industry` registry's `"3100"` catalog entry

## Context

`kotoba-lang/industry`'s registry carried `"3100"`
(`{:id "3100" :name "Manufacture of furniture" ...}`) as `:maturity
:spec`, with a stale `:repo` pointing at a non-existent
`gftdcojp/cloud-itonami-C3100` placeholder. No repository existed at
either the stale `gftdcojp` location or the fleet's actual
`cloud-itonami` org (`gh api repos/cloud-itonami/cloud-itonami-isic-3100`
returned 404) -- this was a genuine fresh-scaffold gap, not a
mislabeling of an already-implemented class. The ID/name match was
independently re-verified against a fresh, read-only clone of
`kotoba-lang/industry` before any implementation work began, per this
fleet's post-incident verification discipline (two prior agents had
previously mislabeled their assigned ISIC class: 0892 assumed to be
salt when it is peat, 0144 assumed to be swine when it is sheep-goats).

This ADR documents the actual, verified implementation that closes
that gap.

## Decision

Implement `cloud-itonami-isic-3100` as a furniture-factory
plant-operations COORDINATION actor, mirroring `cloud-itonami-isic-
1610` (Sawmilling and planing of wood)'s verified pattern closely --
production-batch data logging, equipment maintenance scheduling,
safety-concern flagging, outbound-shipment coordination -- adapted
from lumber production (saws/planers/kilns, lumber-grade/volume/
moisture-content) to furniture manufacturing (cutting/joinery/
assembly/sanding/finishing lines, quality-grade/unit-count/
defect-rate). **This actor holds no cutting/joinery/finishing-line-
equipment-control authority**, a permanent structural block, never a
rollout milestone still to come.

1. **`furnituremfg.governor`** (Furniture Plant Operations Governor)
   -- independent compliance layer, TEN hard checks elaborating four
   HARD invariants, all un-overridable by human approval:
   - request-level propose-only (`:effect :propose` required)
   - closed op allowlist (`:log-production-batch` /
     `:schedule-maintenance` / `:flag-safety-concern` /
     `:coordinate-shipment` only)
   - closed proposal-effect allowlist (no direct cutting/joinery/
     finishing-line-equipment control)
   - line-run finalize blocked -- `:finalize? true` on a
     `:schedule-maintenance` proposal is a permanent, unconditional
     block (attempting to directly execute/actuate a cutting/joinery/
     finishing-line run rather than merely draft-schedule a
     maintenance window)
   - equipment not independently verified/registered (for
     `:schedule-maintenance`)
   - already-scheduled double-schedule guard (for
     `:schedule-maintenance`)
   - batch not independently verified/registered (for
     `:coordinate-shipment`)
   - shipment unit-count independently recomputed against the batch's
     own logged `:unit-count` (for `:coordinate-shipment`)
   - invalid `:quality-grade` (closed known set, for
     `:log-production-batch`)
   - invalid `:defect-rate-percent` (physically implausible reading
     outside 0-100%, for `:log-production-batch`)

   Soft escalation (human sign-off required, not a rejection):
   `:flag-safety-concern` is an unconditional member of `high-stakes`
   -- it always escalates to a human, at every phase, regardless of
   confidence or governor cleanliness (belt-and-suspenders with
   `furnituremfg.phase`, which also never places
   `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-
   shipment` in any phase's `:auto` set); confidence below
   `confidence-floor` (0.6) also escalates.

2. **`furnituremfg.registry`** -- pure-function domain logic:
   equipment/batch verification, shipment-unit recompute, closed
   furniture quality-grade set (`#{:premium :grade-a :grade-b
   :commercial :seconds :reject}`), defect-rate plausibility
   validation (0-100%), draft maintenance-schedule/shipment-
   coordination record construction (unsigned certificates only, per
   README `Actuation`). No pre-existing `kotoba-lang/furnituremfg`-
   style capability library exists to wrap (verified) -- self-
   contained, re-verified independently by the governor, same "ground
   truth, not self-report" discipline as
   `cloud-itonami-isic-1610`'s `sawmilling.registry`.
3. **`furnituremfg.store`** -- `Store` protocol, single `MemStore`
   backend (deterministic default, no deps -- see ns docstring for why
   a second Datomic-backed backend is out of scope for this build,
   matching `cloud-itonami-isic-1610`'s own scope note).
4. **`furnituremfg.advisor`** (FurnitureAdvisor) -- mock deterministic
   advisor + `llm-advisor` seam for a real `langchain.model/ChatModel`;
   structurally cannot generate ops outside the governor's closed
   allowlist; every proposal carries `:effect :propose`.
5. **`furnituremfg.phase`** -- 0->3 staged rollout; only
   `:log-production-batch` may auto-commit at phase 3 (no physical/
   financial risk); `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are deliberately absent from every phase's
   `:auto` set, permanently.
6. **`furnituremfg.operation`** -- langgraph-clj StateGraph
   OperationActor: `intake -> advise -> govern -> decide -> commit |
   hold | request-approval`, one graph run = one auditable operation,
   checkpointed, `interrupt-before #{:request-approval}` for
   human-in-the-loop.
7. **`furnituremfg.sim`** -- demo driver exercising the full
   coordination episode plus every HARD hold scenario directly
   (not-propose-effect, unknown-op, equipment-not-verified,
   batch-not-verified, shipment-units-exceeded, line-finalize-blocked,
   already-scheduled, invalid-grade, invalid-defect-rate).

### What this actor does NOT do

Explicitly documented (README, this ADR, `furnituremfg.governor`/
`furnituremfg.phase` ns docstrings): no cutting-saw/sander/finishing-
line (spray-booth) equipment-control commands, no direct actuation, no
finalizing/executing a cutting/joinery/finishing-line run. These
remain the human plant supervisor's exclusive authority, permanently,
with no actor or human-approval override path -- matching the CLAUDE.md
instruction that this actor coordinates plant operations and never
directly actuates equipment, and that no new Rust/robot-control code
be written for it.

## Verification

- `cloud-itonami-isic-3100`: `clojure -M:test` -- raw final line: `Ran
  71 tests containing 195 assertions.` / `0 failures, 0 errors.`
  (registry pure-function validation; store contract; phase-table
  structural invariants incl. `:schedule-maintenance` never in any
  phase's `:auto` set; operation-actor smoke tests; governor contract
  covering all ten hard checks individually plus the confidence/high-
  stakes escalation gate, both escalation-then-approval and approval-
  rejection paths, auto-commit at phase 3 for `:log-production-batch`
  only).
- `clojure -M:lint` -- 0 errors, 0 warnings.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; verified via `grep -rn 'clojure.lang\|java\.\|
  Thread/\|System/' src test` (zero hits); the actor graph is invoked
  exclusively via `langgraph.graph/run*` (not `.invoke`, which is not
  cljs-portable).
- Governor keyword `:furniture-plant-operations-governor` grep-
  verified UNIQUE fleet-wide before this repo was created
  (`gh search code "furniture-plant-operations-governor" --owner
  cloud-itonami`, zero hits).
- Commit `25c3bdc` pushed directly to `cloud-itonami-isic-3100`'s
  `main` (fresh repository, initial commit).
- Post-merge re-verification (fresh `git clone --depth 1` into a NEW
  scratch directory, `kotoba-lang/technology` cloned as a
  `../technology` sibling per this fleet's standard verification
  protocol): re-ran `clojure -M:test` against the clean clone -- same
  green result, raw output pasted in the closing report.

## Consequences

(+) `cloud-itonami-isic-3100` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor in this
fleet, closing a genuine fresh-scaffold gap (no prior repository, no
prior fabricated report to correct).
(+) `kotoba-lang/industry` registry `"3100"` entry updated in place to
`:maturity :implemented`, corrected `:repo`/`:business-id` (from the
stale non-existent `gftdcojp/cloud-itonami-C3100` to the real
`cloud-itonami/cloud-itonami-isic-3100`), and an ADR reference.
(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off; safety-concern flagging is a circuit-breaker, not
a threshold.
(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and line-run execution remain
human-controlled via external channels.
(-) No integration with real factory-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.
(-) No real LLM is wired into the default advisor (mock-by-default,
matching every sibling actor in this fleet); `furnituremfg.advisor/
llm-advisor` is the documented seam for swapping in a real
`langchain.model/ChatModel`.
