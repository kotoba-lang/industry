# ADR-2607032500: kotoba-lang OOXML/PPTX coverage 拡充 — ロック/縦書き/テーブル寸法・updateパス機能パリティ・チャートデータラベル

**Status**: accepted — 実装・テスト済み、drawingml/presentationml/slides の main へ全反映済み、manifest pin 前進済み
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

`成熟度, coverage を 100%に`（100%は漸近的な目標であり到達不能——SmartArt・OLE・
アニメーション等は恒久的にスコープ外——という前提のもと、毎回firingごとに
次の一手を選んで実装し続けるという標準運用）という定期実行プロンプトのもと、
複数firingにわたり `kotoba-lang/drawingml`（DrawingML shape reader）・
`kotoba-lang/presentationml`（PresentationML package reader）・
`kotoba-lang/slides`（PPTX read/write pipeline、full-regen + source-aware
`update` パッチの両writer）の3リポジトリを横断して coverage を積み上げた。

ADR-2607021610（round-trip drift 修正）・ADR-2607021614（collaborative
editing CRDT）・ADR-2607021649（表/画像/グラフのネイティブ export）が
このパイプラインの以前の主要な意思決定を記録している。本ADRはそれに続く、
今回のセッションで積み上がった一連の機能追加・バグ修正・アーキテクチャ上の
発見を1つにまとめて記録する。

## Decision

### 運用パターン（継続確認された規約）

- **バグ＞機能の優先順位**: 「reader が捕捉済み（または容易に捕捉可能）な値を
  writer が無視・ハードコードしている」形の**実質的なバグ**は、単なる
  未実装機能より優先して着手する。バグは既存の全出力に静かに影響するため
  価値が複利的に高い。
- **「欠落＝既定値」規約**: EDN上のキーは、ソースが既定値と異なる場合、または
  ソースXMLにその要素が無い場合にのみ記録する。プレーンな（装飾なしの）
  シェイプ/スライドの round-trip 出力は、その機能が存在する前と
  バイト同一のまま保たれる。
- **worktree-per-task**: 全ての変更はsuperproject本体の外
  （sibling path、`/tmp/wt-<repo>-<feature>`）に切った worktree で行い、
  GitHub API サーバ側マージ（`gh api .../merges`）でmainへ着地させる。
  ローカルマージ/rebaseは行わない。
- **カスケードpin前進**: drawingml変更 → presentationmlの `deps.edn` pin更新
  → slidesの `deps.edn` pin更新（drawingmlへの直接pinとpresentationml経由の
  間接pinの**両方**を更新する必要がある——一方だけ更新すると古いコードを
  静かにテストし続ける事故が起きる、と判明済み）。

### 実装した機能・修正したバグ（カテゴリ別、詳細は各リポジトリの
README.md `## Coverage matrix` が正）

**読み書き機能追加（drawingml/presentationml/slides 横断）**:
テキストの縦書き方向（`<a:bodyPr>`/`<a:tcPr>` 両方の `vert=`、後者は
前者と別属性）、テーブルの列幅/行高（`<a:gridCol>`/`<a:tr>` の実寸法、
均等分割との差分がある場合のみ記録）、スライド単位の背景オーバーライド
（`<p:bg>`、マスターの背景に優先）、チャートのデータラベル（`<c:dLbls>`、
値/カテゴリ名/系列名/凡例キー/パーセント/バブルサイズ表示制御、チャート
全体で1つ、系列単位ではない）。

**実質的なバグ修正（writerがreader捕捉済みの値を無視/ハードコードして
いたケース）**: 画像のロックフラグ（`picLocks`、常に `noChangeAspect="1"`
固定だった）、テキスト/矩形シェイプのロックフラグ（`spLocks`、要素自体を
常に省略していた）、テーブル/チャートのグラフィックフレームロックフラグ
（`graphicFrameLocks`、常に `noGrp="1"` 固定だった）。3つとも
「reader は既に存在するが writer が結果を反故にする」という同型パターンで、
picture-locks の発見をきっかけに横展開した。

### updateパス（source-aware patch）機能パリティ

`update-pptx-bytes`（既存 `.pptx` バイト列 + 編集後デックEDN から、触った
箇所だけをXML上で書き換え無関係な部分をバイト単位で保持する経路）は、
フルリジェネレート（`pptx-bytes`、ゼロから組み立てる経路）が既に持って
いた機能の多くを反映していなかった——グラデーション塗り・エフェクト
（shadow/glow/reflection）・ハイパーリンク（組み込みナビゲーション＋
外部URL/内部スライドジャンプ両方）・ロックフラグ3種・ボディプロパティ
（wrap/anchor/margins/autofit/縦書き）・テーブルのスタイルフラグ/寸法・
スライド背景・コメント/ノート（既存編集＋新規追加の両方）まで、
1つずつ埋めた。

