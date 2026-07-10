---
id: adr-2607083500-cloud-itonami-vehicle-design-link
title: "ADR-2607083500: cloud-itonami に vehicle-design-link facade を追加する — 実CAD/CAM依存を持たず、released designをopaque payloadとしてBOM再見積りレビューに繋ぐ"
status: accepted
doc_type: adr
topic: cloud-itonami-plm-integration
authoritative: true
last_verified: 2026-07-10
authoritative_for:
  - cloud-itonami.vehicle-design-link の設計決定（activity/effect/audit facade、governance-onlyスコープの維持）
  - vehicle-design参照をkotoba.plm.itemのschemaではなくeffect payloadに載せる判断
  - :cost-estimate/:bom-diff の数値は呼び出し側のkotoba.plm.cost/rolled-costを根拠にする、という追補（2026-07-10）
related:
  - 90-docs/adr/2607083400-vdesign-cad-cam-bridge.md
  - orgs/gftdcojp/cloud-itonami/src/cloud_itonami/vehicle_design_link.cljc
  - orgs/gftdcojp/cloud-itonami/src/cloud_itonami/mes.cljc
supersedes: []
superseded_by: []
---

# ADR-2607083500: cloud-itonami vehicle-design-link facade

- Status: accepted (2026-07-08)
- Deciders: Jun Kawasaki

## Decision

`cloud-itonami.vehicle-design-link` を新設する。`cloud-itonami.mes` と
同型（`design-review-activity`(activity, `:plm`レーン) →
`design-review-effect`(effect, `:risk :financial`) →
`design-review-audit`(audit) を `concept->tx` で1本のtxにまとめる）。

**cloud-itonamiは`kami-engine-vehicle-designer`/`brep`/`cnc`への依存を
一切持たない。** released designの `concept`（`:class`/`:powertrain`等）は
不透明なEDNマップとして扱われ、title生成にのみ使う。`bom-diff`/
`cost-estimate` は呼び出し側が計算して渡す再見積り入力で、cloud-itonami
自身は一切の工学計算・製造計算を行わない。

design参照（`design-ref`）は `kotoba.plm.item` の `item`/`bom-edge`
スキーマには追加せず、effectの `:payload` に載せる。

## Context

ADR-2607083400（kami-engine-vehicle-designer→brep(CAD)→cnc(CAM)統合）に
続き、cloud-itonami側にも同じ設計データへの連携ファサードを追加する
依頼があった。調査の結果:

- `cloud-itonami.plm` は `kotoba.plm.item` への21行の純粋re-exportで、
  `item`/`bom-edge` は固定の `:keys` destructuring + `cond->` で
  **事前に宣言された特定のoptional field のみ**（item: category/
  std-unit-cost/package-ref、bom-edge: ref-designator/eff-from/eff-to/eco）
  を許容する。任意キーを自由に追加できる拡張点ではない（`:design-ref`の
  ような未宣言keyを渡しても`:keys`destructuringにより黙って無視される）。
  よって `:design-ref` を追加するには `kotoba.plm.item`（別repo
  `kotoba-lang/plm`、全cloud-itonami domain共有）のスキーマ改修が必要になり、
  「薄いファサード」の範囲を超える。
- `cloud-itonami.mes`（本ファサードの直接のモデル）は `cloud-itonami.store`
  （800行超）や `cloud-itonami.approval` を一切requireせず、
  `cloud-itonami.activity` のみに依存する「純粋tx投影」namespaceとして
  設計されている。design-review facadeも同じ理由で `store`/`approval`
  を避け、`activity`のみに依存させた（`design-review-audit`は
  `approval/audit`を再利用せず、`mes.cljc`の`completion-audit`と同じ形で
  手組みした）。
- 既存の`cloud-itonami-isic-2610`/`-3030`系ADRが明記する「実際のfab/MES
  制御・実機ロボットモーションプランニング・EDA/CAEシミュレーションは
  モデル化しない」というgovernance-onlyの設計哲学を、`gftdcojp/cloud-itonami`
  本体にもそのまま適用した。

