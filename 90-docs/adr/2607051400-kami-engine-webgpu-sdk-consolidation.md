# ADR-2607051400: kami-engine の WebGPU/SDK 系統合 — `org-w3-webgpu` 新設と `kami-engine-sdk` の cljc/Reagent 移行

**Status**: accepted — Phase 0 (検証) 完了 2026-07-05、Phase 1-4 は owner 確認のうえ着手
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/{webgpu,webgpu-rs,webgpu.pre-canonical-rename,kami-webgpu,kami-engine-sdk,kami-engine-sdk-clj}`

## Context

kami-engine の「AAA品質のリアルタイム3D」再設計を検討する過程で、kotoba-lang 配下
の既存 repo を調査した結果、想定より再設計が進んでいることが判明した:

- **ADR-2607010930（clj-wgsl migration）** で Rust workspace は既に撤去され、
  ~116 crate が「EDN権威 + CLJ→WASM + native-Rust hot core」の3層で `.cljc` へ
  再構築中（113/116 restored, Phase 7）。今回検討していた
  Author-time/Build-time/Runtime の3層分割は、この ADR が既にほぼ実装している。
- **`kami-engine-sdk-clj`** が実質的に「cljc化した kami-engine-sdk」そのものと
  して既に存在する。設計思想は "clj is the brain, Rust is the GPU arm." —
  `kami.ecs`（Datalevin永続ストア→毎フレームdense in-memory ECSへ投影）、
  `kami.schedule`（coarse-grainedなsystem scheduleをEDNとして記述しhostが実行、
  Build→Runtime lowering そのもの）、`kami.render`/`kami.wgsl`/`kami.ipc`
  （毎フレーム純データrender-IRをzero-copy columnar IPCへpack）、`kami.gpu` +
  `IGpuBackend` protocol（実GPU実行はRust `kami-render` に委譲、wgpuの再実装は
  しない）。66 tests / 308 assertions 通過、`:local/root` 依存なし。README に
  「`kami-engine-sdk`（Svelte）とは別物」と明記済み。
- **`kami-engine-sdk`**（Svelte、`orgs/kotoba-lang/kami-engine-sdk`、
  `@etzhayyim/kami-engine-sdk`）は ECS/renderランタイムではなく、UIコンポーネント
  + headless builder の grab-bag: `builders`/`call`/`components`（Svelte）/
  `data`/`document`/`genko`（漫画エディタ）/`gsplat`（Gaussian Splatプレビュー）/
  `manufacturing`/`trackpad`/`types`/`webvr`。
- **Phase 0 で検証した結果、`webgpu` と `kami-webgpu` の正誤関係は当初の推測と
  逆**だった。GitHub側で `kami-webgpu` は既に `webgpu` へ rename 済み
  （`gh repo view kotoba-lang/kami-webgpu` が `name:"webgpu"` へ redirect）。
  つまり **`webgpu` が canonical**（344 files、HEAD 2026-07-03、活発に開発中）。
  ローカルの `kami-webgpu` checkout は `webgpu` HEAD の純粋な祖先（未コミット差分
  なし、独自内容なし）で、rename前の古い checkout が残っているだけ。
  `webgpu-rs` は GitHub 側で既に **archived 済み**（ローカル checkout も未コミット
  差分なし）。`webgpu.pre-canonical-rename` は remote 未設定・全ファイル
  untracked（一度も push されていない rename 前の初期 scaffold）。3つとも
  ローカル checkout の清掃のみで済む（GitHub側操作は不要）。
- **Phase 0 で新たに発見した想定外の課題**: canonical な `webgpu` repo の
  `src/kami/` 配下に `materialx.cljc`/`dxf.cljc`/`verilog.cljc`/`scad.cljc`/
  `graphql.cljc`/`dance.cljc`/`cartpole_math.cljc`/`scene2d.cljc` など、
  WebGPUと無関係かつ**既に同名の独立 repo が kotoba-lang 直下に存在するはずの
  名前空間**が大量に同居している。ADR-2607010930 のRust撤去・復元wave時に
  `webgpu` が一時ステージング場所として使われ、個別repoへの切り出しが未完了の
  まま残った可能性が高い。これは本ADRのscope外の、より大きい別課題として
  切り出す（本ADRでは着手しない）。
- `kami-webgpu` 自体は「Declarative WebGPU from EDN — hiccup for the GPU」を
  謳うEDN render-IR層だが、内部の `kami/webgpu.cljs`（executor: `init!`/`draw!`）
  は生のブラウザWebGPU JS API（`GPUDevice`/`GPUBuffer`/pipeline descriptor等）を
  直接叩いている。これは `org-khronos-glb`（GLBバイナリコーデック）・
  `org-materialx`（MaterialX XML標準ノード定義）・`org-openusd` と同型の
  「外部標準仕様の低レベル実装を独立repoに切り出し、kami-engine側のEDN語彙が
  それを消費する」パターンが未適用の状態だった（WebGPUはW3C仕様）。

## Decision

### 1. `org-w3-webgpu` を新設する

`org-khronos-glb`/`org-materialx`/`org-openusd` と同じ命名規約（外部標準化団体
+ 仕様名）を WebGPU（W3C仕様）にも適用する。canonical repo である `webgpu` の
executor 部分（生WebGPU JS API呼び出し）をここへ切り出し、`webgpu` は
render-IR(EDN)→`org-w3-webgpu` 呼び出しへ委譲する薄い consumer 層として残す
（`kami-engine`が`org-materialx`のMaterialX語彙を消費するのと同型）。

### 2. ローカルの stale checkout を退役する（GitHub側操作は不要）

`kami-webgpu`（`webgpu`の祖先、独自内容なし）・`webgpu-rs`（GitHub側で既に
archived済み）・`webgpu.pre-canonical-rename`（一度もpushされていない孤児
scaffold）の3つのローカルディレクトリを削除する。GitHub側は既に正しい状態
（`webgpu`が生きており、`webgpu-rs`はarchived）なので、rename/archive操作は
不要でローカル清掃のみ。

### 2.5. `webgpu` repo内の名前空間ステージング問題（別ADR行き、本ADRではスコープ外）

canonical な `webgpu` の `src/kami/` に `materialx`/`dxf`/`verilog`/`scad`/
`graphql`/`dance`/`cartpole_math`/`scene2d` 等、既に同名の独立 repo が存在する
はずの名前空間が同居している。ADR-2607010930のRust撤去・復元wave時の一時
ステージング残骸である可能性が高いが、本ADRのwebgpu/SDK統合スコープを超える
ため、別途調査・別ADRで扱う。

### 3. `kami-engine-sdk`（Svelte）を全面 cljc/Reagent 移行する

- **UI層**: Svelte 5 コンポーネント（`components`/`genko`のUI部分/`trackpad`/
  `webvr`のUI部分）を ClojureScript + Reagent へ書き換える。
- **非UIロジック**: `builders`（headless scene builder）/`data`/`document`/
  `gsplat`（Gaussian Splatプレビューブリッジ）/`manufacturing`（計画ヘルパー）
  は `kami-engine-sdk-clj` へ統合し、"clj is the brain" の既存境界に合流させる。
- **`genko`**: Phase 0 で確認済み — `kami-genko` は既に独立 repo として存在し、
  `kami-engine-sdk-clj` には genko 名前空間は無い（重複リスクなし）。Svelte側の
  `genko` フォルダは `kami-genko` へ寄せる。
- 最終形が「`kami-engine-sdk-clj` 一本（UIも含む）」か「headless=
  `kami-engine-sdk-clj` / UI=別repo」の2repo構成かは execution phase 4 で確定する。

## Execution（phase ごとに owner 確認）

1. **Phase 0 — 検証【完了 2026-07-05】**: git remote・commit履歴・
   `gh repo view` で確認した結果、`webgpu` が canonical（GitHub側で
   `kami-webgpu`→`webgpu`へrename済み。`kami-webgpu`ローカルchecknoutは
   `webgpu` HEADの純粋な祖先で独自内容なし）、`webgpu-rs`はGitHub側で既に
   archived、`webgpu.pre-canonical-rename`は一度もpushされていない孤児
   scaffoldと判明。3つともローカル削除のみで完結する（GitHub側操作は不要）。
   `kami-engine-sdk`のgenkoは`kami-genko`（既存独立repo）と重複、
   `kami-engine-sdk-clj`にはgenko名前空間無しを確認。追加で`webgpu` repo内の
   名前空間ステージング残骸を発見（2.5節、別スコープ）。
2. **Phase 1 — `org-w3-webgpu` scaffold**: ADR起票→scaffold→git init→GitHub
   repo作成+push→manifest登録の標準フロー（CLAUDE.md標準作業、確認不要）。
3. **Phase 2 — `webgpu` からexecutor分離**: 生WebGPU JS API呼び出し部分を
   `org-w3-webgpu` へ移し、`webgpu` はそれを呼ぶ薄い consumer 層に。
4. **Phase 3 — stale checkoutのローカル削除**: `kami-webgpu`/`webgpu-rs`/
   `webgpu.pre-canonical-rename` のローカルディレクトリを削除。Phase 0で
   安全確認済みだが、既存チェックアウトの削除を伴うため owner 確認のうえ実行。
5. **Phase 4 — `kami-engine-sdk` cljc移行**: genkoは`kami-genko`へ寄せる→
   非UIロジックの `kami-engine-sdk-clj` 統合→UIのReagent書き換え。規模が
   大きいため独立セッションでスコープを切って進める。

## Consequences

- (+) WebGPU生API層とkami-engine独自EDN語彙層が、他の外部仕様（GLB/MaterialX/
  USD）と同じ命名・分離パターンに揃う。
- (+) `webgpu`/`webgpu-rs`/`webgpu.pre-canonical-rename` という3つの紛らわしい
  重複が解消される。
- (+) Svelte依存が最終的に無くなり、"clj is the brain, Rust is the GPU arm"
  の設計原則にSDK層全体が揃う。
- (−) Phase 4（Svelte→Reagent移行）は規模が大きく、この ADR の時点では未着手。
  実行時に genko/kami-genko の重複解消が前提条件としてブロッカーになりうる。
- (−) `webgpu`/`webgpu-rs` の退役前に、他repoからの `deps.edn` 参照が残って
  いないか要確認（本 ADR 時点では未調査、Phase 0 に含める）。

## Alternatives Considered

- **`kami-webgpu` 内に生API層を残したまま新設をしない**: 却下。
  org-khronos-glb/org-materialx/org-openusdの既存パターンと非対称になり、将来
  他の native adapter（Rust/wgpu-native等）がWebGPU仕様に直接バインドしたい
  場合の再利用先が無い。
- **`kami-engine-sdk`（Svelte）をそのまま残し、`kami-engine-sdk-clj` とは
  役割分担のみで共存させる**: 却下（owner判断）。Svelte依存を最終的に無くし
  UIもcljc/Reagentへ統一する方針を優先。

## Related

- ADR-2607010930: clj-wgsl migration（3層分割・`kami-engine-sdk-clj`の由来）
- ADR-2607051200: kotoba-lang の "*ui*" 系リポジトリ名衝突解消（同型の命名整理）
- ADR-2606281000: `num` — GPU compute substrate としてのCLJC設計
