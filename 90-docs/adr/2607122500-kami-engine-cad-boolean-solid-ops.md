# ADR-2607122500: `kami-engine-cad` boolean solid 演算（union/difference/intersect）— axis-aligned coaxial convex-polygon prism に限定した narrow scope

- **Status**: accepted（実装・テスト・マージまで完了。west/manifest への pin 反映のみ superproject 側で別途実施）
- **Related**: ADR-2607121500（`kami-app-cad`/`kami-engine-cad` を kotoba-lang における Rhino3D 相当として west 登録。Open Questions で boolean を次のギャップ候補として明記していた本人）、ADR-2607083900/2607084200（`org-iso-10303` — 別系統の BREP/STEP カーネル。「axis-aligned cylinder revolve のみ実装し angled/frustum は明示的に対象外」という **narrow-first-cut の作法**を本ADRでも踏襲する）

## Context

ADR-2607121500 の Open Questions に残っていた「boolean 演算の実装方式（robust polygon/polyhedron clipping）」に着手した。`kami-engine-cad`（`src/kami/cad.cljc`）を読むと、この engine が今まで作れる solid は**すべて `extrude-polygon` の結果**であることが分かる: 平面ポリゴン（1ループ）を1本の押し出しベクトルで剛体並進コピーした prism で、`{:cad/kind :solid :solid/vertices [...] :solid/faces [...]}`。一般の三角メッシュ/BREP boolean カーネルはこのリポジトリに存在しない。

一般の任意多面体 boolean（robust triangle-mesh/BREP boolean）を今回の第一実装として書くのは、失敗しやすい大仕事であり、このモノレポの既存の作法（`org-iso-10303` が「axis-aligned cylinder revolve のみ実装し、angled/frustum revolve は明示的に対象外」とした narrow-but-genuinely-correct の選択、ADR-2607083900/2607084200）に反する。そこで本ADRも同じ規律に従い、**「今の data model が実際に許す誠実に正しいスコープ」を選び、対象外を明記する**方針を取った。

### 現行 data model の再確認（実装前の調査結果）

- `extrude-polygon` の結果 solid は常に `points ++ (points 並進コピー)` の頂点配列（`n` 頂点の底面 + `n` 頂点の上面）と `n+2` 面（底面1 + 上面1 + 側面 quad ×n）を持つ。
- したがって solid は数学的に **(2D 断面ポリゴン) × (押し出し軸方向の1D区間)** という直積構造を持つ。
- `kami-app-cad` の `rounded-rect-solid`（`src/kami/cad/app.cljs`）が示す通り、断面ポリゴンは単純な矩形とは限らず、`fillet-sketch` により角を丸めた凸多角形（円弧テッセレート込み）にもなり得る — が、凹多角形や自己交差ポリゴンを生成する経路は現状の sketch 実装には無い。
- 押し出し方向は現状すべてのテスト/UI 経路で `[0 0 h]`（z軸）だが、`extrude-polygon` のAPI自体は任意方向ベクトルを受け付ける。

## Decision

### D1. 直積構造を使った厳密に正しい `boolean-intersect`

任意の集合について `(A1×B1) ∩ (A2×B2) = (A1∩A2)×(B1∩B2)` が**無条件に成立する**（直積の共通部分の恒等式）。これを使い、断面ポリゴンの共通部分（凸多角形同士の Sutherland-Hodgman clipping — 凸 vs 凸で厳密かつ穴を作らない）と、押し出し軸区間の共通部分を独立に計算し、再度 `extrude-polygon` する。これは **軸・profile が異なる2つの coaxial prism 同士でも無条件に正しい**。

### D2. `boolean-union`/`boolean-difference` は「同一 profile・区間差のみ」に限定

union/difference には D1 と同じ直積の恒等式が一般には**成立しない**（`(A1×B1) ∪ (A2×B2) ≠ (A1∪A2)×(B1∪B2)`、`A1=A2` または `B1=B2` の退化ケースでのみ成立）。さらに、凸ポリゴン同士の一般差分（`A\B`）は非凸領域だけでなく、**穴あき（multiply-connected）ポリゴン**（例: 正方形の中央から小さい正方形を抜いた「額縁」形状）を生む場合があり、これは本 engine の単一ループ `extrude-polygon` では表現できない。

したがって union/difference は **2つの solid が同一の断面ポリゴン profile を持つ場合（＝押し出し軸の区間だけが異なる、同一形状の積み重ね/くり抜き）に限定**した:

- `boolean-union`: 区間が重なる/接する → 1つの区間統合 solid。重ならない（gap がある）→ 元の2 solid をそのまま返す（この engine は非連結な2 solid を1つの manifold に融合しない）。
- `boolean-difference`（A−B）: A の区間から B の区間を引いた残り0〜2区間を、A の profile のまま再押し出しする。

Profile 不一致・軸不一致の union/difference は throw する（対象外、下記 Non-goals）。

### D3. 共有の入力検証（3演算共通）

`coaxial-prism`（`src/kami/cad.cljc` 内、非公開ヘルパ）が両 solid から `{:axis :polygon :lo :hi}` を抽出しつつ次を検証し、いずれか不成立なら `ex-info` を throw する:

1. 両 solid が watertight な `extrude-polygon` prism であること（頂点数が偶数・n≧3・面数が n+2、上面が下面の剛体並進であること）。
2. 押し出し方向が **axis-aligned**（x/y/zのいずれか1成分のみ非ゼロ）であること。
3. 断面が押し出し軸に垂直な平面内にあること。
4. 断面ポリゴンが **凸**であること。
5. （3演算共通）両 solid の押し出し軸が **同じ座標軸**であること（"coaxial" — 軸の直線が同一である必要はなく、方向が同じであればよい。断面ポリゴンは平面内のどこにあってもよい）。

