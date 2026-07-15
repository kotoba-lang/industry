# ADR-2607010930: Rust→Clojure/WGSL migration (ambitious scope; hot loop in WGSL, CLJ authors + dispatches)

**Status**: largely complete (Phase 7, 2026-07-02) — 113/116 original kami-engine `kami-*` crates restored to CLJC or confirmed as genuine substrate exclusions; Phase 2 (WGSL `@compute` hot-loop authoring) and Phase 3 (golden-frame CI) remain open follow-ups
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
  guard + `nbb wgsl-parity --strict`); `kotoba-boundary-audit.cljs` gains
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
+ west.yml) is complete (61 repos registered, 0 pin regressions — see below).

## Phase 5 — reconcile with the parallel ADR-2607010000 authority/provider split (2026-07-01)

A concurrent agent independently drafted **ADR-2607010000** ("runtime・SDK・OS
substrate を kotoba-only 正本へ寄せる") with its own working docs
(`90-docs/migration/{kami-kotoba-repo-split,kami-provider-catalog,
kotoba-only-runtime-ledger}.edn`, uncommitted at the time of this Phase 5
entry). It targets an overlapping but *differently shaped* problem: not
"which 61 deleted crates need a CLJC home" (this ADR's Phase 4) but "how
should `kami-engine`'s *remaining* Rust be organized into authority-contract
vs. Wasm-Component-provider repos, going forward." The two efforts must be
reconciled, not run in parallel unaware of each other.

### The shape of ADR-2607010000's design (for context)

Four layers: **(1) Kotoba layer** — EDN/kotoba graph is the true source of
meaning; **(2) Contract layer** — `.cljc` pure functions + EDN are the
executable authority (`kami-contracts`, `kami-scene-contracts`,
`kotoba-core-contracts`, etc.); **(3) Wasm Component adapter layer** — native
code is reduced to a WIT-shaped provider shell that only executes
contract-validated requests; **(4) Host implementation layer** — Rust/TS/Python
survive only for GPU/OS/device/native-crypto/realtime-loop capability the
provider needs, never for domain policy. `kami-provider-catalog.edn`
consolidates the *remaining* kami-engine Rust crates (as of 2026-07-01) into
**8 provider families**, each backed by one target repo:

| Family | Target repo | Crates it absorbs |
|---|---|---|
| render | `kami-render-provider` | kami-render, kami-webgpu-rs, kami-web(-modelb), kami-clj-host, kami-eng-render, kami-eng-web, kami-ui-gpu, kami-rt, kami-rtx-native |
| physics | `kami-physics-provider` | kami-genesis, kami-physics-solvers, kami-physics-2d, kami-vehicle, kami-articulated, kami-shugyo, kami-sensor-sim, kami-autodrive, kami-cartpole-wasm |
| scene-domains | `kami-scene-contracts` | kami-*-scene family (13 crates) |
| domain-providers | `kami-domain-providers` | kami-cad(-import), kami-cae, kami-eda, kami-bim, kami-dft, kami-spice, kami-pdk-adjacent, kami-pnr, kami-ip, kami-si, kami-power, kami-yield, kami-usd(-native), kami-gltf, kami-vrm, kami-audio, kami-rtc, kami-os, kami-pkg |
| app-fixtures | `kami-app-fixtures` | kami-app family, kami-clj-play(3d) |
| visual-render-providers | `kami-render-provider` | kami-atmosphere, kami-character, kami-graph, kami-live, kami-map, kami-mesher, kami-nerf, kami-pbrt, kami-postfx, kami-replicator, kami-sdf, kami-skeleton, kami-terrain, kami-text, kami-tilemap, kami-vegetation, kami-voxel |
| engine-runtime-providers | `kami-runtime-provider` | kami-core, kami-engine, kami-engine-clj, kami-game, kami-input, kami-scene, kami-scene-graph, kami-script-runtime |
| engineering-workflow-providers | `kami-domain-providers` | kami-bridge, kami-dec, kami-eng-core, kami-eng-io, kami-flow, kami-groot, kami-knp, kami-mine-{ai,pds}, kami-pathfind, kami-pdk, kami-pipelines, kami-scad, kami-verify |

Authority repos: `kami-contracts` (component worlds, scene/render/ipc/gpu/rt/sim
contracts — the split target of `kami-engine-sdk-clj`) and `kami-scene-contracts`
(scene-domain EDN vocabularies).

### Reconciliation decision

**No repo-name collisions exist** between this ADR's 61 Phase-4 repos and
ADR-2607010000's 8 authority/provider repos (verified 2026-07-01: empty
intersection). The two sets are complementary, not competing, once framed
correctly:

1. **ADR-2607010000 owns the authority/provider *architecture*** — which
   repo is the EDN/CLJC source of truth (`kami-contracts`,
   `kami-scene-contracts`) and which repos are Wasm-Component provider
   shells for native execution (`kami-render-provider`,
   `kami-physics-provider`, `kami-domain-providers`,
   `kami-runtime-provider`, `kami-app-fixtures`). This is the durable shape.

2. **This ADR's Phase-4 repos are pre-existing, scaffolded homes for the
   crate-level *content*** that provider-catalog's `:crates` lists name —
   e.g. `kami-provider-catalog.edn`'s `domain-providers` family lists
   `kami-dft`/`kami-spice`/`kami-cad`/`kami-eda`/`kami-audio`/`kami-vrm`/
   `kami-rtc`/`kami-os`/`kami-pkg`/`kami-pnr`/`kami-si`/`kami-power`/
   `kami-yield`/`kami-ip`/`kami-bim` — every one of which already has a
   scaffolded 1:1 CLJC repo from this ADR's Phase 4 (`dft`, `spice`, `cad`,
   `eda`, `audio`, `vrm`, `rtc`, `os`, `pkg`, `pnr`, `si`, `power`, `yield`,
   `ip`, `bim`). Likewise `engine-runtime-providers` (core/engine/game/input/
   scene/scene-graph/script-runtime) and `engineering-workflow-providers`
   (bridge/dec/flow/knp/mine-ai/mine-pds/pathfind/pdk/pipelines/scad/verify)
   are ~90% covered by Phase-4 repos.

