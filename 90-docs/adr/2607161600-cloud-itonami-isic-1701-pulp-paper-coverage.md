# ADR-2607161600: cloud-itonami-isic-1701 (Manufacture of pulp, paper and paperboard) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607154200 (cloud-itonami-isic-1610 Sawmilling and planing of wood coverage, closest domain analog)

## Context

ISIC class 1701 (Manufacture of pulp, paper and paperboard) is a
fresh scaffold — no prior repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-1701` before this ADR (confirmed
404 via `gh api repos/cloud-itonami/cloud-itonami-isic-1701` before
starting). The `kotoba-lang/industry` registry entry was independently
re-verified against a fresh read-only clone before any work began:
`{:id "1701" :name "Manufacture of pulp, paper and paperboard" ...}`
is genuinely what the registry contains at that id (this fleet has
twice mislabeled an assigned ISIC class in prior batches — 0892
assumed to be salt when the registry actually assigns salt to 0893,
and 0144 assumed to be swine when the registry actually assigns swine
to 0145 — so this check is mandatory, not optional).

The closest domain analog is `cloud-itonami-isic-1610` (Sawmilling and
planing of wood): both are back-office coordination actors for a fixed
processing PLANT (not a field site) with heavy equipment, a real
physical-safety dimension, and a central ground-truth **production
batch** entity independently gated alongside an **equipment** entity.
Pulp/paper differs in one structural respect that shapes this design:
1610's safety hazard is purely mechanical (saw-blade injury, kiln
fire, airborne wood dust); 1701's is chemical AND environmental
(pulping-liquor chemical hazard, high-pressure/high-temperature
paper-machine mechanical hazard, and — distinctly — a regulated
effluent-discharge concern with real environmental impact: kraft/
sulfite chemical pulping produces process effluent that must be
treated before release). This actor's second PERMANENT governor block
therefore targets effluent-discharge AUTHORIZATION specifically
(`:discharge-authorize? true`), mirroring 1610's kiln-schedule-
finalize block structurally, but on a genuinely distinct domain
concern, rather than reusing a generic "finalize" flag.

This vertical is SELF-CONTAINED — no `kotoba-lang/pulppaper` library
exists (verified), so domain logic (equipment/batch verification,
shipment-volume recompute, pulp/paper-grade validation, ISO-brightness
plausibility validation) lives as pure functions in
`pulppaper.registry` and is re-verified independently by the governor,
mirroring the discipline established by 1610's `sawmilling.registry`
and every prior sibling actor.

## Decision

Build `cloud-itonami-isic-1701` from scratch as a governed-actor
implementation of the pulp/paper blueprint, following the langgraph
StateGraph + independent Governor + Phase 0->3 rollout architecture
established across the fleet:

1. **PulpPaperAdvisor** (`pulppaper.advisor`, sealed intelligence
   node): proposes plant-operations coordination actions only, never
   commits
   - `:log-production-batch` — pulp/paper-grade/volume/ISO-brightness data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — digester/paper-machine/effluent-treatment-plant maintenance scheduling proposal
   - `:flag-safety-concern` — surface a chemical-hazard/effluent-discharge/equipment-safety concern (always escalates)
   - `:coordinate-shipment` — outbound pulp/paper/paperboard shipment coordination proposal

2. **Pulp & Paper Plant Operations Governor** (`pulppaper.governor`,
   independent validation layer, never trusts the advisor's own
   self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into ten concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct pulping/paper-machine-equipment control);
     `:discharge-authorize? true` on a maintenance schedule
     (authorizing an effluent discharge) is a PERMANENT block; a
     shipment may not push a batch's own recorded shipped volume past
     its own logged production volume (independently recomputed); no
     double-scheduling the same maintenance record; no fabricated
     `:grade` value; no physically implausible `:brightness-percent`
     (ISO brightness) value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical AND
   environmentally-regulated domain — chemical pulping-liquor hazard,
   high-pressure/high-temperature paper-machine mechanical hazard,
   effluent-discharge environmental impact):
   - Does NOT control digesters, paper machines, or effluent-treatment equipment directly
   - Does NOT make plant-safety or hazard decisions (exclusive to the human plant supervisor)
   - Does NOT authorize an effluent discharge (permanently blocked, not a
     rollout milestone still to come — see `pulppaper.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `pulppaper.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-volume-
   exceeded?`, `grade-valid?`, `brightness-valid?`) are re-verified
   independently by the governor, following the "ground truth, not
   self-report" discipline established by prior actors (most directly
   `cloud-itonami-isic-1610`'s `sawmilling.registry`).

5. **Store** (`pulppaper.store`): a single `MemStore` backend behind a
   `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 1610,
   this build does NOT ship a second Datomic-backed store — a second
   backend can be added later behind the same protocol without
   changing any caller.

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked
   via `langgraph.graph/run*`, not `.invoke`), append-only audit
   ledger, full test coverage, demo driver. Full module set:
   `deps.edn`, `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later),
   `README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
   `CONTRIBUTING.md`, `SECURITY.md`, `docs/adr/0001-architecture.md`.
   All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-1701` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Pulp/paper mill plant-operations back-office coordination is now
genuinely implemented and tested (not merely scaffolded). ISIC 1701
moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or effluent-discharge authorization, independently
corroborated by `pulppaper.phase`'s permanent exclusion of
`:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely pulp/paper-specific
elaboration mirroring 1610's own dual-gate structure — this domain
also has two distinct ground-truth entity kinds a proposal can
reference, and each is independently re-derived from its own permanent
record, never trusting the proposal's self-report.

(+) The domain-specific effluent-discharge-authorization block is a
genuinely NEW permanent-block shape for this fleet (distinct from
1610's kiln-schedule-finalize block on the SAME structural axis but a
different real-world concern), implemented as a SEPARATE HARD check
(`discharge-authorize-blocked-violations`) independent of the generic
closed-proposal-effect-allowlist check
(`equipment-control-blocked-violations`) — two independent layers
jointly cover the task brief's "any proposal touching pulping/
paper-machine-equipment control or effluent-discharge-authorization is
a hard, permanent block."

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 71 tests / 200 assertions
across 5 test namespaces (`pulppaper.operation-test`,
`pulppaper.governor-contract-test`, `pulppaper.phase-test`,
`pulppaper.store-contract-test`, `pulppaper.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch/environmental-
compliance-reporting systems — scope is deliberately bounded to
back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-1701` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-1701`, initial commit
  `35b91bc1119402ba8a4b8126deeac90c471ad6be` (confirmed matches
  `origin/main` HEAD via the GitHub API immediately after push, and
  re-confirmed by an independent fresh clone in a second scratch
  directory).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`),
  run from the pushed build directory AND independently re-run from a
  brand-new fresh clone at commit `35b91bc1119402ba8a4b8126deeac90c471ad6be`:
  **`Ran 71 tests containing 200 assertions. 0 failures, 0 errors.`**
  (identical raw output both times).
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-volume-exceeded,
  discharge-authorize-blocked, already-scheduled, invalid-grade,
  invalid-brightness), with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:pulp-paper-plant-operations-
  governor` is grep-verified UNIQUE fleet-wide (`gh search code
  "pulp-paper" --owner cloud-itonami`, zero hits before this repo was
  created).
- `kotoba-lang/industry` registry entry for `"1701"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "1701" ...}` block (no wholesale
  regeneration) — see the registry commit/merge SHA recorded alongside
  this ADR's landing, and the post-merge re-verification re-run of
  `clojure -M:test` from an independent fresh clone.
