---
id: adr-2607166000-app-aozora-appview-feed-latency-kaizen
title: "ADR-2607166000: app-aozora appview フィード読み系の latency kaizen — attr 単位 narrow scan + edge cache + cron warmer（p50 14.1s → 21ms）"
status: accepted
doc_type: adr
topic: app-aozora-appview-feed-latency-kaizen
authoritative: true
last_verified: 2026-07-16
authoritative_for:
  - appview.aozora.app のフィード読み系（getTimeline / getVideoFeed / getDiscoverFeed / getRankedFeed）の読み取り戦略（attr 単位 :avet prefix scan の一括 wave。entity 単位 fan-out の禁忌）
  - appview の edge cache 方針（公開・非パーソナライズ feed の Cache API s-maxage=60 + 毎分 cron warmer）
  - appview 読み系のレイテンシ計測手順（70-tools/appview-latency-probe.cljs + 90-docs/perf/appview-latency-ledger.edn、append-only）
related:
  - 90-docs/adr/2607164500-ai-gftd-dougaka-kodomo-toddler-song-pipeline.md
supersedes: []
superseded_by: []
---

# ADR-2607166000: appview フィード読み系の latency kaizen

**Status**: accepted, implemented, deployed（app-aozora `361752b6c64`、worker Version `83977bac`）
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki（指示: 「この query 速度など kaizen するには?」→ 提案承認「do it」）

## Context（実測ベース）

- `aozora.app/videos` の初回表示が約 16 秒かかっていた（ai-gftd-dougaka-kodomo の
  Phase A 投稿を実機確認した際に発覚、ADR-2607164500）。
- 分解計測: `getVideoFeed`/`getTimeline` は**毎回** 13.6〜18.2 秒（コールドスタート
  ではない）。原因は `feed.cljc` の `scan-yoro` — kotobase の `:eavt` **全走査**で
  グラフ全体を再水和してから Worker 内でフィルタする実装（コード自身が
  anti-pattern と自認するコメントあり。全走査は isolate メモリ上限 near で
  約 1/4 失敗する既知問題も kotobase-client に記載）。
- 同ファイルの narrow read 群（`getPostThread` 実測 2.1〜2.5s、`getAuthorFeed`
  2.3s）は既に `:avet` components で絞る実装に移行済みで、フィード系だけ未適用。
- **第一案（entity 単位 narrow 化）は実測で棄却**: index scan → post entity ごと
  hydrate → author ごと profile lookup、という getPostThread 型の一般化を実装・
  デプロイしたところ 14〜16.8 秒のままだった。kotobase 1 リクエスト ~1.2 秒 ×
  Workers の**同時 subrequest 上限 6** で、23 post + authors で計 ~45 リクエスト
  が直列化するため。教訓: この substrate ではリクエスト単価が支配的で、
  **fetch の単位は entity ではなく attribute にする**。

## Decision

1. **attr 単位 narrow scan（`scan-posts`）**: フィード投影が読む属性
   （generic `:atproto.record/{uri,collection,did,cid,json,deleted}`
   （per-actor cutover の書き込み形、ADR-2607032300）+ `:yoro.profile/*` +
   `:atproto.handle/*` + `:atproto.account/*`、計 18 属性）それぞれを
   `:avet [attr]` prefix scan し、依存なしの単一 `Promise.all`（~19 リクエスト
   ≈ 6 並列で 3 波）で取得 → 既存 `scan/scan` に流す。legacy `:yoro.post/*`
   行は id-scan 1 本 + **件数がある時だけ** entity hydrate（yoro-social-v2 では
   ゼロ件。テスト fixture と旧 db の互換のため温存）。
2. **edge cache（Cache API）**: 公開・非パーソナライズの 4 NSID
   （getTimeline / getDiscoverFeed / getRankedFeed / getVideoFeed）の GET を
   `caches.default` で `s-maxage=60, max-age=15`。URL（query 含む）がキャッシュ
   キー。200 のみ保存。workers.dev では Cache API が no-op なので対象は
   custom domain のみ。
3. **cron warmer（毎分）**: scheduled handler が SPA の実 URL 2 本
   （getVideoFeed?limit=30 / getTimeline?limit=30）を **in-process で router
   実行**して cache.put（Worker は自ホスト名を fetch できないため）。実利用者は
   ほぼ常に ~0.1s の hit 側に乗る。
4. **決定論 fitness（計測ループ）**: `70-tools/appview-latency-probe.cljs`（nbb）
   が p50/p95/min/max + cf-cache 内訳を `90-docs/perf/appview-latency-ledger.edn`
   に append-only 記録（BMC canvas-ledger と同じ規約）。co-scientist kaizen
   パターン（ADR-0007 系）の Iteration 0/1 として baseline/after を記録済み。

## 実測（ledger 抜粋）

| | getVideoFeed p50 | getTimeline p50 | 備考 |
|---|---|---|---|
| baseline（全走査） | **14,147ms** | 14,279ms | 毎回。cache 無し |
| after（本 ADR） | **21ms** | 22ms | HIT 4/5。miss は ~8s（narrow scan 3 波） |

- SPA `/videos` の初回 🎬 ローディング約 16 秒問題は、warm hit 時 ~0.1 秒に解消。
- テスト: 402 tests / 1,476 assertions green（golden test 1 本を、ファイル内で
  既知の with-redefs 罠（multi-hop read の後続 hop を覆えない）から `with-datoms`
  （set! ベース）へ移行）。

## Consequences

- (+) p50 で約 670 倍。全走査依存が消え、グラフ成長でのメモリ上限失敗リスクも
  フィード経路から除去。
- (−) miss 経路は依然 ~8 秒（attr scan 19 本 / 並列 6 の 3 波 + kotobase
  リクエスト単価 ~1.2s）。cron warmer は **cron が走った colo しか温めない**
  （Cache API は per-colo）ので、他 colo の初回 1 リクエスト/60 秒は miss を踏む。
- (−) 新着投稿のフィード反映は最大 ~60 秒遅延（TTL）。cadence 投稿（時間単位）
  には十分。
- (−) attr scan の payload は record 総数に線形（likes/reposts 込み）。エンゲージ
  メントが桁で増えたら、kotobase 側の複数 attr 一括 API（1 リクエスト化、下記
  follow-up）が要る。

## Follow-ups

- kotobase cljc-worker に **multi-attr 一括 datoms API**（components の OR /
  attr セット指定）を追加し、フィードの miss を 1 リクエスト ~1.5s に短縮する。
- kotobase リクエスト単価 ~1.2s 自体の削減（R2 読み回数 / chain-head 解決の
  キャッシュ）。
- per-colo cache の限界を超えるなら Cache API → KV（グローバル ~50ms）検討。
- probe の定期実行（cron/routine 化）と ledger の回帰監視。
