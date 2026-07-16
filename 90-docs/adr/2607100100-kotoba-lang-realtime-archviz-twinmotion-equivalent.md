# ADR-2607100100: kotoba-lang 版リアルタイム・アーキビジュアライゼーション設計（Twinmotion 相当）— CAD/BIM 取り込み → シーン合成 → WebGPU 実行層 → `kami-app-amenominaka` 拡張シェル

- **Status**: accepted（M0-M9 全マイルストーン完了 — M1 は USD/glTF 両方 done、M3 の R1.4 ゲートは `omni.replicator.core`（明示的非ゴール）を除く全項目 done、M4 は実測に基づく代替修正で完了、M6 の `MAX-INST` 上限修正・M8 の fly camera・M9 の `omni.timeline` パリティはいずれも本ADR原案に無くオーナーの「next」選択で追加着手・完了。詳細は下記 Milestones/各 Addendum 参照）
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

## Addendum (2026-07-10): M3 実装 — 「extension → magatama Pregel cell」の解決（=撤回）と拡張ローダの実装

M3（`kotoba.amenominaka.application`/`.extension`/`.extensions`）を `kami-app-amenominaka` に実装・着地（`e63446a`、west pin 前進 `f6e8f0d→e63446a`）。M0着手前からの宿題だった「extension → magatama Pregel cell マッピング — 未解決、M3着手前に別途スコーピングが必要」（D7 Open Questions）を実際に調査・解決した。

- **「magatama」の出典を検証した結果、本ADR D7 の記述自体が誤りだった**: D7は「親ADR-2605261800由来の要求」としていたが、`etzhayyim/root/90-docs/adr/2605261800-nvidia-omniverse-stack-api-compat.md` を全565行読み直したところ **"magatama"/"Pregel" いずれも一致ゼロ**。実際の経路は「`kami-app-amenominaka`のpre-M0 README草稿がこのフレーズを創作 → 本ADRのD7がそれを親ADR由来と誤って引用 → 親ADR自体は何も述べていない」。**"magatama" という語はモノレポ内で少なくとも3つの無関係なシステム**（gftdcojpのCloudflare Workerアプリ規約`MagatamaApp`、etzhayyimの資本フロー監視actor、gftdcojpの`pymagatama`/`keiei`デーモン）に独立して再利用されており、Pregel関連の技術的定義はどこにも無い。組織内の実在するPregel-cellシステムは`kotodama`の永続デーモンカタログ（ADR-2605192415）だが、extension loadingとは無関係。
- **対応**: このマッピングを「未実装のギャップ」ではなく**「概念として撤回」**として扱った — 存在しない定義を推測で作らず、依存順序付きlifecycleを"magatama"抜きで実装。
- **拡張ローダは`kami-nv-compat`から正本として移植**: `kotoba.lang.kami-nv-compat.amenominaka.{application,extension}`（クリーンルームの`omni.kit.app.IApp`/`omni.ext.IExt`ミラー、ADR-2605261800 D6/D10.4）を発見 — Kahn位相ソートによる`startup-all`/`shutdown-all`（親→子の順で起動、逆順で停止、循環はthrow）と、実働する`extension.toml`サブセットパーサ`parse-extension-toml`が既に実装・テスト済みだった。ADR-2605261800自身のN10（「nv-compat namespace内でcanonical機能拡張... 禁止」）に従い、ロジックは変更せず`kami-app-amenominaka`側へ正本として移植した（`kami-nv-compat`側は変更せず、将来thin facade化する余地を残す — 別スコープ）。
- **`kotoba-lang/toml`は使えないと確認**: D7の当初計画は「`kotoba-lang/toml`で`.toml`互換薄層」だったが、実ソースを読んだ結果 `toml.core/toml` は **EDN→TOML専用**（パーサ無し）と判明。リテラル`.toml`読み込みには使えないため、`kami-nv-compat`の手書きパーサをそのまま移植した。
- **M0/M2をextensionとして配線**: `kotoba.amenominaka.extensions`が新規で実装した部分 — `scene`(M0、依存なし)と`render-ir`(M2、`scene`に依存)を`extension.edn`（ファイルではなくコード内EDNデータ — 単一アプリでプラグインディレクトリのスキャン需要が無いため）として登録し、依存順（`["scene" "render-ir"]`）で起動することをテストで確認。
- **R1.4ゲートの最終状態**: `omni.usd`(M1)・`omni.kit.viewport`(M2)・`omni.kit.app`(M3のローダ自体)の3/5は実装済み。`omni.timeline`（キーフレーム/カメラパス、D7が"M3 stretch"と明記）は未実装のまま。`omni.replicator.core`（合成データ）は最初から対象外（D6明記）— ゲートは構造的に完全には閉じない。
- **検証**: 29 tests / 122 assertions green（M0-M2込み、67の新規テスト追加）、clj-kondo clean。CI green（`test`/`webgpu-smoke`両ジョブ、main上でも確認）。
- **未解決のまま**: `omni.timeline`（M3 stretch、未着手）、`wgsl`の`@compute`対応（M4）、インタラクティブなorbit/flyカメラ操作。

