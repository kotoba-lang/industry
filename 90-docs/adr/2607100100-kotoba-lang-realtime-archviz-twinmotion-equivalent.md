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
