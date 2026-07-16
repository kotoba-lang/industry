---
id: adr-2607167000-kotobase-block-cache-hot-read-followups
title: "ADR-2607167000: kotobase 不変ブロックキャッシュ + ホット読みの follow-up 裁定（単価の支配項は appview→kotobase の逐次 HTTP 往復）"
status: accepted
doc_type: adr
topic: kotobase-block-cache-hot-read-followups
authoritative: true
last_verified: 2026-07-16
authoritative_for:
  - kotobase-cljc-worker の不変ブロック isolate メモリキャッシュ（CID ブロックのみ、head は毎回 R2、64MB FIFO byte budget、nil 非キャッシュ）
  - ADR-2607166600 Follow-up 4 件の裁定（実測に基づく実施/延期と延期トリガ）
  - appview エッジキャッシュ対象の拡張（getPostThread / getAuthorFeed / getProfile、60s TTL）
related:
  - 90-docs/adr/2607166600-kotobase-ipld-materialized-views-ivm.md
  - 90-docs/adr/2607166000-app-aozora-appview-feed-latency-kaizen.md
supersedes: []
superseded_by: []
---

# ADR-2607167000: 不変ブロックキャッシュ + ホット読み follow-up 裁定

**Status**: accepted, implemented, deployed（kotobase-cljc-worker `1f07596`=PR#10 / app-aozora `01bb2e5`=PR#83）
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki（指示: ADR-2607166600 の Follow-up 4 件の実施）

## Context / 計測で判明した事実

ADR-2607166600 の残 follow-up のうち「kotobase リクエスト単価 ~1.2s の削減」を
本丸として着手。**CID ブロックは不変 = どこでキャッシュしても意味論不変**（IPFS
がキャッシュを許す根拠と同じ）という性質から、isolate メモリの不変ブロック
キャッシュを実装・デプロイして before/after を実測した結果:

- **feed view miss: 変化なし（~0.6s）** — view 経路は「head 読み(mutable、毎回 R2)
  + view ブロック 1 読み」で、すでにブロック読みが支配項ではない。
- **getPostThread / getAuthorFeed: 2〜3s のまま高分散** — 支配項は
  **appview→kotobase の逐次 HTTP 往復そのもの**（1 往復 ~0.4〜0.6s: Worker 起動 +
  TLS + head R2 読み。× 呼び出し回数 4〜6）。ブロックキャッシュでは消えない。
- ブロックキャッシュ自体は R2 読み量の削減・attr-scan fallback（19 呼び出し）や
  fold の下支えとして有効なので採用のまま。

## Decision（Follow-up 4 件の裁定）

1. **リクエスト単価の削減** — 2 段で実施:
   (a) 不変ブロック isolate キャッシュ（本 ADR、実装済み。head/heads キーは
   絶対にキャッシュしない）。(b) 実測で判明した真の支配項（往復回数×往復単価）
   への対処として、**公開・非パーソナライズのホット読み（getPostThread /
   getAuthorFeed / getProfile）を appview エッジキャッシュ対象に追加**（60s TTL）
   → 繰り返し閲覧 ~0.1s。往復単価そのもの（Worker 起動 + head 読み）は
   Cloudflare 基盤コストで、残る削減余地は「呼び出し回数の圧縮」= 将来の
   compound read（1 POST で複数 index 読みをバッチ）— follow-up として残す。
2. **他ホット読みの view 化（#4）** — **不採用**。per-author / per-thread view は
   カーディナリティが無限で「名前付き少数 view + 1 ブロック」という現設計に
   合わない。実測上もエッジキャッシュ + 将来の compound read が同じ体感を
   より安く達成する。フィード（少数・全員共通）だけが view の正しい適用対象。
3. **O(Δ) view 維持（#1）— 延期**。fold は現状 full-hydrate であり、view 導出は
   その merged db からの無料フィルタ。デルタ維持を今入れても速くならず正しさ
   リスクだけ増える。**トリガ: fold 自体の incremental 化に着手した時**（保存形
   は spec+rows 同梱で移行可能）。
4. **view rows の chunked 化（#2）— 延期**。現 view は数百行・1 ブロック。
   **トリガ: rows > ~5,000 または views ブロック > ~2MB**（feed rows には record
   json 全文が乗るため、投稿数がそのオーダーに達したら prolly-tree 化）。

## 実測（ledger iteration 3）

| 経路 | 値 |
|---|---|
| getVideoFeed p50 / miss p95 | **18ms / 0.9s** |
| getPostThread / getAuthorFeed（60s 内の繰り返し） | **~0.11s**（cf-cache HIT） |
| 同（初回・TTL 跨ぎ） | 1.4〜3.3s（往復回数支配、compound read 対象） |

## Consequences / 注意

- (+) R2 読み量は不変ブロック分だけ恒久減（isolate 温存中）。コスト・レート限界の余裕。
- (−) zone cache 経由の HIT はブラウザ向け max-age が zone 設定で 4h に
  書き換わるのを観測（`max-age=14400`、worker 刻印は 15s）。thread の
  ブラウザ内 4h 陳腐化は現閲覧量では許容し、**zone の Browser Cache TTL を
  "Respect Existing Headers" へ変更するのを follow-up** とする（zone 設定変更の
  ため要オーナー確認 — 全 aozora.app 配下のキャッシュ挙動に波及）。
- (−) 初回 read の往復単価は残る。次の一手は kotobase の compound read
  （1 リクエストに複数 index 読みを同梱、getPostThread が 4〜6 往復 → 1 往復）。

## Follow-ups

- kotobase compound read（batched index reads、thread/author の初回 ~2.5s → ~0.6s 見込み）。
- zone Browser Cache TTL → Respect Existing Headers（オーナー確認の上で）。
- （継承）O(Δ) view 維持 / chunked rows — 上記トリガ到達時。