## Addendum (2026-07-10): M4 実装 — 実測で真因を特定、D6-bの想定を訂正して着地

M4（stretch/条件付き）はオーナーとの合意により、まず**実際にM2のCPU-authored instancingが性能上限にぶつかるかを実測してから着手を判断**する方針で進めた（D6-bの「M2が性能上限にぶつかった場合のみ着手」を文字通り検証）。

- **実測（実ブラウザ、Playwright+フルChromium、静的シーン60フレーム描画）で本物の壁を発見**: 1,000要素=3.4ms/frame、5,000要素=11.4ms/frame、**8,000要素で60fps割れ（18.0ms/frame）**、15,000要素=34.0ms/frame(≈29fps)。M4のトリガ条件は満たされた。
- **真因診断がD6-bの想定と異なっていた（重要）**: CPU側の`kotoba.amenominaka.render-ir`ブリッジ自体は2万要素でも約25msで完了する一度きりのコストであり、実際のボトルネックは**`kotoba-lang/webgpu`の`draw!`が静的シーンでも毎フレーム全instanceを再ソート・再グルーピング・再マーシャリング・GPUへ再アップロードしていた**こと（`draw!`のソースを直読して確認 — sort-by/partition-by/reduce/Float32Array確保+dotimesマーシャル+write-buffer!が全て条件なしで毎フレーム実行）。**「GPU側compute shaderでのinstance生成が不足している」というD6-b/D7のADR自身の想定は実測データと合わなかった** — GPU容量の限界ではなく、CPU側の冗長な毎フレーム再アップロードだった。
- **対応方針をオーナーに確認の上、実際のボトルネックを修正**: `wgsl @compute`のGPU側instancing実装（当初のM4想定）はこの具体的なボトルネックを解消しないため見送り、代わりに`kotoba-lang/webgpu`（`kami-app-amenominaka`ではなく共有依存先）の`draw!`にinstance bufferのdirty-trackingキャッシュを実装（`init!`が返すcontextに`:instance-cache`atomを追加、`(:instances ir)`を`identical?`で比較しキャッシュヒット時はsort/marshal/uploadを丸ごとスキップ）。後方互換（`:instance-cache`が無いcontextは常にcache-miss経路、既存動作と同じ）。
- **修正の実測効果**: 同一ベンチマークで、5,000要素 11.4ms→0.47ms、15,000要素 34.0ms→0.85ms、20,000要素(`MAX-INST`16,384で頭打ち) 36.3ms→1.03ms — **高負荷域で約30〜40倍高速化**。M2の既存スクリーンショット検証（`verify_m2_render.cljs`）を無変更で再実行し同一の正しい描画を確認、`nbb test`の`.cljc`スイート（この変更の影響を受けない）もgreenのまま。
- **`kami-app-amenominaka`側にも再利用可能なベンチマーク一式を着地**（`kotoba.amenominaka.render-stress-demo` + `test/render/verify_m4_stress.cljs`）: 単発の調査で終わらせず、CIに回帰ガードとして組み込み（15,000要素でavgFrameMsが5msを超えたら fail — 修正後の実測約0.9msに対し十分な余裕を持たせつつ、O(n)per-frameへの退行は検知できる閾値）。
- **worktree運用上のミス**: 最初に構築したベンチマークharnessをuncommittedのままworktreeごと`rm -rf`してしまい紛失 — 実際の修正（webgpu側）は既にlandedだったため実害は無かったが、ベンチマークtooling自体は再構築して正式にlandした。
- **副産物の発見**: `verify-west-pins.cljs`は`--entry`指定時も無関係な全entryの整合性を検証するため、無関係な既存issue（`scene2d`のpinが3コミット分stale、GitHub API比較で確認 — 私の変更とは無関係）に一度ブロックされた。`--no-verify-remote`を使用し理由をcommit messageに記録（CLAUDE.mdの規定通り）。
- **未解決のまま**: `MAX-INST`（16,384）を超えるinstanceはdraw!が無警告で切り捨てる — これはパフォーマンスでなく**正確性**のギャップ（大規模都市スケールのシーンでは実在しうる）。`omni.timeline`（M3 stretch）、インタラクティブなorbit/flyカメラ操作も引き続き未着手。

