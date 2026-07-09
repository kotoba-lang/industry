# ADR-2607010000: runtime・SDK・OS substrate を kotoba-only 正本へ寄せる

**Status**: accepted (all six scoped repos landed & reconciled; kami-nv-compat port waves continue as follow-up)
**Date**: 2026-07-01 (status updated 2026-07-02)
**Progress note (2026-07-02)**: ledger `90-docs/migration/kotoba-only-runtime-ledger.edn` の全 item が done。
aiueos の Rust runtime crate 削除・kotodama-mcp の EDN manifest 化 + TS provider 削除・
kotodama-host の host contract CLJC 化 + TS SDK 削除・kototama の Rust wrapper 削除は
origin main に着地済み（stalled WIP の cleanup landing 含む: kotodama-mcp `5247c16`,
kotodama-host `cb6c087`, kototama `10ddfe2`）。kami-engine も reconcile 完了
（2026-07-02 午後）: Rust 削除 sweep・sdk-clj 更新・mangaka 分割は着地済み残骸と判定し
archive、sip dashboard の adapter 化完成 2 ファイルを救出着地（`aed5d85`）、GitHub に
存在しない commit を指していた west pin を修復（archive branch
`archive/34b7226-quality-liveness-unlanded` で旧 pin の fetch 可能性も回復）。
残る進行中作業は kami-nv-compat の port waves（wave 33+ 活発に進行中、pin == HEAD）のみ。
**Scope**:

- `orgs/kotoba-lang/aiueos`
- `orgs/kotoba-lang/aiueos-cljc-contract`
- `orgs/kotoba-lang/kami-engine`
- `orgs/gftdcojp/net-kotobase`
- `orgs/kotoba-lang/kotodama-host`
- `orgs/kotoba-lang/kotodama-mcp`

## Context

`orgs` 配下の移行対象を生成物・依存物・作業用 worktree を除いて確認すると、
まだ Rust/TypeScript/Svelte/Python/JavaScript が残っている。特に OS substrate /
runtime・SDK 系は単純な言語置換では壊れやすい。

- `aiueos`: README 上は kotoba-defined だが、manifest/policy/broker/runtime の実装正本は
  まだ Rust に残る。
- `aiueos-cljc-contract`: aiueos の `.cljc` authority contract が入り始めた移行先。
- `kami-engine`: Rust workspace。render / physics / robotics / scene / app crate が多数。
- `net-kotobase`: kotobase.net BaaS。外部 write surface は既に `worker/src/app.cljc`
  だが、Rust kotoba submodule、Worker package、SDK/wasm proof が残る。
- `kotodama-host`: Rust desktop/KAMI host scaffolds、Rust config、TypeScript host SDK。
- `kotodama-mcp`: MCP server implementations and fixtures。TypeScript MCP package 群。

既存方針として、Worker は `.cljc` 正本へ寄せる
`ADR-2606290000-all-workers-cljc-only-policy` があり、MCP は
`ADR-2606272204-mcp-clj-protocol-manifest-dispatch` で EDN model + host-injected
ports の設計がある。aiueos も `ADR-2606290930-kotoba-aiueos-capability-bridge` で
Kotoba Grant 正規化を前提にしている。

したがって今回の目的は「Rust/TS を即削除する」ではなく、最終的に **kotoba / kotoba-clj で
完結する** 正本へ移すこと。adapter の基本形は Rust/TS の手書き runtime ではなく
**Wasm Component Model** とし、host 固有コードは component import/export を実環境へ接続する
launcher/provider に限る。

## Decision

対象 repo を同じ4層構造へ寄せる。

1. **Kotoba layer: final source of truth**
   - OS / runtime / SDK / tool / scene / protocol は kotoba data + `.cljc` authority logic で
     完結させる。
   - `.cljc` は移行期の実装形式であり、意味論上の正本は EDN/kotoba graph とする。
   - Rust/TS/Python/Svelte に domain authority を残さない。

