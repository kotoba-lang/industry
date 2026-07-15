# ADR-2607151105: cloud-itonami-isic-1511 (Tanning and dressing of leather) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1701 (Manufacture of pulp, paper and
paperboard -- the reference module shape this actor mirrors,
independently re-read in full before use), the `kotoba-lang/industry`
registry's `"1511"` catalog entry (previously `:spec` with a
placeholder `gftdcojp/cloud-itonami-C1511` repo link that was never
populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1511"` entry at
`:maturity :spec` pointing at
`https://github.com/gftdcojp/cloud-itonami-C1511` -- a placeholder
repo link with no actual implementation behind it (no source, no
tests; confirmed 404 via `gh api` before any work began, as was the
new-convention `cloud-itonami/cloud-itonami-isic-1511` name). This is
part of an ongoing careful, smaller-batch rollout (one ISIC class per
agent, a capable model, mandatory verification) after a prior
18-agent haiku batch produced a 61% defect rate on other ISIC
classes; 36+ consecutive agents on this stricter protocol had all
succeeded before this one. This ADR covers ISIC 1511 only. Before any
work began, the registry entry's identity (`{:id "1511" :name
"Tanning and dressing of leather"}`) was independently verified
against a fresh clone, per this fleet's caution against ID/name
mismatches (prior agents in this fleet had mislabeled 0892 as salt
when it was actually peat, and 0144 as swine when it was actually
sheep/goats) -- confirmed correct, no mismatch. No prior
`cloud-itonami-isic-1511` repo existed under the `cloud-itonami` org.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1511` as a leather-tannery
PLANT OPERATIONS COORDINATION actor (not direct tanning/finishing-
line-equipment control authority, not effluent-discharge-
authorization authority), mirroring `cloud-itonami-isic-1701`'s
verified module shape (`advisor`/`governor`/`operation`/`phase`/
`registry`/`sim`/`store`, `deps.edn`/`blueprint.edn`/README/
GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY/`docs/adr/0001-
architecture.md`) with fresh, leather-tanning-specific domain logic
under the `leathertanning` namespace.

1. **`leathertanning.registry`** -- pure domain logic: a closed
   leather-grade / tanning-process-stage set (`:chrome-tanned-wet-
   blue`/`:vegetable-tanned-crust`/`:full-grain`/`:top-grain`/
   `:corrected-grain`/`:split-leather`/`:suede`/`:nubuck`/`:patent-
   leather`/`:bonded-leather`), a shrinkage-temperature (hydrothermal
   stability, Ts) plausibility bound (0-150C -- the real leather-
   science QC parameter analogous to 1701's ISO-brightness check;
   chrome-tanned leather typically reads Ts 100-120C, vegetable-tanned
   75-90C, both comfortably inside this physical ceiling), equipment/
   batch verified+registered ground-truth gates, and independent
   shipment-area (`:area-sqm`, not tonnes -- finished leather is
   conventionally sold by area) recompute against a batch's own
   logged output.
2. **`leathertanning.governor`** -- ten concrete HARD checks
   (mirroring 1701's own ten) plus a confidence/high-stakes SOFT
   escalation gate: request-level propose-only, closed four-op
   allowlist, closed proposal-effect allowlist (no direct tanning-
   drum/finishing-line-equipment control), a PERMANENT
   effluent-discharge-authorization block (`:discharge-authorize?
   true` on a `:schedule-maintenance` proposal), equipment not
   verified/registered, already-scheduled double-schedule guard,
   batch not verified/registered, shipment-area exceeded, invalid
   grade, invalid shrinkage-temperature.
3. **`leathertanning.phase`** -- Phase 0->3 rollout;
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are permanently absent from every phase's
   `:auto` set (scheduling real maintenance against a tanning drum has
   physical consequence; a safety concern and a shipment both always
   need a human), only `:log-production-batch` may auto-commit at
   phase 3 when governor-clean.
4. **`leathertanning.store`** -- single `MemStore` (atom of EDN)
   behind a `Store` protocol; sample data seeds one verified/
   registered batch with shipping headroom, one verified/registered
   batch nearly fully shipped (a small new shipment exceeds its own
   logged area -- HARD hold), one unverified/unregistered batch
   (blocks shipment), one verified/registered `:tanning-drum` unit,
   one unverified/unregistered `:finishing-equipment` unit (blocks
   maintenance).
5. **`deps.edn`/`blueprint.edn`/docs** -- mirror
   `cloud-itonami-isic-1701`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README
   sections). All source is `.cljc`, no JVM-only interop; the actor
   graph is invoked exclusively via `langgraph.graph/run*`.

