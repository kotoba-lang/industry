# kyber-plm

PLM × Kyber ERP 貫通スレッドの参照実装（Clojure + Datomic Local）。
設計は **ADR-2606171400**（`ai-gftd-apps-gftdcojp/90-docs/adr/`）。

EBOM → MBOM → 在庫 → 標準原価 → 総勘定元帳(GL) を 1 本の Datomic グラフで貫通させ、
設計変更(ECO)が在庫評価・原価・P/L まで波及するさまを最小構成で動かす。

## 動かす

```bash
clojure -M:run     # エンドツーエンド デモ (bb demo)
clojure -M:test    # テスト (bb test)
```

Datomic Local を `:storage-dir :mem`（揮発・ディスク非残置）で使う。本番は同じ
`kyber-plm.db` facade を kyber-datomic XRPC（ADR-0025 / mangaka.store.kotoba）に差し替える。

## レイヤ

| ns | 役割 |
|---|---|
| `kyber-plm.schema` | Datomic スキーマ。`:plm.*`(品目/BOM辺/ECO) / `:erp.*`(在庫/PO/原価/COA/仕訳) / `:ocel.*`(監査) |
| `kyber-plm.db` | Datomic Local facade（fresh-conn / tx! / q / pull / attr）|
| `kyber-plm.plm` | PLM core: 品目・BOM辺・ECO の構築子、lifecycle、MBOM/where-used クエリ |
| `kyber-plm.cost` | 標準原価ロールアップ（released MBOM を bottom-up 再帰、サイクル検出）|
| `kyber-plm.thread` | PLM→ERP reactive 派生（**released gating**）: release-item! / receive-goods! / release-eco! |
| `kyber-plm.erp` | ERP tx ビルダー: 残高ゼロ検証付き仕訳・原価スナップショット・OCEL・帳票クエリ |
| `kyber-plm.demo` | `-main` シナリオ |

## 不変条件

- **released gating**: `:draft`/`:in-review` の品目・ECO は ERP に伝播しない（`thread` が released を確認してから派生）。
- **GL バランス**: `erp/journal` は借方=貸方でなければ例外。
- **原価ロールアップ**: `rolled(make) = Σ rolled(child)×qty`、`rolled(buy) = std-unit-cost`。buy の原価欠落は例外（暗黙の 0 円ロールを禁止）。

## デモが示すこと

1. 部品(buy ×2)→組立(make) を release。make release 時に在庫登録＋原価ロール(500)。
2. 組立を 10 個受入 → perpetual inventory（Dr 在庫1400 / Cr GR-IR 2150 = 5000）。
3. ECO-1 で部品 PN-2000 の標準を 100→130。release-eco! が親を再ロール(620)し、
   在庫を 5000→6200 に再評価、差額 1200 を変動勘定(5900, P/L)へ計上。
4. 全イベントを APQC タグ付き OCEL に記録（process-mining 可能）。

## ADR 上の位置づけ / 次の一手

- NSID は実装実体に整合：`:plm.item/*` ↔ `ai.gftd.apps.kyber.plm.item`、`:erp.inventory/*` ↔ `ai.gftd.apps.kyber.inventoryItem`。
- MVP は専用 projector を新設せず、本スレッドを ERP 側に相乗りさせる方針（ADR-2606171400 §4 注）。
- 未実装（このモジュールの範囲外）: 日付/ECO 二系統の effectivity 解決、MRP 不足計算→PO 自動起票、revision supersede の完全運用、kyber-datomic 本番バックエンド。
