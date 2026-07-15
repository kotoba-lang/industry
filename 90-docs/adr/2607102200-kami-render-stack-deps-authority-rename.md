---
id: adr-2607102200-kami-render-stack-deps-authority-rename
title: "ADR-2607102200: kami-engine 3D/WebGPU/WebGL 2.0/render 系統の依存・権限マップと rename / cleanup"
status: accepted
doc_type: adr
topic: kotoba-lang-repo-boundaries
authoritative: true
last_verified: 2026-07-10
authoritative_for:
  - kotoba-lang 3D / WebGPU / WebGL 2.0 / render 系統の canonical 依存レイヤと責任境界
  - repo-wide 3D 実装で kami-engine stack を必須にする規則と完了条件
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

# ADR-2607102200: kami-engine 3D/WebGPU/WebGL 2.0/render 系統の依存・権限マップと rename / cleanup

- **Status**: accepted (2026-07-10)
- **Deciders**: Jun Kawasaki
- **Scope**: kotoba-lang 配下の 3D / WebGPU / WebGL 2.0 / render / script-host / UI SDK 系統

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
L2 render IR     webgpu                     … EDN render-IR + WebGPU executor (primary)
                 webgl                     … 共通 EDN render-IR subset の WebGL 2.0 executor (fallback)
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
2. **EDN render-IR + browser draw** — **WebGPU + WGSL first** は `webgpu`
   （`kami.webgpu` / `kami.webgpu.ir`）、**WebGL 2.0 + GLSL ES 3.00 fallback** は
   `webgl`（`kami.webgl`）。両 backend は同じ IR を消費し、WebGL 2.0 はその共通描画
   subset を実行する（WebGPU 固有の compute / storage 機能を擬似実装しない）。
3. **ECS / scene authoring / IPC pack** — `kami-engine-sdk-clj` のみ。Svelte SDK は触らない。
4. **WASM host（guest 実行）** — browser: `wasm-webcomponent`、native: `kami-engine-host-rs`。
5. **contracts / fixtures** — `kami-engine`。native 実装をこの repo に戻さない（no-Rust CI）。
6. **nested monorepo path は deps に書かない** — 常に standalone `orgs/kotoba-lang/<repo>`。

### 1.1 Repo-wide mandatory 3D path（2026-07-10 addendum）

本 ADR のレイヤ図は推奨構成ではなく、**workspace 内のすべての3D実装に対する強制境界**
である。modeling / animation / CAD / BIM / sculpt / visualization / game の違いによって
別エンジンを作ってはならない。

| concern | 必須の authority | app が所有してよいもの |
|---|---|---|
| geometry / topology / operation | 対応する `kami-engine-*` portable domain (`.cljc` / `.kotoba`) | command dispatch、選択状態の表示 |
| guest execution | browser は `wasm-webcomponent` + `kotoba wasm emit`、native は canonical host | lifecycle orchestration |
| scene / animation / simulation | `kami-engine-sdk-clj` と対応 domain engine | editor workflow、panel state |
| browser GPU | primary: WebGPU (`webgpu` → `org-w3-webgpu`)、fallback: WebGL 2.0 (`webgl` / `kami.webgl`) | viewport の配置と resize、capability による backend 選択 |
| shader / pipeline / mesh upload | WebGPU + WGSL: `webgpu` / `kami.webgpu.mesh`、WebGL 2.0 + GLSL ES 3.00: `webgl` | engine API の呼び出し |
| UI chrome | `html` + `css`、共通 UI は `kotoba-ui` / `uikit` / `appkit` | toolbar、panel、menu、shortcut profile |
| interchange | canonical spec repo (`org-openusd` / `org-khronos-gltf` / `org-vrmc-vrm` 等) | import/export workflow |

したがって `kami-app-*` は 3D domain を再実装せず、engine command → immutable state / EDN
→ render-IR → WebGPU / WebGL 2.0 executor という一方向の経路を統合する。HTML `<canvas>` は
WebGPU または WebGL 2.0 の presentation surface としてのみ許され、Canvas 2D context は
authoritative 3D renderer ではない。DOM / SVG / CSS 3D は overlay、diagram、thumbnail、
明示された degraded fallback に限定する。

**禁止事項**

- app 内の独自 mesh / scene / picking / renderer と、生 JavaScript / TypeScript / Rust に
  よる geometry core の複製