これでADR-2607100100のM0〜M4全マイルストーンが完了（M4は「見送り」ではなく実測に基づく代替修正という形で完了）。

## Addendum (2026-07-09): M5 実装 — インタラクティブUIシェル（オーナー確認: 「twinmotion の uiux は設計されている?」→未設計と回答した上で「ok, do it」）

M0-M4完了後、オーナーから「Twinmotion相当のUI/UXは設計されているか」と問われ調査した結果、**専用のUI/UXは一切存在しない**（それまでの成果物は全て単発の静的デモページ経由でしか見られなかった）一方、org全体の**default UI/UXデザインシステム**（ADR-2607022800: `shitsuke`+`liquid-glass-ui`+`kotoba-ui`+`appkit`/`uikit`）は実在し、実デプロイ済みの参照実装（`murakumo-studio`、`local-manimani`）もあると報告、「ok, do it」の指示でM5として実装した。

- **設計判断の根拠を全て実ソース確認**: `liquid-glass-ui`は`liquid-glass.gpu`（canvas内での真のガラス質感描画）が明示的に未来課題であることをADR/design docsで確認 — このappの実際の形（「DOM chromeがcanvas leafを囲む」）はミスマッチでなく、`kami-engine-hud`（既存の無関係なWebGPU HUDオーバーレイ）で既に前例のある形と一致することを確認した上で採用。デスクトップ向けpanel/toolbar/dropdown中心のappなので`appkit`（`uikit`ではなく）を選択 — 両者の差分は`panel`/`list-view`のデフォルトのみと実ソースで確認済み。
- **`kotoba-ui`の実バグ2件を発見、upstreamにパッチせず回避**: `menu-select`はReactでuncontrolled（`<select>`に`:value`なし、`<option>`に`:key`なし）、`button`は`:on-click`を一切サポートしない（shitsukeのSSR専用`:act`契約のみ）。両方とも実ソースを直読して確認した実バグ。`murakumo-studio/src/murakumo_studio/ui.cljs`が同一バグに対して既に確立していた、同一DOM形状を保つ手書きcontrolled-component代替（`preset-select`/`btn`）をそのまま移植 — CSSのターゲティングを壊さずに機能させた。
- **カメラ設計はM4のキャッシュを意識**: `apply-camera!`は既存render-IRへ`:eye`/`:target`を`assoc-in`するだけで`:instances`に触れない — マウスドラッグ/ホイールによるカメラのみのフレームでは`kotoba-lang/webgpu`のinstance bufferキャッシュ（M4、`identical?`判定）がヒットし続ける。実際にプリセット変更（`recompute-scene!`）があった時だけ`:instances`を再構築する設計。
- **実ブラウザ検証（`test/render/verify_m5_ui.cljs`、nbb+Playwrightフルchromium）が検証対象自身のバグでなく検証ハーネス側の実バグを発見**: 静的CSSサーバー（`test/render/lib/webgpu_harness.cljs`）の`mime-types`マップに`.css`エントリが無く、Chromiumが`vendor/kotoba-ui.css`を`application/octet-stream`として受け取り**スタイルシートとして無言で適用拒否**していた（`document.styleSheets`のルール数が実際の132件でなく1件だったことで発見）。`".css" "text/css; charset=utf-8"`を追加して修正、再検証でliquid-glassのダークテーマ・パネル・specular装飾が正しく適用されることを実スクリーンショットで目視確認。
- **検証項目**: 環境プリセット4種のdropdown（`#field-weather`等）と`#viewport`canvasがDOM上に存在、初期状態が`#debug-state`（M2/M4の`#out`と同じ「実DOM経由でしか読める状態がない」ためのidiom — WebGPU canvasのpixel readbackはM2で既に信頼性なしと判明済み）に反映、`page.selectOption`で天候をclearに変更すると実際にReactの`onChange`→`recompute-scene!`→`#debug-state`まで反映される（controlled-component代替が実際に機能する具体的証拠）、`#viewport`のドラッグで実際に描画フレームが変化する（pixel readbackでなく2枚の実Chromiumスクリーンショットの比較）、コンソールエラーなし — 全てgreen。GitHub Actions macOS（`webgpu-smoke` job）でも同一検証がgreen、スクリーンショットをartifactとしてアップロード。
- **プリセットidは全て実ソースから採取**: weather（overcast/clear）、terrain（plains/quarry/desert/tundra）、vegetation（grass/fern/palm/conifer/bush/cactus/moss）、postfx（nintendo/retro/final-fantasy/baminiku-character） — 各`kami-*-scene`リポ自身の出荷済みEDNを直読し、推測なし。
- **M4 Addendumの「未解決のまま」の一項目を解消**: 「インタラクティブなorbit/flyカメラ操作」は本M5で実装完了（orbit+zoom。flyカメラは未着手のまま — orbitのみで実用上十分と判断）。`omni.timeline`・`MAX-INST`上限は引き続き未解決（M5のスコープ外）。
- **CI/manifest反映**: `clojure -M:test`（29/29, 122 assertions）・`-M:lint`（0/0）は無変更、`kami-app-amenominaka`のCIに`appkit`/`kotoba-ui`/`shitsuke`/`liquid-glass-ui`（+`css`、babashkaのローカル`:paths`解決専用）のsibling checkoutと`nbb ui-css`+`shadow-cljs compile shell`+M5検証ステップを追加、両job green確認後にmainへサーバーサイドmerge。superprojectのwest.ymlも`--entry kami-app-amenominaka`でpin前進（サーバー側pin検証OK、fast-forward）。

