# ADR-2607100100: kotoba-lang 版リアルタイム・アーキビジュアライゼーション設計（Twinmotion 相当）— CAD/BIM 取り込み → シーン合成 → WebGPU 実行層 → `kami-app-amenominaka` 拡張シェル

- **Status**: proposed（設計のみ。実装未着手 — 下記 Milestones 参照）
- **Related**: ADR-2605261800（`etzhayyim/root`。NVIDIA Omniverse Stack API-Compat の親 charter — `kami-app-amenominaka` の authoritative parent、本 ADR が従う D10 fallback-gate 枠組み）、ADR-2607010930（clj-wgsl migration — WGSL-compute hot-loop 規則、kami-engine Rust workspace 撤去）、ADR-2607010000（kotoba-runtime-sdk-cljc-migration — 4層 authority/provider 構成、`kami-provider-catalog.edn` の render provider family）、ADR-2607078000（wasm-webcomponent CLJS ESM host policy — browser-host の正式な authoring tier、`clojurewasm` は host-providing-imports 役には使えないと実証済み）、ADR-2607062330/2607062400（kototama actor:host ABI）、ADR-2607100030（kami:engine ECS host imports — 閉じた capability table を本当に必要な時だけ拡張する先例）

## Context

オーナーから「kotoba-lang に Twinmotion（Epic Games の実時間アーキビジュアライゼーション・ツール — CAD/BIM を取り込み、植生/天候/ポストエフェクトを載せて実時間ウォークスルーする）相当の lib/app はあるか」と問われ、調査した結果:

- **入力・環境演出の部品は揃っている**: `bim`（IFC相当のBIMモデル）、`kami-cad-import`（STEP/glTF/OpenSCAD取り込み）、`cad`/`dxf`/`step`/`scad`、`kami-atmosphere-scene`/`kami-vegetation-scene`/`kami-terrain-scene`/`kami-postfx-scene`（天候/植生/地形/ポストエフェクトのEDNプリセット）、`usd`/`materialx`/`ocio`（シーン交換・マテリアル・カラーマネジメント）。
- **決定的に欠けているのは2つ**: (1) これらを1つの「ウォークスルー可能な scene」に束ねる合成層が存在しない（`kami-scene-contracts` は各 `kami-*-scene` の EDN 原本の provenance/sha256 検証だけを行う repo であり、合成はしない — 確認済み）。(2) 実際にGPUで絵を出す実行層が scaffold 止まりで存在しない（`kami-engine-render` は空、`kami-engine` は Rust workspace 撤去済みで「native execution は substrate」という原則のみが残り、具体的な crate 在庫は無い）。
- **名前だけ予約済みの app shell**: `kami-app-amenominaka`（README 曰く "Omniverse Kit-equivalent app shell + extension loader"）。現状は `pub const` 5個の移植のみで「R1.0 path reservation」。README 自身の "Scope (R1.4 deliverable)" 節が `extension.toml` ローダ / lifecycle / extension→magatama Pregel cell マッピング / 5参照拡張（`omni.usd`/`omni.kit.app`/`omni.replicator.core`/`omni.kit.viewport`/`omni.timeline`）パリティを列挙しているが、"Do not treat the presence of this README section as evidence that any of the above is wired up" と明記。

オーナーとの事前合意（AskUserQuestion 2026-07-09）:
- v1 は **設計（ADR）を先に固める** — 実装はこの ADR 確定後、別セッション/別ADRで着手。
- 実装先は **新規 repo を起こさず `kami-app-amenominaka` を肉付けする**。

本 ADR はこの2点を前提に、CAD/BIM取り込みから実時間ウォークスルーまでの全体設計を確定する。

## Decision

### D1. 位置づけ — ADR-2605261800 の non-fallback tier の内側に留める

`kami-app-amenominaka` の authoritative parent は `etzhayyim/root` の ADR-2605261800（NVIDIA Omniverse Stack API-Compat）。同 ADR の D10 は「WebGPU+WASM viability gate → 未達なら Council Lv6+ ≥3 attestation を経て native Rust fallback（`kami-usd-native`/`kami-rtx-native`/`kami-physics-solvers` の path reservation を起動）」という枠組みを敷いている。

