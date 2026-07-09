# ADR-2607031200: kotoba-lang/kami-app-character-creator — EDN-native VRM キャラクターメイキング

**Status**: accepted（Phase 1〜4 実装完了 + Phase 5 でアーキテクチャ転換、下記参照）
**Date**: 2026-07-03（Phase 5 追記も同日 — `/loop` セッション1本で完結）
**Deciders**: Jun Kawasaki

## Context

オンラインゲームのキャラクターメイキング（体型・顔・髪型・衣装・カラーをユーザーが
スライダー/ピッカーで編集し、その場でプレビューし、保存・エクスポートできる画面）を
VRM アバターに対して用意したい、というオーナー要求。

設計前に実体調査（2セッションの fork 調査、`west update --fetch smart` で複数
`kotoba-lang/*` repo を最新化した上で実ファイルを読んだ）を行った。結論: **生成側の
データ/ロジック層はほぼ全て既に実在し、CLJC で書かれている**。ADR-2607010930
（Rust→Clojure/WGSL migration）で `kami-engine` の Rust ワークスペースは削除済みだが、
その中身は 1:1 で `kotoba-lang/{vrm,character,gltf,skeleton,render,webgpu,...}` に
CLJC 移植されている（旧 `kami-engine/CLAUDE.md` が描写する Rust クレート構成は現状を
反映していない — ADR-2607010930 参照）。

### 実在するもの（今回の調査で確認、file/namespace 単位）

- **`kotoba-lang/vrm`**（4,704 行の Rust → ~2,650 行 CLJC、17 namespace、25
  `deftest`/116 assertions）: VRM 1.0 の parse/decompose/compose/export が全部揃う。
  - `vrm.parse` — GLB→`VrmDocument`
  - `vrm.part` — アバターをパーツ分解（body/hair/face/…）
  - `vrm.compose` — パーツ群を1つの `VrmDocument` にマージ
  - `vrm.export` — `VrmDocument`→GLB バイト列（実 `.vrm` ファイル）
  - `vrm.humanoid` — ボーンマッピング ⇄ `kotoba-lang/skeleton` 互換 `{:bones […]}`
  - `vrm.spring` — スプリングボーン（揺れ物）物理シム
  - `vrm.constraint` — ノード拘束（aim/rotation/roll）ソルバ
  - `vrm.expression` — 表情ウェイト→morph/material/UV の解決器（VRM 1.0 プリセット
    17種: `happy angry sad relaxed surprised aa ih ou ee oh blink blinkLeft
    blinkRight lookUp lookDown lookLeft lookRight` + neutral、`vrm.vrm-types/
    expression-preset-table`）
  - `vrm.firstperson` / `vrm.compat`（VRM0.x→1.0）
- **`kotoba-lang/character`**（5,514 行の Rust → 全15ファイル移植、66 `deftest`/
  17,843 assertions）: MetaHuman 互換のパラメトリック・キャラクター生成 SDK。
  - `character.params/CharacterDef` — パラメトリックな入力ドキュメント
    （face/eyes/nose/mouth/hair/body/…）— **これがキャラクターメイキングの
    「ユーザーが編集するデータ」の型そのもの**
  - `character/generate-character` — `CharacterDef`→メッシュパーツ一式
  - `character.base-mesh`（FLAME 風ヘッドメッシュ）/ `character.blendshape`
    （**ARKit 52 ターゲット**の顔変形）/ `character.body`（体型+服メッシュ+VRM
    ヒューマノイド骨格）/ `character.material`（PBR）/ `character.hair` +
    `character.hair-gen`（プリセット/手続き型ストランド毛髪）/ `character.groom`
    （ストランドアセット+LOD+ヘアカード）/ `character.control-rig`（FACS AU→
    ボーン DAG）/ `character.anim-blueprint`（アニメ・ステートマシン）/
    `character.metahuman`（46 FACS AU、LOD0-3、`.dna` キャリブレーション）/
    `character.export`（GLB export）
- **`kotoba-lang/gltf`** / **`kotoba-lang/skeleton`**: glTF ローダ・骨格アニメ、
  上記2つが依存する形で実在（duck-typing、ハード依存なし）。
