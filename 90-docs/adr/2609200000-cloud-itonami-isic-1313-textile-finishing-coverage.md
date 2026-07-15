# ADR-2609200000: cloud-itonami-isic-1313 (Finishing of textiles) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1391 (Manufacture of knitted and
crocheted fabrics -- the reference module shape this actor mirrors,
independently re-read in full before use), the `kotoba-lang/industry`
registry's `"1313"` catalog entry (previously `:spec` with a
placeholder `gftdcojp/cloud-itonami-C1313` repo link that was never
populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1313"` entry at
`:maturity :spec` pointing at
`https://github.com/gftdcojp/cloud-itonami-C1313` -- a placeholder
repo link with no actual implementation behind it (no source, no
tests; confirmed 404 via `gh api` before any work began, as was the
new-convention `cloud-itonami/cloud-itonami-isic-1313` name). This is
part of an ongoing careful, smaller-batch rollout (one ISIC class per
agent, a capable model, mandatory verification) after a prior
18-agent haiku batch produced a 61% defect rate on other ISIC
classes; 126+ consecutive agents on this stricter protocol had all
succeeded before this one. This ADR covers ISIC 1313 only. Before any
work began, the registry entry's identity (`{:id "1313" :name
"Finishing of textiles"}`) was independently verified against a fresh
clone (via the GitHub git-data blob API, not the CDN-cached
raw.githubusercontent.com mirror), per this fleet's caution against
ID/name mismatches -- confirmed correct, no mismatch. No prior
`cloud-itonami-isic-1313` repo existed under the `cloud-itonami` org.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1313` as a textile-
finishing-plant PLANT OPERATIONS COORDINATION actor (not direct
dyeing/printing-line control authority), mirroring
`cloud-itonami-isic-1391`'s verified module shape (`advisor`/
`governor`/`operation`/`phase`/`registry`/`sim`/`store`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY/`docs/adr/0001-architecture.md`) with fresh,
textile-finishing-specific domain logic under the `finishingops`
namespace.

1313 is **contract processing**: dyeing, printing, bleaching, and
mercerizing of customer-SUPPLIED greige fabric (ISIC Rev.5 wording),
distinct from primary fibre/yarn/fabric manufacturing. This shapes two
structural differences from the 1391 reference: every batch record
carries a `:customer-id` (the plant does not own the fabric it
processes), and `:coordinate-shipment` coordinates a shipment BACK TO
THE CUSTOMER rather than to a generic buyer-warehouse.

1. **`finishingops.registry`** -- pure domain logic: a closed
   production-batch colorfastness-grade set (`:grade-5`/`:grade-4`/
   `:grade-3`/`:grade-2`/`:grade-1`, mirroring the AATCC/ISO gray-scale
   colorfastness rating), a shrinkage-rate-percent plausibility bound
   (-5.0% to 20.0%, allowing for slight fabric growth from heat-set/
   mercerizing finishing rather than only shrinkage), equipment/batch
   verified+registered ground-truth gates, and independent
   shipment-yardage (`:volume-yards`, the batch's own logged finished-
   fabric yardage post-shrinkage) recompute against a batch's own
   logged output.
2. **`finishingops.governor`** -- eleven concrete HARD checks (one
   more than 1391's own ten) plus a confidence/high-stakes SOFT
   escalation gate: request-level propose-only, closed four-op
   allowlist, closed proposal-effect allowlist (no direct dyeing/
   printing-line-equipment control), a PERMANENT line-operate block
   (`:direct-operate? true` on a `:schedule-maintenance` proposal), a
   PERMANENT effluent-permit-decision block (`:permit-decision? true`
   on ANY proposal -- the eleventh check, added beyond 1391's shape;
   see Decision below), equipment not verified/registered,
   already-scheduled double-schedule guard, batch not
   verified/registered, shipment-volume exceeded, invalid grade,
   invalid shrinkage-rate.
3. **`finishingops.phase`** -- Phase 0->3 rollout;
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are permanently absent from every phase's
   `:auto` set (scheduling real maintenance against a dyeing/printing/
   bleaching/mercerizing line has physical consequence; a safety
   concern and a shipment both always need a human), only
   `:log-production-batch` may auto-commit at phase 3 when
   governor-clean.
4. **`finishingops.store`** -- single `MemStore` (atom of EDN) behind
   a `Store` protocol; sample data seeds one verified/registered batch
   with shipping headroom, one verified/registered batch nearly fully
   shipped (a small new shipment exceeds its own logged yardage --
   HARD hold), one unverified/unregistered batch (blocks shipment),
   one verified/registered `:dyeing-line` unit, one
   unverified/unregistered `:mercerizing-line` unit (blocks
   maintenance).
5. **`deps.edn`/`blueprint.edn`/docs** -- mirror
   `cloud-itonami-isic-1391`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README
   sections). All source is `.cljc`, no JVM-only interop; the actor
   graph is invoked exclusively via `langgraph.graph/run*`.

