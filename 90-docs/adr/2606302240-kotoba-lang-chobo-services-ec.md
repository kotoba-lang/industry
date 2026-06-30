---
id: adr-2606302240-kotoba-lang-chobo-services-ec
title: "ADR-2606302240: kotoba-lang/chobo を services-EC 基盤として itonami 業務モデルを共通化する"
status: proposed
doc_type: adr
topic: kotoba-lang-services-ec
authoritative: true
last_verified: 2026-06-30
authoritative_for:
  - 小売 EC（mise）とサービス EC（itonami）が共有する監査台帳 / 請求 / サブスクリプション基盤の repo 名と境界
  - EAVT（activity/artifact/relation/decision/effect）+ plan/tier/entitlement/quota/metering/overage + invoice/settlement + tenant/operator trust の層構成
  - 注入 port（ILedgerStore / IInvoiceStore）と mock adapter の v1 範囲
  - mise.order → chobo.ledger 投影（mise.ledger stub）の v1 連携範囲
related:
  - 90-docs/adr/2606302206-kotoba-lang-mise-ec-system.md
  - 90-docs/adr/2606301900-kotoba-lang-shitsuke-design-system.md
  - orgs/kotoba-lang/chobo
  - orgs/kotoba-lang/mise
  - orgs/kotoba-lang/shitsuke
  - orgs/gftdcojp/cloud-itonami
  - orgs/gftdcojp/cloud-itonami-6310
  - orgs/gftdcojp/ai-gftd-apex
supersedes: []
superseded_by: []
---

# ADR-2606302240: kotoba-lang/chobo を services-EC 基盤として itonami 業務モデルを共通化する

**Status**: proposed — landed (2026-06-30), chobo/mise tests green / manifest 登録済み
**Date**: 2026-06-30
**Deciders**: Jun Kawasaki

## Context / 背景

`cloud-itonami` は業務 OS（manimani の企業版）で、EAVT（activity/artifact/relation/decision/
effect）の監査台帳を主体に sales/contract/billing/erp/plm/mes の lane を持つ。`ai-gftd-apex` は
Proton 型サブスクリプション商品設計（tier/entitlement/quota/overage/metering）を持つ。これらは
「サービス型 EC」の語彙だが、`mise`（小売 EC: cart/SKU/checkout）とは別系統で再利用されていない。

mise vs Shopify は小売 EC 機能カバー ~15–20%（cart/checkout/order/inventory/pricing の純粋骨格
のみ）。一方 itonami 系のサブスクリプション/計量/請求/監査台帳は kotoba-lang に共通ライブラリとして
無い。両者を共通化するため、itonami の業務モデルから EC 関連部分（監査台帳 + サブスクリプション/
計量 + 請求）を抽出し `chobo`（帳簿）として kotoba-lang に置く。

## Decision / 決定

`chobo`（kotoba-lang, portable `.cljc`, runtime dep は shitsuke のみ）を新規起こす。mise（小売）
と itonami（サービス）が同じ台帳/請求/サブスクリプション基盤を共有する。

### 層

| 層 | 役割 |
|---|---|
| `chobo.ledger` | EAVT activity/artifact/relation/decision/effect + append-only log + `ILedgerStore` |
| `chobo.subscription` | plan/tier/entitlement/quota/metering/overage（ai-gftd-apex 設計由来） |
| `chobo.invoice` | invoice + lines + status statechart + dunning + `IInvoiceStore` |
| `chobo.tenant` | tenant + capabilities + operator trust levels |
| `chobo.events` | re-frame events/subs（portable 7-fn subset via shitsuke） |
| `chobo.views` | 純 hiccup: ledger-entry / meter-gauge / plan-status / invoice-card / tenant-badge |
| `chobo.ssr` | SSR parity |

### 契約（authoritative）

1. **dual-render**: 同じ `.cljc` 純 hiccup view を SSR（`shitsuke.hiccup/->html`）と reagent（cljs）
   の両方へ（shitsuke/mise と同契約）。
2. **portable re-frame subset**: `chobo.events` は `shitsuke.re-frame.core` に 7 関数のみで登録。
3. **注入 port**: `ILedgerStore`, `IInvoiceStore`（mock 同梱）。Datomic/D1/kotoba-server adapter は
   follow-up。
4. **純粋 state**: ledger/subscription/invoice の状態遷移は純関数。台帳は append-only。
5. **mise 連携（v1 stub）**: `mise.ledger/order->activity` が mise Order を chobo.ledger の
   :sales/:order activity に投影。`mise.pricing` totals → `chobo.invoice` line。v1 は投影 stub
   のみ（order→invoice→ledger 自動パイプラインは follow-up）。
6. **itonami 由来**: EAVT schema は itonami の `:itonami.activity/*` が起源。subscription model は
   ai-gftd-apex 設計が起源。chobo は両者の portable 抽出。

## Consequences

- **正向**: サービス型 EC（subscription/metering/監査台帳）と小売 EC（mise）が共通基盤で共有化。
  新規 SaaS/サブスクリプション商品は chobo + shitsuke で立ち上がる。itonami の lane も chobo.ledger
  に載せ替え可能。
- **負向**: v1 は mock adapter のみ（Datomic/D1 未統合）。mise との実連携（order→invoice→ledger の
  実際の自動投影）は follow-up stub のみ。
- **移行**: mise は `mise.ledger` stub で chobo に依存（pin 前進 9804549 → efc6bc6）。itonami 本体の
  chobo 載せ替えは別 follow-up。

## Alternatives Considered

- **itonami に EC 関連を直書き**: 却下。mise（小売）と共有できず、shitsuke/mise の共通化方針と対に
  ならない。
- **mise に subscription を拡張**: 却下。小売 EC とサービス EC は関心事が違う。chobo として独立させ
  mise が依存する方がクリーン。
- **Shopify のサブスクリプション API を模倣**: 却下。gftd. の cljc/kotoba 体制（ポータブル・データ
  主権・監査台帳）に合わない。

## References

- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/schema.cljc`（EAVT 由来）
- `orgs/gftdcojp/ai-gftd-apex/docs/products/ai-gftd-apex/business-operating-design.md`
  （subscription/entitlement/quota/overage 設計由来）
- `90-docs/adr/2606302206-kotoba-lang-mise-ec-system.md`
- `orgs/kotoba-lang/chobo/docs/adr/0001-chobo-services-ec.md`（per-repo 設計 SSoT）

Co-Authored-By: Claude Opus 4.8 (1M context)