## Addendum (2026-07-10): M6 実装 — MAX-INST 無警告切り捨てを修正（オーナー「next」の選択肢の1つ、AskUserQuestionで4件とも選択）

M5完了後、オーナーに「next」で残る4項目（MAX-INST上限修正／glTF書き出し／fly camera／omni.timeline）を提示し全て選択された。まずMAX-INST（M4/M5 addendumが繰り返し「未解決のまま」と記録していた**正確性**ギャップ、パフォーマンスではない）から着手。

- **真因は`kotoba-lang/webgpu`の`draw!`が固定サイズGPUバッファに`(take MAX-INST raw-instances)`で無警告に切り捨てていたこと**（`compute-instance-data`のソース直読で確認）。`kami-app-amenominaka`側のM4ベンチマーク（`render-stress-demo`）は`scene->render-ir`が生成した`instanceCount`（入力側、常に正しいn）しか報告しておらず、`draw!`内部の切り捨ては**このベンチマーク自身の出力からは一切見えなかった**（M4 addendumの「20,000要素はMAX-INST 16,384で頭打ち」という記述は、ベンチマークの数値からでなくソースコードを読んで得た知識であって、実測で検出されたものではなかったと判明 — 本addendumで訂正）。
- **修正: 固定16,384バッファをdoubling方式で動的成長するバッファに変更**。`org-w3-webgpu`に`destroy-buffer!`（`GPUBuffer.destroy()`の薄いラッパー、既存の`create-buffer!`と対の操作が無かった）を追加。`kotoba-lang/webgpu`の`init!`は`:inst`（固定バッファ）でなく`:inst-buffer`（`{:buf :capacity}`のatom、初期容量は従来と同じ16,384で典型シーンの挙動を変えない）を返すよう変更、新規`ensure-inst-buffer!`が`draw!`の中で毎フレーム容量を確認し、必要ならcapacity×2ずつ倍化して旧バッファを`destroy-buffer!`してから新バッファを作る。`compute-instance-data`の`(take MAX-INST ...)`は削除（無制限）。M4のinstance-cacheとの整合: バッファが新規作成された（`grew?`）frameは、たとえCPU側instanceデータがcache-hitでもGPUバッファの中身は未定義なので強制的に再アップロードする分岐を追加——cache-hitとgrow?が両立する経路は現状発生しないが、防御的に正しくしてある。
- **新規observabilityフック**: `kami.webgpu/inst-buffer-capacity`（現在のGPUバッファの容量を返す）を追加 — テスト用途だけでなく、呼び出し側が「切り捨てられていないか」を確認できる一般的な診断フックとして。
- **実ブラウザ検証で切り捨てを直接反証**: `render-stress-demo`の`#out` JSONに`instBufferCapacity`を追加、`verify_m4_stress.cljs`に新規`check-no-truncation!`（n=20,000で`instBufferCapacity >= n`を検証）を追加。実測: n=20,000で`instBufferCapacity`が16,384→32,768へ倍化、`instanceCount`は20,001（地形instance込み）で切り捨てなし。既存のM4パフォーマンス回帰チェック（n=15,000で<5ms/frame）も同時にgreenのまま（1.2ms/frame）——growth自体はcache-miss frame（実際のシーン変更）でしか起きないため、M4が固定したO(1)per-frameコストを再度崩していないことも実測で確認。M2/M5の既存検証（`verify_m2_render.cljs`/`verify_m5_ui.cljs`）も無変更で再実行しgreenのまま（M5のUIシェル自体は`:inst`/`:inst-buffer`という内部キー名変更の影響を一切受けない——`webgpu/draw!`の外部呼び出し契約は変わっていない）。
- **3リポジトリにまたがる着地**（M0-M5は基本1リポジトリのみだったのに対し、本milestoneは`org-w3-webgpu`→`kotoba-lang/webgpu`→`kami-app-amenominaka`の順に3件のPR/mergeが必要だった）: `org-w3-webgpu`はCIが`push: branches:[main]`+`pull_request:`のみでbranch pushでは起動しないと判明したためPRを作成してCI確認（`webgpu`/`kami-app-amenominaka`は素のbranch pushでCIが起動する設定だったため従来通り）。3リポジトリとも個別にgreen確認後、この順でサーバーサイドmerge。superprojectのwest.ymlは`--entry org-w3-webgpu,webgpu,kami-app-amenominaka`（カンマ区切り複数entry指定）で1コミットにまとめて最小diff前進、3件ともサーバー側pin検証OK（fast-forward）。
- **作業中の手順ミス（自己訂正）**: `destroy-buffer!`の最初の実装を誤って共有checkout（`orgs/kotoba-lang/org-w3-webgpu`本体）に直接書いてしまい、CLAUDE.mdの「共有checkoutは閲覧/統合専用」規定に反した——気づいた時点で`git checkout --`で即座に revert し、正しく独立worktreeで再実装した（実害なし、共有checkoutは編集前の状態のまま）。
- **未解決のまま**: glTF書き出し（M1残り）、fly camera（M5残り）、`omni.timeline`（M3 stretch）——いずれもM7以降として続けて着手する。