### Decision: the eleventh governor check -- a dedicated effluent-discharge-permit-decision block

Textile dyeing/printing/bleaching/mercerizing plants generate chemical
process wastewater subject to discharge-permit regimes (e.g. US Clean
Water Act NPDES 40 CFR Part 410, EU Industrial Emissions Directive
2010/75/EU, Japan's 水質汚濁防止法). This actor's `:flag-safety-
concern` op covers `:chemical-handling`/`:effluent-discharge`
concerns, but flagging a concern must never slide into DECIDING it --
issuing or deciding an effluent-discharge-permit outcome is a
regulatory authority's / human plant supervisor's exclusive act. This
is modeled as a dedicated, symmetric HARD-PERMANENT governor check
(`effluent-permit-decision-blocked-violations`, gated on ANY
proposal's own `:permit-decision? true` field) alongside the existing
`:direct-operate? true` line-operate block -- two independent,
unconditional scope boundaries, neither overridable by phase or human
approval. This is the one structural addition beyond 1391's ten-check
shape.

### What this actor does NOT do

Direct dyeing/printing/bleaching/mercerizing-line-equipment control
remains exclusive to the human plant supervisor, permanently, with no
actor or human-approval override path -- enforced structurally by two
independent HARD checks (the closed proposal-effect allowlist and the
`:direct-operate?` flag check), not just documented. Deciding or
issuing an effluent-discharge-permit outcome remains exclusive to a
human plant supervisor / regulatory authority, permanently, enforced
by a third independent HARD check (the `:permit-decision?` flag
check). `:flag-safety-concern` (chemical-handling or
effluent-discharge concern) ALWAYS escalates to a human, never
auto-decided at any confidence or phase.

## Verification

- `cloud-itonami-isic-1313`: `clojure -M:test` -- raw final line:
  `Ran 72 tests containing 198 assertions.` / `0 failures, 0 errors.`
  (both on first local build and re-confirmed from an independent
  fresh clone alongside fresh `kotoba-lang/langgraph`/`kotoba-lang/
  langchain` sibling clones).
- `clojure -M:lint` -- 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly
  (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-volume-exceeded, line-operate-blocked,
  effluent-permit-decision-blocked, already-scheduled, invalid-grade,
  invalid-shrinkage-rate) -- ran clean, no exceptions; both new
  PERMANENT-block scenarios (`:line-operate-blocked` and the new
  `:effluent-permit-decision-blocked`) independently confirmed present
  in the demo's own audit-ledger output.
- Repo created fresh (`gh repo create` + push), commit
  `3f289fc9b292b1d006b84454dd03ba86f5b22b53` on
  `cloud-itonami-isic-1313`'s `main` (initial commit, no prior
  history), confirmed via the GitHub API's
  `repos/cloud-itonami/cloud-itonami-isic-1313/commits/main` `.sha`.
- `kotoba-lang/industry` registry `"1313"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1313` placeholder to
  `cloud-itonami/cloud-itonami-isic-1313`, `:maturity` `:spec` ->
  `:implemented`, `:operating-states` first state aligned from `:spec`
  to `:intake` per the more recent 13xx `:implemented` convention);
  landed via a Contents-API single-file PUT (sha-checked optimistic
  concurrency, succeeded on the first attempt, commit
  `e14ac2b43357d07baa8eea2ad40b79dc54428770`), exact-block edit only
  (byte-diff-verified: removing the old/new block text from the
  before/after file bodies yields byte-identical remainders, i.e. no
  other entry's block was touched).
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion:
  recomputed live via `(industry/maturity-summary)` (not `grep -c`) on
  a freshly re-fetched `origin/main` immediately before each edit, not
  assumed. This promotion's own testing block landed via a
  Contents-API single-file PUT (commit
  `b5fb2934230e5d6bcb8bd0ca2c7b19e2ad52b642`, 347 -> 348, succeeded on
  the first attempt); a fresh post-merge re-verification clone then
  caught 2 further concurrent sibling promotions that had landed in
  the interim (test runner's own failure diff: `expected: (= 348
  (:implemented m)) actual: (not (= 348 350))`), corrected via a
  second Contents-API single-file PUT (commit
  `7628dd833736639d9761b998ad360ead9f14fed6`, pure count re-sync,
  348 -> 350, no new testing block); a THIRD fresh re-verification
  clone caught 2 more concurrent sibling promotions (352), which by
  the time of that clone had already been folded in and re-synced by
  a concurrent sibling agent's own PUT (commit `cfb98e9`, `test: add
  cloud-itonami-isic-0129 implemented assertion, bump maturity count
  to 352`) -- confirmed green with no further edit needed from this
  agent. Full `kotoba-lang/industry` suite green on this final,
  independently-fresh, untouched clone: `Ran 15 tests containing 1015
  assertions.` / `0 failures, 0 errors.`
- Post-merge re-verification (CRITICAL-6 discipline, repeated across
  three successive fresh clones as concurrent sibling promotions kept
  landing): final fresh clone HEAD `2c4dc7f8e7e8f245473dc7940ec8dfb1a71670cd`,
  registry.edn's own `"1313"` entry re-confirmed `:maturity
  :implemented` with the corrected `:repo`/`:business-id`; no mojibake
  (`grep -c "â€" resources/kotoba/industry/registry.edn` and a
  `[�]` regex scan both returned 0 matches). Also independently
  re-cloned `cloud-itonami-isic-1313` itself (plus fresh
  `kotoba-lang/langgraph`/`kotoba-lang/langchain` sibling clones) into
  a separate brand-new scratch directory and re-ran `clojure -M:test`:
  `Ran 72 tests containing 198 assertions.` / `0 failures, 0 errors.`
- Known cosmetic note: this promotion's own `industry_test.clj`
  testing-block prose (landed in commit
  `b5fb2934230e5d6bcb8bd0ca2c7b19e2ad52b642`, before this ADR's own
  number was finalized) cites "superproject ADR-2608500000" as a
  forward reference -- by the time this ADR file itself was ready to
  land, `2608500000` had been independently claimed (twice, requiring
  one sibling renumbering to `2609100000`) by two unrelated ADRs
  (`cloud-itonami-isic-2391`, `cloud-itonami-isic-2815`), so this
  ADR's real number is `2609200000` instead. That single stale
  cross-reference string was corrected via one further targeted
  Contents-API single-file PUT (see this repo's own commit history on
  `test/kotoba/industry_test.clj` immediately following this ADR's own
  landing) -- exact-string edit only, not a wholesale rewrite.

## Consequences

(+) `cloud-itonami-isic-1313` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing
a placeholder registry entry that pointed at a repo that never
existed.
(+) `kotoba-lang/industry` registry `"1313"` entry promoted to
`:maturity :implemented`; the live-recomputed `:implemented` count
crossed 347 -> 352 total across this promotion and several concurrent
sibling promotions landing in the same window (this promotion's own
share is the 347 -> 348 delta; the remainder is other agents' work,
each independently re-verified rather than assumed).
(+) `finishingops.governor`'s dedicated `effluent-permit-decision-
blocked` check is a reusable pattern for any future ISIC class in this
fleet whose domain carries its own regulatory-decision-authority
scope boundary distinct from equipment-control (e.g. other regulated-
discharge or regulated-emission manufacturing classes), demonstrating
that a plant-operations-coordination actor can carry MORE than one
independent PERMANENT scope boundary when the domain's own regulatory
context calls for it.
(-) Still a simulation/proposal layer, not a real plant-operations
control system. Dyeing/printing/bleaching/mercerizing-line equipment
actuation and effluent-discharge-permit decisions remain
human-controlled via external channels.
(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.
