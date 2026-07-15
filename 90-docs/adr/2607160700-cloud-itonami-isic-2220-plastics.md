# ADR-2607160700: cloud-itonami isic-2220 plastics-products manufacturing → `:implemented`

- Status: Accepted (2026-07-15)
- Related: ADR-2607111600 (isic-2910 motor-vehicle promotion — sibling
  architecture this repo mirrors), ADR-2607160100 (isic-2630
  smartphone/communication-equipment, first downstream consumer),
  ADR-2607160200 (isic-2920 body-shop, second downstream consumer),
  ADR-2607160500 (isic-2720 battery/cell, prior shared-upstream
  sibling), ADR-2607160600 (isic-2310 glass, prior shared-upstream
  sibling), ADR-2607151600 (real engineering-simulation integration,
  automotive pilot), ADR-2607152000 (real engineering-simulation fleet
  extension)

## Context

ISIC 2220 (manufacture of plastics products) sat as a `:maturity
:spec` placeholder with a dead `gftdcojp/cloud-itonami-C2220` URL — no
repo, no business model, no actor. This session already built
`cloud-itonami-isic-2630` (smartphone/communication-equipment
assembly) and `cloud-itonami-isic-2910`/`cloud-itonami-isic-2920`
(motor-vehicle final assembly / body-coachwork manufacturing) as
native real-physics actors, with shared upstream materials stages
already closed for both chains: batteries (`cloud-itonami-isic-2720`)
and glass (`cloud-itonami-isic-2310`). A value-chain review found that
injection-molded PLASTICS are the next natural shared upstream stage:
smartphone housings/back-covers/internal chassis brackets AND
automotive bumper fascias/interior trim/dashboard components are both
fundamentally injection-molded-plastics outputs. No cloud-itonami
actor produced a material-spec-verified, clamp-force-verified
plastics molding-run-batch — a real gap common to two value chains at
once, not a niche single-vertical gap.

## Decision

1. **`cloud-itonami-isic-2220`** — Molding Advisor ⊣ Clamp-Force
   Governor (molding-run-batch intake, per-product-class material-
   spec-rules verify, end-of-line short-shot/flash/warpage quality
   screen, robot injection-mold clamping-force verification mission,
   dual actuation: ship-molding-run-batch + issue-material-
   certificate, audit export). Namespaces live under `moldworks.*`
   with the standard facts/registry/store/governor/phase/advisor/
   operation/sim/robotics/export shape, mirroring `cloud-itonami-
   isic-2720`/`cloud-itonami-isic-2310`'s own structure exactly
   (`langgraph-clj` StateGraph, `MemStore`/`DatomicStore` dual backend
   proven by a shared contract test, dedicated boolean double-
   actuation guards, honest facts catalog).
2. **Native real-physics simulation from day one**
   (ADR-2607151600/ADR-2607152000 pattern, delivered the same way
   `cloud-itonami-isic-2720`/`cloud-itonami-isic-2310` delivered it —
   as a new build, not a retrofit): `moldworks.robotics` runs an
   ACTUAL time-stepped `kotoba-lang/physics-2d` rigid-body simulation
   of an injection-mold clamping-force verification cycle — a moving
   mold-half `Body2D` closes at a controlled velocity onto a static
   mold-half `Body2D`. A real `:sim-peak-clamp-force-n`/`:sim-peak-
   clamp-tonnage` is read directly off the simulated collision
   trajectory (F = m·a) and independently rechecked against the
   batch's own `required-clamp-tonnage-tons` — derived from the part's
   own projected area and material's own cavity-pressure factor via
   the REAL, widely-cited plastics-processing engineering heuristic
   (clamp force (tons) ≈ projected part area (in²) × a material-
   specific cavity-pressure factor, ~2-5 tons/in² for easy-flow
   materials like polypropylene/ABS and ~6-8 tons/in² for harder/
   glass-filled/engineering-grade materials like glass-filled nylon or
   PC/ABS blends common in automotive) — disclosed honestly as a
   moderate-confidence, reasoned industry rule of thumb, NOT a single
   formal-standard numeric threshold (unlike `cellworks.robotics`'s UN
   38.3 T6 13 kN ceiling, which IS a single formal standard's own
   cited number).