- Three.js / Babylon.js 等を第2の production engine として導入
- CSS transform、静止画、Canvas 2D だけで production 3D tool を名乗る実装
- 生 `navigator.gpu` / WebGL 2.0 context bootstrap や WGSL / GLSL ES / pipeline の app への複製
- screenshot / visual smoke だけで modeling operation の成立を判定

**各 3D app の最低検証ゲート**

1. domain engine の topology / scene / animation data assertion
2. create/edit/undo-redo/save-export の実データ round-trip
3. WASM guest と host contract の parity（WASM を持つ機能）
4. 実ブラウザ WebGPU E2E。macOS runner は Metal backend を使う
5. 同一 scene / operation の共通描画 subset に対する WebGL 2.0 + GLSL ES 3.00 fallback E2E
6. GitHub Pages の load / interaction smoke test
7. WebGPU unavailable 時は capability 判定で WebGL 2.0 に切り替え、両方 unavailable の時だけ
   degraded state を明示（fake renderer には切り替えない）

規格名と実装名を混同しない。`WebGPU` / `WGSL` / `WebGL 2.0` / `GLSL ES 3.00` が
規格上の表記である。`wgpu` / `WGPU` は native implementation/substrate 名としてのみ使い、
Web標準名としては使わない。`WSGL` と `GLSLES` は本ADRの用語として採用しない。

例外は accepted ADR に対象 repo、理由、期限、代替 authority、撤去条件を記載した場合だけ
許可する。temporary fallback はコードと UI の双方で `non-authoritative` と表示する。

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

1. **Guest binary** — `.kotoba` → `kotoba wasm emit` → real `.wasm` AOT (first path)
2. **Host / tender runtime** — **kototama** on the runtime ladder
   `kotoba wasm AOT` → `clojurewasm` → ClojureScript → nbb
   (ADR-2607100100). **JVM/Chicory (`kototama.tender` :clj) is a demoted
   compat suite only**, not the design premise.
3. **Browser packaging** — `wasm-webcomponent` (extracted from kototama `web/`)
   runs the same AOT `.wasm` on the browser's native `WebAssembly` engine.
4. **Portable ECS host vocabulary** — `kotoba.kami-host` (`.cljc`; first wire
   is ClojureScript / native WASM, not Chicory)

`kami-engine-host-rs` is archived. Do not reintroduce a parallel rs host.
Do not teach "kototama = JVM/Chicory" — that inverts ADR-2607100100.

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
| Role | Thin **browser packaging** for AOT `.wasm` | **Wasm tender runtime** — hosts `kotoba wasm emit` guests under caps/limits |
| First runtime | Browser native `WebAssembly` (always) | **`.kotoba` wasm AOT** on native WASM; next **clojurewasm**; cljs/nbb as needed |
| Demoted | — | **JVM/Chicory** (`kototama.tender` :clj) = compat / CI harness only (ADR-2607100100) |
| Guest ABI | `kotoba` module imports as small host files (kgraph, actor:host ports, kami-ecs, …) | Same family: `kototama.contract` caps + tender vocabulary; unikernel-style host |
| Scope | Drop-in WebComponent + host-import helpers | Contract, grants, RuntimeLimits, fleet, deploy; **not** "the JVM product" |
| Origin | Extracted *from* kototama `web/` so apps do not pull the whole tender | Parent tender of that extraction; browser path *consumes* wasm-webcomponent |

**Use wasm-webcomponent** to put an AOT `.wasm` on a page with host imports.
**Use kototama** as the tender/runtime that defines how guests are hosted
(caps, limits, fleet) — first on **wasm AOT / clojurewasm**, not Chicory.
They are not competing JVM vs browser products: guest is AOT wasm; packaging
for the browser is wasm-webcomponent; tender policy is kototama.

### Updated canonical map (post-addendum)

```
L5 apps          kami-app-* / freeboard / …
L4 hosts         kototama                   … tender runtime (first: .kotoba wasm AOT / clojurewasm;
                                              JVM/Chicory demoted compat)
                 wasm-webcomponent          … browser packaging of AOT .wasm (from kototama web/)
                 kotoba.kami-host           … portable ECS host (cljs/native WASM first)
L3 authoring     kami-engine-sdk            … was -clj; ECS / scene / render-IR
                 kami-engine-app-sdk        … browser chrome helpers
                 kami-engine-hud            … HUD widget data IR
                 kami-engine-input-map      … stick/deadzone
L2 render        webgpu + org-w3-webgpu + render
L0 contracts     kami-engine / kami-contracts
```