## Addendum (2026-07-10): M7 実装 — glTF書き出し（オーナー「next」で選択した4項目の2件目）

M6完了後、引き続きオーナーが「next」で選択した残り3項目のうち、glTF書き出し（M1が当初USDのみ実装しglTFを見送っていた分）に着手した。

- **既存repoの調査で判明した実態**: `org-khronos-gltf`（旧名`gltf`、ADR-0048系）は実在しテスト済みのglTF 2.0バイナリ(`.glb`)writer/parserだったが、`build-gltf-json`は**単一node/単一mesh/単一materialの固定形状のみ**（USD側の`usd.core/usda`が持つ`[:def "Xform" name & children]`可変長hiccupのような一般的な多nodeビルダーが無い）。superproject全体を検索しても`org-khronos-gltf`の実consumerはゼロ（`render`/`kami-cad-import`/`org-vrmc-vrm`が各自独立にglTF相当ロジックを実装しており、`manifest/repos.edn`自身も「duplicated logic in gltf(write)とvrm(read)」と既に記載していた重複）。
- **`org-khronos-gltf`自体を拡張**（`kami-app-amenominaka`側だけで完結させず、正しく「raw specレイヤーを一般化する」方向で対応）: `export-glb-scene-byte-seq`/`export-glb-scene`/`build-gltf-scene-json`を新規実装 — ネストしたnode木（`{:name :translation :mesh :children}`、meshはoptional＝USDのXformプレースホルダに相当する純粋なtransform-onlyノードも自然に表現可能）をDFSでglTFのフラットな`:nodes`/`:children`インデックス形式へ変換し、全meshのvertex/index bytesを1本の共有バッファへレイアウト。6件の新規テスト（既存19件+6=25件/749 assertions、実際にexport→`parse-gltf`で読み戻す形の検証、JSON形状だけのassertではない）。CI green（JDK17/21マトリクス両方）。
- **`kami-app-amenominaka`側は`usd-export`のジオメトリ忠実度ルールをそのまま再利用**: `rectangle-axis-sweep?`/`element-kind-str`を`usd-export`内で`defn-`から`defn`へ公開範囲変更し、`gltf-export`から直接呼び出す形にした（同じ判定ロジックを2箇所に複製せず、2つのexporterが将来サイレントに乖離しない設計）。sites→buildings→storeys→elementsの木構造walkもUSD版と1:1で対応。
- **実装中に発見した実バグ2件**:
  1. **座標系**: `bim`/USDはZ-up（`scene->usda`の`:upAxis :Z`）だが、glTF 2.0仕様は**Y-up必須**。`zup->yup`（`[x y z]→[x z (-y)]`、行列式+1の軸置換）で全position/normal/translationを変換。
  2. **winding順**: `axis-sweep-rectangle->box-mesh`（M1で実装、USD向け）の面頂点順を実際に外積計算で検証した結果、**外向きでなく内向き法線を生む順（CW-from-outside）だった**——USD側はこれまで法線の向きを一切assertしていなかったため無害だったが（`usd-export`のテストは点/インデックスの生データのみ検証）、glTFはCCW-from-outsideが既定のfront-face規約かつ実際にper-vertex法線を出力するため、この不整合はそのままでは可視的に破綻する（法線が内向き＝標準的なbackface cullingで裏返って見える）。`box-mesh->gltf-mesh`で各面の頂点列を反転（triangulate前）してから処理することで、法線とwinding順の両方を一度に正しくした。
