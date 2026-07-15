# ADR-2607160600: cloud-itonami isic-2310 (glass and glass products) → `:implemented`

- Status: Accepted (2026-07-16)
- Related: ADR-2607111600 (2910 motor vehicles), ADR-2607151600 (real
  engineering-simulation integration, automotive pilot), ADR-2607152000
  (real engineering-simulation fleet extension)

## Context

ISIC Rev.5 **2310** (manufacture of glass and glass products) still sat
as a `:maturity :spec` placeholder with a dead `gftdcojp/cloud-itonami-C2310`
URL — no repo, no business model, no actor. Two fleet verticals built
earlier this session already assumed a finished glass-panel input
without ever modeling where it comes from: `cloud-itonami-isic-2630`
(communication-equipment manufacturing)'s display-module optical-
bonding press test assumes a cover-glass panel already exists, and
`cloud-itonami-isic-2920` (motor-vehicle bodies/coachwork)'s downstream
sibling `cloud-itonami-isic-2910` (motor-vehicle assembly) needs
windshield/side-glass safety glazing. Automotive safety glazing and
smartphone cover glass are both fundamentally glass-manufacturing
outputs of the SAME manufacturing act — a float-line/tempering-line
producing a flat-glass panel to a tempering/chemical-strengthening
spec — differing only in temper process and glazing-standard citation.
2310 is that missing upstream stage.

This is also the first NEW actor in this fleet built to
ADR-2607151600/ADR-2607152000's real-physics-simulation standard from
day one, in a single pass, alongside the governed-actor pattern itself
— not a retrofit, mirroring how `cloud-itonami-isic-2920`/`-2930`/
`-2394` delivered that standard natively.

## Decision

1. **`cloud-itonami-isic-2310`** — Glass Advisor ⊣ Tempering Governor
   (glass-panel-batch intake, glazing-standard-rules verify, end-of-
   line optical/edge-defect quality screen, robot flexural-bend-test
   simulation, dual actuation: ship-glass-panel-batch + issue-glazing-
   certificate, audit export). Namespace prefix `glassworks.*`.
2. **Real time-stepped physics simulation** (`glassworks.robotics`,
   ADR-2607152000 pattern): a genuine `kotoba-lang/physics-2d`
   rigid-body simulation of an ASTM C158 three-point flexural
   (bend-strength) test — a loading-pin `Body2D` closes at a
   controlled velocity onto a static (mass 0) glass-panel-specimen
   `Body2D`. `:sim-peak-flexural-force-n` is read directly off the
   ACTUAL simulated collision trajectory (F = m·a); `:sim-peak-
   flexural-stress-mpa` applies the genuine textbook rectangular-beam
   center-loading formula `sigma = 3·F·L/(2·w·h^2)`. Takes a real
   pinned git-coordinate dependency on `io.github.kotoba-lang/
   physics-2d` (sha `03b6c23c8d9aa36b92a8130fd3e37fc2548b4d26`) from
   day one.
3. Real-world glazing-standard basis, per product class: automotive
   safety glazing — ANSI/SAE Z26.1 (referenced by 49 CFR 571.205/
   FMVSS 205, USA) + ASTM C1036 (flat-glass baseline spec), JIS R 3211
   (Japan), UNECE Regulation No. 43 (multi-market EU/Japan/1958-
   Agreement type-approval basis); cover/chemically-strengthened glass
   — ASTM C158 (the same standard the flexural-bend-test simulation
   itself implements). Flexural-strength acceptance bands are seeded
   with EXPLICIT, disclosed confidence: annealed float glass (~35-55
   MPa) is a confident general anchor; automotive-tempered (~150-260
   MPa) and chemically-strengthened cover glass (~450-900 MPa) are
   reasoned engineering estimates, never presented as verbatim single
   citations.