Pruned: `kami-engine-sdk-svelte`, `kami-engine-host-rs`.

## Addendum 2 (2026-07-10) — merge `kami-engine-hud` into `kami-engine-app-sdk`

Owner: "do it" on consolidating the DOM-overlay split.

### Decision

**`kami-engine-hud` is merged into `kami-engine-app-sdk`.** One package owns
browser app chrome for kami-engine WebGPU apps:

| Namespace | Origin | Role |
|---|---|---|
| `kotoba.ui` | ex-`kami-engine-hud` | EDN hiccup HUD (`:panel`/`:bar`/`:minimap`/`:text`, `mount!`/`render!`) |
| `kami-ui-sdk.*` | ex-`kami-ui-sdk` JS | motion / effect / sound / rtc / widgets math (+ DOM widgets) |

### Why merge (not keep two repos)

The previous split ("data IR" vs "chrome behavior") was correct as layers
inside one product surface, but wrong as **two GitHub repos** — consumers could
not tell which to depend on, and `kotoba.ui` is itself a browser DOM executor
(same host class as `kami-ui-sdk.widgets`). Authority is one: **app chrome over
the WebGPU canvas**.

Still separate (do not merge):

- **`kami-engine-sdk`** — ECS / scene / render-IR (brain, not chrome)
- **`kotoba-ui` / `uikit` / `appkit`** — product design system, not 3D canvas
- **`kami-engine-hud-gpu`** — optional GPU HUD backend; stays sibling for now
  (README notes the merge)

### Execution

1. Copy `kotoba.ui` + tests into `kami-engine-app-sdk` (51→ tests green including HUD).
2. `kami-engine-hud` → deps-only shim re-exporting `kami-engine-app-sdk`, then **archived**.
3. Prefer `kami-engine-app-sdk` in all new deps; old `:local/root "../kami-engine-hud"` still resolves via shim until clones drop it.

### Updated L3 chrome row

```
L4 hosts         kototama / wasm-webcomponent / kotoba.kami-host
                 (wasm AOT first; Chicory demoted — addendum 3)
L3 chrome        kami-engine-app-sdk   … HUD (kotoba.ui) + motion/sound/effect/rtc/widgets
                 kami-engine-input-map … stick/deadzone
L3 brain         kami-engine-sdk       … ECS / scene / render-IR
```

Pruned/archived: `kami-engine-hud` (shim remains on GitHub for redirect + legacy path).

## Addendum 3 (2026-07-10) — kototama is .kotoba wasm AOT / clojurewasm, not JVM/Chicory

Owner correction: framing kototama as "primarily JVM/Chicory" was wrong and
conflicts with the repo-wide runtime priority (ADR-2607100100 / CLAUDE.md):

```
kotoba wasm runtime > clojurewasm > ClojureScript > nbb
(JVM is demoted — last resort / explicit compat only)
```

### Correct authority

| Layer | What | First path | Demoted |
|---|---|---|---|
| **Guest** | game/app logic | `.kotoba` → `kotoba wasm emit` → AOT `.wasm` | interpreting CLJ on JVM as the product path |
| **Tender (kototama)** | host guest under caps/limits | native WASM host of that AOT binary; **clojurewasm** when host-import FFI allows | `kototama.tender` Chicory `:clj` suite |
| **Browser package** | put `.wasm` on a page | **wasm-webcomponent** (from kototama `web/`) | hand-rolled JS hosts |
| **ECS host lib** | kami:engine vocabulary | `kotoba.kami-host` portable `.cljc` (cljs / native WASM wire) | Chicory-only host |

`kototama.tender` (Chicory) remains useful as a **compat / verification**
harness (bit-exact fixtures, CI) — the same pattern as ADR-2607100030
addendum 2 for `kami-host` — but it is **not** what "using kototama" means
for new work.

Historical note: ADR-2607022900 / 2607062330 landed Chicory as the then-
working tender. ADR-2607100100 and this addendum **re-rank** that path
downward without deleting the code.

## Addendum 4 (2026-07-10) — wgsl SSoT + kototama README alignment