本 ADR が設計する範囲（BIM/CAD取り込み・環境プリセット合成・ラスタライズ主体のブラウザWebGPUウォークスルー・USD/glTF書き出し）は、**この D10 gate を一切トリガしない**: フルフィデリティな USD 合成エンジン（tinyusdz 相当の from-scratch parser）も、physically-based path tracing（Mitsuba3 相当）も要求しない。したがって:

- `kami-usd-native` / `kami-rtx-native` / `kami-physics-solvers` の path reservation には触れない・変更しない。
- Council Lv6+ attestation は不要（native Rust fallback を一切発動しないため）。
- 本 ADR は ADR-2605261800 の **R1.4（`kami-app-amenominaka` extension loader）を、native Rust を書かずに kotoba-lang 既存資産で満たすための、kotoba-lang ローカルな実装設計**という位置づけ。

### D2. Authoring tier — 新しい tier を発明しない、既存の CLJS を伸ばす

CLAUDE.md の優先順位（`kotoba wasm` > `clojurewasm` > `ClojureScript` > `nbb` > `jvm`）と、ADR-2607078000 の実測結果を踏まえる:

- `clojurewasm` は **host-providing-imports 役には使えないと実証済み**（native-only で browser 内実行不可、FFI が「Clojure が wasm を呼ぶ」方向のみで「wasm が host 関数を呼ぶ」方向が無い）。
- `kotoba wasm`（kototama 経由）は閉じた capability table（`kotoba-core-contracts`）にホスト関数を登録する経路だが、GPU/描画系の capability は現状ゼロ件（後述 D6）。
- ADR-2607078000 は「host-providing-imports が必要な browser 向けホストは ClojureScript を shadow-cljs `:target :esm` でコンパイルする」を正式方針として確定させ、`kami-engine-host` をその第一実装として landed 済み。

`kami-webgpu`/`webgpu`（"declarative WebGPU from EDN" — `src/kami/webgpu.cljs` が実際に browser の WebGPU API を EDN render-IR から叩いている、scaffold ではなく動くコード）は**既にこの正式 tier に乗っている**。したがって本設計は新しい authoring tier を発明せず、`kami-webgpu`/`webgpu` を拡張する（D5）。

### D3. 入力層 — 既存をそのまま使う、新規 repo なし

`bim`（IFC相当の空間階層・要素taxonomy・Pset/Qto）、`kami-cad-import`（STEP/glTF/OpenSCAD → part graph）、`cad`/`dxf`/`step`/`scad`（フォーマット別パーサ）をそのまま入力層として使う。新規実装・新規 repo は不要。

### D4. シーン合成層 — 実際に欠けている部分。`kami-app-amenominaka` の内側に実装する

`kami-*-scene` ファミリ（`kami-atmosphere-scene`/`kami-vegetation-scene`/`kami-terrain-scene`/`kami-postfx-scene`）は各々独立した EDN authoring surface であり、`bim`/`kami-cad-import` の建物モデルと束ねて1つの「ウォークスルー可能な scene」にする合成コードはどこにも存在しない（`kami-scene-contracts` は provenance 検証専用、合成ではない — D0 の Context で確認済み）。

**新規 namespace `kotoba.amenominaka.scene` を `kami-app-amenominaka` リポジトリ内に実装する**（新規 repo は起こさない — 実装先はオーナー合意により `kami-app-amenominaka` 一本）。役割:

```
bim/*.edn ─┐                                  ┌─ kami-atmosphere-scene(天候preset)
kami-cad-import/*.edn ─┤                      ├─ kami-vegetation-scene(植生preset)
                       ├─▶ kotoba.amenominaka.scene ◀─┤
                       │      (pure CLJC, merge/from-edn)  ├─ kami-terrain-scene(地形biome)
                       └──────────────────────────────────┴─ kami-postfx-scene(ポストFXpreset)
                                        │
                                        ▼
                              1本の walkthrough-ready scene.edn
                        （建物 + 地形 + 植生 + 天候 + ポストFX を1つのEDN authorityに）
```

