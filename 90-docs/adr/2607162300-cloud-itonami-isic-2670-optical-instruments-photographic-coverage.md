# ADR-2607162300: ISIC 2670 (optical instruments and photographic equipment) fresh scaffold → `:implemented`

- Status: Accepted (2026-07-16)
- Related: ADR-2607151300 (`cloud-itonami-isic-3250`, medical/dental
  instruments, closest structural sibling and primary mirror target),
  `cloud-itonami-isic-2652` (watches/clocks, secondary mirror target)

## Context

`kotoba-lang/industry`'s registry entry for ISIC 2670 ("Manufacture of
optical instruments and photographic equipment") sat as a
`:maturity :spec` placeholder pointing at a dead
`gftdcojp/cloud-itonami-C2670` URL with `business-id
"cloud-itonami-C2670"` — no repo, no actor, fresh scaffold territory
(confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-2670`
404 before this work began). This is part of the ongoing careful,
smaller-batch rollout of governed cloud-itonami actors following a
prior 18-agent haiku batch that had a 61% defect rate; this build
follows the stricter protocol (capable model + mandatory verification)
that 90+ consecutive prior agents on this fleet have completed
successfully.

The closest structural sibling is `cloud-itonami-isic-3250` (medical
and dental instruments): both are back-office plant-operations-
coordination actors for a precision-manufacturing plant with the same
four-op shape (`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination). `cloud-itonami-isic-2652`
(watches and clocks) was read as a secondary reference to confirm the
domain-specific "permanent authority block" pattern (FDA 510(k)/CE-
mark for 3250, COSC chronometer certification for 2652) generalizes
cleanly to a third regulatory regime.

## Decision

1. **`cloud-itonami-isic-2670`** — OpticalInstrAdvisor ⊣ Optical
   Instrument Plant Operations Governor, langgraph-clj StateGraph,
   append-only audit ledger. A plant OPERATIONS COORDINATION actor for
   optical-instrument-and-photographic-equipment manufacturing
   (lens-grinding/optics-assembly/testing lines producing lenses,
   binoculars, microscopes, telescopes, cameras, projectors) — not
   direct lens-grinding/assembly-line-equipment control authority and
   not laser-safety-classification-body regulatory authority.
2. Four propose-only ops: `:log-production-batch` (instrument-class/
   resolution-test/quantity/defect-rate data logging),
   `:schedule-maintenance` (grinding/assembly/test-bench-equipment
   maintenance scheduling proposal), `:flag-safety-concern`
   (materials-safety/precision-defect/laser-alignment-hazard concern,
   ALWAYS escalates), `:coordinate-shipment` (outbound product
   shipment coordination).
3. Thirteen concrete governor checks elaborate four HARD invariants:
   plant/batch record must be independently verified/registered before
   any action; `:effect` must be `:propose` only; any proposal
   touching lens-grinding/assembly-line-equipment control (including
   direct equipment actuation) is a hard, permanent block; closed
   op-allowlist enforced. A DOMAIN-SPECIFIC fifth permanent block
   (mirroring 3250's FDA 510(k)/CE-mark block and 2652's COSC
   chronometer-certification block, both elaborations of the same
   "equipment control / regulatory-authority self-issuance" HARD
   invariant category) guards against self-issuing an IEC 60825-1
   laser safety class certification — an authority reserved to the
   accredited testing laboratory, never this actor.
4. Production-batch domain fields: `:instrument-class` (closed set
   `#{:optical-lens :binocular :microscope :telescope :camera
   :projector}`, excluding spectacle lenses and electron/proton
   microscopes which fall outside ISIC 2670 by definition),
   `:resolution-test-line-pairs-per-mm` (USAF-1951-style resolution
   reading, plausibility-checked 0.1–1200.0 lp/mm against the
   visible-light diffraction limit), `:defect-rate-percent` (0–100).
5. No pre-existing `kotoba-lang/opticalmfg`-style capability library
   to wrap (verified via GitHub repo/code search) — self-contained
   domain logic in `opticalmfg.registry`, re-verified independently by
   `opticalmfg.governor`, the same "ground truth, not self-report"
   discipline every sibling actor's own registry establishes.
6. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2670` URL and `business-id
   "cloud-itonami-isic-2670"`, replacing the dead
   `gftdcojp/cloud-itonami-C2670` placeholder.
7. `:itonami.blueprint/governor` keyword
   `:optical-instrument-plant-operations-governor` is grep-verified
   UNIQUE fleet-wide (`gh search code
   "optical-instrument-plant-operations-governor" --owner
   cloud-itonami`, zero hits before this repo was created).

## Consequences

(+) ISIC 2670 now has a documented, governed, auditable back-office
coordination layer for optical-instrument-and-photographic-equipment
plant operations, mirroring the fleet's established governed-actor
pattern.

(+) The domain-specific laser-safety-classification-authority block
demonstrates the "permanent authority block" pattern (established by
3250's FDA/CE block and 2652's COSC block) generalizes to a third
distinct regulatory regime without structural changes to the governor/
phase/store shape.

(+) All source is portable `.cljc` (no JVM-only interop); reuses the
`langgraph`/`langchain` local/root deps.edn shape common to every
sibling actor.

(−) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and laser safety
classification issuance remain human-/institution-controlled via
external channels.

(−) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, testing-laboratory APIs)
— a standalone coordinator blueprint, matching every prior
manufacturing sibling.

## Verification

- `cloud-itonami-isic-2670`, fresh clone, `clojure -M:test`:
  `Ran 77 tests containing 211 assertions.` `0 failures, 0 errors.`
- `clojure -M:lint` clean (0 errors, 0 warnings).
- `clojure -M:dev:run` demo narrative exercises every HARD-hold
  scenario directly (`:not-propose-effect`, `:unknown-op`,
  `:equipment-not-verified`, `:batch-not-verified`,
  `:shipment-quantity-exceeded`, `:actuate-equipment-blocked`,
  `:laser-classification-authority-blocked`, `:already-scheduled`,
  `:invalid-instrument-class`,
  `:invalid-resolution-test-line-pairs-per-mm`,
  `:invalid-defect-rate`), no exceptions.
- Industry: `"2670"` `:spec` → `:implemented`, `:repo` and
  `:business-id` updated to `cloud-itonami/cloud-itonami-isic-2670`.
- GitHub public repo under `cloud-itonami/`, landed on `main`.
- Post-merge re-verification (fresh re-clone of both
  `cloud-itonami-isic-2670` and `kotoba-lang/industry`): see this
  ADR's companion superproject commit / PR for the re-run
  `clojure -M:test` raw output and `industry_test.clj` full-suite
  output confirming the entry survived and the `:implemented` count
  assertion matches `kotoba.industry/maturity-summary`.
