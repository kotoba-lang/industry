---
id: adr-2607166600-kotobase-ipld-materialized-views-ivm
title: "ADR-2607166600: kotobase IPLD materialized views — RisingWave 型 IVM をチェーン state に載せ、フィードを 1 リクエスト読みにする（KV 非依存・分散対応）"
status: accepted
doc_type: adr
topic: kotobase-ipld-materialized-views-ivm
authoritative: true
last_verified: 2026-07-16
authoritative_for:
  - kotobase の materialized view 層（view spec / fold 時 materialize / チェーン state の "views" Link / datomic.view エンドポイント / kc/view クライアント）
  - aozora yoro-feed view の spec 共有（aozora.appview.feed-view が read 側と fold cron の単一正本）
  - ADR-2607166000 の Follow-up「kotobase 側 multi-attr 一括 API」の解決形（view はその上位互換）
related:
  - 90-docs/adr/2607166000-app-aozora-appview-feed-latency-kaizen.md
  - 90-docs/adr/2607164500-ai-gftd-dougaka-kodomo-toddler-song-pipeline.md
supersedes: []
superseded_by: []
---

# ADR-2607166600: kotobase IPLD materialized views（RisingWave 型 IVM）

**Status**: accepted, implemented, deployed（kotobase-cljc-worker `20dccacb`、pds `be84d049`、appview `c6d7cfe6`）
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki（指示: 「残課題を解決, KV に依存せずに, kotobase の ipld で分散型でも効率的に query したい。risingwave などを参考に」）

## Context

ADR-2607166000 の残課題: フィードの cache-miss 経路が ~8 秒（attr 単位 narrow scan 19 リクエスト × kotobase リクエスト単価 ~1.2s ÷ 並列上限 6）。KV 置き換え案は Cloudflare 中央キャッシュへの依存を増やすためオーナーが否認。kotobase は IPLD（dag-cbor / CIDv1 / R2+IPFS pin）上の append-only チェーン + prolly-tree 索引スナップショットという分散に適した構造を既に持つ — 足りないのは「よく引く射影を 1 ブロックで引ける形」だけ。

**RisingWave → kotobase の概念対応**（採用した設計の骨子）:

| RisingWave | kotobase |
|---|---|
| ストリーム（changelog） | novelty tx ブロック（append-only） |
| バリア / チェックポイント | `fold!`（novelty を索引スナップショットへ圧縮） |
| 共有ストレージ上の MV state | **views ブロック**（dag-cbor、AEAD 暗号化、content-addressed） |
| MV カタログ | チェーン state の `"views"` Link（spec + rows を同梱） |
| serving は MV を読むだけ | `datomic.view` = state 読み → views ブロック 1 読み |
| 常に fresh | 読み時に未 fold novelty を view 述語でマージ（hot-datoms と同じ hot/cold 分割） |

**分散性（KV との本質差)**: view は CID でチェーン head から到達可能な普通の IPLD ブロック。チェーンを sync した**どのピアも**同一 view を検証可能に 1 ブロック読みで提供できる。中央キャッシュ・別系統のインフラを一切増やさない。

## Decision

1. **kotobase-peer**（`core.cljc`）: view spec = `{"attrs" [attr …]}`（宣言的・純データ。
   将来の述語拡張はこの map に足す）。`fold!` が merged db（fold は元々 full hydrate
   する — 追加 IO ゼロ）から各 view の rows `[[e a v_edn] …]` を導出し、
   `{"ct" AEAD({"views" {name {"spec" .. "rows" ..}}})}` を 1 ブロック書き、
   state に `"views"` Link を載せる。**spec は carry-forward**（views 引数なしの
   fold でも保存済み spec で再 materialize。nil spec で除去）。transact 側の
   `commit!` は state を merge で継ぐため views は自動温存。読みは新規公開 fn
   `view-rows`: 保存 rows + 未 fold novelty（retraction は打ち消し、assert は
   spec フィルタで追加）— **常に最新**。
2. **kotobase-cljc-worker**: `datomic.fold` に `views_edn`（EDN 文字列）、新規
   `datomic.view`（read 経路、`{:graph :view}` → do-datoms 互換の `:datoms` rows +
   `:spec`。未宣言は `ViewNotFound`）。
3. **kotobase-client**: `kc/view`（datoms と同型の read、retry/read-CACAO）、
   `kc/fold` に `:views` opt。
4. **app-aozora**: spec の単一正本 `aozora.appview.feed-view`（read 側 feed.cljc と
   fold cron の双方が require — attr セットの drift を構造的に防ぐ)。
   `scan-posts` は view を先に読み、`ViewNotFound`/旧 worker では ADR-2607166000 の
   attr-scan wave に**フォールバック**（デプロイ順に対して両方向安全)。PDS の
   5 分毎 fold cron が `:views` を毎回 upsert 宣言。

## 実測

- テスト: kotobase-peer 103 tests（+ IVM 3 本: materialize / novelty 鮮度 /
  retraction 打ち消し / carry-forward / nil-spec 除去）、kotobase-cljc-worker
  38 tests（cljs 経路の fold+view E2E）、app-aozora 406 tests（view 優先 +
  フォールバック）— すべて green。
- レイテンシ: デプロイ後の実測は ledger（`90-docs/perf/appview-latency-ledger.edn`）
  に追記。期待値: フィード cache-miss ~8s → **~1〜2s**（kotobase 2 リクエスト:
  head/state + views ブロック。エッジキャッシュ hit ~0.1s は不変)。

## Consequences

- (+) 残課題だった miss 経路が「リクエスト数 × 単価」の積から解放され、KV なしで
  グラフ成長に対して O(view) で安定。ピア分散でも同じ最適化がそのまま効く。
- (+) fold が元々払っている full hydrate に相乗りするため、fold の追加コストは
  rows のフィルタ + 1 ブロック書きのみ。
- (−) view の鮮度は「fold チェックポイント + novelty マージ」で常に正しいが、
  **novelty が肥大している間は novelty 読みが依然比例コスト**（fold cron が
  健全である前提は従来と同じ）。
- (−) rows は 1 ブロック内包 — view が数万行に育ったら chunked rows（prolly-tree
  化）が必要（spec が rows と同梱なので後方互換に移行可能）。
- (−) zero-novelty のグラフに対する views_edn 宣言は現状 no-op（do-fold の早期
  return）— 書き込みが流れている運用グラフでは実害なし、Follow-up に明記。
- (−) Phase 1 の view 導出は fold の merged db からの全量フィルタ（fold 自体が
  full-hydrate な現状に追随）。fold が incremental 化したら O(Δ) デルタ維持へ
  （保存形は対応済み）。

## Follow-ups

- fold の incremental 化に合わせた O(Δ) view 維持（真の RisingWave IVM）。
- chunked view rows（prolly-tree）と view の cursor/limit サーバ側適用。
- zero-novelty グラフへの views 宣言（fold の早期 return の緩和 or 専用 op）。
- kotobase リクエスト単価 ~1.2s 自体の削減（state/head 読みの R2 ラウンド数）。
- 他のホットな読み（getAuthorFeed / getProfile 群）の view 化判断。
