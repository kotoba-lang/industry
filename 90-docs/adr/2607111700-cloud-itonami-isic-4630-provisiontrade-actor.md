# ADR-2607111700: cloud-itonami-isic-4630 — 加工食品・飲料・たばこ卸売を ProvisionTradeAdvisor ⊣ :provision-trading-governor で実装する食品安全+物品税規制 actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4690`(総合商社)・`cloud-itonami-isic-4610`(仲立)・
`cloud-itonami-isic-4620`(農産物原材料・生体動物)に続く fleet 継続タスク
として ISIC 4630「Wholesale of food, beverages and tobacco」を選定した。
4620 が**未加工**の農産物原材料・生体動物(バイオセキュリティ規制)だった
のに対し、4630 は**加工・包装済み**の食品・飲料(アルコール含む)・たばこ
製品の卸売という、下流(downstream)の別業態である。死んだ
`gftdcojp/cloud-itonami-G4630` URL のまま `:spec` で放置されていた。

## Decision

新規 actor `cloud-itonami-isic-4630` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。`cloud-itonami-isic-4620`(プリンシパル型 +
バイオセキュリティのkind分割チェック)の直接の設計継承だが、ISIC 4630が
束ねる3つの規制体系(食品安全・酒税/アルコール規制・たばこ税/年齢確認)を
**さらに精緻な多対一マッピングで分割**した。

### 1. ProvisionTradeAdvisor ⊣ :provision-trading-governor(単一不変条件)

> **ProvisionTradeAdvisor は、`:provision-trading-governor` が拒否する
> `:delivery/dispatch`・`:invoice/settle` を決して行わない。**

### 2. `:consignment-category` の多対一規制クラスマッピング(設計上の核心判断)

4620 が `:consignment-kind`(`:plant`/`:animal`)を1対1で2つのHARDチェック
に対応させたのに対し、4630 は4つの consignment category
(`:food`/`:beverage-non-alcoholic`/`:beverage-alcoholic`/`:tobacco`)を
**3つの規制クラスへ多対一でマッピング**した(`provisiontrade.facts/
regulatory-class-for`):

- `:food` と `:beverage-non-alcoholic` は**同一の** `food-safety-
  certificate-missing` チェックを共有する -- 種付けした全法域において
  非アルコール飲料は食品と同じ一般食品安全法規の下にあり、別建ての物品税
  体系を持たないため
- `:beverage-alcoholic` は専用の `alcohol-excise-license-missing` チェック
- `:tobacco` は専用の `tobacco-excise-age-verification-missing` チェック
  (物品税登録と年齢確認という2つの実在する要件を、どちらが欠けても
  たばこの発送は不安全という理由で1つの named rule に畳み込んだ)

検討した2つの代替案(単一の汎用チェック / 4カテゴリそれぞれに専用チェック)
を却下した理由も含め、実装側 ADR(`docs/adr/0001-architecture.md` Decision
4)に詳述されている。

| # | チェック | 種別 | 適用 |
|---|---|---|---|
| 1 | no-spec-basis | HARD | 全カテゴリ |
| 2 | evidence-incomplete | HARD | 全カテゴリ |
| 3 | credit-uncleared | HARD | 全カテゴリ |
| 4 | contract-missing | HARD | 全カテゴリ |
| 5 | food-safety-certificate-missing | HARD | food / beverage-non-alcoholic |
| 6 | alcohol-excise-license-missing | HARD | beverage-alcoholic |
| 7 | tobacco-excise-age-verification-missing | HARD | tobacco |
| 8 | counterparty-sanctions-flag-unresolved | HARD(常時) | 全カテゴリ |
| 9 | already-dispatched / already-invoiced | HARD | 全カテゴリ |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | `:delivery/dispatch`/`:invoice/settle` は常に人間承認 |

### 3. Robotics premise: true(4620 と同じ再考プロセス、コピーではない)

食品・飲料・たばこの卸売配送センターは実在の AS/RS・ロボットケース
ピッキング/パレタイジング・AGV フォークリフトを運用する(Symbotic 採用
DC・Ocado 等が実例)。ロボットの役割は卸売業者自身の dock でのピック
・パック・パレタイズ・ステージングであり、実際の `:delivery/dispatch`
地点にとどまる -- 長距離輸送キャリアの区間は明示的にスコープ外。

### 4. 法域カタログ(4法域、各カテゴリの引用、ADR起票時に WebSearch で
裏取り済み。1件、実装側の誤りを本ADR起票時に訂正)

- **日本** — 食品衛生法(厚生労働省)/ 酒税法(国税庁)/ たばこ事業法
  (財務省)
- **米国** — Food Safety Modernization Act(Pub. L. 111-353、FDA)/
  Federal Alcohol Administration Act(TTB)/ Family Smoking Prevention
  and Tobacco Control Act(Pub. L. 111-31、FDA Center for Tobacco
  Products + TTB)
- **英国** — Food Safety Act 1990(FSA)/ **Finance (No. 2) Act 2023
  Part 2 (Alcohol Duty)**(HMRC)/ Tobacco Products Duty Act 1979
  (HMRC + Trading Standards)。**訂正**: 実装エージェントは当初
  Alcoholic Liquor Duties Act 1979 を引用したが、同法は2023年8月1日、
  Finance (No. 2) Act 2023 第113条(1)により**廃止済み**であることを
  ADR起票時の WebSearch で確認し、現行の Alcohol Duty 体系(標準化
  ABVバンド課税)を定める Finance (No. 2) Act 2023 Part 2 に訂正した。
  registry.edn のコメントにもこの訂正を明記した。
- **EU(ドイツ代表)** — Regulation (EC) No 178/2002(General Food Law、
  EFSA)/ Directive 92/83/EEC(アルコール物品税)/ Directive 2011/64/EU
  (たばこ物品税)

**要確認事項**: 実装エージェント自身が、米国コードの正確な pin-cite・
英国/ドイツの正確な施行法令名について確信度がやや低いと明記している。

## Consequences

- (+) `kotoba-lang/industry` registry の 4630 スロットが実装へ昇格。
- (+) 4620 の「kind分割」パターンを「多対一の規制クラスマッピング」へ
  一般化した -- 1つの ISIC コードが複数の下位カテゴリと複数の規制体系を
  束ねる場合の設計解のレパートリーが増えた。
- (+) ADR起票プロセス自体が実装側の引用ミス(廃止済み英国法)を独立検証で
  発見・訂正した実例になった -- 「未検証で組み込み、登録前に必ず
  WebSearch で裏取りする」というこの fleet の品質ゲートが機能した証跡。
- (+) `clojure -M:dev:test`: 47 tests / 259 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモは4カテゴリそれぞれのクリーン
  シナリオ + 8種類の HARD hold(カテゴリ別に異なる rule keyword で記録)
  を確認済み。
- (-) R0 の法域カバレッジは4法域のみ。米国コード pin-cite・英独施行法令
  細部は要検証。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`--entry industry` 最小 diff)。`cloud-itonami-isic-4630` は
  standalone(manifest/repos.edn には登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4630/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対、
  Decision 4 に規制クラスマッピングの詳細、Decision 10 に robotics 判断)
- `90-docs/adr/2607111100-cloud-itonami-isic-4620-agri-trading-actor.md`
  (kind分割パターンの直接の先例)
- `90-docs/adr/2607111000-cloud-itonami-isic-4610-commission-broker-actor.md`
- `90-docs/adr/2607110600-cloud-itonami-isic-4690-shosha-general-trading-actor.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4630" エントリ)
