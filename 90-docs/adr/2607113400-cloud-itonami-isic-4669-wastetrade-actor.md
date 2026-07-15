# ADR-2607113400: cloud-itonami-isic-4669 — 廃棄物・スクラップ卸売を WasteTradeAdvisor ⊣ :waste-trading-governor で実装する越境移動同意(PIC)actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4641`(繊維・衣料・履物、強制労働推定、
ADR-2607113100)に続く fleet 継続タスクとして ISIC 4669「Wholesale of
waste and scrap and other products n.e.c.」を選定した。死んだ
`gftdcojp/cloud-itonami-G4669` URL のまま `:spec` で放置されていた。

## Decision

新規 actor `cloud-itonami-isic-4669` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。**fleet 初の「環境」規制体系**を持つ actor
である -- これまでの兄弟(貿易制裁・バイオセキュリティ・食品安全・人権)
のいずれとも異なる。

### 1. WasteTradeAdvisor ⊣ :waste-trading-governor(単一不変条件)

> **WasteTradeAdvisor は、`:waste-trading-governor` が拒否する
> `:delivery/dispatch`・`:invoice/settle` を決して行わない。**

### 2. Prior Informed Consent(PIC)の双務性モデル化(設計上の核心判断)

`prior-informed-consent-missing` チェックは、これまでの兄弟のどのチェック
とも構造が異なる: **輸出者側の一方的な証明書ではなく、輸出国・輸入国
政府間の双務的な同意**を要求する。バーゼル条約は有害廃棄物の輸出前に
**輸入国政府の書面同意**を義務付ける(第6条 PIC 手続き)。実装は
`:transboundary-notification-filed?`(通報)と
`:destination-country-consent-documented?`(輸入国同意)の**両方**を
要求する一つの HARD チェックとして畳み込んだ。

**法域無条件での評価**: 4662金属と同じく、`:waste-stream-type` が有害
廃棄物に分類される場合は**法域を問わず**このチェックが発火する
(4641繊維の法域ゲート付き設計とは逆)。実装エージェントの理由付け:
米国はバーゼル条約の非締約国だが、RCRA(資源保護回収法)が並行する
輸入国同意要件を独自に課しているため、「拘束力ある成文法を持つ法域のみ
発火」という4641の論理を単純に踏襲すると、非締約国の米国向け輸出で
チェックを無効化してしまい誤りになる。この判断の根拠を実装側 ADR
Decision 4 に、4662・4641両方の先例と対比して詳述している。

**「グリーンリスト廃棄物は対象外」の実証**: 同一の未文書化状態でも、
有害廃棄物(使用済み鉛蓄電池、wo-6)はHARD hold、非有害・グリーンリスト
廃棄物(選別済み鉄スクラップ、wo-7)はクリーン発送 -- 包括的な廃棄物
禁輸ではないことをcontrol pairで証明。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1-4 | no-spec-basis / evidence-incomplete / credit-uncleared / contract-missing | HARD | 兄弟共通 |
| 5 | **prior-informed-consent-missing** | HARD | `:waste-stream-type` が有害廃棄物の場合のみ発火、法域無条件、通報+輸入国同意の両方を要求 |
| 6-8 | counterparty-sanctions-flag-unresolved / already-dispatched / already-invoiced | HARD | 兄弟共通 |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | 兄弟共通 |

### 3. Robotics premise: true

ロボットソーティングアーム・磁気/渦電流セパレーター・自動梱包
(ベーリング)、いずれも卸売業者自身のヤード発送地点で完結する実在の
自動化。下流のリサイクル/破砕/処分処理(別のISIC活動)は明示的に
スコープ外。

### 4. 法域/フレームワークカタログ(ADR起票時に WebSearch で裏取り済み、
いずれも正確)

**拘束力ある政府規制**: バーゼル条約(1989年採択・1992年発効、
約190締約国、第6条PIC手続き。**米国は非締約国**、これを正直に反映)を
日英独の基盤に、米国は RCRA(42 U.S.C. §6901以下、40 CFR Part 262
Subpart H)を独自に引用。日本のバーゼル法(特定有害廃棄物等の輸出入等の
規制に関する法律)、英国 Transfrontier Shipment of Waste Regulations
2007(SI 2007/1711)、EU Regulation (EU) 2024/1157(2024-05-20発効、
主要規則は2026-05-21から適用開始 -- Regulation (EC) 1013/2006の
recast)。

**任意の民間認証(情報提供のみ、HARDチェックのゲートには使わない)**:
R2v3(SERI)・e-Stewards(BAN)を e-waste 注文の情報ノートとして表示する
のみで、拘束力ある法規制であるかのように装っていない -- 実装エージェント
のこの区別を本ADRでも支持する。

## Consequences

- (+) `kotoba-lang/industry` registry の 4669 スロットが実装へ昇格。
- (+) fleet に初の環境規制体系 actor を追加し、PIC の双務性という新しい
  構造パターンを確立した。
- (+) 4662(法域無条件)・4641(法域ゲート付き)という既存2パターンの
  どちらを踏襲すべきか実装エージェントが理由を持って選択し(法域無条件を
  選択、ただし理由は4662と異なる: 米国の非締約国という地位そのものが
  「なぜ法域無条件が正しいか」の根拠になっている)、機械的なコピーで
  ないことをADRに明記した。
- (+) `clojure -M:dev:test`: 46 tests / 228 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモは有害/非有害のcontrol pair・
  通報のみ(輸入国同意欠落)ケース含めEnd-to-Endで確認済み。
- (-) RCRAの正確なCFR条項境界、日本バーゼル法の現行条文番号、UK SI
  2007/1711の改正履歴、EU 2024/1157の細かい段階適用日程は要検証と
  実装エージェント自身が明記。
- (-) 本サイクルから `nbb scripts/gen-west-manifest.cljs` が
  `nbb scripts/gen-west-manifest.cljs` へ移行済み(オーナーによる
  進行中のnbb→nbb移行が本ADR起票の間にmainへ着地した)。以降の pin 前進は
  `npx nbb scripts/gen-west-manifest.cljs --entry <name>` を使用する。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`--entry industry` 最小 diff)。`cloud-itonami-isic-4669` は
  standalone(manifest/repos.edn には登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4669/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対、
  Decision 4 に PIC 設計、Decision 10 に robotics 判断)
- `90-docs/adr/2607113100-cloud-itonami-isic-4641-textiletrade-actor.md`
  (法域ゲート付きパターンの先例、対比対象)
- `90-docs/adr/2607112400-cloud-itonami-isic-4662-metaltrade-actor.md`
  (法域無条件パターンの先例)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4669" エントリ)
