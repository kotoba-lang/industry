# ADR-2607051400: kami-engine の WebGPU/SDK 系統合 — `org-w3-webgpu` 新設と `kami-engine-sdk` の cljc/Reagent 移行

**Status**: accepted — Phase 0/1/2/3 完了、Phase 4 は genko部分を調査完了（コード変更
見送り）、残り（builders/data/UI移行）は ADR-2607051510 へ引き継ぎ（2026-07-05）
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
2. **Phase 1 — `org-w3-webgpu` scaffold【完了 2026-07-05】**: ADR起票→scaffold
   （`w3.webgpu` — `webgpu`の`kami/webgpu.cljs`executorが呼ぶ生API呼び出し
   全部をカバーする1:1薄いラッパー、記述子はJSオブジェクトのまま・Clojure map
   自動変換はしない）→git init→GitHub repo作成
   (`kotoba-lang/org-w3-webgpu`, public)+push→manifest登録
   （`repos.edn`の`:extra-projects`に追加、`nbb scripts/gen-west-manifest.cljs
   --entry org-w3-webgpu`でwest.yml反映、pin検証OK）。まだ`webgpu`側からは
   依存されていない（Phase 2待ち）。
3. **Phase 2 — `webgpu` からexecutor分離【完了 2026-07-05】**: `kami/webgpu.cljs`
   の生API呼び出し（`navigator.gpu`/`GPUDevice`/`GPUBufferUsage`/`GPUTextureUsage`/
   ...直接呼び出し35箇所）を全て`w3.webgpu`（`:local/root "../org-w3-webgpu"`）
   経由に置き換え。純粋な抽出（意味論の変更なし）。ブラウザ実機テストは無い
   （リポジトリに元々`kami.webgpu`用のテストが存在しない）ため、
   `cljs.build.api`での`:optimizations :none`コンパイルチェック
   （`src` + `../org-w3-webgpu/src`）で全参照の解決とarity一致を検証 —
   `kami.webgpu`/`w3.webgpu`に関する warning はゼロ（他の無関係な既存
   namespaceのwarningのみ、本変更と無関係）。`webgpu`のwest pinも
   前進済み。
4. **Phase 3 — stale checkoutのローカル削除【完了 2026-07-05】**:
   `kami-webgpu`/`webgpu-rs`/`webgpu.pre-canonical-rename` のローカル
   ディレクトリを削除済み。
5. **Phase 4 — `kami-engine-sdk` cljc移行【調査完了・genko部分はコード変更不要と判断
   2026-07-05】**: 実地調査の結果、当初の前提（`genko-embed.ts`と`kami-genko`は
   危険な重複で解消すべき）を修正する。
   - **規模の実測**: `kami-engine-sdk`全体で約17,700行（11サブフォルダ）。
     `genko`だけで8,334行（21ファイル）— 単なるdocument modelではなく
     Canvas/ChatPanel/認証/プロジェクト選択を含む本物のインタラクティブUI。
   - **`kami-engine-sdk-clj`への統合という前提の誤り**: `kami-engine-sdk-clj`
     は`kami.ecs`/`kami.render`/`kami.physics_compute`/`kami.schedule`/
     `kami.sim`等の**汎用ECS/物理/レンダリングのエンジンコア**であり、
     `kami-engine-sdk`の`builders`/`data`（`createBoneController`/
     `createEmotionAnalyzer`/pose-presets等、VRMアバター特有のドメイン
     ロジック、Svelte 5 runes `.svelte.ts`）をそのまま統合するのは抽象度の
     ミスマッチ。この部分の移行先は未確定のまま残す。
   - **genko重複の実態**: `genko-embed.ts`の`serializeDoc`/`deserializeDoc`
     と`kami-genko`の`write-doc`/`read-doc`/`normalize`を実装レベルで
     突き合わせた結果、これは事故的な重複ではなく**意図的な二層設計**と判明。
     `genko-embed.ts`は「外部依存ゼロの自己完結HTML文字列」を生成する必要が
     あり生JSをテンプレートリテラルに埋め込む設計。`kami-genko`は同じロジックを
     **サーバーサイド/他消費者（storyboardブリッジ等）から再利用可能にする**
     ための独立cljc SSoTで、`genko-embed.ts`を置き換える目的ではない。挙動は
     既に一致している（`kami-genko`の`normalize`が担う同期処理は
     `genko-embed.ts`側では`deserializeDoc`直後の`loadPage()`内で既に
     行われている）。
   - **`genko-embed.ts`を実際にKamiGenkoバンドルへ配線し直す判断**: 却下。
     shadow-cljsビルド動作が未確認、コンパイル済みJSバンドルを
     テンプレートリテラル文字列内に安全に埋め込む必要（エスケープリスク）、
     ブラウザ実機で視覚検証できない、という実装コストに対し、既に挙動が
     一致しているため得られる実益がほぼ無い（防御的normalizeが多少堅牢になる
     程度）。「危険な重複を解消する」という前提そのものが崩れたため、
     リスクを取ってまでのコード変更は見送る。
   - **未着手のまま残る**: `builders`/`data`/`document`/`manufacturing`/
     `trackpad`の移行先（`kami-engine-sdk-clj`直下ではなく、別namespace/
     別repoが必要）、UIのReagent書き換え、`components`（VRMビューアUI）。
     エコシステム全体のcljc/cljs中心の重複整理は ADR-2607051510 へ切り出す。

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
