---
id: adr-2607010850-kotoba-lang-ec-domain-lanes
title: "ADR-2607010850: kotoba-lang に EC ドメイン lane ライブラリ群（senden/madoguchi/soko/kiyaku）を追加し itonami 業務モデルを拡張共通化する"
status: proposed
doc_type: adr
topic: kotoba-lang-ec-domain-lanes
authoritative: true
last_verified: 2026-07-01
authoritative_for:
  - 小売・サービス EC 以外の業務ドメイン（marketing / support / warehouse / customer+returns）の kotoba-lang 共通ライブラリ構成
  - chobo.ledger EAVT 基底の上の lane 拡張モデル（各ドメイン = lane keyword + 純粋モデル + re-frame subset + 純 hiccup）
  - senden/madoguchi/soko/kiyaku 4 repo の境界と chobo/mise/shitsuke 依存関係
related:
  - 90-docs/adr/2606302240-kotoba-lang-chobo-services-ec.md
  - 90-docs/adr/2606302206-kotoba-lang-mise-ec-system.md
  - 90-docs/adr/2606301900-kotoba-lang-shitsuke-design-system.md
  - orgs/kotoba-lang/senden
  - orgs/kotoba-lang/madoguchi
  - orgs/kotoba-lang/soko
  - orgs/kotoba-lang/kiyaku
  - orgs/kotoba-lang/chobo
  - orgs/kotoba-lang/mise
  - orgs/gftdcojp/cloud-itonami
supersedes: []
superseded_by: []
---

# ADR-2607010850: kotoba-lang に EC ドメイン lane ライブラリ群（senden/madoguchi/soko/kiyaku）を追加し itonami 業務モデルを拡張共通化する

**Status**: proposed — landed (2026-07-01), 4 repo tests green / manifest 登録済み
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki

## Context / 背景

mise（小売 EC）vs Shopify は ~15–20% カバー。chobo（services EC: 監査台帳 + サブスクリプション
+ 請求）で itonami 業務モデルの一部を共通化したが、EC/業務ドメインは sales/仕入れ/発注 以外にも
**marketing / customer support / 倉庫・logistics / customer accounts / 返品・RMA** など多数あり、
これらは kotoba-lang に共通ライブラリとして存在しなかった（一部は itonami の lane に埋まっている
程度）。ユーザー指摘「販売仕入れ発注以外にも marketing, customer support, 倉庫... などもっとある」
を受けて、欠けている EC ドメインを lane ライブラリとして kotoba-lang に追加する。

## Decision / 決定

chobo.ledger（EAVT 基底）の上に、4 つの EC ドメイン lane ライブラリを新規起こす。各ドメインは
`lane` keyword + 純粋データモデル + 状態機械 + re-frame portable 7-fn subset events/subs + 純 hiccup
views（shitsuke 上）+ SSR parity。いずれも chobo.ledger に activity として投影される。

### 4 repo

| repo | 名 | ドメイン | lane | 依存 |
|---|---|---|---|---|
| `senden` | 宣伝 | marketing: campaign / funnel / attribution | :marketing | shitsuke + chobo |
| `madoguchi` | 窓口 | customer support / CRM: ticket / customer / contact / SLA | :support | shitsuke + chobo |
| `soko` | 倉庫 | warehouse / logistics: multi-location stock / shipment / fulfillment | :warehouse | shitsuke + chobo + mise |
| `kiyaku` | 客 | customer accounts + returns/RMA: account / address / returns | :customer, :returns | shitsuke + chobo |

### 契約（authoritative）

1. **dual-render**: 各 `.cljc` 純 hiccup view を SSR（`shitsuke.hiccup/->html`）と reagent（cljs）の
   両方へ（shitsuke/mise/chobo と同契約）。
2. **portable re-frame subset**: 各 `*.events` は `shitsuke.re-frame.core` に 7 関数のみで登録。
3. **chobo.ledger 投影**: 各ドメインの domain event は `*-activity` fn で chobo.ledger の activity
   に投影（lane = ドメイン）。監査台帳は共通基底。
4. **純粋 state**: 各ドメインの状態遷移（campaign/ticket/shipment/return の statechart）は純関数。
5. **mise 連携**: soko は mise に依存（inventory 連携）。kiyaku の end-customer は chobo.tenant
   （operator）と対。

### ドメインモデル（要点）

- **senden**: Campaign（draft→scheduled→running→paused→completed|cancelled）+ spend/conversions/CPA;
  Funnel（awareness→interest→consideration→purchase→retention）+ conversion rates;
  Attribution（first/last/linear/multi-touch）。
- **madoguchi**: Ticket（new→open→pending→resolved→closed, reopen）+ SLA breach;
  Customer（tags/LTV/order-refs/contact-log）。
- **soko**: Warehouse + location-aware Stock（sku×wh→qty）+ reserve/restock/transfer;
  Shipment（pending→picked→packed→shipped→delivered|cancelled）+ tracking;
  fulfillment plan（pick-list + per-warehouse shipment, first-fit allocation）。
- **kiyaku**: CustomerAccount + address book + payment-method refs + wallet;
  ReturnRequest/RMA（requested→approved→shipped→received→refunded|rejected）+ refund。

## Consequences

- **正向**: EC/業務ドメインが marketing/support/warehouse/customer+returns まで kotoba-lang で共有化。
  新規 EC サイトは mise + chobo + 必要な lane ライブラリを組み合わせて構成。itonami の lane も
  chobo.ledger 基底に載せ替え可能。
- **負向**: v1 は純粋モデル + mock（D1/Datomic adapter 未統合）。各ドメイン間の自動連携
  （例: order→fulfillment→shipment→return）は follow-up stub のみ。
- **移行**: 4 repo の manifest pin は各 repo main HEAD。mise pin は変更なし（efc6bc6）。

## Alternatives Considered

- **1 つの巨大 EC ライブラリに全ドメインを詰め込む**: 却下。関心事が大きすぎ、shitsuke/mise/chobo
  の小粒共通ライブラリ方針に合わない。ドメイン単位の repo がクリーン。
- **itonami の lane としてのみ実装**: 却下。kotoba-lang 共通化されず、mise（小売）と共有できない。
- **Shopify の各機能を直接模倣**: 却下。gftd. の cljc/kotoba 体制（ポータブル・データ主権・監査台帳）
  に合わない。lane + 純粋モデルで後付け可能。

## References

- `orgs/kotoba-lang/chobo/docs/adr/0001-chobo-services-ec.md`（EAVT 基底）
- `orgs/kotoba-lang/mise/docs/adr/0001-mise-ec-system.md`（小売 EC）
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/operating.cljc`（lane カタログ起源）
- `90-docs/adr/2606301900-kotoba-lang-shitsuke-design-system.md`（design system 基底）

Co-Authored-By: Claude Opus 4.8 (1M context)
