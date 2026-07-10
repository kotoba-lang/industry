---
id: adr-2607102200-kami-render-stack-deps-authority-rename
title: "ADR-2607102200: kami-engine 3D/WebGPU/render 系統の依存・権限マップと rename / cleanup"
status: accepted
doc_type: adr
topic: kotoba-lang-repo-boundaries
authoritative: true
last_verified: 2026-07-10
authoritative_for:
  - kotoba-lang 3D / WebGPU / render 系統の canonical 依存レイヤと責任境界
  - 誤解を生む repo 名の rename 表（script-runtime / engine-sdk / host）
  - kami.wgsl 二重方言の SSoT 方針
  - kami-engine monorepo nested 残骸の cleanup 方針
related:
  - 90-docs/adr/2607010930-clj-wgsl-migration.md
  - 90-docs/adr/2607051400-kami-engine-webgpu-sdk-consolidation.md
  - 90-docs/adr/2607051510-kami-engine-ecosystem-cljc-cljs-dedup.md
  - 90-docs/adr/2607051200-kotoba-lang-ui-family-rename.md
  - 90-docs/adr/2607032100-consolidate-engine-into-kami-engine-clj.md
  - 90-docs/adr/2607100030-kotoba-kami-engine-host-imports.md
supersedes: []
superseded_by: []
---

# ADR-2607102200: kami-engine 3D/WebGPU/render 系統の依存・権限マップと rename / cleanup

- **Status**: accepted (2026-07-10)
- **Deciders**: Jun Kawasaki
- **Scope**: kotoba-lang 配下の 3D / WebGPU / render / script-host / UI SDK 系統

## Context

ADR-2607010930（clj-wgsl migration）と ADR-2607051400/1510（webgpu/SDK 統合）
で「kami-engine 系統に整理する」方針は確立済みだが、実装は 3 世代が同居:

| 世代 | 内容 | 問題 |
|---|---|---|
| A. monorepo 残骸 | `kami-engine/kami-*` サブツリー | 抽出後の削除未完（ADR-2607051510 未実行分） |
| B. scaffold 抽出 | `kami-engine-render` 等 | README が "Scaffold only" の空箱 |
| C. 生きたパス | `webgpu` / `kami-engine-sdk-clj` / `wasm-webcomponent` / `kami-script-runtime-rs` | 消費者が A の stale パスを指す |

加えて **名前が責務と食い違う** repo が残っている:

- `kami-script-runtime` — 実体は `input_map` のみ（WASM host ではない）
- `kami-script-runtime-rs` — 実体は WASM host（名前が "script-runtime" で CLJC 版と衝突）
- `kami-engine-sdk` — Svelte UI grab-bag（`kami-engine-sdk-clj` の ECS/render SDK と衝突）
- `kami.wgsl` — **同名 ns に 2 方言**（webgpu の hiccup コンパイラ vs sdk-clj の map `emit`）

本 ADR は A（依存マップ文書化）+ B（stale deps / nested 掃除）+ C（wgsl 方針）+ D
（cleanup 実行）+ **責任に基づく rename** を一括で確定する。

## Decision

### 1. Canonical 依存レイヤ（権限 = 所有責務）

```
L5 apps          kami-app-* / freeboard / kisekae / character-creator
                   │
L4 hosts         wasm-webcomponent          … browser WASM host (kotoba emit)
                 kami-engine-host-rs        … native wasmtime host (旧 -script-runtime-rs)
                 kotoba.kami-host           … portable ECS host (ADR-2607100030)
                   │
L3 authoring     kami-engine-sdk-clj        … ECS / scene / render-IR / IPC / schedule
                 kami-engine-app-sdk        … DOM overlay UI (旧 kami-ui-sdk)
                 kami-engine-sdk-svelte     … Svelte UI mirror (旧 kami-engine-sdk)
                   │
L2 render IR     webgpu                     … EDN render-IR + browser executor
                   ├── org-w3-webgpu        … 生 navigator.gpu（W3C 境界）
                   └── expr                 … 式コア（wgsl が消費）
                 render                     … CPU 側 camera/mesh/gltf/meshopt/splat
                   │
L1 shader data   webgpu 内 kami.wgsl        … hiccup → WGSL 文字列（browser SSoT）
                 kami-engine-sdk-clj 内
                   kami.wgsl-emit           … map IR → WGSL（WIT/frame 登録用・別方言）
                   │
L0 contracts     kami-engine                … WIT / fixtures / scene EDN（no-Rust asset repo）
                 kami-contracts
```

