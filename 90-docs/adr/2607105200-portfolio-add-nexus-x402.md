# ADR-2607105200: ポートフォリオに nexus-x402（横断決済 facilitator/gateway）を追加

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki

## Context

ADR-2607093100（x402 を pay の標準ワイヤに採用）/ ADR-2607093300（nexus-x402
facilitator/gateway 設計）で `orgs/gftdcojp/nexus-x402` を scaffold・push 済み
（ゲートウェイ核 = `kotoba-lang/pay` の `pay.facilitator`、19 tests/112
assertions、landed）。しかし ADR-2607021500 の 7-layer ポートフォリオ体系
（cloud-murakumo/net-kotobase/cloud-itonami 等 11 product）には未登録だった —
nexus-x402 は L0（club-shinshi）/ L1（cloud-murakumo）/ L2（net-kotobase）を
**横断する決済レイヤ**であり、既存の縦の依存チェーン（L0→L2→L1→L3→L5→L4）
のどの1レイヤーにも属さない。

本 ADR で nexus-x402 を **横断インフラ（cross-cutting layer）** として
ポートフォリオへ追加し、既存の datoms 正本・CLI・スコアリングに統合する。

## Decision — レイヤー追加

| Layer | Product | 参照モデル | 一言 |
|---|---|---|---|
| **Lx cross-cutting: payment facilitator/gateway** | nexus-x402 | Cloudflare Monetization Gateway（closed）/ Stripe Connect | 鍵ゼロ・マルチ seller の自前 x402 決済 facilitator。L0/L1/L2 の複数 seller が共有する決済レール |

### nexus-x402 — 鍵ゼロ facilitator 型

各 seller worker（club-shinshi/murakumo/kotobase）が `pay.x402` +
`treasury.core` を個別 vendor して Basescan verify・challenge/authorize 配線を
重複実装している問題に対し、鍵を一切持たない中央 facilitator を提供する。
検証のみ担い決済は各 seller 自身の treasury へ直接着金（no-key-custody
不変条件）。2 つの統合モード（薄い委譲 API `/verify` `/settle` / フル
gateway proxy `/gateway/<seller>/<path>`）で seller が移行コストを選べる。
x402 標準ワイヤ準拠なので Cloudflare facilitator へロックインしない。

**riskiest**: 移行が強制でない設計のため、seller が実際に個別ゲートの vendor
をやめて nexus 委譲へ移行するか（gate = shinshi/murakumo/kotobase のうち
最低 1 つが Facilitator API 委譲へ移行）。

各 canvas 9 block 全文は base datoms
（`90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`）と生成 md
（`90-docs/business/nexus-x402-business-model.md`）が正。

## Decision — 統合

1. **base datoms** に nexus-x402 × 9 block + 3 仮説（riskiest/high/speculative）
   を追記（`:canvas/layer :payment-facilitator-infra`）
2. **CLI registry**（`gftd.cli`）に `nexus`（→nexus-x402）を追加、`gftd`
   umbrella が全 12 product を扱う
3. **layer-labels**（`gftd.canvas`）に `:payment-facilitator-infra` を追加
4. **bin/nexus** wrapper を追加（既存 8 CLI と同型の nbb ラッパー）
5. `gftd canvas md --all` で `90-docs/business/nexus-x402-business-model.md`
   を CLI 生成版へ再生成（先行の手書きドラフトを置換）。`nbb
   70-tools/bmc/run-tests.cljs` を通す

## スコア

nexus-x402 は Worker 未デプロイ・実トラフィックゼロのため、
`maturity-facts.edn` / `metrics/nexus-x402.edn` への実測記録は行わない
（club-shinshi/isekai 追加時と異なり、稼働中サービスではないため fabricate
しない）。デプロイ後に `70-tools/bmc/collect.cljs` 経由で実測を収集してから
`gftd score` を実行する。

## Consequences

- (+) 12 プロダクト・9 レイヤー相当（うち1つは横断）の整合体系に拡張。
  横断インフラ（複数 product が共有する決済レイヤ）という新しい canvas 類型
  が体系化された。
- (+) shinshi/murakumo/kotobase 向けの sales pitch（本 ADR と同日の follow-up）
  が、この canvas を根拠に個別提示できるようになった。
- (−) Worker 未デプロイのため Key Metrics・Revenue はすべて仮説段階（gate
  未測定）。デプロイ・SELLERS_JSON 投入は Cloudflare リソースに触れるため、
  並行 subagent との競合回避ルール（CLAUDE.md）に従い別タスクで行う。
- (−) 横断インフラという canvas 類型は既存 7-layer の縦依存モデルに厳密には
  収まらない — `layer-labels` は独立キーとして追加し、既存レイヤーの意味は
  変更しない。

## References

- ADR-2607021500（7 レイヤー正本、本 ADR で横断レイヤーを追加）
- ADR-2607093100（x402 標準ワイヤ採用）/ ADR-2607093300（nexus-x402 設計）
- ADR-2607021900（追加パターンの先行例: network-isekai / club-shinshi）
- `orgs/gftdcojp/nexus-x402/README.md`
- `90-docs/business/nexus-x402-business-model.md`（手書きドラフト → 本 ADR で
  CLI 生成版へ置換）
