---
id: adr-2607071000-app-aozora-video-upload-r2-blob
title: "ADR-2607071000: app-aozora — 動画アップロード経路 (PDS R2 blob store + composer 添付 + appview getVideoFeed)"
status: accepted
doc_type: adr
topic: app-aozora-video-upload
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - PDS uploadBlob/getBlob の byte store (R2 bucket aozora-pds-blobs, binding BLOBS, key blob/<cid>) と 100MB 上限
  - cljs SPA post-composer の動画添付 → app.aozora.embed.video record embed の書式
  - appview com.etzhayyim.yoro.feed.getVideoFeed (server-side video filter) の契約
  - SPA /videos の appview 成功・0件時の public チェーン degrade 規則
related:
  - 90-docs/adr/2607062100-app-aozora-vertical-video-feed.md
  - 90-docs/adr/2607071100-app-aozora-live-media-plane-v0.md
supersedes: []
superseded_by: []
---

# ADR-2607071000: app-aozora — 動画アップロード経路

**Status**: accepted（実装・デプロイ済み 2026-07-07。appview `653ea254` / pds+spa `c66c8d70` として app-aozora main 着地）
**Deciders**: Jun Kawasaki（オーナー指示: ADR-2607062100 の follow-up 3 点を実装）

## Context

- ADR-2607062100 が `/videos` 縦動画フィードを実装したが、(1) appview 側
  `getVideoFeed` は未実装（チェーン先頭が常に fail）、(2) SPA から動画を投稿する
  手段が無い、(3) **PDS の uploadBlob は bytes をどこにも保存していなかった**
  （kotobase PSA /pins は best-effort メタデータのみ、「gateway 到達可能と仮定」
  という documented TODO — 実際には getBlob は常に失敗する壊れた経路）。
- アカウントには legacy `aozora-app` SSR Worker が bind していた R2 bucket
  `aozora-pds-blobs` が既存。

## Decision

1. **PDS blob store = R2**。`app-aozora-pds` Worker に binding `BLOBS`
   （bucket `aozora-pds-blobs`、key `blob/<cid>` — legacy キーと prefix で分離）。
   uploadBlob が bytes を R2 に PUT（失敗は 500 — 保存されていない ref を返す
   嘘を止める）、PSA pin は従来どおり best-effort。getBlob は R2-first +
   IPFS gateway fallback（R2 以前の CID 用）。上限 100MB（413）。
2. **appview getVideoFeed**。timeline を「record が video embed を持つ post」に
   filter（`app.aozora.embed.video` / `app.bsky.embed.video` /
   recordWithMedia+video）。record は JSON.parse 済み JS object なので
   goog.object アクセス（:advanced 安全）。limit は filter 後に適用。
   lexicon `com.etzhayyim.yoro.feed.getVideoFeed`。
3. **composer 動画添付**。interop に raw-body `at-upload-blob`（xrpc-fetch は
   JSON 専用のため別経路）+ `:atproto/upload-blob` fx。composer は video/*
   1 ファイル（100MB クライアント側ガード）を投稿時に uploadBlob →
   `{:$type "app.aozora.embed.video" :src <getBlob URL> :video <blob ref>}` を
   record embed に積んで createRecord。File は app-db に積まず component ratom。
4. **SPA degrade 修正**: appview getVideoFeed が「成功・0 件・cursor 無し」
   （deployed-but-dry）の場合、失敗時と同様に public チェーンへ degrade —
   graph に動画 post が無い間の false empty state を防ぐ。
5. `live?` は record `$type` prefix に加えて **embed `:live true`** も見る
   （ADR-2607071100 の告知 post 連携）。

## Verification (2026-07-07)

- 本番 PDS で 100KB blob の uploadBlob → getBlob 往復、bytes 一致
  （content-type video/mp4、immutable cache）。
- appview getVideoFeed 200（graph に動画無しのため空配列 — 正）。
- /videos 本番: appview 空 → thevids 500 → What's Hot degrade で動画描画。
- tests: 40-engine 259 (R2 blob 3 件追加) + SPA 47 green。
- デプロイ: app-aozora-pds `d79edc06` / app-aozora-appview `7858e5de` /
  app-aozora-spa `efad26cf`。

## Notes / follow-ups

- uploadBlob の認可はデプロイ時点で bearer 素通し（PSA 転送のみ）。
  PDS_REQUIRE_AUTH との整合（session 検証の強制）は follow-up。
- kotobase blob-ingest（R2 → IPFS 本 push）が繋がれば gateway fallback が
  実体を持つ。それまで R2 が唯一の byte 真実。
- 画像添付（app.bsky.embed.images）は同じ uploadBlob 経路で載るが未実装。
