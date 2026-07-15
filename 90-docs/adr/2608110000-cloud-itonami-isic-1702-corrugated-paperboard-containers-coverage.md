# ADR-2608110000: cloud-itonami-isic-1702 (Manufacture of corrugated paper and paperboard and of containers of paper and paperboard) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607161600 (cloud-itonami-isic-1701 Manufacture of pulp, paper and paperboard coverage, closest domain analog)

## Context

ISIC class 1702 (Manufacture of corrugated paper and paperboard and of
containers of paper and paperboard) is a fresh scaffold — no prior
repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-1702` before this ADR (confirmed 404
via `gh api repos/cloud-itonami/cloud-itonami-isic-1702` and
`gh api repos/kotoba-lang/cloud-itonami-isic-1702` before starting).
The `kotoba-lang/industry` registry entry was independently
re-verified against a fresh read via the GitHub git-data API before
any work began: `{:id "1702" ...}` in the live registry has its
`:name` field literally truncated with an ellipsis
(`"Manufacture of corrugated paper and paperboard and of conta..."`)
— a pre-existing cosmetic truncation affecting 67 of 645 entries
registry-wide, unrelated to this task. The truncated text is a clean
prefix of the expected full name "Manufacture of corrugated paper and
paperboard and of containers of paper and paperboard" (the same 5
digits, same "conta[iners]" prefix), so this is confirmed to be the
correct ISIC class, not a mislabeled premise — the truncation was left
as-is per this fleet's standing instruction not to reformat/repair
unrelated parts of `registry.edn`, but the full untruncated name was
restored as part of this task's own exact-text edit of the `"1702"`
block (the only entry touched).

The closest domain analog is `cloud-itonami-isic-1701` (Manufacture of
pulp, paper and paperboard): both are back-office coordination actors
for a fixed processing PLANT (not a field site) with heavy equipment,
a real physical-safety dimension, and a central ground-truth
**production batch** entity independently gated alongside an
**equipment** entity. Corrugated-board/box manufacturing differs from
1701 in one structural respect that shapes this design: 1701's domain
(chemical/mechanical pulping, effluent discharge) carries a distinct,
separately-regulated environmental-discharge concern; corrugating/
converting lines (corrugator, printer-slotter, flexo-folder-gluer,
die-cutter) have a mechanical/equipment-safety hazard profile but no
analogous regulated-discharge axis, and this task's brief calls for
exactly ONE permanent block ("any proposal touching corrugator/
converting-line-equipment control is a hard, permanent block"), not
1701's two independent permanent blocks (generic equipment-control
allowlist PLUS a separate effluent-discharge-authorization flag).

This vertical is SELF-CONTAINED — no `kotoba-lang/corrugated` library
exists (verified), so domain logic (equipment/batch verification,
shipment-quantity recompute, board/container-grade validation,
burst-strength (Mullen) and edge-crush-test (ECT) plausibility
validation) lives as pure functions in `corrugated.registry` and is
re-verified independently by the governor, mirroring the discipline
established by 1701's `pulppaper.registry` and every prior sibling
actor.

## Decision

Build `cloud-itonami-isic-1702` from scratch as a governed-actor
implementation of the corrugated-packaging blueprint, following the
langgraph StateGraph + independent Governor + Phase 0->3 rollout
architecture established across the fleet:

1. **CorrugatedPackagingAdvisor** (`corrugated.advisor`, sealed
   intelligence node): proposes plant-operations coordination actions
   only, never commits
   - `:log-production-batch` — corrugating/converting batch, output-quality (burst strength, edge crush test) data logging
   - `:schedule-maintenance` — corrugator/converting-line-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface an equipment-safety/quality-defect concern (always escalates)
   - `:coordinate-shipment` — outbound box/sheet shipment coordination proposal

2. **Corrugated Packaging Plant Operations Governor**
   (`corrugated.governor`, independent validation layer, never trusts
   the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into ten concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects — any proposal touching corrugator/converting-line-
     equipment control is PERMANENTLY blocked; a shipment may not push
     a batch's own recorded shipped quantity past its own logged
     production quantity (independently recomputed); no
     double-scheduling the same maintenance record; no fabricated
     `:grade` value; no physically implausible `:burst-strength-kpa`
     (Mullen burst test) or `:edge-crush-kn-m` (Edge Crush Test) value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary**:
   - Does NOT control the corrugator, printer-slotter, flexo-folder-gluer, or die-cutter directly
   - Does NOT make plant-safety or quality-defect-disposition decisions (exclusive to the human plant supervisor)
   - All proposals are `:effect :propose`; actuation is human-approval-gated
   - `:schedule-maintenance` is never a member of any phase's `:auto` set (see `corrugated.phase`)

4. **Self-contained domain logic**: `corrugated.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-quantity-
   exceeded?`, `grade-valid?`, `burst-strength-valid?`, `edge-crush-
   valid?`) are re-verified independently by the governor, following
   the "ground truth, not self-report" discipline established by prior
   actors (most directly `cloud-itonami-isic-1701`'s
   `pulppaper.registry`).

5. **Store** (`corrugated.store`): a single `MemStore` backend behind
   a `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 1701,
   this build does NOT ship a second Datomic-backed store — a second
   backend can be added later behind the same protocol without
   changing any caller.

