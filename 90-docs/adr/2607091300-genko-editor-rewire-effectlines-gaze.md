# ADR-2607091300: genko エディタ再配線 — genko-embed→kami-genko 委譲 / aozora studio genko canvas / effectLines・gaze レンダラ

**Status**: accepted (implementation in progress this session)
**Date**: 2026-07-09
**Deciders**: Jun Kawasaki

## Context

mangaka.gftd.ai 退役(ADR-2607071100 wave3)後の機能監査(2026-07-09、3系統並行監査)で、
「失われた機能」と「在るのに配線が切れている機能」を棚卸しした。結論:

1. **genko-embed.ts(WebGPU ペンタブ描画エディタ、`com-junkawasaki/kami-engine-sdk`
   `src/lib/genko/genko-embed.ts`、~2500行)は完動だが孤立**。
   - ADR-2607020200 で document model + node-tree ops + oplog + serialize を
     純 cljc の `kotoba-lang/kami-genko` に切り出したのに、genko-embed の
     inline JS は **依然ローカル実装のまま**で `globalThis.KamiGenko` に一切
     委譲していない(SSoT 分裂状態)。kami-genko の消費者は kotobase-client /
     kami-webvr のみ。
   - persistence は `*.etzhayyim.com` XRPC 専用配線。aozora.app には未統合。
2. **aozora.app/studio は「genko エディタの新居」としてリダイレクトされている
   が、実体はメタデータ/テキスト編集フォーム**(`studio_editor.cljc` —
   `:gh.manga/*` tx の title/text/imageUrl 編集のみ)。描画キャンバス・コマ割り・
   フキダシ・トーンのツーリングは無い。
3. **effectLines(効果線)と gaze(視線誘導)はレンダラが存在しない**。
   `ai.gftd.mangaka.page` lexicon にフィールド定義(z-order:
   panel art → tones → effectLines → SFX → bubbles)と実データ
   (ghosthacker cut-design 46頁: effectLines 169 / gaze 279 パネル)だけがあり、
   kami-mangaka-page / text / render / genko-embed のどこにも消費コードが無い。
   bubbles / sfx / tones の3つだけが `kami-mangaka-page`(Java2D B5 bake)に
   レンダラを持つ。

なお同監査で確定した**完全消失系**(Wattpad 自動投稿 / YouTube / Sora 動画 /
TTS / 3D scene・pose・cinematography / 顔検出+Hume / LoRA 学習 / 批評リファイン
ループ / Neo4j+React Flow パイプライン)は本 ADR のスコープ外
(langgraph.edn `:backlog` と本 ADR の記録をもって現状維持。再実装は個別 ADR)。

## Decision

**「部品は在るのに配線が無い」3点を配線する。**

### 1. genko-embed → kami-genko 委譲(SSoT 修復)

- `kami-genko` の JS バンドル(`npm run build` → `dist/kami-genko.js`、
  `globalThis.KamiGenko`)を `kami-engine-sdk` に vendor
  (`src/lib/genko/vendor/kami-genko.js`、来歴コメント付き)し、genko-embed の
  emit する自己完結 HTML に inline。
- inline JS の node-tree ops(allNodes / findByNid / wouldCycle / nodeVisible /
  reorderNodes / nodeTree / set-parent 相当)・oplog(recordOp / replayOplog)・
  serialize(readDoc / writeDoc / normalize)を **KamiGenko 呼び出しに置換**。
  挙動は不変(pure refactor)。nid 生成・WebGPU 描画・DOM・persistence I/O は
  host 側に残す(ADR-2607020200 の境界どおり)。
- 挙動差が見つかった場合は **inline(=稼働実績側)の挙動を正**とし、
  kami-genko 側を直す。旧 inline 挙動を期待値に固定した round-trip smoke test
  を vendor 隣に常設。

### 2. aozora.app/studio に genko canvas(v1)

- `/studio/<slug>/genko` ルートを追加し、genko-embed の canvas を埋め込む。
- **v1 は client-side 完結**(ADR-2607071100 v1 と同哲学): localStorage
  draft 自動保存 + genko doc JSON(oplog 込み)のダウンロード/読込。
  aozora PDS / B2 への保存、`:gh.manga/*` tx との相互変換
  (`page->storyboard` 経由)は follow-up。
- etzhayyim XRPC 配線はそのまま残す(etzhayyim ホストでの既存利用を壊さない)。
  ホスト判定で persistence を出し分ける。

### 3. effectLines / gaze レンダラを kami-mangaka-page に実装

- `draw-effect-lines!`: kind = focus / explosion / flash / speed。
  中心(centerX/centerY)・密度(density)・coverage から放射/平行線を Java2D で
  描画。座標系はパネルローカル 0-1000(実データ準拠)。z-order は lexicon
  どおり tones の後・SFX の前。
- gaze は **印刷物ではなくレビュー用オーバーレイ**として実装:
  `compose-page!` のオプション `{:gaze-overlay? true}` 指定時のみ
  entry→focus→exit の赤破線矢印を描画(既定 off、非破壊)。
- 検収データ: ghosthacker cut-design
  (`mangaka-ghosthacker-assets/arc0-1-origin/2026-06-08-cut-design-rollout/data/`)。

### 実施経路(CLAUDE.md 準拠)

- 各子リポは plain git: superproject 外の worktree で実装 → session branch を
  push → **サーバサイドマージ**(`gh api .../merges`)で main 化 → west pin を
  `gen-west-manifest.cljs --entry <name>` の最小 diff で前進。
- 本セッションでは **production deploy(wrangler deploy)はしない**。
  aozora.app への反映はオーナーの deploy 判断に委ねる。

## Consequences

- genko document model の SSoT が実際に単一化され、cljc 側のテストが
  genko-embed の挙動を守るようになる。
- aozora.app/studio が「メタ編集フォーム」から「描けるエディタ(v1)」になり、
  mangaka.gftd.ai リダイレクトの看板と実体が一致に近づく。
- effectLines / gaze が初めて印刷経路(page bake)とレビュー経路で可視化される。
  ghosthacker arc0-1 の 169+279 パネル分の演出データが死蔵から復帰。
- 残る未配線(kami-mangaka-page/text を実行アプリに配線、manga-viewer と
  kami-mangaka-reader の二重化解消、genko doc ⇄ `:gh.manga/*` tx 変換)は
  follow-up として明示的に残る。
