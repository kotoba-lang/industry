# ADR-2608200000: cloud-itonami-isic-0149 (Raising of other animals) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607011000 (itonami actor pattern), ADR-2607154000
(cloud-itonami-isic-0145 Raising of swine/pigs coverage, closest domain
analog and module-for-module mirror source)

## Context

ISIC class 0149 (Raising of other animals) is a fresh scaffold — no
prior repo existed at `cloud-itonami/cloud-itonami-isic-0149` before
this ADR (confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-0149`).
The `kotoba-lang/industry` registry entry was independently
re-verified against a fresh clone before any work began: `{:id "0149"
...}` in the live registry has `:name "Raising of other animals"`,
matching this task's premise exactly (no truncation, no mislabeling —
unlike the sibling 0144 entry, which a prior agent in this fleet had to
correct from a wrongly-assumed swine/pigs premise to its actual "Raising
of sheep and goats").

ISIC 0149 is a residual ("not elsewhere classified") livestock category:
any animal-raising activity not already covered by cattle/buffaloes
(0141), horses/other equines (0142), camels/camelids (0143), sheep/goats
(0144), swine (0145), or poultry (0146) — e.g. rabbits, fur animals,
bees/apiculture, silkworms, or game farming. This repository picks
**apiculture (beekeeping/honey production)** as its one concrete worked
illustration (the demo facility, `otherlivestockops.sim`, registers an
apiary; the sample health/biosecurity concern vocabulary leads with
Varroa mite and American Foulbrood), while `otherlivestockops.facts/
husbandry-lines` documents that the same facility/holding abstraction
covers rabbit, fur-animal, sericulture (silkworm), and game-farming
lines unchanged — a deliberate design choice for a category whose ISIC
definition is inherently a residual grouping rather than a single
product line.

The closest domain analog is `cloud-itonami-isic-0145` (Raising of
swine/pigs): both are back-office farm-operations coordinators over a
registered facility/holding entity, with an identical shape of closed
op-allowlist, hard treatment/economic-decision block, and
always-escalate health-concern gate. This build mirrors
`swineops.*` (0145) module-for-module under the `otherlivestockops.*`
namespace, renaming the domain-specific vocabulary: `:log-herd-record`
→ `:log-husbandry-record`, `:schedule-veterinary-visit` →
`:schedule-farm-operation` (generalized to also cover harvest
scheduling, e.g. honey extraction or pelt harvest — 0149's husbandry
lines include non-lethal harvest events that 0145's swine domain has no
analog for), and the blocked-op pair `:administer-treatment`/
`:order-slaughter` → `:administer-treatment`/`:order-cull-or-harvest`
(0149's residual category spans both lethal harvest, e.g. pelt/silk
cocoon harvest, and non-lethal harvest, e.g. honey extraction — the
governor's hard block is scoped to *finalizing* a cull-or-harvest
decision, not to the routine `:schedule-farm-operation` proposal that
may legitimately request a harvest be scheduled).

This vertical is SELF-CONTAINED — no `kotoba-lang/other-livestock`
library exists, so domain logic (facility verification, count/cost/
confidence validation) lives as pure functions in
`otherlivestockops.registry` / `otherlivestockops.facts`, re-verified
independently by the governor, mirroring the discipline established by
`swineops.registry` and every prior 01xx sibling actor.

## Decision

Build `cloud-itonami-isic-0149` from scratch as a governed-actor
implementation of the other-livestock-raising blueprint, following the
advisor/governor/phase-gate architecture established across the fleet:

1. **OtherLivestockOpsAdvisor** (`otherlivestockops.advisor`, sealed
   intelligence node): proposes farm-operations coordination actions
   only, never commits
   - `:log-husbandry-record` — feeding/breeding/health-check batch data logging
   - `:schedule-farm-operation` — feeding/breeding/harvest (e.g. honey extraction, pelt harvest) scheduling proposal
   - `:flag-animal-health-concern` — surface a disease/welfare concern (always escalates)
   - `:order-supplies` — feed/veterinary-supply procurement proposal

2. **Other-Livestock Farm Operations Governor**
   (`otherlivestockops.governor`, independent validation layer, never
   trusts the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally): the
     referenced facility/holding must be independently
     verified/registered before any proposal may proceed; the
     proposal's `:effect` must be `:propose`; `:op` must be in the
     closed four-op allowlist; direct treatment administration
     (`:administer-treatment`) and finalizing a culling/harvest
     decision (`:order-cull-or-harvest`) are PERMANENTLY blocked; a
     `:log-husbandry-record` batch count must be a positive number
   - ESCALATE (human sign-off, always): `:flag-animal-health-concern`
     always escalates; `:order-supplies` above its category cost
     threshold escalates; low confidence (< 0.7) escalates

3. **Scope boundary**:
   - Does NOT directly handle animals — the farm operator's exclusive authority
   - Does NOT make veterinary treatment decisions
   - Does NOT finalize culling or harvest decisions (economic/ethical, human authority)
   - Does NOT declare outbreaks or contact animal-health authorities
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `otherlivestockops.registry` pure
   functions (`cost-exceeds-threshold?`, `husbandry-count-non-positive?`,
   `confidence-below-floor?`) are re-verified independently by the
   governor, following the "ground truth, not self-report" discipline
   established by `swineops.registry` (0145) and every prior sibling
   actor.

5. **Store** (`otherlivestockops.store`): a single in-memory `MemStore`
   backend behind a `Store` protocol, tracking registered
   facility/holding records. A Datomic/kotoba-server-backed store is a
   follow-up, not part of this build (matching every prior 01xx
   sibling).

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop). `otherlivestockops.operation` is a
   synchronous stub of the advisor → governor → phase-gate flow (real
   `langgraph-clj` StateGraph wiring with `interrupt-before`/
   checkpoint-based human-in-the-loop resume for escalated operations is
   deferred, mirroring `swineops.operation`, 0145). Full module set:
   `deps.edn`, `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later),
   `README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
   `CONTRIBUTING.md`, `SECURITY.md`, `docs/business-model.md`,
   `docs/operator-guide.md`. All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-0149` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Other-animal (n.e.c.) farm-operations back-office coordination is
