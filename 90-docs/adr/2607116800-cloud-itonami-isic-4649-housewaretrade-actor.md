# ADR-2607116800: cloud-itonami-isic-4649 — その他家庭用品卸売を HousewareTradeAdvisor ⊣ :consumer-product-safety-governor で実装する消費者製品安全 actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4652`(電子・通信機器、連言チェック、
ADR-2607115400)に続く fleet 継続タスクとして ISIC 4649「Wholesale of
other household goods」を選定した。死んだ `gftdcojp/cloud-itonami-G4649`
URL のまま `:spec` で放置されていた。

## Decision

新規 actor `cloud-itonami-isic-4649` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。米国消費者製品安全委員会(CPSC)規制を
規制の重心とする点で4653(製品コンプライアンス)と類似の系譜だが、
**耐久資本財(農業機械)ではなく消費財(家庭用品)**であり、規制メカニズム
そのものの形が異なる。

### 1. HousewareTradeAdvisor ⊣ :consumer-product-safety-governor(単一
不変条件)

> **HousewareTradeAdvisor は、`:consumer-product-safety-governor` が
> 拒否する `:delivery/dispatch`・`:invoice/settle` を決して行わない。**

### 2. 出荷前証明書 vs. 事後的リコール -- 2つの本質的に異なるチェック形状
を強制的に一つの型にはめ込まなかった(設計上の核心判断)

実装エージェントは以下の2つを**意図的に別の形状**として実装した:

- `childrens-product-certificate-missing`: **出荷前ゲート**。
  `:childrens-product?` でゲートされ、鉛/フタル酸エステル検査と
  CPC(Children's Product Certificate)登録という同一証明手続きの
  2つの証拠アームを1つのルールに畳み込む(4662金属卸売の「1事実畳み
  込み」形状に近い)。`:delivery/dispatch` でのみ評価。
- `active-recall-unresolved`: **再評価される三値enum**
  (`:recall-status` -- `:none`/`:open`/`:resolved`、ブール値ではない)。
  `:delivery/dispatch` と `:invoice/settle` の**両方**で評価される。
  理由: CPSA第15条(b)項(15 U.S.C. §2064(b))は「一度クリーンだと証明
  すれば済む」義務ではなく「継続的にモニタリングする」義務である --
  リコールは製品が既に流通した**後**に発見される事後的事実であり、
  出荷前証明書のパターンを強制的に当てはめると規制の実態を正しく
  反映しないと判断した。同一SKUでの open→resolved の全ライフサイクル
  を、既存の `:order/intake` patch 経路(新規op不要)でテストとデモの
  両方で実証している。**明示的な注記**: `:recall-status` は
  isic-6492で過去に発見された「`:status` 値を二重actuationガードに
  誤用するバグパターン」とは別物であり、二重actuationガードは引き続き
  専用の `:dispatched?`/`:invoiced?` ブール値のままであると
  ドキュメントに明記している。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1-4 | no-spec-basis / evidence-incomplete / credit-uncleared / contract-missing | HARD | 兄弟共通 |
| 5 | **childrens-product-certificate-missing** | HARD | `:childrens-product?` でゲート、出荷前1回 |
| 6 | **active-recall-unresolved** | HARD | 三値enum、発送・請求の両方で再評価 |
| 7 | counterparty-sanctions-flag-unresolved | HARD | 汎用OFAC |
| 8-9 | already-dispatched / already-invoiced | HARD | 二重actuation防止(recall enumとは別軸) |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | 兄弟共通 |

### 3. Robotics premise: true(4653からの意図的な離脱)

4653(農業機械)は `:robotics false` としたが、4649は熟考の上で
`:robotics true` とした -- 理由: 住宅用品・小型家電・玩具はカートン
/パレット規模の商品で、実在の商業配送センターにおける AS/RS・
ロボットケースピッキング装置の対象となる(4653の自走式/牽引式大型
機械のような、ゲート可能な固定装置が存在しない状況とは異なる)。
直近の兄弟の値を機械的にコピーしなかった判断のもう1つの実例。

### 4. 法域カタログ(ADR起票時に WebSearch で裏取り済み、日本の10日
報告期限まで一致)

- **米国** — CPSIA(Consumer Product Safety Improvement Act of 2008、
  Pub. L. 110-314)/ 15 U.S.C. §1278a(鉛100ppm上限)、16 CFR Part
  1303(鉛塗料)/ Part 1307(フタル酸エステル0.1%上限)、CPSA第14条
  /16 CFR Part 1107+1110(CPC第三者検査証明)、CPSA第15条(b)項/
  15 U.S.C. §2064(b)(24時間以内のリコール報告義務)、16 CFR Part
  1632/1633(マットレス難燃性基準)
- **日本** — 消費生活用製品安全法(経済産業省・消費者庁、PSCマーク、
  重大製品事故を知った日から**10日以内**の報告義務 -- 実装エージェント
  が中程度の確信度と明記していた数値を本ADR起票時のWebSearchで正確に
  確認)

**意図的な除外**: EU General Product Safety Regulation (EU) 2023/988
は範囲の不確実性を理由に意図的にseedしなかった(水増ししない正直さ)。

## Consequences

- (+) `kotoba-lang/industry` registry の 4649 スロットが実装へ昇格。
- (+) 「出荷前証明書」対「事後的継続監視義務」という、規制メカニズムの
  時間的性質そのものに基づくチェック形状の分岐を fleet に導入した --
  これまでの「法域ゲート/型ゲート/連言」という空間的な分岐とは異なる
  軸。
- (+) `:robotics` を4653から機械的にコピーせず、消費財と資本財の物理
  実務の違いに基づいて再考した実例をさらに1つ積み上げた。
- (+) `clojure -M:dev:test`: 41 tests / 217 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモは同一SKUでのリコール
  open→resolvedの全ライフサイクルをEnd-to-Endで確認済み。
- (-) 米国の全州共通の家具張り生地難燃性基準は前提としていない(実装
  エージェントが明記)、EU GPSR 2023/988は範囲不確実性のため未seed。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`nbb scripts/gen-west-manifest.cljs --entry industry` 最小 diff)。
  `cloud-itonami-isic-4649` は standalone(manifest/repos.edn には
  登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4649/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対、
  Decision に出荷前証明書 vs. 事後的リコールの設計理由)
- `90-docs/adr/2607115000-cloud-itonami-isic-4653-agmachtrade-actor.md`
  (対比対象、耐久資本財の製品コンプライアンス)
- `90-docs/adr/2607115400-cloud-itonami-isic-4652-telecomtrade-actor.md`
  (対比対象、連言チェック形状)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4649" エントリ)