3. **Resolution: Phase-4 repos are the *content* layer; ADR-2607010000's
   provider-family repos are optional *aggregation* points, not replacements.**
   A Phase-4 repo (e.g. `kotoba-lang/dft`) is not obsoleted by
   `kami-domain-providers` — either (a) `kami-domain-providers` becomes a
   thin umbrella that `:local/root`-deps or git-subtree-vendors the
   already-scaffolded per-domain repos, or (b) the provider-catalog's
   `:crates` grouping is reinterpreted as "these Phase-4 repos together form
   the domain-providers family" without a physical merge. Either way, no
   Phase-4 scaffold work is wasted, and CLJC restoration proceeds directly
   in the Phase-4 repos using ADR-2607010000's four-layer discipline
   (EDN/CLJC = authority; native, if any, = validated-request executor only).

4. **Coverage gap (crates ADR-2607010000 names that Phase 4 does NOT cover)**
   are crates that still exist in `kami-engine` today (not yet deleted) —
   `kami-render`, `kami-webgpu-rs`, `kami-web`, `kami-genesis`,
   `kami-physics-solvers`, `kami-vehicle`, `kami-articulated`, the
   `kami-*-scene` family, `kami-live`, `kami-map`, `kami-pbrt`,
   `kami-clj-play(3d)`, `kami-app-{amenominaka,car-sim,giemon,...}`,
   `kami-cad-import`, `kami-usd(-native)`, `kami-engine-clj`,
   `kami-eng-core`, `kami-eng-io`, `kami-groot`. These are out of Phase 4's
   scope (Phase 4 only restores *deleted* crates) and belong entirely to
   ADR-2607010000's provider-split, which operates on the live tree.

### CLJC restoration order (going forward)

Restoration in the Phase-4 repos should follow ADR-2607010000's authority
discipline even though the repos predate it:

1. Each Phase-4 repo's `.cljc` restoration starts with the **EDN contract**
   (data shapes, request/response, capability grants) before any executable
   logic — mirroring `kami-contracts`'s `:new-repo-defaults` convention
   (`forbidden-authority-files ["Cargo.toml" "package.json" "pyproject.toml"]`).
2. Where a Phase-4 repo's domain has native-only concerns (GPU dispatch,
   OS syscalls, realtime audio), it stays a thin provider — logic lives in
   the CLJC contract, native code only executes validated requests
   (ADR-2607010000 layer 3/4 discipline), consistent with this ADR's own
   Phase 2 principle (hot loop in WGSL/native; CLJ authors + dispatches).
3. Cross-reference `kami-provider-catalog.edn`'s per-family `:rule` when
   restoring a given Phase-4 repo (e.g. `dft`/`spice`/`pdk` etc. follow
   `:native-code-only-executes-validated-requests`).

## Phase 6 (2026-07-02) — kotoba-store/query/browser-client gap closure

The `:post-phase-3-survey-2026-07-01` entry above flagged but did not act on
a gap: this ADR's original `:out-of-scope` list classified
`kotoba-store/ProllyTree/CID` (content-addressed storage hot path) and
`kotoba-query`/`kotoba-runtime` (4-index Datalog Arrangement + browser
client) as **`:stay-Rust-substrate`** — deliberately *not* migration
targets. `kotoba-lang/kotoba` then deleted its entire Rust workspace in
`604896171b` anyway, taking that "must stay" substrate with it and leaving
**zero CLJC replacement plan** for it. `kotoba-lang/map` (Phase 4/5 restored
real MVT decoder + globe/orbital-math CLJC) has domain-render logic but no
data layer — nothing in the org can query kotobase.net's IPLD blocks from
the browser today.

Trigger: owner directive 2026-07-02 for `kotoba-lang/map`'s design —
**browser kotoba-wasm does all query/assembly client-side; kotobase.net is
delivery-only** (serves `block.get` CID fetches, does not shape queries
server-side). This is stated as the base design for the whole service
surface (map, murakumo, ...), not map-specific.

### Reused (already real, not scaffold)

- `kotoba-lang/multiformats` — CID/multihash/multibase, pure CLJC
- `kotoba-lang/dag-cbor` — canonical dag-cbor encode/decode, pure CLJC
- `kotoba-lang/cacao` — CAIP-122/SIWE CACAO mint+verify, pure CLJC (IPNS
  record signing/verification builds on this, not a new primitive)
- `kotoba-lang/kotoba-pages-poc` — GitHubPagesBlockStore, CID-queryable
  static tier (ADR-2606242400) — the existing reference pattern for
  "server = delivery only". `net-kotobase`'s own reduction to a
  delivery-only surface belongs to ADR-2607010000's `net-kotobase` scope,
  not redefined here.

### New repos

| repo | deps | scope |
|---|---|---|
| `kotoba-lang/prolly-tree` | multiformats, dag-cbor | content-addressed probabilistic B-tree — chunk boundary ~1/256 determined by **child CID bytes** at internal levels (matches the deleted Rust fix: boundary must not be keyed on leaf max-key, which caused infinite recursion), multi-level build, CID-addressed nodes via an injected `put!` port, lookup/scan-prefix via an injected `get-fn` port |
| `kotoba-lang/quad-store` | prolly-tree, multiformats, dag-cbor | `Quad{s p o}` + 4-index in-memory Arrangement (`spo`/`pso`/`pos`/`ocp`, same naming as the deleted `kotoba-query`), `commit!` snapshots the 4 indices to prolly-tree roots forming a CID-addressed commit chain |
| `kotoba-lang/kqe` | quad-store | Kotoba Query Engine — `[s p o]` pattern query with wildcards, routes to the index matching which positions are bound (mirrors the deleted `route_bgp_triples`); full Datalog fixpoint/SPARQL BGP is **not** in this landing |
| `kotoba-lang/kotoba-client` | kqe, quad-store, prolly-tree, multiformats, cacao, dag-cbor | browser orchestration — `ingest-block` (CID re-verify, mirrors the deleted `KotobaNode.ingestBlock`), `hydrate-via-blocks` (missing-CID walk + injected fetch/store ports, loops to convergence, mirrors the deleted `hydrateViaBlocks`). **IPNS-record signature verification (trustless head resolution) is explicitly deferred** — needs `cacao`'s exact signing surface, tracked as a repo-local TODO rather than fabricated |