**権限ルール**

1. **GPU bootstrap / 生 WebGPU API** — `org-w3-webgpu` のみ。`webgpu` は経由呼び出し。
2. **EDN render-IR + browser draw** — `webgpu`（`kami.webgpu` / `kami.webgpu.ir`）。
3. **ECS / scene authoring / IPC pack** — `kami-engine-sdk-clj` のみ。Svelte SDK は触らない。
4. **WASM host（guest 実行）** — browser: `wasm-webcomponent`、native: `kami-engine-host-rs`。
5. **contracts / fixtures** — `kami-engine`。native 実装をこの repo に戻さない（no-Rust CI）。
6. **nested monorepo path は deps に書かない** — 常に standalone `orgs/kotoba-lang/<repo>`。

### 2. Rename 表（責務に合わせる）

| before | after | 理由 |
|---|---|---|
| `kami-script-runtime` | `kami-input-map` | 中身は device-neutral stick/deadzone/button-edges のみ。`input`（action mapping）とも別物 |
| `kami-script-runtime-rs` | `kami-engine-host-rs` | 実体は wasmtime WASM host。`-rs` は Rust substrate を明示 |
| `kami-engine-sdk` | `kami-engine-sdk-svelte` | Svelte 5 UI mirror。`kami-engine-sdk-clj` と名前空間を衝突させない |

**意図的に rename しない**

| repo | 理由 |
|---|---|
| `webgpu` | ADR-2607051400 で `kami-webgpu`→`webgpu` が既に canonical。再 reverse しない |
| `org-w3-webgpu` | 外部規格 + 仕様名パターンで正しい |
| `render` | CPU ドメイン port。package `kotoba.render.*` と一致 |
| `kami-engine-sdk-clj` | "clj is the brain" の正式名として定着 |
| `kami-engine-app-sdk` | ADR-2607051200 で改名済み |
| `input` | 別責務（action mapping）。`kami-input-map` と同居 |

### 3. Scaffold / 空箱の退役方針

README が "Scaffold only" で src が 1 ファイル程度の repo は **実装の正本ではない**。
正本へのポインタ README を残し、GitHub archive を推奨（redirect は rename と同様に保持）:

| scaffold | 正本 |
|---|---|
| `kami-engine-render` | `render`（CPU）+ `webgpu`（browser IR）+ `kami-engine/kami-render`（WGSL 資産） |
| `kami-engine-script-runtime` | `kami-engine-host-rs` + `kami-input-map` |
| `kami-engine-web` | `webgpu` / `kami-web`（CLJC port） |
| `kami-engine-core` / `-engine` / `-io` | 該当 CLJC domain repo 群 |

### 4. `kami.wgsl` 二重方言の解消（C）

**同名 ns 衝突は事故**。2 つは方言が違うため無理に 1 実装に潰さない:

| 方言 | 置き場所 | データ形 | 用途 |
|---|---|---|---|
| **hiccup compiler** | `webgpu` の `kami.wgsl` | `(func …)` / `(struct* …)` / vectors | browser WebGPU、`bb gen-wgsl` |
| **map emit** | `kami-engine-sdk-clj` の **`kami.wgsl-emit`**（旧 `kami.wgsl`） | `{:wgsl/name … :wgsl/compute …}` | WIT `register-shader` / postfx / physics compute |

standalone `wgsl`（`kotoba.wgsl`）は hiccup 方言の薄い re-export とし、実装 SSoT は
`webgpu` の `kami.wgsl` に一本化する（行数がほぼ同一の複製をやめる）。

### 5. Stale deps 修正（B）

