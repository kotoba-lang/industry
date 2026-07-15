# ADR-2607157100: cloud-itonami-isic-1391 (Manufacture of knitted and crocheted fabrics) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1520 (Manufacture of footwear -- the
reference module shape this actor mirrors, independently re-read in
full before use), the `kotoba-lang/industry` registry's `"1391"`
catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-C1391` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1391"` entry at
`:maturity :spec` pointing at
`https://github.com/gftdcojp/cloud-itonami-C1391` -- a placeholder
repo link with no actual implementation behind it (no source, no
tests; confirmed 404 via `gh api` before any work began, as was the
new-convention `cloud-itonami/cloud-itonami-isic-1391` name). This is
part of an ongoing careful, smaller-batch rollout (one ISIC class per
agent, a capable model, mandatory verification) after a prior
18-agent haiku batch produced a 61% defect rate on other ISIC
classes; 66+ consecutive agents on this stricter protocol had all
succeeded before this one. This ADR covers ISIC 1391 only. Before any
work began, the registry entry's identity (`{:id "1391" :name
"Manufacture of knitted and crocheted fabrics"}`) was independently
verified against a fresh clone, per this fleet's caution against
ID/name mismatches (prior agents in this fleet had mislabeled 0892 as
salt when it was actually peat, and 0144 as swine when it was
actually sheep/goats) -- confirmed correct, no mismatch. No prior
`cloud-itonami-isic-1391` repo existed under the `cloud-itonami` org.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1391` as a knitting/
crocheting-mill PLANT OPERATIONS COORDINATION actor (not direct
knitting/crocheting-line control authority), mirroring
`cloud-itonami-isic-1520`'s verified module shape (`advisor`/
`governor`/`operation`/`phase`/`registry`/`sim`/`store`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY/`docs/adr/0001-architecture.md`) with fresh,
knitting/crocheting-specific domain logic under the `knittingops`
namespace.

1. **`knittingops.registry`** -- pure domain logic: a closed
   production-batch quality-grade set (`:grade-a`/`:grade-b`/
   `:grade-c`/`:irregular`/`:reject`), a defect-rate-percent
   plausibility bound (0-100%, mirroring 1520's own defect-rate
   check), equipment/batch verified+registered ground-truth gates,
   and independent shipment-yardage (`:volume-yards`, not pairs --
   knitted/crocheted fabric is conventionally sold by yardage)
   recompute against a batch's own logged output.
2. **`knittingops.governor`** -- ten concrete HARD checks (mirroring
   1520's own ten) plus a confidence/high-stakes SOFT escalation
   gate: request-level propose-only, closed four-op allowlist, closed
   proposal-effect allowlist (no direct circular/flat-knitting-
   machine or crocheting-line-equipment control), a PERMANENT
   line-operate block (`:direct-operate? true` on a
   `:schedule-maintenance` proposal), equipment not
   verified/registered, already-scheduled double-schedule guard,
   batch not verified/registered, shipment-volume exceeded, invalid
   grade, invalid defect-rate.
3. **`knittingops.phase`** -- Phase 0->3 rollout;
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are permanently absent from every phase's
   `:auto` set (scheduling real maintenance against a circular/flat-
   knitting machine or crocheting line has physical consequence; a
   safety concern and a shipment both always need a human), only
   `:log-production-batch` may auto-commit at phase 3 when
   governor-clean.
4. **`knittingops.store`** -- single `MemStore` (atom of EDN) behind
   a `Store` protocol; sample data seeds one verified/registered
   batch with shipping headroom, one verified/registered batch nearly
   fully shipped (a small new shipment exceeds its own logged yardage
   -- HARD hold), one unverified/unregistered batch (blocks
   shipment), one verified/registered `:circular-knitting-machine`
   unit, one unverified/unregistered `:crocheting-line` unit (blocks
   maintenance).
5. **`deps.edn`/`blueprint.edn`/docs** -- mirror
   `cloud-itonami-isic-1520`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README
   sections). All source is `.cljc`, no JVM-only interop; the actor
   graph is invoked exclusively via `langgraph.graph/run*`.

### What this actor does NOT do