### `kotoba-lang/wgsl` is the hiccup→WGSL SSoT

| Namespace | Home | Role |
|---|---|---|
| `kami.wgsl` | **`wgsl`** package | Implementation |
| `kotoba.wgsl` | **`wgsl`** package | Facade re-export (legacy require) |
| `kami.wgsl-emit` | `kami-engine-sdk` | Different dialect (map IR → WGSL for WIT) |

`webgpu` no longer vendors `src/kami/wgsl.cljc`; it depends on `../wgsl`
(deps.edn + bb.edn classpath). Domain packages (`sprite-gpu`, `shaders`,
`sky`, `render-shaders`) keep requiring `kotoba.wgsl` via the facade.

### kototama README

Aligned with addendum 3 / ADR-2607100100: first path = `.kotoba` wasm AOT
+ native WASM (wasm-webcomponent); clojurewasm next; Chicory demoted.

## Addendum 5 (2026-07-10) — webgpu mini-monorepo dedup (extract SSoT)

### Policy

**`webgpu` is the GPU executor + browser harness, not a grab-bag of domain modules.**
Render-domain pure data (`kami.*` shader/quad/scene assembly) lives in sibling
packages. `webgpu` depends on them; consumers that only need quads/scene2d
depend on the thin package.

### Ownership table (post-extract)

| Namespace | Package (SSoT) | Notes |
|---|---|---|
| `kami.wgsl` / `kotoba.wgsl` | **wgsl** | hiccup→WGSL (addendum 4) |
| `kami.sprite-gpu` / `kotoba.sprite-gpu` | **sprite-gpu** | rect half-extents fix |
| `kami.sky` / `kotoba.sky` | **sky** | gradient pass |
| `kami.shaders` / `kotoba.shaders` | **shaders** | lit/shadow EDN |
| `kami.render-shaders` / `kotoba.render-shaders` | **render-shaders** | open-world EDN |
| `kami.scene2d` / `kotoba.scene2d` | **scene2d** | frame assembly |
| `kami.text` | **scene2d** | GPU 7-segment (≠ `kotoba-lang/text` string utils) |
| `kami.sprite2d.layout` | **sprite2d** | pure layout |
| `kami.webgpu*` / `kami.webgl*` / `kami.gpu` / `kami.pipelines` / harness | **webgpu** | stays |

### Left in webgpu on purpose (not extracted this pass)

`kami.sprite2d` (Canvas2D cljs), `kami.ui`, `kami.input`, `kami.audio`,
`kami.dance`, `kami.fsm`, `kami.level`, `kami.physics`, `kami.netsync`,
`kami.host`, `kami.playwright`, `kotoba.webgpu-rs.*` — either browser-only
harness, still staging, or not near-duplicate of a live standalone SSoT.

### Verification

- Per-package unit tests green for extracted packages where present
- `bb test` in webgpu green after deps rewiring

## Addendum 6 (2026-07-10) — dance / webgl / gpu SSoT + archived west group

### Problem

Addendum 5 extracted pure render data packages (`wgsl`, `sprite-gpu`, `sky`, …)
but left **`kami.dance` / `kami.webgl` / `kami.gpu` vendored in `webgpu`**.

Meanwhile the sibling packages `dance` / `webgl` / `gpu` already had **thin
`kotoba.*` facades that re-exported `kami.*` from webgpu** — an inverted
dependency (facade package → monorepo). That:

1. Created a cycle risk if webgpu ever depended on those packages
2. Left standalone consumers of `kotoba-lang/gpu` etc. broken without webgpu
3. Kept the WebGL2 executor and dance frame-ir inside the WebGPU executor repo

Also, archived shims (`kami-engine-hud`, `kami-engine-host-rs`,
`kami-engine-sdk-svelte`) still lived in the default `+kotoba-lang` west group,
so every `west update` tried to fetch retired paths.

### Decision

| package | SSoT ns | role | deps |
|---|---|---|---|
| **`gpu`** | `kami.gpu` | capability-gated pipeline IR (`resolve-graph` / tiers) | none (pure) |
| **`webgl`** | `kami.webgl` (+ `kami.webgl.glsl`) | WebGL2 executor for the same render-IR | `gpu`, `sprite-gpu` |
| **`dance`** | `kami.dance` | pure dance stage → frame-ir | none (pure) |
| **`webgpu`** | `kami.webgpu` (+ harness) | WebGPU executor + game domains | + `gpu`, `webgl`, `dance` |

