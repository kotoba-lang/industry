# ADR-2607160200: cloud-itonami isic-2920 (body-in-white body shop) → `:implemented`

- Status: Accepted (2026-07-16)
- Related: ADR-2607111600 (isic-2910 motor-vehicle promotion), ADR-2607151600
  (real engineering-simulation integration, automotive pilot), ADR-2607152000
  (real engineering-simulation fleet extension)

## Context

The industry registry's entry for ISIC Rev.5 **2920** (manufacture of bodies
[coachwork] for motor vehicles) sat at `:maturity :spec`, pointed at the dead
`gftdcojp/cloud-itonami-C2920` placeholder URL, with no repo, no business
model, no actor. A value-chain review of the automotive cluster found the
three neighboring verticals already implemented -- `cloud-itonami-isic-2410`
(basic iron and steel, the raw-material input), `cloud-itonami-isic-2910`
(motor-vehicle final assembly, downstream), `cloud-itonami-isic-2930` (parts/
accessories, a Tier-1/Tier-2 supplier concern) -- but the body-in-white (BIW)
stamping/welding stage that actually turns coiled sheet steel into a welded
vehicle body shell, directly upstream of final assembly, had no actor at all:
a genuine gap in the middle of the automotive value chain.

ADR-2607151600 established a real, time-stepped `physics-2d` engineering-
simulation pattern for the automotive pilot (isic-2910), and ADR-2607152000
generalized it to 6 further manufacturing actors. This new vertical, isic-
2920, was not one of those original 7 -- it is built to the SAME real-physics
standard NATIVELY from day one (mirroring how `cloud-itonami-isic-2930`/
`cloud-itonami-isic-2394` were built real-physics-first), not a retrofit.

## Decision

1. **`cloud-itonami-isic-2920`** -- Body Shop Advisor ⊣ Stamping Governor
   (body-shell intake, per-jurisdiction AHSS material-certification evidence
   verify, end-of-line dimensional/weld-quality screen, robot stamping-press-
   forming mission, dual actuation: ship-body-shell + issue-body-certificate,
   audit export).
2. Entity is a **body-shell** (a produced body-in-white unit, tracked per
   body-serial), distinct from isic-2910's vehicle, isic-2930's part-lot, and
   isic-2410's steel heat.
