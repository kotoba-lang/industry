# ADR-2611220000: cloud-itonami ISIC 1393 (Manufacture of carpets and rugs) coverage

## Status

Accepted. `cloud-itonami-isic-1393` implemented and merged to `main`
on `cloud-itonami/cloud-itonami-isic-1393`, following the verified
fresh-scaffold protocol established across this fleet (part of the
ongoing careful, smaller-batch rollout after a prior 18-agent haiku
batch had a 61% defect rate -- 138+ consecutive agents on the
stricter capable-model + mandatory-verification protocol have
succeeded since; this is one of the last Wave 3 batches).

## Context

`kotoba-lang/industry`'s `registry.edn` carries `{:id "1393" :name
"Manufacture of carpets and rugs" ...}` at `:maturity :spec`, with a
stale, nonexistent placeholder `:repo`/`:business-id`
(`gftdcojp/cloud-itonami-C1393`, confirmed 404 via `gh api`) predating
the current `cloud-itonami/cloud-itonami-isic-<code>` naming
convention. No repo existed at `cloud-itonami/cloud-itonami-isic-1393`
before this work (confirmed 404 via `gh api` before scaffolding).

cloud-itonami models every ISIC industry class as an autonomous
"actor": an LLM/advisor behind an independent Governor, a
langgraph-clj StateGraph, and an append-only audit ledger -- the same
governed-actor pattern used across the fleet (see `build-actor` skill,
`90-docs/adr/2606141500`-family precedent, and the closest sibling
`cloud-itonami-isic-1394`).

## Decision

Implement `cloud-itonami-isic-1393` as a **carpet/rug plant OPERATIONS
COORDINATION actor**, NOT direct tufting/weaving-line control
authority, closely mirroring `cloud-itonami-isic-1394` (Manufacture of
cordage, rope, twine and netting)'s module shape (`advisor` ⊣
`governor` ⊣ `phase` ⊣ `store`, four propose-only ops, `MemStore`-only
backend, Phase 0->3 rollout), substituting carpet/rug-specific ground
truth.

### Proposal ops (closed allowlist, all `:effect :propose`)

- `:log-production-batch` — tufting/weaving/backing batch, output-quality (pile-density / tuft-bind-strength test) data logging
- `:schedule-maintenance` — tufting/weaving-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface an equipment-safety/quality-defect concern, ALWAYS escalates
- `:coordinate-shipment` — outbound product shipment coordination

### Governor (`Carpet & Rug Plant Operations Governor`, `:carpet-rug-plant-operations-governor`)

HARD invariants (always `:hold`, no override), elaborated into twelve
concrete checks in `carpetops.governor`:

1. Plant/batch record must be independently verified/registered
   (`:verified?` AND `:registered?`) before any action is taken
   against it (equipment before maintenance scheduling, batch before
   shipment coordination)
2. The request's own `:effect` must be `:propose` only
3. Any proposal touching tufting/weaving/backing-line-equipment
   control is a HARD, PERMANENT block (closed proposal-effect
   allowlist; `:direct-operate? true` is unconditionally blocked)
4. Closed op-allowlist enforced (`:log-production-batch` /
   `:schedule-maintenance` / `:flag-safety-concern` /
   `:coordinate-shipment` only)

Plus: no double-scheduling the same maintenance record, no fabricated
`:quality-grade`, no physically implausible
`:pile-density-tufts-per-sqm` or `:tuft-bind-strength-lbf` or
`:defect-rate-percent`, and independent shipment-area recompute
against the batch's own logged `:area-square-meters` (never a
self-reported shipped-area claim).

ESCALATE (always human sign-off, SOFT -- overridable by a human):
- `:flag-safety-concern` always escalates, regardless of confidence
- Low-confidence proposals

`:schedule-maintenance` is never in any phase's `:auto` set, including
phase 3 -- only `:log-production-batch` (no physical/financial risk)
may auto-commit, and only at phase 3 when governor-clean.

The governor keyword `:carpet-rug-plant-operations-governor` was
grep-verified UNIQUE fleet-wide (`gh search code
"carpet-rug-plant-operations-governor" --owner cloud-itonami`, zero
hits before this repo was created).

### Registry update

`kotoba-lang/industry`'s `registry.edn` entry for `"1393"` updated
in-place (exact-text block edit, no other entry touched):
- `:maturity` `:spec` -> `:implemented`
- `:repo` `"https://github.com/gftdcojp/cloud-itonami-C1393"` ->
  `"https://github.com/cloud-itonami/cloud-itonami-isic-1393"`
- `:business-id` `"cloud-itonami-C1393"` ->
  `"cloud-itonami-isic-1393"`
- `:operating-states` `[:spec :design :produce :inspect :package
  :audit]` -> `[:intake :design :produce :inspect :package :audit]`
  (mirrors the same `:spec`->`:intake` substitution
  `cloud-itonami-isic-1394`'s own promotion made)
- `:required-technologies`/`:name`/`:id`/`:optional-technologies`
  unchanged (already correct pre-existing values)

`test/kotoba/industry_test.clj`'s `:implemented` count assertion
bumped to the true count recomputed from the live file via
`kotoba.industry/maturity-summary` (not `grep -c`) after the registry
edit landed.

## Consequences

(+) ISIC 1393 (carpets and rugs) now has a documented, governed,
auditable operations-coordination actor, consistent with the rest of
the cloud-itonami fleet.

(+) The registry's pre-existing stale placeholder repo/business-id for
"1393" is corrected to the current naming convention as part of this
promotion, removing a dangling reference to a nonexistent
`gftdcojp/cloud-itonami-C1393` repo.

(-) Still a simulation/proposal layer; no integration with real
plant-management databases (equipment telemetry, batch tracking,
freight dispatch).

## Verification

- `cloud-itonami-isic-1393`: `clojure -M:test` from an independent
  fresh clone (paired with a fresh `kotoba-lang/langgraph` +
  `kotoba-lang/langchain` sibling checkout) -- raw output pasted in
  the landing report; `clojure -M:lint` clean; `clojure -M:dev:run`
  demo narrative exercises proposal submission, escalation, and every
  HARD-hold scenario directly.
- `kotoba-lang/industry`: post-merge fresh clone (+ `../technology`
  sibling) `clojure -M:test` green; registry re-checked for the
  correct `"1393"` entry surviving with `:maturity :implemented` and
  no mojibake; a sample of 2-3 other entries' blocks confirmed
  untouched.
- All actor source is `.cljc` (portable ClojureScript / JVM / nbb) --
  no JVM-only interop.