| consumer | 誤 | 正 |
|---|---|---|
| `freeboard` | `../kami-engine/kami-engine-sdk-clj`（lib 名も `kami-engine-sdk`） | `../kami-engine-sdk-clj` + 座標 `kami-engine-sdk-clj` |
| `host` | `../kami-engine/kami-engine-sdk-clj` | `../kami-engine-sdk-clj` |

`kami-app-isekai` → `../kami-engine/kami-engine-clj` は ADR-2607032100 の
**意図的 monorepo 内 consolidation** のため変更しない。

### 6. `kami-engine` nested 残骸 cleanup（B）

| nested | 処置 |
|---|---|
| `kami-engine-sdk-clj/` | **削除**。standalone が SSoT（分岐は standalone 側を優先） |
| `kami-webgpu-rs/` | **削除**。`webgpu` に統合済 |
| 1–4 file の `kami-*-scene/` stub で standalone が充実しているもの | **削除**（fixture のみ残す場合は `fixtures/` へ寄せる） |
| `kami-script-runtime/`（survivors.clj fixture） | fixture を `fixtures/` へ移して nested 削除 |
| `kami-ui-sdk/`（live JS） | **当面残す**。`kami-engine-app-sdk` の port 元。JS 退役は別波 |
| `kami-render/src/shaders/*.wgsl` | **資産として残す**。parity SSoT（webgpu の gen-wgsl 先） |
| `kami-engine-clj/` | **残す**（guest コンパイラ consolidated home） |

### 7. west / manifest

- `path-overrides` に旧名 → 新名を追加（GitHub redirect とローカル path の橋渡し）
- `extra-projects` の path 文字列を新名へ更新
- `remote-overrides` の `kami-engine-sdk` キーも新名へ
- pin 前進は `--entry <name>` 最小 diff（wholesale 再生成禁止）

## Execution checklist

1. [x] 本 ADR 起票
2. [x] freeboard / host の deps 修正 + push
3. [x] `kami.wgsl` → `kami.wgsl-emit`（sdk-clj 内）+ tests green
4. [ ] standalone `wgsl` を webgpu re-export 化
5. [x] GitHub rename ×3 + local dir 移動 + README
6. [x] kami-engine nested 安全削除
7. [ ] scaffold README に退役ポインタ
8. [x] repos.edn path-overrides + west entry 更新
9. [ ] 検証: 当該 repo の test / lint

## Consequences

- (+) 依存を読むだけで「誰が何を所有するか」が分かる
- (+) freeboard/host が stale nested を引かなくなる
- (+) `kami.wgsl` 同名衝突が解消され、方言が ns で区別される
- (+) Svelte SDK / WASM host / input_map の名前が責務と一致
- (−) rename は GitHub redirect があるとはいえ、外部 clone / ドキュメントの追従が必要
- (−) nested `kami-ui-sdk` JS と Svelte sdk の退役は未着手（別波）
- (−) scaffold archive は推奨だが、west から物理削除するまでは pin が残る

## Alternatives considered

- **webgpu を再び kami-webgpu に戻す**: 却下。ADR-2607051400 の canonical 決定を壊す。
- **2 方言の kami.wgsl を 1 実装に強制マージ**: 却下。データ形と用途が異なり、破壊的 rewrite になる。ns 分離で十分。
- **kami-script-runtime を input に merge**: 却下。`input` は action mapping の別 port。stick/deadzone を混ぜると責務が濁る。
- **nested を一切触らず docs のみ**: 却下。stale deps が実際に nested を指している。

## Related

- ADR-2607010930 clj-wgsl migration
- ADR-2607051400 webgpu / org-w3-webgpu / SDK 統合
- ADR-2607051510 重複整理（webgpu 済・kami-engine nested 未）
- ADR-2607051200 ui family rename
- ADR-2607100030 kami host imports

## Addendum 1 (2026-07-10) — naming correction + prune

Owner review of the first landing:

### Rename / prune table (this addendum)