## Consequences

- `cloud-itonami`の`deps.edn`に新規依存の追加は不要（`kami-engine-vehicle-designer`/
  `brep`/`cnc`いずれも依存しない）。
- `test/cloud_itonami/test_runner.cljc`は新規testnamespaceの自動発見をせず、
  `:require`と`test-names`の両方に明示登録が必要（本ADRの実装で見落とし
  かけた点として記録しておく）。
- **運用上の注意**: `orgs/gftdcojp/cloud-itonami`には`lefthook`の
  `post-checkout`フックがあり、`git checkout`のたびにCloudflare Pagesへ
  自動デプロイされる（`deploy-cloudflare-pages`）。このrepoでbranch
  checkoutを伴う作業をする際は、mainの現在のcontentが毎回本番デプロイ
  される点を認識しておくこと。

## 却下案

- **`kotoba.plm.item`/`bom-edge`に`:design-ref`フィールドを追加**: 全
  cloud-itonami domain（sales/mes/erp/...）が共有するスキーマへ
  vehicle設計固有のfieldを混ぜることになり、責務が混ざる。effect
  payloadで十分に用途を満たせるため不採用。
- **`cloud-itonami.approval/audit`を再利用**: `store.cljc`（800行超）への
  依存を引き込むことになり、`mes.cljc`が確立した「純粋tx投影
  namespaceはstoreに依存しない」という規約を破る。

## Addendum（2026-07-10）— cost-estimateを実際のPLMコスト再計算に紐付け

初版のテストは`:cost-estimate {:delta-usd 340.0}`のような、根拠のない
決め打ちの数値をそのまま渡していた。本ns自身のdocstringは「呼び出し側
（`kotoba-lang/plm`）が計算した実際の再見積り値」を渡す想定と明記して
いたにもかかわらず、テスト内では実際にその計算を一度も経由していな
かった——数値の出どころが検証可能でなかった。

`kotoba-lang/plm`自身の`receipt-and-eco-revaluation`テスト
（`test/kotoba/plm/thread_test.cljc`）が既に検証済みの実シナリオ
（アセンブリ`PN-1000@A` = 4×`PN-2000@A` + 2×`PN-2001@A`。ECOで
`PN-2000`の標準原価を100→130に変更 → `kotoba.plm.cost/rolled-cost`が
親の原価を500→620に再計算、差分120）から、その**実際に検証済みの数値**
をそのまま`cost-estimate-matches-a-real-plm-cost-rollup`テストへ転記した
（`orgs/gftdcojp/cloud-itonami/test/cloud_itonami/vehicle_design_link_test.cljc`）。

- **`kotoba-lang/plm`への新規依存やDatomic Localは追加していない**——
  cloud-itonamiのtest実行が軽量なままであるという既存の制約
  （`kotoba.plm.store-datomic`はJVM専用+`:datomic`/`:test`alias opt-in）
  を尊重し、数値を転記するに留めた。真のend-to-end統合（cloud-itonami
  からkotoba-lang/plmを実際に呼び出す）はこのADRのスコープ外——
  却下案として明記する。
- merge commit: `db37cb409bba2a0db0a0f5c592d2989c759eedd3`
  （`gftdcojp/cloud-itonami`, branch `feat/vdl-real-plm-cost-test`）。
  487テスト/3237アサーション、既存テストへの regressionなし。

### 却下案（追補）

- **cloud-itonamiのtestからkotoba-lang/plmを実際に呼び出す真のend-to-end
  統合**: `kotoba.plm.db/fresh-conn`はDatomic Local（`:datomic`/`:test`
  alias、JVM専用）を要求する——cloud-itonamiのtest suiteに新規の重い
  依存を持ち込むことになり、「薄いファサード」の設計哲学（本ADR初版の
  Decision節）に反するため見送った。転記した数値の検証可能性は
  `kotoba-lang/plm`側で`clojure -M:test`を実行すれば再現できる、という
  形で担保する。
