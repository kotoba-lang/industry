# ADR-2607113100: cloud-itonami-isic-4641 — 繊維・衣料・履物卸売を TextileTradeAdvisor ⊣ :textile-trading-governor で実装する強制労働サプライチェーン actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4662`(金属・金属鉱石、紛争鉱物トレーサビリティ、
ADR-2607112400)に続く fleet 継続タスクとして ISIC 4641「Wholesale of
textiles, clothing and footwear」を選定した。死んだ
`gftdcojp/cloud-itonami-G4641` URL のまま `:spec` で放置されていた。

## Decision

新規 actor `cloud-itonami-isic-4641` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。プリンシパル型トレーディング + サプライ
チェーン来歴チェックという点では4662金属の直系だが、**チェックの発火条件
の形が本質的に異なる**。

### 1. TextileTradeAdvisor ⊣ :textile-trading-governor(単一不変条件)

> **TextileTradeAdvisor は、`:textile-trading-governor` が拒否する
> `:delivery/dispatch`・`:invoice/settle` を決して行わない。**

### 2. 強制労働推定(rebuttable presumption)のモデル化(設計上の核心判断)

4662金属の `conflict-minerals-provenance-unverified` チェックは
**法域を問わず**金属種別のみで発火する(jurisdiction-unconditional)。
対して4641の `forced-labor-presumption-unrebutted` チェックは
**法域がゲート条件そのもの**である(jurisdiction-gated) -- これは実装
エージェントが意図的に選んだ設計差分であり、単純なコピーではない:

> UFLPA(Uyghur Forced Labor Prevention Act)は米国の**国境での輸入
> 差止めメカニズム**であり、Dodd-Frank 1502条/EU規則2017/821のような
> 「サプライチェーンのどこかに存在すれば発生する開示義務」とは性質が
> 異なる。よって:
> - **発火条件**: (a) 注文の法域が拘束力ある強制労働輸入禁止法を持つ
>   (現状: 米国のみ)**かつ** (b) 原産地域/エンティティリストが該当
>   (新疆ウイグル自治区 / UFLPA Entity List / CAATSA 321(b)条 北朝鮮)
> - **反証(rebuttal)**: `:supply-chain-traceability-documented?` と
>   `:forced-labor-rebuttal-evidence-on-file?` の**両方**が true でなければ
>   hold

**「本当に反証可能」であることの実証**: 同一の新疆原産で証拠なし(to-6)
はHARD hold、同一原産で両エビデンスありの(to-7)はクリーン発送 --
包括的な地域禁輸ではないことをcontrol pairで証明。**「法域ゲート付き」
であることの実証**: 同一の未反証新疆原産でも `:jurisdiction "USA"`
(to-6相当)はhold、`:jurisdiction "JPN"`(拘束力ある成文法が未種付け)
はクリーン発送(to-8) -- こちらも包括的な地域禁輸ではないことを別の
control pairで証明。実装側 ADR(Decision 4)に3つの却下案とともに詳述。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1-4 | no-spec-basis / evidence-incomplete / credit-uncleared / contract-missing | HARD | 兄弟共通 |
| 5 | **forced-labor-presumption-unrebutted** | HARD | 法域が拘束力ある強制労働輸入禁止法を持ち、かつ原産地域/エンティティが該当する場合のみ発火(法域ゲート付き) |
| 6-8 | counterparty-sanctions-flag-unresolved / already-dispatched / already-invoiced | HARD | 兄弟共通 |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | 兄弟共通 |

### 3. Robotics premise: true

ハンガー吊り下げ衣料のGOH(garment-on-hanger)ソーティングコンベア +
履物・畳み繊維向けpick-to-light/AS-RS、いずれも卸売業者自身の発送地点で
完結する実在の倉庫自動化。

### 4. 法域/フレームワークカタログ(ADR起票時に WebSearch で裏取り済み、
すべて正確)

- **米国** — UFLPA(Pub. L. 117-78、2021年制定、rebuttable presumption
  発効日2022-06-21)+ 19 U.S.C. §1307(Tariff Act of 1930 §307 の
  現行法典化)、CBP執行。CAATSA第321条(b)項(北朝鮮国籍者による労働、
  地理的制限なし)も同じ rebuttable presumption 機構として seed。
  米国繊維固有法として Textile Fiber Products Identification Act
  (15 U.S.C. §70、FTC)/ Flammable Fabrics Act(15 U.S.C. §1191、CPSC)
  も引用。
- **EU** — Regulation (EU) 2024/3015(強制労働製品規則)。2024-12-13
  発効、主要規則の適用開始は2027-12-14 -- **意図的に `:binding? false`**
  として種付け(まだ発効前の規則を拘束力ありと誤登録しない正直さ)。
- **英国** — Modern Slavery Act 2015 は検討したが**意図的に不採用**とした
  (開示義務法であって国境執行メカニズムではないため、`forced-labor-
  presumption-unrebutted` の根拠に引用すると機構を誤って表現することに
  なる、と実装エージェントが明記。この判断を本ADRでも支持する)。
- 日本・英国には拘束力ある強制労働輸入禁止の成文法を seed していない --
  捏造せず正直な報告方針を踏襲。

## Consequences

- (+) `kotoba-lang/industry` registry の 4641 スロットが実装へ昇格。
- (+) trading 系 actor 群のサプライチェーン来歴チェックに、
  「法域無条件(4662)」と「法域ゲート付き(4641)」という2つの下位
  パターンを確立した。単純な relabel ではなく、実定法の機構的性質の
  違いから導かれた設計判断であることを両方向のcontrol pairで実証した。
- (+) まだ発効していない規制(EU 2024/3015)を `:binding? false` として
  正直に区別した先例になった。
- (+) `clojure -M:dev:test`: 44 tests / 202 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモは反証可能性・法域ゲートの
  両方をcontrol pairでEnd-to-Endに確認済み。
- (-) CAATSA 321条(b)項の正確な項番、EU 2024/3015の正確な適用日は
  実装エージェントが中程度の確信度と明記していたが、本ADR起票時の
  WebSearchでいずれも正確であることを確認できた。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`--entry industry` 最小 diff)。`cloud-itonami-isic-4641` は
  standalone(manifest/repos.edn には登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4641/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対、
  Decision 4 に発火条件設計、Decision 10 に robotics 判断)
- `90-docs/adr/2607112400-cloud-itonami-isic-4662-metaltrade-actor.md`
  (直接の手本、法域無条件パターン)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4641" エントリ)
