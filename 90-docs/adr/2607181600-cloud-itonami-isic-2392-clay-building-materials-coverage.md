# ADR-2607181600: cloud-itonami-isic-2392 (Manufacture of clay building materials) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-2431 (Casting of iron and steel -- the
reference module shape this actor mirrors, independently re-verified
before use), cloud-itonami-isic-2013 (Manufacture of plastics and
synthetic rubber in primary forms -- second heavy-industrial
plant-coordination reference), the `kotoba-lang/industry` registry's
`"2392"` catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-C2392` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"2392"` entry at
`:maturity :spec` pointing at
`https://github.com/gftdcojp/cloud-itonami-C2392` -- a placeholder repo
link with no actual implementation behind it (no source, no tests).
This is part of an ongoing careful, smaller-batch rollout (one ISIC
class per agent, a capable model, mandatory verification) after a
prior 18-agent haiku batch produced a 61% defect rate (empty
implementations, missing modules, false "all green" reports) on other
ISIC classes; 54+ consecutive agents on this stricter protocol have
all succeeded since. This ADR covers ISIC 2392 only, built and
verified from scratch -- no prior `cloud-itonami-isic-2392` repo
existed under the `cloud-itonami` org (confirmed 404 via `gh api
repos/cloud-itonami/cloud-itonami-isic-2392` before starting).

Before any implementation work, the registry entry was independently
re-verified fresh (cloned `kotoba-lang/industry` to a uniquely-named
scratch dir, not the shared checkout) to confirm `{:id "2392" :name
"Manufacture of clay building materials" ...}` is genuinely what is
registered -- this fleet has previously seen agents mislabel their
assigned ISIC class (0892 assumed=salt, actually peat; 0144
assumed=swine, actually sheep-goats), so this check is mandatory, not
optional. Confirmed correct.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-2392` as a clay building
material (brick and roof-tile) plant PLANT-OPERATIONS COORDINATION
actor (not extrusion-press/kiln-line control authority), mirroring
`cloud-itonami-isic-2431`'s verified module shape (`registry`/`store`/
`governor`/`advisor`/`operation`/`phase`/`sim`, `deps.edn`/
`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/
SECURITY) module-for-module, with fresh, clay-building-materials-
specific domain logic under the `claymfg` namespace:

1. **`claymfg.registry`** -- pure, self-contained domain-logic
   functions (no pre-existing `kotoba-lang/claymfg`-style capability
   library exists to wrap -- verified): equipment-verified?/
   equipment-registered?/equipment-ready?, batch-verified?/
   batch-registered?/batch-ready?, `shipment-weight-exceeded?`
   (independent recompute against the batch's own logged production
   weight), `product-type-valid?` (closed set: solid-brick/
   perforated-brick/hollow-block/facing-brick/paving-brick/roof-tile/
   ridge-tile/clay-drainage-pipe), `dimensional-deviation-valid?`
   (0-100% plausibility ceiling on the batch's own dimensional-spec
   deviation reading -- a data point this vertical has and its 2431
   sibling does not), `defect-rate-valid?` (0-100% plausibility
   ceiling on the batch's own breakage/defect-rate reading), and
   `register-maintenance`/`register-shipment` draft-record
   constructors (unsigned certificates, MNT-/SHP- numbering).
2. **`claymfg.store`** -- single `MemStore` backend (atom of EDN, no
   deps) behind a `Store` protocol; `batches`/`equipment`/
   `maintenance`/`shipments` entity kinds plus a generic `records` map
   and an append-only `ledger`. Sample data: `kiln-001` (verified/
   registered tunnel kiln), `extruder-002` (UNVERIFIED/unregistered
   extrusion press), `batch-001`/`batch-002` (verified/registered,
   the latter near its own logged shipping-weight ceiling), `batch-003`
   (UNVERIFIED/unregistered).
3. **`claymfg.governor`** (`Clay Plant Operations Governor`) -- 11
   independently-verified HARD-violation checks (one more than
   `cloud-itonami-isic-2431`'s own 10 -- the extra
   `invalid-dimensional-deviation` check has no 2431 analog) plus the
   confidence/high-stakes SOFT-escalation gate: request-level
   propose-only, closed op allowlist, closed proposal-effect allowlist
   (`kiln-line-control-blocked`), permanent kiln-line-actuate block
   (`:actuate-kiln-line? true`), equipment not verified/registered,
   already-scheduled (double-schedule guard), batch not verified/
   registered, shipment-weight-exceeded, invalid-product-type,
   invalid-dimensional-deviation, invalid-defect-rate. All HARD,
   unconditional, no human-approval override.
4. **`claymfg.advisor`** (`ClayAdvisor`) -- sealed, deterministic mock
   intelligence node (`mock-advisor`) that proposes only, never
   commits; an `llm-advisor` variant backed by a real
   `langchain.model/ChatModel` is provided for production use with a
   defensive EDN proposal parser (any parse/shape failure yields a
   safe low-confidence noop).
5. **`claymfg.operation`** (`ClayOperationActor`) -- a langgraph-clj
   StateGraph (`intake -> advise -> govern -> decide -> commit | hold
   | request-approval`), `interrupt-before #{:request-approval}` for
   real human-in-the-loop plant-supervisor/shipping-approver sign-off.
6. **`claymfg.phase`** -- Phase 0->3 rollout;
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are permanently absent from every phase's
   `:auto` set (a structural fact, not a rollout milestone); only
   `:log-production-batch` (no physical/financial risk) may auto-commit
   at phase 3 when governor-clean.
7. **`deps.edn`/`blueprint.edn`/docs** -- mirror
   `cloud-itonami-isic-2431`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README
   sections).