| before (post first landing) | after | action |
|---|---|---|
| `kami-engine-sdk-clj` | **`kami-engine-sdk`** | rename; drop `-clj` (only engine SDK left) |
| `kami-engine-sdk-svelte` | — | **archived / pruned** (Svelte path retired) |
| `kami-engine-host-rs` | — | **archived / pruned** (rs wasm host unused; browser path only) |
| `kami-input-map` | **`kami-engine-input-map`** | rename into `kami-engine-*` family |

### WASM host policy (corrected)

**Do not use a Rust/wasmtime host for kami game guests going forward.**
Canonical host paths:

1. **Browser** — `wasm-webcomponent` (native `WebAssembly` engine + host imports)
2. **Portable ECS host** — `kotoba.kami-host` (`.cljc`; Chicory/nbb/browser wire)
3. **JVM tender (non-kami-game)** — `kototama.tender` (Chicory; `actor:host` ABI)

`kami-engine-host-rs` is archived. Do not reintroduce a parallel rs host.

### DOM overlay family — who is who (clarified)

Three things looked similar; they are not the same layer:

| repo | layer | owns | does NOT own |
|---|---|---|---|
| **`kami-engine-hud`** | **data IR** | declarative HUD widget *description* (`:panel`/`:bar`/`:minimap`/`:text`) as EDN/CLJC over the WebGPU canvas | browser DOM construction, motion/sound/RTC |
| **`kami-engine-app-sdk`** | **browser app chrome** | pure/portable math for motion/easing/spring, particle trajectories, sound presets, RTC spatialize, plus optional DOM widgets (`Slider`/…) | the game HUD *data model* (that is hud); engine ECS/render-IR (that is `kami-engine-sdk`) |
| **`kotoba-ui` / `uikit` / `appkit`** | **product UI design system** | app/web product screens (SwiftUI-like) | anything 3D/WebGPU |

Rule of thumb:

- **Author "what HUD shows"** → `kami-engine-hud` data
- **Run "how the browser chrome behaves"** (tweens, toasts, pickers, audio cues) → `kami-engine-app-sdk`
- **Ship a website/app shell unrelated to the 3D canvas** → `kotoba-ui` family

If those two engine pieces still feel too close in practice, a future
follow-up may fold pure math from `app-sdk` under `hud` or vice versa —
out of scope for this addendum (no merge yet; names already differ by role).

### `wasm-webcomponent` vs `kototama` Wasm runtime

| | **wasm-webcomponent** | **kototama** |
|---|---|---|
| Role | Thin **browser packaging** library | Full **Wasm tender / execution runtime** |
| Runs | Browser native `WebAssembly` | Primarily **JVM/Chicory** (`kototama.tender`); browser via its `web/` which *imports* wasm-webcomponent |
| Guest ABI focus | `kotoba` module imports (kgraph, actor:host ports, kami-ecs, gpu clear, …) as small JS host files | `actor:host` contract + caps + limits + tender; unikernel-style host |
| Scope | Drop-in WebComponent + host-import helpers; zero JVM | Contract validation, capability grants, RuntimeLimits, fuel, memory limits, deploy story |
| Origin | Extracted *from* kototama's `web/` PoC (ADR-2607061630 / 2607061850) so apps do not depend on the whole tender | Parent runtime of that extraction |

**Use wasm-webcomponent** when you only need "load this `.wasm` in a page and bind host imports."
**Use kototama** when you need the tender (caps, limits, JVM host, actor lifecycle) —
not as a second browser game host next to wasm-webcomponent.

### Updated canonical map (post-addendum)

```
L5 apps          kami-app-* / freeboard / …
L4 hosts         wasm-webcomponent          … browser (only wasm host for games)
                 kotoba.kami-host           … portable ECS host
                 kototama.tender            … JVM actor:host tender (non-rs)
L3 authoring     kami-engine-sdk            … was -clj; ECS / scene / render-IR
                 kami-engine-app-sdk        … browser chrome helpers
                 kami-engine-hud            … HUD widget data IR
                 kami-engine-input-map      … stick/deadzone
L2 render        webgpu + org-w3-webgpu + render
L0 contracts     kami-engine / kami-contracts
```

Pruned: `kami-engine-sdk-svelte`, `kami-engine-host-rs`.

