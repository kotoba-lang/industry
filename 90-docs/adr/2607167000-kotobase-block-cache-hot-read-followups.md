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
2. **他ホット読みの view 化（#4）** — per-actor/per-thread の「個別 view」は
   不採用（カーディナリティ無限）のまま、**全消費者を単一の global view から
   サーブする**形で解決（addendum、2026-07-17）: view spec に `:yoro.follow/*`
   を追加し、`scan-yoro` 自体を view-first（attr-wave fallback、真の全走査は
   `scan-eavt-full` として温存・消費者ゼロ）に差し替え。thread/author/profile
   は単一 scan 化（actor 解決も scan 内 handle registry で完結。getProfile は
   registered handle も解決するよう互換改善）。実測: 初回 2.2〜3.3s →
   **1.2〜1.3s**（+ 60s 内の繰り返しは edge cache ~0.1s）。これにより
   engagement/registry/push/convo/prekeys/likes/followers 等の全 scan-yoro
   消費者から 13〜18s の全走査が消えた。compound read の必要性は解消
   （残る 1.2s は kotobase 1 往復の単価そのもの）。付随修正: kc/view の 404 を
   empty-success でなく reject に（view 欠落を空グラフと偽装し fallback を
   殺す実障害）、scan-posts の同期 throw も fallback へ、テストの async
   restore race（done 後の遅延 restore が他 ns へ resolving stub を漏らし
   偽 view を供給）への構造的対処（決定論 baseline + call-site self-protect）。
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

## Addendum 2（2026-07-17）: kotobase.net プレーンの検証結果と queue poll 最適化

「未検証」だった kotobase.net の serving 実体をコード直読 + 実測で確定:

- apex `kotobase.net`（net-kotobase worker）は datomic ops を
  `KOTOBA_BACKEND_URL = backend.kotobase.net` にプロキシし、その実体
  **kotobase-cf-wasm は名前だけ WASM で、中身は kotobase-server の cljc
  handler（hot-datoms、components/limit 尊重の range-pruned narrow 読み）に
  移行済み**だった。kotobase-client の「wasm worker は components を無視」
  警告 docstring は陳腐化していた（本調査を一度誤誘導 — PR #9 で修正）。
- ただし **`datomic.q` は kotobase.net 側も hot-db 全再構築**（aozora 側
  cljc-worker と同じ）で、その最大消費者は **cloud-murakumo 分散ジョブ
  キューの 1 秒間隔 worker poll**（queued-jobs/job-by-id が Datalog q）。
  append-only の :queue/events 履歴に比例して毎 poll のコストが成長する
  構造だった（実測: 現状の queue graph は 0.4〜0.5s/poll と小さく無事故 —
  成長曲線だけが問題）。
- **修正**: poll を `:avet [":gen.job/edn"]` の narrow attr-prefix 読みに
  置換（payload は同一、hot-db 構築ゼロ、cloud-murakumo PR #22、202 tests
  green）。これで kotobase.net プレーンの既知の O(graph) 定常読みは解消。
  残る q 消費者は net-kotobase 自身の geo/web search（低頻度・別スコープ）。
- kotobase.net には `datomic.view` / 不変ブロックキャッシュは未搭載
  （kotobase-cf-wasm の store 層は cljc-worker と別実装）— 必要になった
  時点で cljc-worker と同じ 2 点を移植するのが次の一手。

## Follow-ups

- ~~zone Browser Cache TTL~~ — **closed 2026-07-17（worker 側で解消・zone 設定変更不要）**:
  CDN-Cache-Control でも zone の 4h 書き換えは残ると実測 → 送出応答を常に
  `private, max-age=15` に正規化し zone 前面キャッシュに保存させず、鮮度
  キャッシュは caches.default のみに保持（保存コピーは public s-maxage=60）。
  実測 hit 0.09〜0.11s / miss 0.65s、書き換え消滅（app-aozora PR #85）。
- （継承）O(Δ) view 維持 / chunked rows — 上記トリガ到達時。
