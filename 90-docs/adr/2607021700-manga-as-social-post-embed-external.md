# ADR-2607021700: manga を social post の一形態に（embed.external）+ パネル画像パイプライン + kotobase transact 障害記録

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

`https://aozora.app/manga/ghosthacker` はパネル画像が表示されず（viewer が
画像パスの文字列を表示する実装・画像の配信先も未配線）、またオーナー提案として
「manga などは atproto の embed url で social post の形態の一つとして整理
できないか」があった（2026-07-02）。

調査で判明した事実:
- パネル画像の実体は ghosthacker repo（`260123-jump/resources/images/...`）の
  フル解像度 PNG **248 枚 / 445MB** — git コミットも raw 配信も不適
  （CLAUDE.md 大容量バイナリ規約）。
- 投稿の embed は PDS の record→datom 変換（`aozora.pds.encode/post-entity`）が
  text 系フィールドのみ保存するため round-trip で消える。ただし読出側
  （`aozora.appview.scan/post-row`）は **`:yoro.post/record`（フル record JSON）
  があればそれを優先**する設計が既にあり、kotobase グラフには同形式の既存 datom
  も存在（2026-06-23）— 書込側の 1 箇所が欠けているだけだった。
- SPA の XRPC 書込はデフォルト 5 秒 AbortController で切られており、kotobase
  transact（実測 5〜10 秒）を伴う投稿は**全て client 側 abort**していた
  （サーバ側は完了 = 「投稿失敗表示なのに record は存在」の原因）。

## Decision — manga は「canonical URL + app.bsky.embed.external」で流通する

1. **画像パイプライン**: `scripts/sync-manga-images.bb` が tx EDN の参照
   パネルのみを ImageMagick でリーダー幅 WebP（1100px / q82、445MB→**21MB**）に
   変換し `public/images/`（gitignored deploy 資産、public/js と同扱い）へ。
   viewer は `.png`→`.webp` の拡張子スワップで `<img loading=lazy>` 描画。
   ⚠ Workers assets は `./public` のミラー — **本スクリプト未実行の deploy は
   manga 画像を消す**（package.json `deploy` script が生成→build→deploy を直列化）。
2. **共有 = 投稿**: manga ページ「共有して投稿」→ composer を本文 prefill +
   `app.bsky.embed.external {uri,title,description}` 付きで開く（uri =
   `https://aozora.app/manga/<slug>` が canonical）。composer は embed チップを
   表示（削除可）し、createRecord record に `:embed` を添付。
3. **feed 描画**: post-card が `embed.external` をリンクカード描画。aozora.app
   内部 URL は SPA ルータ遷移（`/manga/*` は MANGA ラベル）、外部 URL は新規タブ。
   `yoro-ui.kotoba.feed/row->post` は `:yoro.post/record` を優先。
4. **書込 timeout**: `at-procedure` は 30 秒（読出は 5 秒のまま）。
5. **PDS 側の embed 永続化**（`post-entity` に `:yoro.post/record` 追加、
   kotobase-test 224/981 green）は **branch `pds-embed-record` に退避、未デプロイ**
   — 下記障害の解消が前提条件。

着地: app-aozora main `93edcdb`、SPA deploy Version `10e8306b`（本番で画像表示
確認済み）。deps は package.json + lockfile をコミット（**@noble/curves は v1
pin** — repo_signer 等が v2 で削除された `secp256k1.utils.randomPrivateKey` に依存）。

## Incident — kotobase transact "Invalid array buffer length"（未解決・別系統）

2026-07-02 15:14〜15:19 JST の間から、**kotobase-cf-wasm
（kotobase.aozora.app）が投稿系 tx の transact に 500
`{"ok":false,"error":"Invalid array buffer length"}` を返す**ようになった。

- アカウント作成系 tx は通る。読出（datoms）は正常。グラフ dump（10.4k datoms）
  に異常値なし。
- **PDS のどのビルドでも再現**（6/30 稼働版・新規ビルド・encode 変更有無すべて）:
  A/B デプロイ + `wrangler rollback` ×2 + fresh/established アカウント両方の
  probe で切り分け済み。kotobase-cf-wasm は 6/24 から未デプロイ →
  **グラフ状態/サイズ依存の kotobase 側バグ**。
- 影響: aozora.app の新規投稿が現在全滅（本変更とは独立に発生）。
- 再現手段: 使い捨て did:key で CACAO→createAccount→createRecord する node
  スクリプト（session scratchpad `pds-probe.mjs`、要移植）。
- Follow-up: net-kotobase（kotobase-cf-wasm）の transact/index 経路の調査。
  解消後に `pds-embed-record` を deploy → fresh アカウントの投稿 E2E で embed
  round-trip 確認 → merge。

## Consequences

- (+) manga が読める（画像実表示・21MB 資産・lazy load）。共有→投稿→feed
  カードの UI 一式と canonical URL 規約が整い、「作品 = URL + external embed」
  という atproto 標準形（Bluesky 等の外部クライアントとも互換の形）に乗った。
- (+) 全 UI 投稿を殺していた 5 秒 timeout バグの修復。deps 再現性
  （lockfile + noble v1 pin）。
- (−) embed の server 永続化は kotobase 障害待ち（それまで共有投稿の embed は
  record 再構成で落ち、テキスト内 URL のみ）。
- (−) OG メタタグ（外部クライアントの unfurl 用）は assets-only worker では
  per-URL 差し込み不可 — 小さな worker script が follow-up。
- (−) 並行セッションの manifest regen による **pin 巻き戻し**を今日も踏んだ
  （88b365c への silent revert、7ce5183 と同型）。API single-entry 前進側でも
  「旧 SHA の出現数 = 1 検証」を厳守しないと no-op commit になる（実例:
  fa2dde24）。修復は 8ee08eb。
