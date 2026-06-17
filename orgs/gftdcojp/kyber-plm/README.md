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
| `kyber-plm.cost` | 標準原価ロールアップ（released MBOM を bottom-up 再帰、サイクル検出、**effectivity as-of 対応**）|
| `kyber-plm.thread` | PLM→ERP reactive 派生（**released gating**）: release-item! / receive-goods! / release-eco! / **revision supersede** |
| `kyber-plm.mrp` | 多階層 MBOM 展開 → on-hand ネット → **PO 自動起票**（ocel po.created）|
| `kyber-plm.production` | **製造完了 backflush**: 部品を WIP へ払出(Dr WIP/Cr 在庫)、完成品受入(Dr 在庫/Cr WIP)、WIP クローズ |
| `kyber-plm.erp` | ERP tx ビルダー: 残高ゼロ検証付き仕訳・原価スナップショット・OCEL・帳票クエリ |
| `kyber-plm.store` | **バックエンド抽象**（Store protocol）: Datomic Local（dev）↔ kyber-datomic XRPC（本番, mangaka.store.kotoba 互換）|
| `kyber-plm.kotobase` | PLM item/BOM を **kotobase `kg.ingest` エンティティ**（claims `{:pred :value}` / relations）へ射影。本番テナント書き込み口（live/LIVE.md で write→commit→read-back 実証済み）|
| `kyber-plm.demo` | `-main` シナリオ（§7 で kg.ingest 射影も表示）|

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

## effectivity / MRP / revision / backend

- **effectivity**: BOM辺の `:eff-from`/`:eff-to`（と `:eco`）を `cost/rolled-cost` の as-of で解決。同一ポジションの旧→新部品の切替を日付で展開。
- **MRP**: `mrp/mrp-run!` が make 所要を多階層展開し on-hand とネットして買い品の不足分に PO を自動起票。
- **revision**: `plm/revise-item-tx` で新版（BOM継承・旧版 supersede）を作成、`release-item!` 時に旧版を `:obsolete` 化（ocel item.superseded）。
- **backend**: `kyber-plm.store` の Store protocol で Datomic Local（dev/test）と kyber-datomic XRPC（本番）を差替可能。本番 transport は mangaka.store.kotoba と同じ wire 契約（`tx_edn` / `rows_edn` / `entity_edn`）。**ドメイン全体（plm/cost/thread/mrp/production）が `kyber-plm.db` 経由で Store に委譲**するため、kotoba バックエンド上でもそのまま動く（テスト `whole-domain-runs-on-kotoba-backend` で実証）。
- **production**: `production/complete-production!` が直下 MBOM 部品を払い出して WIP に積み、完成品を受け入れる。`parent std = Σ child std × qty` のロールアップ不変条件により WIP は 0 にクローズ。
- ECO 再評価は affected 部品**自身の在庫**と、それを使う全親の両方を再評価（標準変更は両方向に波及）。

## ADR 上の位置づけ / 次の一手

- NSID は実装実体に整合：`:plm.item/*` ↔ `ai.gftd.apps.kyber.plm.item`、`:erp.inventory/*` ↔ `ai.gftd.apps.kyber.inventoryItem`。
- MVP は専用 projector を新設せず、本スレッドを ERP 側に相乗りさせる方針（ADR-2606171400 §4 注）。
- 残課題: 実 kyber-datomic エンドポイントでの疎通（要 XRPC 認証情報）。ECO disposition（rework/scrap）の在庫処理、ロット/シリアル trace、複数プラント MBOM。
