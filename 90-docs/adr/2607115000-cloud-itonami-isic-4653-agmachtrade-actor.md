# ADR-2607115000: cloud-itonami-isic-4653 — 農業機械・設備卸売を AgMachTradeAdvisor ⊣ :ag-equipment-governor で実装する製品コンプライアンス actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4651`(コンピュータ・周辺機器、デュアルユース輸出管理
分類、ADR-2607113600)に続く fleet 継続タスクとして ISIC 4653
「Wholesale of agricultural machinery, equipment and supplies」を選定
した。死んだ `gftdcojp/cloud-itonami-G4653` URL のまま `:spec` で放置
されていた。

## Decision

新規 actor `cloud-itonami-isic-4653` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。既存の `cloud-itonami-isic-4620`(農産物
原材料・生体動物、AgriTradeAdvisor)は**農作物・家畜そのもの**を扱う
バイオセキュリティactorだが、4653は**その農作業を行う機械**を扱う、
規制の性質が根本的に異なる actor である: 商品来歴/貿易制裁ではなく
**製品コンプライアンス**(排出ガス認証・転倒時保護構造(ROPS)安全認証)
がゲート条件になる。

### 1. AgMachTradeAdvisor ⊣ :ag-equipment-governor(単一不変条件)

> **AgMachTradeAdvisor は、`:ag-equipment-governor` が拒否する
> `:delivery/dispatch`・`:invoice/settle` を決して行わない。**

### 2. 排出ガス認証とROPS認証の独立分離(設計上の核心判断)

4651(techtrade)が「未分類 vs. ライセンス未取得」という2つの失敗モード
に分割したのに対し、4653は**さらに一歩進めて、2つのチェックをそれぞれ
独自のブール条件でゲート**した(1つの enum や1つの畳み込みルールでは
ない):

- `emissions-certificate-missing`: `:engine-powered?` でゲート(エンジン
  搭載機のみ発火)
- `rops-certification-missing`: `:ride-on?` でゲート(オペレーター搭乗型
  かつ転倒リスクがある機体のみ発火)

**両者が真に独立していることの実証**: 固定式エンジン駆動灌漑ポンプ
(エンジン搭載だが搭乗型ではない)は `emissions-certificate-missing`
のみでhold、`rops-certification-missing` は発火しない -- 逆(搭乗型だが
エンジン非搭載、既存の牽引式インプルメントが該当)も同様に一方のみ発火。
牽引式インプルメント(どちらの条件も満たさない)は両方 NO-OP でクリーン
発送、包括的な認証要求ではないことを実証。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1-4 | no-spec-basis / evidence-incomplete / credit-uncleared / contract-missing | HARD | 兄弟共通 |
| 5 | **emissions-certificate-missing** | HARD | `:engine-powered?` でゲート |
| 6 | **rops-certification-missing** | HARD | `:ride-on?` でゲート、5とは独立 |
| 7 | counterparty-sanctions-flag-unresolved | HARD | 汎用OFAC |
| 8-9 | already-dispatched / already-invoiced | HARD | 二重actuation防止 |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | 兄弟共通 |

### 3. Robotics premise: false(4651に続くfleet2例目の明示的却下)

4651(techtrade)は `:delivery/dispatch` のみtrueとするパス別判断を
下したが、4653はさらに踏み込んで **`:robotics` を丸ごとfalseとし、
`:required-technologies` から除いた**。理由: この actor の
`:delivery/dispatch` が実際にゲートする対象(トラクター・コンバイン・
インプルメント)は、実務上人間のオペレーターが運転またはロビー
牽引してヤードから出る -- ロボットコマンドをゲートできる固定装置が
存在しない。実装エージェントは大規模ディーラーグループの実在する自動
部品在庫検索を robotics true の根拠として検討したが、それは本 actor が
統治する op(`:delivery/dispatch`)とは**別のエンティティ**をゲートする
ため、根拠として採用しないという判断を下した -- fleet の「直近の兄弟の
値を機械的にコピーしない」原則の一貫した適用。

### 4. 法域カタログ(ADR起票時に WebSearch で裏取り済み、EU規則の正確な
施行日まで一致)

- **米国** — EPA 40 CFR Part 1039(オフロードディーゼルエンジン、
  Tier 1〜Tier 4 Final、Engine Family認証)、OSHA 29 CFR 1928.51
  (1976年10月25日以降製造のトラクターへのROPS義務化、SAE J2194/
  ASABE S519)、OFAC
- **EU(ドイツ代表)** — Regulation (EU) 2016/1628(Stage V オフロード
  排出ガス基準)、Machinery Regulation (EU) 2023/1230(Directive
  2006/42/ECを**2027年1月20日**に廃止・置換 -- 実装エージェントが
  不確実と明記していた正確な施行日を本ADR起票時のWebSearchで確認)

**要確認事項**: OECD Code 4(トラクター保護構造の公式静的試験
標準コード)とISO 3471の正確な適用範囲の細部は実装エージェント自身が
確信度をやや低めに明記していた。

## Consequences

- (+) `kotoba-lang/industry` registry の 4653 スロットが実装へ昇格。
- (+) 「製品コンプライアンス」という第三のトレーディング下位パターンを
  確立した(既存の「商品来歴」「貿易制裁」に加えて)。
- (+) 4651の「2つの技術的失敗モード分離」パターンをさらに一般化し、
  「2つの完全独立したブール条件ゲート」という、まだfleetに無かった
  形に発展させた。
- (+) `:robotics false` の明示的却下判断を2例目として記録し、
  「直近の兄弟の値を機械的にコピーしない」原則の実例を積み上げた。
- (+) `clojure -M:dev:test`: 38 tests / 192 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモは固定式エンジン駆動機と
  牽引式インプルメントのcontrol pairでEnd-to-Endに独立性を確認済み。
- (-) OECD Code 4/ISO 3471の適用範囲細部は要検証。
- (-) 本サイクルの登録は `kotoba-lang/industry` main の並行プロモーション
  (ISIC 3811)と textual conflict を起こし、再ブランチ+cherry-pickで
  解消(カウントを133に修正)。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`nbb scripts/gen-west-manifest.cljs --entry industry` 最小 diff)。
  `cloud-itonami-isic-4653` は standalone(manifest/repos.edn には
  登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4653/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対、
  Decision 4 に独立ゲート設計、Decision に robotics 却下理由)
- `90-docs/adr/2607113600-cloud-itonami-isic-4651-techtrade-actor.md`
  (2つの技術的失敗モード分離の直接の先例)
- `orgs/cloud-itonami/cloud-itonami-isic-4620/docs/business-model.md`
  (対比対象、農産物原材料そのものを扱う別 actor)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4653" エントリ)