- **`kotoba-lang/render`**（61 tests/637 assertions）: メッシュ生成・カメラ・
  glTF/GLB・meshopt/splat デコーダの純CPU層。
- **`kotoba-lang/webgpu`**（`kami.webgpu` + 旧 `webgpu-rs` 吸収）:
  `kotoba.webgpu-rs.render-ir/parse-render-ir`（228行）が旧 kami-engine
  ADR-0044 の render-IR 語彙（`:lights :camera :env :materials :meshes`、
  `parse-mesh` は `:skin :joints :morphs` まで parse）を**データ層としては
  完全実装済み**。
- **`kotoba-lang/kami-app-car-sim`**: `garage.cljc`（`spec-for` — id→解決済み
  spec）、`scene.cljc/boot-config`（id+paint-hex+map→1枚の config map）という、
  「プリセットpicker + カラー選択 → 1つの解決済みドキュメント」の実例パターン。
  本設計の `chargen/boot-config` はこれを模倣する。
- **`kotoba-lang/cacao`**（CAIP-122/SIWE, Ed25519 did:key）と
  **`kotoba-lang/kotoba-client`**（`ingest-block`/`hydrate-via-blocks`、
  CID検証つき**読み取り専用**ハイドレーション）は実在し健全。

### ギャップ（新規に作る必要があるもの）

1. **ブラウザ側 GPU 実行系が `:meshes`/skin/morph を描画しない。**
   `kami.webgpu.cljs` の `init!`/`draw!`（308行）は `parse-render-ir` を一切
   参照せず、`:instances`（プロシージャルな直方体）しか描かない。スキニング+
   モーフのライブ3Dプレビューは**新規のWGSL頂点シェーダ + `kami.webgpu.cljs`
   配線**が要る。
2. **VRM 表情プリセット ⇄ ARKit ブレンドシェイプの対応表が存在しない。**
   `character.blendshape` は ARKit 52種、`vrm.expression`/`vrm.vrm-types` は
   VRM 1.0 の17プリセット。両者を繋ぐブリッジ（例:
   `:happy → [{mouthSmileLeft 1.0} {mouthSmileRight 1.0} {cheekSquintLeft 0.3}
   {cheekSquintRight 0.3}]`）は「1:1 Rust 移植」の対象外だったため未着手。
3. **kotobase.net への書き込み/publish経路がない。** `kotoba-client` は読み取り
   専用（IPNS署名検証も自己申告で follow-up 扱い）。CACAO は実在するが
   「サインイン→プロリー木更新→IPNS head publish」を繋ぐものが無い。
4. **UI部品（スライダー/カラースウォッチ/カルーセル）が無い。** `kami-ui-sdk`
   は CLJC 移植済みだが `ui.cljc`（154行）は純粋なスタイル/レイアウト計算の
   みで、ピッカー系コンポーネントはゼロ。

## Decision

新規 repo **`kotoba-lang/kami-app-character-creator`**（`kami-app-isekai` /
`kami-app-car-sim` の命名規約に合わせる）を、既存の `vrm` / `character` /
`webgpu` / `skeleton` / `kami-isekai-assets` を土台に **4フェーズ**で実装する。
Phase 1 は既存データ層の組み替えだけで完結し、Phase 2〜4 は上記ギャップを埋める
独立した follow-up として明示的にスコープする（「もう出来ている」と偽装しない）。

### データモデル: `CharacterDoc`（EDN、このアプリの正本）

```edn
{:character/id "..."
 :character/name "..."
 :character/seed 42
 :character/def   ;; character.params/CharacterDef そのまま埋め込み(sliders)
   {:body {...} :face {...} :eyes {...} :nose {...} :mouth {...} :hair {...}}
 :character/palette   ;; kami-isekai-assets の palette.cljc 方式を踏襲
   {:skin [0.85 0.7 0.6] :hair [0.2 0.15 0.1] :eye [0.3 0.5 0.7]}
 :character/equip [:outfit/casual-01]   ;; スロット参照。Phase 1 は装備メッシュ差し替え無しの固定セットで可
 :character/expression-map :chargen/vrm-expression-bridge   ;; §表情ブリッジの参照id（キャラ間で共有、キャラごとに複製しない）
 :character/vrm {:humanoid-scale 1.0}}  ;; vrm.humanoid へ渡す追加パラメータ
```

