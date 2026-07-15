# ADR-2608300000: cloud-itonami ISIC 2731 (Manufacture of fibre optic cables) coverage

## Status

Accepted. `cloud-itonami-isic-2731` fresh-scaffolded, tested, and
pushed to `main` at `github.com/cloud-itonami/cloud-itonami-isic-2731`
(commit `61443e1e4cd94f69b675b2120fa252a4237a2470`). Registry entry
`{:id "2731" ...}` in `kotoba-lang/industry` promoted from `:spec` to
`:implemented`.

## Context

This ADR is part of an ongoing, carefully sequenced, smaller-batch
rollout of the cloud-itonami fleet (every ISIC industry class as an
autonomous "actor": LLM/advisor behind an independent Governor,
langgraph-clj-style state machine, append-only audit ledger), after an
earlier 18-agent haiku batch had a 61% defect rate. 120+ consecutive
agents on the current stricter protocol (capable model + mandatory
verification) had succeeded before this one.

Before any work began, the assigned ISIC class was independently
verified against the live `kotoba-lang/industry` registry (fetched via
the GitHub Contents API, never the `raw.githubusercontent.com` CDN, to
avoid stale-cache risk): `{:id "2731" :name "Manufacture of fibre
optic cables" :maturity :spec ...}`, distinct from sibling `2732`
("Manufacture of other electronic and electric wires and cables") and
`2733` ("Manufacture of wiring devices"), both already `:implemented`
in this fleet. No mismatch was found; the `:name` field was not
truncated (no trailing `"..."`), so no seed-data repair was needed on
this entry beyond the normal maturity/repo/business-id fields.

No `github.com/cloud-itonami/cloud-itonami-isic-2731` repository
existed before this work (confirmed 404 via `gh api
repos/cloud-itonami/cloud-itonami-isic-2731`).

## Decision

Scaffolded `cloud-itonami-isic-2731` mirroring the verified-working
`cloud-itonami-isic-2733` (wiring devices) reference repo's shape and
governed-actor architecture (full module set: `advisor.cljc`/
`governor.cljc`/`operation.cljc`/`phase.cljc`/`registry.cljc`/
`store.cljc`/`sim.cljc`, plus `deps.edn`/`blueprint.edn`/LICENSE
(AGPL-3.0-or-later)/README/GOVERNANCE.md/CODE_OF_CONDUCT.md/
CONTRIBUTING.md/SECURITY.md/`docs/adr/0001-architecture.md`), adapted
to ISIC 2731's own domain: fibre drawing (pulling glass fibre from a
heated preform on a draw tower, with in-line primary/secondary
UV-cured acrylate coating) followed by cabling (stranding coated
fibres into buffer tubes or ribbons and jacketing/extruding the
finished cable).

The `fibreopticmfg` namespace prefix and the
`:fibre-optic-cable-plant-operations-governor` blueprint governor
keyword were both grep-verified UNIQUE fleet-wide (`gh search code ...
--owner cloud-itonami`, zero hits) before use.

**FibreOpticCableAdvisor ⊣ Fibre Optic Cable Plant Operations
Governor**, a plant OPERATIONS COORDINATION actor — NOT direct
drawing/cabling-line-equipment control authority. Closed four-op
allowlist, all `:effect :propose` only:
- `:log-production-batch` — drawing/coating/cabling batch,
  output-quality (attenuation-dB/km OTDR/cutback test) data logging
- `:schedule-maintenance` — drawing/cabling-line-equipment maintenance
  scheduling proposal
- `:flag-safety-concern` — surface an equipment-safety/quality-defect
  concern; ALWAYS escalates to a human plant supervisor
- `:coordinate-shipment` — outbound cable-reel shipment coordination

HARD invariants (always `:hold`, no override), elaborated into twelve
concrete governor checks:
1. Plant/batch record (equipment for maintenance, batch for shipment)
   must be independently verified/registered before any action is
   taken against it — never trust the advisor's own report.
