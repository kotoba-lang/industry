# ADR-2607051520: komawari(コマ割り)設計 — 黄金比 × フォースライン panel layout

**Status**: accepted (implemented, merged to each repo's main — see :artifacts in the companion .edn for SHAs/PR#)
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/ai-gftd-mangaka`、`orgs/kotoba-lang/kami-mangaka-page`、
`orgs/com-junkawasaki/ghosthacker/260123-jump`

## Context

鳥山明作品のコマ割り分析(YouTube解説動画のスクリーンショット2枚、ユーザー
提供)が示す2つの原則を、`mangaka.gftd.ai`(Ghost Hacker 漫画生成パイプライン)
に langgraph ノードとして設計統合し、実際に Ghost Hacker Jump 第1話を
書き直して検証してほしい、というオーナー指示から着手した。

1. **コマの角度が力線を継承する** — 殴打・斬撃などアクションの対角線が、
   コマ枠(ガター)自体の角度としてページを横断する。
2. **1.618:1(黄金比)の構成** — コマの大小配分が均等グリッドではなく φ 比。

調査の結果、`ai-gftd-mangaka/clj/src/mangaka/domain.cljc` の storyboard
スキーマには既に `:panel/rect`(正規化 `[x y w h]`)・`:panel/tilt`・
`:panel/polygon`・`:panel/bleed`・`:panel/z` という panel geometry フィールドが
**宣言だけされていて、それを計算するアルゴリズムが1つも存在しなかった**
(`chat.cljc` の LLM ツールが生の float を受け取るか、`seed.cljc` で手打ちする
だけ)。一方 `kotoba-lang/kami-mangaka-page`(別コンシューマ=`kami-app-sip`
向け、Java2D 製本DTP)には `manga-layouts.ts` 移植の `gn-templates` /
`size-weight` / `rows-of` という、コマの大小を weight で行分割する既存実装が
あり、この設計の直接の前例だった。「実際に漫画を作る」ための素材は
`ghosthacker/260123-jump/resources/manga_script.edn` の 17 ページ版
Episode 1(v3, 大門建設編)を選定 — dialogue/visual が整理された EDN で、
3〜5ページへの圧縮に適していた。

## Decision

### 1. `mangaka.layout.komawari`(新規、`ai-gftd-mangaka/clj/src/mangaka/layout/`)

Pure `.cljc`、host interop なし。

- **黄金比の重み付け**: `:beat/weight`(`:splash|:large|:medium|:small|:beat`
  またはnumber)を `φ²|φ|1|1/φ` に対応させ(`weight-of`)、行の高さ・列の幅を
  この重みの正規化比で分割する(`row-bands`/`col-bands`)。均等グリッドでは
  なく φ の指数で強弱がつく。
- **フォースライン(力線)剪断**: 1コマだけ傾けると隣接コマとの境界にギャップ
  /重なりができるため、**行全体を同じ tilt でシアーする**(`row-tilt` が
  行内の最高強度beatのvectorを採用し、`shear-polygon` が全パネル共通の
  `dx = h·tan(tilt)` で上辺だけをオフセットする平行四辺形を返す)。同じ h・
  同じ tilt を共有するので、隣接パネルの目地(ガター)は常に一定幅の平行
  チャンネルになる(単体テストで実測検証済み)。
  - tilt の上限は **1:φ 矩形自身の対角線角度**(`Math/atan(1/φ) ≈ 31.7°`)—
    「なぜ32°ではなく31.7°か」を恣意的な数値でなく同じ黄金比の幾何から導く。
- **governor(`validate-layout`)**: 生成した(または誰か他が手で書いた)
  `:page/panels` を独立に検査 — 範囲外(bleedでない限り[0,1]厳守、bleed
  panelは剪断のはみ出しを許容)・重なり(bbox overlap面積)・tilt上限超過・
  読み順(行内でx降順=右→左)。CLAUDE.md の actor 規約(intelligence node は
  proposal のみ、governor が拒否する書込みを actor は行わない)を、LLMでは
  ない決定的アルゴリズムにも同じ精神で適用した。

### 2. `mangaka.graphs.compose-komawari` + NSID登録

`ai.gftd.mangaka.composeKomawari` として `langgraph.edn`/`registry.cljc` に
登録(`manifest-matches-registry` テストで両者のlock-stepを保証)。
`mangaka.graphs.gen`(comfy txt2img前提のplan→render→persist)には乗せず、
新規に `plan → govern → render(SVG preview) → persist` の4ノードを手書き
— govern が reject した layout は `store/save-document!` に到達しない
(governorがgateする)。

### 3. `mangaka.preview` の拡張(SSoTは変えない)

`panel-svg` が `:panel/polygon` があれば `<polygon>`、無ければ従来通り
`<rect>` を描画するよう拡張。`ai-gftd-mangaka/clj/CLAUDE.md` の「bespoke
SVG/canvas renderer を storyboard の SSoT として再導入するな」という注記を
尊重し、**既存の(SSoTではない)derived-view previewを拡張しただけ**で新規
rendererは作らなかった。加えて `:visual`(ghosthacker manga_script.edn の
生台本フィールド)をイタリック体の director's note として表示 — これは
`:panel/*` schema キーではなく、この preview限りのcosmetic表示。

### 4. `kami-mangaka-page` への同一原則の移植(追加のみ、既存動作は不変)

`layout-page` の戻り値を `[panel rect]` から `[panel rect tilt]` の3要素に
拡張(既存テストは `second`/位置分解で rect だけ見るため無変更で通過)。
パネルが `:intensity`/`:vector` を持たない限り `tilt` は常に `nil` = 既存
呼び出し元とのバイト単位互換。`komawari-tilt`(ai-gftd-mangaka の
`tilt-for` と同じ折り畳み/clampロジック)、`shear-polygon`(Java2D
`Path2D$Double`)を追加し、`draw-cover`/`placeholder`/frame描画がtilt有りの
ときだけ `.setClip(Shape)`/`.draw(Shape)` に切り替わる。`tone-bg!`(背景
トーン)は今回rect-clipのまま(スコープ外、Consequences参照)。

### 5. Ghost Hacker Episode 1 — komawari再構成(5ページ)

`ghosthacker/260123-jump/resources/episodes/ep1-komawari-redesign/
episode.edn`。17ページ版 `manga_script.edn`(大門建設編)を、各ページの
`:rows`(行→beatの2次元配列、`:beat/weight`/`:beat/intensity`/
`:beat/vector` 付き)として5ページに再構成:

1. **怠惰な天才**(全calm、縦積み4行 — φ重みだけで強弱)
2. **違和感**(1行目φ列分割+差し込みコマ、3-4行目でtension開始 15°)
3. **解剖**(tension 18°→18°→22°→26°と漸増、クライマックスへの助走)
4. **斬**(核心ページ — impact 24°の共有tiltで2コマ同時剪断、最終大ゴマは
   1:φ対角線上限 30° の「ZBAAAAAN」斬撃線がそのままコマ枠になる)
5. **手数料**(tilt 0 のφグリッドへ帰還 — 緊張の解放を「コマが直立に戻る」
   ことで表現)

`ai.gftd.mangaka.composeKomawari` を実際に(mem-store経由で)5回呼び出し、
governor は5ページとも `:ok? true`(issue 0件)。生成された実座標を
`mangaka.preview` でSVG化し、Artifactとして提示した(セッションに画像生成
ツールが接続されていないため、**これは作画(線画/トーン仕上げ)を含まない
「ネーム」(コマ割り+台詞配置の設計図)である** — 後述Consequences)。

## Consequences

- (+) `ai-gftd-mangaka` に**初めて panel geometry を算出するアルゴリズム**
  ができた(従来は手打ちかLLMの生float)。governor gate 付きで、既存の
  actor/Governor 規約(CLAUDE.md)に沿う初のnon-LLM実装例になった。
- (+) 単体テスト(`komawari_test.cljc`)で「行全体のシア一貫性(ガター幅が
  上下で一定)」を数値で検証 — 見た目のレビューでは気づきにくい種類の
  バグ(1コマだけ傾けた場合のwedge状ギャップ)を構造的に排除した設計になって
  いることを保証する。
- (+) `kami-mangaka-page` 側は追加のみで、既存7テストが無変更で通過(21→28
  assertions)。2つの独立した実装が同じ数式(`Math.atan(1/φ)`上限、
  行共有shear)を共有しており、将来どちらかを直す時の参照になる。
- (−) **これは作画ではなくネーム**。今回の環境に画像生成ツール
  (ComfyUI/SDXL/Gemini等、`kami-mangaka-render`や既存`generation_prompts.edn`
  が前提とする経路)への接続がないため、パネルの中身(線画)は生成していない
  — dialogue配置とコマ形状(角度・大小)のみ実データ。
- (−) tilt はページ内の**同一行内でのみ**共有される(隣接行を跨ぐ斜線は
  対応していない)。参照画像にあった「複数段を貫く1本の対角線」は将来
  拡張の対象。
- (−) `kami-mangaka-page` の `tone-bg!`(背景トーン描画)は今回rect-clipの
  まま — 剪断されたパネルの上でトーンだけ角丸/矩形にはみ出す可能性がある
  (コマ枠自体は正しく描かれるため実害は小さい)。
- (−) chat-agentの `add_panel`/`update_panel` ツールから `composeKomawari`
  を呼べるようにする配線(CLAUDE.mdの「Adding a graph」recipe step 5)は
  見送った — ネストした行×beat配列をJSON Schemaで表現するのは相応の設計
  検討が要るため、今回はNSID登録止まり。

## Alternatives Considered

- **既存 `kami-mangaka-page` の `gn-templates`/`size-weight` をそのまま
  `ai-gftd-mangaka` にも使う**: 却下。両者はコンシューマ(mangaka.gftd.ai vs
  kami-app-sip)もデータ表現(正規化EDN datom vs Java2D percent)も異なり、
  `ai-gftd-mangaka/clj/CLAUDE.md` が明記する通り無関係な系統
  (ADR-2607022800 cluster 43)。共有すべきは**設計原理**(φ・shared-row-tilt)
  であって実装ではない — 数式を独立に2箇所へ移植する方針にした。
- **1コマだけ傾ける(独立tilt)**: 却下。単体テストで実測した通り、隣接
  コマとの境界にwedge状のギャップ/重なりが生じる。行全体を同じtiltで
  シアーする設計に修正し、この不変条件をgovernorではなくジオメトリの
  構成自体で保証した。
- **新しい bespoke SVG/canvas renderer を書く**: 却下。
  `ai-gftd-mangaka/clj/CLAUDE.md` が明示的に禁止(storyboard datoms →
  KAMI render-IR が正式path)。既存の非SSoT derived-view
  (`mangaka.preview/page->svg`)を拡張するに留めた。
- **`composeKomawari` を `mangaka.graphs.gen`(plan→render→persist)に乗せる**:
  却下。そのscaffoldはcomfy txt2img前提(`comfy/render-panel`呼び出し)で、
  本グラフは画像生成をしない純粋geometry計算のため、4ノード
  (plan→govern→render→persist)を独立に組んだ。

## Related

- `ai-gftd-mangaka/clj/CLAUDE.md`: グラフ追加のrecipe、KAMI renderのSSoT方針
- root `CLAUDE.md` Actors 節: proposal-only intelligence node ⊣ governor の
  actor discipline(本ADRのgovernor設計はこの精神をLLMでない実装に適用した
  初例)
- ADR-2606282100: kami-mangaka-page(Tier-1 mangaka)の由来、`gn-templates`の
  出典
- ADR-2607022800 (cluster 43): `kami-mangaka-*` family と mangaka.gftd.ai が
  無関係な系統であることの根拠