これは ADR-2607010000 の4層構成（Kotoba/EDN=truth → Contract層 → Wasm Component adapter → Host実装）の**第1〜2層のみ**で完結する純粋データ変換であり、GPU/native は一切関与しない。`kami-app-amenominaka` README が既に述べている「extension → magatama Pregel cell」マッピングの発展として自然に位置づく（各 `kami-*-scene` preset を1つの "extension" として読み込む、という R1.4 の元々の構想と整合）。

### D5. 出力・レンダリング実行層 — 2トラック

**Track 1（M1・低リスク・最速で「見える」）**: `scene.edn` → `usd` + `materialx` + `ocio` で `.usdz`/`.gltf` に書き出し、既存の外部 USD ビューア/Blender 等で確認する。新規レンダリングエンジンのコードは一切書かない。`omni.usd` パリティ（README が既に挙げている5参照拡張の1つ）はこのトラックで満たす。

**Track 2（M2・本命・ブラウザ内実時間ウォークスルー）**: `kami-webgpu`/`webgpu` を拡張し、3D scene 用の render-IR を追加する。現状の render-IR は `dom-gpu` で実証済みの 2D/UI 形状（"hiccup for the GPU"）であり、3D シーン形状（カメラ・メッシュインスタンス・PBRマテリアル・ライティング）はまだ無い —**これが本 ADR で唯一の実質的な新規エンジン設計**。最小形状の出発点（実装時に精緻化する前提のスケッチ）:

```clojure
{:camera {:eye [x y z] :target [x y z] :up [0 1 0] :fov-deg 50 :near 0.1 :far 5000}
 :lights {:sun   {:dir [...] :color [...] :intensity ...}   ; kami-atmosphere-sceneのweather presetから導出
          :ambient {:color [...] :intensity ...}}           ; 同上（sky/IBL近似）
 :meshes    {"bim://wall/123" {:source :bim :geometry-ref ...} ...}
 :materials {"materialx://brick-01" {:base-color [...] :roughness ... :normal-map ...} ...}
 :instances [{:mesh-ref "bim://wall/123" :transform [...] :material-ref "materialx://brick-01"}
             {:mesh-ref "vegetation://oak-01" :transform [...] :material-ref "..."}
             ...]}
```

M2 の実装は ADR-2607078000 の方針通り ClojureScript + shadow-cljs `:target :esm` で `kami-webgpu`/`webgpu` に足す。GPUの hot loop（インスタンス変換・カリング等）は ADR-2607010930 の原則通り WGSL 側に置く方針だが、MVPでは CPU 側で EDN からインスタンスリストを事前展開する素朴な実装で足りるため、WGSL compute 未着手（D6-b）を M2 のブロッカーにはしない。

`omni.kit.viewport` パリティ（カメラ操作）と `omni.timeline` パリティ（最小限のキーフレーム/カメラパス）はこのトラックで満たす。

### D6. 明示的にスコープ外にする2点

**(a) 閉じた capability table（`kotoba-core-contracts`）に GPU/描画系 capability を新設しない。** 調査の結果、現行の host-import table（31件、id 201-225 + 233の `kami/engine` ECS）に GPU/render/graphics 系エントリは1件も無い。これを新設する誘惑があるが、`kami-app-amenominaka` は browser 上の CLJS SPA として動く app shell であり、サンドボックス化された `.kotoba` guest ではない — 描画は D5 Track 2 の通り `kami-webgpu` が browser の WebGPU API を直接叩く経路であり、kototama の guest→host capability 経由である必要がない。ADR-2607100030 が示した「本当に必要な時は決まった経路（kotoba-core-contracts → kotoba-lang effect-for-kind → kotoba op->kind + host実装）で capability を足せる」という先例は認識しつつ、**本設計ではその経路を使わない**と明示する。

