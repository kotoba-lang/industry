# ADR-2607116900: cloud-itonami-isic-4663 — 建設資材・金物・配管・暖房設備卸売を BuildMatTradeAdvisor ⊣ :potable-water-safety-governor で実装する飲用水安全 actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4649`(その他家庭用品、消費者製品安全、
ADR-2607116800)に続く fleet 継続タスクとして ISIC 4663「Wholesale of
construction materials, hardware, plumbing and heating equipment」を
選定した。死んだ `gftdcojp/cloud-itonami-G4663` URL のまま `:spec` で
放置されていた。

## Decision

新規 actor `cloud-itonami-isic-4663` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。規制の重心は**飲用水と接触する配管製品の
鉛含有規制**であり、fleetにとって新しい具体的な product-certification
領域である。

### 1. BuildMatTradeAdvisor ⊣ :potable-water-safety-governor(単一不変
条件)

> **BuildMatTradeAdvisor は、`:potable-water-safety-governor` が拒否
> する `:delivery/dispatch`・`:invoice/settle` を決して行わない。**

グレップ検証済み: 既存の `:water-safety-governor`(ISIC 3600、水道
事業体)・`:plumbing-trade-governor`(ISIC 4322、配管工事業)のいずれ
とも重複しない、本 actor(卸売業)固有の governor 名。

### 2. `lead-free-certification-missing`(型ゲート付き、単一チェック
に2つの証拠アームを畳み込み)

`:potable-water-contact?` でゲートされ、NSF/372(鉛含有量試験)+
NSF/61(認証登録)という同一の Lead-Free 認証手続きの2つの証拠アームを
1つのルールに畳み込んだ(4649の recall enum のような再評価は不要 --
認証は一度取得すれば「元に戻らない」性質のため `:delivery/dispatch`
でのみ評価)。

**型ゲートの実証を通常より強化**: 通常の兄弟は1つの control caseで
型ゲートを証明するが、本実装は**2つの独立した非該当カテゴリ**
(建設資材=木材、暖房設備=ガス炉)の両方でNO-OPを実証した -- ISIC 4663
自体が名指しする2つの非飲用水カテゴリをどちらもカバーする、より
徹底した実証。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1-4 | no-spec-basis / evidence-incomplete / credit-uncleared / contract-missing | HARD | 兄弟共通 |
| 5 | **lead-free-certification-missing** | HARD | `:potable-water-contact?` でゲート、NSF/372+61の2証拠アームを1ルールに畳み込み |
| 6 | counterparty-sanctions-flag-unresolved | HARD | 汎用OFAC |
| 7-8 | already-dispatched / already-invoiced | HARD | 二重actuation防止 |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | 兄弟共通 |

### 3. 暖房設備向け第二チェックの意図的な不採用(設計上の重要な判断)

実装エージェントは暖房機器向けの安全認証(ANSI Z21系/CSA/UL-NRTL)を
第二のHARDチェックとして追加することを検討したが、**意図的に見送った**。
理由: SDWA第1417条のような正確に引用可能な単一の連邦法基準とは異なり、
米国のガス/油焚き機器のNRTL認証要件は州・地方レベルのモデルコード
採用のパッチワークを経由しており、統一的な連邦法として正確に引用する
確信度が不足していた。fleetのパターンに機械的に合わせて第二チェックを
無理に追加しなかった判断であり、本ADRで支持する。

### 4. Robotics premise: true

バルク木材/パイプ/鉄筋のラッキング・スタッキング・ガントリークレーン
自動化、パレット化された金物/器具/暖房設備のAS/RS -- 4653(自走式
農業機械、`:robotics false`)とは対照的な、実在する物理的自動化根拠が
ある。

### 5. 法域カタログ(ADR起票時に WebSearch で裏取り済み、米国の閾値は
完全一致、日本の所管省庁を訂正)

- **米国** — Reduction of Lead in Drinking Water Act of 2011(Pub. L.
  111-380)、Safe Drinking Water Act第1417条(42 U.S.C. §300g-6)改正、
  Lead-Free定義を旧8%から**加重平均0.25%**へ厳格化(2014年1月4日
  施行)、はんだ/フラックスは0.2%、NSF/ANSI/CAN 372(鉛含有量)+ NSF/
  ANSI/CAN 61(健康影響)認証 -- 実装エージェントの引用と閾値・施行日
  ともに完全一致を確認。
- **日本** — 水道法 + 給水装置の構造及び材質の基準に関する省令。
  **訂正**: 実装エージェントは当初「厚生労働省」単独所管として引用
  したが、本ADR起票時のWebSearchで、現行の浸出基準は「**国土交通大臣
  及び環境大臣**が定める」ものであり、直近改正(令和8年1月28日、
  国土交通省・環境省告示第2号)も同2省庁による共同告示であることを
  確認した -- 2024年の水道行政再編(水道整備・管理行政の厚生労働省
  から国土交通省・環境省への移管)を反映し、registry.edn のコメントを
  現行の正確な所管省庁に訂正した。実装エージェント自身がこの点を
  「要検証」と明記していたことが、この訂正発見につながった。

## Consequences

- (+) `kotoba-lang/industry` registry の 4663 スロットが実装へ昇格。
- (+) 型ゲート実証を「2つの独立した非該当カテゴリ」でより徹底的に
  行った先例になった。
- (+) fleetのパターンへの機械的な追従を拒み、確信度不足の第二チェック
  を意図的に見送った判断を、明確な理由とともに記録した。
- (+) ADR起票プロセス自体が実装側の日本法所管省庁の記載ミス(2024年
  再編の未反映)を独立検証で発見・訂正した実例になった -- 「未検証で
  組み込み、登録前に必ずWebSearchで裏取りする」という fleet の品質
  ゲートが再度機能した証跡(4630の英国法引用ミス訂正に続く2例目)。
- (+) `clojure -M:dev:test`: 39 tests / 200 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモは木材・ガス炉の両方の
  no-opコントロールをEnd-to-Endで確認済み。
- (-) CSA/IAPMO/UL等がNSF Internationalと同等の認証機関としての地位を
  NSF/372+61において持つかは実装エージェント自身が要検証と明記。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`nbb scripts/gen-west-manifest.cljs --entry industry` 最小 diff)。
  `cloud-itonami-isic-4663` は standalone(manifest/repos.edn には
  登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4663/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対、
  Decision 5 に暖房設備第二チェック不採用の理由)
- `90-docs/adr/2607116800-cloud-itonami-isic-4649-housewaretrade-actor.md`
  (直近の先例、単一チェック畳み込み vs. 再評価enumの対比)
- `90-docs/adr/2607115000-cloud-itonami-isic-4653-agmachtrade-actor.md`
  (対比対象、robotics false の先例)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4663" エントリ)
