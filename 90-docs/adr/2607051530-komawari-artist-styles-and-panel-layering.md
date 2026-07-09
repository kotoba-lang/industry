# ADR-2607051530: komawari artist-style catalog(toriyama/togashi)+ panel layering

**Status**: accepted (implemented, merged to gftdcojp/ai-gftd-mangaka main — see :artifacts in the companion .edn for the SHA/PR#)
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/ai-gftd-mangaka`
**Related**: ADR-2607051520(同日、komawariの初出 — force-line shear + φ比のみ実装)

## Context

ADR-2607051520 は「鳥山明のコマ割り」1例だけを実装したものだった。オーナーから
続けて (1) それを "toriyama layout" として名前を付けて明示的に一般化すること、
(2) 新しい参照コマ2枚(HUNTER×HUNTER 王位継承戦編、冨樫義博)を分析し
"togashi layout" として同様に整理すること、(3) 吹き出しの形・文字の薄さ濃さ・
セリフがコマ/吹き出しに収まる/収まらないパターンといった観察を EDN に分類
すること、(4) 1コマを1枚絵でなくキャラクター/背景ごとのレイヤー重ねとして
設計することの4点を指示された。

新しい2枚のHxH参照コマを分析した結果、判明したのは **鳥山と冨樫が同じ効果
(劇的な緊張の可視化)を正反対の仕組みで作っている**ということだった:
鳥山はコマの**枠自体を曲げる**(ADR-2607051520のforce-line shear)。冨樫は
**コマを直角のまま保ち**、①小さな反応コマが下の大ゴマの境界を越えて重なる
panel-in-panel(スラッカの挙手割り込み)、②頭部が上コマ枠にほぼ接する
tight-crop(ガトーの威圧感)、③隣接コマ間での描き込み密度の急変(薄い
シルエット→密な放射集中線)、④大人数キャストを速攻導入する名札の多用、
という4つの別の技法で緊張を作っていた — この2枚には診断した限り
force-line的な斜めコマは1つも無かった(当初の想定=「togashiはもっと過激に
傾ける」は誤りで、証拠から書き直した)。

さらに調査で判明したのは、吹き出しの形/文字の薄さ濃さ/セリフ種別という
(3)の観察対象は、**このリポジトリに既に存在する** ということだった:
`kotoba-lang/kami-engine/kami-mangaka-expression-clj`
(`kami.mangaka.expression` + `resources/mangaka_expression_patterns.edn`)
が、まさに同じHUNTER×HUNTER王位継承戦編のコマ観察から `:register`
(speech/shout/whisper/thought/monologue/narration/chatter/nameplate/sfx)・
`:bubble`(oval/jagged/cloud/square/wavy/spike/burst)・`:weight`
(faint→heavy)・`:font-role`・`:tone`・`:archetypes` を既に整備していた
(`:observations` は `:election-guards`/`:slakka-interrupt` として今回と
同じコマを既に引用)。この既存資産を重複させず、新規のカタログは
**幾何(コマ形状・レイヤー配置)側だけ**を担当することにした。

## Decision

### 1. `mangaka.layout.komawari` に named style を追加

`styles`(`{:toriyama {...} :togashi {...}}`)+ `default-style`。各styleは
`:tilt-enabled?`(force-line shearを許すか)と `:frame-break?`(panel-in-panel
insetを許すか)の2フラグだけを持つ:

- `:toriyama` — `:tilt-enabled? true :frame-break? false`。ADR-2607051520の
  挙動そのまま。
- `:togashi` — `:tilt-enabled? false :frame-break? true`。tiltは
  `:beat/intensity :impact` でも常にnil(rectilinear維持)。

`row-tilt`(`[row style]`アリティ追加)と `propose-page-layout` の
`:beat/inset` 処理の両方をこの2フラグでgateする — 実装中、`:beat/inset` が
style を見ずに常時適用されていた(styleカタログの宣言と矛盾する実バグ)のを
発見し修正した。`mangaka.graphs.compose-komawari` の `plan` も同様に
`:style` を素通ししていなかったバグを発見・修正(グラフ経由のテストで
`:style :togashi` を渡すまで気づかなかった — 実行して検証することの効能)。

### 2. panel-in-panel frame-break(`apply-inset`)

`:beat/inset`(bool または `{:scale :overlap}`)→ ビートの矩形をセルの
末尾側(reading-first)へ縮小し、高さを `:overlap` 分だけ行の外(下の行)へ
伸長 — スラッカの挙手コマが集団コマに重なる技法の直接実装。結果の panel は
`:panel/tags ["frame-break"]` を持ち、`validate-layout` はこのタグを持つ
panelが関わる重なりを **意図的なものとして許容**する(通常の重なりは
従来通りerror)。

### 3. `mangaka.domain` に `:panel/layers` component を追加

`:panel/bubbles`/`:panel/sfx` と全く同じパターン(schema entry、
`layer-keys`、`layer->tx`、`doc-pull`、`pulled->panel`、
`valid-panel!`の構造チェック、`export-lexicon`)で追加。`:layer/kind`
(:background/:midground/:character/:effect/:inset/:text)・`:layer/ref`・
`:layer/rect`(**意図的に bounds-check しない** — `:layer/bleed` な
キャラクターレイヤーがパネル自身の矩形をはみ出すのはTogashiのframe-break
技法そのもの)・`:layer/z`・`:layer/bleed`・`:layer/opacity`・`:layer/tone`・
`:layer/tags`。

### 4. `mangaka.preview` がレイヤーを描画

`:panel/layers` を `:layer/z` 順に半透明矩形として、パネル自身の白地の上に
描画(`layer-fill` で kind ごとに色分け、`:layer/bleed` なレイヤーは赤い
破線でオーバーフローを可視化)。

### 5. `resources/komawari_styles.edn`

toriyama/togashiの分析を引用付きで記録するカタログ(`:source`/
`:observations`、既存 `mangaka_expression_patterns.edn` と同じ形式)。
吹き出し/文字/トーンの語彙は再定義せず `:expression-crossref` で
`kami.mangaka.expression` を参照するだけに留めた。

### 6. 実証デモ

「スラッカの挙手割り込み→集団のリアクション」という**同一のビート内容**を
`ai.gftd.mangaka.composeKomawari` に `:style :toriyama` / `:style :togashi`
それぞれで実行し、governor両方 `:ok? true`(0 issues)。同じ入力から
幾何的に異なる出力(togashi: panel-in-panel + レイヤーbleed / toriyama:
force-line shear)が実際に生成されることを実装コードで検証した。

## Consequences

- (+) 「1例をtoriyama、次の観察をtogashiとして」という指示通り、2つの
  実在する技法を、名前付きの相互排他フラグ(2個のbool)で表現できた —
  過剰なパラメータ空間を持たない。
- (+) 実装中に2つの実バグ(`:beat/inset`がstyle非依存だった、
  `compose-komawari`が`:style`を素通ししていなかった)を、グラフ経由の
  end-to-endテストを書いて初めて発見した — 純関数の単体テストだけでは
  見えない類の統合バグ。
- (+) `kami.mangaka.expression` との重複を避けた(既存資産を尊重し、
  cross-referenceのみ)。
- (−) `resources/komawari_styles.edn` は現状「コードの`styles`マップが
  真実、EDNは引用付き分析ドキュメント」という関係で、EDNから直接ロードする
  実装にはなっていない(ADR-2607051520の kami-mangaka-page 移植と同じ
  "duplicated math, not shared dep" の位置づけ)。将来、styleパラメータが
  増えたら EDN を直接読むローダに切り替える価値がある。
- (−) レイヤーの重なり順(z)・bleedの妥当性は `mangaka.preview` が視覚化
  するのみで、`validate-layout` はレイヤー同士の重なりを検査しない(パネル
  同士の重なりだけを見る) — レイヤーレベルのgovernorチェックは未実装。
- (−) 2枚の参照コマの台詞は画像からの読み取りであり、OCR的な完全性は
  主張しない(構造・幾何の観察が本ADRの主眼)。

## Alternatives Considered

- **:togashi も :toriyama と同じくforce-line shearを使うが、より過激な
  角度にする**: 却下。実際の参照コマにforce-line shearは1つも見当たらず、
  証拠に反する。代わりにpanel-in-panel/tight-crop/density-contrastという
  別の技法として実装した。
- **吹き出し/文字の薄さ濃さもこのADRで再実装する**: 却下。
  `kami-mangaka-expression-clj` が同じ出典から既に整備済み — 車輪の
  再発明を避け、cross-referenceに留めた。
- **レイヤーの重なりを厳密にgovernorで検証する**: 見送り(スコープ外)。
  レイヤーのbleedは意図的な技法であり、現時点では視覚化(preview)だけで
  十分と判断。必要になれば `validate-layout` にレイヤー版のoverlapチェックを
  追加する。

## Related

- ADR-2607051520: komawari(コマ割り)設計の初出(force-line shear + φ比)
- `orgs/kotoba-lang/kami-engine/kami-mangaka-expression-clj/resources/
  mangaka_expression_patterns.edn`: 吹き出し/文字/トーン語彙のSSoT
  (本ADRが重複させず参照する既存資産)