外部URL/内部スライドジャンプのハイパーリンクは、既存のシェイプ単体の
XML断片内で完結する他のパッチと異なり、**スライド自身の`.rels`への
新規リレーションシップ書き込み**が必要な、真に横断的な変更だった。
`patch-hyperlink-relationships` が、既存 `.rels` の最大rIdの続きから
rIdを割り当て、解決したrIdを一時的な内部キー
（`:slides/hyperlink-rel-id`）としてシェイプマップにマージし、既存の
`apply-rpr-overrides` 経路にそのまま流し込むことで、`patch-shape-block`
のシグネチャ変更を避けた。

コメントの既存編集（`patch-existing-comments`）は、コメントリストを
1つのまとまりとして扱い（テーブルセルのように個別アドレス可能な断片
ではないため）、コメントパート全体を再生成する方式にした。新しい著者名
にはパッケージ全体で共有される `commentAuthors.xml` に新しいIDを
追加する一方、既存の著者（他のスライドのコメントが参照しているものも
含む）は既存のIDを保持する——full-regenの `comment-authors-xml` が
常に 0,1,2... と振り直す設計のままpatchに流用すると、他スライドの
参照が指すIDを静かに破壊してしまうため、patch専用の著者ID解決
（`assign-comment-author-ids`）を新設した。

### 発見した副次的なバグ（updateパス監査中）

`patch-new-content`（画像/チャート/ノート/ハイパーリンクの新規追加を
扱う関数）は、コメントを一切扱っていなかった——コメント無しスライドへの
**新規**コメント追加すら動いていなかった（以前のREADMEの「新規コメントは
動く」という記載は誤りで、本セッション中に訂正）。さらに、この関数の
per-slideゲート条件が「新規（ロケーター無し）シェイプが1つも無ければ
スキップ」だったため、**新規シェイプを伴わない、ノート/コメントのみの
初回追加は静かに何も起きない**という潜在バグも発見した。既存のノート
テストがこの問題を「トリガー用のダミーシェイプ」で回避していたことが
判明し、ワークアラウンドを削除して修正そのものを直接証明する形に
書き換えた。

### manifest pin 前進

`manifest/west.yml` の `drawingml`/`presentationml`/`slides` の3エントリを
それぞれの `origin/main` tip まで前進させた（`e1f3dc37→563fb9d6`,
`030cb804→9d1ed29f`, `e1ceb61a→a2c2f352`）。差分は当該3行のみ
（`gen-west-manifest.cljs --entry` 相当の最小diff、wholesale再生成では
ない）。superproject本体の外に切った専用worktreeでPR経由でmainへ着地。

## Verification

- `drawingml`: 全機能実装ごとにテスト追加、最終 52 tests / 370 assertions
  （table-cell-vertical追加時点）まで積み上がり、以降のセッションでも
  regression無し。
- `presentationml`: 23 tests / 105 assertions、変更都度確認、regression無し。
- `slides`: 最終 229 tests / 1112 assertions（chart-data-labels追加後）、
  全機能追加・全patchパス機能について round-trip / patch-path 双方の
  専用テストを追加。

## Consequences

- (+) `update`（patchパス）は、フルリジェネレートで実装済みの機能と
  ほぼ完全に同等の編集能力を持つようになった——実ユーザーが「インポート
  したデックを軽く編集する」際に最も頻繁に通る経路の忠実性が大きく向上。
- (+) 3件の実質的なロックフラグバグが横展開的に発見・修正され、
  「reader捕捉済みの値をwriterが無視する」という不具合パターンの
  横断監査手法が確立された。
- (+) チャートのデータラベルなど、実デックで頻出する要望への対応が進んだ。
- (-) チャートXML自体の読み込み（type/series/legend/axis/data-labels等の
  round-trip）は今後も対象外——チャートインポートは reference-metadata
  のみ（rel-id + 解決済みpart path）のまま、意図的な非対称設計。
- (-) コメントの**既存**編集は完全対応したが、コメントの追加/削除に伴う
  `commentAuthors.xml` の完全なガーベジコレクション（他スライドから
  一切参照されなくなった著者のクリーンアップ）は行わない——additive-only
  規約（他のpatchパス機能とも整合）。
- (-) ハイパーリンクのpatchは、ターゲットのみ変更された場合でも既存の
  リレーションシップを再利用せず常に新規rIdを割り当てる——古いエントリが
  孤立したまま残るが、無害（スキーマ上有効）。
- (未対応/恒久スコープ外) SmartArt・OLE埋め込みオブジェクト・アニメーション
  （`p:timing`）・パターン塗りの完全round-trip・動画/音声の再生オプション・
  埋め込みフォントのバイト本体・3D/ベベルエフェクト。

## References

- `orgs/kotoba-lang/drawingml/README.md`（Coverage matrix）
- `orgs/kotoba-lang/presentationml/README.md`（Coverage matrix）
- `orgs/kotoba-lang/slides/README.md`（Coverage matrix、Patch/update path節）
- `90-docs/adr/2607021610-slides-pptx-roundtrip-drift-fix.md`
- `90-docs/adr/2607021649-slides-native-table-picture-chart-export.md`
- 本ADRとペアの `.edn`