4. Dual actuation on the same entity (a glass-panel batch):
   - `:actuation/ship-glass-panel-batch` (batch-shipment draft; the
     record honestly names the REAL two-member downstream-consumer
     mapping — automotive-safety-glazing batches → `cloud-itonami-
     isic-2910`/`cloud-itonami-isic-2920`, cover-glass batches →
     `cloud-itonami-isic-2630`)
   - `:actuation/issue-glazing-certificate` (Glazing/Glass Test
     Certificate draft)
5. Double-actuation guards use dedicated booleans
   (`:glass-batch-shipped?`, `:glazing-certified?`), never a status
   lifecycle (ADR-2607071320 / 6492 lesson).
6. `panel-thickness-deviation-out-of-range?` continues the fleet
   two-sided range check family, SEPARATE from the robotics-simulated
   flexural-strength reading — the governor independently rechecks
   both before `:actuation/ship-glass-panel-batch` may commit.
7. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2310` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2310` placeholder.
8. **Real render proof**: the actor's own real physics-2d flexural-
   bend-test trajectory (batch-1's `:flexural-test-pin-mass-kg 42.7`/
   `:panel-thickness-actual-mm 4.0` configuration) was dumped to
   `scene-data.json` and rendered as box meshes via `kotoba-lang/
   webgpu`'s `kami.webgpu.mesh` executor (fetched fresh from GitHub
   `main`) in a headless-but-real Chromium, on BOTH backends: a real
   WebGPU device (`navigator.gpu`/`requestAdapter`/`requestDevice`)
   and a forced real WebGL2 fallback (`navigator.gpu` deleted via
   `addInitScript`) with genuine `readPixels` proof that the rendered
   glass-panel/loading-pin pixels differ from the background clear
   color. Screenshots saved outside the actor repo (`/tmp/render-2310/
   glass-flexural-render.png` / `-webgl2.png`), matching this
   session's established render-harness technique.

## Consequences

(+) Both this session's automotive-glazing chain
(`cloud-itonami-isic-2910`/`-2920`) and its communication-equipment
chain (`cloud-itonami-isic-2630`) now have a real, governed upstream
glass-manufacturing stage instead of an assumed input.
(+) Reuses langgraph + dual-backend store parity + the fleet's real-
physics-simulation pattern (ADR-2607151600/ADR-2607152000) without
inventing new architecture; delivered natively in one pass (governor,
real physics, real render, ADR).
(−) `physics-2d`'s AABB collider models a three-point/center-loading
bend test only; ASTM C158's four-point loading geometry is not
modeled (disclosed in `glassworks.robotics`'s own docstring).
(−) Glazing-standard-authority coverage is a starting catalog (USA/
Japan/UNECE for automotive, one global ASTM entry for cover glass),
not exhaustive — e.g. no Germany/EU national statute beyond the
UNECE R43 multi-market basis.
(−) Flexural-strength acceptance bands for tempered/chemically-
strengthened glass are reasoned engineering estimates, not single
verbatim citations — disclosed honestly in `glassworks.robotics`.

## Verification

- `cloud-itonami-isic-2310`: `clojure -M:dev:test` green (46 tests /
  236 assertions), `clojure -M:lint` clean, `clojure -M:dev:run`
  completes with no exceptions, exercising every HARD-hold path
  including a genuinely under-tempered physics-derived fixture
  (batch-5: real simulated flexural stress 45.0 MPa vs. its own
  recorded [150.0, 260.0] MPa automotive-tempered spec).
- Real physics numbers (batch-1, clean): `:sim-peak-flexural-force-n`
  854.0 N, `:sim-peak-flexural-stress-mpa` 160.125 MPa (within its
  [150,260] MPa spec). Batch-6 (cover glass): 648.98 MPa (within its
  [450,900] MPa spec).
- Real render: WebGPU backend rendered successfully (`navigator.gpu`
  real device); WebGL2 fallback backend rendered successfully with
  real `readPixels` proof (center pixel `[94,146,162,255]` vs.
  background corner `[9,14,26,255]`, genuinely differing).
- Industry: maturity `"2310"` → `:implemented`.
- GitHub public repo under `cloud-itonami/`.