- **実ブラウザ検証（`test/render/verify_m7_gltf.cljs`）が「モックでなく本物のダウンロード」を検証**: M5のUIシェルに追加した「Export glTF」ボタンを実際にPlaywrightでクリックし、Chromiumの実`download`イベントで生成された実ファイルをディスクから読み取り、`org-khronos-gltf`自身の`parse-gltf`でパースして構造検証（GLBマジックバイト、期待どおり5ノード、実mesh1件・24頂点＝6面×4の正しいflat-shaded box、shared 8点でないこと）。GitHub Actions macOS（`webgpu-smoke` job）でもgreen。
- **作業中に無関係の重大な問題に遭遇（自己解決）**: pin前進の直前、`manifest/repos.edn`が`main`上で**未解決のgit conflict marker（`<<<<<<< Updated upstream`）を含んだまま**コミットされている（`gen-west-manifest.cljs`が`clojure.edn/read-string`でSymbolしか読めず`IllegalArgumentException`）ことを発見した。GitHub API直読みで確認したところ、直近（2026-07-10 02:41 UTC、コミット`a82acb97de0e`、「ADR-2607093500 adnet」登録）で別の並行セッション（同一ユーザーの別Claude Codeセッション）が誤って混入させたものと判明——親コミット`24c84dbfbb10`では repos.edn は正常だった。**このADR/M7の作業では一切手を加えず**（repos.edn を勝手に「解決」せず）、少し待って再確認したところ、当該並行セッション自身が数分以内に修正済み（`main`が`4439196f1ce4`へ前進、conflict markerゼロを確認）だったため、正常化を確認した上で本来のpin前進作業を再開した。
- **未解決のまま**: fly camera（M5残り）、`omni.timeline`（M3 stretch）——引き続きM8/M9として着手する。

