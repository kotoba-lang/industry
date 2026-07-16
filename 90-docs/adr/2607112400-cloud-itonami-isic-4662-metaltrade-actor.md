# ADR-2607112400: cloud-itonami-isic-4662 — 金属・金属鉱石卸売を MetalTradeAdvisor ⊣ :metal-trading-governor で実装する紛争鉱物トレーサビリティ actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4690`(総合商社)・`cloud-itonami-isic-4610`(仲立)・
`cloud-itonami-isic-4620`(農産物・生体動物)・`cloud-itonami-isic-4630`
(加工食品・飲料・たばこ)に続く fleet 継続タスクとして ISIC 4662
「Wholesale of metals and metal ores」を選定した。死んだ
`gftdcojp/cloud-itonami-G4662` URL のまま `:spec` で放置されていた。

## Decision

新規 actor `cloud-itonami-isic-4662` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。プリンシパル型トレーディング(4671/4690/
4620/4630 と同じ)だが、規制の重心が**「取引が行われる法域」ではなく
「商品そのものの来歴(chain of custody)」**である点が、これまでの全ての
兄弟actorと本質的に異なる。

### 1. MetalTradeAdvisor ⊣ :metal-trading-governor(単一不変条件)

> **MetalTradeAdvisor は、`:metal-trading-governor` が拒否する
> `:delivery/dispatch`・`:invoice/settle` を決して行わない。**

### 2. 紛争鉱物トレーサビリティチェック(設計上の核心判断)

`conflict-minerals-provenance-unverified` チェックは、これまでの兄弟
actor 群のどのチェックとも異なる評価軸を持つ: **法域ではなく金属種別に
よって発火するかどうかが決まり、かつ法域を問わず一律評価される**。
`:metal-type` が 3TG(tin/tantalum/tungsten/gold)+ cobalt のいずれかの
場合のみ発火し、`:chain-of-custody-documented?`(鉱山までの来歴文書)と
`:conflict-free-smelter-certified?`(RMI/RMAP 型の精錬所認証)の**両方**
を要求する。銅・鉄鉱石・アルミ等の base metal では常に NO-OP(テストで
gold と copper の control pair により直接実証)。

**cobalt の扱いについて**: 米国 Dodd-Frank 法1502条・EU規則2017/821の
いずれも法的にはコバルトをカバーしていない(3TGのみが法定対象)。実装
エージェントはコバルトを OECD Due Diligence Guidance のミネラル非依存的
な枠組みとコンゴ民主共和国の零細鉱業における実在する人権懸念の文献を根拠
に、**独自のポリシー拡張**としてスコープに含めたと明記しており、これを
「実定法上の要求」であるかのように装っていない点を本ADRでも踏襲する。

| # | チェック | 種別 | 適用 |
|---|---|---|---|
| 1 | spec-basis-violations | HARD | 法域が spec-basis を持たない |
| 2 | evidence-incomplete-violations | HARD | 汎用エビデンスチェックリスト未完了 |
| 3 | credit-uncleared-violations | HARD | 与信未クリア |
| 4 | contract-missing-violations | HARD | 契約条件未記録 |
| 5 | **conflict-minerals-provenance-unverified-violations** | HARD | `:metal-type` が3TG+コバルトの場合のみ、法域を問わず一律発火 |
| 6 | counterparty-sanctions-flag-unresolved-violations | HARD(常時) | 発送・請求とも無条件 |
| 7 | already-dispatched-violations / already-invoiced-violations | HARD | 二重actuation防止 |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | `:delivery/dispatch`/`:invoice/settle` は常に人間承認 |

### 3. Robotics premise: true

バルク鉱石(スタッカー・リクレーマー/コンベアによるバルクターミナル
自動化)と精製金属(LME型倉庫でのオーバーヘッドクレーン/AGVフォーク
リフト自動化)の2つの物理形態について個別に検討し、いずれも卸売業者
自身の発送地点で完結する実在の自動化根拠があると判断した。

### 4. 2つの独立カタログ(一般貿易 vs 紛争鉱物、ADR起票時に WebSearch
で裏取り済み)

**一般貿易カタログ**(4法域、燃料固有の物品税引用を再利用せず独自に選定):
日本(関税法/輸出貿易管理令、MOF/METI)、米国(Tariff Act of 1930、
CBP+OFAC)、英国(Taxation (Cross-border Trade) Act 2018、HMRC+SAMLA
2018/OFSI)、EU(ドイツ代表、Union Customs Code Reg.(EU)952/2013、
Zoll/BMF)。

**紛争鉱物カタログ**(4法域中2法域のみ実定法を持つ、正直な報告):
米国(Dodd-Frank法1502条、15 U.S.C. §78m note、SEC Rule 13p-1)/
EU(Regulation (EU) 2017/821)が実在の拘束力ある規制。それ以外の法域は
OECD Due Diligence Guidance for Responsible Supply Chains of Minerals
from Conflict-Affected and High-Risk Areas(第3版、2016年)を非拘束の
運用ベースラインとして代替使用する -- これは Dodd-Frank 1502条・EU
2017/821 いずれもが実際に参照する国際標準であり、実装エージェントの
選択は妥当である。

**要確認事項(実装エージェント自身が明記、本ADRでも踏襲)**: ドイツの
EU規則2017/821所管当局としての BAFA の位置づけは WebSearch でも確定的
に確認できず未検証(該当箇所には断定せず「未検証」と明記)。米国コード
の正確な関税法 pin-cite、英国のポストBrexit関税相互参照の細部も同様。

## Consequences

- (+) `kotoba-lang/industry` registry の 4662 スロットが実装へ昇格。
- (+) trading 系 actor 群に「法域軸ではなく商品来歴軸で評価するチェック」
  という新しい設計パターンを追加した -- これまでの兄弟(法域スペックの
  有無、カテゴリ別規制クラス)とは異なる第三の評価軸。
- (+) 政策的スコープ拡張(コバルト)を実定法の主張と明確に区別して記録
  した先例になった。
- (+) `clojure -M:dev:test`: 41 tests / 208 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモは錫のクリーンシナリオ+6種類
  のHARD hold+金vs銅のcontrol pairをEnd-to-Endで確認済み。
- (-) R0 の紛争鉱物カバレッジは2法域のみが実定法。BAFA所管認定・米国
  pin-cite・英国相互参照は要検証。
- (-) 登録時、`kotoba-lang/industry` main が並行の重工業プロモーション
  (ISIC 2824)によって先行しており、テストのカウント行(120)が textual
  conflict を起こした。ローカル解決ではなく最新 main からの再ブランチ+
  cherry-pick で解消し(121に更新)、`--force` は使用していない。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`--entry industry` 最小 diff)。`cloud-itonami-isic-4662` は
  standalone(manifest/repos.edn には登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4662/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対、
  Decision 10 に robotics 判断)
- `90-docs/adr/2607111700-cloud-itonami-isic-4630-provisiontrade-actor.md`
- `90-docs/adr/2607111100-cloud-itonami-isic-4620-agri-trading-actor.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4662" エントリ)