`:character/def` は `character.params/CharacterDef` の形をそのまま使う（新しい
スキーマを作らない）。アプリ固有の追加（`:palette`/`:equip`/参照）だけを
外側にラップする。

### 生成パイプライン（Phase 1、新規エンジン作業ゼロ — 既存 fn の合成のみ）

```
CharacterDoc
  → character/generate-character  (character.params/CharacterDef から body/hair/face/material パーツ)
  → vrm.part                      (パーツを VRM part 形へ)
  → vrm.compose                   (1つの VrmDocument へマージ)
  → vrm.humanoid                  (ボーンマッピング解決、kotoba-lang/skeleton 互換 {:bones […]})
  → chargen/expression-bridge     (新規: VRM 17プリセット ⇄ ARKit 52 の対応表を character.blendshape の
                                    出力に適用してから vrm.expression の :morph-target-binds に配線)
  → vrm.export                    (→ 実 .vrm バイト列、ダウンロード用)
       ├→ kotoba.webgpu-rs.render-ir/parse-render-ir  (→ :meshes/:materials/:morphs、Phase 2 プレビュー用)
       └→ kami.isekai.chargen 方式の 2D EDN スプライト (→ ピッカーのサムネイル。asset file 不要、Phase 1 で使う)
```

Phase 1 の「プレビュー」は 3D ライブレンダリングではなく、
`kami.isekai.chargen/compose-character` と同じ「EDN プリミティブ合成」方式の
**2D サムネイル**（正面/横）に留める。3D ライブプレビューは Phase 2。これにより
Phase 1 は新規エンジン作業ゼロで「パラメータをいじる→即座に見た目が変わる→
`.vrm` をエクスポートできる」という最小の character creator ループが成立する。

### 表情ブリッジ（新規データ、Phase 1 に含める — ロジックではなく EDN テーブル）

`kami-app-character-creator/data/vrm_expression_bridge.edn` に
`{:happy [...] :angry [...] ...}` の17エントリを1回だけ作る（`character-scene`
などの姉妹 `-scene` 系リポと同じ「EDN テーブルを1箇所に集約」方式）。全キャラが
共有する定数データなので `CharacterDoc` には ID 参照のみ持たせる。

### UI（`kami-app-car-sim` の `boot-config` パターンを踏襲）

`chargen/boot-config {:base-id :palette-hex :hair-id :outfit-id}` →
1枚の `CharacterDoc` を返す純関数を核に置き、UI（Body/Face/Hair/Outfit/Color の
5パネル）は編集のたびにこの純関数（または `CharacterDoc` への直接 assoc）を
呼んで再生成する。Nintendo 系UI規約（クリーム背景・pastel palette・spring
motion、`kami-engine` 旧 CLAUDE.md の UI/UX節を踏襲）に合わせ、`kami-ui-sdk`
に **新規** `KamiUI.Slider` / `KamiUI.ColorSwatch` / `KamiUI.Carousel` を追加する
（Phase 4、`kami-ui-sdk` 側への additive 変更として提案・実装）。

### フェーズ計画

| Phase | 内容 | 新規エンジン/GPU作業 | 依存 |
|---|---|---|---|
| **1** | `CharacterDoc` スキーマ、生成パイプライン配線、表情ブリッジEDN、2Dサムネイルプレビュー、`localStorage` 保存 + `.vrm`/`.edn` ダウンロードエクスポート | なし（既存 fn 合成のみ） | vrm, character, gltf, skeleton, kami-isekai-assets(chargen方式の踏襲) |
| **2** | ライブ3Dプレビュー: `kami.wgsl` にスキニング+モーフの頂点シェーダを追加、`kami.webgpu.cljs` が `parse-render-ir` の `:meshes/:skin/:joints/:morphs` を消費するよう配線。プレビュー中は `vrm.spring`（髪/服の揺れ）・`vrm.constraint`（ルックアット）を毎フレーム評価 | **あり**（engine-owner review 対象、`kotoba-lang/webgpu` への additive 変更） | webgpu, skeleton |
| **3** | kotobase.net 保存: CACAO (`kotoba-lang/cacao`) でサインイン → `kotoba-client` に書き込み/publish 経路が生えたら `CharacterDoc` を署名レコードとして自分の graph に put!。**`kotoba-client` の write path 自体が現状無いため、この Phase は kotoba-client 側の follow-up ADR を前提とする** | なし（`kotoba-client` 側の別 ADR に依存） | kotoba-client(write path 未実装), cacao |
| **4** | `kami-ui-sdk` に Slider/ColorSwatch/Carousel を追加（Nintendo規約準拠） | なし（JS、additive） | kami-ui-sdk |