- `kotoba.gpu` / `kotoba.webgl` / `kotoba.dance` remain **thin facades**.
- Dependency edge is **webgpu → siblings**, never reverse.
- Live consumer `network-isekai` bb classpath points at `kotoba-lang/dance`
  (and gpu/webgl/sprite-gpu/wgsl/expr as needed), not only `webgpu/src`.

### Archived west group

`manifest/repos.edn` gains `:archived` (same shape as `:datalad`):

| path | reason |
|---|---|
| `orgs/kotoba-lang/kami-engine-hud` | merged into app-sdk (addendum 2); deps shim only |
| `orgs/kotoba-lang/kami-engine-host-rs` | rs host retired (addendum 1) |
| `orgs/kotoba-lang/kami-engine-sdk-svelte` | Svelte path retired (addendum 1) |

`group-filter` includes **`-archived`**. `gen-west-manifest.cljs` emits
`groups: [archived]` + `userdata.archived: true`. Default `west update` skips
them; opt-in with `west update --group-filter +archived <name>`.

### Left in webgpu (not this wave)

Canvas2D `kami.sprite2d` painter, `kami.pipelines`, game harness
(`physics`/`fsm`/`netsync`/…), `kotoba.webgpu-rs` staging, Playwright harness.

### Pins landed (child mains)

| repo | tip |
|---|---|
| gpu | `20bdb945ba1916a4d11caabfbf62778f6a293b1e` |
| webgl | `7ca96c7f1afc2e4df81ecbdcb0ad04ba5c084e02` |
| dance | `4b6d263047530961a0960ad9fc9bd6d870e760f4` |
| webgpu | `a34418a6b244fe20b4fc126510c69865e40b48a3` |
| network-isekai | `ac50081f07834be83eab5053b9e1d55deb5d0d8f` |

## Addendum 7 (2026-07-10) — physics / fsm / netsync / pipelines / sprite2d Canvas2D SSoT

### Problem

After addendum 6, pure **game-domain** modules still lived in `webgpu`, and
several sibling packages still **re-exported from webgpu** (inverted dep):

| package | pre-state | issue |
|---|---|---|
| `physics` | `kotoba.physics` → `kami.physics` in webgpu | inverted; `host` depended on whole webgpu for collision |
| `fsm` | full `kotoba.fsm` copy | drift risk vs webgpu `kami.fsm` |
| `netsync` | fuller package (prediction) vs thinner webgpu | two SSoTs |
| `pipelines` | package validation helpers; webgpu had `parse-rust` | split surface |
| `sprite2d` | layout already extracted; Canvas2D painter still in webgpu | incomplete extract |

### Decision

| package | SSoT ns | notes |
|---|---|---|
| **physics** | `kami.physics` | collision layers/matrix; pure `.cljc` |
| **fsm** | `kami.fsm` | EDN state machines; pure `.cljc` |
| **netsync** | `kami.netsync` | schema + snapshot/interp + **pred-*** (package superset) |
| **pipelines** | `kami.pipelines` | EDN table + `valid?`/`spec` + `parse-rust` |
| **sprite2d** | `kami.sprite2d` + layout | Canvas2D painter (`.cljs`) + pure layout |
| **webgpu** | executor + glue | depends on the above; no reverse edge |
| **host** | — | depends on `physics` directly (not webgpu) |

`kotoba.*` remain thin facades. Edge is always **consumer → SSoT package**.

### Left in webgpu (browser host / staging)

`kami.webgpu*` executor, `kami.host`/`input`/`ui`/`audio`, `kami.level`,
`kami.cartpole-math`, `kami.playwright`, `kotoba.webgpu-rs` staging.

### Pins landed

| repo | tip |
|---|---|
| physics | `83d2cfcb732e28efaf9b03f9bb472f1e4bd7ef30` |
| fsm | `23f7b817ea6b9b2c6660dc98f37bdfc47586a82a` |
| netsync | `4c3f8045f76cd18eeae5ff231089a1dd336b4351` |
| pipelines | `0e2aaf67e306c8942a5b5d12cb9dafb783ec3c7e` |
| sprite2d | `ca031b0f5611c611557b4ed4daccbe024baad2d7` |
| webgpu | `27f384184c141c1a5d0ed7928128d160225ee9dc` |
| host | `5d6c858d8083ddca6d128a4630586c331304db28` |