3. Real-world material-certification basis: voluntary INDUSTRY AHSS material
   standards an OEM purchase contract requires a body-shell's rail-material
   mill certification to conform to -- SAE J2340 (USA), JIS G 3135 (Japan),
   EN 10346 (Germany/EU). Facts catalog seeds these three jurisdictions only,
   honestly distinguished from isic-2910's GOVERNMENT-mandated vehicle
   type-approval statutes (the same honest distinction `autoparts.facts`
   already draws for PPAP vs. `automotive.facts`'s statutes).
4. **Native real-physics stamping-press-forming simulation** (`bodyshop.
   robotics`, ADR-2607151600/ADR-2607152000's pattern, delivered from day
   one): a press-die `Body2D` (a real `kotoba-lang/physics-2d` rigid body)
   closes at a controlled velocity onto a static sheet-metal-blank `Body2D`;
   `world-step` actually integrates/collides/resolves the contact over real
   ticks; `:sim-peak-forming-force-n`/`:sim-peak-forming-pressure-mpa` are
   read directly off the ACTUAL simulated trajectory (F = m·a), never
   invented. The governor independently re-derives that reading against a
   real, disclosed ceiling (3× the panel's own rail-material-grade published
   yield strength), never trusting the mission's self-reported verdict.
5. **Cross-actor material-grade-vocabulary reuse**: `:rail-material-grade`
   (`:DP600`/`:DP980`/`:boron-PHS`) deliberately reuses the SAME real AHSS
   grade names `kami-engine-vehicle-designer`'s `vdesign.simverify` geometry
   map already cites for automotive's own crash-structural model -- a
   body-shell's rail material grade IS the literal input to isic-2910's
   downstream crash-safety check, a genuine cross-actor consistency, not
   cosmetic.
6. `body-shell-dimension-out-of-range?` continues the fleet's two-sided
   range check family (after testlab / conservation / water / steelworks /
   turbine / automotive / autoparts), applied to a body-shell's own measured
   overall-length deviation against its own recorded nominal-dimension spec
   bounds.
7. Dual actuation on the same entity, dedicated boolean double-guards
   (`:body-shell-shipped?`/`:body-certified?`, never a status lifecycle,
   ADR-2607071320/6492 lesson): `:actuation/ship-body-shell` (the REAL
   upstream → downstream hand-off to `cloud-itonami-isic-2910`'s own
   `:vehicle/intake`) and `:actuation/issue-body-certificate` (a Body-in-White
   Quality Certificate).
8. Weld-quality defect unresolved is evaluated unconditionally so
   `:end-of-line-quality/screen` itself can HARD-hold (parksafety
   ADR-2607071922 Decision 5 discipline, same as every prior sibling
   governor's own unresolved-defect check).
9. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2920` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2920` placeholder.
10. Render proof: a throwaway `/tmp` harness (mirroring ADR-2607151600's own
    render-pilot technique) dumps the real `bodyshop.robotics/simulate-press`
    trajectory to scene data and draws the press-die/sheet-metal-blank as box
    meshes via `kami.webgpu.mesh` (fetched fresh from `kotoba-lang/webgpu`'s
    `main`), verified end-to-end in real headless Chromium via Playwright on
    BOTH the native WebGPU path and the WebGL2 fallback path (`navigator.gpu`
    deleted), with both an in-page `gl.readPixels` probe and an independent
    out-of-band PNG-pixel decode confirming the press-die pixel differs from
    background in both the tick-0 and settled frames.

## Consequences

(+) The body-in-white stamping/welding stage gains a forkable OSS operating
stack with auditable governor holds, closing the mid-supply-chain gap this
value-chain review identified, alongside basic iron/steel (2410), auto parts
(2930) and motor-vehicle final assembly (2910).
(+) Delivers a REAL time-stepped physics simulation (not a symbolic
comparison) as a native part of this actor's initial build, and a real
WebGPU/WebGL2-rendered visual proof of that simulation, extending
ADR-2607151600/ADR-2607152000's fleet pattern to a brand-new actor.
(+) Genuine cross-actor material-grade-vocabulary consistency with
`cloud-itonami-isic-2910`'s downstream crash-structural check.
(−) `physics-2d` is a 2D projection with no material-stiffness/stress-strain
model -- the forming-pressure ceiling is an honest engineering proxy for a
forming-limit-diagram exceedance, not a literal transcription of one specific
named tear-threshold standard (fully disclosed in `bodyshop.robotics`'s own
docstring).
(−) Material-cert-authority coverage is a starting catalog (3 jurisdictions),
not exhaustive.
(−) The render harness is a throwaway `/tmp` proof, not a shipped feature of
the actor repo itself (mirrors ADR-2607151600's own scope).

## Verification

- `cloud-itonami-isic-2920`: `clojure -M:dev:test` green (44 tests / 229
  assertions), `clojure -M:lint` clean, `clojure -M:dev:run` completes with
  no exceptions, exercising every HARD-hold path including a genuinely-
  failing physics-derived fixture (shell-5: a press-die mass configured for
  a much heavier stamping run than its DP600 rail material calls for yields
  a real simulated forming pressure of 1953.125 MPa, exceeding DP600's
  1140 MPa ceiling on the governor's independent recheck, even though
  `:robotics-sim-verified?` was seeded `true` -- the actor never trusts the
  stored verdict).
- Render proof: real WebGPU (`navigator.gpu` available, Metal-backed) AND
  real WebGL2 fallback both render the scene; both backends' screenshots
  show the press-die pixel differing from background in both the tick-0 and
  settled frames, confirmed via both an in-page `gl.readPixels` probe and an
  independent out-of-band PNG decode.
- Industry registry: maturity `"2920"` → `:implemented`.
- GitHub public repo under `cloud-itonami/cloud-itonami-isic-2920`.