All four ship with real initial implementations (not scaffold-only stubs) —
matching the quality bar of `kotoba-lang/mst`, not Phase 4's "deps.edn +
README + empty src" convention — because the leaf-most pieces
(`prolly-tree` chunking, `quad-store` indices, `kqe` routing, `kotoba-client`
CID-verify + hydrate loop) are genuinely tractable in one pass; the
Datalog-fixpoint and IPNS-verify pieces are explicitly named follow-ups in
each repo's own README rather than silently omitted.

### Application

`kotoba-lang/map` gets `kotoba-client` wired in as its data layer (viewport
→ `geo` H3 tile keys → `kotoba-client` hydrate + `kqe` query → the existing
`mvt`/`orbital`/`projection` render pipeline). This wiring is a follow-up PR
against `kotoba-lang/map`, not part of the four new repos landed in this
phase.

### Known gap not closed by this phase

`90-docs/kotoba-lang-adr.edn`'s inter-repo dependency index was generated
against 200 repos; the org now has 332. This phase only adds entries for the
4 new repos (+ `map`'s new `kotoba-client` dependency) — a full from-scratch
332-repo `gh api` sweep to regenerate every repo's dependency edges is a
separate follow-up, not silently claimed done here.

## Phase 7 (2026-07-02) — ambitious-scope wave closed: 113/116 kami-engine crates restored

Where Phase 4 (2026-07-01) only *scaffolded* 63 `kotoba-lang/{repo}` homes
(deps.edn + README + empty src/test skeleton), this phase actually performed
the CLJC restoration for those scaffolds, then extended coverage well beyond
Phase 4's original 63-repo list — driven by a full crate-by-crate re-audit of
`kami-engine`'s complete original 116-crate `kami-*` inventory (not just the
"deleted-not-migrated" subset Phase 4 scoped to).

### Final tally

**113 of 116 original `kami-*` crates now have a CLJC or documented-exclusion
home in `kotoba-lang`.** The 3 remaining:

- `kami-clj-play` / `kami-clj-play3d` — confirmed genuine substrate: each is
  a single `src/main.rs` native windowed player (winit + wgpu) with zero
  portable domain logic. Correctly excluded, no further action.
- `kami-ui-sdk` — confirmed to be a JavaScript (not Rust) SDK; out of this
  ADR's Rust→CLJC restoration scope by definition. Left for a separate
  decision (JS migration is a different kind of task than this ADR covers).

