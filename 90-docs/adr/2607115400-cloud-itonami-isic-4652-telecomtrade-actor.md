# ADR-2607115400: cloud-itonami-isic-4652 — 電子・通信機器卸売を TelecomTradeAdvisor ⊣ :telecom-supply-chain-governor で実装するサプライチェーン国家安全保障 actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4653`(農業機械・設備、製品コンプライアンス、
ADR-2607115000)に続く fleet 継続タスクとして ISIC 4652「Wholesale of
electronic and telecommunications equipment and parts」を選定した。
死んだ `gftdcojp/cloud-itonami-G4652` URL のまま `:spec` で放置されて
いた。

## Decision

新規 actor `cloud-itonami-isic-4652` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。`cloud-itonami-isic-4651`(コンピュータ・
周辺機器、TechTradeAdvisor)も「輸出管理」を扱うが、その規制の問いは
「この品目の技術分類は、この宛先向けにライセンスを要するか」であるのに
対し、4652の規制の問いは根本的に別物: **「この機器の製造元は特定リスト
に載っているか、そしてそれを載っているという理由だけで、この買い手
カテゴリには売れないのか」** -- 技術分類問題ではなく、サプライチェーン
の信頼性・調達元問題である。

### 1. TelecomTradeAdvisor ⊣ :telecom-supply-chain-governor(単一不変
条件)

> **TelecomTradeAdvisor は、`:telecom-supply-chain-governor` が拒否
> する `:delivery/dispatch`・`:invoice/settle` を決して行わない。**

### 2. 連言(AND)条件チェック -- fleet 4つ目のチェック形状(設計上の核心
判断)

`covered-manufacturer-buyer-restricted` は、これまでのfleetのどの
チェック形状とも異なる: **構造的に無関係な2つの独立したブール値の連言**
(製造元がリスト該当 **かつ** 買い手カテゴリが制限対象)である。

- 4651(techtrade)の分類→ライセンス判定は**逐次的で依存関係がある**
  (先に分類しなければライセンス判定に進めない)
- 4653(agmachtrade)の排出ガス/ROPSは**2つの完全に独立したチェック**
  (それぞれ別ルールとしてhold)
- 4652(telecomtrade)は**1つのチェックの中で2つの独立事実を連言で
  結合**する、fleet で初めてのパターン

**連言であることの実証(4項のcontrol quadruple)**:
- リスト該当製造元(Huawei)+ commercial-unrestricted買い手 → クリーン
  発送(製造元だけでは発火しない)
- リスト非該当製造元(Nokia)+ federal-agency買い手 → クリーン発送
  (買い手カテゴリだけでは発火しない)
- リスト該当製造元 + federal-agency買い手 → HARD hold
- リスト該当製造元 + fcc-usf-funded-carrier買い手(別の制限カテゴリ)
  → HARD hold

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1-4 | no-spec-basis / evidence-incomplete / credit-uncleared / contract-missing | HARD | 兄弟共通 |
| 5 | **covered-manufacturer-buyer-restricted** | HARD | 製造元リスト該当 **かつ** 買い手カテゴリが制限対象、両方が真の場合のみ発火 |
| 6 | counterparty-sanctions-flag-unresolved | HARD | 汎用OFAC |
| 7-8 | already-dispatched / already-invoiced | HARD | 二重actuation防止 |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | 兄弟共通 |

### 3. Robotics premise: true(均一、パス分割なし)

このverticalは物理ハードウェアのみを扱うため(4651のような「みなし
輸出」のような非物理actuationが存在しない)、fuel/metal卸売兄弟と同じ
均一な `:robotics true` とした。

### 4. 法域カタログ(意図的に2法域のみ、水増しせず正直に -- ADR起票時に
WebSearch で裏取り済み、製造元リストの企業名まで一致)

- **米国** — Secure and Trusted Communications Networks Act 2019 に
  基づくFCC Covered List(現行掲載企業: Huawei, ZTE, Hytera, Hikvision,
  Dahua, China Mobile, China Telecom, China Unicom, Pacific Networks
  -- 実装エージェントの引用と完全一致)、NDAA FY2019 §889 Part A
  (2019年8月13日発効、連邦機関調達禁止)/ Part B(2020年8月13日発効、
  連邦契約者/助成金受給者禁止)、FAR 52.204-25。**別途**、2022年11月の
  FCC規則(2023年2月6日発効)がCovered List機器の新規機器認証を買い手
  を問わず全面禁止することも実在確認したが、これは買い手非依存のため
  実装エージェントは意図的に別のHARDチェックとして実装しなかった --
  本ADRが実証したい連言構造をぼかすため。この判断は妥当と判断する。
- **英国** — Telecommunications (Security) Act 2021 + Huawei
  designated vendor direction(2022年、35のUK通信事業者に対しHuawei
  機器の段階的排除を義務付け)

日本・EUは確信度不足を理由に**意図的に除外**した(水増しせず正直な
報告)。

## Consequences

- (+) `kotoba-lang/industry` registry の 4652 スロットが実装へ昇格。
- (+) fleetに4つ目の異なるHARDチェック形状(逐次分類・独立2ゲート・
  連言AND)を追加し、「輸出管理」という同じラベルでも規制メカニズムが
  質的に異なる場合はチェック形状そのものを変えるべきという設計原則の
  実例をさらに1つ積み上げた。
- (+) 法域カタログを意図的に2つに絞り、確信度不足の法域を水増ししない
  正直さを実証した(他の兄弟の「4法域」パターンを機械的に踏襲しない
  判断)。
- (+) `clojure -M:dev:test`: 45 tests / 251 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモは4項control quadrupleを
  End-to-Endで確認済み。
- (-) NDAA §889(f)(3)の正確な企業名表記、FCC Covered ListのURL
  (fcc.gov/supplychain/coveredlist)、英国規制の正確な条項番号は
  実装エージェント自身が学習知識ベースの想起であり要検証と明記。
  本ADR起票時のWebSearchで主要事実(企業リスト・発効日)は確認済み。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`nbb scripts/gen-west-manifest.cljs --entry industry` 最小 diff)。
  `cloud-itonami-isic-4652` は standalone(manifest/repos.edn には
  登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4652/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対、
  Decision 4 に連言チェックの設計理由)
- `90-docs/adr/2607113600-cloud-itonami-isic-4651-techtrade-actor.md`
  (対比対象、逐次分類チェックの先例)
- `90-docs/adr/2607115000-cloud-itonami-isic-4653-agmachtrade-actor.md`
  (対比対象、独立2ゲートチェックの先例)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4652" エントリ)