Phase 1 が単独で動く character creator（2Dプレビュー＋`.vrm`エクスポート）として
完結する点が重要 — Phase 2〜4 が遅れても価値が出る形に切る。

## Consequences

- (+) 生成ロジックはほぼ全部が既存の 1:1 移植済み CLJC（`vrm`/`character`）の
  合成で済み、Phase 1 は新規のドメインロジックをほぼ書かずに動く character
  creator が作れる。
- (+) `.vrm` はスペック準拠の実バイナリとしてエクスポートできる（`vrm.export`
  が実装済みのため、VRoid等の他ツールとの相互運用性を最初から持つ）。
- (+) 表情ブリッジ・パレット・装備スロットは全部 EDN データなので、フォーク/
  カスタムキャラクターパックの差し替えが recompile 無しで可能（ADR-0038 の
  "everything describable is EDN" 方針を踏襲）。
- (−) Phase 1 はライブ3Dプレビューを提供しない（2Dサムネイルのみ）。「その場で
  3Dモデルがぐりぐり動く」体験は Phase 2 まで無い — オーナー期待値とズレる
  場合は Phase 1/2 の優先順位を再検討する必要がある。
- (−) Phase 2 は `kotoba-lang/webgpu` という共有エンジンリポへの変更を伴うため、
  旧 `kami-engine` の merge-gate 文化（engine owner review）を引き継ぐべきか
  要検討（本 ADR では touch する側として明示するに留め、レビュー体制自体は
  範囲外）。
- (−) Phase 3（kotobase.net 保存）は `kotoba-client` の write path が無い限り
  着手不能。Phase 1 のローカル保存（`localStorage` + ファイルダウンロード）で
  実用上は困らないため、無理に先取りしない。

## Phase 1〜4 実装ログ（同日、`/loop` セッション内で完了）

当初計画どおり `kotoba-lang/kami-app-character-creator` を新規 repo として起こし、
Phase 1（`CharacterDoc` + 生成パイプライン + 表情ブリッジ + ローカル保存/`.vrm`
エクスポート）を土台に、当初計画を大幅に超えて以下まで実装・ブラウザ実機検証済み
（各コミットの詳細は `kotoba-lang/{character,vrm,webgpu,kami-app-character-creator}`
の commit history 参照 — この ADR には要約のみ記す）:

- **Phase 2 前倒し**: `kotoba-lang/webgpu` に `kami.webgpu.mesh`（スキニング+モーフ
  +テクスチャ+2-tone トゥーンシェーディング対応の追加専用 WebGPU 実行系）を実装し
  レビュー後 merge。procedural 生成キャラクターの全身スキニング・表情モーフィング
  をライブ描画。
- procedural 生成側の作り込み: 全身メッシュ化（23→35 ボーン、VRM 1.0 標準ボーン名、
  指/足指の関節）、体の継ぎ目ブレンディング+法線スムージング、服の脚/腕カバレッジ、
  ARKit 52 ターゲットの実データ化（VRM 18 プリセット全対応）、眉毛+体型プリセット、
  ヘアシェル生成（`character.hair-gen` の既存だが未使用だった 3層ポリゴンシェル
  ジェネレータを接続）、アクセサリー装備システム+タトゥー/傷デカール、グラデーション/
  放射状/縞のプロシージャルマテリアル、保存/読込+ランダム生成ボタン。
