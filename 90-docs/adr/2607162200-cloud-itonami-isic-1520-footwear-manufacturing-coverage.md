# ADR-2607162200: cloud-itonami-isic-1520 (Manufacture of footwear) plant-operations-coordination actor -- full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1610 (Sawmilling and planing of wood --
the closest domain analog, the reference this actor mirrors most
closely: both are back-office plant-operations-coordination actors
over real physical-safety-relevant equipment), ADR-2607011000 (ISIC
section coverage), the `kotoba-lang/industry` registry's `"1520"`
catalog entry

## Context

`kotoba-lang/industry`'s registry carried `"1520"` (`{:id "1520" :name
"Manufacture of footwear" ...}`) as `:maturity :spec`, with a stale
`:repo` pointing at a non-existent `gftdcojp/cloud-itonami-C1520`
placeholder. No repository existed at either the stale `gftdcojp`
location or the fleet's actual `cloud-itonami` org (`gh api
repos/cloud-itonami/cloud-itonami-isic-1520` returned 404) -- this was
a genuine fresh-scaffold gap, not a mislabeling of an already-
implemented class. The ID/name match was independently re-verified
against a fresh, read-only clone of `kotoba-lang/industry` before any
implementation work began, per this fleet's post-incident verification
discipline (prior agents had previously mislabeled their assigned
ISIC class: 0892 assumed to be salt when it is peat, 0144 assumed to
be swine when it is sheep-goats).

This ADR documents the actual, verified implementation that closes
that gap.

## Decision

Implement `cloud-itonami-isic-1520` as a footwear-factory PLANT
OPERATIONS COORDINATION actor, mirroring `cloud-itonami-isic-1610`
(Sawmilling and planing of wood)'s verified module shape closely --
**this actor holds no cutting/lasting/assembly-line-equipment-control
authority**, a permanent structural block, never a rollout milestone
still to come.

1. **`footwearops.governor`** (Footwear Plant Operations Governor) --
   independent compliance layer, TEN concrete hard checks elaborating
   four HARD invariants, all un-overridable by human approval:
   - request-level propose-only (`:effect` must be `:propose`) --
     structural, evaluated first
   - closed op allowlist (`:log-production-batch` /
     `:schedule-maintenance` / `:flag-quality-concern` /
     `:coordinate-shipment` only)
   - closed proposal-effect allowlist -- no direct cutting/lasting/
     assembly-line-equipment control, structural, permanent
   - direct-operate blocked -- a `:schedule-maintenance` proposal
     declaring `:direct-operate? true` is a PERMANENT, unconditional
     block (this domain's analog of `cloud-itonami-isic-1610`'s
     kiln-`:finalize?` block: a proposal-level field that, if true,
     attempts to bypass "propose/schedule a DRAFT" and reach actual
     equipment operation)
   - equipment not verified/registered -- independently re-derives the
     referenced equipment's own `:verified?`/`:registered?` before any
     maintenance may be scheduled
   - already scheduled -- refuses to double-schedule the same
     maintenance record
   - batch not verified/registered -- independently re-derives the
     referenced batch's own `:verified?`/`:registered?` before any
     shipment may be coordinated
   - shipment volume exceeded -- independently recomputes whether the
     batch's own recorded shipped-to-date volume plus the proposal's
     own claimed volume would exceed the batch's own recorded
     production volume
   - invalid quality-grade -- rejects a fabricated `:quality-grade`
     value outside the closed known set
   - invalid defect-rate -- rejects a `:defect-rate-percent` value
     outside the physically plausible 0-100% range

   Soft escalation (human sign-off required, not a rejection):
   `:flag-quality-concern` (materials-defect / labor-safety /
   labeling-compliance) is an unconditional member of `high-stakes` --
   it always escalates to a human, at every phase, regardless of
   confidence or governor cleanliness (belt-and-suspenders with
   `footwearops.phase`, which also never places it in any phase's
   `:auto` set); low-confidence proposals also escalate.

2. **`footwearops.registry`** -- pure-function domain logic:
   equipment/batch verification, shipment-volume recompute,
   quality-grade validation (closed set `#{:grade-a :grade-b :grade-c
   :irregular :reject}`), defect-rate plausibility validation
   (0-100%), and draft maintenance-schedule/shipment-coordination
   record construction (`MNT-######`/`SHP-######` unsigned drafts).
   This vertical has NO pre-existing `kotoba-lang/footwear`-style
   capability library to wrap (verified: no such repo exists) --
   self-contained domain logic, re-verified independently by the
   governor, the same "ground truth, not self-report" discipline
   established across prior actors (most directly
   `cloud-itonami-isic-1610`'s `sawmilling.registry`).
3. **`footwearops.store`** -- `Store` protocol, single `MemStore`
   backend (append-only audit ledger + SSoT for batches, equipment,
   maintenance, shipments, quality-concerns), matching
   `cloud-itonami-isic-1610`'s own scope decision that a second
   Datomic-backed backend is out of scope for this build.
4. **`footwearops.advisor`** (FootwearAdvisor) -- mock deterministic
   advisor + `llm-advisor` seam for a real `langchain.model/ChatModel`;
   structurally cannot generate ops outside the governor's closed
   allowlist; every proposal carries `:effect :propose`.
5. **`footwearops.phase`** -- 0->3 staged rollout;
   `:log-production-batch` (no physical/financial risk) is the ONLY
   op that may auto-commit, and only at phase 3 when governor-clean;
   `:schedule-maintenance`/`:flag-quality-concern`/
   `:coordinate-shipment` are deliberately absent from every phase's
   `:auto` set, permanently.
6. **`footwearops.operation`** -- langgraph-clj StateGraph
   OperationActor: `intake -> advise -> govern -> decide -> commit |
   hold | request-approval`, one graph run = one auditable operation,
   checkpointed, `interrupt-before #{:request-approval}` for
   human-in-the-loop.
7. **`footwearops.sim`** -- demo driver exercising the full
   coordination episode plus every HARD-hold scenario directly
   (not-propose-effect, unknown-op, equipment-not-verified,
   batch-not-verified, shipment-volume-exceeded,
   direct-operate-blocked, already-scheduled, invalid-grade,
   invalid-defect-rate).

### What this actor does NOT do

Explicitly documented (README, this ADR, `footwearops.governor`/
`footwearops.phase` ns docstrings): no cutting/sewing/lasting/molding
equipment control, no direct actuation, no plant-safety/labor-safety/
labeling-compliance adjudication (that remains the human plant
supervisor's exclusive authority). Regulatory context is documented
informationally in this ADR and the actor's own ADR-0001 (EU Directive
94/11/EC footwear labelling, US FTC/CPSC labelling guidance, Japan's
家庭用品品質表示法) but the actor never adjudicates compliance itself
-- `:flag-quality-concern`'s `:labeling-compliance` concern-type
exists solely to surface such an issue to a human.

## Verification

- `cloud-itonami-isic-1520`: `clojure -M:test` -- raw final line: `Ran
  71 tests containing 194 assertions.` / `0 failures, 0 errors.`
  (governor contract covering all ten hard checks individually, phase
  gating, auto-commit at phase 3 for `:log-production-batch`,
  escalation-then-approval and approval-rejection paths for
  `:schedule-maintenance`/`:flag-quality-concern`/
  `:coordinate-shipment`, closed-allowlist enforcement; phase-table
  structural invariants; registry record construction + validation;
  store contract).
- `clojure -M:lint` -- 0 errors, 0 warnings.
- `clojure -M:dev:run` -- runs cleanly end-to-end: full happy-path
  episode (log -> schedule maintenance -> flag quality concern ->
  coordinate shipment, each escalate-then-approve), all nine
  standalone HARD-hold scenarios, no exceptions.
- Commit `2db0599f7b835e1f14c461d9fc15af6e649ceb97` pushed directly to
  `cloud-itonami-isic-1520`'s `main` (fresh repository, initial
  commit); verified `git merge-base --is-ancestor` against
  `origin/main` before proceeding.