### Domain adaptation from the 2431 reference

The closest architectural analog is `cloud-itonami-isic-2431` (Casting
of iron and steel): both are back-office coordination actors for a
fixed processing PLANT with heavy manufacturing equipment and a real
physical safety dimension, sharing the same four-op shape and the same
two-entity verified/registered gate structure (equipment for
maintenance scheduling, batch for shipment coordination). The two
verticals are distinct plants with distinct hazard profiles: 2431's
central physical hazard is molten-metal handling (splash/burn risk at
the melting furnace and pour, furnace radiant-heat exposure,
mold/core-binder fume exposure); 2392's is kiln-firing heat and
clay/silica-dust exposure (kiln-fire/thermal-hazard at the firing
zone, clay/silica-dust hazard at pugging/extrusion, extrusion-press
pinch-point hazard). This build mirrors 2431's architecture closely
but adapts the hazard profile and equipment/product vocabulary: 2392's
permanent equipment-actuation block guards an extrusion press/kiln
line (`:actuate-kiln-line?`) rather than a melting furnace/pouring
line (`:actuate-furnace?`); 2392's production-batch record declares a
`:product-type` (brick/block/tile/pipe families, per ISIC 2392's own
combined scope), a `:dimensional-deviation-percent` (dimensional-spec
conformance, a data point specific to this vertical per the domain
design brief's own "dimensional-spec" requirement, with no direct 2431
analog), and a `:defect-rate-percent` (output-quality data), rather
than 2431's `:alloy-grade`/`:defect-rate-percent` pair alone. `2392` is
also distinct from `cloud-itonami-isic-2391` (Manufacture of
refractory products, a distinct high-temperature-service ceramics
vertical for furnace linings rather than building materials) and
`cloud-itonami-isic-2393` (Manufacture of other porcelain and ceramic
products, a distinct ceramics vertical with a different feedstock and
firing profile), neither of which this build depends on or wraps.

### What this actor does NOT do

Extrusion-press and kiln-line equipment operation remain exclusive to
the human plant supervisor, permanently, with no actor or
human-approval override path -- enforced structurally by the closed
proposal-effect allowlist and the unconditional `kiln-line-actuate-
blocked` check, not just documented.

## Consequences

(+) `cloud-itonami-isic-2392` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing
a placeholder registry entry that pointed at a repo that never
existed.

(+) `kotoba-lang/industry` registry `"2392"` entry promoted to
`:maturity :implemented` (`:repo`/`:business-id` corrected from the
never-populated `gftdcojp/cloud-itonami-C2392` placeholder to
`cloud-itonami/cloud-itonami-isic-2392`) -- see the registry
commit/merge SHA recorded alongside this ADR's landing, and the
post-merge re-verification re-run of `clojure -M:test` from an
independent fresh clone.

(+) `claymfg.registry/dimensional-deviation-valid?`'s 0-100%
plausibility-ceiling shape (parallel to, but independent of,
`defect-rate-valid?`) is a reusable pattern candidate for other
manufacturing actors whose domain has a distinct dimensional-spec
conformance data point separate from output-quality/defect rate.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and kiln-line operation remain
human-controlled via external channels; no integration with real
plant-management databases (equipment telemetry, batch tracking,
freight dispatch).

## Verification

- `cloud-itonami-isic-2392`: `clojure -M:test` -- raw final lines:
  `Ran 76 tests containing 209 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Scaffolded and tested from a uniquely-named scratch dir
  (`/tmp/2392-actor-build/orgs/cloud-itonami/cloud-itonami-isic-2392`),
  never the shared superproject checkout; `deps.edn` resolved against
  freshly-cloned sibling `kotoba-lang/langgraph`/`kotoba-lang/langchain`
  checkouts in the same scratch tree (`../../kotoba-lang/*`), not the
  shared checkout.
- Repo created fresh (`gh repo create` + push), initial commit
  `5a1690b8ffaf59dbde7977fc2a5786f8075d3c2f` on
  `cloud-itonami-isic-2392`'s `main` (confirmed as the tip of
  `origin/main` via `gh api
  repos/cloud-itonami/cloud-itonami-isic-2392/commits/main`).
- All source is `.cljc` (portable ClojureScript/JVM/nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- `kotoba-lang/industry` registry `"2392"` entry updated in place
  (`:repo`/`:business-id` corrected, `:maturity` `:spec` ->
  `:implemented`) via an exact-text in-place edit of the single
  `{:id "2392" ...}` block (no wholesale regeneration);
  `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  to match the live-recomputed implemented-entry count (recomputed via
  `kotoba.industry/maturity-summary` against a fresh `clojure -M:test`
  run from a freshly re-fetched `origin/main`, not assumed/grepped);
  full `kotoba-lang/industry` suite re-run green post-edit and again
  post-merge against a fresh clone.
