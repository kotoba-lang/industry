# ADR-2615000000: cloud-itonami-isic-2513 (Manufacture of steam generators, except central heating hot water boilers) steam-generator-manufacturing plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-2815 (Manufacture of ovens, furnaces
and furnace burners -- the reference module shape this actor mirrors,
independently re-read in full before use), the `kotoba-lang/industry`
registry's `"2513"` catalog entry (previously `:spec` with a pre-
existing truncated `:name` field, an unrelated ~10%-of-entries seed-
data bug, corrected as part of this same edit)

## Context

`kotoba-lang/industry`'s registry carried a `"2513"` entry at
`:maturity :spec` with `:repo "https://github.com/gftdcojp/cloud-
itonami-C2513"` (an old, never-populated naming scheme) and a
truncated `:name` field: `"Manufacture of steam generators, except
central heating hot..."` -- confirmed via fresh clone before any work
began to be the known, pre-existing seed-data truncation bug affecting
roughly 10% of registry entries (unrelated to any agent's work, not a
mismatch), matched against the correct full ISIC-08 class name
"Manufacture of steam generators, except central heating hot water
boilers". Confirmed no repo exists yet at `cloud-itonami/cloud-
itonami-isic-2513` (`gh api` 404) before any work began. This is part
of an ongoing careful, smaller-batch rollout (one ISIC class per
agent, a capable model, mandatory verification) after a prior
18-agent haiku batch produced a 61% defect rate on other ISIC
classes; 144+ consecutive agents on this stricter protocol had all
succeeded since. This ADR covers ISIC 2513 only, and is the final
batch of Wave 3 -- after this promotion, only the two deliberately-
scoped-out sensitive classes (2520 weapons/ammunition, 3040 military
fighting vehicles) remain unimplemented in the entire wave. ISIC 2513
covers manufacture of steam generators (fire-tube boilers, water-tube
boilers, waste-heat-recovery boilers, heat-recovery steam generators)
-- explicitly EXCLUDING central heating hot water boilers, which fall
outside this class.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-2513` as a steam-generator-
manufacturing PLANT OPERATIONS COORDINATION actor (not direct
fabrication-line control authority, not a pressure-vessel-safety-
certification authority), mirroring `cloud-itonami-isic-2815`'s
verified module shape (`registry`/`store`/`governor`/`operation`/
`phase`/`advisor`/`sim`, `deps.edn`/`blueprint.edn`/README/GOVERNANCE/
CODE_OF_CONDUCT/CONTRIBUTING/SECURITY, AGPL-3.0-or-later) with fresh,
steam-generator-manufacturing-specific domain logic under the
`steamgenmfg` namespace:

1. **`steamgenmfg.registry`** -- pure functions: equipment/batch
   verified+registered ground-truth checks, independent shipment-
   quantity recompute against the batch's own logged production
   quantity, a closed four-value product-type set
   (`:fire-tube-boiler`/`:water-tube-boiler`/`:waste-heat-recovery-
   boiler`/`:heat-recovery-steam-generator`) that deliberately excludes
   central heating hot water boilers per ISIC 2513's own class
   exclusion, hydrostatic pressure-test plausibility validation
   (`:hydrotest-pressure-bar`, 0-600 bar -- informed by real industrial
   steam-generator/boiler test practice: fire-tube boilers ~10-17 bar,
   water-tube process/cogeneration boilers ~20-100 bar,
   utility/supercritical power boilers up to ~300-350 bar design
   pressure, ASME BPVC Section I hydrotest typically 1.5x MAWP), and
   defect-rate plausibility validation (0-100%).
2. **`steamgenmfg.store`** -- single `MemStore` backend (atom of EDN)
   behind a `Store` protocol; four entity kinds (`batches`/`equipment`/
   `maintenance`/`shipments`) plus a generic domain-agnostic `records`
   map and an append-only `ledger`.
