# ADR-2608500000: cloud-itonami-isic-2391 (Manufacture of refractory products) refractory-products-plant operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-2392 (Manufacture of clay building
materials -- the reference module shape this actor mirrors, independently
re-read in full before use), the `kotoba-lang/industry` registry's `"2391"`
catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-C2391` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"2391"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C2391` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests; confirmed 404 via `gh api` before any work began, both for that
placeholder name and for `cloud-itonami/cloud-itonami-isic-2391` itself).
This is part of an ongoing careful, smaller-batch rollout (one ISIC class
per agent, a capable model, mandatory verification) after a prior
18-agent haiku batch produced a 61% defect rate on other ISIC classes;
126+ consecutive agents on this stricter protocol had already succeeded
before this one. This ADR covers ISIC 2391 only. Before any work began,
the registry entry's identity (`{:id "2391" :name "Manufacture of
refractory products"}`) was independently verified against a fresh clone
of `kotoba-lang/industry`, per this fleet's caution against ID/name
mismatches (prior agents in this fleet had mislabeled other ISIC classes)
-- confirmed correct, no mismatch, and the `:name` was not one of the
~10% of registry entries with a truncated `"..."` seed-data bug.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-2391` as a REFRACTORY-PRODUCTS
PLANT OPERATIONS COORDINATION actor (not direct kiln/pressing-line
control authority), mirroring `cloud-itonami-isic-2392`'s verified module
shape (`advisor`/`governor`/`operation`/`phase`/`registry`/`sim`/`store`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY/LICENSE) with fresh, refractory-products-specific
domain logic under the `refractorymfg` namespace (grep-verified UNIQUE
fleet-wide via `gh search code`, as is the
`:refractory-plant-operations-governor` blueprint keyword). ISIC 2391
covers high-temperature-service refractory ceramics (fire brick, kiln
lining, furnace lining materials) for industrial furnaces and kilns,
distinct from ISIC 2392 (construction brick/tile) and ISIC 2393 (other
porcelain/ceramic products):

1. **`refractorymfg.registry`** -- pure functions: equipment/batch
   verified+registered gates, independent shipment-weight recompute
   against a batch's own logged production weight, a closed
   `valid-product-types` set (`:fireclay-brick`/`:silica-brick`/
   `:high-alumina-brick`/`:magnesia-brick`/`:insulating-firebrick`/
   `:monolithic-castable`/`:ramming-mix`/`:kiln-furniture`), and two
   refractory-specific quality-plausibility checks with no direct 2392
   analog: `thermal-shock-cycles-valid?` (0-200 water-quench
   cycles-to-failure, e.g. ASTM C1171) and `cold-crushing-strength-
   valid?` (0-300 MPa) -- replacing 2392's `:dimensional-deviation-
   percent`/`:defect-rate-percent` pair, since refractory products are
   graded by thermal-shock resistance and compressive strength rather
   than building-brick dimensional-spec conformance.
2. **`refractorymfg.governor`** (`Refractory Plant Operations
   Governor`) -- eleven concrete HARD checks (mirroring 2392's own
   eleven, one-for-one, with the two quality-plausibility checks
   swapped for the domain's own vocabulary): request-level
   propose-only, closed op allowlist, closed proposal-effect
   allowlist, a PERMANENT `:actuate-kiln-pressing-line? true` block
   (this vertical's pressing-line/kiln-line-equipment-control scope
   boundary -- both a pressing-line press and a kiln line are in
   physical scope here, unlike 2392's single extrusion/kiln-line
   flag), independent equipment verified/registered gate before
   maintenance scheduling, independent batch verified/registered gate
   before shipment coordination, independent shipment-weight
   recompute, a double-schedule guard, invalid-product-type,
   invalid-thermal-shock-cycles, invalid-cold-crushing-strength, plus
   the confidence-floor/high-stakes escalation gate.
3. **`refractorymfg.store`** -- single `MemStore` backend (atom of
   EDN) behind a `Store` protocol, same seam as every sibling
   `cloud-itonami-isic-*` actor; sample data seeds one
   verified+registered batch with shipping headroom, one
   verified+registered batch near its own logged weight ceiling (a
   small shipment blows through it -- HARD hold), one
   UNVERIFIED/unregistered batch, one verified+registered tunnel-kiln
   unit, one UNVERIFIED/unregistered isostatic-press unit.
4. **`refractorymfg.advisor`** (`RefractoryAdvisor`) -- deterministic
   mock advisor (same shape as every sibling's own mock), a
   `llm-advisor` seam for a real `langchain.model/ChatModel`, and a
   defensive EDN-proposal parser that fails safe to a low-confidence
   noop on any parse/shape error.
5. **`refractorymfg.phase`** -- Phase 0->3 rollout;
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are permanently absent from every phase's
   `:auto` set (asserted as a structural invariant in
   `phase_test.cljc`); only `:log-production-batch` may auto-commit at
   phase 3 when governor-clean.
6. **`refractorymfg.operation`** -- one langgraph-clj StateGraph run
   per coordination request (`:intake -> :advise -> :govern -> :decide
   -> :commit | :hold | :request-approval`), `interrupt-before
   #{:request-approval}` for human-in-the-loop plant-supervisor/
   shipping-approver sign-off, identical wiring to 2392's own
   `operation.cljc`.

