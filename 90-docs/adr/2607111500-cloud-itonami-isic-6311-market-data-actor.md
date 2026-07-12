# ADR-2607111500: cloud-itonami-isic-6311 — マルチアセット market-data 集約・ホスティングを MarketData-LLM ⊣ MarketDataGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーから「今の cloud-itonami, kotoba-lang で実世界の株価・為替・
コモディティ・暗号資産・不動産などの market 情報を収集・保持・更新する
repo/actor は設計されているか」と問われ、調査の結果**設計されていない**と
判明した:

- `kotoba-lang/securities` の README は「No network, no I/O, and **no real
  market data or custody integration** — an operator supplies their own
  licensed price feed」と明記し、market データそのものは明示的にスコープ
  除外していた。
- compat-catalog 内の `com-alpaca`/`com-coinbase`/`com-bloomberg-commodities`
  等はテンプレ生成のスキーマ+汎用CRUD stub のみで、外部フィードへ実際に
  問い合わせるロジックが存在しなかった。
- `90-docs/adr/2607031600-cloud-murakumo-gpu-fleet-requirements.md` は
  `fx-yen-per-usd` 等の実相場接続の必要性を認識しつつ「更新経路未定義」と
  明記していた。

`kotoba-lang/industry` registry の未着手 `:spec` スロットから対象を選定した。
ISIC Rev.4 6311「Data processing, hosting and related activities」はデータ
処理・ホスティング業一般を指すコードだが、既存の実装済み隣接コード(6611
取引所運営、6612 証券/コモディティ仲介業)がいずれも「市場の運営」「仲介」
であって「市場データそのものの収集・保持・配信」ではないため、
`cloud-itonami-isic-4610`(6619→Card Transaction Processing の narrowing と
同型)の前例に倣い、6311 を**マルチアセット市場データ集約・ホスティング
サービス**へ narrow した。死んだ `gftdcojp/cloud-itonami-J6311` プレース
ホルダー URL のまま `:spec` で放置されていたスロットである。

## Decision