- 副産物として見つかり修正した実バグ: JVM負の浮動小数点読み取りクラッシュ
  (`vrm.convert`)、cljsでの`Math.signum`未定義クラッシュ (`character.math`)、
  VRM表情バインドの`node`/`mesh`フィールド取り違え、`vrm.cljc`ルート名前空間の
  `vrm.compose`衝突クラッシュ、メッシュ分類の誤判定（素材名の偶然の部分一致）。

## Phase 5（同日）— procedural 生成の限界認識と VRM-first アーキテクチャへの転換

### きっかけ

Phase 1〜4 で procedural 生成（`character.body`/`character.hair`/
`character.base-mesh` のリング押し出し・楕円体ベースのプリミティブ生成）を
スムージング・継ぎ目処理・ヘアシェル化まで作り込んだ後、オーナーが実際に
ブラウザで確認し「頭部が怖い」「MetaHumanと混ざっていないか」と指摘。調査の結果:

- **技術的には MetaHuman とは無関係**（`generate-character` は `character.metahuman`
  を一度も呼ばない — `generate-character` / `metahuman/generate-metahuman` /
  `dna/from-bytes` は並列の独立エントリポイントで、パイプラインとして繋がっていない）。
- しかし本質的な問題はより根深い: procedural 生成は**そもそも一度も本物の
  彫刻済みキャラクターアートだったことがない**（削除された Rust クレート
  `kami-character` 自体がプレースホルダーのプロシージャル生成だった、ADR-2607010930
  参照）。土台がプリミティブ生成である限り、スムージングや継ぎ目処理では
  越えられない根本的な質の天井がある。

### CC0 素体調達の行き詰まりと解決

本物の彫刻済み VRM 素体（VRoid Hub/Studio 由来等）を探したが、クリーンな
CC0 ライセンスのモデルの確定に難航（`vrm-c/vrm-specification` の公式サンプル
`Seed-san.vrm` 等は「VRM Public License 1.0」— モデルごとに設定可能な条件付き
ライセンスで、アバター利用が特定人物に制限されている可能性がありCC0ではない）。

オーナーの決定: **VRM バイナリ自体は一切コミットせず、代わりに「ユーザーが自分の
VRM ファイルを持ち込んで使う」機能を提供する** — `M3-org/CharacterStudio`
（MIT licensed、公開 OSS）と同じ思想。この方式なら誰の著作物も再配布しないため、
ライセンス問題が構造的に発生しない。

### 実装: VRM アップロード → CharacterStudio 風マルチパーツミキシング

1. **単一VRMアップロード**（`kotoba-lang/vrm` の `vrm.parse`/`vrm.expression`/
   `vrm.spring` + `kami-app-character-creator` の `character-creator.gpu-adapter`
   の拡張 — `mesh-primitives-by-index`（マルチプリミティブ対応。実データで
   1メッシュに複数マテリアルがあることを確認）、`node-world-transforms`/
   `skin-joint-palette`（実スキニング）、`material-base-color-texture`）で、
   実VRMファイルを読み込んでライブ描画・実表情・実スプリングボーン揺れ物理まで
   動作する状態にした。
2. **`vrm.convert` に glTF sparse accessor 対応を追加**（VRoid Studio標準の
   圧縮ブレンドシェイプ形式。これが無いと実VRMの表情モーフが一切読めなかった）。
3. `vrm.part` のメッシュ分類ヒューリスティックを改善（メッシュ自身の名前を
   マテリアル名より優先 — 実データで衣装メッシュがマテリアル名の偶然の部分一致で
   顔に誤分類されるバグを発見・修正）。
4. **CharacterStudio の実装を調査**（`CharacterManager` の実態は「多数の
   小規模な事前用意アセットをホストされたマニフェストから選ぶ」方式 — 当初
   想定した「アップロード済みアバターを分解する」とは異なると判明）し、
   我々の制約（素体を一切ホストできない）に合わせて適応: **ユーザーが複数の
   VRMをアップロードして自分のライブラリを作り、カテゴリ（body/hair/face/
   outfit/accessory/other）ごとにどのVRM由来のパーツを使うか選んでミックスする**
   UI（`!library`/`!active-parts`/`recompose-library!`、カテゴリごとの
   `kami-ui-sdk.widgets/carousel!`）。procedural 生成は「(procedural
   placeholder)」としてパネル下部に降格（削除はしていない — VRM を持たない
   ユーザー向けのフォールバック）。