3. **`steamgenmfg.governor`** ("Steam Generator Plant Operations
   Governor") -- 12 independently-verified hard-violation checks over
   a closed four-op allowlist (`:log-production-batch`/`:schedule-
   maintenance`/`:flag-safety-concern`/`:coordinate-shipment`, all
   `:effect :propose` only): request-level propose-only, closed op
   allowlist, closed proposal-effect allowlist (no direct
   fabrication/welding/pressure-vessel-assembly-line-equipment
   control), a PERMANENT equipment-actuate block
   (`:actuate-equipment? true`), a PERMANENT pressure-vessel-safety-
   certification-authority block (`:issue-certification? true` -- this
   actor never self-issues an ASME Boiler and Pressure Vessel Code
   (BPVC) Section I "S" stamp certification or a National Board of
   Boiler and Pressure Vessel Inspectors (NBBI) registration),
   independent equipment verified/registered gate before maintenance
   scheduling, a double-schedule guard, independent batch
   verified/registered gate before shipment coordination, independent
   shipment-quantity-exceeded recompute, invalid-product-type,
   invalid-hydrotest-pressure-bar, and invalid-defect-rate rejection.
   `:flag-safety-concern` always escalates to a human plant supervisor
   regardless of confidence, as does low confidence generally;
   `:log-production-batch` is the only op eligible to auto-commit, and
   only at phase 3 when governor-clean.
4. **`steamgenmfg.phase`** -- Phase 0->3 staged rollout;
   `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-
   shipment` are permanently ABSENT from every phase's `:auto` set
   (structural fact, not a rollout milestone still to come); only
   `:log-production-batch` may auto-commit at phase 3.
5. **`steamgenmfg.advisor`** ("SteamGeneratorAdvisor") -- deterministic
   mock advisor (default) plus an `llm-advisor` backed by
   `langchain.model/ChatModel`; every output is censored downstream by
   the governor before anything touches the SSoT.
6. **`steamgenmfg.operation`** ("SteamGeneratorOperationActor") --
   langgraph-clj StateGraph, `intake -> advise -> govern -> decide ->
   commit | hold | request-approval`, `interrupt-before
   #{:request-approval}` for human-in-the-loop sign-off, invoked
   exclusively via `langgraph.graph/run*` (never `.invoke`, which is
   not cljs-portable).
7. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-2815`'s shape (`:test`/`:lint`/`:run`/`:dev`
   aliases, `itonami.blueprint/*` metadata, scope/design/testing README
   sections). `:itonami.blueprint/governor` is
   `:steam-generator-plant-operations-governor`, grep-verified UNIQUE
   fleet-wide (`gh search code "steam-generator-plant-operations-
   governor" --owner cloud-itonami`, zero hits before this repo was
   created); the `steamgenmfg` namespace prefix is likewise
   grep-verified UNIQUE fleet-wide (zero hits for `gh search code
   "steamgenmfg" --owner cloud-itonami` before this repo was created).

### What this actor does NOT do

Controlling fabrication/welding/pressure-vessel-assembly-line
equipment directly, and self-issuing an ASME BPVC/National Board
pressure-vessel safety-certification mark, remain exclusive to the
human plant supervisor and the accredited certification body
respectively, permanently, with no actor or human-approval override
path -- enforced structurally by the governor's closed op/effect
allowlists and two dedicated permanent-block checks
(`equipment-actuate-blocked-violations`,
`certification-authority-blocked-violations`), not just documented.
This actor also never logs a central-heating-hot-water-boiler product
type -- ISIC 2513's own class definition excludes that product, and
`steamgenmfg.registry/valid-product-types` structurally cannot express
it (a dedicated test, `central-heating-hot-water-boiler-product-type-
is-held`, exercises this directly).

## Verification

- `cloud-itonami-isic-2513`: `clojure -M:test` -- raw final line: `Ran
  79 tests containing 213 assertions.` / `0 failures, 0 errors.`
  (re-run green a second time from a brand-new fresh clone after
  push).
- `clojure -M:lint` -- 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation/approval on every write op, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded,
  equipment-actuate-blocked, certification-authority-blocked,
  already-scheduled, invalid-product-type (including a
  central-heating-hot-water-boiler attempt),
  invalid-hydrotest-pressure-bar, invalid-defect-rate) -- ran clean, no
  exceptions.
- All source under `src/`/`test/` is `.cljc`, no JVM-only interop; the
  actor graph is invoked exclusively via `langgraph.graph/run*`.
- Repo created fresh (`gh repo create` + push), initial commit
  `ed417d9d6213b72e0dcf69cc103db4dab809d6ac` on
  `cloud-itonami-isic-2513`'s `main` (no prior history), confirmed via
  `git rev-parse HEAD` on a brand-new fresh clone.
- `kotoba-lang/industry` registry `"2513"` entry updated in place
  (exact-text edit of the literal `{:id "2513" ...}` block only, no
  other entry's block touched -- verified via a byte-offset diff
  between the pre-edit and post-edit file, confirming the changed
  region was confined to `:name`/`:repo`/`:business-id`/`:maturity`
  inside that one block): `:repo`/`:business-id` corrected from the
  never-populated `gftdcojp/cloud-itonami-C2513` naming to
  `cloud-itonami/cloud-itonami-isic-2513` / `cloud-itonami-isic-2513`,
  `:name` de-truncated from `"Manufacture of steam generators, except
  central heating hot..."` to the full ISIC-08 class name
  `"Manufacture of steam generators, except central heating hot water
  boilers"`, `:maturity` `:spec` -> `:implemented`,
  `:required-technologies`/`:optional-technologies`/
  `:operating-states` left as already registered. Landed via a
  Contents API single-file PUT (sha-checked optimistic concurrency),
  commit `0416adb800616e45183b67cad8615e5c3d7dcb30`.
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion
  bumped, live-recomputed via `(kotoba.industry/maturity-summary)`
  against a freshly re-fetched `origin/main` tip immediately before
  each edit (never a reused/stale buffer) -- this file is an extremely
  hot, high-concurrency shared file across the fleet. Landed across
  four Contents API PUT attempts as the live count kept advancing from
  concurrent sibling promotions during this work: the promotion block
  itself plus an initial `365 -> 366` bump (commit
  `62e46a3db6260c64496a8522e4ab75342b869264`), a `366 -> 368` re-bump
  after two further concurrent sibling promotions landed (commit
  `d2febc347b024205aa9fc84351dc48224af3f0dd`), a third attempted
  `368 -> 369` re-bump that hit a 409 (a concurrent sibling promotion
  for `cloud-itonami-isic-2399` landed first and happened to bump the
  count to the same `369` this agent had already independently
  computed as live-correct, so no further PUT was needed once
  reconciled), and a final live re-fetch confirming `369` matched the
  truly-landed registry content with zero further drift.
- Full `kotoba-lang/industry` suite re-run green after all edits
  (fresh clone, plus a fresh `kotoba-lang/technology` sibling clone
  for `deps.edn` resolution): `Ran 15 tests containing 1032 assertions.`
  / `0 failures, 0 errors.`
- Final post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone of both `cloud-itonami-isic-2513` and
  `kotoba-lang/industry`, plus a fresh `kotoba-lang/technology`
  sibling): `clojure -M:test` re-run green in both repos; the
  registry's `"2513"` entry confirmed `:maturity :implemented` with
  the corrected `:repo`/`:business-id`/`:name`, and sibling entries
  `"2520"`/`"3040"`/`"2512"`/`"2399"` confirmed untouched.
  `(industry/maturity-summary)` on the final tip returned `{:total
  648, :spec 254, :blueprint 25, :implemented 369}`, matching the test
  file's final bumped assertion. `grep -c` for the UTF-8 replacement
  character against `resources/kotoba/industry/registry.edn` returned
  `0` (no file-wide mojibake).

## Consequences

(+) `cloud-itonami-isic-2513` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing
a `:spec` placeholder pointing at a repo that never existed.

(+) `kotoba-lang/industry` registry `"2513"` entry promoted to
`:maturity :implemented`, and its pre-existing truncated `:name` bug
is fixed as part of the same edit.

(+) `steamgenmfg.registry`'s closed product-type set gives this
fleet's product-taxonomy-exclusion pattern (established by
`cloud-itonami-isic-2513`'s own ISIC-class exclusion of central
heating hot water boilers) a concrete example: a closed enum that
structurally cannot express an excluded product, with a dedicated test
exercising the exclusion directly, reusable by any future ISIC class
whose own class definition carries an explicit product exclusion.

(+) `steamgenmfg.governor`'s ASME BPVC/National Board
certification-authority block extends the fleet's established
"this actor is never the certification body" pattern (UL/CSA/CE for
`cloud-itonami-isic-2815`) to the pressure-vessel-safety-certification
regime, a genuinely distinct accreditation body and standard from any
prior sibling in this fleet.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body
APIs) -- this is a standalone coordinator blueprint, matching every
prior sibling actor's own stated limitation.
