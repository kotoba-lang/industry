---
id: adr-2607999970-cloud-itonami-supply-chain-pedigree-linkage-isic0710-isic2410
title: "ADR-2607999970: cloud-itonami supply-chain pedigree連携の第三リンク — isic-0710→isic-2410（鉄鉱石採掘→粗鋼、真の原材料起点）"
status: accepted
date: 2026-07-16
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。ADR-2607999950/2607999960
    （isic-2410→isic-2930→isic-2910の2ホップ）完了・実証済みを受けて、
    「原材料から全て」を文字通り実証する第三リンクへ）
related:
  - 90-docs/adr/2607999950-cloud-itonami-supply-chain-pedigree-linkage.md（正本pilot）
  - 90-docs/adr/2607999960-cloud-itonami-supply-chain-pedigree-linkage-isic2930-isic2910.md（第二リンク、:pedigree/upstream chaining実装済み）
  - 90-docs/adr/2607141920-cloud-itonami-isic-0710-iron-ore-mining-coverage.md（isic-0710の scope: coordination only）
supersedes: []
superseded_by: []
last_verified: 2026-07-16
doc_type: adr
topic: cloud-itonami-supply-chain-connectivity
authoritative: true
authoritative_for:
  - "isic-0710→isic-2410 を『isic-0710が自身の実production-log記録（鉱石品位）から
    pedigreeを発行→isic-2410が独立検証、isic-2410自身が発行するpedigreeに
    :pedigree/upstreamとして埋め込む』という第三リンクとする位置づけ。これで
    isic-0710→isic-2410→isic-2930→isic-2910の3ホップ・4アクターのチェーンが完成する"
---

# ADR-2607999970: cloud-itonami supply-chain pedigree連携の第三リンク — isic-0710→isic-2410

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki

## Context

ADR-2607999950/2607999960で「粗鋼→部品→完成車」の2ホップが実証済み。本ADRで
「鉄鉱石採掘→粗鋼」を繋げ、**真の原材料起点からの3ホップ・4アクターチェーン**
（isic-0710鉄鉱石採掘 → isic-2410粗鋼 → isic-2930自動車部品 → isic-2910完成車）
を完成させる——本セッション冒頭の質問「原材料から全てのサプライチェーンが...」
に対する最も具体的な回答になる。

**isic-0710はisic-2410/isic-2930と異なりphysics-2d物理シミュレーションを持たない**
（coordination-only actor、採掘そのものはモデル化しない、per ADR-2607141920）。
pedigreeのclaimsは、isic-0710が実際に記録する`production-record`（鉱石品位survey
データ、`ironops.facts/production-grade-valid?`が既に検証している`grade-actual`
フィールド）から構築する——物理シミュレーションではなく**実運用記録データ**を
claimsの根拠とする、初めてのパターン。

isic-2410（粗鋼）の`steelworks.facts`には日本/米国/英国/独国いずれの型式承認カタログ
にも`"chemistry-analysis-report"`（成分分析報告書）という証拠項目が既に存在し、
これがisic-0710のore-grade pedigreeを受け入れる自然なhook pointになる。

## Decision

1. **`ironops.export`（新規）に`pedigree-for-production-record`を追加**
   （`steelworks.export/pedigree-for-heat`と同型だが、根拠が物理シミュレーション
   ではなく実運用記録データである点を正直に開示する）: 実際にlog-productionされた
   production-recordの`grade-actual`（および関連する実データフィールド）から
   `kotoba.pedigree`レコードを構築する。
2. **`steelworksの粗鋼pedigree発行（`pedigree-for-heat`）に、その heatが
   `:upstream-ore-pedigree`を持っていれば`:pedigree/upstream`として埋め込む**
   よう拡張する——これで3ホップの真のchainが表現可能になる。
3. **`steelworks.facts`/`steelworks.store`（heatレコード）に任意の
   `:upstream-ore-pedigree`フィールドを追加**（既存のchemistry-analysis-report
   自己申告evidence itemは変更しない、破壊的変更にしない）。
4. **`steelworks.governor`に新HARD check**を追加: `:upstream-ore-pedigree`が
   存在する場合、`kotoba.pedigree/valid?`とその品位claimがisic-2410自身の
   受入基準を満たすか独立検証する。存在しない場合はno-op。
5. **実証**: 可能であれば**3ホップ全て**（isic-0710→isic-2410→isic-2930→isic-2910、
   4アクター）を実際に順番に呼び出す統合テストをisic-2910のtest scope
   （または新設の独立検証用の場所）に書く——ADR-2607999960が確立した
   複数`:local/root` sibling併記パターンをそのまま踏襲する。技術的に3ホップ
   統合テストが難しい場合は、正直にisic-0710→isic-2410の1ホップ証明に留め、
   理由を開示する。
6. **ネットワーク呼び出しは一切しない。** 新規物理エンジン・CADカーネルは
   作らない。registry.edn更新はスコープ外。

## Consequences

(+) 「原材料（鉄鉱石採掘）から完成車まで」という4アクター・3ホップの実データ連携が
初めて完成する——本セッション冒頭の問いに対する最も具体的な実証。
(+) 物理シミュレーションベースではなく実運用記録データベースのpedigree発行という
新パターンが確立し、今後coordination-only actor（鉱業・農業等）からの連携にも
再利用できる。
(−) 3ホップ統合テストの実現可能性は実装セッション次第——ADR-2607999960の技術的
知見（複数sibling `:local/root`併記）を踏まえれば可能性は高いが、保証はしない。

## References

- https://github.com/kotoba-lang/pedigree
- https://github.com/cloud-itonami/cloud-itonami-isic-0710
- https://github.com/cloud-itonami/cloud-itonami-isic-2410
- https://github.com/cloud-itonami/cloud-itonami-isic-2930
- https://github.com/cloud-itonami/cloud-itonami-isic-2910