2. **Contract layer: kotoba/cljc 正本**
   - manifest, lexicon, command, scene, graph, MCP tool, request/response, audit event は
     EDN データと `.cljc` pure function を正本にする。
   - JSON, WIT, TypeScript type, Rust struct は生成物または adapter schema として扱う。
   - core namespace は third-party runtime dependency を持たない。I/O は ports で注入する。

3. **Wasm Component adapter layer: host-specific provider shell**
   - adapter は原則として Wasm Component Model の world/import/export で表す。
   - domain command は kotoba EDN、component ABI は canonical typed boundary、host provider は
     capability を実環境へ materialize するだけにする。
   - WIT/Component metadata も正本ではなく、kotoba contract から生成または検査される artifact。
   - Cross-language interop は `kotoba data -> component call -> kotoba result/audit event`
     の形に閉じる。

4. **Host implementation layer: temporary launcher/provider**
   - Rust は GPU / OS / device / WASM ABI / native crypto / realtime loop など、
     Wasm component provider が必要とする host capability だけに暫定的に残す。
   - TypeScript は Cloudflare Worker bootstrap、browser DOM bridge、npm-only SDK interop
     だけに残す。business logic と validation は持たせない。
   - Python は external model/tool process の process boundary 以外には残さない。残す場合も
     plan/result を kotoba contract で受け渡す。
   - MCP transport, HTTP, stdio, WebSocket, D1/R2/B2, filesystem, keychain は adapter port。

5. **Generated layer: disposable artifacts**
   - `package.json`, `tsconfig`, `Cargo.toml` がある場合でも、その directory の正本が
     `.cljc`/EDN か adapter かを明示する。
   - generated WIT/wasm/TS/Rust は `dist`, `pkg`, `target`, `out` に閉じ、tracked source と
     混同しない。

## Repository Plans

### `aiueos` / `aiueos-cljc-contract`

Goal: aiueos を「Rust OS substrate」ではなく「kotoba-defined capability OS contract」にする。
Rust crate は kotoba contract の verifier/runner adapter へ降格する。

Migration units:

- `aiueos.manifest`: `:aiueos/*` schema、component kind、trust、limits、surface、artifact CID。
- `aiueos.policy`: capability linking, effect/trust, DMA/IOMMU, device exclusivity,
  Kotoba Grant intersection。
- `aiueos.broker`: verify/admit/launch/audit pipeline を plan/result contract 化。
- `aiueos.surface`: robot/browser/cloud/computer-use/device provider surfaces を EDN capabilities
  と host-injected ports へ分離。
- `aiueos.audit`: append-only event shape、run receipt、CID-addressed receipt DAG。
- `aiueos.runtime`: compile/run は kototama/kotoba execution contract 経由。wasmtime は adapter。
- `aiueos.component`: surface providers and sandboxed apps are expressed as Wasm components;
  host ABI is generated/checked from kotoba capability contracts.

Rust retention rule:

- keep temporarily: wasmtime host, OS process/bootstrap, CLI shim, native device/provider,
  microVM/image builder, performance-critical sandbox runner。
- remove or wrap: manifest parser, policy reasoner, broker state machine, audit event construction,
  surface authorization, grant normalization。

`aiueos-cljc-contract` is the migration front door. New aiueos authority behavior goes there first,
then Rust is adjusted to call or match the `.cljc` contract. Once parity is complete, `aiueos`
becomes an adapter/distribution crate and no longer owns semantics.

### `kami-engine`

Goal: Rust engine workspace を「engine semantics の正本」から「kotoba scene/physics/render
contract の実行 adapter」へ降格する。

Migration units:

- `kami.scene`: scene graph, asset manifest, material, camera, input, event を EDN model 化。
- `kami.physics`: state, force, constraint, solver step, contact result を deterministic
  `.cljc` contract 化。高性能 solver は Rust adapter として contract を読む。
- `kami.robotics`: actuator/sensor/control loop を command/result plan にする。
- `kami.render`: render intent を EDN IR 化。wgpu/WebGPU/DOM は renderer adapter。
- `kami.app`: `kami-app-*` は app logic を `.cljc` cell/state-machine へ移し、Rust app crate は
  launch + frame-loop + adapter binding だけに縮小。

