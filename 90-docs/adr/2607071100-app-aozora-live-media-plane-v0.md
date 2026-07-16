---
id: adr-2607071100-app-aozora-live-media-plane-v0
title: "ADR-2607071100: app-aozora — ライブ配信 media plane v0 (HLS HTTP ingest、ADR-2606271500 の実装スライス)"
status: accepted
doc_type: adr
topic: app-aozora-live-media-plane
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - live.aozora.app (app-aozora-live Worker) の ingest/serve 契約 — PUT/DELETE /ingest/<id>/<name>、GET /s/<id>/<name>、app.aozora.live.{startBroadcast,stopBroadcast,listBroadcasts}
  - broadcast 認可モデル (INGEST_SECRET → ingestToken = HMAC-SHA256(secret, broadcastId))
  - producer 経路 — OBS→ffmpeg local HLS + watcher アップローダ (scripts/live-relay.cljs)。ffmpeg 直 HTTP PUT が Cloudflare edge に破棄される実測と回避
  - ライブ告知の書式 — 通常 post + app.aozora.embed.video {playlist, live:true}
related:
  - 90-docs/adr/2606271500-kotoba-stage-obs-live-aozora.md
  - 90-docs/adr/2607062100-app-aozora-vertical-video-feed.md
  - 90-docs/adr/2607071000-app-aozora-video-upload-r2-blob.md
supersedes: []
superseded_by: []
---

# ADR-2607071100: app-aozora — ライブ配信 media plane v0

**Status**: accepted（実装・デプロイ・E2E 検証済み 2026-07-07。app-aozora main
`0d724f6f` + `ac166061` (waitUntil) + `ed854658` (relay)）
**Deciders**: Jun Kawasaki（オーナー指示: ADR-2606271500 の media plane 残課題に着手）

## Context

ADR-2606271500 (kotoba-stage) は OBS→aozora ライブ配信を設計したが、2026-07-01
追記のとおり実装は現存せずゼロから。残課題は WHIP/ICE/DTLS-SRTP 終端 +
RTP→H264→CMAF パッケージングという大きな media plane だった。

v0 の観察: **配信に最低限必要なのは「HLS を書ける producer」と「認証付き
content store + 公開再生パス」だけ**。ffmpeg は HLS 著作の最も枯れた実装であり、
サーバは RTP を触る必要がない。

## Decision（v0 アーキテクチャ）

```
OBS ──rtmp──▶ ffmpeg (local, -c copy) ──HLS files──▶ tmpdir
                                            │ watcher (nbb, 0.7s poll)
                                            ▼ HTTP PUT (segment → playlist の順)
                     live.aozora.app (app-aozora-live Worker, cljs)
                       PUT/DELETE /ingest/<id>/<name>   Bearer ingestToken
                       GET /s/<id>/<name>               公開再生 (CORS *)
                       R2 bucket aozora-live-segments (key live/<id>/<name>)
                                            ▼
                     SPA /videos (hls.js) — 告知 post の embed {playlist, live:true}
                     を再生し LIVE バッジ (ADR-2607062100 / 2607071000 の live? 判定)
```

- **認可**: `INGEST_SECRET`（wrangler secret、Keychain service
  `aozora-live-ingest` に控え）が operator 資格。broadcast ごとの
  `ingestToken = HMAC-SHA256(INGEST_SECRET, broadcastId)` — stateless 検証、
  失効は secret rotation。
- **liveness**: ffmpeg/relay が segment ごとに playlist を再 PUT するので
  playlist の R2 uploaded 時刻が heartbeat（90s 窓）。stopBroadcast は
  `.ended` marker。listBroadcasts はこれで live/ended を返す。
- **VOD replay**: relay は hls_list_size 0（全 segment 保持）で ENDLIST 込みの
  完全 playlist を最後に PUT — 配信終了後もそのまま再生可能。
- **XRPC lexicon**: `app.aozora.live.{startBroadcast,stopBroadcast,
  listBroadcasts}` + record 予約 `app.aozora.live.broadcast`（v0 の告知は
  通常 post + embed live:true。ADR-2606271500 の getIngestTicket/getPlaylist/
  getViewerTicket からの命名変更は本 ADR が正）。

### 実測に基づく重要な設計決定（producer 側 HTTP）

ffmpeg の `-method PUT` 直接 ingest は **Cloudflare edge に破棄される**:
- ffmpeg は最終 chunk 送出直後に response を読まず socket を閉じる。raw-socket
  再現で、**完全な chunked body でも response を読まずに切断すると 404
  （非永続）、読めば 200（永続）** を確認（2026-07-07）。`ctx.waitUntil` でも
  救えない（リクエストが Worker 到達前に edge で破棄される）。
- `-http_persistent 1` は接続再利用 2 本目が `Canceled` になり以降 stall。

よって producer は **local HLS 出力 + watcher アップローダ**
（`40-engine/cljs/live/scripts/live-relay.cljs`）: segment は「playlist に参照
された時点＝完成」でアップロードし、その後に playlist を PUT（プレイヤーが
404 segment を引かない順序）。HTTP は babashka http-client（response 読取り）。

## スコープ外（follow-up — ADR-2606271500 本体）

- WHIP/ICE/DTLS-SRTP 終端（ブラウザから直接配信）・RTP→CMAF サーバ側
  パッケージング・LL-HLS partial segments。
- kotoba-stage（CLJ シーングラフ合成）。
- `:live/segment` datom 台帳 / IPFS セグメント配布（v0 は R2 のみ）。
- 視聴チケット（v0 は公開読み）。startBroadcast の CACAO 化。
- SPA からの告知 post 自動作成（v0 は relay が embed JSON を印字、手動投稿）。

## Verification (2026-07-07)

- E2E: `nbb live-relay.cljs --mode test --duration 20` → 10 segments + ENDLIST、
  公開 URL から ffprobe が h264+aac をデコード、配信中 listBroadcasts
  `live:true` → 終了後 `false`。
- auth: 誤 secret で startBroadcast 403、トークン無し PUT 403。
- tests: `aozora.live.core-test`（id/name 検証・経路 parse・liveness 窓・HMAC）
  含む 40-engine 267 tests / 1152 assertions green。
- インフラ: R2 bucket `aozora-live-segments` 作成、`INGEST_SECRET` 設定
  （Keychain 控え）、Worker `app-aozora-live` を live.aozora.app にデプロイ。