`kami-webgpu` (a plausible-looking name in this family) was investigated and
confirmed to be a **separate, mature, unrelated project** ("Declarative
WebGPU from EDN — hiccup for the GPU," CLJS, own ADR/CHANGELOG/tests) that
happens to share a naming pattern — explicitly out of scope by owner
decision, not counted against either tally.

### Two restorations that corrected earlier mistaken exclusions

Two crates previously recorded as out-of-scope substrate were re-investigated
during Phase 7 closeout and found to carry genuinely portable content:

- **`kami-flow`** — earlier misjudged as an unrelated Node.js/Cypher
  graph-ingest tool. That description actually applied to an *unrelated*
  `graph/` subdirectory sharing the same `kami-engine/kami-flow/` folder
  path; `kami-flow`'s own `src/lib.rs` is a genuine RTL→PnR→GDSII→Verify/
  Power/DFT/SI/Yield→STA→DRC/LVS→Signoff orchestrator, now restored to
  `kotoba-lang/kami-flow`, wiring together 7 already-restored sibling EDA
  crates (`rtl`/`pnr`/`model-checking`/`power`/`dft`/`signal-integrity`/`yield`).
- **`kami-script-runtime`** — the crate as a whole remains correctly
  out-of-scope (it *is* the WASM host — wasmtime/wasmi binding
  `kami:engine/*` imports to live Rust engine state, genuine substrate).
  But its `src/input_map.rs` submodule is, per its own doc comment,
  "pure Rust... no platform deps" — device-neutral touch/analog input
  mapping. That one file was scope-extracted to `kotoba-lang/kami-script-runtime`
  (repo name kept `kami-script-runtime` for discoverability; only
  `input_map.rs`'s content was ported, `lib.rs`/`platform.rs`/`bin/*` were
  not and will not be).

### Restoration method used throughout

Every restoration followed the same discipline: verify the target scaffold
was genuinely empty (or genuinely nonexistent, for the ~50 crates outside
Phase 4's original list) before touching it; fetch the deleted Rust source
at `kami-engine@a8368f9c0d784dbc9d11e8fa8f407aa95c7ce4fa`; port 1:1 with
every original `#[test]` mirrored; for wasm-bindgen/native-app crates
(`kami-app-isekai`, `kami-app-giemon`, etc.), scope to the genuinely portable
computational kernels/config data and explicitly document native-only
exclusions rather than force a whole-crate port; for `kami-*-scene` crates,
depend on the already-restored `kotoba-lang/scene` (tolerant EDN accessor
port of `kami-scene`) plus the paired domain crate, duck-typing a local
default shape when the domain crate was still mid-restoration in a parallel
dispatch. `manifest/repos.edn` (`:extra-projects`) + `manifest/west.yml` were
updated per-repo via direct GitHub API commits with fresh-fetch retry-on-409
loops (an external concurrent process was independently advancing unrelated
pins throughout this phase) and a mandatory post-write SHA verification pass
comparing the written pin against the actual API-fetched repo HEAD.

### Separately: 7 live (never-deleted) `-clj` projects split out of the monorepo

Distinct from the 116-crate Rust-deletion tally above, 7 `kami-*-clj`
subdirectories inside `kotoba-lang/kami-engine` were never part of the
deleted Rust workspace — they are live, current Clojure(Script) projects
that predate this ADR. Following the precedent already set by
`kami-mangaka-genko-clj` → `kotoba-lang/kami-genko`, these were split out to
their own standalone repos (copy-out, not move — the source `kami-engine`
subtree is untouched): `kami-mangaka-{page,reader,scene,render,text}-clj`,
`kami-app-sip-clj`, `kami-engine-sdk-clj` (distinct from the pre-existing,
unrelated `kotoba-lang/kami-engine-sdk` Svelte UI mirror). Two of these
(`page`/`reader`) had a `:local/root` dependency on `text-clj` that broke on
the split; both were fixed to `:git/url`+`:sha` coordinates once `text-clj`
landed as its own repo, and both now test green.

### Incident during this phase (self-caught, corrected)

A manifest-write script bug during `kami-shugyo`'s registration caused one
commit to accidentally wipe `manifest/repos.edn` to 0 bytes (a Python
content-transform step failed its "expected trailing block" assertion,
because a concurrent process had appended new entries between fetch and
write, but the script proceeded to `PUT` a stale/empty content variable
instead of aborting). Caught immediately via a routine post-write line-count
check; reverted from the parent commit within the same session with the
intended `kami-shugyo` entry re-applied on top, and zero registrations were
lost. Every subsequent manifest write in this phase added a hard
line-count-must-not-decrease guard before every `PUT`.

## Related

- `90-docs/migration/clj-wgsl-ledger.edn` (the crate-by-crate ledger, SSoT for this migration)
- `manifest/kotoba-boundaries.edn` `:wgsl-ownership` (shader ownership map)
- `scripts/verify-clj-everywhere.sh` (WGSL-parity surface)
- `scripts/kotoba-boundary-audit.cljs --clj-wgsl` (WGSL ownership audit)
- `90-docs/adr/2606241700-kotoba-clj-runtime-kotoba-ext.md`
- `90-docs/adr/2606302300-org-taxonomy-4-orgs.md`
- kami-engine `90-docs/adr/0040`, `0042` (CLJ/EDN everywhere)
- kotoba `docs/ADR-clojure-wasm.md`, `docs/ADR-safe-capability-language.md`