## Addendum (2026-07-10): M8 実装 — free-flyカメラ（オーナー「next」で選択した4項目の3件目）

M7完了後、引き続きオーナーが「next」で選択した残り2項目（fly camera/omni.timeline）のうち、M5が当初orbitカメラのみ実装しfly cameraを見送っていた分（M4/M5 addendumが繰り返し「未解決のまま」と記録していた項目）に着手した。

- **`kami.webgpu.ir`/`kami.webgpu`への新規API追加は不要だった**: `rig->camera`は`:eye`/`:target`を生成する「1つの方法」に過ぎず（`draw!`/`apply-camera!`は結果のeye/targetしか見ない）、`fly-eye-target`（`{:pos :yaw :pitch}`から同様にeye/targetを直接計算する純関数）を`kami-app-amenominaka`側にだけ追加すれば十分だった。`apply-camera!`はcamera-modeで分岐してどちらの関数を呼ぶか選ぶだけで、M4のinstance bufferキャッシュ（`:instances`が変わらない限りGPUバッファ再アップロードをskipする仕組み）はどちらのカメラモードでも同様に有効（camera-onlyフレームは`:instances`に一切触れない）。
- **設計**: WASD移動はyaw方向のみを基準にした水平面相対移動（pitch方向は無視——見下ろしながら前進しても地面に突っ込まない、標準的なFPSカメラの挙動）、Space/Shiftで垂直移動、マウスドラッグでyaw/pitchのlook操作（pitchは±90°手前でclampしジンバルフリップを回避）。移動はrequestAnimationFrameループが「現在押されているキーの集合」を毎フレーム読む方式（per-keydownイベントの単発デルタでなく）——キーを押しっぱなしにしている間、滑らかでフレームレート非依存に移動し、camera-modeがfly以外に切り替わったら自己終了する。orbit→fly切替時は現在のeye/targetからpos/yaw/pitchをシードし、視点がジャンプしないようにハンドオフする。
- **実ブラウザ検証（`test/render/verify_m8_fly_camera.cljs`）で実タイミングバグを発見・対処**: 実装中に、React 18の自動イベントハンドラbatchingにより、Reagentの`:on-click`内`swap!`の結果がPlaywrightの`.click()`プロミス解決前にDOMへflushされるとは限らないという実タイミング問題を発見した（1回目のorbit→fly切替は直後の読み取りでも偶然成功したが、2回目のfly→orbit切替では失敗し「got fly」のまま——continuous re-renderの負荷差が原因と推定）。`#debug-state`を1回読むのでなく期待値になるまでpollingする形（既存の`verify_m4_stress.cljs`の`poll-out-text`と同じidiom）に修正して解決した。実際の`w`キー600ms押下（Playwright `keyboard.down`/`up`——本物のkeydown/keyupイベント、状態の直接書き換えではない）による実移動（`:fly :pos`がZ軸方向に約5units移動）と実レンダリングフレーム変化（2枚の実Chromiumスクリーンショット比較）を確認、GitHub Actions macOS（`webgpu-smoke` job）でもgreen。
- **未解決のまま**: `omni.timeline`（M3 stretch）——引き続きM9として着手する。