### What this actor does NOT do

**CRITICAL SCOPE BOUNDARY -- safety-critical domain** (kiln-firing
thermal/burn hazard at higher-than-building-material-brick service
temperatures, refractory-dust exposure -- crystalline silica/alumina/
magnesia respirable dust at raw-material batching and pressing, a
well-documented silicosis risk in this industry -- and pressing-line
pinch-point hazard): this actor does NOT control the pressing-line
press or kiln line directly, does NOT make plant-safety or
thermal-safety decisions (exclusive human plant-supervisor authority),
and does NOT actuate the pressing-line press or kiln line. All four
ops are `:effect :propose` only; `:flag-safety-concern` ALWAYS
escalates regardless of confidence, with no phase or confidence
threshold ever bypassing human sign-off.

## Verification

- `cloud-itonami-isic-2391`: `clojure -M:test` -- raw final lines:
  `Ran 76 tests containing 209 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, equipment-not-verified, batch-not-verified,
  shipment-weight-exceeded, kiln-line-actuate-blocked,
  already-scheduled, invalid-product-type, invalid-thermal-shock-
  cycles, invalid-cold-crushing-strength) -- ran clean end to end.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`).
- Repo created fresh (`gh repo create cloud-itonami/cloud-itonami-
  isic-2391` + push), initial commit
  `2c8c34ef8c9317e6517467514a57676489691fb8` on `main` (no prior
  history), confirmed via the GitHub commits API.
- `kotoba-lang/industry` registry `"2391"` entry updated in place via
  a direct exact-block edit (only `:repo`/`:business-id`/`:maturity`
  changed; `:required-technologies`/`:optional-technologies`/
  `:operating-states` left as already-registered, since they already
  matched this build's own design): `:repo`/`:business-id` corrected
  from the never-populated `gftdcojp/cloud-itonami-C2391` placeholder
  to `cloud-itonami/cloud-itonami-isic-2391`, `:maturity` `:spec` ->
  `:implemented`.
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion
  bumped (live-recomputed via `(kotoba.industry/maturity-summary)`
  against a freshly re-fetched `origin/main` immediately before the
  edit -- not assumed), plus a new `testing` block asserting
  `:implemented` for `"2391"`, built against a freshly-refetched
  buffer (not a stale read) per this fleet's hot-file discipline for
  this heavily-contended file.
- Final post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone of `kotoba-lang/industry` plus a fresh
  `kotoba-lang/technology` sibling): `clojure -M:test` re-run green,
  the `"2391"` entry confirmed `:maturity :implemented` with the
  corrected `:repo`/`:business-id`, and a sample of 2-3 unrelated
  entries confirmed intact (not clobbered by this edit). No mojibake
  found in `registry.edn`. Exact commit SHAs and raw test-output lines
  for both repos are recorded in the executing agent's final report
  (this ADR intentionally does not duplicate the fast-drifting shared
  `:implemented` counter's exact value at time of writing, since the
  fleet runs at extremely high concurrency and the counter is
  continuously advanced by unrelated concurrent agents; this
  promotion's own +1 was independently confirmed correct and intact
  against a fresh post-merge clone).

## Consequences

(+) `cloud-itonami-isic-2391` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing
a placeholder registry entry that pointed at a repo that never
existed.
(+) `kotoba-lang/industry` registry `"2391"` entry promoted to
`:maturity :implemented`; the shared fleet-wide `:implemented` count
advanced by this promotion's own +1.
(+) `refractorymfg.registry`'s `thermal-shock-cycles-valid?`/
`cold-crushing-strength-valid?` pair demonstrates the same
"ground truth, not self-report" governor-independent-recompute
discipline `cloud-itonami-isic-2392`'s `dimensional-deviation-valid?`/
`defect-rate-valid?` pair established, now on a second,
domain-appropriate quality-metric vocabulary (thermal-shock resistance
and compressive strength rather than dimensional-spec conformance),
further validating the pattern's reusability across this fleet's
ceramics/non-metallic-minerals cluster.
(+) Scope is bounded and verifiable: four HARD invariants elaborated
into eleven concrete governor checks protect against scope creep into
unauthorized equipment operation or kiln/pressing-line actuation;
safety concerns are a circuit-breaker, not a threshold.
(-) Still a simulation/proposal layer, not a real plant-operations
control system -- equipment actuation and kiln-line operation remain
human-controlled via external channels, matching every sibling
actor's own current scope.
(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint, same as every sibling actor.