### D4. Feature-tree 配線

`:boolean-union`/`:boolean-difference`/`:boolean-intersect` を `evaluate-feature`（既存の `:fillet-sketch`/`:extrude` と同じ `case` dispatch）に追加。**返り値は常に 0〜2 要素の solid ベクタ**（intersect は重ならなければ `[]`、union は非連結なら `[a b]`、difference は完全に削れれば `[]`）。単一 solid を期待する既存コード（`solid-mesh` 等）を直接渡せる形にするため単一solidへ unwrap する誘惑があったが、それは「1個しか結果が無い」という前提を暗黙に埋め込む嘘になるため**しない**。`feature-model/results` に入るのは常にベクタで、消費側が `(first result)`/`(seq result)` を明示的に扱う。

### D5. `kami-app-cad` への最小限UI配線

「Solid Extrusion」パネルに「Boolean Solid Ops」セクションを追加（`public/index.html` 8行、`src/kami/cad/app.cljs` に2つの event listener）: 現在の Solid A を「Solid B」として stash → union/difference/intersect を選択 → 適用。結果ベクタの `(first results)` のみビューポートに表示（複数結果を同時表示するビューアはこの最小実装のスコープ外、テキストで件数を報告するのみ）。エラーは throw を `catch :default` で捕まえテキスト表示（既存の `apply-project!` の catch 規約と同じ）。

## Non-goals（明示的にやらないこと）

- **一般の任意多面体（非coaxial・非凸・非軸整列）triangle-mesh/BREP boolean は対象外。** 大規模で失敗しやすい一般実装を第一実装として書かない、という本モノレポの規律（`org-iso-10303` 前例）に従う。
- **押し出し軸が異なる（non-coaxial）solid 同士の boolean は対象外**（throw）。例: z軸押し出しの箱と x軸押し出しの箱の union/difference/intersect。
- **凹多角形・自己交差断面は対象外**（throw）。
- **union/difference の「異なる profile 同士」（側面に切り欠きを入れる等）は対象外**（throw）— D2 で述べた通り、穴あきポリゴンを要求しうるため。
- **union/difference の結果が非連結（gap がある/完全に削れる）場合に「複数 solid のベクタ」を返す設計自体は対応済みだが、`kami-app-cad` の UI は最初の1個しか表示しない**（複数ビューアは対象外）。

## Verification

- `kami-engine-cad`: 21 tests / 94 assertions green（`clojure -M:test`）。新規5テスト:
  - `boolean-intersect-of-overlapping-boxes`（手計算 volume 12.0、頂点座標を集合として厳密一致確認、非重複ケースで空ベクタ）
  - `boolean-union-of-touching-same-profile-boxes`（接する2区間 → volume 60.0 に統合、非接触2区間 → `[a b]` それぞれ 24.0/12.0）
  - `boolean-difference-cuts-a-middle-slab-into-two-solids`（中央区間を削り2個の watertight solid、各24.0。自己差分で空、完全非重複で `[a]` 変化無し）
  - `boolean-ops-reject-non-coaxial-and-mismatched-profile-solids`（軸不一致・profile不一致・非watertight入力の reject）
  - `boolean-feature-participates-in-feature-tree`（feature-tree 統合、`:feature-model/results` がベクタで返ることを確認）
- `kami-app-cad`: 既存 8 tests / 19 assertions green（回帰なし）。`clojure -M:cljs -m shadow.cljs.devtools.cli release app` ビルド成功（0 warnings、23 files compiled）。`public/js/app.js` を再ビルドしコミット（過去コミット同様、Closure Compiler advanced-mode の変数リネームにより diff は全体に及ぶ — 前例 `0c7e7fd` と同規模のdiff、異常ではない）。
- サーバーサイドマージ: `kami-engine-cad` main `db0eb3185579f8a5a9e2b3511bc40ce7c2d295e4` → `a66b438f1909d72400c9c467bd70ca83291a9a5d`、`kami-app-cad` main `6d60ce4a...`（PR #1 マージコミット） → `5246ea840f6b91a91843d2f6465462a470136873`。

## Consequences

- (+) Rhino/SolidWorks/Fusion360 共通の基本CAD操作である boolean が、誠実な narrow scope の範囲内で feature-tree に参加できるようになった。
- (+) 「union/difference は同一profile限定」という制約はドキュメント（docstring + README相当のコメント + 本ADR）に明記されており、利用側が誤って一般ケースを期待することを防ぐ。
- (−) 側面に切り欠きを入れる・異形状同士を融合するといった「本当にほしくなる」boolean のユースケースの多くは、本ADRのスコープ外のまま（Non-goals参照）。一般 BREP boolean が必要になった時点で改めてADR化する。
- (−) `kami-app-cad` の UI は複数結果 solid のうち最初の1個しか見せない（D5）。

## Open Questions / Follow-up

- 一般の（非coaxial・任意断面）boolean の実装方式（robust polygon/polyhedron clipping、穴あきポリゴン表現を含む `extrude-polygon` の拡張、または一般 BREP kernel への委譲）は次のギャップ候補として残る。
- `org-iso-10303` 側で boolean が実装された場合、両者の重複・住み分けを再検討する必要がある（ADR-2607121500 の Non-goals と同じ懸案の継続）。
- superproject 側の `manifest/west.yml`/`manifest/repos.edn` pin 前進（`kami-engine-cad`・`kami-app-cad` とも）は本ADRの実装完了後、coordinator が別途実施する。