## Addendum (2026-07-10): M9 実装 — omni.timelineパリティ（オーナー「next」で選択した4項目の最後）

M8完了後、引き続きオーナーが「next」で選択した最後の項目（omni.timeline）に着手した——これでM5完了時に提示した4項目（MAX-INST上限修正/glTF書き出し/fly camera/omni.timeline）すべてに着手完了。

- **D7自身のスコープを厳密に踏襲**: D7が「最小限のキーフレーム/カメラパス」と明記しており、フルのUSD-stageアニメーションタイムライン（任意プロパティのスクラビング、レイヤー等）は最初からスコープ外（M1のMaterialXバインディングギャップと同じ「推測実装より明記済みギャップ」方針）。`kotoba.amenominaka.timeline`は依存ゼロの純粋な`.cljc`——キーフレーム列`[{:t :eye :target} ...]`、`add-keyframe`（2秒間隔で自動追加）/`duration`/`eval-at`（境界2キーフレーム間の線形補間、範囲外はclamp）のみ。
- **設計**: 「Record Keyframe」は現在のカメラが何であれ（orbitでもflyでも、M8が既に両方とも同じ`:eye`/`:target`を生成することを確立済み）その時点のrender-IRから直接キャプチャする。「Play」は実`requestAnimationFrame`ループで`eval-at`を毎フレーム呼びrender-IRへ`:eye`/`:target`を直接書き込む——意図的に`apply-camera!`/`:camera-mode`を経由しない設計（再生は現在のカメラが何をしていようと一時的に上書きするものであって、永続的なモード切替ではないため）。orbit/fly状態自体は再生中も変更されず、再生・スクラブが止まればそのまま再開する。
- **実ブラウザ検証（`test/render/verify_m9_timeline.cljs`）で実バグ2件を発見・対処、いずれもアプリ本体でなく検証手法自体のバグ**: (1) Playwrightの`locator.fill()`は`type=range` inputを未サポート（実行時に「Malformed value」エラー）——直接`.value`書き込み+`input`/`change`イベントdispatchで代替した。(2) M8で発見済みのReact 18自動batchingによる実タイミングレースが「Record Keyframe」ボタンでも非決定的に再発することを確認——同じ対処法（`#debug-state`をpollingする）を一貫して適用して解決。実際に2つの異なる位置でのキーフレーム記録（fly modeでの実`d`キー押下による実移動を挟む）、中点（t=1.0）へのスクラブでrender-IRの`:eye`が2つの記録eyeの算術平均と完全精度で一致することを確認、実時間再生で自動停止（playhead≈duration）と実レンダリングフレーム変化を確認、GitHub Actions macOS（`webgpu-smoke` job）でもgreen。
- **本ADRの全マイルストーン完了**: M0-M9で、Milestonesに記載された全項目（M0-M3+M5はオリジナル設計通り、M4は実測ベース代替修正、M6-M9はオーナー追加リクエスト）が完了。R1.4ゲートの5項目中4項目（`omni.usd`/`omni.kit.viewport`/`omni.kit.app`/`omni.timeline`）が実装済み、`omni.replicator.core`のみ最初から対象外（D6明記の非ゴール）。着地作業中、無関係な並行セッションの活動（`manifest/repos.edn`への一時的なgit conflict marker混入と自己解決、ADR `.edn`ファイル群のDatomic-queryable形式への一括移行）に2回遭遇したが、いずれも実害なく確認・対応した（詳細はM7 addendum参照）。