**(b) `wgsl` repo の `@compute`/`@workgroup_size` 対応（ADR-2607010930 Phase 2 が要求、未着手）は本 ADR のスコープ外。** M2 の CPU-authored instancing で頭打ちになった場合にのみ、M4（stretch, gated）として着手する。今回は「いつか要る」という理由だけで先に手を付けない。

### D7. `kami-app-amenominaka` の R1.4 スコープ充足

README が既に宣言している R1.4 deliverable を、以下の対応で満たす（現状「未実装」と明記されているものを、本設計に沿って実装対象として確定させる）:

| README記載の R1.4 要素 | 本設計での対応 |
|---|---|
| `extension.toml` ローダ | EDN ネイティブな `extension.edn`（name/version/depends_on/startup/shutdown）を第一級とし、`kotoba-lang/toml`（既存repo）でリテラル `.toml` 互換パーサを薄く被せる |
| extension lifecycle（`depends_on` 解決） | `depends_on` の位相ソートによる起動順決定。D4 の scene 合成・D5 の render dispatch を「拡張」としてロードする |
| extension → magatama Pregel cell マッピング | **未解決** — 親 ADR-2605261800 由来の要求で、本 ADR では具体的な配線を確定しない（Open Questions 参照。M3 着手前に別途スコーピングが必要） |
| `omni.usd` パリティ | D5 Track 1（USD/glTF 書き出し） |
| `omni.kit.viewport` パリティ | D5 Track 2（ウォークスルーカメラ） |
| `omni.kit.app` パリティ | 拡張ローダそのもの |
| `omni.timeline` パリティ | 最小限のキーフレーム/カメラパス（M3 stretch） |
| `omni.replicator.core` パリティ | **対象外**（合成データ/ドメインランダマイゼーションは本設計のスコープ外。将来別 ADR） |

## Milestones

- **M0 — scene 合成コントラクト**: `kotoba.amenominaka.scene`（D4）。純粋 CLJC、レンダリング皆無。テスト: サンプル建物（`bim`）+ 地形/植生/天候プリセットを1本の検証済み `scene.edn` に合成できること。
- **M1 — USD/glTF 書き出し**（D5 Track 1）: `scene.edn` → `.usdz`/`.gltf`。既存 USD ビューア/Blender で目視確認。新規レンダリングエンジン不要 — 最速で「実際に見える」マイルストーン。
- **M2 — 3D render-IR + `kami-webgpu` ブラウザ MVP**（D5 Track 2）: 静的シーンのブラウザ内ウォークスルー（orbit/fly カメラ）。CPU-authored instancing。WebGPU+WASM edge-target 不変条件（iPhone12+/Android4GB）を尊重、native Rust 不使用。
- **M3 — `kami-app-amenominaka` 拡張シェル**（D7）: extension ローダ・lifecycle を実装し、M0-M2 を最初の実拡張として包む。README の「path reservation」状態を解消。
- **M4（stretch・条件付き）**: `wgsl` の `@compute` 対応後、GPU側での大規模植生/地形インスタンシング。M2 の CPU instancing が実際に性能上限にぶつかった場合のみ着手（先取りしない）。

## Non-goals（明示的にやらないこと）

- フォトリアルな path tracing（Mitsuba3 / `kami-rtx-native`）— ADR-2605261800 D10 gate 配下のまま、本 ADR は触れない。
- from-scratch USD 合成エンジン（`kami-usd-native`）— 同上、path reservation のまま。
- `omni.replicator.core` 相当（合成データ/ドメインランダマイゼーション）。
- 物理・車両シミュレーション統合（`kami-vehicle`, Genesis）— 本設計は静的環境ビジュアライゼーションのみを対象とし、シミュレーションは対象外。
- `kotoba-core-contracts` への GPU/描画 capability 新設（D6-a）。

## Consequences

