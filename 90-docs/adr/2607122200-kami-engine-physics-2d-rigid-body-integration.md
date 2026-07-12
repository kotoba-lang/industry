# ADR-2607122200: wire physics-2d into kami-engine's ECS via kotoba.physics.contract

- **Status**: accepted (2026-07-12)
- **Related**: ADR-2607102200 addenda 7-9 (kami-engine browser-host/physics SSoT
  extraction that produced the current `kotoba-lang/host` + `kotoba-lang/physics` split),
  ADR-2607121900 (raycast host binding, same `kami.host` file), kami-engine's own
  `kami-physics/` package (#100, `kami.physics.runtime` — a backend-id router, merged
  concurrently with this work).

## Context

`kotoba-lang/physics/src/kotoba/physics/contract.cljc` defines a shared, portable
`PhysicsBackend` protocol (`descriptor`/`step`/`solve`), fidelity tiers
`#{:realtime :reduced-order :high-fidelity}`, and an immutable SI-unit scene envelope
(`make-scene`/`make-case`/`make-result`). Three backends already implement it, each with
its own green test suite, but **none had any consumer anywhere in the monorepo** before
this change:

1. `kotoba-lang/physics-2d` — `physics-2d.backend/backend`, fidelity `:realtime`,
   capabilities `#{:rigid-body-2d :circle-collider :aabb-collider :impulse-resolution
   :collision-layers}`. A zero-dep 1:1 CLJC port of a deleted Rust crate: AABB + circle
   colliders, O(n²) broadphase/narrowphase, impulse resolution + positional correction.
   `(step backend scene dt-s)` advances one frame (`dt-s` in `(0, 0.25]` seconds);
   `(solve backend _)` always throws (realtime-only).
2. `kotoba-lang/kami-engine-cae-solver` — `cae.backend/backend`, fidelity
   `:high-fidelity`, capabilities covering CFD/FEM/process/materials/electromagnetics/
   production-simulation. `(solve backend case)` dispatches into `cae.industrial`'s
   reduced-order reference formulas; `(step backend _ _)` always throws (finite-solve
   only — an engineering calculator, not a frame integrator, and cannot replace
   physics-2d in a game loop).
3. `kotoba-lang/kami-engine-vphysics` — vehicle-specific road-load/aero math, out of
   scope for this ADR.

`kotoba-lang/kami-engine` itself has **zero live Rust ECS** (verified via its own
CLAUDE.md staleness notice: no `Cargo.toml`/`*.rs` files anywhere, CI enforces a
"no-rust guard"). The browser host every compiled kami game actually runs against is
`kotoba-lang/host`'s `kami.host`/`kotoba.host` (registered in kami-engine's own
`docs/adapter-registry.edn` as the `:browser-host` adapter under the `:clj-authoring`
contract) — the CLJS twin of the historical `kami-script-runtime` binding surface, same
`kami:engine/*` ABI vocabulary (scene/random/physics/input/render/audio/time). Its
`kami:engine/physics@1.0.0` import (`apply-impulse`) had always been a no-op stub — no
game could get real physics from it.

`network-isekai` (`gftdcojp/network-isekai`) already had a **separate, standalone**
`isekai.physics` bridge (`src/isekai/physics.cljc`, already merged, already tested) that
wires physics-2d through the same contract — but it powers a `window.__physics`
verification probe only, driven manually, with its own disjoint entity set. It is not
hooked into the game's actual ECS (`kami.host`'s `:ents`), so no game's renderer/guest
ever sees positions this bridge computes. This is the concrete gap the owner asked to
close: attach a real physics-2d rigid body to an entity, and have it advanced through
the shared contract each tick, feeding the actual game loop.

### Honest CAE cross-check finding (verification-test grounding)