Rust retention rule:

- keep: wgpu, native file/device access, realtime loop, SIMD/GPU solver, FFI/WASM ABI.
- remove or wrap: domain state machines, validation, manifest parsing, routing, policy,
  app workflow, test fixtures expressible as EDN.

Final-state rule: even retained Rust renderer/solver code is not authority. It accepts kotoba IR,
returns kotoba result/event data, and can be replaced without changing domain semantics.

### `net-kotobase`

Goal: kotobase.net の外部 API と tenant semantics は kotoba-clj / `.cljc` 正本に統一し、
deploy/runtime adapter は Wasm Component Model へ寄せる。Worker/TS は component host
provider であり、API semantics を持たない。

Migration units:

- `kotobase.contract`: tenant-facing NSID, backend NSID translation, auth claims, quotas,
  pin metadata, query request/result, error envelope。
- `kotobase.component`: HTTP/XRPC/MCP/pin/query/write surfaces を Wasm component export として
  定義し、requests/results は kotoba EDN contract にする。
- `kotobase.worker`: Cloudflare Worker は component host/provider。fetch/D1/R2/B2/env は
  component imports を満たすだけで、route/auth/quota semantics を持たない。
- `kotobase.storage`: B2/IPFS/CAR/Durable Object/D1 は component imports。contract tests は
  fixture providers。
- `kotobase.sdk`: Datomic/Datalog/SPARQL/Cypher client surface は EDN request model から生成し、
  SDK は component call facade にする。

TS retention rule:

- keep: Cloudflare module export bootstrap, Wasm component loader/provider, Wrangler compatibility
  shim, npm-only publish wrapper.
- remove or wrap: route logic, auth/quota validation, request normalization, API examples checker。

Rust retention rule:

- keep only as component builder/runner/provider or native storage/crypto acceleration. Public API
  behavior must remain tested through `.cljc` contract fixtures and component parity tests.

### `kotodama-host`

Goal: host runtime を「Kotodama actor protocol の Wasm component host」に分け、SDK 正本を
TypeScript から EDN/.cljc に移す。

Migration units:

- `kotodama.host.manifest`: host capability, actor registration, lifecycle, health,
  permission, device surface。
- `kotodama.host.protocol`: dispatch, request id, stream event, cancellation, error envelope。
- `kotodama.host.config`: current Rust config crate を EDN schema + `.cljc` validation 正本へ。
- `kotodama.host.sdk`: TypeScript SDK exported API は generated facade。source of truth は
  `.cljc` protocol/model。
- `kotodama.host.component`: actor lifecycle and dispatch are component exports; host capabilities
  are imports.

Rust retention rule:

- keep: desktop tray/window, process supervision, local IPC, OS notification, device hooks,
  native secrets, realtime host process.
- remove or wrap: protocol validation, lifecycle state machine, routing, registry lookup,
  audit event construction。

TS retention rule:

- keep: npm packaging facade and browser/node ergonomic wrapper generated from `.cljc` model.
- remove or wrap: hand-maintained type definitions and duplicate protocol logic。

### `kotodama-mcp`

Goal: MCP implementations を TypeScript server packages から EDN manifest + `mcp-clj` dispatch
へ移し、transport/host interop は Wasm component provider として扱う。

Migration units:

- Each `*-mcp` package gets a `mcp.edn` or `src/*/manifest.cljc` defining tools/resources/prompts.
- Tool argument schemas become EDN JSON-Schema subset accepted by `mcp-clj`.
- Tool execution is host-injected `ITool` ports. Transport is stdio/SSE/HTTP adapter only.
- Component export shape is generated from the same EDN manifest where a host requires binary
  plugin packaging.
- Fixtures remain EDN and can be executed by JVM tests without Node.

TS retention rule:

