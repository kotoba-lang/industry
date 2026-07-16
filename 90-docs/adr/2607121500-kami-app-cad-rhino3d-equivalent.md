# ADR-2607121500: kotoba-lang 版 Rhino3D 相当の正式位置づけ — `kami-app-cad`/`kami-engine-cad` を west 登録し、スケッチ・フィレット/シャンパーを実装

- **Status**: accepted（本ADRで登録・小規模ギャップ実装まで完了。継続実装は Open Questions 参照）
- **Related**: ADR-2607083900/2607084200（`org-iso-10303`（旧`brep`）+ `org-iso-6983`（旧`cnc`）— 別系統のBREP/STEPカーネル。box+cylinder-revolveのみで、本ADRの対象より未成熟。両者は統合しない、下記Non-goals参照）、ADR-2607100100（`kami-app-amenominaka` — Twinmotion相当。`kami-studio`ハブページの兄弟app）

## Context

オーナーから「今の kotoba-lang で rhino3d に相当する app, engine は?」と問われ調査した。

- 最初に見つけた候補 `org-iso-10303`（旧`brep`）は ISO 10303-42 相当の BREP topology + STEP read/write を持つ幾何カーネルだが、実装済みは `extrude`（箱）と `revolve`（軸対称の完全回転体のみ）に限られ、fillet/chamfer/boolean/sweep/loft は明示的に未実装（ADR-2607083900/2607084200）。
- 追加調査で `orgs/kotoba-lang/kami-app-cad` + `orgs/kotoba-lang/kami-engine-cad` を発見。こちらの方が実装が進んでいた: 有理Bézier/NURBS互換カーブ（`evaluate`/`split-curve`/`trim-curve`/`join-curves`/`loft`/`loft-mesh`）、2Dスケッチ+拘束ソルバ（`sketch`/`constraint`/`solve-sketch`: 水平/垂直/一致/距離）、パラメトリックfeature-tree（`feature`/`feature-model`/`recompute-feature-model`、Rhino/SolidWorks型）、`solid`/`extrude-polygon`/`watertight-solid?`/`solid-volume`。
- **ところがこの2リポジトリは `manifest/repos.edn`/`west.yml` に完全に未登録、ADRも無い「未分離」状態**だった。実体は GitHub (`kotoba-lang/kami-app-cad`, `kotoba-lang/kami-engine-cad`) に実コミット付きで存在し、`kami-app-modeler`/`kami-app-amenominaka`と同一の GitHub Pages デプロイ（`shadow-cljs release app` → `public/` → `.github/workflows/pages.yml`）も既に稼働していた。
- 同じく未登録だった `kami-studio`（`orgs/kotoba-lang/kami-studio/src/kami/studio/ui.cljc`）というランチャーページが、CAD/Modeler/Sculpt/Animator/BIM Editor/Amenominakaの6appを一覧化しており、**CADを「NURBS & precision」と明記** — org自身の分類がこれを裏付ける。
- 他候補は不一致と確認済み: `kami-app-modeler`（polygon/sub-D、Blender/Maya型）、`kami-app-sculpt`（SDFブラシ変形、ZBrush/Mudbox型）、`kami-app-animator`（タイムライン）、`kami-app-bim-editor`（IFC型）、`kami-app-amenominaka`（Twinmotion型リアルタイムArchviz）は、いずれもRhino3Dが担うNURBS/パラメトリックソリッドモデリングとは別パラダイム。

オーナー承認（AskUserQuestion 2026-07-12）: 「登録+公開+小規模ギャップ実装1件」で進める。

## Decision

### D1. `kami-app-cad` + `kami-engine-cad` を kotoba-lang における Rhino3D 相当の正本とする

新規appは起こさない。既存の未分離実装を正式にRhino3D相当と位置づけ、west manifestに登録する（本ADRの直接の帰結、実行はこのADRのコミットに同梱）。

### D2. ギャップ実装: スケッチ・フィレット/シャンパー（直線-直線コーナーのみ）

Rhino/SolidWorks/Fusion360共通の基本CAD操作である「スケッチの角を丸める/面取りする」を`kami-engine-cad`に追加した:

- `fillet-sketch`/`chamfer-sketch`（`kami-engine-cad` `src/kami/cad.cljc`）— 隣接する2直線が共有する頂点を、指定半径/距離のタンジェント円弧（fillet）または直線カット（chamfer）に置き換える。両者ともパラメトリックfeature-tree に `:fillet-sketch`/`:chamfer-sketch` feature kindとして参加する。
- `sketch-loop->polygon` — フィレット/シャンパー後のスケッチをループとして辿り、円弧をテッセレートしてフラットなポリゴン点列に変換、既存の`extrude-polygon`にそのまま渡せる。
- スコープは直線-直線コーナーのみ（円弧-直線/円弧-円弧は対象外、T字接合や開いたループは拒否）。
- `kami-app-cad`側UIに「Corner radius」入力を追加、`rounded-rect-solid`が半径0で従来の直角矩形にフォールバックする形で配線。

### D3. 検証

- `kami-engine-cad`: 16 tests / 70 assertions green（タンジェンシー検証・過大/負半径のreject・feature-tree統合・watertight押し出しを含む）。
- `kami-app-cad`: 8 tests / 19 assertions green、`npx shadow-cljs release app` ビルド成功（0 warnings）。
- サーバーサイドマージ: `kami-engine-cad` main `09a7cea→db0eb31`、`kami-app-cad` PR #1 `473b465→6d60ce4`。
- GitHub Pages実配信確認: https://kotoba-lang.github.io/kami-app-cad/ に`corner-radius`入力が実際に反映されていることを`curl`で確認済み。

## Non-goals（明示的にやらないこと）

- **`org-iso-10303`（旧`brep`）との統合・一本化はしない。** 両者は別カーネル・別設計思想（`org-iso-10303`はISO 10303準拠のトポロジー厳密性優先、`kami-engine-cad`はスケッチ拘束+feature-tree優先のアプリ実装）であり、本ADR時点では重複を承知の上で並存させる。将来の一本化判断は別ADRに委ねる。
- 円弧-円弧/円弧-直線コーナーのfillet/chamferは対象外（D2）。
- boolean（和/差/積）は対象外 — 現在の`solid`/`extrude-polygon`モデルは単一ソリッド結果を前提にしており、boolean結果の多様体トポロジー処理は別途設計が要る。
- 真のNURBS曲面（`loft`/`loft-mesh`を超えるsweep/shell/patternでの自由曲面）は対象外。

## Consequences

- (+) 「未分離」だった実働2リポジトリが正式にwest管理下に入り、他セッション/エージェントが`manifest/repos.edn`経由で発見・依存できるようになる。
- (+) Rhino3D相当という位置づけが明文化されたことで、今後の機能ギャップ（boolean等）の優先順位判断がしやすくなる。
- (−) `org-iso-10303`との重複懸念は本ADRでは解消していない（Non-goals参照）— 将来的にどちらかに統合するか、住み分け（STEP交換用途 vs インタラクティブapp用途）を明文化するかは未決。

## Open Questions / Follow-up

- boolean演算の実装方式（robust polygon/polyhedron clipping）は次のギャップ候補として残る。
- `org-iso-10303`とのオーナーシップ・住み分けの明文化。
- 円弧を含むコーナーへのfillet/chamfer拡張。
