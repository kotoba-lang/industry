# ADR-2607161000: cloud-itonami isic-2219 rubber-products actor (rubberworks) → `:implemented`

- Status: Accepted (2026-07-15)
- Related: ADR-2607151600 (cloud-itonami ⟷ kami-engine-vehicle-designer
  real engineering-simulation integration, automotive pilot),
  ADR-2607152000 (real engineering-simulation integration — fleet
  extension)

## Context

`cloud-itonami-isic-2219` (manufacture of other rubber products) sat as
a `:maturity :spec` placeholder with a dead
`gftdcojp/cloud-itonami-C2219` URL — no repo, no business model, no
actor. This session has been building out a coherent supply web
feeding two chains — smartphone manufacturing
(`cloud-itonami-isic-2630`) and vehicle manufacturing
(`cloud-itonami-isic-2910`/`cloud-itonami-isic-2920`) — with shared
upstream materials stages already built for both: batteries
(`cloud-itonami-isic-2720`), glass (`cloud-itonami-isic-2310`),
plastics (`cloud-itonami-isic-2220`), wire/cable harnesses
(`cloud-itonami-isic-2732`), and optical/camera modules
(`cloud-itonami-isic-2670`). Molded/extruded rubber products are the
next natural shared stage: smartphone protective cases and internal
gaskets/seals (waterproofing gaskets around ports/buttons) AND
automotive rubber components (weatherstripping/door seals, hoses,
engine/body mounts, grommets) are BOTH fundamentally rubber-products-
manufacturing outputs — the same "missing shared upstream stage" gap
the prior materials actors closed for both chains. This vertical is
deliberately DISTINCT from `cloud-itonami-isic-2211` (rubber tyres,
already implemented separately, its own material-spec catalog and its
own governor) — this actor covers "other rubber products" (seals/
gaskets/hoses/mounts/cases), never tyres.

## Decision

1. **`cloud-itonami-isic-2219`** — Rubber Advisor ⊣ Compression-Seal
   Governor (rubber-part-batch intake, per-product-class material-spec
   rules verify, end-of-line quality screen, robot ASTM D395
   compression-set-test verification, dual actuation: ship-rubber-
   part-batch + issue-material-certificate, audit export). Entity is a
   **rubber-part-batch** (a manufactured lot of molded/extruded rubber
   parts of one compound/spec), not a finished device or vehicle.
2. Real-world material-spec basis: `rubberworks.facts` seeds
   AUTOMOTIVE-RUBBER (ASTM D2000 rubber-grade line-callout
   classification + ISO 1817 fluid-resistance) and ELECTRONICS-GASKET
   (IEC 60529 IP-rating + ASTM D395 compression-set) product-class
   schemes only — organized by product class, not per-country, the
   same honest structural shape `opticsworks.facts`/`moldworks.facts`
   use for their own conformance catalogs.
3. **Real physics-2d simulation delivered NATIVELY from day one**
   (extending ADR-2607151600/ADR-2607152000's real-engineering-
   simulation fleet pattern to a brand-new actor, not a retrofit):
   `rubberworks.robotics` runs an ACTUAL time-stepped `kotoba-lang/
   physics-2d` rigid-body simulation of the real ASTM D395 Method B
   compression-set test — a compression-platen `Body2D` closes at a
   controlled velocity onto a static rubber-specimen `Body2D` to the
   standard's own real 25% compression fraction; `:sim-peak-
   compression-force-n` is read directly off the actual simulated
   collision trajectory (F = m·a), never invented. The governor
   independently re-derives this reading against a real, disclosed
   TWO-SIDED reasoned-engineering-estimate compression-force band keyed
   by durometer (Shore A hardness) class: 50–300 N for soft, gasket-
   grade compounds (~Shore A 40–50); 300–1200 N for harder, structural-
   mount-grade compounds (~Shore A 70–90) — never trusting the
   mission's self-reported verdict.
4. Dual actuation on the rubber-part-batch entity, dedicated boolean
   double-actuation guards (`:rubber-part-batch-shipped?`/`:material-
   certified?`, never a status lifecycle, ADR-2607071320/6492
   discipline): `:actuation/ship-rubber-part-batch` (dispatches onward
   to `cloud-itonami-isic-2630` for smartphone protective-case/gasket
   compounds, or `cloud-itonami-isic-2910`/`cloud-itonami-isic-2920`
   for automotive weatherstripping/hose/mount compounds) and
   `:actuation/issue-material-certificate` (Rubber Compound Test
   Certificate: ASTM D2000/D395 conformance report).
5. `rubber-part-batch-durometer-deviation-out-of-range?` — a further
   instance of this fleet's two-sided ground-truth range-check family,
   on a batch's own measured durometer deviation from its own compound
   spec, independent of the physics-derived compression-force check
   (two DISTINCT fields, the same shape `automotive.governor`/
   `opticsworks.governor` use).
6. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2219` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2219` placeholder.

## Consequences

(+) The rubber-products manufacturing stage gains a forkable OSS
operating stack with auditable governor holds, closing a gap common to
BOTH the smartphone-assembly and vehicle-assembly value chains — the
same dual-downstream-hand-off shape `cloud-itonami-isic-2220`/
`cloud-itonami-isic-2720`/`cloud-itonami-isic-2310`/`cloud-itonami-
isic-2670` established.
(+) Delivers a REAL time-stepped physics simulation (not a symbolic
comparison) natively from day one, anchored on a disclosed, moderate-
confidence reasoned-engineering-estimate compression-force band by
durometer class (ASTM D395 itself specifies the test method, not a
required force range — this session did not fabricate one).
(+) Explicitly scoped away from `cloud-itonami-isic-2211` (rubber
tyres) — no tyre-specific citation appears anywhere in this actor.
(−) No physical plant digital-twin tick beyond the single compression-
set-test physics check (a stress-relaxation/creep-over-held-duration
simulation is out of scope — `physics-2d` has no viscoelastic material
model).
(−) Material-spec-scheme coverage is a starting catalog (2 product
classes), not exhaustive.
(−) `physics-2d` is a 2D projection with no material-stiffness/
deformation model; both rigid bodies are approximated as flat-plate
AABBs (a disclosed simplification, same as every real-physics sibling).

## Verification

- `cloud-itonami-isic-2219`: `clojure -M:dev:test` green (50 tests /
  257 assertions), `clojure -M:lint` clean, `clojure -M:dev:run`
  completes with no exceptions exercising every HARD-hold path
  including the genuinely-failing physics-derived over-/under-
  compression fixtures (batch-5: 5.0kg platen mass → 1600.0N, over its
  own [300,1200]N band; batch-6: 0.1kg platen mass → 32.0N, under its
  own [50,300]N band).
- Real, screenshotted WebGPU + WebGL2 render proof of the simulated
  compression-platen/rubber-specimen trajectory (`rubberworks.robotics/
  simulate-press`'s real per-tick trajectory, dumped via `clojure
  -M:dev:render-export` to `docs/samples/scene-data.json`), rendered
  via a minimal shadow-cljs app calling `kotoba-lang/webgpu`'s
  `kami.webgpu.mesh` executor, driven headless via Playwright (full
  Chromium WebGPU + a forced WebGL2 fallback), with pixel-readback
  confirmation that the platen/specimen/background pixels are
  mutually distinct in every captured frame.
- Industry: maturity `"2219"` → `:implemented`.
- GitHub public repo under `cloud-itonami/`.