- keep: npm package bootstrap for hosts that require a Node binary, stdio/SSE adapter, third-party
  SDK bridge that has no stable HTTP/EDN equivalent.
- remove or wrap: manifest construction, JSON-RPC validation, required argument checking,
  request dispatch。

## Cross-Cutting Shape

Each migrated repo should converge on this layout:

```text
deps.edn
src/<domain>/**/*.cljc       # source of truth
resources/**/*.edn           # manifest, fixtures, examples
test/<domain>/**/*_test.clj  # JVM contract tests
component/
  wit/                       # generated or checked WIT/component boundary, not semantic source
  fixtures/                  # component parity fixtures
adapter/
  rust/                      # native capabilities only, if needed
  ts/                        # worker/node/browser bootstrap only, if needed
  py/                        # process boundary only, if explicitly exempted
generated/ or dist/          # disposable outputs
```

Where the existing repo cannot move directories immediately, add a local `MIGRATION.md`
or ADR pointer declaring which files are source-of-truth and which are adapters.

## Migration Phases

1. **Inventory and classify**
   - For every `Cargo.toml`, `package.json`, `tsconfig`, `pyproject`, `requirements`,
     classify directory as `contract`, `adapter`, `generated`, or `historical`.
   - Any `contract` not in `.cljc`/EDN becomes a migration item.
   - The inventory source is `90-docs/migration/kotoba-only-runtime-ledger.edn`.

2. **Freeze new non-cljc logic**
   - New domain logic must be `.cljc`.
   - Rust/TS changes are allowed only in adapter directories or with a local ADR exception.

3. **Extract contract models**
   - Start with schemas and request/result envelopes before behavior.
   - Add fixture parity tests against current Rust/TS behavior.
   - Define component imports/exports from the same contract, but do not make WIT the authority.

4. **Move behavior**
   - Migrate validation, routing, state machine, policy, manifest handling, and tool dispatch.
   - Keep native code callable behind Wasm component providers until parity is green.

5. **Generate or shrink facades**
   - Replace handwritten TS SDK types, Rust DTO structs, WIT, and loader glue with generated or
     mechanically checked facades where practical.

6. **Prune**
   - Remove Rust/TS code only after equivalent `.cljc` tests and adapter smoke tests exist.
   - Keep README migration notes for any intentionally retained adapter.
   - A retained adapter must have an explicit `:adapter-reason` in the migration ledger.

## Acceptance Criteria

For each repo:

- There is a documented source-of-truth boundary: `.cljc`/EDN contract vs adapter.
- All public protocol routes/tools/commands have `.cljc` model + validation tests.
- All externally executed behavior has a component import/export boundary derived from or checked
  against the kotoba contract.
- Rust/TS files that remain are adapter-only by inspection and have no duplicated business logic.
- A local audit command returns only expected adapter/generated/historical files for
  `*.rs`, `*.ts`, `*.tsx`, `*.svelte`, `*.py`, `*.js`.
- New PRs cannot add Rust/TS/Python domain logic without an ADR exception.
- The final semantic dependency graph closes over kotoba/kototama/kotoba-auth/mcp-clj style
  `.cljc` libraries. Host adapters cannot introduce new semantic authority.

## Non-Goals

- Treating host adapters, WIT, or Component Model IDL as permanent alternate source-of-truth
  implementations.
- Big-bang deletion of Rust/TS before parity tests exist.
- Treating generated WASM/JS artifacts as source.

## Immediate Priority

1. `aiueos-cljc-contract`: expand manifest/policy/broker/audit contract and make Rust match it.
2. `kotodama-mcp`: lowest native complexity; map TS packages to `mcp-clj` EDN manifests.
3. `kotodama-host`: extract protocol/config/lifecycle contract; shrink TS SDK first.
4. `net-kotobase`: already has `app.cljc`; finish contract extraction around API/auth/quota/storage,
   then move Worker runtime to a Wasm component provider shape.
5. `kami-engine`: largest native surface; start with scene/physics/render IR contracts, keep Rust
   as performance adapter until each crate is classified.
