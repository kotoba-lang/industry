# ADR-2609400000: `cloud-itonami-isic-2823` (Manufacture of machinery for metallurgy) promoted to `:implemented`

- Status: Accepted (2026-07-16)
- Related: ADR-2607105200 (`3011` shipbuilding, classic heavy industry),
  cloud-itonami-isic-2812 (fluid power equipment), cloud-itonami-isic-2815
  (ovens/furnaces), cloud-itonami-isic-2819 (other general-purpose
  machinery), fleet governed-actor pattern ADRs, langgraph ADR-0001

## Context

`kotoba-lang/industry` registry entry `"2823"` sat at `:maturity :spec`
with `:name "Manufacture of machinery for metallurgy"` and a dead
`gftdcojp/cloud-itonami-C2823` placeholder URL — verified via a fresh
git-blob fetch of `resources/kotoba/industry/registry.edn` before any
work began, per this fleet's ID/name-mismatch caution. Both the stale
placeholder (`gftdcojp/cloud-itonami-C2823`) and the real target
(`cloud-itonami/cloud-itonami-isic-2823`) were independently confirmed
404 via `gh api` before scaffolding — a genuinely fresh scaffold, no
prior repository.

ISIC 2823 covers manufacture of machinery for metallurgy: rolling
mills, casting machinery, and metal-processing plant equipment,
distinct from siblings 2812 (fluid power equipment), 2815 (ovens,
furnaces and furnace burners), and 2819 (other general-purpose
machinery), all already `:implemented` in this fleet.

## Decision

1. Create public OSS repo `cloud-itonami/cloud-itonami-isic-2823` with
   **MetalMachAdvisor ⊣ Metallurgy Machinery Plant Operations
   Governor** (`metalmachmfg.*` namespaces), mirroring
   `cloud-itonami-isic-2819`'s [Manufacture of other general-purpose
   machinery] verified module shape module-for-module
   (`metalmachmfg.*` in place of `weighpkgmfg.*`) — this build picks
   ONE concrete illustrative product line, documented plainly in the
   README: rolling-mill and casting-machinery manufacturing
   (rolling-mill stands, continuous casters, die-casting machines,
   extrusion presses, forging presses, wire-drawing machines).
   `:fabrication-line`/`:assembly-test-bench` equipment kinds replace
   2819's assembly-line/calibration-test-bench, and a
   `:load-test-tonnes` proof-load plausibility check (0-100000t,
   informed by real heavy-metallurgy-machinery test practice — rolling
   -mill separating forces and forging/extrusion-press ratings span
   from a few hundred to tens of thousands of tonnes-force, and even
   the largest publicly documented forging presses in the world
   sit well under this ceiling) replaces 2819's
   `:calibration-accuracy-percent`. A permanent machinery-safety
   certification-authority block (`:issue-certification? true`, e.g. CE
   under EU Machinery Directive 2006/42/EC / ANSI B11.19) mirrors
   2819's own legal-metrology/machinery-safety certification-authority
   block. `:flag-safety-concern` (equipment-safety/structural-
   integrity/quality-defect concern) always escalates regardless of
   confidence, matching 2819's own safety-concern-escalation
   invariant. The proposal-effect allowlist plus a permanent
   equipment-actuate block (`:actuate-equipment? true`) structurally
   prevent any direct fabrication/assembly-line-equipment control,
   with no human-approval override path. Twelve concrete governor
   checks elaborate the same four HARD invariants as 2819 (propose-
   only effect, closed op allowlist, closed proposal-effect allowlist,
   permanent equipment-actuate block, permanent certification-
   authority block, independent equipment/batch verification/
   registration before any action, independent shipment-quantity
   recompute, double-schedule guard, product-type/load-test/defect-
   rate validation). `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are NEVER in any phase's `:auto` set, only
   `:log-production-batch` may auto-commit at phase 3 when clean.
   Fully portable `.cljc` with no JVM-only interop in `src/`.
   `:itonami.blueprint/governor` keyword
   `:metallurgy-machinery-plant-operations-governor` and the
   `metalmachmfg` namespace were both grep-verified UNIQUE fleet-wide
   (`gh search code ... --owner cloud-itonami`, zero hits) before this
   repo was created.
2. Promote registry entry `"2823"` from `:spec` → `:implemented`,
   replace the dead `gftdcojp/cloud-itonami-C2823` placeholder URL
   with the real repo, correct `:business-id` to
   `cloud-itonami-isic-2823`.
3. Business model, operator guide, and child ADR-0001 live in the
   child repo (`docs/adr/0001-architecture.md`); this superproject ADR
   records fleet-level bookkeeping.

## Consequences

(+) Metallurgy-machinery plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) Industry registry honesty: no more dead C2823 placeholder for
2823.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- Child: `clojure -M:test` (70 tests / 201 assertions green,
  independently re-verified from a fresh clone)
- Child: `clojure -M:lint` clean (0 errors, 0 warnings)
- Child: `clojure -M:dev:run` demo narrative exercises proposal
  submission, escalation, and every HARD-hold scenario directly
- Industry: `(kotoba.industry/maturity "2823")` → `:implemented`,
  `(kotoba.industry/maturity-summary)` recomputed live (not assumed)
  before bumping the fleet-wide `:implemented` count assertion
- GitHub: https://github.com/cloud-itonami/cloud-itonami-isic-2823