5. **本当に異なる2つの実VRM**（Seed-san.vrm、VRM公式サンプル
   Constraint-Twist-Sample.vrm、共に VRM Public License 1.0 系・ローカル検証
   限定・非コミット）でクロスアバター・パーツミキシングを実ブラウザ操作で検証
   — 髪カテゴリを切り替えてシルエットが実際に変化、エクスポート後の再パースで
   正しい由来ミックスを確認。バグなし。
6. **`vrm.compose` のバッファ重複排除バグを発見・修正**: 複数パーツが同じ
   ソースドキュメントを共有する場合（実際のミキシングで頻発するケース）に、
   そのドキュメントの全バイナリ（テクスチャ含む）がパーツ数分重複していた
   （2ファイル・5パーツの実測で 54MB → 修正後 21.6MB、正しい重複排除を確認）。

### 結論・今後

VRM-first（ユーザー持ち込み素体 + `vrm.part`/`compose` によるマルチパーツ
ミキシング）が **character creator の主役アーキテクチャ**として採用された。
procedural 生成は素体を持たないユーザー向けの副次的フォールバックとして残る。
残課題:

- procedural フォールバック自体の質は本質的な天井があるため、追加のスムージング等
  より、VRM アップロード体験の充実（ドラッグ&ドロップ、ライブラリのサムネイル表示等）
  を優先すべき。
- `vrm.compose` は今も各ソースドキュメントの全バイナリを保持したまま
  マージしている（reachability グラフに基づく未使用バイトの刈り込みは
  別途の効果。ADR-2607010930/2607021900 のような「CharacterStudio のVRM
  optimizer（メッシュマージ+テクスチャアトラス化）」相当の最適化は未着手）。
- 顔のペイントされたテクスチャ（目・鼻・口）がまだ表示されないケースがある
  （メッシュが複数プリミティブ/マテリアルを持つ場合の描画ループ側の合成が
  未完 — データ層の読み取り自体は `mesh-primitives-by-index` で解決済み）。
- kotobase.net への保存（Phase 3）は引き続き `kotoba-client` の write path
  待ちで未着手。

## References

- ADR-2607010930（Rust→Clojure/WGSL migration） — `vrm`/`character`/`gltf`/
  `skeleton`/`webgpu` 復元の根拠
- `kotoba-lang/vrm` README（15モジュール1:1移植、テスト内訳）
- `kotoba-lang/character` README（15モジュール1:1移植、DNA/FACS/groom詳細）
- `kotoba-lang/kami-isekai-assets` `kami.isekai.chargen`（EDNプリミティブ合成の
  character generator 先例）
- `kotoba-lang/kami-app-car-sim` `garage.cljc` / `scene.cljc`（picker→
  boot-config パターンの先例）
- 旧 `kami-engine/90-docs/adr/0044-edn-render-ir-threejs-vrm-parity.md`
  （render-IR 語彙の由来。実体は `kotoba-lang/webgpu` の
  `kotoba.webgpu-rs.render-ir` に移植済み）
- root `CLAUDE.md` 「kotoba-server（kotobase.net）」節（Phase 3 の認証/publish
  方式の参照実装 `ai-gftd-itonami/src/itonami/cacao.clj`）
- `M3-org/CharacterStudio`（MIT license, https://github.com/M3-org/CharacterStudio）
  — Phase 5 の VRM-first マルチパーツミキング設計の着想元。`CharacterManager`の
  実態調査を踏まえ、素体を一切ホストできない我々の制約に合わせて「ユーザーが
  複数VRMをアップロードして自分のライブラリを作る」形に適応した（コードの
  移植ではなく、UXパターンの参考）。
- `vrm-c/vrm-specification` `samples/`（Seed-san / VRM1_Constraint_Twist_Sample
  — Phase 5 の実VRMクロスミキシング検証に使用したローカル限定テストアセット。
  いずれも VRM Public License 1.0 系で CC0 ではないため、どのリポジトリにも
  コミットしていない）
