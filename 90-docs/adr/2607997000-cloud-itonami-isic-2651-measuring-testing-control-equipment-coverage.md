# ADR-2607997000: ISIC 2651 (measuring, testing, navigating and control equipment) fresh scaffold → `:implemented`

- Status: Accepted (2026-07-15)
- Related: ADR-2607994500 (`cloud-itonami-isic-2651-instrumentation-coverage`,
  the decision ADR that selected this ISIC class and scoped the
  scaffold + registry work to a separate implementation session — this
  ADR records that implementation), ADR-2607162300
  (`cloud-itonami-isic-2670`, optical instruments/photographic
  equipment, closest structural sibling and primary mirror target),
  ADR-2607151300 (`cloud-itonami-isic-3250`, medical/dental
  instruments, secondary mirror target)

## Context

`kotoba-lang/industry`'s registry entry for ISIC 2651 ("Manufacture of
measuring, testing, navigating and control equipment") sat as a
`:maturity :spec` placeholder pointing at a dead
`gftdcojp/cloud-itonami-C2651` URL with `business-id
"cloud-itonami-C2651"` — no repo, no actor, fresh scaffold territory
(confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-2651`
404 before this work began). ADR-2607994500 already recorded the
decision to build this class (selected as the remaining
smartphone/electronics-adjacent measuring/testing/inspection-equipment
manufacturing gap after ISIC 2670 was promoted to `:implemented`) and
explicitly scoped the actual scaffold + registry update to a separate
implementation session — this ADR is that implementation. This is part
of the ongoing careful, smaller-batch rollout of governed cloud-itonami
actors following a prior 18-agent haiku batch that had a 61% defect
rate; this build follows the stricter protocol (capable model +
mandatory verification) that 96+ consecutive prior agents on this
fleet have completed successfully.

The closest structural sibling is `cloud-itonami-isic-2670` (optical
instruments and photographic equipment): both are back-office plant-
operations-coordination actors for a precision-manufacturing plant
with the same four-op shape (`:log-production-batch`/
`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`)
and the same two-entity verified/registered gate structure (equipment
for maintenance scheduling, batch for shipment coordination).
`cloud-itonami-isic-3250` (medical/dental instruments) was read as a
secondary reference to confirm the domain-specific "permanent
authority block" pattern (IEC 60825-1 laser safety classification for
2670, FDA 510(k)/CE-mark for 3250) generalizes cleanly to a fourth
regulatory regime (NIST-traceable calibration certification, ISO/IEC
17025 accredited calibration laboratories).

## Decision

1. **`cloud-itonami-isic-2651`** — MeasCtrlAdvisor ⊣ Measuring Control
   Equipment Plant Operations Governor, langgraph-clj StateGraph,
   append-only audit ledger. A plant OPERATIONS COORDINATION actor for
   measuring/testing/navigating/control-equipment manufacturing
   (calibration-bench/assembly-line/test-bench lines producing
   process-control instruments, laboratory analytical instruments,
   test-and-inspection equipment, navigational instruments, meters,
   radiation-detection instruments, surveying instruments,
   meteorological instruments) — not direct calibration/assembly-line-
   equipment control authority and not a metrology-certification
   authority.
2. Four propose-only ops: `:log-production-batch` (instrument-class/
   calibration-accuracy/quantity/defect-rate data logging),
   `:schedule-maintenance` (calibration/assembly/test-bench-equipment
   maintenance scheduling proposal), `:flag-safety-concern`
   (calibration-drift/precision-defect/electrical-safety concern,
   ALWAYS escalates), `:coordinate-shipment` (outbound product
   shipment coordination).
3. Thirteen concrete governor checks elaborate four HARD invariants:
   plant/batch record must be independently verified/registered before
   any action; `:effect` must be `:propose` only; any proposal
   touching calibration/assembly-line-equipment control (including
   direct equipment actuation) is a hard, permanent block; closed
   op-allowlist enforced. A DOMAIN-SPECIFIC fifth permanent block
   (mirroring 2670's IEC 60825-1 laser-safety-classification block and
   3250's FDA 510(k)/CE-mark block, both elaborations of the same
   "equipment control / regulatory-authority self-issuance" HARD
   invariant category) guards against self-issuing a NIST-traceable
   calibration certificate — an authority reserved to the accredited
   calibration laboratory, never this actor.
4. Production-batch domain fields: `:instrument-class` (closed set
   `#{:process-control-instrument :laboratory-analytical-instrument
   :test-and-inspection-equipment :navigational-instrument :meter
   :radiation-detection-instrument :surveying-instrument
   :meteorological-instrument}`, excluding optical instruments,
   watches/clocks and medical/dental instruments which fall outside
   ISIC 2651 by definition), `:calibration-accuracy-ppm` (deviation
   from a certified reference standard, in parts per million,
   plausibility-checked 0.0–100000.0 ppm against real-world
   calibration-tolerance classes), `:defect-rate-percent` (0–100).
5. No pre-existing `kotoba-lang/measctrlmfg`-style capability library
   to wrap (verified via GitHub repo/code search) — self-contained
   domain logic in `measctrlmfg.registry`, re-verified independently
   by `measctrlmfg.governor`, the same "ground truth, not self-report"
   discipline every sibling actor's own registry establishes.
6. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2651` URL and `business-id
   "cloud-itonami-isic-2651"`, replacing the dead
   `gftdcojp/cloud-itonami-C2651` placeholder.