`cae.industrial.cljc` (423 lines, read in full) was checked for any closed-form case
that genuinely overlaps physics-2d's actual domain (2D rigid-body collision/impulse
dynamics: momentum, restitution, gravity, positional correction). **None of its six
solvers do**: `:cfd` (steady duct/ventilation flow), `:fem` (static axial-bar/
cantilever-beam deformation, stress, first mode, Basquin fatigue — linear-elastic
*statics* and modal analysis, not moving-body dynamics), `:process` (welding/casting/
rolling heat & force), `:materials` (phase transformation), `:emag` (3-phase motor
power/torque), `:production-des` (discrete-event queueing). There is no free-body,
projectile, or rigid-body-collision case anywhere in that file. Forcing a mapping (e.g.
comparing a beam's static deflection to a bouncing ball) would be fabricated, not
grounded. The honest cross-check is therefore standard analytic mechanics computed
directly — Newton's law of impact (the *definition* of restitution), momentum
conservation, kinetic-energy conservation for an elastic collision, and the exact
closed-form symplectic-Euler trajectory under gravity — not cae.industrial.

## Decision

1. **Add a rigid-body-2d ECS system to `kami.host`** (`kotoba-lang/host`,
   `src/kami/host.cljc`): `attach-rigid-body!`/`detach-rigid-body!`/
   `set-rigid-body-gravity!`/`rigid-body-ids`/`step-rigid-bodies!`. A game attaches a
   `:physics/body` component (mass/restitution/friction/collider/trigger?) to an entity
   id; `step-rigid-bodies!` (called once per `tick!` frame) projects every tagged
   entity's `:x/:y/:vx/:vy` into `kotoba.physics.contract/make-scene`, calls
   `contract/step` with `physics-2d.backend/backend`, and writes the resulting
   positions/velocities back onto the ECS `:ents` map. This goes through the shared
   contract, not a bespoke ad-hoc physics loop — mirroring the existing ad-hoc
   `:platformer` gravity/collision convention (same "host app hands the state a config
   map, `tick!` applies it" shape) but delegating the actual physics to physics-2d
   instead of hand-rolled gravity/support-top code.
2. **`tick!`'s ad-hoc gravity/plain-integration pass and `resolve-collisions!`'s layer
   push-apart both exclude rigid-body-2d ids** (`rigid-body-ids state`), so the two
   physics systems never double-drive the same entity — disjoint entity sets, both
   opt-in, chosen per-entity by which component it carries.
3. **`network-isekai` forwards a new `:rigid-body-2d` scene key** the same one line
   `:platformer` already uses (`isekai.game/wire-input!`): `(when-let [rb
   (:rigid-body-2d scene)] (swap! st assoc :rigid-body-2d rb))`. A code comment
   disambiguates this from the pre-existing `isekai.physics`/`window.__physics`
   verification-only bridge (different, disjoint entity set).
4. **`kami-engine`'s own docs** (`CLAUDE.md`) get a pointer to this wiring, since the
   repo's actual live code for it lives in `kotoba-lang/host`, not in `kami-engine`
   itself — and a cross-reference to kami-engine's own `kami-physics/`
   (`kami.physics.runtime`, PR #100, merged concurrently): that package is a
   backend-id **router** with no ECS of its own (dispatches `{rigid-2d, vehicle, cae}`
   by id for tools/pipelines); `kami.host`'s rigid-body-2d system talks to
   `physics-2d.backend` directly instead, so a browser game bundle never pulls in the
   vehicle/CAE backends it doesn't need.
5. **Verification tests ground physics-2d in analytic mechanics, not cae.industrial**
   (`kotoba-lang/physics-2d`, new test file), per the honest finding above: exact
   closed-form symplectic-Euler trajectory under gravity (derived from the integrator's
   own update rule) + O(dt) convergence check; Newton's law of impact for e=1/0.5/0;
   momentum conservation across a binary collision for any restitution; kinetic-energy
   conservation (e=1) / strict dissipation (e=0); and a free-fall-then-bounce chained
   end-to-end through impact-speed (v=√2gh) and rebound-height (physics-2d's combined,
   geometric-mean restitution model) checks — all exercised through the real production
   path (`contract/step` + `physics-2d.backend/backend`), not the bare engine.

## Consequences

- A game/scene authored against `kotoba-lang/host` can now attach real rigid-body
  physics to any entity and get correct impulse-resolved collisions, gravity, and
  positional correction each frame, without hand-writing a physics loop.
- `physics-2d.backend` now has two real consumers: `kami.host`'s rigid-body-2d ECS
  system (this ADR, gameplay-affecting) and `isekai.physics` (pre-existing,
  verification-probe only, unaffected/untouched by this change).
- `kami-engine-cae-solver` remains entirely unconsumed by this integration path — the
  honest finding is that its domain (static structural/CFD/process/materials/emag/DES
  screening) has no genuine overlap with realtime rigid-body dynamics. No code in
  `kami-engine-cae-solver` was changed.
- `kami-engine-vphysics` was left untouched (vehicle-specific, out of scope).
- Known gap: the WASM guest ABI's own `kami:engine/physics@1.0.0/apply-impulse` import
  stays a no-op (unchanged by this ADR) — a compiled `.kotoba`/`.cljc` guest cannot
  itself author a rigid-body component through the wasm boundary; only the *host app*
  (network-isekai's scene EDN, or any future `kami.host` consumer) can, via
  `attach-rigid-body!`/the `:rigid-body-2d` scene key. Extending the guest ABI itself is
  a larger, separate change (would need `kotoba.engine-clj`'s host-import ABI +
  `kototama`'s codegen updated in lockstep) and is out of scope here.

## Verification

- `kotoba-lang/physics-2d`: `clojure -M:test` — 9 tests / 24 assertions, 0 failures, 0
  lint errors (`clojure -M:lint`). New file:
  `test/physics_2d/analytic_mechanics_verification_test.cljc`.
- `kotoba-lang/host`: `clojure -M:test` — 8 tests / 16 assertions, 0 failures, 0 lint
  errors. New file: `test/kami/host_rigid_body_test.clj` (exercises attach/detach,
  free-fall integration, and a momentum-conserving collision through the ECS
  write-back path — runs on the JVM with no WebAssembly instance, since this half of
  `kami.host` is plain atom ops + `contract/step`).
- `network-isekai`: single-line, pattern-mirrored change (`isekai.game.cljc`); verified
  by structural parity with the already-working `:platformer` forwarding line and a
  clj-kondo syntax pass (no parse errors; the two pre-existing `exists?`/`array-seq`
  "errors" from an isolated single-file lint run are cljs-core false positives of
  linting outside the project's own shadow-cljs config, not new issues — this repo has
  no standalone `:test`/`:lint` alias to run in-project).
- `kami-engine`: doc-only change (`CLAUDE.md`); no test surface.

## References

- `orgs/kotoba-lang/physics/src/kotoba/physics/contract.cljc`
- `orgs/kotoba-lang/physics-2d/src/physics_2d/backend.cljc`,
  `orgs/kotoba-lang/physics-2d/src/physics_2d.cljc`
- `orgs/kotoba-lang/physics-2d/test/physics_2d/analytic_mechanics_verification_test.cljc`
- `orgs/kotoba-lang/host/src/kami/host.cljc` (`attach-rigid-body!`, `detach-rigid-body!`,
  `set-rigid-body-gravity!`, `rigid-body-ids`, `step-rigid-bodies!`, `tick!`,
  `resolve-collisions!`)
- `orgs/kotoba-lang/host/test/kami/host_rigid_body_test.clj`
- `orgs/kotoba-lang/kami-engine-cae-solver/src/cae/industrial.cljc` (read in full for
  the honest no-overlap finding)
- `orgs/kotoba-lang/kami-engine/CLAUDE.md` (browser host physics section)
- `orgs/kotoba-lang/kami-engine/kami-physics/src/kami/physics/runtime.cljc` (#100,
  related backend router)
- `orgs/gftdcojp/network-isekai/src/isekai/game.cljc` (`:rigid-body-2d` forwarding,
  `wire-input!`), `orgs/gftdcojp/network-isekai/src/isekai/physics.cljc` (pre-existing,
  unaffected verification-only bridge)
