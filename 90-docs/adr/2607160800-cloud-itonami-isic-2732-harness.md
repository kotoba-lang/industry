# ADR-2607160800: cloud-itonami isic-2732 (wire/cable/harness manufacturing) → `:implemented`

- Status: Accepted (2026-07-15)
- Related: ADR-2607151600 (real engineering-simulation integration, automotive pilot), ADR-2607152000 (fleet extension establishing the direct-`physics-2d`-dependency, no-design-library-sibling shape this actor follows), ADR-2607111600 (2910 motor vehicles), sibling promotion ADRs for 2720 (batteries), 2310 (glass), 2220 (plastics)

## Context

This session has been building out a coherent supply web feeding two
manufacturing chains: smartphone manufacturing
(`cloud-itonami-isic-2630`) and vehicle manufacturing
(`cloud-itonami-isic-2910`/`-2920`), with shared upstream materials
stages already built for both — batteries (`cloud-itonami-isic-2720`),
glass (`cloud-itonami-isic-2310`) and plastics
(`cloud-itonami-isic-2220`). **ISIC 2732 (manufacture of other
electronic and electric wires and cables)** still sat as a
`:maturity :spec` placeholder with a dead
`gftdcojp/cloud-itonami-C2732` URL — no repo, no business model, no
actor — despite being arguably the MOST directly connective materials
stage of all: automotive wiring harnesses and smartphone internal
flex-cables/fine-gauge wiring are both fundamentally wire-and-cable
manufacturing outputs, and both chains' existing actors consume this
output without any upstream actor producing it.

Per ADR-2607152000, six manufacturing-stage cloud-itonami actors
already carry a real, time-stepped `kotoba-lang/physics-2d`-backed
process-verification check (upgraded from ADR-2607142800's symbolic
pass/fail), reusing the direct-dependency, no-design-library-sibling
shape `cloud-itonami-isic-2930` (auto-parts) established for its own
weld-joint/fastener proof-load pull test via the honest "collision-
as-tension-limit" reinterpretation technique (a moving rigid body
travels toward a static wall standing in for the real point of
separation/failure, since `physics-2d`'s collision-only solver has no
native notion of a body separating under tension).

## Decision

1. **`cloud-itonami-isic-2732`** — Harness Advisor ⊣ Cable-Integrity
   Governor (cable-run-batch intake, per-product-class
   harness-standard-rules evidence verify, end-of-line
   continuity/insulation-resistance quality screen, dual actuation:
   `:actuation/ship-cable-run-batch` + `:actuation/issue-harness-
   certificate`, audit export).
2. Real-world standards basis: **IPC/WHMA-A-620** (Requirements and
   Acceptance for Cable and Wire Harness Assemblies), **SAE J1128**
   (US low-tension primary cable) and **ISO 6722** (road-vehicle
   60V/600V single-core cables) for the automotive wiring-harness
   product class; **UL 758** (appliance wiring material) and
   **IPC-A-610** (acceptability of electronic assemblies) for the
   fine-gauge internal electronics/flex-cable product class. The
   facts catalog is keyed by PRODUCT CLASS (`"AUTO"`/`"ELEX"`), not
   ISO3 country code, since these are industry/standards-body
   specifications international in scope, not a single national
   statute — the same honest field-reinterpretation `autoparts.facts`
   established for PPAP.
3. **`harnessworks.robotics/simulate-tensile-pull-test`** extends
   ADR-2607152000's real-physics pattern to a SEVENTH manufacturing-
   stage cloud-itonami actor: a genuine time-stepped `physics-2d`
   rigid-body simulation of the conductor/crimp tensile-pull test (a
   real, standard wire/cable/harness QA test — IPC/WHMA-A-620
   specifies minimum crimp pull-force requirements by wire gauge;
   ASTM B3 is the related copper-conductor tensile-property
   baseline), using the SAME "collision-as-tension-limit" technique
   `autoparts.robotics` established. `:sim-peak-pull-force-n` is
   derived from the actual simulated collision impulse, never
   invented; the governor independently rechecks it against a
   disclosed 60 N REASONED-ESTIMATE floor for the automotive/
   fine-gauge conductor class modeled (not a verbatim single-table
   citation — disclosed as such, matching `autoparts.robotics/min-
   proof-load-n`'s own confidence level).
4. **Real render proof**: a scratch Playwright harness (under
   `/tmp`, not committed to any repo) dumped the actor's own real
   simulated `:pull-clamp`/`:limit-boundary` trajectory to
   `scene-data.json` and rendered it as real box meshes via
   `kotoba-lang/webgpu`'s `kami.webgpu.mesh` executor, confirmed on
   BOTH backends: real WebGPU (headless Chromium,
   `navigator.gpu`-backed) and the WebGL2 fallback (forced via
   deleting `navigator.gpu`) — both produced a differentiated,
   non-background pixel at the simulated collision/limit tick versus
   the start tick and the background corner, and a PNG screenshot was
   saved for each.
5. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2732` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2732` placeholder.
6. **Scope note**: this actor is the connective upstream stage for
   BOTH `cloud-itonami-isic-2910`/`-2920` (automotive wiring
   harnesses) and `cloud-itonami-isic-2630` (smartphone flex-cables),
   both terminating at `cloud-itonami-isic-2720`'s (battery) terminals
   — the same downstream/adjacent-consumer cross-reference pattern
   `cloud-itonami-isic-2720`/`-2310`/`-2220` established as shared
   upstream materials stages for these same two chains.

## Consequences

(+) The automotive/smartphone supply web gains its missing upstream
wire/cable/harness stage, with a genuinely time-stepped
physics-backed QA check (not a symbolic pass/fail) and a real
rendered visual proof on two independent GPU backends.
(+) Zero new physics engine, CAD kernel, or Rust crate — 100%
composed from existing, previously-established real components
(`physics-2d`, `kami.webgpu.mesh`, `w3.webgpu`, `kotoba.webgl`), the
same discipline ADR-2607151600/ADR-2607152000 established.
(−) Product-class coverage is a starting catalog (AUTO/ELEX only),
not exhaustive.
(−) The 60 N minimum-pull-force floor is a disclosed REASONED
ESTIMATE for the conductor gauge class modeled, not a verbatim
single-standard citation.
(−) The physics simulation remains a 2D projection with no force-
deflection/spring model (same disclosed limit as every real-physics
sibling in this fleet).

## Verification

- `cloud-itonami-isic-2732`: `clojure -M:dev:test` green (45 tests /
  225 assertions), `clojure -M:lint` clean (0 errors, 0 warnings),
  `clojure -M:dev:run` completes with no exceptions, exercising every
  HARD hold including a genuinely-failing under-crimped/wrong-gauge
  fixture (batch-5: real simulated peak pull force 30 N vs. the 60 N
  floor).
- Real physics-2d simulation (batch-1, `:crimp-effective-mass-kg`
  0.10 kg): peak deceleration 1000 m/s², peak pull force 100 N (passes
  the 60 N floor with margin); batch-3 (0.11 kg): 110 N (passes);
  batch-5 (0.03 kg, under-crimped/wrong-gauge): 30 N (HARD hold,
  below floor).
- Render proof: both WebGPU and WebGL2 backends produced a real,
  differentiated PNG screenshot of the simulated pull-clamp/
  limit-boundary collision tick.
- Industry: maturity `"2732"` → `:implemented`
- GitHub public repo under `cloud-itonami/`
