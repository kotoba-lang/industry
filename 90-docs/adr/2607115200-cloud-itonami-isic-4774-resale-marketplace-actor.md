# ADR-2607115200: cloud-itonami-isic-4774 — 中古品小売(Retail Sale of Second-Hand Goods)を ResaleAdvisor-LLM ⊣ ResaleGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーの「成熟度を高めて」という指示のもと、`kotoba-lang/industry` registry
の未着手 `:spec` スロットから6件を選んで並列で actor を新設した(本ADRは
その1件)。ISIC Rev.4 4774「Retail sale of second-hand goods」が対象。

## Decision

新規 actor `cloud-itonami-isic-4774` を `cloud-itonami` org 直下に
public/AGPL-3.0-or-later で新設した(namespace `resale`)。P2P/委託中古品
リセールマーケットプレイス(eBay中古/Vestiaire Collective/GameStop買取級の
業態)。actor は listing/authentication/sale の**決定のみ**を仲介し、実物
商品の移動・カストディ・決済は一切扱わない(No :robotics)。

### ResaleAdvisor-LLM ⊣ ResaleGovernor(単一不変条件)

6 HARD(rbac・**stolen-goods-reporting-gate**・**counterfeit-flag-gate**・
condition-misrepresentation-gate・source-provenance-gate・
licensed-disclosure)+ 3 SOFT(確信度フロア・high-value-holding-period
gate・dispute-request 無条件)。

`stolen-goods-reporting-gate` と `counterfeit-flag-gate` は他の
cloud-itonami actor に存在しない domain-unique HARD チェック — 中古品
小売に固有の実定法上の義務(米国カリフォルニア州 B&P Code §21625 以下・
ニューヨーク州 GBL Art. 5 §§60-70 等の中古品業者の売主ID記録・疑わしい
intake の保留/報告義務)を根拠に、高額品/フラグ付きカテゴリの intake が
非KYCまたはフラグ付き売主からの場合は拒否し、真贋確認が必要なカテゴリの
販売は `:authentic` 判定なしには確定できない。`default-phase` はセッション
開始時点から保守的な `1` を採用。

## Consequences

- (+) `kotoba-lang/industry` registry の 4774 スロットが実装へ昇格。
- (+) `clojure -M:dev:test`: 37 tests / 138 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0(`clojure.core/agent` との名前衝突を
  修正済み)。`clojure -M:dev:run` デモも8シナリオ全て正しく発火。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ。
  `cloud-itonami-isic-4774` は standalone、plain-git 子リポとして
  `manifest/repos.edn` には登録しない。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4774/README.md` + `docs/adr/0001-architecture.md`
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`(フリート標準パターンの手本)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`(id "4774" エントリ)
