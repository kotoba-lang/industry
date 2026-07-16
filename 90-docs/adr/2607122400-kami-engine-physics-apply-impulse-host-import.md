# ADR-2607122400: implement the WASM guest ABI's kami:engine/physics apply-impulse host-import

- **Status**: accepted (2026-07-12)
- **Related**: ADR-2607122200 (kami-engine's physics-2d rigid-body-2d ECS
  integration — this ADR closes the "known gap" that one left open),
  ADR-2607121900 (raycast host binding, same `kami.host` file and same
  previously-a-no-op-stub pattern).

## Context

ADR-2607122200 wired `physics-2d.backend` into `kotoba-lang/host`'s `kami.host`
ECS via `kotoba.physics.contract`: a game (or, so far, only the *host app* —
`network-isekai`'s scene EDN) can `attach-rigid-body!` a `:physics/body`
component to an entity, and `step-rigid-bodies!` advances it every tick with
real impulse resolution. That ADR explicitly documented a "known gap": the
WASM guest ABI's own `kami:engine/physics@1.0.0` `apply-impulse` host-import
(`kotoba.engine-clj.ast` `:host-import/physics-apply-impulse`, declared since
ADR-2607121900) was a hardcoded no-op stub in `kami.host`'s `import-object`
(`physics #js {:apply-impulse (fn [_ _ _ _] nil)}`). A compiled `.kotoba`/
`.cljc` guest calling `(apply-impulse! eid ix iy iz)` would link and run
without erroring — but nothing would ever move. Only the host app could give
an entity a shove; a guest (gameplay logic) could not.

## Decision

1. **Add a portable `apply-impulse!`** (`kami.host`, `src/kami/host.cljc`,
   placed alongside `attach-rigid-body!`/`rigid-body-ids`/`step-rigid-bodies!`
   — outside the `#?(:cljs ...)` block, so it compiles identically on `:clj`
   and `:cljs` and is directly JVM-testable, mirroring why those functions
   were factored out that way in ADR-2607122200): `[state id ix iy] ->` looks
   up `(get-in @state [:rigid-body-2d :bodies id :mass])`, and if the id has a
   live entity and a positive mass, applies the standard impulse-momentum
   relation `Δv = J / m`, adding `ix/mass` to `:vx` and `iy/mass` to `:vy`.
   `iz` is dropped before reaching this function — `physics-2d` is XY-plane
   only, matching how `step-rigid-bodies!` already only touches x/y/vx/vy.
2. **`import-object`'s `:apply-impulse` now delegates to it**:
   `(fn [eid ix iy _iz] (apply-impulse! st (n eid) ix iy))` — one line, no
   behavior duplicated between the WASM-facing wrapper and the portable core.
3. **No-op policy for the "no rigid-body" case**: silently skip (return
   `nil`, don't throw) when the id has no live entity, no `:physics/body`
   component, or a static body (`:mass 0` — `physics-2d`'s own convention for
   "never integrated"; guarding it also avoids a divide-by-zero). This
   mirrors the existing convention `:set-position`/`:set-velocity` already
   use for a missing/despawned entity (`(when (ent st eid) ...)`) rather than
   throwing — a thrown host-import call would abort the guest's entire tick
   for what is, gameplay-wise, a harmless no-op shove against something that
   structurally can't be shoved (no mass to convert the impulse against).
4. **This is an external impulse source, not a new collision path.** The
   impulse-momentum relation this applies is exactly what
   `physics-2d.backend`'s realtime collision response already computes
   internally inside `step-rigid-bodies!` each tick; `apply-impulse!` adds a
   second, independent way to feed a velocity change into the same rigid
   body — "gameplay code says give this a shove" — not a duplicate resolver.
5. **Fixed a pre-existing, unrelated test bug found while verifying**:
   `test/kami/host_rigid_body_test.clj` required `[physics-2d :as engine]`
   (dash) — but `physics-2d`'s own top-level namespace is *literally*
   `physics_2d` (underscore; see that repo's own `src/physics_2d.cljc`'s
   `(ns physics_2d ...)` and its own `test/physics_2d_test.cljc`'s
   `[physics_2d :as p]`, both consistently underscored, an unconventional but
   internally-consistent choice in that repo). Requiring the dashed symbol
   made Clojure look for a *different* namespace object than the one the file
   actually declares (`namespace 'physics-2d' not found after loading
   '/physics_2d'`) — a syntax error that made this entire test file fail to
   compile. This means `clojure -M:test` in `kotoba-lang/host` was **not**
   actually green before this change, despite ADR-2607122200's verification
   section claiming "8 tests / 16 assertions, 0 failures" — that claim was
   evidently never actually re-checked against a from-scratch `deps.edn`
   resolution of `../physics-2d`. Fixed by requiring `[physics_2d :as
   engine]` (the one-character, obviously-correct fix matching what actually
   exists); left a code comment explaining the underscore convention so it
   doesn't get "corrected" back to the broken dashed form later.

## Honest cross-repo findings (this ADR's own verification-grounding)

- **`kotoba.engine-clj`'s codegen needed NO change.** Its host-import table
  (`src/kotoba/engine_clj/ast.cljc`) already declared
  `:host-import/physics-apply-impulse` with module-field
  `["kami:engine/physics@1.0.0" "apply-impulse"]`, param-kinds
  `[:i64 :f32 :f32 :f32]`, and return-kind `:void` — identical in shape and
  already-correct since whenever `raycast`/`apply-force` were added
  (ADR-2607121900). `codegen.cljc`'s host-import call emission
  (`emit-host-import-call`, `collect-host-imports`) is fully generic/
  table-driven over these three tables — there is no apply-impulse-specific
  code anywhere to be missing. Verified empirically, not just by reading:
  compiled `(defsystem shove [dt] (apply-impulse! 1 (f32 5.0) (f32 10.0) (f32
  0.0)))` via `kotoba.engine-clj/compile-str` in a throwaway REPL check and
  inspected the resulting Module IR's `:host-imports`, which contained
  exactly `{:kind :host-import/physics-apply-impulse, :module-field
  [kami:engine/physics@1.0.0 apply-impulse], :param-kinds [:i64 :f32 :f32
  :f32], :return-kind :void, :index 0}` — the correct import declaration a
  guest linking against `kami.host`'s `import-object` needs. No engine-clj
  commit was made (and, separately: **`kotoba-lang/engine-clj` is GitHub-
  archived/read-only as of 2026-07-03** — its own README states it was
  consolidated into `kotoba-lang/kami-engine`'s `kami-engine-clj/`
  subdirectory, but that subdirectory does **not** actually exist on
  `kotoba-lang/kami-engine`'s `main` as of this writing (verified via the
  GitHub contents API, 404) — an inconsistency this ADR flags but does not
  resolve, since no engine-clj-side change was needed here regardless).
- **`kotoba-lang/kototama` has zero involvement in this import path.**
  Grepped the whole repo for `apply-impulse`/`physics-apply-impulse`/
  `kami:engine`: the only hit is a string literal `"kami:engine"` used as a
  *negative* test fixture in `test/kototama/contract_test.cljc`
  (`wrong-abi-surface-fails-with-data`), asserting that kototama's own
  `actor:host` ABI validator correctly *rejects* an ABI surface tagged
  `kami:engine` (an unrelated namespace, used only to prove the validator
  checks `:abi/namespace`). This confirms — rather than merely repeats — the
  prior session's cursory grep finding: kototama's `actor:host` ABI (for
  atproto actors/organisms) and the game engine's `kami:engine` ABI are
  deliberately separate, unrelated systems that happen to share a design
  pattern (per that file's own comment, "Mirrors kami:engine"), not a shared
  implementation. No kototama commit was made or needed.

## Consequences

- A compiled `.kotoba`/`.cljc` guest can now author a real, physically
  correct impulse against its own (or any) rigid-body-2d entity through the
  WASM ABI, closing the gap ADR-2607122200 left open — gameplay code (not
  just the host app's scene EDN) can make an entity jump, get knocked back,
  launch a projectile-style shove, etc.
- `apply-force` (`:host-import/physics-apply-force`, also declared in the ABI
  since ADR-2607121900) remains a no-op — out of scope here, left for a
  future ADR if a concrete caller needs continuous force application (as
  opposed to an instantaneous impulse) rather than being guessed at.
- The pre-existing `physics-2d`/`physics_2d` namespace-name test bug is now
  fixed, so `kotoba-lang/host`'s `clojure -M:test` is verifiably green
  end-to-end for the first time (previously only appeared green because the
  claim in ADR-2607122200 was not independently re-verified).
- The `kotoba-lang/engine-clj` → `kami-engine/kami-engine-clj` migration
  inconsistency (archived repo says "moved", target subdirectory doesn't
  exist upstream) is flagged for whoever owns that migration to complete or
  correct; not resolved by this ADR.

## Verification

- `kotoba-lang/host`: `clojure -M:test` — 11 tests / 23 assertions, 0
  failures, 0 errors (up from 8 tests / 16 assertions — 3 new tests: exact
  Δv = J/m through the real production call path (`apply-impulse!`, the same
  function `import-object`'s `:apply-impulse` delegates to — the cljs-only
  `#js` wrapper itself can't be exercised on the JVM, same constraint
  ADR-2607122200 already worked around for `step-rigid-bodies!` etc.); no-op
  on an entity with no rigid-body component; no-op on a static body (mass 0)
  and on a despawned/unknown id). `clojure -M:lint` — 0 errors, same 3
  pre-existing unrelated warnings as before this change (unused
  `clojure.string`/`kotoba.physics`/`kami.gpu` requires — false positives of
  linting `:cljs`-only usages outside a cljs build, not introduced here).
- `kotoba.engine-clj` (archived repo, read-only): verified via a throwaway,
  uncommitted REPL check (see above) that the existing host-import ABI
  tables already compile `apply-impulse!` calls to the correct import
  declaration — no test was added there since no code change was made or
  could be pushed (archived).
- `kotoba-lang/kototama`: verified via `grep` only (read-only investigation,
  no code touched, no tests run/needed).

## References

- `orgs/kotoba-lang/host/src/kami/host.cljc` (`apply-impulse!`,
  `import-object`'s `physics` map's `:apply-impulse`)
- `orgs/kotoba-lang/host/test/kami/host_rigid_body_test.clj`
  (`apply-impulse-converts-impulse-to-velocity-delta-via-impulse-momentum`,
  `apply-impulse-is-a-no-op-on-an-entity-with-no-rigid-body`,
  `apply-impulse-is-a-no-op-on-a-static-body-and-on-a-despawned-id`, and the
  `[physics_2d :as engine]` namespace-name fix)
- `orgs/kotoba-lang/engine-clj/src/kotoba/engine_clj/ast.cljc`
  (`:host-import/physics-apply-impulse` and its module-field/param-kinds/
  return-kind entries — read, not modified; repo is GitHub-archived)
- `orgs/kotoba-lang/engine-clj/src/kotoba/engine_clj/codegen.cljc`
  (generic host-import call emission — read, not modified)
- `orgs/kotoba-lang/kototama/test/kototama/contract_test.cljc` (the
  unrelated `"kami:engine"` negative-test fixture — read, not modified)
- `orgs/kotoba-lang/physics-2d/src/physics_2d.cljc`,
  `orgs/kotoba-lang/physics-2d/src/physics_2d/backend.cljc` (mass/impulse
  semantics this ADR's `Δv = J/m` mirrors — read, not modified)
- ADR-2607122200 (`90-docs/adr/2607122200-kami-engine-physics-2d-rigid-body-integration.md`)
- ADR-2607121900 (raycast host binding — same file, same "declared-in-ABI-
  but-was-a-no-op-stub" pattern this ADR closes for apply-impulse)
