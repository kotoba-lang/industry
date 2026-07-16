# ADR-2607116500: cloud-itonami-isic-3822 — 危険廃棄物処理/処分(Treatment and Disposal of Hazardous Waste)を HazWasteTreatment-LLM ⊣ HazWasteGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーの「成熟度を高めて」という継続指示のもと、`kotoba-lang/industry`
registry の未着手 `:spec` スロットから4件を選んで並列で actor を新設した
(本ADRはその1件)。ISIC Rev.4 3822「Treatment and disposal of hazardous
waste」は、同セッション前半で新設した `cloud-itonami-isic-3811`(非危険
廃棄物収集)の直接の兄弟スロットとして選定した — 意図的に業態を分離する
ため。

## Decision

新規 actor `cloud-itonami-isic-3822` を `cloud-itonami` org 直下に
public/AGPL-3.0-or-later で新設した(namespace `hazwaste`)。`isic-3811`
が「非危険物であることを前提に収集をディスパッチする」業態であるのに対し、
3822 は**危険廃棄物そのものを合法的に処理・処分する**業態であり、米国
EPA RCRA Subtitle C マニフェスト制度・EU Waste Shipment Regulation・
Basel Convention 越境移動規制という、はるかに重い規制枠組みに服する。

### HazWasteTreatment-LLM ⊣ HazWasteGovernor(単一不変条件)

> **HazWasteTreatment-LLM は、HazWasteGovernor が拒否するマニフェスト
> 受入・処理実行・開示・訂正確定を決して行わない。**

8チェック(HARD: rbac・**manifest-chain-of-custody-gate**・
**treatment-method-authorization-gate**・source-provenance-gate・
licensed-disclosure、SOFT: 確信度フロア・cross-border-gate・
dispute-request 無条件)。

`manifest-chain-of-custody-gate`(generator→transporter→facility の署名済み
チェーンが不完全なら拒否、40 CFR Part 262 が根拠)と
`treatment-method-authorization-gate`(施設の有効な許可が対象
waste-code×method を認可していなければ拒否)は 3811 には存在しない
domain-unique HARD チェック。`cross-border-gate` は Basel Convention の
越境移動監督義務を反映した SOFT(常時escalate)チェック。

R0 分類根拠カタログは実在する3つの規制枠組み(米国 RCRA、EU Waste
Shipment Regulation、Basel Convention)。`default-phase` はセッション
開始時点から保守的な `1` を採用。

Robotics premise: false(マニフェスト・処理記録の管理のみで、実際の
処理作業自体は actor の境界外)。

## Consequences

- (+) `kotoba-lang/industry` registry の 3822 スロットが実装へ昇格。
- (+) 3811/3822 の2つで「非危険物収集」と「危険物処理」という法的責任
  構造の異なる2業態を明確に分離した。
- (+) `clojure -M:dev:test`: 28 tests / 98 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも7シナリオ
  全て正しく発火。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`manifest/west.yml` の `industry` entry、`--entry industry` の最小
  diff)。`cloud-itonami-isic-3822` は standalone、plain-git 子リポとして
  `manifest/repos.edn` には登録しない。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-3822/README.md` + `docs/adr/0001-architecture.md`
- `90-docs/adr/2607115100-cloud-itonami-isic-3811-waste-collection-actor.md`(直接の兄弟actor)
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`(フリート標準パターンの手本)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`(id "3822" エントリ)
