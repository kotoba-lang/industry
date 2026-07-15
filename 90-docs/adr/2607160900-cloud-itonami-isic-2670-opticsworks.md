# ADR-2607160900: cloud-itonami isic-2670 optical instruments / photographic equipment (Optics Advisor ⊣ Module-Seating Governor) → `:implemented`

- Status: Accepted (2026-07-15)
- Related: ADR-2607151600 (real engineering-simulation integration,
  automotive pilot), ADR-2607152000 (real engineering-simulation fleet
  extension), ADR-2607111600 (isic-2910 motor-vehicle promotion —
  sibling architecture this repo mirrors), ADR-2607160700 (isic-2220
  plastics — closest recent sibling, including the identical
  concurrent-stub-then-PR-replacement precedent this ADR follows)
- Supersedes (description only, not registry mechanics): the
  descriptive text of ADR-2607162300 (a concurrent session's earlier
  registry promotion of this same ISIC code), whose "OpticalInstrAdvisor
  / Optical Instrument Plant Operations Governor" four-op, non-physics
  stub was wholesale replaced by this ADR's build — see Context.

## Context

This session has been building out a coherent supply web feeding two
manufacturing chains — smartphone manufacturing (`cloud-itonami-isic-
2630`) and vehicle manufacturing (`cloud-itonami-isic-2910`/
`cloud-itonami-isic-2920`) — with shared upstream materials stages
already closed for both: batteries (`cloud-itonami-isic-2720`), glass
(`cloud-itonami-isic-2310`), plastics (`cloud-itonami-isic-2220`), and
wire/cable harnesses (`cloud-itonami-isic-2732`). Optical/camera-
module manufacturing is the next natural shared stage: smartphone
camera modules (a huge, real, dedicated smartphone-component category)
and automotive backup-cameras/ADAS optical sensor modules/LIDAR
housings are BOTH fundamentally optical-instrument-manufacturing
outputs — a gap common to two value chains at once, not a niche
single-vertical gap.

