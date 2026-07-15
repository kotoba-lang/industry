# ADR-2607995500: cloud-itonami ISIC 2790 (Manufacture of other electrical equipment) coverage

## Status

Accepted. `cloud-itonami/cloud-itonami-isic-2790` created as a fresh
scaffold (no pre-existing repo — confirmed via `gh api
repos/cloud-itonami/cloud-itonami-isic-2790` 404 before this ADR),
implementing the `OtherElecEquipAdvisor ⊣ Other Electrical Equipment
Plant Operations Governor` actor per the same langgraph-clj
StateGraph + independent Governor + append-only audit ledger pattern
established across the cloud-itonami fleet. This is part of the
ongoing careful, smaller-batch rollout (capable model + mandatory
verification) that has followed a prior 18-agent haiku batch with a
61% defect rate.

## Context

`kotoba-lang/industry`'s registry entry `{:id "2790" ...}` carries
`:name "Manufacture of other electrical equipment"` — confirmed
verbatim via the GitHub Contents API (not the CDN-cached
`raw.githubusercontent.com`) against the live registry file
(`resources/kotoba/industry/registry.edn`, blob sha
`f1433aa0e56f39441fe6135b9559bf592f74eaba`) before any work began, per
this fleet's mandatory ID/name-match verification step. ISIC 2790 is
the residual class within ISIC Rev.4/5 division 27 (Manufacture of
electrical equipment): "other electrical equipment not elsewhere
classified" — electric welding and soldering equipment, non-electric
domestic-appliance parts, and resistors/capacitors n.e.c.

Prior to this work the registry entry was `:maturity :spec` with a
legacy placeholder `:repo "https://github.com/gftdcojp/cloud-itonami-C2790"`
and `:business-id "cloud-itonami-C2790"` (never a real implementation —
confirmed 404 on the real target `cloud-itonami/cloud-itonami-isic-2790`
before scaffolding).

The closest domain analog and mirrored reference is
`cloud-itonami/cloud-itonami-isic-2710` (Manufacture of electric
motors, generators, transformers and electricity distribution and
control apparatus, 77 tests / 210 assertions), independently
re-verified working before use as a template; `cloud-itonami-isic-2740`
(Electric lighting equipment) was also confirmed as a second reference
point with the same 77/210 shape. Both establish the four-op
plant-operations-coordination pattern
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/
`:coordinate-shipment`) this build follows.

## Decision

### Decision 1: Plant operations coordination, not assembly/test-line control authority

`cloud-itonami-isic-2790` is a back-office **plant operations
coordination** actor for ISIC 2790's residual class of other
electrical equipment (electric welding/soldering equipment,
non-electric domestic-appliance parts, resistors/capacitors n.e.c.).
It never controls assembly or test-bench equipment directly, and it is
never an electrical-safety certification authority (e.g. UL/CE/IEC
compliance marks) — see the repo's own README `What this actor does
NOT do` and `docs/adr/0001-architecture.md` for the full elaboration.

### Decision 2: Same four-op shape as `cloud-itonami-isic-2710`, adapted product/test vocabulary

Four ops, all `:effect :propose` only:
- `:log-production-batch` — assembly/test batch, output-quality/test-result data logging
- `:schedule-maintenance` — assembly/test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface an electrical-safety/UL-CE-compliance concern (ALWAYS escalates)
- `:coordinate-shipment` — outbound product shipment coordination

Product-type closed set: `#{:welding-equipment :soldering-equipment
:appliance-part :resistor :capacitor}`. In place of 2710's high-voltage
`:dielectric-test-kv` (hipot withstand test, grounded in IEC 60076-3),
this vertical validates `:insulation-resistance-mohm` (a routine
megohmmeter insulation-resistance reading, plausibility-checked
0–200,000 MΩ against the working range of standard production
insulation testers) — reflecting ISIC 2790's residual-class products
being lower-power/lower-voltage than 2710's motors/generators/
transformers.

### Decision 3: HARD invariants (no override), mirroring the fleet pattern

1. Plant/batch record must be independently verified/registered
   (`:verified?` AND `:registered?`) before any action (equipment
   before maintenance scheduling, batch before shipment coordination).
2. The request's own `:effect` must be `:propose` only.
3. The closed op-allowlist is enforced (`:log-production-batch`/
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` only).
4. Any proposal touching assembly/test-bench-equipment control (a
   proposal `:effect` outside the four propose-shaped effects, or
   `:actuate-equipment? true`) or a safety-certification-authority
   decision (`:issue-certification? true`, any op) is a HARD,
   PERMANENT, unconditional block — no phase, no human approval can
   ever override this.