新規 actor `cloud-itonami-isic-6311`(ISIC Rev.4 6311、narrowed）を
`cloud-itonami` org 直下に public/AGPL-3.0-or-later で新設する。
`cloud-itonami-isic-8291`(Dossier-LLM を DisclosureGovernor で封じ込めた
「収集・保持・契約者限定開示」構図)を直接の手本としつつ、市場データ固有の
リスク面(桁間違い/フェイクプリント、取引停止中銘柄への誤配信)に対応する
新規 HARD チェックを1つ追加した。

### 1. MarketData-LLM ⊣ MarketDataGovernor(単一不変条件)

> **MarketData-LLM は、MarketDataGovernor が拒否する価格の取込
> (`:quote/ingest`)・系列公開(`:series/derive`)・開示(`:disclosure/query`)・
> 訂正確定(`:correction/request`)を決して行わない。**

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | rbac | HARD | actor-role(feed-operator/data-quality-officer/subscriber)が operation の権限を持つか |
| 2 | **tolerance-gate**(新規、market-data 固有) | HARD | `:quote/ingest` の新価格が直近既知良好値から 15% を超えて乖離したら拒否。確信度に関わらず — 桁間違い/フォーマット崩れの構造的防御。他の cloud-itonami actor に存在しない、価格データという業態固有のリスク面 |
| 3 | source-provenance-gate | HARD | 出典クラスが `marketdata.facts/allowed-source-classes` に無ければ拒否。`:licensed-operator-feed` は加えてアクティブかつアセットクラス対応の `feed-license` を要求 |
| 4 | licensed-disclosure | HARD | 有効な契約(tenant×tier)が無い、または開示列が tier を超えたら拒否 |
| 5 | 確信度フロア | SOFT | `:confidence < 0.6` → escalate |
| 6 | **halted-instrument gate** | SOFT | 対象銘柄が取引停止/サーキットブレーカー中 → 必ず人間承認(dossier の high-stakes gate の写像) |
| 7 | correction-request | SOFT(無条件) | データ品質紛争は確信度に関わらず常に人間レビュー、どの phase でも auto 化しない |

**意図的に無い項目**: 与信/カウンターパーティチェックに相当するものは
存在しない — この actor は価格データの収集・保持・配信のみを行い、注文
執行・カストディ・約定を一切含まないため(trading/brokerage 系 actor から
の安易な流用を避けた証跡として ADR に明記)。

### 2. Phase 0→3 + 恒久人間ゲート

`dossier`/`talent`/`commtrade` と同型: `:disclosure/query` のみ phase 0 から
governor ゲート付きで許可、`:quote/ingest`/`:series/derive` は phase 3 で
governor-clean かつ高確信なら auto-commit 可能、`:correction/request` は
どの phase の `:auto` 集合にも入らない構造的恒久ゲート。

### 3. R0 の正直なスコープ(捏造禁止)

`dossier` の「6つの実在公開一次情報源のみ」の discipline に倣い、出典
カタログ(`src/marketdata/facts.cljc`)は実在する3つの自由・公式参照ソース
(ECB euro FX reference rates、US EIA Open Data、FRED Case-Shiller HPI)+
1つの構造的クラス `:licensed-operator-feed`。株式/暗号資産/大半のコモディ
ティの生きた気配値は、`kotoba-lang/securities` と同じ境界により、operator
が自前のライセンス済みフィードを `feed-license` レコードとして登録して
初めて取込可能 — 無料の公式ソースを偽装しない。`facts/coverage` が常に
正直に現状を報告する。

### 4. Robotics premise: false

配送・実物資産の移動を伴わない、価格データの収集・保持・配信のみの
デジタルサービスであり、actor の境界の外に物理的な作動は存在しない。

## Consequences

- (+) `kotoba-lang/industry` registry の 6311 スロットが `:spec`(死んだ
  `gftdcojp/cloud-itonami-J6311` URL)から実装へ昇格(`M6910`・`isic-8291`・
  `isic-4690`・`isic-4610` に続く5件目)。
- (+) `kotoba-lang/securities`(実データ・カストディ統合なし、operator が
  ライセンス済みフィードを供給する境界)が前提としていた「市場データ層」が
  初めて実装され、`:market-data` capability として他 blueprint(securities
  含む)から wholesale 消費できる形になった。
- (+) tolerance-gate という、他の cloud-itonami actor に存在しない
  market-data 固有の HARD チェックを新設し、単純な流用ではなく業態の
  構造的差異(桁間違い/fat-finger 耐性)を反映したことを ADR に明記した。
- (+) `clojure -M:dev:test`: 32 tests / 137 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも
  end-to-end で確認済み(ECB参照レート更新→commit、出典なしtick→hold、
  tier超過/未契約開示→hold ×2、取引停止銘柄への取込→人間承認→commit、
  データ品質訂正→人間承認→commit、許容乖離超過→hold の7シナリオ全て正しく
  発火)。
- (-) R0 の自由公式ソースは3種のみ(FX/コモディティ/不動産指数の一部)。
  株式・暗号資産・大半のコモディティは operator の feed-license 登録が
  必須で、この actor 単体では取込できない。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。実運用の
  取引所/ベンダーとのフィード契約は operator の責任範囲。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`manifest/west.yml` の `industry` entry、`--entry industry` の最小
  diff、コミット `3f3f83a3f1a7`)。`cloud-itonami-isic-6311` は既存の
  `cloud-itonami-{ISIC}` blueprint 群と同じ慣例により `manifest/repos.edn`
  には登録しない(standalone、plain-git 子リポ)。

## 代替案と不採用理由

- **6611(取引所運営)/6612(証券・コモディティ仲介業)のスロットを流用**:
  両者は既に実装済みで、かつ業態が「市場の運営」「仲介」であって「データ
  そのものの収集・保持・配信」ではない。市場データ集約は独立した業態
  (D&B が corporate intelligence と別業態であるのと同型)として 6311 を
  選ぶのが正確。
- **LLM に取込・公開権限を直接付与(エージェント自律)**: 速いが、出典なき
  断定・桁間違い価格の流通・停止銘柄への誤配信を構造的に防げない。単一
  不変条件(決定1)に反する。
- **tolerance-gate を SOFT(escalate)にとどめる**: 桁間違い/フォーマット
  崩れは確信度と無関係に起きるため、SOFT では低確信フィルタをすり抜ける
  高確信の誤値を止められない。HARD が必須と判断した。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-6311/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対)
- `90-docs/adr/2607111000-cloud-itonami-isic-4610-commission-broker-actor.md`
  (narrowing 手法の直接の手本)
- `90-docs/adr/2607110400-cloud-itonami-isic-8291-corporate-compliance-intelligence-actor.md`
  (収集・保持・契約者限定開示パターンの直接の手本、spec→実装昇格の先例)
- `orgs/kotoba-lang/securities/README.md`(operator-supplies-own-feed 境界)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "6311" エントリ)