`kotoba-lang/industry`'s registry entry `:id "2670"` had already been
touched by a CONCURRENT session before this build began: it pushed a
simple, non-physics `opticalmfg.*` propose-only "plant operations
coordination" stub (`OpticalInstrAdvisor` ⊣ `Optical Instrument Plant
Operations Governor`, four ops: `:log-production-batch`/
`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`,
77 tests / 211 assertions) to `cloud-itonami/cloud-itonami-isic-2670`
and promoted the registry entry `:spec` → `:implemented` accordingly
(its own ADR-2607162300). This is the SAME race condition already
documented for `cloud-itonami-isic-2220` (ADR-2607160700, item 7): a
less-rigorous stub landed on the same repo name from a different
concurrent session before this session's real-physics build could
land. Per that same precedent, this ADR's implementation replaces the
stub via a normal PR + merge (no force-push, no history rewrite) —
see the repo's own PR #1 (merge commit `e6566472683aebc0442b89a41b0d5f54cb9e5b8a`).
ADR-2607162300's registry-promotion mechanics (repo URL, business-id)
remain correct (same repo); only its DESCRIPTIVE text (op list, test
counts, governor shape) is now stale, superseded by this ADR — see
Verification for the corrected registry-entry text to apply.

## Decision

1. **`cloud-itonami-isic-2670`** — Optics Advisor ⊣ Module-Seating
   Governor, `langgraph-clj` StateGraph, append-only audit ledger.
   Namespaces live under `opticsworks.*` with the standard facts/
   registry/store/governor/phase/opticsadvisor/operation/sim/robotics/
   export shape, mirroring `cloud-itonami-isic-2220`/`cloud-itonami-
   isic-2732`'s own structure exactly (`MemStore`/`DatomicStore` dual
   backend proven by a shared contract test, dedicated boolean
   double-actuation guards, honest facts catalog).
2. **Native real-physics simulation from day one**
   (ADR-2607151600/ADR-2607152000 pattern): `opticsworks.robotics` runs
   an ACTUAL time-stepped `kotoba-lang/physics-2d` rigid-body
   simulation of a lens-barrel press-fit seating test — a press-tool
   `Body2D` closes at a controlled velocity onto a static lens-housing
   `Body2D`. A real `:sim-peak-seating-force-n` is read directly off
   the simulated collision (F = m·a, the same derivation technique
   `moldworks.robotics`/`harnessworks.robotics`/`commsdevice.robotics`
   use), checked against a two-sided tolerance band per product class
   (smartphone consumer optics `[1, 20]` N, ruggedized automotive
   optics `[5, 60]` N) — disclosed HONESTLY as a reasoned engineering
   estimate, not a verbatim single-standard numeric table:

   > "HONEST CONFIDENCE DISCLOSURE: this band is a REASONED ENGINEERING
   > ESTIMATE informed by commonly-cited compact-camera-module
   > lens-seating/press-fit assembly forces (roughly low-single-digit-
   > to-tens of newtons for consumer smartphone optics, plausibly
   > higher — tens of newtons — for ruggedized automotive ADAS-grade
   > housings), NOT a verbatim transcription of a single formal
   > standard's numeric table, the SAME moderate-confidence disclosure
   > discipline `moldworks.robotics`'s cavity-pressure-factor bands and
   > `commsdevice.robotics`'s bonding-pressure band use for their own
   > reasoned-estimate figures."
3. **Evidence catalog** (`opticsworks.facts`) seeds two real, current,
   cited product-class schemes — SMARTPHONE-CAMERA (ISO 12233
   resolution/MTF test method; IEC 60825-1 laser-product safety
   classification, applicable because smartphone camera modules
   commonly integrate a laser autofocus/ToF emitter) and
   AUTOMOTIVE-ADAS (ISO 26262 functional-safety framework for ADAS
   sensor-module qualification; ISO 20653 defining the IP6K9K
   ingress-protection rating for exterior camera/optical-sensor
   housings). SAE J3088 is disclosed as a low-confidence, unconfident
   supplementary citation and deliberately held OUT of
   `:required-evidence`, so the governor's evidence gate never depends
   on an unverified citation. A product class not in this table has no
   spec-basis, never fabricated.
4. Entity is an **optical-module-batch** (a manufactured lot of
   camera/optical modules of one spec). The ground-truth range check —
   this fleet's two-sided range-check family — is **focus-back-distance
   deviation** (µm), band `[-3.0, 3.0]` µm, independent of the
   physics-derived seating-force check.
5. Dual actuation on the same entity, each with its own dedicated
   boolean guard (never a status lifecycle), each with its own
   append-only history and jurisdiction-scoped sequence number:
   `:actuation/ship-optical-module-batch` (the real dual hand-off to
   BOTH `cloud-itonami-isic-2630`'s smartphone camera-module
   integration and `cloud-itonami-isic-2910`/`cloud-itonami-isic-2920`'s
   automotive optical-sensor/ADAS-camera integration) and
   `:actuation/issue-optical-certificate` (a real Optical Module Test
   Certificate — resolution/MTF test report + safety compliance,
   required before shipment).
6. `io.github.kotoba-lang/physics-2d` taken as a real pinned
   git-coordinate dependency (`sha 03b6c23c8d9aa36b92a8130fd3e37fc2548b4d26`),
   not `:local/root` — this vertical has no design-library sibling
   repo, so the physics module is built directly in
   `opticsworks.robotics`, mirroring `moldworks.robotics`'s /
   `harnessworks.robotics`'s own dependency shape exactly.
7. A prior, less-rigorous `opticalmfg.*` propose-only admin-ops-
   coordination stub had already been pushed to this same repo name by
   a concurrent session before this ADR's real-physics build landed
   (its own ADR-2607162300); it was replaced via a normal PR + merge
   (no force-push, no history rewrite) rather than overwritten in
   place — see the repo's own PR #1
   (`https://github.com/cloud-itonami/cloud-itonami-isic-2670/pull/1`,
   merge commit `e6566472683aebc0442b89a41b0d5f54cb9e5b8a`), the exact
   same resolution `cloud-itonami-isic-2220` used for its own
   concurrent-stub collision (ADR-2607160700, item 7).
8. **Real visual render proof**: the lens-seating simulation's actual
   simulated trajectory (press-tool + lens-housing rigid-body
   positions, 26 ticks) was dumped to `scene-data.json` and rendered as
   box meshes via `kotoba-lang/webgpu`'s `kami.webgpu.mesh` executor
   (fetched fresh from GitHub `main`), driven headlessly via Playwright
   (nbb drivers) — see Verification for backend results and screenshot
   paths.

## Consequences

