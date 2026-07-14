# ADR-2607150200: computer/electronics final-product-assembly gap 2620 (computers and peripheral equipment) → `:implemented`

- Status: Accepted (2026-07-14)
- Related: ADR-2607142800 (robotics premise → concrete process simulation, fleet pattern), ADR-2607011000 (original robotics premise + ISIC section coverage 21/21), ADR-2607111600 (isic-2910 motorvehicle → `:implemented`, closest structural sibling)

## Context

A value-chain survey of the computer/electronics industry (2026-07-14)
found `cloud-itonami-isic-2610` (semiconductor/electronic-component
fab) implemented as **Fab Advisor ⊣ Fab Operations Governor**, but the
FINAL-PRODUCT-ASSEMBLY tier -- ISIC 2620, manufacture of computers and
peripheral equipment: assembling a laptop/PC/server/monitor out of
already-fabricated components, distinct from fabricating the
components themselves -- had no actor at all. The registry entry sat
as a `:maturity :spec` placeholder with a dead
`gftdcojp/cloud-itonami-C2620` URL and `business-id
"cloud-itonami-C2620"` -- no repo, no business model, no actor. This
was a genuine mid-chain gap: isic-2610 fabricates the chips/boards, but
nothing in the fleet governed the act of assembling those fabricated
components into a shippable finished device-unit.

The closest structural sibling is `cloud-itonami-isic-2910` (motor
vehicles): both are final-assembly-and-release verticals with the same
intake -> evidence-checklist verify -> defect screen -> robotics
mission -> dual actuation shape, even though the regulatory regime
(EMC/product-safety self-declaration vs. vehicle type-approval/
homologation) and final product differ. ADR-2607142800 additionally
established `cloud-itonami-isic-2910`'s `automotive.robotics` as the
fleet-wide reference pattern for concrete robot-process simulation
(a per-actor `<domain>.robotics` namespace, a new always-human-approval
robotics op, and a new governor HARD check that independently rechecks
a ground-truth tolerance field rather than trusting a mission's
self-reported verdict) and explicitly named `isic-2620` as one of the
gap actors to build with this pattern from day one.

## Decision

1. **`cloud-itonami-isic-2620`** -- Device Assembly Advisor ⊣ Assembly
   Governor (device-unit intake, per-jurisdiction EMC/product-safety
   compliance-rules verify, end-of-line quality screen, robot burn-in-
   cell verification mission, dual actuation: ship-device-unit +
   issue-declaration-of-conformity, audit export).
2. Real-world regulatory basis: EMC/product-safety compliance regimes
   for IT/electronic equipment -- Japan's VCCI (Voluntary Control
   Council for Interference by Information Technology Equipment) +
   PSE mark (電気用品安全法, METI), US FCC Part 15 (unintentional
   radiators) self-certification, UK OPSS/UKCA self-declaration, EU
   CE-marking under the EMC Directive 2014/30/EU + RoHS Directive
   2011/65/EU, with IEC 62368-1 (audio/video/IT-equipment safety)
   cited as a shared international safety-standard anchor. Facts
   catalog seeds JPN / USA / GBR / DEU only.
3. **Robot process simulation delivered from day one, per the
   ADR-2607142800 pattern**: `deviceassembly.robotics` (automated
   functional-test rig / thermal burn-in stress test / robotic EMC
   pre-scan mission), `deviceassembly.governor/robotics-simulation-
   violations` (HARD-holds `:actuation/ship-device-unit` if the
   mission never ran, OR if an independent recheck of the device-
   unit's own thermal-margin fields disagrees with the mission's
   stored `:passed?`), and `deviceassembly.registry/device-unit-emc-
   emission-out-of-range?` (a further instance of the fleet's two-
   sided range-check family, after `automotive.registry/vehicle-
   emissions-out-of-range?`).
4. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2620` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2620` placeholder and `business-id`.
5. Pattern: clone of the `cloud-itonami-isic-2910` (motor vehicles)
   governed-actor + robotics-simulation shape (langgraph, dual
   actuation, dedicated boolean double-guards, honest facts catalog,
   robot mission with independent governor recheck) -- second
   manufacturing vertical to ship the ADR-2607142800 robotics-
   simulation pattern at initial build (after its establishment on
   `cloud-itonami-isic-2910`), and the first actor to close the
   final-product-assembly tier of the computer/electronics value
   chain identified by the 2026-07-14 survey.

## Consequences

(+) The computer/electronics value chain now has both a component-fab
actor (`cloud-itonami-isic-2610`) and a final-product-assembly actor
(`cloud-itonami-isic-2620`), closing the mid-chain gap the survey
identified.
(+) Robot process simulation ships as a first-class governor HARD
check from initial build, not a retrofit -- validates that the
ADR-2607142800 pattern is a tractable, bounded diff to replicate
across new actors, not just existing ones.
(+) Reuses langgraph + store dual-backend parity without new physics.
(−) No physical plant digital-twin tick in this repo (follow-up domain
data is out of scope here, matching every prior manufacturing sibling).
(−) Compliance-authority coverage is a starting catalog (4
jurisdictions), not exhaustive.
(−) Retrofitting the remaining gap actors identified in the 2026-07-14
value-chain survey (isic-2930 automotive parts, isic-2394 cement,
isic-4741 computer retail) and the remaining non-retrofitted
manufacturing actors (isic-0810 quarryops, isic-4211 construction) with
the ADR-2607142800 robotics-simulation pattern is follow-up work,
tracked outside this ADR.

## Verification

- `cloud-itonami-isic-2620`: `clojure -M:dev:test` green (44 tests /
  219 assertions), `clojure -M:lint` clean, `clojure -M:dev:run` demo
  narrative exercises every HARD hold (`:no-spec-basis`,
  `:evidence-incomplete`, `:robotics-simulation-missing`,
  `:robotics-simulation-out-of-tolerance`, `:device-unit-emc-emission-
  out-of-range`, `:end-of-line-defect-unresolved`, `:already-shipped`,
  `:already-declared`) with no exceptions.
- Industry: maturity `"2620"` → `:implemented`
- GitHub public repo under `cloud-itonami/`