ESCALATE (always human sign-off, human may approve): `:flag-safety-
concern` always escalates regardless of confidence; low-confidence
proposals also escalate.

Twelve concrete governor checks elaborate these four invariants (see
`src/otherelecmfg/governor.cljc` docstring for the full enumeration),
the same shape `cloud-itonami-isic-2710`'s twelve checks establish.

### Decision 4: Self-contained domain logic (no external capability library)

Like `cloud-itonami-isic-2710`, this vertical has NO pre-existing
`kotoba-lang/otherelecmfg`-style capability library to wrap (verified:
no such repo exists). Domain logic (equipment/batch verification,
shipment-quantity recompute, product-type validation, insulation-
resistance plausibility validation, defect-rate plausibility
validation) lives as pure functions in `otherelecmfg.registry`,
re-verified independently by `otherelecmfg.governor`.

### Decision 5: All source `.cljc`, cljs-first / no JVM interop

Per this workspace's runtime-priority rules (`kotoba wasm runtime` >
`clojurewasm` > `ClojureScript` > `nbb`, JVM/bb demoted), all source is
portable `.cljc` with no JVM-only interop, matching every sibling
actor in this fleet. The actor graph is invoked exclusively via
`langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).

## Consequences

(+) ISIC 2790's residual-class other-electrical-equipment plant
operations back-office now has a documented, governed, auditable
coordination layer.

(+) The "coordination, not control" boundary and the "no self-issued
certification" boundary are both explicit, testable, and permanently
enforced code paths, not policy documents alone.

(+) Fleet coverage of ISIC division 27 (Manufacture of electrical
equipment) extends: `cloud-itonami-isic-2790` joins
`cloud-itonami-isic-2710`/`cloud-itonami-isic-2740` as implemented,
adjacent siblings.

(-) Still a simulation/proposal layer — equipment actuation and
certification issuance remain human-/institution-controlled via
external channels, same limitation every sibling actor in this fleet
shares.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs).

## Verification

- `cloud-itonami-isic-2790` fresh scaffold: `clojure -M:test` green,
  **77 tests containing 210 assertions, 0 failures, 0 errors** — run
  both in the build directory before push and independently
  re-verified from a brand-new `git clone` after push (see the repo's
  own commit history and this ADR's companion commit for the exact
  raw output lines).
- `clojure -M:lint` clean (0 errors, 0 warnings).
- `clojure -M:dev:run` demo narrative exercises the happy path for all
  four ops plus every HARD-hold scenario directly (not-propose-effect,
  unknown-op, equipment-not-verified, batch-not-verified, shipment-
  quantity-exceeded, equipment-actuate-blocked, certification-
  authority-blocked, already-scheduled, invalid-product-type, invalid-
  insulation-resistance-mohm, invalid-defect-rate).
- Governor keyword `:other-electrical-equipment-plant-operations-
  governor` grep-verified unique fleet-wide before use (`gh search
  code "other-electrical-equipment-plant-operations-governor" --owner
  cloud-itonami` — zero hits before this repo existed).
- `kotoba-lang/industry` registry entry `{:id "2790" ...}` promoted
  `:spec` -> `:implemented`, `:repo`/`:business-id` corrected from the
  legacy `gftdcojp/cloud-itonami-C2790` placeholder to the real
  `cloud-itonami/cloud-itonami-isic-2790`, edited as an exact in-place
  text edit of only that entry's block (never a wholesale
  parse/reserialize of the registry file). `industry_test.clj`'s
  `:implemented` count assertion recomputed from the live file via
  `kotoba.industry/maturity-summary` (not `grep -c`) and bumped
  accordingly; full suite re-run green before and after the bump.