now genuinely implemented and tested (not merely scaffolded). ISIC 0149
moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants (`treatment-or-harvest-blocked`) protect against scope creep
into direct animal handling, veterinary treatment, or
culling/harvest-finalization — distinguishing the permanently-blocked
"finalize a cull/harvest" op from the routine, allowed
"schedule a farm operation that happens to be a harvest" op.

(+) The residual (n.e.c.) nature of ISIC 0149 is handled honestly:
rather than inventing a single fictional species, the design documents
one concrete worked illustration (apiculture) while keeping the
facility/holding abstraction and reference-data tables
(`otherlivestockops.facts/husbandry-lines`,
`otherlivestockops.facts/health-concerns`) generic enough to also cover
rabbit, fur-animal, sericulture, and game-farming lines without any
code change.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All five core modules (governor/store/advisor/registry/phase) plus
`operation`/`sim`/`deps.edn` are present and exercised by 32 tests / 103
assertions across 5 test namespaces (`otherlivestockops.facts-test`,
`otherlivestockops.governor-test`, `otherlivestockops.phase-test`,
`otherlivestockops.registry-test`, `otherlivestockops.store-test`).

(-) Still a simulation/proposal layer, not integrated with a real
Datomic/kotoba-server backend or a real LLM advisor — scope is
deliberately bounded to back-office coordination, matching every prior
01xx sibling actor.

(-) `otherlivestockops.operation` is a synchronous stub; real
`langgraph-clj` StateGraph wiring (checkpoint-based human-in-the-loop
resume) is deferred, mirroring 0145's own deferral.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-0149` repo: fresh scaffold, full module set
  (facts/registry/store/advisor/governor/phase/operation/sim +
  `deps.edn` + `blueprint.edn` + LICENSE + governance docs) pushed to
  `main` at `github.com/cloud-itonami/cloud-itonami-isic-0149`, initial
  commit `82534a07a631a3730397742b73d3a748fa98b63b` (confirmed matches
  `origin/main` HEAD via the GitHub API immediately after push).
- `clojure -M:test`, run from the pushed build directory:
  **`Ran 32 tests containing 103 assertions. 0 failures, 0 errors.`**
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:run` demo narrative registers an apiary facility, proposes
  a `:log-husbandry-record`, and confirms the phase-0 disposition is
  `:escalate` (no autonomous commits during simulation rollout).
- All source is `.cljc` (portable, no JVM-only interop).
- `kotoba-lang/industry` registry entry for `"0149"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "0149" ...}` block (no wholesale
  regeneration), also correcting `:repo` (from the placeholder
  `https://github.com/gftdcojp/cloud-itonami-A0149`) and `:business-id`
  (from `cloud-itonami-A0149`) to the actual
  `https://github.com/cloud-itonami/cloud-itonami-isic-0149` /
  `cloud-itonami-isic-0149`, matching the convention of every other
  `:implemented` 01xx entry — see the registry commit/merge SHA recorded
  alongside this ADR's landing, and the post-merge re-verification
  re-run of `clojure -M:test` from an independent fresh clone.