### What this actor does NOT do

Direct tanning-drum/finishing-line-equipment control and
effluent-discharge authorization remain exclusive to the human
tannery supervisor / environmental compliance officer, permanently,
with no actor or human-approval override path -- enforced
structurally by two independent HARD checks (the closed
proposal-effect allowlist and the `:discharge-authorize?` flag
check), not just documented. `:flag-safety-concern` (chromium VI/
chemical-liquor hazard, effluent-discharge concern, worker-safety
exposure) ALWAYS escalates to a human, never auto-decided at any
confidence or phase.

## Verification

- `cloud-itonami-isic-1511`: `clojure -M:test` -- raw final line:
  `Ran 71 tests containing 200 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly
  (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-area-exceeded,
  discharge-authorize-blocked, already-scheduled, invalid-grade,
  invalid-shrinkage-temperature) -- ran clean, no exceptions.
- Repo created fresh (`gh repo create` + push), commit
  `22b59f336ec18124cbf743d5e80114741e3ffc8c` on
  `cloud-itonami-isic-1511`'s `main` (initial commit, no prior
  history), confirmed via `git rev-parse HEAD` == the GitHub API's
  `repos/.../commits/main` `.sha`.
- `kotoba-lang/industry` registry `"1511"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1511` placeholder to
  `cloud-itonami/cloud-itonami-isic-1511`, `:maturity` `:spec` ->
  `:implemented`); `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion bumped from a live-recomputed 226 to
  227 implemented entries (recomputed via
  `(industry/maturity-summary)` against a freshly re-fetched
  `origin/main` immediately before the edit -- confirmed via the test
  runner's own failure diff, `expected: (= 226 (:implemented m))
  actual: (not (= 226 227))`, not assumed). The edited test file was
  itself re-run (`clojure -M:test`) before commit to confirm it
  parses and passes. Full `kotoba-lang/industry` suite green
  post-edit: `Ran 15 tests containing 947 assertions.` / `0 failures,
  0 errors.` Landed via GitHub API server-side merge (`gh api
  repos/kotoba-lang/industry/merges`), merge commit
  `c64efcd1e0f3406f7f7bde151da6c111a62ec7c9`, succeeded on the first
  attempt (no 409s encountered).
- Post-merge re-verification: freshly re-fetched `origin/main`,
  re-cloned `kotoba-lang/industry` into a brand-new scratch directory
  (plus a fresh `kotoba-lang/technology` sibling clone for
  `deps.edn` resolution) -- fresh clone HEAD confirmed ==
  `c64efcd1e0f3406f7f7bde151da6c111a62ec7c9`, re-ran `clojure -M:test`
  against the clean post-merge clone: `Ran 15 tests containing 947
  assertions.` / `0 failures, 0 errors.` `grep -c "â"
  resources/kotoba/industry/registry.edn` returned `0` (no file-wide
  UTF-8 mojibake). Also independently re-cloned
  `cloud-itonami-isic-1511` itself (plus fresh `kotoba-lang/langgraph`/
  `kotoba-lang/langchain` sibling clones) into a separate brand-new
  scratch directory and re-ran `clojure -M:test`: `Ran 71 tests
  containing 200 assertions.` / `0 failures, 0 errors.`

## Consequences

(+) `cloud-itonami-isic-1511` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing
a placeholder registry entry that pointed at a repo that never
existed.
(+) `kotoba-lang/industry` registry `"1511"` entry promoted to
`:maturity :implemented`, count 226 -> 227.
(+) `leathertanning.registry`'s shrinkage-temperature-valid? check is
a reusable pattern for any future hide/leather-processing ISIC class
in this fleet (e.g. 1512 luggage/leather-goods manufacturing) as the
domain-appropriate physical-plausibility QC check, distinct from
1701's ISO-brightness or 1061's mycotoxin-ppb-ceiling pattern.
(-) Still a simulation/proposal layer, not a real plant-operations
control system. Tanning-drum/finishing-line-equipment actuation and
effluent-discharge decisions remain human-controlled via external
channels.
(-) No integration with real tannery-management databases (equipment
telemetry, batch tracking, freight dispatch, environmental-compliance
reporting systems) -- this is a standalone coordinator blueprint.
