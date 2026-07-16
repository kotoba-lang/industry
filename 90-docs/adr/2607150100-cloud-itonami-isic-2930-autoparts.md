# ADR-2607150100: cloud-itonami isic-2930 (auto parts) → `:implemented`

- Status: Accepted (2026-07-14)
- Related: ADR-2607111600 (isic-2910 motor-vehicle promotion), ADR-2607142800
  (robotics-process-simulation fleet pattern — named isic-2930 as
  explicit follow-up work), ADR-2607011000 (robotics premise + ISIC
  section coverage)

## Context

A value-chain survey of the automotive industry (2026-07-14) found
`cloud-itonami-isic-2910` (manufacture of motor vehicles — OEM
final assembly) implemented, but the auto-parts/component tier
(ISIC 2930: manufacture of parts and accessories for motor vehicles)
had no actor at all — a gap in the middle of the supply chain. A
Tier-1/Tier-2 supplier ships a part-lot to the OEM final-assembly
plant that isic-2910 models, but nothing modeled the supplier side.

The industry-registry entry for `2930` had sat at `:maturity :spec`
since ADR-2607011000, with a dead `gftdcojp/cloud-itonami-C2930`
placeholder URL. Separately, ADR-2607142800 established a concrete
robotics-process-simulation pattern (a per-actor `<domain>.robotics`
namespace mapping real physical process steps to a `kotoba.robotics`
mission, a governor HARD check that independently re-derives an
out-of-tolerance verdict rather than trusting a mission's self-report)
via `cloud-itonami-isic-2910`'s `automotive.robotics`, and explicitly
named `isic-2930` as follow-up work for that pattern's replication.

## Decision

1. **`cloud-itonami-isic-2930`** — Auto-Parts Advisor ⊣ Auto-Parts
   Governor (part-lot intake, PPAP evidence-checklist verify,
   process-capability defect screen, robot CMM/torque/weld-inspection
   mission, dual actuation: ship-part-lot + issue-ppap-certificate,
   audit export).
2. Real-world regulatory/industry basis: PPAP (Production Part
   Approval Process) is an OEM-customer-driven **industry** quality-
   management requirement, not a government statute (unlike isic-2910's
   vehicle type-approval, which is government-mandated). Facts catalog
   seeds USA (AIAG PPAP Manual) / DEU (VDA 2 PPF, published by VDA QMC)
   / GBR (SMMT, one of the five IATF 16949 sponsor associations) / JPN
   (JAPIA/JASO) only, citing each authority honestly for what it is —
   an industry standards body, never a legislature.
3. `autoparts.robotics` delivers ADR-2607142800's robotics-process-
   simulation pattern from day one: a CMM dimensional scan /
   fastener-torque check / weld-joint ultrasonic scan mission,
   `:robotics-sim-verified?` on the part-lot, and a governor HARD
   check (`robotics-simulation-violations`) that requires the mission
   on file AND independently re-derives out-of-tolerance from the
   part-lot's own `:critical-dimension-deviation-*` fields — never
   trusting the mission's self-reported `:passed?` alone.
4. `part-lot-dppm-out-of-range?` (defective parts per million reject
   rate against the part-lot's own recorded quality-agreement bounds)
   continues the fleet's two-sided range-check family established by
   testlab/conservation/water/steelworks/turbine/automotive's
   emissions and structural-tolerance checks.
5. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2930` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2930` placeholder (change to be applied
   by the repository owner to `orgs/kotoba-lang/industry/resources/
   kotoba/industry/registry.edn`, a file other parallel agents are
   concurrently touching).
6. Pattern: clone of the isic-2910 (and, before it, isic-2410/2811/
   3011/3030) governed-actor fleet shape (langgraph, dual actuation,
   dedicated boolean double-guards, honest facts catalog, MemStore/
   DatomicStore dual backend), plus the isic-2910-established
   robotics-process-simulation shape — the auto-parts supplier tier
   directly upstream of isic-2910's OEM final-assembly tier in the
   same value chain.

## Consequences

(+) The auto-parts supplier tier gains a forkable OSS operating stack
with auditable governor holds, closing the mid-supply-chain gap the
2026-07-14 value-chain survey identified between raw-materials/
component manufacturing and OEM final assembly.
(+) `cloud-itonami-isic-2930` is the first actor to deliver
ADR-2607142800's robotics-process-simulation pattern as a native part
of its initial build rather than a retrofit, demonstrating the
pattern is tractable to replicate from day one for new manufacturing
verticals.
(−) No physical plant digital-twin geometry in this repo (export +
operator console samples only, same limitation ADR-2607111600 noted
for isic-2910).
(−) PPAP-authority coverage is a starting catalog (4 jurisdictions),
not exhaustive, and does not capture OEM-specific PPAP supplements
(e.g. individual automaker customer-specific requirements layered on
top of IATF 16949/AIAG PPAP).
(−) Retrofitting the pattern into other manufacturing actors named in
the same 2026-07-14 value-chain survey (isic-2610 fab, isic-0810
quarryops, isic-4211 construction) and building the remaining midstream/
downstream gap actors (isic-2620 computer/peripheral manufacture,
isic-2394 cement, isic-4741 computer retail) remains follow-up work
outside this ADR.

## Verification

- `cloud-itonami-isic-2930`: `clojure -M:dev:test` green (44 tests /
  219 assertions), `clojure -M:lint` clean (0 errors), `clojure
  -M:dev:run` demo narrative exercises every HARD-hold path (no-
  spec-basis, evidence-incomplete, robotics-simulation-missing,
  robotics-simulation-out-of-tolerance, part-lot-dppm-out-of-range,
  process-capability-defect-unresolved, already-shipped,
  already-certified) with no exceptions.
- GitHub public repo under `cloud-itonami/cloud-itonami-isic-2930`
  (AGPL-3.0-or-later).
- Industry registry: maturity `"2930"` → `:implemented` (change
  provided to repository owner for application, see Decision 5).
