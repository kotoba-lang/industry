# ADR-2607022200: 各 product の gate 計器を並列実装 + superproject reader 配線

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607022100 で gate 評価器を入れ、各 BMC の「不足計器」が毎朝 surface される
ようにした。オーナー指示（2026-07-02）で、その不足計器を **subagent で並列・
per-repo 隔離**（各 child repo を独立 clone、CLAUDE.md の共有 checkout 並行編集
禁止に従う）で実装する。

まず 11 プロダクトを read-only スカウトで実地調査し、各 repo の「gate を測定
可能にする最小コード + emit すべき metrics 形式」を確定。次に純コードで実装・
検証できるものを並列実装エージェント（独立 clone → 実装 → repo テスト → draft PR）
に回した。

## Decision — emitter（各 child repo、draft PR）

| product | gate emitter | PR | 状態 |
|---|---|---|---|
| network-isekai | `fork_stats.cljc` + feed index の fork fold → `public/feed/fork-stats.edn`（weekly / fork-origin-ratio / viral-coefficient） | gftdcojp/network-isekai#15 | nbb gate 2 種 green |
| net-kotobase | Stripe webhook fulfillment（`checkout.session.completed`→tenant paid、KV）+ tenant 従量 metrics | (実装 PR) | — |
| cloud-murakumo | `cost.cljc`（run ledger→¥/Mtok）+ `GET /infer/cost` + run body に node/elapsed | (実装 PR) | — |
| club-shinshi | `creator_billing_daily` D1 migration + rollup + read（creator GMV vs ad 収益） | (実装 PR) | — |
| cloud-manimani | `POST /telemetry/install`（opt-out 匿名）+ signup 記録 + 転換 metrics | (実装 PR) | — |
| ai-gftd-yukkuri | `analyze_youtube` に API キー経路（subscribers/views は OAuth 不要）+ `channelStats` + cron | (実装 PR) | — |

いずれも **純コードのみ**（secret/OAuth/課金/PSP 契約は含めず PR 本文に人間残作業
として明記）、main 非 push・force-push 禁止・draft。

## Decision — reader（superproject `gftd.gate`、本 PR）

emitter が出す metrics キーを gate が測定に使えるよう gate-spec を
measurable-with-fallback 化。`:compare {:lhs :op :rhs}`（2 metrics 比較）形式を追加:

- `:hyp/isekai-fork-viral` → `[:fork :viral-coefficient] >= 1.0`
- `:hyp/murakumo-tok-price` → compare `[:cost :fleet-yen-per-mtok] <= [:cost :spot-yen-per-mtok]`
- `:hyp/club-shinshi-creator-take` → compare `[:revenue :creator-gmv-jpy] > [:revenue :ad-revenue-jpy]`
- `:hyp/manimani-ledger-pay` → `[:conversion :pct] >= 0.04`（Obsidian Sync 水準）
- （既存）`:hyp/kotobase-graph-arpu` → `[:stripe :active-subscriptions] >= 1`、
  `:hyp/yukkuri-ypp-then-rpm` → `:all` 登録者>=1000 & 総再生>=4000h

各 spec は `:needs-when-unmeasurable` を保持 — emitter が deploy されて metrics に
該当キーが入るまでは従来どおり「不足計器」を surface し、キーが入った朝に
**routine が自動で gate 判定 → 満たせば hyp を validated 昇格**。tests 6→9
（22→37 assertions）green。

## Consequences

- (+) 6 product の gate 計器が実コードとして各 repo に draft PR で着地（または着地中）。
  superproject 側は 5 product 分の gate を機械測定可能に配線済み。emitter deploy →
  metrics 反映 → 自動昇格、の経路が両端そろった。
- (+) `:compare` 形式で「A > B」型 gate（収益比較・原価比較）も機械判定可能に。
- (−) collect.cljs が各 emitter エンドポイント/ファイルを読む 1 行ずつの配線は、
  emitter が本番 deploy された後の follow-up（未 deploy を今叩いても空）。
- (−) apex（Stripe price=人間）/ cloud-itonami（vertical 経営判断 + Pages Functions
  未実装）/ app-aozora（PDS 書込のデプロイ権限、OCEL activity 空スタブ）/
  app-aozora-yoro（child repo 未分離）/ etzhayyim（主観 gate + refs/west/main）は
  emitter が重い or 人間判断依存のため本ラウンドでは reader は needs-only のまま、
  emitter は次ラウンド。
- (−) 各実装 PR は draft・未マージ。build 検証は各 repo のテストで担保、レビュー後にマージ。

## References

- ADR-2607022100（gate 評価器 / advisor）
- 各 product のスカウト spec（本セッションの調査）
- gftdcojp/network-isekai#15 ほか per-repo draft PR
