# ADR-2607111000: cloud-itonami-isic-4610 — 手数料/契約ベース卸売(仲立/コミッションブローカー)を CommTradeAdvisor ⊣ :commission-broker-governor で実装する agency/brokerage actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4690`(総合商社、ADR-2607110600)に続き、`kotoba-lang/
industry` registry の未着手 `:spec` スロットから次の対象を選定した。ISIC
4610「Wholesale on a fee or contract basis」は、既存2つの trading 系
actor(`cloud-itonami-isic-4671` 燃料商社、`cloud-itonami-isic-4690` 総合
商社)とは業態が本質的に異なる: 両者は**プリンシパル**(自ら商品を買って
売る、貸倒れ・在庫リスクを負う)であるのに対し、4610 は**エージェント/
ブローカー**(仲立人・コミッションブローカー、商品の所有権を一度も持たず、
売買当事者の間を仲介して手数料を得る)である。他の卸売系プレースホルダー
(4620/4630/4641/4649/4651/4652/4653/4659/4662/4663/4669 等)と同じ、
`gftdcojp/cloud-itonami-G4610` という死んだ旧URLのまま `:spec` で放置
されていた。

## Decision

新規 actor `cloud-itonami-isic-4610`(ISIC Rev.4 4610)を `cloud-itonami`
org 直下に public/AGPL-3.0-or-later で新設する。`cloud-itonami-isic-4690`
の直接の手本を踏襲しつつ、**プリンシパル型からエージェント型への構造転換**
を行った(単純な relabeling ではない)。

### 1. CommTradeAdvisor ⊣ :commission-broker-governor(単一不変条件)

> **CommTradeAdvisor は、`:commission-broker-governor` が拒否する
> `:deal/confirm`(2者間の成約確認)・`:commission/invoice`(自らの手数料
> 請求)を決して行わない。**

エージェント型の本質的な違いとして、`:deal/confirm` はブローカー自身が
商品や代金を動かすものではなく、2当事者(プリンシパル)間で直接決済される
成約の**記録**にすぎない。`:commission/invoice` も、原取引の決済ではなく
ブローカー自身の手数料請求である。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | spec-basis | HARD | 法域が `commtrade.facts/catalog` に spec-basis を持たない限り不許可 |
| 2 | evidence-incomplete | HARD | mandate/verify のエビデンスチェックリストが未完了なら不許可 |
| 3 | mandate-missing | HARD | 委任状/手数料条件(engagement letter, commission-terms)が台帳に無い |
| 4 | **principal-identity-unverified**(両サイド) | HARD | `:buyer-kyc-cleared?` **かつ** `:seller-kyc-cleared?` の両方が true でなければならない — fueltrade/shosha の単一カウンターパーティ与信チェックと異なり、ブローカーは両当事者のKYCに責任を持つ |
| 5 | **conflict-of-interest-undisclosed**(新規、brokerage 固有) | HARD | `:mandate-side` が `:dual`(買主・売主の両方から委任された双方代理)で `:dual-agency-disclosed?` が true でない場合に発火。プリンシパル型2兄弟には存在しない、仲立業の中核的規制関心そのもの(商法上、仲立人は当事者双方に対して中立・公平を保つ義務を負う) |
| 6 | counterparty-sanctions-flag-unresolved(両サイド) | HARD(常時評価) | `:buyer-sanctions-screened?` **かつ** `:seller-sanctions-screened?` の両方 |
| 7 | already-confirmed / already-invoiced | HARD | 二重成約・二重請求防止 |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | `:deal/confirm`/`:commission/invoice` は governor がクリーンでも常に人間承認へ |

**意図的に無い項目**: `credit-uncleared` に相当するチェックは存在しない
-- ブローカーは与信を供与せずポジションも取らないため、この概念自体が
本業態に適用されない(fueltrade/shosha からの安易な relabeling を避けた
証跡として ADR に明記)。

### 2. Phase 0→3 + 恒久人間ゲート

`fueltrade`/`shosha` と同型: `:mandate/intake` のみ phase 3 で auto-commit
可能、`:deal/confirm`/`:commission/invoice` はどの phase の `:auto` 集合
にも入らない構造的恒久ゲート。

### 3. 法域カタログ(正直な R0 スコープ、ADR起票時に WebSearch で裏取り済み)

- **日本** — 商法(明治32年法律第48号)第543条以下、仲立営業。仲立人は
  当事者双方に対して中立・公平を保つ義務を負う(conflict-of-interest
  チェックの実定法上の根拠そのもの)
- **英国** — The Commercial Agents (Council Directive) Regulations 1993
  (SI 1993/3053)、EU指令86/653/EEC の国内法化、Brexit後も assimilated law
  として存続
- **ドイツ** — Handelsgesetzbuch(HGB)§§84-92c、Handelsvertreter、同じく
  指令86/653/EEC に基づく
- **米国** — Perishable Agricultural Commodities Act(PACA)、7 U.S.C.
  §499a 以下、USDA AMS が commission merchants/dealers/brokers を免許制で
  規制。**正直なスコープ**: 生鮮農産物のみが対象で、米国の commission-agent
  取引全般をカバーする主張ではない(実装エージェント自身が明記)

### 4. Robotics premise: false

`cloud-itonami-isic-4690` よりさらに明確に、コミッションブローカーの
actuation 全体は書面/システム上の取引仲介であり、配送・決済とも2当事者間で
直接行われ actor の境界の外にある。

## Consequences

- (+) `kotoba-lang/industry` registry の 4610 スロットが `:spec`(死んだ
  `gftdcojp/cloud-itonami-G4610` URL)から実装へ昇格(`M6910`・
  `isic-8291`・`isic-4690` に続く4件目)。
- (+) trading 系 actor がプリンシパル型(fueltrade/shosha)とエージェント型
  (本actor)の両方をカバーする2つの下位パターンを持つことを示した。
  `conflict-of-interest-undisclosed`(dual-agency)という、プリンシパル型
  には存在しない brokerage 固有の HARD チェックを新設し、`credit-uncleared`
  相当を意図的に持たないことで、安易な relabeling ではなく業態の構造的差異
  を反映したことを ADR に明記した。
- (+) `clojure -M:dev:test`: 36 tests / 179 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも
  end-to-end で確認済み(clean intake→verify→confirm→invoice 一式 +
  6つの HARD hold シナリオ全て正しく発火)。
- (-) R0 の法域カバレッジは4法域のみ。米国は生鮮農産物(PACA)限定で、
  一般商品のコミッションブローカー規制はカバーしていない。
- (-) ドイツの制裁執行機関の帰属(Bundesbank/Zoll/新設 Central Office for
  Sanctions Enforcement の3機関併存)は簡略化されており、実装エージェント
  自身がこの点を要確認事項として明記している。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`manifest/west.yml` の `industry` entry、`--entry industry` の最小
  diff)。`cloud-itonami-isic-4610` は既存の `cloud-itonami-{ISIC}`
  blueprint 群と同じ慣例により `manifest/repos.edn` には登録しない
  (standalone、plain-git 子リポ)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4610/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対)
- `90-docs/adr/2607110600-cloud-itonami-isic-4690-shosha-general-trading-actor.md`
  (直接の手本、プリンシパル型 trading actor の一般化)
- `orgs/cloud-itonami/cloud-itonami-isic-4671/docs/business-model.md`
  (governed-trading パターンの原型)
- `90-docs/adr/2607110400-cloud-itonami-isic-8291-corporate-compliance-intelligence-actor.md`
  (spec→実装昇格の先例)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4610" エントリ)
