# ADR-2607031200: kotoba-lang/kami-app-character-creator — EDN-native VRM キャラクターメイキング

**Status**: proposed
**Date**: 2026-07-03
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