- Post-merge re-verification (fresh `git clone --depth 1` into a NEW
  scratch directory, `kotoba-lang/langgraph` and `kotoba-lang/
  langchain` cloned as sibling directories so the repo's own
  `:local/root` deps resolve exactly as a real monorepo checkout
  would): re-ran `clojure -M:test` against the clean clone -- same
  green result (`Ran 71 tests containing 194 assertions.` / `0
  failures, 0 errors.`), raw output pasted in the closing report.

## Consequences

(+) `cloud-itonami-isic-1520` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor in this
fleet, closing a genuine fresh-scaffold gap (no prior repository, no
prior fabricated report to correct).
(+) `kotoba-lang/industry` registry `"1520"` entry updated in place to
`:maturity :implemented`, corrected `:repo`/`:business-id` (from the
stale non-existent `gftdcojp/cloud-itonami-C1520` to the real
`cloud-itonami/cloud-itonami-isic-1520`), and an ADR reference.
(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off; direct cutting/lasting/assembly-line-equipment
operation is permanently, structurally blocked (two independent
layers: `footwearops.governor`'s `line-operate-blocked-violations`
and `footwearops.phase`'s permanent absence from every `:auto` set).
(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation remains human-controlled via
external channels.
(-) No integration with real factory-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.
(-) No real LLM is wired into the default advisor (mock-by-default,
matching every sibling actor in this fleet); `footwearops.advisor/
llm-advisor` is the documented seam for swapping in a real
`langchain.model/ChatModel`.