(+) The optical/camera-module manufacturing stage gains a forkable OSS
operating stack with auditable governor holds, closing a gap common to
BOTH the smartphone-assembly and vehicle-assembly value chains at
once — the same dual-downstream-hand-off shape `cloud-itonami-isic-
2720`/`cloud-itonami-isic-2310`/`cloud-itonami-isic-2220`/
`cloud-itonami-isic-2732` established.
(+) Delivers a REAL time-stepped physics simulation (not a symbolic
comparison) as this actor's FINAL build state, replacing a stub that
had none — anchors its tolerance band honestly as a reasoned
engineering estimate rather than a fabricated single-standard number.
(+) Genuine dual-downstream hand-off value: the same optical-module-
batch-shipment/certificate shape serves both `cloud-itonami-isic-2630`
and `cloud-itonami-isic-2910`/`cloud-itonami-isic-2920` without this
actor needing to know which downstream consumer a given shipment goes
to.
(−) `physics-2d` is a 2D projection with no material-stiffness/optical-
coating/adhesive-cure model; both the press-tool and lens-barrel/
housing are approximated as AABBs (a disclosed simplification, the
same "policy, not control" boundary every real-physics sibling in this
fleet discloses).
(−) The SAE J3088 citation's exact scope/title could not be verified
without live web access — disclosed honestly as an unconfident
supplementary citation, deliberately excluded from the governor's
evidence gate rather than presented as certain.
(−) Facts catalog seeds two product classes only (SMARTPHONE-CAMERA,
AUTOMOTIVE-ADAS), not exhaustive optical-instrument-manufacturing
coverage (e.g. binoculars, microscopes, telescopes, projectors — the
broader ISIC 2670 class — are out of scope for this actor's evidence
catalog, which focuses on the two camera/optical-sensor-module product
classes this session's supply web actually needs).
(−) `kotoba-lang/industry` registry's current descriptive text for
`:id "2670"` (from the concurrent session's ADR-2607162300) is now
stale — it describes the superseded `opticalmfg.*` stub, not this
ADR's `opticsworks.*` implementation. The `:repo`/`:business-id` URLs
remain correct; only the comment/test-counts/`:required-technologies`
need a follow-up registry-owner edit (out of scope for direct edit by
this session — registry.edn is under active contention from many
concurrent sessions).

## Verification

- `cloud-itonami-isic-2670`: `clojure -M:dev:test` green (48 tests /
  247 assertions, 0 failures / 0 errors), `clojure -M:lint` clean (0
  errors / 0 warnings), `clojure -M:dev:run` completes with no
  exceptions and exercises every HARD hold (`no-spec-basis`,
  `robotics-simulation-missing`, `optical-module-batch-focus-back-
  distance-out-of-range`, `robotics-simulation-out-of-tolerance`
  over-pressed AND under-pressed, `end-of-line-defect-unresolved`,
  `already-shipped`, `already-certified`).
- Real physics (all values read directly off the simulated
  `physics-2d` collision, F = m·a, independent of the deliberately
  varied press-tool mass): batch-1 (smartphone camera module, 25 g
  press mass) → **2.0 N**, clears the `[1, 20]` N consumer-grade band;
  batch-3 (automotive optical-sensor module, 400 g press mass) →
  **32.0 N**, clears the `[5, 60]` N ruggedized-grade band; batch-5
  (automotive, deliberately misconfigured 1000 g press mass) →
  **80.0 N**, exceeds the 60 N ceiling — caught on the governor's
  independent recheck (over-pressed / crack-risk direction); batch-6
  (smartphone, deliberately misconfigured 5 g press mass) → **0.4 N**,
  falls short of the 1 N floor — caught (under-pressed / loose-lens
  direction). Ground truth: batch-3's focus-back-distance deviation
  (4.2 µm) exceeds the `[-3.0, 3.0]` µm band — caught independently of
  the physics check.
- Real render: batch-1's actual simulated trajectory (press-tool +
  lens-housing positions, 26 ticks) was dumped to `scene-data.json`
  and rendered as box meshes via `kotoba-lang/webgpu`'s
  `kami.webgpu.mesh` executor (fetched fresh from GitHub `main`),
  driven headlessly via Playwright (nbb drivers). BOTH backends
  rendered successfully: real WebGPU (`actualBackend: "webgpu"`) and
  the WebGL2 fallback (`navigator.gpu` deleted via `addInitScript`,
  `actualBackend: "webgl2"`), with genuine screenshot-decode proof
  that the rendered press-tool pixel (RGBA `[145,51,26,255]`) and
  lens-housing pixel (RGBA `[34,94,145,255]`) both genuinely differ
  from the background pixel (RGBA `[9,14,26,255]`), identically on
  both backends. Frame 0 shows a visible ~0.6 mm gap between the two
  bodies (physically correct — the press-tool starts at x=-0.0046 m,
  the contact plane is at x=-0.002 m); the seated frame (frame 25)
  shows them flush/adjacent exactly where the real simulated
  trajectory predicts contact (press-tool converges to x=-0.004 m).
  Screenshots saved outside the actor repo:
  `/tmp/render-2670/optics-seating-render.png` (WebGPU, seated frame,
  2833 bytes), `/tmp/render-2670/optics-seating-render-frame0.png`
  (WebGPU, first frame), `/tmp/render-2670/optics-seating-render-
  webgl2.png` (WebGL2, seated frame),
  `/tmp/render-2670/optics-seating-render-webgl2-frame0.png` (WebGL2,
  first frame) — matching this session's established render-harness
  technique.
- Industry: `"2670"` already shows `:implemented` (promoted by the
  concurrent session's ADR-2607162300); this ADR's PR merge means that
  entry's descriptive comment/test-counts/`:required-technologies` are
  now stale and need a follow-up registry-owner edit — corrected EDN
  text supplied alongside this ADR's landing report.
- GitHub public repo under `cloud-itonami/cloud-itonami-isic-2670`,
  PR #1 merged, merge commit `e6566472683aebc0442b89a41b0d5f54cb9e5b8a`.