2. The request's own `:effect` must be `:propose` only.
3. Any proposal whose own `:effect` would touch drawing/coating/
   cabling-line-equipment control (including a direct
   `:actuate-equipment? true` actuation attempt) is a HARD, PERMANENT,
   unconditional block — no phase, no human override.
4. The op allowlist is closed to the four ops above.

A fifth HARD, PERMANENT, unconditional block (mirroring
`cloud-itonami-isic-2733`'s own certification-authority block, adapted
to this domain's own compliance regime — Telcordia GR-20, ITU-T G.65x,
UL/NFPA fire-rating marks): any proposal declaring
`:issue-certification? true` is rejected; this actor never self-issues
a fibre-optic-cable compliance mark.

ESCALATE (always human sign-off, human may approve): `:flag-safety-
concern` always escalates regardless of confidence; low-confidence
proposals also escalate. `:schedule-maintenance` and
`:coordinate-shipment` are deliberately absent from every rollout
phase's `:auto` set (including phase 3), so they always require human
approval once the governor clears them — only `:log-production-batch`
(no physical/financial risk) may auto-commit, and only at phase 3 when
governor-clean.

This vertical has no pre-existing `kotoba-lang/fibreopticmfg`-style
capability library to wrap (verified: no such repo exists), so domain
logic (equipment/batch verification, shipment-quantity recompute,
product-type validation, attenuation-dB/km plausibility validation,
defect-rate plausibility validation) is self-contained pure functions
in `fibreopticmfg.registry`, independently re-verified by
`fibreopticmfg.governor` — see the child repo's own
`docs/adr/0001-architecture.md` for full design rationale and
domain-vocabulary derivation (attenuation-dB/km bound grounded in IEC
60793-1-40; product-type closed set grounded in Telcordia GR-20 / ITU-T
L.10 cable-construction categories).

## Consequences

(+) ISIC 2731 (fibre optic cable manufacturing) now has a documented,
governed, auditable plant-operations coordination layer in the
cloud-itonami fleet, alongside its already-implemented ISIC-27xx
siblings 2732 and 2733.

(+) Registry entry corrected from placeholder naming
(`gftdcojp/cloud-itonami-C2731` / business-id `cloud-itonami-C2731`)
to the fleet-standard `cloud-itonami/cloud-itonami-isic-2731` repo
naming and `cloud-itonami-isic-2731` business-id, matching sibling
2732/2733 conventions.

(-) Still a simulation/proposal layer; no integration with real
plant-management systems (equipment telemetry, batch tracking,
freight dispatch, certification-body APIs).

## Verification

Fresh-clone `clojure -M:test` (raw output, unedited):

```
Running tests in #{"test"}

Testing fibreopticmfg.governor-contract-test

Testing fibreopticmfg.operation-test

Testing fibreopticmfg.phase-test

Testing fibreopticmfg.registry-test

Testing fibreopticmfg.store-contract-test

Ran 77 tests containing 209 assertions.
0 failures, 0 errors.
```

`clojure -M:lint` (clj-kondo): `linting took 1050ms, errors: 0,
warnings: 0`. `clojure -M:dev:run` demo exercises the happy path
(auto-commit, two escalate/approve flows) and all eleven HARD-hold
scenarios directly (not-propose-effect, unknown-op,
equipment-not-verified, batch-not-verified, shipment-quantity-
exceeded, equipment-actuate-blocked, certification-authority-blocked,
already-scheduled, invalid-product-type, invalid-attenuation-db-km,
invalid-defect-rate) without exception.

Re-verified after a second fresh clone into a new temp dir (post
registry-merge), see the `kotoba-lang/industry` registry entry and
this repo's own commit history for the exact re-verified test output.
All source is `.cljc` (no JVM-only interop); the actor graph is
invoked exclusively via `langgraph.graph/run*`.