3. **Material-spec evidence catalog** (`moldworks.facts`) seeds two
   real, current, cited product-class schemes — AUTOMOTIVE (SAE
   J1545/J1885 color/appearance-durability, ASTM D638 tensile
   properties, ASTM D256 Izod impact resistance, ISO 3167 multipurpose
   test specimens) and CE-HOUSING (UL 94 mandatory flammability
   rating, ISO 294 injection-molded test specimens). A product class
   not in this table (e.g. medical-device housings) has NO spec-basis,
   never fabricated.
4. Entity is a **molding-run-batch** (a manufactured lot of injection-
   molded parts from one mold+material combination).
   `molding-run-batch-shrinkage-out-of-range?` continues the fleet's
   two-sided range-check family, applied to a batch's own measured
   shrinkage-rate deviation from its own molded material's rated
   shrinkage factor — a real post-mold dimensional-QA metric,
   independent of the physics-derived clamp-force check.
5. Dual actuation on the same entity, each with its own dedicated
   boolean guard (`:molding-run-batch-shipped?`/`:material-
   certified?`, never a status lifecycle — ADR-2607071320/6492
   lesson): `:actuation/ship-molding-run-batch` (the real dual
   hand-off to BOTH `cloud-itonami-isic-2630`'s consumer-electronics
   housing integration and `cloud-itonami-isic-2910`/`cloud-itonami-
   isic-2920`'s automotive plastics integration) and `:actuation/
   issue-material-certificate` (a Material Certificate of Compliance).
6. `moldworks.robotics`'s clamp-tonnage tolerance check is
   DELIBERATELY ONE-SIDED (only under-clamping is flagged) —
   insufficient clamp force is the real defect-risk direction for
   injection molding (mold-half separation under injection pressure →
   flash; starved holding pressure in remote cavities → short-shot),
   unlike `glassworks.robotics`'s two-sided flexural-strength
   acceptance band — a disclosed, deliberate asymmetry matching the
   real physics, not an oversight.
7. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2220` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2220` placeholder. A prior, less-rigorous
   `plasticsmfg.*` propose-only admin-ops-coordination stub had
   already been pushed to this same repo name by a concurrent session
   before this ADR's real-physics build landed; it was replaced via a
   normal PR + merge (no force-push, no history rewrite) rather than
   overwritten in place — see the repo's own PR #1.
8. **Real visual render proof**: the clamp-force-verification's actual
   simulated trajectory (moving mold-half + static mold-half rigid-
   body positions per tick) was rendered via `kami.webgpu.mesh`
   (fetched fresh from `kotoba-lang/webgpu`'s GitHub `main`) as two
   real frames (open/pre-contact, closed/clamped), driven headlessly
   via Playwright — see Verification for backend results and
   screenshot paths.

## Consequences

(+) The injection-molded-plastics manufacturing stage gains a
forkable OSS operating stack with auditable governor holds, closing a
gap common to BOTH the smartphone-assembly and vehicle-assembly value
chains at once — the same dual-downstream-hand-off shape
`cloud-itonami-isic-2720`/`cloud-itonami-isic-2310` established for
batteries and glass.
(+) Delivers a REAL time-stepped physics simulation (not a symbolic
comparison) as a native part of this actor's initial build, and
anchors its tolerance ceiling on a REAL, widely-cited plastics-
processing engineering heuristic rather than an arbitrary proxy
multiple — honestly disclosed as a moderate-confidence industry rule
of thumb, not a single formal-standard number.
(+) Genuine dual-downstream hand-off value: the same molding-run-
batch-shipment/material-certificate shape serves both `cloud-itonami-
isic-2630` and `cloud-itonami-isic-2910`/`cloud-itonami-isic-2920`
without this actor needing to know which downstream consumer a given
shipment goes to.
(−) `physics-2d` is a 2D projection with no material-stiffness/
deformation model; both mold-halves are approximated as flat-plate
AABBs (a disclosed simplification — `physics-2d`'s narrowphase has no
mixed AABB/circle collision support, though here both bodies are
genuinely flat parting-line faces, a closer match than some prior
siblings' own AABB approximations of curved/cylindrical geometry).
Unlike `cellworks.robotics`'s UN 38.3 T6 literal 50%-deformation
citation or `glassworks.robotics`'s measured brittle-fracture-
deflection estimate, this actor's `mold-approach-travel-m` has NO
literal-standard or measured-material anchor at all — a rigid
mold-clamp stop genuinely has no analogous deformation distance,
disclosed honestly as a weaker-anchored distance choice than either
sibling's own.
(−) Material-spec-scheme coverage is a starting catalog (2 product
classes), not exhaustive, and does not capture every product class an
injection-molding plant might produce (e.g. medical-device housings,
food-contact packaging).
(−) No molten-plastic rheology/fill/pack-hold simulation — `physics-
2d` has no such model at all; the clamp-force check is a mechanical-
force reading only, the same "policy, not control" boundary every
real-physics sibling in this fleet discloses.

## Verification

- `cloud-itonami-isic-2220`: `clojure -M:dev:test` green (45 tests /
  236 assertions), `clojure -M:lint` clean, `clojure -M:dev:run`
  completes with no exceptions and exercises every HARD hold
  (no-spec-basis, evidence-incomplete, robotics-simulation-missing,
  robotics-simulation-out-of-tolerance, molding-run-batch-shrinkage-
  out-of-range, end-of-line-defect-unresolved, already-shipped,
  already-certified).
- Real physics: batch-1 (automotive interior bracket, 120 in² × 7.0
  tons/in² = 840.0 tons required; 90,000 kg moving-platen assembly) →
  9,000,000 N / 1,011.64 tons peak clamp reading, clears the required
  840.0-ton tonnage with margin; batch-5 (same automotive part/
  material spec, deliberately undersized 40,000 kg moving-platen
  assembly — a genuine press-run-configuration mismatch) → 4,000,000 N
  / 449.62 tons, falls short of the 840.0-ton requirement — caught on
  the governor's independent recheck even though `:robotics-sim-
  verified?` was seeded `true` ("already on file").
- Real render: batch-1's actual simulated trajectory (moving mold-half
  + static mold-half positions, 28 ticks) was dumped to
  `scene-data.json` and rendered as box meshes via `kotoba-lang/
  webgpu`'s `kami.webgpu.mesh` executor (fetched fresh from GitHub
  `main`), driven headlessly via Playwright (nbb drivers). BOTH
  backends rendered successfully: real WebGPU (`navigator.gpu`/
  `requestAdapter`/`requestDevice` all succeeded against a real
  Metal-backed adapter) and the WebGL2 fallback (`navigator.gpu`
  deleted via `addInitScript`), with genuine `readPixels`/screenshot-
  decode proof that the rendered mold-half-a pixel (RGB(54,22,10)
  WebGL2 / RGB(76,31,13) WebGPU) genuinely differs from the background
  pixel (RGB(9,14,26) both backends). Both frames (open/tick-0,
  clamped/tick-27) show the moving mold-half visibly further from, then
  flush against, the static mold-half. Screenshots saved outside the
  actor repo (`/tmp/render-2220/plastics-clamp-render.png` /
  `-webgl2.png`), matching this session's established render-harness
  technique.
- Industry: maturity `"2220"` → `:implemented`
- GitHub public repo under `cloud-itonami/cloud-itonami-isic-2220`