## Addendum 8 (2026-07-10) — thin webgpu executor + host surface + webgpu-rs restore

### Problem

After addendum 7, `webgpu` still bundled **game browser surface** and **retired-rs
domain**, so apps that only need collision/host still dragged the GPU executor (or
vice versa). `webgpu-rs` had been archived after a temporary merge into webgpu.

### Decision

| package | owns | notes |
|---|---|---|
| **host** | `kami.host` + `kami.input` + `kami.ui` + `kami.audio` | browser game surface; **no webgpu dep** |
| **level** | `kami.level` | spawns/zone/objective EDN |
| **playwright** | `kami.playwright` | headless Chromium eval harness |
| **webgpu-rs** | `kotoba.webgpu-rs.*` | pure-CLJC retired-rs domain; **unarchived** as SSoT |
| **webgpu** | `kami.webgpu*` + `kami.cartpole-math` | **thin browser executor** only |

Scaffold packages (empty clj-wgsl placeholders) join the **archived** west group:

- `kami-engine-render`, `kami-engine-core`, `kami-engine-io`,
  `kami-engine-engine`, `kami-engine-script-runtime`

### webgpu remaining tree

```
src/kami/webgpu.cljs + webgpu/{geometry,ir,mesh}
src/kami/cartpole_math.cljc   ; compute-golden harness
```

### Pins

| repo | tip |
|---|---|
| level | `d63ad49c14f2dd935ca9cf54cc4971e180527fe7` |
| playwright | `7019358de0ddccbbc20735e578dcc65ee2f11c44` |
| host | `86928e771266f4f788c0cf4ca738884c58e02767` |
| webgpu-rs | `d63532a775c4a174f06528779d6beae2bf62d321` |
| webgpu | `67e0f5479f4d15ff80cc973e1b4c8379920eaa95` |

## Addendum 9 (2026-07-10) — pure executor + nested monorepo cleanup + cartpole-math

### webgpu is now pure executor

```
src/kami/webgpu.cljs
src/kami/webgpu/{geometry,ir,mesh}.cljc/cljs
```

| moved out | to |
|---|---|
| `kotoba.webgpu-rs.*` | `kotoba-lang/webgpu-rs` (already SSoT; vendored copy removed) |
| `kami.cartpole-math` | **new** `kotoba-lang/cartpole-math` |

### kami-engine nested cleanup (executed)

| nested | action |
|---|---|
| `kami-engine-sdk-clj/` | **deleted** — SSoT `kami-engine-sdk` |
| `kami-webgpu-rs/` | **deleted** — SSoT `webgpu-rs` |
| `kami-script-runtime/` | fixture → `fixtures/script-runtime/survivors.clj`, nested **deleted** |
| tiny `kami-*-scene/` stubs | EDN → `fixtures/scenes/*`, stubs **deleted** |
| `kami-engine-clj/`, `kami-ui-sdk/`, `kami-render` shaders, `kami-game-scene` | **kept** |

### Scaffold retirement READMEs

`kami-engine-render` / `core` / `script-runtime` README now point to live packages
(west group already `archived` from addendum 8).

### Pins

| repo | tip |
|---|---|
| cartpole-math | `3934359eb9bfb4667d8d54921700b90a8e3fba75` |
| webgpu | `fa8394cd4a214a53011f40cc64d4833e1153c4d7` |
| kami-engine | `0462a89cf521fcf938e02a141d2073f45167c83b` |
| kami-engine-render | `8aa316c7a9ac622f67585d0057bcfedbee62b80e` |
| kami-engine-core | `b61a29ba734bcc1dbffb6a44cc37daba396253e6` |
| kami-engine-script-runtime | `001c4e3f150621680e0838e344ebebcdb8be41cb` |

## Addendum 10 (2026-07-10) — kami-engine-clj standalone + consumer rewire

### Problem

Guest game compiler (`kotoba.engine-clj`) still lived only as
`kami-engine/kami-engine-clj` nested path. Consumers:

- `kami-app-isekai` `:cljs-game` alias
- `network-isekai` deps.edn