- (+) 新規 repo ゼロ。既存 8+ repo（`bim`/`kami-cad-import`/`kami-*-scene` 群/`usd`/`materialx`/`ocio`/`kami-webgpu`/`webgpu`）をそのまま or 拡張で使い、実質的な新規コードは `kami-app-amenominaka`（元々の実装先合意）と `kami-webgpu`/`webgpu`（既に動いている repo の拡張）に閉じる。
- (+) ADR-2605261800 の Council Lv6+ gate も、`kotoba-core-contracts` の capability table 新設も、native Rust も一切トリガしない — 実装コストと承認コストの両方が小さい経路。
- (+) M1（USD書き出し）だけでも「CAD/BIM取り込み→環境演出→見える」という Twinmotion の価値提供の大部分を、レンダリングエンジンを1行も書かずに達成できる。
- (−) M2 の 3D render-IR は本 ADR 内で最もリスクが高い未知数 — 2D/UI形状の先例（`dom-gpu`）はあるが3D シーン形状の先例が無く、D5 のスケッチは出発点に過ぎない。実装着手前に短い設計スパイクが要る可能性が高い。
- (−) D7 の「extension → magatama Pregel cell マッピング」は本 ADR では未解決のまま M3 に持ち越す。

## Open Questions / Follow-up

- `magatama` Pregel cell の具体的な配線（D7）— M3 着手前に別途スコーピングが必要。本 ADR は「解決しない」ことを明示するに留める。
- M2 の 3D render-IR の詳細スキーマ（D5 のスケッチの精緻化）— 実装着手時に短い follow-up ADR or 設計ノートとして確定させる。
- 実ブラウザでの WebGPU 動作確認は、ADR-2607078000 が既に記録した「サンドボックス化された browser-automation 環境ではローカル static file server に到達できない」という既知のギャップに M2 でも再度ぶつかる可能性が高い — Node/V8 での等価確認（同ADRの前例）で代替し、実ブラウザでの最終確認は開発者本人の環境に委ねる。

## Related

`kami-app-amenominaka`（実装先）、`kami-scene-contracts`（provenance検証のみ、合成層ではないことを確認済み）、`kami-render-provider`（`repos.edn` に予約済みだが未作成 — 本設計は当面これに依存しない。将来 render provider family として `kami-webgpu`/`webgpu` を吸収する構想は ADR-2607010000 のまま維持）。

## Addendum (2026-07-09): M0 実装、D3 の訂正（`kami-cad-import` は使わない）

M0（`kotoba.amenominaka.scene`）を `kami-app-amenominaka` に実装・着地（`652d2d7`、west pin 前進 `5a2f6a7→652d2d7`）。実装前に各入力/環境層の実ソースを読んで検証した結果、D3 の前提に1点誤りがあった:

- **`kami-cad-import` は汎用CAD取り込みではなく、車両パーツグラフ専用**であることが実装時にソースレベルで判明した（README のみでなく `cad_import.part/VehicleAssembly` の実装を確認）。`part-kinds`(`:chassis`/`:body`/`:powertrain`/…)・`material`（steel/aluminium/glass等の車両材料固定集合）・`hardpoints`（bolt/weld/hinge等のJBeamソフトボディ概念）に閉じており、glTF ingest (`cad-import.ingest.gltf/from-gltf-map`) は `gftd_vehicle`/`gftd_part` アノテーション必須・幾何ペイロードは AABB のみ（実三角形/頂点データなし）。STEP経路もFreeCAD script文字列生成のみで実パースなし。**建築/BIM入力には使えない。**
- **対応**: M0 の建物入力は `bim`（IFC相当、実ジオメトリコンストラクタ `brep-geometry`/`axis-sweep-geometry`/`mesh-ref-geometry` を持つ）のみとした。`kami-cad-import` は M0 実装に含めていない。D3「入力層 — 既存をそのまま使う」は `bim`/`cad`/`dxf`/`step`/`scad` については変更なしで有効、`kami-cad-import` の部分のみ本 Addendum で撤回する。
- **将来**: 建築/BIM 向けの汎用 STEP/glTF 取り込みが必要になったら、`kami-cad-import` を車両専用のまま残し、別途 `bim` 側に取り込みパスを足すか、`cad-import.ingest.gltf` を車両アノテーション必須でない形に一般化するかは、その時点で別 ADR として判断する（本 ADR ではどちらか確定しない）。

