# ADR-2607160100: cloud-itonami-isic-2630 (communication equipment / smartphones) → `:implemented`

- Status: Accepted (2026-07-16)
- Related: ADR-2607151600 (real-engineering-simulation integration pilot — `cloud-itonami-isic-2910`), ADR-2607152000 (real-engineering-simulation fleet extension — `cloud-itonami-isic-2930`/`cloud-itonami-isic-2394`)

## Context

ISIC Rev.5 **2630** (manufacture of communication equipment — the
industry registry's own dead placeholder repo,
`gftdcojp/cloud-itonami-C2630`) still sat at `:maturity :spec`. The
adjacent final-assembly tier, `cloud-itonami-isic-2620` (computers and
peripheral equipment), already models general IT-equipment EMC/
product-safety self-declaration, but its devices mostly have no
intentional radio transmitter. Smartphones/handsets do, so this
promotion builds a NEW actor rather than a variant of isic-2620,
carrying a materially different, radio-specific regulatory regime
(type-approval, not general EMC self-declaration).

This promotion also builds to this fleet's current full standard from
day one — the same governed-actor pattern PLUS a native real
time-stepped `physics-2d` engineering simulation PLUS a real WebGPU/
WebGL2 render proof of that simulation's own trajectory data — mirroring
the pattern ADR-2607151600 established (`cloud-itonami-isic-2910`
pilot) and ADR-2607152000 extended natively-from-day-one
(`cloud-itonami-isic-2930`/`cloud-itonami-isic-2394`).

## Decision

1. **`cloud-itonami-isic-2630`** — Device Advisor ⊣ Radio-Compliance
   Governor (device-unit intake, radio-compliance-rules verify,
   end-of-line quality screen, robot display-bonding-press simulation,
   dual actuation: ship-device-unit + issue-radio-conformity-
   certificate, audit export). Namespaces under `commsdevice.*`,
   MemStore + DatomicStore dual backend (langchain.db), langgraph-clj
   StateGraph actor, phase 0→3 rollout with actuation permanently
   excluded from every phase's `:auto` set.
2. Real-world regulatory basis, deliberately DISTINCT from isic-2620's
   general EMC catalog: Japan's 技術基準適合証明 (Giteki mark,
   MIC/総務省 via TELEC), EU Radio Equipment Directive 2014/53/EU
   (RED), US FCC Part 15 Subpart C (intentional radiators) + Part
   22/24/27 (licensed cellular services) Certification, UK Radio
   Equipment Regulations 2017 — plus, uniquely, an RF-exposure/SAR
   test report every seeded jurisdiction requires (no analog in
   isic-2620's catalog) and the general IEC 62368-1 safety baseline
   both share. Facts catalog seeds JPN/USA/GBR/DEU only.
3. `commsdevice.robotics` delivers a REAL, native-from-day-one
   `physics-2d` rigid-body simulation of the display-module optical-
   bonding (OCA lamination) press: a press-platen `Body2D` (per-device-
   unit `:bonding-press-platen-mass-kg`) closes at a controlled
   velocity onto a static display-stack `Body2D` (cover glass + OCA +
   LCD/OLED module), `world-step` actually integrates/collides/
   resolves the contact, and `:sim-peak-bonding-pressure-mpa` is read
   directly off the actual simulated trajectory — mirroring
   `cementmill.robotics`'s (`cloud-itonami-isic-2394`) press-collision
   technique exactly. The acceptance band (0.15–0.55 MPa) is disclosed
   honestly as a reasoned engineering estimate for OCA-lamination
   bonding pressure, not a confidently-sourced single citation.
4. `device-rf-power-out-of-range?` (RF conducted-transmit-power
   deviation, dB) continues this fleet's two-sided ground-truth range
   check family, checked independently by the governor before
   `:actuation/ship-device-unit`, alongside the independent physics-2d
   bonding-pressure recheck — never trusting a proposal's self-report.
5. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2630` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2630` placeholder.
6. Real render proof: a throwaway `/tmp`-only shadow-cljs harness
   (`render2630`) dumped `commsdevice.robotics/simulate-bonding-press`'s
   own real platen trajectory (device-1, 18.0kg platen mass) to
   scene data, and drew it as two box meshes (start/approach frame,
   settled/contact frame) through `kami.webgpu.mesh` (fetched fresh
   from `kotoba-lang/webgpu`'s `main`), verified via headless
   Playwright Chromium on BOTH the real WebGPU backend and the
   WebGL2 fallback (`navigator.gpu` deleted via `addInitScript`) —
   platen/display-stack pixels genuinely differ from background in
   both simulated frames, on both backends.

## Consequences

(+) Communication-equipment (smartphone) manufacturing gains a
forkable OSS operating stack with auditable governor holds, closing
the industry registry's dead-placeholder gap for `2630`.
(+) Built to the current full fleet standard from day one: governed
actor + real physics-2d simulation + WebGPU/WebGL2 render proof, not a
retrofit.
(+) Clearly distinguishes its regulatory regime from the adjacent
`cloud-itonami-isic-2620` sibling (radio-type-approval + SAR vs.
general EMC/product-safety self-declaration).
(−) `physics-2d` has no material-stiffness/adhesive-compliance model,
so the OCA layer's own real viscoelastic behavior cannot itself vary
the simulated reading (honestly disclosed in `commsdevice.robotics`'s
own docstring).
(−) Facts catalog seeds four jurisdictions; not exhaustive
radio-type-approval-authority coverage, and does not capture
carrier-specific certification supplements.

## Verification

- `cloud-itonami-isic-2630`: `clojure -M:dev:test` green (41 tests /
  222 assertions), `clojure -M:lint` clean, `clojure -M:dev:run`
  completes with no exceptions exercising all 7 governor HARD-hold
  paths including a genuinely-failing physics-derived fixture
  (device-5: real `:sim-peak-bonding-pressure-mpa` 0.643 MPa exceeds
  the 0.15–0.55 MPa band on independent recheck, despite
  `:robotics-sim-verified?` already being seeded `true`)
- Render proof: `/tmp/render-2630/smartphone-bonding-render.png`
  (real WebGPU, Metal-backed) and
  `/tmp/render-2630/smartphone-bonding-render-webgl2.png` (WebGL2
  fallback) — both `npm run verify`/`verify-webgl2` exit 0
- Industry: maturity `"2630"` → `:implemented`
- GitHub public repo under `cloud-itonami/`
