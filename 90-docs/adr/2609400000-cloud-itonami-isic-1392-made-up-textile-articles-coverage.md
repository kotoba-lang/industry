# ADR-2609400000: cloud-itonami-isic-1392 (Manufacture of made-up textile articles, except apparel) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1313 (Finishing of textiles -- the
reference module shape this actor mirrors, independently re-read in
full before use), the `kotoba-lang/industry` registry's `"1392"`
catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-C1392` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1392"` entry at
`:maturity :spec` pointing at
`https://github.com/gftdcojp/cloud-itonami-C1392` -- a placeholder
repo link with no actual implementation behind it (no source, no
tests; confirmed 404 via `gh api` before any work began, as was the
new-convention `cloud-itonami/cloud-itonami-isic-1392` name). This is
part of an ongoing careful, smaller-batch rollout (one ISIC class per
agent, a capable model, mandatory verification) after a prior
18-agent haiku batch produced a 61% defect rate on other ISIC
classes; 132+ consecutive agents on this stricter protocol had all
succeeded before this one. This ADR covers ISIC 1392 only. Before any
work began, the registry entry's identity (`{:id "1392" :name
"Manufacture of made-up textile articles, except apparel"}`) was
independently verified against a fresh clone of `kotoba-lang/industry`
(`resources/kotoba/industry/registry.edn`), per this fleet's caution
against ID/name mismatches -- confirmed correct, no mismatch. No
prior `cloud-itonami-isic-1392` repo existed under the `cloud-itonami`
org.

ISIC Rev.5 1392 covers plants that cut and sew already-finished fabric
into bedding, curtains, towels, and table linens -- distinct from
wearing apparel manufacture (ISIC 1410) and from carpets and rugs
(ISIC 1393), both separate sibling classes.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1392` as a made-up-textile-
articles-plant PLANT OPERATIONS COORDINATION actor (not direct
cutting/sewing-line control authority), mirroring
`cloud-itonami-isic-1313`'s verified module shape (`advisor`/
`governor`/`operation`/`phase`/`registry`/`sim`/`store`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY/`docs/adr/0001-architecture.md`) with fresh,
made-up-textile-articles-specific domain logic under the
`madeuptextileops` namespace, module-for-module
(`madeuptextileops.*` in place of `finishingops.*`).

1392 is finished-goods manufacture (cutting and sewing finished
fabric into bedding, curtains, towels, and table linens), not
contract processing of customer-supplied greige fabric like 1313 --
so unlike 1313, batch records carry no `:customer-id` and
`:coordinate-shipment` coordinates a generic outbound shipment rather
than one back to a specific contracting customer.

1. **`madeuptextileops.registry`** -- pure domain logic: a closed
   production-batch quality-grade set (`:grade-a`/`:grade-b`/
   `:grade-c`/`:reject`, the standard first-quality/seconds/thirds/
   reject grading scheme for finished sewn goods), a defect-rate-
   percent plausibility bound (0.0% to 50.0%), equipment/batch
   verified+registered ground-truth gates, and independent
   shipment-unit (`:units`, the batch's own logged finished-unit
   count) recompute against a batch's own logged output.
2. **`madeuptextileops.governor`** -- ten concrete HARD checks (one
   fewer than 1313's own eleven -- see Decision below) plus a
   confidence/high-stakes SOFT escalation gate: request-level
   propose-only, closed four-op allowlist, closed proposal-effect
   allowlist (no direct cutting/sewing-line-equipment control), a
   PERMANENT line-operate block (`:direct-operate? true` on a
   `:schedule-maintenance` proposal), equipment not
   verified/registered, already-scheduled double-schedule guard,
   batch not verified/registered, shipment-units exceeded, invalid
   grade, invalid defect-rate.
3. **`madeuptextileops.phase`** -- Phase 0->3 rollout;
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are permanently absent from every phase's
   `:auto` set (scheduling real maintenance against a cutting/sewing/
   hemming/quilting line has physical consequence; a safety concern
   and a shipment both always need a human), only
   `:log-production-batch` may auto-commit at phase 3 when
   governor-clean.
4. **`madeuptextileops.store`** -- single `MemStore` (atom of EDN)
   behind a `Store` protocol; sample data seeds one
   verified/registered batch with shipping headroom, one
   verified/registered batch nearly fully shipped (a small new
   shipment exceeds its own logged unit count -- HARD hold), one
   unverified/unregistered batch (blocks shipment), one
   verified/registered `:cutting-line` unit, one
   unverified/unregistered `:sewing-line` unit (blocks maintenance).
5. **`deps.edn`/`blueprint.edn`/docs** -- mirror
   `cloud-itonami-isic-1313`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README
   sections). All source is `.cljc`, no JVM-only interop; the actor
   graph is invoked exclusively via `langgraph.graph/run*`.