…hard-coded the nested path, violating "nested monorepo path は deps に書かない".

### Decision

| action | detail |
|---|---|
| **New** `kotoba-lang/kami-engine-clj` | standalone SSoT; 52 tests green |
| Consumers | `../kami-engine-clj` (or `../../kotoba-lang/kami-engine-clj`) |
| Nested | replaced with README shim only |
| `kami-ui-sdk` (nested JS) | `MOVED.md` retirement path → `kami-engine-app-sdk` / `host` |

### Pins

| repo | tip |
|---|---|
| kami-engine-clj | `6ebf42078ae7e5017f27405509abba6f0e8a5fda` |
| kami-engine | `6136628d7517b4f0ddb4f9fa70f760dd484a94af` |
| kami-app-isekai | `df573cc4c08c0e8c99a8bf524224ddcaa5f2acd7` |
| network-isekai | `67f53a2e7ab3d92c676a3a0a6cf87663782ced54` |

## Addendum 11 (2026-07-10) — nested kami-ui-sdk JS retired + scaffold READMEs

### kami-ui-sdk nested JS

| | |
|---|---|
| **SSoT** | `kami-engine-app-sdk` (`kami-ui-sdk.*` + `kotoba.ui`) |
| **Demo-only JS** | `kami-engine/kami-web/vendor/kami-ui-sdk/*.js` |
| **Nested path** | README shim only (`kami-engine/kami-ui-sdk/README.md`) |

`graph.html` updated to load vendor scripts. Live apps must not depend on
nested `kami-ui-sdk/*.js`.

### Scaffold retirement READMEs (complete set)

All clj-wgsl empty scaffolds now document archived status:

`kami-engine-render`, `core`, `io`, `engine`, `script-runtime`

(west group already `-archived` for these from earlier addenda; pins advanced.)

### Pins

| repo | tip |
|---|---|
| kami-engine | `d8198ccd131336bf907c2e3da760e80c343949dd` |
| kami-engine-io | `e8f1d58d1425e97b6d62743ae50cc8574d3ed2cb` |
| kami-engine-engine | `bd7ca8b34690ed35f45d759c8313942480aab854` |

## Addendum 12 (2026-07-10) — demos / kami-web / consumer deps.edn sync

### kami-web authority

| layer | package / path |
|---|---|
| CLJC (`kotoba.web.*`) | `kotoba-lang/kami-web` `src/` |
| Static demos (graph/play/vendor UI JS) | `kotoba-lang/kami-web` `demos/` |
| Nested `kami-engine/kami-web` | README **shim only** |

Serve demos: `python -m http.server --directory demos`.

`deps.edn` gains optional `:demos` alias → webgpu / host / app-sdk / sdk.

### Consumer deps.edn updates

| consumer | change |
|---|---|
| **network-isekai** | `:deps` + `host` + `dance`; bb classpath lists domain SSoTs |
| **kami-app-isekai** | `:cljs-game` + `host`; webgpu coordinate rename |
| **ai-gftd-mangaka** | `kami-engine-sdk` + **new** `kami-mangaka-expression` (no nested) |
| **net-babiniku** | webgpu coordinate/docs only |

### New standalone

`kotoba-lang/kami-mangaka-expression` — patterns + `kami.mangaka.expression` (104 assertions green).

### Pins

| repo | tip |
|---|---|
| kami-web | `982a73488d70aab74d7f42a9c2aacee585fb0282` |
| kami-engine | `74b29738cfc19d4c66a8b56df07096990642c48e` |
| kami-mangaka-expression | `1fe312c6cdeb0d6aef3c20403d8748f9d4244795` |
| network-isekai | `7ab833408eb257426c98d775e84b6dc3cbe3005a` |
| ai-gftd-mangaka | `c90e9633d832ad63b2117a649e3f6a6178926869` |
| kami-app-isekai | `e335e5807aa39c7c3e45c8d1f6f0aa72a98891af` |
| net-babiniku | `015e7e38e9525212b27ec77bee008f8489b4642a` |


## Addendum 13 (2026-07-10) — nested mangaka-*-clj shims + consumer deps

### Nested monorepo → standalone (mangaka lettering/DTP/render)

