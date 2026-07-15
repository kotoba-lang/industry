# ADR-2607999700: cloud-itonami ISIC 2733 (Manufacture of wiring devices) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607995500 (cloud-itonami-isic-2790 Manufacture of other electrical equipment coverage, closest domain analog and primary mirrored reference)

## Context

`kotoba-lang/industry`'s `registry.edn` carries `{:id "2733" :name
"Manufacture of wiring devices" ...}` at `:maturity :spec` with a
placeholder `:repo`/`:business-id` (`gftdcojp/cloud-itonami-C2733`).
This ADR promotes ISIC 2733 to `:implemented` following the same
verified fresh-scaffold protocol used across the cloud-itonami fleet
(mirroring `cloud-itonami-isic-2790` and `cloud-itonami-isic-2740`
most closely).

ISIC 2733 is a distinct class from sibling ISIC 2732 ("Manufacture of
other electronic and electric wires and cables", already
`:implemented` in the registry): 2733 covers **wiring devices**
(switches, socket-outlets/receptacles, plugs, junction boxes) --
fixed devices that terminate, switch or distribute a circuit -- while
2732 covers wire/cable products. The two verticals were confirmed
distinct by direct read of both live registry entries before any code
was written.

No pre-existing `kotoba-lang/wiringdevmfg`-style capability library
exists for this vertical (verified: no such repo). This build follows
Decision 1 of prior `cloud-itonami-isic-27xx` siblings: self-contained
pure-function domain logic in the actor's own `registry` namespace,
re-verified independently by the governor.

## Decision

Scaffolded a new standalone repo, `cloud-itonami/cloud-itonami-isic-2733`
(fresh GitHub repo, confirmed non-existent via `gh api` 404 before
creation), implementing a **plant operations coordination** actor for
ISIC 2733 wiring-device manufacturing:

- **WiringDeviceAdvisor** (sealed intelligence node, mock/deterministic
  by default, swappable for a real `langchain.model/ChatModel`):
  proposes only, never commits.
- **Wiring Devices Plant Operations Governor** (independent compliance
  layer, `wiringdevmfg.governor`): re-derives ground truth from
  `wiringdevmfg.registry`'s pure functions and `wiringdevmfg.store`'s
  SSoT, never trusting the advisor's self-report. Twelve concrete
  checks elaborate four HARD invariants (see below).
- **`wiringdevmfg.phase`**: Phase 0->3 staged rollout;
  `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`
  are permanently absent from every phase's `:auto` set; only
  `:log-production-batch` may auto-commit at phase 3 when
  governor-clean.
- **`wiringdevmfg.store`**: single `MemStore` backend (atom of EDN)
  behind a `Store` protocol, append-only audit ledger.
- **`wiringdevmfg.operation`**: the langgraph-clj StateGraph wiring
  advise -> govern -> decide -> commit|hold|approval, one graph run
  per coordination request.

### Domain shape

Four closed, `:effect :propose`-only ops:
- `:log-production-batch` -- molding/assembly/test batch,
  output-quality/test-result data logging
- `:schedule-maintenance` -- molding/assembly/test-line-equipment
  maintenance scheduling proposal
- `:flag-safety-concern` -- surface an electrical-safety/UL-CE-
  compliance concern, ALWAYS escalates
- `:coordinate-shipment` -- outbound product shipment coordination

Product-type closed set: `#{:switch :socket-outlet :plug
:junction-box}`. QC metric: `:contact-resistance-milliohm` (0-20,000
mΩ, grounded in standard production micro-ohmmeter range per IEC
60669-1 / IEC 60884-1 acceptance testing -- distinct from sibling
2790's `:insulation-resistance-mohm` and sibling 2710's
`:dielectric-test-kv`, reflecting this domain's focus on switching/
terminal contact resistance rather than bulk winding insulation or
high-voltage withstand) plus `:defect-rate-percent` (0-100%, shared
shape with every sibling).

### HARD invariants (always `:hold`, no override)

1. Plant/batch record (equipment for maintenance, batch for shipment)
   must be independently verified/registered before any action is
   taken against it, and a shipment's quantity must independently
   recompute within the batch's own logged production quantity.
2. The request's own `:effect` must be `:propose` only.
3. The proposal's own `:effect` must be one of the four propose-shaped
   effects -- no direct molding/assembly/test-line-equipment control
   (PERMANENT).
4. Any proposal (any op) declaring `:issue-certification? true` is a
   PERMANENT, unconditional block -- this actor never self-issues an
   electrical-safety compliance mark.

Elaborated into twelve concrete `wiringdevmfg.governor` checks:
request-level propose-only, closed op allowlist, closed proposal-
effect allowlist, permanent equipment-actuate block, permanent
certification-authority block, independent equipment
verification/registration, double-schedule guard, independent batch
verification/registration, independent shipment-quantity recompute,
product-type validation, contact-resistance plausibility validation,
defect-rate plausibility validation.

### ESCALATE (always human sign-off)

`:flag-safety-concern` always escalates regardless of confidence; low
confidence proposals escalate.

## Verification

Fresh scaffold, built and tested from a uniquely-named scratch
directory (`/tmp/scratch-2733`, outside any shared checkout), pushed
directly to a brand-new `cloud-itonami/cloud-itonami-isic-2733` repo.

```
Ran 77 tests containing 209 assertions.
0 failures, 0 errors.
```

(`clojure -M:test`, fresh clone re-verified post-merge -- see the
`kotoba-lang/industry` registry-promotion companion ADR entry and the
repo's own `docs/adr/0001-architecture.md` for the identical raw
output re-captured from a second, independent fresh clone.)

`clojure -M:lint` clean (0 errors, 0 warnings). `clojure -M:dev:run`
demo narrative exercises the full happy path (auto-commit, two
escalate/approve cycles, one escalate/approve shipment) plus all
eleven HARD-hold scenarios directly (not-propose-effect, unknown-op,
equipment-not-verified, batch-not-verified, shipment-quantity-
exceeded, equipment-actuate-blocked, double-scheduled, invalid-
product-type, invalid-contact-resistance-milliohm, invalid-
defect-rate, certification-authority-blocked).

All source is `.cljc`, portable to ClojureScript / JVM / nbb -- no
JVM-only interop. The actor graph is invoked exclusively via
`langgraph.graph/run*`.

Repo: <https://github.com/cloud-itonami/cloud-itonami-isic-2733>

## Consequences

(+) ISIC 2733 wiring-device plant operations back-office now has a
documented, governed, auditable coordination layer.

(+) `kotoba-lang/industry` registry's `{:id "2733"}` entry moves from
`:spec` to `:implemented`, with `:repo`/`:business-id` corrected from
the placeholder `gftdcojp/cloud-itonami-C2733` to the real
`cloud-itonami/cloud-itonami-isic-2733`.

(-) Still a simulation/proposal layer; no integration with real plant-
management databases, equipment telemetry, freight dispatch, or
certification-body APIs -- a standalone coordinator blueprint, same
posture as every sibling actor in this fleet.
