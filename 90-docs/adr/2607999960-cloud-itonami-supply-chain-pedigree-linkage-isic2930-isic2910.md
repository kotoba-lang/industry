---
id: adr-2607999960-cloud-itonami-supply-chain-pedigree-linkage-isic2930-isic2910
title: "ADR-2607999960: cloud-itonami supply-chain pedigree連携の第二リンク — isic-2930→isic-2910（部品ロット→完成車型式認証）"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。ADR-2607999950（isic-2410→isic-2930
    pilot）完了・実証済みを受けて第二リンクへ）
related:
  - 90-docs/adr/2607999950-cloud-itonami-supply-chain-pedigree-linkage.md（正本pilot、
    kotoba-lang/pedigree・実装パターンの直接の踏襲元）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-supply-chain-connectivity
authoritative: true
authoritative_for:
  - "isic-2930→isic-2910 を『isic-2930が自身のpedigreeを発行（上流isic-2410由来分を
    参照埋め込みし、真のチェーンにする）→isic-2910が独立検証』というADR-2607999950
    パターンの第二適用とする位置づけ"
---

# ADR-2607999960: cloud-itonami supply-chain pedigree連携の第二リンク — isic-2930→isic-2910

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

ADR-2607999950のpilot（isic-2410粗鋼→isic-2930自動車部品）は実際にlandし、
実クロスリポジトリ呼び出し（`autoparts`のtestが`steelworks.export/pedigree-for-heat`
を実際に呼ぶ）で証明済み。前回のloop調査で、`cloud-itonami-isic-2910`
（完成車組立）の`automotive.facts`には日本/米国/英国/独国いずれの型式認証カタログにも
`"material-certification-record"`という証拠項目が既に存在しており、これが
pedigree接続の自然なhook pointだと判明した。一方`automotive.facts`にはBOM/部品リスト
の概念自体が無いため、`isic-2930`同様「既存レコードへの任意フィールド追加」では
済まず、車両dispatch proposal側に新しい概念（受け入れたpart-lot pedigreeの参照）を
追加する必要がある。

## Decision

1. **`autoparts.export`に`pedigree-for-part-lot`を新規追加**（isic-2410の
   `pedigree-for-heat`と同型）: 実際に受入検証済みのpart-lotの実telemetry
   （`:sim-proof-load-force`等）から`kotoba.pedigree`レコードを構築する。
   **その part-lot が既に上流の`:upstream-pedigree`（isic-2410由来）を持って
   いれば、それも`:pedigree/upstream`のような形で埋め込むか参照する**——
   これで初めて「鉄鉱石ではないが粗鋼→部品→完成車」という2ホップの真の
   provenance chainになる（kotoba.pedigreeのschemaに`:pedigree/upstream`
   フィールド追加が必要ならkotoba-lang/pedigree自体も拡張する）。
2. **`automotive.facts`/`automotive.store`（またはvehicle-dispatch proposalの
   実データ構造）に、任意の`:upstream-part-pedigrees`（part-lot pedigreeの
   vector、複数部品を想定）フィールドを追加**。既存の
   `material-certification-record`という自己申告evidence itemは変更しない
   （破壊的変更にしない）——新しい実データ経路を並行して追加する。
3. **`automotive.governor`に新HARD check**を追加: `:upstream-part-pedigrees`が
   存在する場合、各要素の`kotoba.pedigree/valid?`と、claim値が
   `automotive`自身の受入基準を満たすかを独立検証する。存在しない場合は
   no-op（既存proposalは変更なく通る）。
4. **実証**: isic-2930の実`pedigree-for-part-lot`を実際に呼び出し、その出力を
   isic-2910のproposalに渡す実クロスリポジトリtestを書く（fixtureで誤魔化さない、
   ADR-2607999950と同じ規律）。
5. **ネットワーク呼び出しは一切しない**（既存規律の継続）。新規物理エンジン・
   CADカーネルは作らない。registry.edn更新はスコープ外。

## Consequences

(+) 「鉄鉱石相当の粗鋼→部品→完成車型式認証」という2ホップの実データ連携が実証される。
(−) isic-2910の`automotive.facts`は多国籍の型式認証カタログを持つ、比較的複雑な
既存構造——変更は慎重に、既存の型式認証checkを一切壊さないことを最優先する。
(−) 3ホップ目（isic-0729/isic-0710の原材料 → isic-2410）は本ADRのスコープ外。

## References

- https://github.com/kotoba-lang/pedigree
- https://github.com/cloud-itonami/cloud-itonami-isic-2410
- https://github.com/cloud-itonami/cloud-itonami-isic-2930
- https://github.com/cloud-itonami/cloud-itonami-isic-2910
