# ADR-2607010930: Rust→Clojure/WGSL migration (ambitious scope; hot loop in WGSL, CLJ authors + dispatches)

**Status**: proposed
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki

## Context

By line count the superproject is still ~82% Rust (kotoba-v2025 excluded,
~477k Rust lines vs ~103k CLJ/CLJC/CLJS/EDN). This is **by design**, not
neglect: `kotoba` `ADR-clojure-wasm` / `ADR-safe-capability-language` and
`kami-engine` `ADR-0040/0042` codify a **three-layer split — EDN description +
CLJ→WASM behaviour + native-Rust hot core** — and the hot core (wgpu render,
physics solve, audio mix, ECS, crypto, libp2p, WASM codegen/host, content-
addressed storage) has no Clojure substitute. "CLJ everywhere" is already
operational: `kotoba-clj` / `kami-engine-clj` compile a Clojure subset to real
WASM; `logic.clj` / `author.clj`, the mangaka suite, and the SIP game app run
on every target including iOS/console via the `wasmi` no-JIT backend.

The owner's directive is **more ambitious than "respect the substrate
boundary"**: bring parts of the hot core itself into Clojure/WGSL —
specifically physics-solver pure-compute kernels and the audio mixer — while
re-examining the iOS/console `wasmi` no-JIT constraint.

## Decision

The ambition is realised by a single architectural rule, not by porting hot
loops into CLJ-WASM:

> **The per-element hot loop runs on GPU hardware as WGSL compute. CLJ only
> authors (at edit time) and dispatches (one coarse-grained call per system
> per frame). The per-element loop never runs in CLJ-WASM.**

This is the only resolution to the `wasmi` no-JIT constraint: a per-particle
or per-sample CLJ loop interpreted by `wasmi` on iOS/console is orders of
magnitude too slow. By keeping the inner loop in WGSL (hardware, not
interpreted) and CLJ dispatch coarse-grained (one `-tick` per system per
frame, exactly the existing `kami-script-runtime` contract), the no-JIT path
is never on the hot loop.

### Five-class crate ledger

Every Rust crate in the two big workspaces (`kotoba` 36+3+1, `kami-engine`
103) plus the small standalone Rust repos is classified in
`90-docs/migration/clj-wgsl-ledger.edn`:

- `:stay-Rust-substrate` — compiler/host/crypto/net/storage-hot (out-of-scope below)
- `:migrate-out` — `kotoba-kotodama` non-substrate domain code → etzhayyim/gftdcojp/murakumo
- `:port-to-CLJC-domain-interpreter` — data-heavy walkers (FSM, mixer graph, behaviour-tree, camera rigs, input maps) the `kotoba-clj` PRELUDE already compiles (`keystone_domains.rs` green)
- `:port-to-WGSL-compute` — per-element hot kernels (physics solvers, audio DSP, postfx) authored as `kami.wgsl` data, dispatched by wgpu
- `:org-transfer` — generic `-clj` infra repos `com-junkawasaki → kotoba-lang` (ADR-2606302300; ownership move, not a language port)

### Phases

- **Phase 0** (this ADR): ledger + guardrails — `verify-clj-everywhere.sh`
  gains a WGSL-authoring-parity surface (regenerate-from-CLJC + clean-tree
  guard + `bb wgsl-parity --strict`); `kotoba-boundary-audit.bb` gains
  `--clj-wgsl` mode classifying every `include_str!(".wgsl")` against the
  `:wgsl-ownership` map seeded in `manifest/kotoba-boundaries.edn`.
- **Phase 1**: boundary-respecting wins — `kotoba-kotodama` migrate-out;
  data-heavy domain interpreters → CLJC; `kami-webgpu` fixture single-source;
  `kototama` CLJC contract; `-clj` infra → `kotoba-lang` org transfer.
