# ADR-2607111100: cloud-itonami-isic-4620 — 農産物原材料・生体動物卸売を AgriTradeAdvisor ⊣ :agri-trading-governor で実装する biosecurity-gated agri-trading actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4690`(総合商社、ADR-2607110600)・`cloud-itonami-isic-4610`
(仲立/コミッションブローカー、ADR-2607111000)に続く fleet 継続タスクとして、
`kotoba-lang/industry` registry の未着手 `:spec` スロットから ISIC 4620
「Wholesale of agricultural raw materials and live animals」(農産物原材料・
生体動物の卸売)を選定した。他の卸売系プレースホルダーと同じ、死んだ
`gftdcojp/cloud-itonami-G4620` URL のまま放置されていた。

## Decision

新規 actor `cloud-itonami-isic-4620` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。`cloud-itonami-isic-4671`/`-4690` と同じ
**プリンシパル型**(自ら商品の所有権を持って売買する)だが、規制の重心が
燃料excise(4671)/輸出管理(4690)ではなく、**バイオセキュリティ/食品安全**
である点が本質的に異なる。

### 1. AgriTradeAdvisor ⊣ :agri-trading-governor(単一不変条件)

> **AgriTradeAdvisor は、`:agri-trading-governor` が拒否する
> `:delivery/dispatch`(穀物・家畜の配送)・`:invoice/settle`(請求確定)を
> 決して行わない。**

### 2. `:consignment-kind` による2系統の証明書チェック(設計上の核心判断)

ISIC 4620 は「植物由来の原材料(穀物・飼料・種子・繊維等)」と「生体動物」
という、法的に全く異なる2つの規制体系を1つの ISIC コードに束ねている。
実装エージェントは `agri-order` に `:consignment-kind`(`:plant`/`:animal`)
を持たせ、governor に**単一の汎用「証明書欠落」チェックではなく、意図的に
分離した2つの HARD チェック**を実装した:

- `phytosanitary-certificate-missing`(`:plant` のみ発火、
  `:phytosanitary-certificate?` を参照)
- `animal-health-certificate-missing`(`:animal` のみ発火、
  `:animal-health-certificate?` を参照)

理由: 単一の汎用チェックにすると、監査台帳から「どちらの規制体系が
未充足だったか」が読み取れなくなる。`agritrade.facts` の法域カタログも
法域×kind の組で保持する。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | no-spec-basis | HARD | 法域が spec-basis を持たない |
| 2 | evidence-incomplete | HARD | エビデンスチェックリスト未完了 |
| 3 | credit-uncleared | HARD | カウンターパーティ与信未クリア |
| 4 | contract-missing | HARD | 契約条件未記録 |
| 5 | **phytosanitary-certificate-missing** | HARD | 植物検疫証明書未取得(`:plant` consignment のみ) |
| 6 | **animal-health-certificate-missing** | HARD | 家畜衛生証明書未取得(`:animal` consignment のみ) |
| 7 | counterparty-sanctions-flag-unresolved | HARD(常時評価) | 制裁スクリーニング未了 |
| 8 | already-dispatched / already-invoiced | HARD | 二重配送・二重請求防止 |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | `:delivery/dispatch`/`:invoice/settle` は常に人間承認 |

### 3. Robotics premise: true(直近2兄弟からの単純パターンマッチではない)

`cloud-itonami-isic-4690`/`-4610` はいずれも `:robotics false`(非physical
仲介業)だったが、本 actor は**熟考の上で `:robotics true`** とした:
穀物エレベーターのコンベア(オーガー・ベルト・計量荷積み)は fueltrade の
ラックバルブロボットに類する実在の物理自動化であり、正当な根拠がある。
一方、生体動物の積み込み自動化(シュート/ゲート制御)は実在するが、
実在する動物福祉輸送法により**常に人間が立ち会う**もので、無人ロボットの
主張はしない -- この非対称性を README/business-model.md/ADR に明記した。
直近の兄弟が false だったからといって思考停止で false にせず、ドメインごと
に個別判断した点を本 ADR に記録する。

### 4. 法域カタログ(4法域、動植物それぞれの引用、ADR起票時に WebSearch で
裏取り済み)

- **日本** — 植物防疫法(laws.e-gov.go.jp/law/325AC0000000151)/
  家畜伝染病予防法(laws.e-gov.go.jp/law/326AC1000000166)、いずれも
  農林水産省(MAFF)所管、異なる出先機関網
- **米国** — Plant Protection Act(7 U.S.C. §7701 et seq.)/ Animal Health
  Protection Act(7 U.S.C. §8301 et seq.)、いずれも APHIS(USDA)所管
- **英国** — Plant Health 関連規則 / Animal Health Act 1981、APHA 所管
- **EU(ドイツ代表)** — Regulation (EU) 2016/2031(植物健康法)/
  Regulation (EU) 2016/429(Animal Health Law)

**要確認事項**: 英国の Plant Health 規則の正式名称の細部、ドイツ州レベルの
施行法令名は実装エージェント・本ADR起票時ともに未検証(独立した web
verification が望ましい)。ヘッドラインとなる法令・所管機関はいずれも
WebSearch で実在確認済み。

## Consequences

- (+) `kotoba-lang/industry` registry の 4620 スロットが実装へ昇格
  (`M6910`・`isic-8291`・`isic-4690`・`isic-4610`・`isic-6311` に続く)。
- (+) trading 系 actor 群に「プリンシパル型 × バイオセキュリティ規制」
  という3つ目の下位パターンを追加。単一ISICコードが2つの規制体系を
  束ねる場合の設計解(kind別チェック分離)を確立した。
- (+) `:robotics` を機械的にコピーせず domain ごとに再考した先例になった。
- (+) `clojure -M:dev:test`: 41 tests / 212 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモも植物系・動物系それぞれの
  クリーンシナリオ + 両証明書欠落 HARD hold を含め end-to-end 確認済み。
- (-) R0 の法域カバレッジは4法域のみ。英国/ドイツの施行法令細部は要検証。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`--entry industry` 最小 diff)。`cloud-itonami-isic-4620` は standalone
  (manifest/repos.edn には登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4620/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対)
- `90-docs/adr/2607110600-cloud-itonami-isic-4690-shosha-general-trading-actor.md`
- `90-docs/adr/2607111000-cloud-itonami-isic-4610-commission-broker-actor.md`
- `orgs/cloud-itonami/cloud-itonami-isic-4671/docs/business-model.md`
  (governed-trading パターンの原型)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4620" エントリ)