`kami-*-scene` 4 repo（atmosphere/vegetation/terrain/postfx）は D4 の想定通り、いずれも `shipped-*`（例: `atmosphere-scene/shipped-weather "overcast"`）1関数呼び出しで実データを取得でき、`bim/project` 出力と合わせて `{:scene/building ... :scene/atmosphere ... :scene/vegetation {...} :scene/terrain ... :scene/postfx ...}` への合成は想定通り軽量だった（7 tests / 32 assertions green, clj-kondo clean）。M1 以降は未着手のまま。

## Addendum (2026-07-10): M1 実装、幾何フィデリティの限界を明記

M1（`kotoba.amenominaka.usd-export`）を `kami-app-amenominaka` に実装・着地（`47cfe0d`、west pin 前進 `652d2d7→47cfe0d`）。D5 Track1（USD/glTF書き出し）のうちUSDのみ実装、glTFは未着手のまま据え置き。

- **`usd`/`materialx` の実API確認**: `usd.core/usda`+`prim`+`attr`+`rel` はスキーマ非依存の汎用USDA text emitter（"hiccup for USD"）— `Mesh`固有ヘルパーは無いが `point3f[]`/`int[] faceVertexIndices` 等は型付きattrとして問題なく書ける（`val*` の `:array`/vector-of-vectors/plain-vector 分岐をソース直読で確認）。`materialx.core` も同型の薄いXML hiccupエミッタ。`ocio` はOpenColorIO YAML config生成器で幾何/マテリアルとは無関係と判明 — M1では意図的にスキップ（D5に反しない、将来の色管理設定という別関心事）。
- **`bim` の幾何フィデリティの限界を発見**: `bim/element` の `ElementGeometry`（brep/axis-sweep/mesh-ref/none）と、storey-scene側の実三角形データ`SceneGeom`/`triangles-geom`は**`bim.cljc`内で一切橋渡しされていない**（全148行を読んで確認 — BREPテッセレータ/sweep→mesh変換関数が存在しない）。したがってM0の `:scene/building`（生の`bim/project`）は実三角形を持たない。
- **対応**: BREPテッセレータを新規実装する（CADカーネルの実装に相当し明らかにM1のスコープ外）のではなく、正直に限定した — `axis-sweep` + `rectangle`profile の要素（壁/梁の典型ケース）だけ実boxメッシュを計算し、それ以外（brep/mesh-ref/非矩形profile/no-geometry）は `kotobaGeometryKind` 属性を持つ `Xform` プレースホルダとして書き出す（フェイクメッシュにしない）。環境プリセット（atmosphere/vegetation/terrain/postfx）はM0のscene EDNに配置/heightfield/instanceデータが無いため、`Environment` Scope prim上に `pr-str` した custom string attr として記録（geometryの捏造はしない）。マテリアルは `bim` が色/PBR値を持たないため、`materialx.core` で中立グレーの `standard_surface` を1つだけ companion `.mtlx` として生成 — USD stageへの `material:binding` 結線（UsdMtlx schema）は未検証のため意図的に見送り、既知のギャップとして明記した。
- **検証**: 手計算した壁のboxメッシュ8頂点座標と生成された `.usda` 出力が一致することをテストで確認、さらに実際の生成出力（`.usda`/`.mtlx`）を目視確認して構文の妥当性（ネストしたXform階層、`point3f[]`/`faceVertexIndices`/`faceVertexCounts`、balanced braces）を確認した。13 tests / 66 assertions green（M0の7/32から増加）、clj-kondo clean。
- **未解決のまま**: glTF書き出し、USD<->MaterialX の正式binding、`usdcat`/`usdchecker`等の外部USDツールによる実ツール検証（サンドボックス内に無いため）。

## Addendum (2026-07-10): M2 実装 — D5 Track2の前提誤りの訂正、実ブラウザ検証成功

M2（`kotoba.amenominaka.render-ir`）を `kami-app-amenominaka` に実装・着地（`f6e8f0d`、west pin 前進 `47cfe0d→f6e8f0d`）。