7. `:itonami.blueprint/governor` keyword
   `:measuring-control-equipment-plant-operations-governor` is
   grep-verified UNIQUE fleet-wide (`gh search code
   "measuring-control-equipment-plant-operations-governor" --owner
   cloud-itonami`, zero hits before this repo was created).

## Consequences

(+) ISIC 2651 now has a documented, governed, auditable back-office
coordination layer for measuring/testing/navigating/control-equipment
plant operations, mirroring the fleet's established governed-actor
pattern.

(+) The domain-specific metrology-certification-authority block
demonstrates the "permanent authority block" pattern (established by
2670's laser-safety-classification block and 3250's FDA/CE block)
generalizes to a fourth distinct regulatory regime without structural
changes to the governor/phase/store shape.

(+) All source is portable `.cljc` (no JVM-only interop); reuses the
`langgraph`/`langchain` local/root deps.edn shape common to every
sibling actor.

(−) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and metrology
certification issuance remain human-/institution-controlled via
external channels.

(−) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, calibration-laboratory
APIs) — a standalone coordinator blueprint, matching every prior
manufacturing sibling.

## Verification

- `cloud-itonami-isic-2651`, fresh clone, `clojure -M:test`:
  `Ran 77 tests containing 212 assertions.` `0 failures, 0 errors.`
  (verified twice: once immediately pre-push from the build directory,
  once from a fully independent fresh clone post-push.)
- `clojure -M:lint` clean (0 errors, 0 warnings).
- `clojure -M:dev:run` demo narrative exercises every HARD-hold
  scenario directly (`:not-propose-effect`, `:unknown-op`,
  `:equipment-not-verified`, `:batch-not-verified`,
  `:shipment-quantity-exceeded`, `:actuate-equipment-blocked`,
  `:metrology-certification-authority-blocked`, `:already-scheduled`,
  `:invalid-instrument-class`, `:invalid-calibration-accuracy-ppm`,
  `:invalid-defect-rate`), no exceptions.
- Industry: `"2651"` `:spec` → `:implemented`, `:repo` and
  `:business-id` updated to `cloud-itonami/cloud-itonami-isic-2651`.
- GitHub public repo under `cloud-itonami/`, landed on `main`.
- Post-merge re-verification (fresh re-clone of both
  `cloud-itonami-isic-2651` and `kotoba-lang/industry`): see this
  ADR's companion superproject commit / PR for the re-run
  `clojure -M:test` raw output and `industry_test.clj` full-suite
  output confirming the entry survived and the `:implemented` count
  assertion matches `kotoba.industry/maturity-summary`.