Direct circular/flat-knitting-machine or crocheting-line-equipment
control remains exclusive to the human mill supervisor, permanently,
with no actor or human-approval override path -- enforced
structurally by two independent HARD checks (the closed
proposal-effect allowlist and the `:direct-operate?` flag check), not
just documented. `:flag-safety-concern` (materials-safety or
equipment-safety concern) ALWAYS escalates to a human, never
auto-decided at any confidence or phase.

## Verification

- `cloud-itonami-isic-1391`: `clojure -M:test` -- raw final line:
  `Ran 71 tests containing 194 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly
  (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-volume-exceeded,
  line-operate-blocked, already-scheduled, invalid-grade,
  invalid-defect-rate) -- ran clean, no exceptions.
- Repo created fresh (`gh repo create` + push), commit
  `637255d4d60b898b051e758b09110f95d5b12f9d` on
  `cloud-itonami-isic-1391`'s `main` (initial commit, no prior
  history), confirmed via `git rev-parse HEAD` == the GitHub API's
  `repos/.../commits/main` `.sha`.
- `kotoba-lang/industry` registry `"1391"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1391` placeholder to
  `cloud-itonami/cloud-itonami-isic-1391`, `:maturity` `:spec` ->
  `:implemented`); `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion bumped from a live-recomputed 255 to
  257 implemented entries (recomputed via
  `(industry/maturity-summary)` against a freshly re-fetched
  `origin/main` immediately before the edit -- confirmed via the test
  runner's own failure diff, `expected: (= 255 (:implemented m))
  actual: (not (= 255 257))`, and independently corroborated by a
  direct `clojure -M -e "(require 'kotoba.industry) (println
  (kotoba.industry/maturity-summary))"` call returning `{:implemented
  257 ...}` -- not assumed; at least one other sibling promotion
  landed 255 -> 256 concurrently before this one). The edited test
  file was itself re-run (`clojure -M:test`) before commit to confirm
  it parses and passes. Full `kotoba-lang/industry` suite green
  post-edit: `Ran 15 tests containing 954 assertions.` / `0 failures,
  0 errors.` Landed via GitHub API server-side merge (`gh api
  repos/kotoba-lang/industry/merges`), merge commit
  `85d9a4a2d191d7350da00c04c514a65d297c84c1`, succeeded on the first
  attempt (no 409s encountered).
- Post-merge re-verification: freshly re-fetched `origin/main`,
  re-cloned `kotoba-lang/industry` into a brand-new scratch directory
  (plus a fresh `kotoba-lang/technology` sibling clone for
  `deps.edn` resolution) -- fresh clone HEAD confirmed ==
  `85d9a4a2d191d7350da00c04c514a65d297c84c1`, re-ran `clojure -M:test`
  against the clean post-merge clone: `Ran 15 tests containing 954
  assertions.` / `0 failures, 0 errors.` `grep -c "â"
  resources/kotoba/industry/registry.edn` returned `0` (no file-wide
  UTF-8 mojibake). Also independently re-cloned
  `cloud-itonami-isic-1391` itself (plus fresh `kotoba-lang/langgraph`/
  `kotoba-lang/langchain` sibling clones) into a separate brand-new
  scratch directory and re-ran `clojure -M:test`: `Ran 71 tests
  containing 194 assertions.` / `0 failures, 0 errors.`

## Consequences

(+) `cloud-itonami-isic-1391` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing
a placeholder registry entry that pointed at a repo that never
existed.
(+) `kotoba-lang/industry` registry `"1391"` entry promoted to
`:maturity :implemented`, count 255 -> 257 (256 from a concurrent
sibling promotion, 257 from this one).
(+) `knittingops.registry`'s yardage-based shipment-volume-exceeded?
check is a reusable pattern for any future fabric/textile-processing
ISIC class in this fleet (e.g. 1392 made-up textile articles, 1393
carpets and rugs) as the domain-appropriate ground-truth recompute,
distinct from 1520's pairs-based or 1511's area-based equivalents.
(-) Still a simulation/proposal layer, not a real mill-operations
control system. Circular/flat-knitting-machine and crocheting-line
equipment actuation remains human-controlled via external channels.
(-) No integration with real mill-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.