- **Phase 2** (ambitious): `kami.wgsl` gains `@compute` workgroup support;
  the not-yet-built 5-solver WGSL (rigid/MPM/SPH/FEM/PBD) is authored from
  CLJC (greenfield — `kami-physics-solvers` is an 18-line stub today; the
  reference kernels live in `kami-genesis`); `kami.binaural` `emit :wgsl`
  (offline bounce); postfx → `kami.wgsl` data; ECS iteration → CLJ
  `defsystem` (declare + dispatch, never per-entity iterate).
- **Phase 3**: extend golden-frame determinism to WGSL-authored compute
  (buffer-hash across wgpu native + web + CPU reference); new
  `.github/workflows/clj-wgsl-parity.yml` runs `west update` →
  `gen-west-manifest --check` → `verify-clj-everywhere.sh` → boundary-audit
  → `parity` (wasmtime↔wasmi) → `kami-script-runtime --features backend-wasmi`
  build (the no-JIT invariant gate); a `wasmi-dispatch-budget` test
  structurally enforces coarse-grained CLJ dispatch.

### Premise corrections surfaced during exploration

- `kami-physics-solvers` is an **18-line stub** (`SOLVERS` const only); the
  5-solver WGSL compute is **not yet built**. The real physics + WGSL today
  lives in `kami-genesis` (`mpm.rs`, `cartpole.rs`, `double_pendulum.rs`,
  `planar_chain.rs` + `kami-genesis/src/wgsl/*_step.wgsl`). Phase 2.1 is
  greenfield CLJC authoring, not a port of existing WGSL.
- The Rust repos live under `orgs/kotoba-lang/`, not `orgs/com-junkawasaki/`
  (the latter holds older CLJC siblings). `kotoba-lang/kotoba-lang` is a
  pure-spec repo with no `deps.edn` — not a runnable Clojure target.
- Superproject CI runs no cargo/clojure/`--check` today (CodeQL + JupyterLite
  only). Phase 3 closes the gap.

## Out-of-scope (stay-Rust, hard boundary)

The ambition does not reach here — each is substrate with no Clojure
substitute, or self-reference:

- `kotoba-crypto` (constant-time, `#![deny(unsafe_code)]`)
- `kotoba-net` (libp2p), `kotoba-store`/ProllyTree/CID (content-addressed storage hot path)
- `kotoba-clj` + `kami-engine-clj` (the compilers — self-reference)
- `kotoba-runtime` + `kami-script-runtime` (WASM host = substrate)
- `kotoba-edn` (SSoT EDN reader — porting is circular)
- `kami-webgpu-rs` + `kami-render` wgpu command recorder itself (what-to-record → EDN; recorder stays Rust)
- `kami-genesis` Isaac Sim/PhysX facade (vendor API)
- `hecs` ECS storage (60 fps tick is native)
- `aiueos` (capability-secure WASM OS supervisor — cannot be a confined component)

## Consequences

- The "still 82% Rust" figure stays high by design; the metric that moves is
  **how much of the hot loop is CLJC-authored WGSL vs hand-written Rust
  WGSL**, tracked by the `--clj-wgsl` audit (`:cljc-authored` count rises,
  `:port-target` falls as items port).
- A new invariant enters CI: `kami-script-runtime --features backend-wasmi`
  must build and the dispatch budget must stay coarse-grained. Any host
  import the `wasmi` backend can't bind fails here.
- The `kami.wgsl` compute-stage extension (`@compute` + `@workgroup_size`) is
  the keystone dependency for all of Phase 2; its s-expr→WGSL lowering must
  grow storage-buffer indexing and workgroup-id, with a raw-WGSL-string
  escape hatch (`:wgsl/body "raw wgsl"`) for complex kernels.

## Post-Phase-3 premise correction (2026-07-01, post-completion survey)

The original Out-of-scope list (above) assumed a live Rust substrate in
`kotoba-lang/kotoba` and `kotoba-lang/kami-engine`. **That Rust is gone.**
A post-completion survey (Phase 3.1/3.3 + two read-only subagent sweeps)
found:

- `kotoba-lang/kotoba` removed its entire legacy Rust workspace in
  `604896171b "Remove legacy Rust workspace (#259)"` (2026-07-01). The
  `kotoba-llm` Rust crate (and its `&str` WGSL shaders) is git-history-only;
  the LLM stack now lives CLJC-first in `kotoba-lang/{torch, num, inference}`
  with its own `num.wgsl` emitter — so the ledger's "Phase 2b LLM shader
  authoring via `kami.wgsl`" is **MOOT/superseded** (re-scope to `num.wgsl`
  if ever desired).
- `kotoba-lang/kami-engine` has **zero `.rs`/`Cargo.toml`**; CI enforces a
  no-rust guard ("ensure Rust runtime files stay out"). 61 of the ledger's
  103 kami-engine crates were deleted. Of those, a handful were migrated to
  sibling CLJC repos (`kami-cad`→`cad`, `kami-eda`→`eda`, `kami-cae`→`cae-solver`),
  but ~58 were **deleted without a CLJC home**. There is no separate Rust
  workspace repo anywhere (com-junkawasaki / kotoba-lang / etzhayyim) hosting
  the executor trio (`kami-webgpu-rs`/`kami-render`/`kami-script-runtime`) —
  they are gone, not relocated.

This makes the original Out-of-scope "stay-Rust" boundary **partly stale**:
the named Rust substrate no longer exists in-manifest. The *principle*
(hot loop in WGSL; CLJ authors + dispatches; compilers/hosts/crypto/net stay
native substrate) still holds — but the concrete "stay-Rust crate" list now
describes historical state, not current inventory.

## Phase 4 — restore the deleted-not-migrated crates as kotoba-lang CLJC repos (2026-07-01)

To give the ~58 deleted-not-migrated crates a CLJC home, **63 new
`kotoba-lang/{repo}` repos were scaffolded** (zero-dep `.cljc`: `deps.edn` +
README + `.gitignore` + src/test skeleton). These are scaffold-only — the
CLJC restoration (contracts / data interpreters / EDN IR) is pending per-repo.
The set:

- EDA/CAD/CAE domains (no home yet): `dft` `spice` `pdk` `pnr` `verify`
  `mine-ai` `mine-pds` `bim` `rtl` `power` `si` `pkg` `yield` `ip` `flow`
- Engine/render/game domains: `core` `scene` `scene-graph` `render`(=engine-render)
  `game` `cam` `input` `skeleton` `tilemap` `voxel` `sdf` `mesher` `nerf`
  `gltf` `vrm` `postfx` `audio` `dec` `terrain` `vegetation` `atmosphere`
  `physics-2d` `ui-gpu` `graph` `geo` `os` `rtc` `bridge` `knp` `scad`
  `pathfind` `character` `cartpole-wasm` `articulated-scene` `demo` `devtools`
  `pipelines` `app`(=builder SDK)
- Engine-family (kept `kami-engine-*` prefix): `kami-engine-core` `-io`
  `-render` `-web` `-script-runtime` `-engine`
- App-family (kept `kami-app-*` prefix): `kami-app` `kami-app-isekai`
  `kami-app-quarry-walk`

The `kami-engine-script-runtime` repo is the future home of the wasmi no-JIT
build gate (Phase 3.3's dead CI step revives once this repo hosts the
backend-wasmi contract). Manifest registration (repos.edn `:extra-projects`
+ west.yml) is pending.

## Related

- `90-docs/migration/clj-wgsl-ledger.edn` (the crate-by-crate ledger, SSoT for this migration)
- `manifest/kotoba-boundaries.edn` `:wgsl-ownership` (shader ownership map)
- `scripts/verify-clj-everywhere.sh` (WGSL-parity surface)
- `scripts/kotoba-boundary-audit.bb --clj-wgsl` (WGSL ownership audit)
- `90-docs/adr/2606241700-kotoba-clj-runtime-kotoba-ext.md`
- `90-docs/adr/2606302300-org-taxonomy-4-orgs.md`
- kami-engine `90-docs/adr/0040`, `0042` (CLJ/EDN everywhere)
- kotoba `docs/ADR-clojure-wasm.md`, `docs/ADR-safe-capability-language.md`