### Decision: no analog to 1313's eleventh check -- one fewer permanent scope boundary, by design

1313's dyeing/printing/bleaching/mercerizing lines generate chemical
process wastewater subject to effluent-discharge-permit regimes,
requiring a dedicated eleventh HARD check
(`effluent-permit-decision-blocked-violations`). Cutting and sewing
finished fabric into bedding, curtains, towels, and table linens
involves no chemical discharge, so this vertical genuinely has no
analogous regulatory-decision-authority boundary to enforce -- this
is a real domain difference, not an oversight. This vertical's own
permanent scope boundary is narrower and singular, matching the
domain-design brief exactly: "any proposal touching cutting/sewing-
line-equipment control is a hard, permanent block" -- implemented as
the same two-layer combination 1313 itself uses for its own
equipment-control boundary (a closed proposal-effect allowlist PLUS a
dedicated `:direct-operate? true` block on `:schedule-maintenance`),
just without the second, chemical-discharge-specific boundary 1313
also carries.

### What this actor does NOT do

Direct cutting/sewing/hemming/quilting-line-equipment control remains
exclusive to the human plant supervisor, permanently, with no actor
or human-approval override path -- enforced structurally by two
independent HARD checks (the closed proposal-effect allowlist and the
`:direct-operate?` flag check), not just documented.
`:flag-safety-concern` (equipment-safety or quality-defect concern)
ALWAYS escalates to a human, never auto-decided at any confidence or
phase.

## Verification

- `cloud-itonami-isic-1392`: `clojure -M:test` -- raw final line: `Ran
  71 tests containing 192 assertions.` / `0 failures, 0 errors.`
  (both on first local build and re-confirmed from an independent
  fresh clone alongside fresh `kotoba-lang/langgraph`/`kotoba-lang/
  langchain` sibling clones).
- `clojure -M:lint` -- 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly
  (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-units-exceeded, line-operate-blocked,
  already-scheduled, invalid-grade, invalid-defect-rate) -- ran
  clean, no exceptions; the PERMANENT-block scenario
  (`:line-operate-blocked`) independently confirmed present in the
  demo's own audit-ledger output.
- Repo created fresh (`gh repo create` + push), commit
  `7a5f6f4b25f6b33f39e68d84b38622af699ced27` on
  `cloud-itonami-isic-1392`'s `main` (initial commit, no prior
  history), confirmed via the GitHub API's
  `repos/cloud-itonami/cloud-itonami-isic-1392/commits/main` `.sha`.
- `kotoba-lang/industry` registry `"1392"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1392` placeholder to
  `cloud-itonami/cloud-itonami-isic-1392`, `:maturity` `:spec` ->
  `:implemented`), landed via the flow described in this repo's own
  commit history on `resources/kotoba/industry/registry.edn`, exact-
  block edit only.
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion:
  recomputed live via `(industry/maturity-summary)` (not `grep -c`)
  on a freshly re-fetched `origin/main` immediately before landing,
  not assumed. See this repo's own commit history on
  `test/kotoba/industry_test.clj` for the exact before/after count and
  the resulting `Ran N tests containing M assertions, 0 failures, 0
  errors` output.
- Post-merge re-verification (CRITICAL-6 discipline): re-cloned
  `kotoba-lang/industry` fresh (plus a fresh `kotoba-lang/technology`
  sibling) after the registry PUT/merge landed, re-ran the full
  `clojure -M:test` suite, and re-confirmed the `"1392"` entry's own
  survival (`:maturity :implemented`, corrected `:repo`/
  `:business-id`) plus 2-3 sibling entries' blocks untouched. No
  mojibake found in `registry.edn`.

## Consequences

(+) `cloud-itonami-isic-1392` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing
a placeholder registry entry that pointed at a repo that never
existed.
(+) `kotoba-lang/industry` registry `"1392"` entry promoted to
`:maturity :implemented`; the live-recomputed `:implemented` count
advanced by this promotion's own +1 share (see this repo's own commit
history on `test/kotoba/industry_test.clj` for the exact before/after
numbers, since concurrent sibling promotions may have landed in the
same window).
(+) `madeuptextileops.governor`'s narrower, single-permanent-boundary
shape (vs. 1313's two independent permanent boundaries) is a
reusable pattern demonstrating that a plant-operations-coordination
actor's HARD-invariant count should track the DOMAIN's own real
regulatory/safety surface, not mechanically copy a reference actor's
full check count -- fewer checks here is a correct domain fact, not a
regression.
(-) Still a simulation/proposal layer, not a real plant-operations
control system. Cutting/sewing/hemming/quilting-line equipment
actuation remains human-controlled via external channels.
(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.