6. **A direct governor-level test for the permanent equipment-control
   block**: because the deterministic mock advisor always emits a
   fixed `:effect` per op, `equipment-control-blocked-violations` is
   not reachable via the normal actor-graph path with the shipped
   advisor — it is exercised directly against
   `corrugated.governor/check` in
   `equipment-control-blocked-is-held-and-permanently-blocked`, the
   same way a compromised/hallucinating advisor's output would be
   censored (mirroring 1701's own unreached generic-allowlist check,
   made explicit here with its own test since this task's brief names
   it as the domain's one permanent invariant).

7. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked
   via `langgraph.graph/run*`, not `.invoke`), append-only audit
   ledger, full test coverage, demo driver. Full module set:
   `deps.edn`, `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later),
   `README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
   `CONTRIBUTING.md`, `SECURITY.md`, `docs/adr/0001-architecture.md`.
   All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-1702` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Corrugated-board/box plant-operations back-office coordination is
now genuinely implemented and tested (not merely scaffolded). ISIC
1702 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation, independently corroborated by `corrugated.phase`'s
permanent exclusion of `:schedule-maintenance` from every phase's
`:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely corrugated-packaging-
specific elaboration mirroring 1701's own dual-gate structure — this
domain also has two distinct ground-truth entity kinds a proposal can
reference, and each is independently re-derived from its own permanent
record, never trusting the proposal's self-report.

(+) Deliberately scoped to a SINGLE permanent block (unlike 1701's
two), matching this task brief precisely rather than importing 1701's
effluent-discharge axis, which has no domain analog in corrugated-
board/box converting.

(+) Quality-plausibility discipline is explicit: burst-strength
(Mullen) and edge-crush-test (ECT) readings are independently
range-checked, never trusted as self-reported sensor data — a
genuinely corrugated-packaging-specific elaboration of 1701's ISO-
brightness check.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 76 tests / 213 assertions
across 5 test namespaces (`corrugated.operation-test`,
`corrugated.governor-contract-test`, `corrugated.phase-test`,
`corrugated.store-contract-test`, `corrugated.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-1702` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-1702`, initial commit
  `f36f047c30544191725d2a7a0b59fade5808c5d0` (confirmed matches
  `origin/main` HEAD via the GitHub API immediately after push, and
  re-confirmed by an independent fresh clone in a second scratch
  directory).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`),
  run from the pushed build directory AND independently re-run from a
  brand-new fresh clone at commit `f36f047c30544191725d2a7a0b59fade5808c5d0`:
  **`Ran 76 tests containing 213 assertions. 0 failures, 0 errors.`**
  (identical raw output both times).
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-quantity-
  exceeded, already-scheduled, invalid-grade, invalid-burst-strength,
  invalid-edge-crush), plus a direct governor-level check for
  equipment-control-blocked, with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:corrugated-packaging-plant-
  operations-governor` is grep-verified UNIQUE fleet-wide (`gh search
  code "corrugated-packaging-plant" --owner cloud-itonami`, zero hits
  before this repo was created).
- `kotoba-lang/industry` registry entry for `"1702"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "1702" ...}` block (no wholesale
  regeneration; the block's own `:name` was also de-truncated to the
  full ISIC name as part of this same exact-text edit) — see the
  registry commit/merge SHA recorded alongside this ADR's landing, and
  the post-merge re-verification re-run of `clojure -M:test` from an
  independent fresh clone.