| nested (README shim only) | SSoT |
|---|---|
| `kami-engine/kami-mangaka-text-clj` | `kotoba-lang/kami-mangaka-text` |
| `kami-engine/kami-mangaka-page-clj` | `kotoba-lang/kami-mangaka-page` |
| `kami-engine/kami-mangaka-render-clj` | `kotoba-lang/kami-mangaka-render` |
| `kami-engine/kami-mangaka-expression-clj` | `kotoba-lang/kami-mangaka-expression` (addendum 12) |

Standalone tips already held the live source (page: komawari tilt / effectLines;
render: datomize reconstitution). Nested copies were stale or monorepo-local only.

### Consumer deps.edn

| consumer | change |
|---|---|
| **kami-genko** | `:test` expression dep → `kami-mangaka-expression` repo (drop `kami-engine` + `:deps/root`) |
| **com-etzhayyim-sip** | `kami-engine-sdk` (rename from `-clj`) + fresh `kami-mangaka-{render,page}` SHAs; README documents git coords |
| **kami-mangaka-page** | advance `kami-mangaka-text` pin to main tip |

### Amenominaka

`kami-app-amenominaka` `:local/root` scene siblings (`kami-atmosphere-scene`,
`kami-vegetation-scene`, `kami-terrain-scene`, `kami-postfx-scene`, …) all
resolve; no content rewire needed this wave.

### Pins

| repo | tip |
|---|---|
| kami-engine | `1aa94d700d67a4bcd50e66c01859e87044962110` |
| kami-genko | `3fb00e686156926e9e62d0fa0fa5f003a5eeaf34` |
| kami-mangaka-page | `3e5ccbf6cf8ca28f9cafda6c6cb28b70fb4b59a5` |
| kami-mangaka-render | `3777d04d88071ab118f0623553a3ecfe52e33d26` |
| com-etzhayyim-sip | `44bdf25fba664907d67ab78b925d5a17ed553d1f` |

## Addendum 14 (2026-07-10) — never suffix repo names with `-clj`

### Rule (standing)

**Do not put `-clj` at the end of a GitHub repo / west project / package name.**

Clojure is the default language of this ecosystem; encoding it in the repo
name is noise and collides with the real role of the package. Prefer a
**role** name when the short name is already taken:

| bad (language tag) | good (role / domain) |
|---|---|
| `foo-clj` | `foo` |
| `kami-engine-clj` | `kami-engine-guest` (guest WASM compiler; `kami-engine` is the asset monorepo) |
| `kami-mangaka-scene-clj` | `kami-mangaka-scene-author` (EDN authoring; `kami-mangaka-scene` is the facade port) |

Allowed related forms that are **not** this rule:

- file extensions: `.clj` / `.cljc` / `.cljs`
- Clojure namespaces that already use a historical token (e.g. `kotoba.engine-clj`) — rename only when touching that API
- contract packages that literally mean **CLJC** portability (`*-cljc-contract`) — different token

New repos created without this rule are a process bug; fix before west register.

### Renames this wave (GitHub redirect kept)

| before | after |
|---|---|
| `kami-engine-clj` | **`kami-engine-guest`** |
| `kami-mangaka-scene-clj` | **`kami-mangaka-scene-author`** |
| `kotoba-issue-clj` | **`kotoba-issue`** |
| `kotoba-ledger-clj` | **`kotoba-ledger`** |
| `kotoba-procedure-clj` | **`kotoba-procedure`** |
| `sha256d-clj` | **`sha256d`** |

Consumers updated: `local-manimani`, `cloud-itonami` (deps coords).  
`path-overrides` maps old paths → new for west/local checkouts.

### Pins

| repo | tip |
|---|---|
| kami-engine-guest | `86a9b795ceac9c064f364727802d9901319b58b1` |
| kami-mangaka-scene-author | `8abd2668baa51060ae301b2b8e6f051b3d684430` |
| kotoba-issue | `676aee4514df90c9313578de9688bdf230021941` |
| kotoba-ledger | `11b1ab84a1dcb4c0594944d5d82ab732aa62f031` |
| kotoba-procedure | `5a7a0493cfb1cc7b91b56af6be27971070ffb410` |
| sha256d | `0ebf167fb19b45175b1fa84c989db5ecb624b302` |
| local-manimani | `10509a44b9082fe1cec2b6c54236b5abfafd4832` |
| cloud-itonami | `e1366d9e43d30a0ba8537afeb32979ef8a506cdb` |