- **D5の前提の訂正（重要）**: 本ADRのD5は「現状の render-IR は 2D/UI 形状（`dom-gpu` 由来）であり 3D シーン形状はまだ無い」としていたが、これは誤りだった。`kotoba-lang/webgpu`（`kami-webgpu` は ADR-2607051400 でリネーム済みの stale entry — `repos.edn` に明記されており `webgpu` が正）の `kami.webgpu.ir`/`kami.webgpu.cljs` を実ソースで読んだ結果、**カメラ・シャドウマップ付き directional light・PBRマテリアル・インスタンシングを備えた実働3Dレンダラーが既に存在**していた（`dom-gpu` は確かに2D/UI専用だが、それとは別リポジトリ）。M2 の実質的な新規作業は「3Dレンダリングエンジンの実装」ではなく「M0の scene.edn をその既存 render-IR 形状に変換するEDNブリッジ」のみだった。
- **`ir/instance` の制約を発見**: box instance の `:size` は `[footprint-width height]` の**正方形フットプリントのみ**（矩形の `[l w h]` は存在しない、`ir.cljc` ソース直読で確認）。`bim` の壁（10m×0.2m×3.5m等の細長い矩形断面）を正確に表現できないが、`ir/instance` 自身のdocstringが挙げる例 `;; a building` も同じ正方形フットプリントの massing block（マッシング・ブロック）表現を採用しており、これに倣った — 壁の run length をフットプリント幅として使う早期段階のマッシング表現（建築設計初期のブロックスタディに相当）とし、未検証の `:geo` カスタムジオメトリレジストリ拡張は行わなかった。
- **地形**: `terrain-scene` のbiome paletteの base色でground plane instanceを1つ追加（実データ）。植生/postfxは配置データが無いためbridgeに含めていない（M1と同じ理由）。
- **実ブラウザでの検証に成功**: shadow-cljs で `kami.webgpu` + 本ブリッジを `.cljs` からコンパイルし、Playwrightで**headless-shellでなくフル版Chromium**を明示的に起動（`chromium.executablePath()`）して `navigator.gpu` を実際に取得、`kami.webgpu/init!`+`draw!` を実行してスクリーンショットを撮った — 青空・地面色（terrainパレット）・陰影付きの massing block が正しく描画されていることを目視確認した（`ADR-2607078000` Addendum 8 が確立した手法の再利用。ただし **オーナー指摘により、検証ハーネスは `.mjs` のコピーでなく nbb (`.cljs`) に翻訳して実装** — CLAUDE.mdにルールを追記済み）。GitHub Actions macOS runner上でも同じ検証が green（`webgpu-smoke` job、スクリーンショットをartifactとしてアップロード）。
- **副産物として発見・修正した実バグ**: (1) `kami-app-amenominaka` のCIは **M0着地以来ずっと失敗していた**（`gh run list` で確認 — `deps.edn` の `:local/root` 兄弟依存がCIの単一repo checkoutでは解決できないため）。M2の一環でCI設定を全面修正（兄弟repoを `$GITHUB_WORKSPACE` 直下の子として並列checkoutし、`working-directory` で本体に切り替える構成 — `actions/checkout` は `path:` が `$GITHUB_WORKSPACE` を跨ぐ `../` を許可しないため実測で判明）。(2) ローカルの `orgs/kotoba-lang/webgpu` checkout が stale で、実際のGitHub上の最新版は `org-w3-webgpu`/`expr` という新たな `:local/root` 依存を追加していた（ADR-2607051400/2607051500）— `kami.webgpu.ir`自体は無変更（diff確認済み）だったが、これらのsibling checkoutが無いとtools.depsのclasspath解決が失敗するため追加。
- **検証**: 20 tests / 66→86 assertions green（M0/M1込み）、clj-kondo clean（`.cljc`/`.cljs`/nbbの`.cljs`全て）。
- **未解決のまま**: インタラクティブなorbit/flyカメラ操作（render-IRは静止フレームのカメラのみ）、`wgsl` の `@compute` 対応（M4のまま）、M3（`kami-app-amenominaka` 拡張シェル自体）は未着手。
