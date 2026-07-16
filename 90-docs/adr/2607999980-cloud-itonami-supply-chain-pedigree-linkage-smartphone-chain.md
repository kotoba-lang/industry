---
id: adr-2607999980-cloud-itonami-supply-chain-pedigree-linkage-smartphone-chain
title: "ADR-2607999980: cloud-itonami supply-chain pedigree連携 — スマートフォンチェーン（isic-0729非鉄金属鉱業→isic-2610半導体fab→isic-2630通信機器）"
status: accepted
date: 2026-07-16
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。ADR-2607999950/2607999960/
    2607999970で自動車4アクターチェーンが完成済み。「スマホ側のチェーンも同様に
    構築」を選択）
related:
  - 90-docs/adr/2607999950-cloud-itonami-supply-chain-pedigree-linkage.md（正本pilot、kotoba-lang/pedigree）
  - 90-docs/adr/2607999960-cloud-itonami-supply-chain-pedigree-linkage-isic2930-isic2910.md（:pedigree/upstream chaining実装）
  - 90-docs/adr/2607999970-cloud-itonami-supply-chain-pedigree-linkage-isic0710-isic2410.md（原材料起点＝coordination-only actorからのpedigree発行パターン、直接の踏襲元）
supersedes: []
superseded_by: []
last_verified: 2026-07-16
doc_type: adr
topic: cloud-itonami-supply-chain-connectivity
authoritative: true
authoritative_for:
  - "isic-0729→isic-2610→isic-2630 を、自動車チェーン（isic-0710→isic-2410→
    isic-2930→isic-2910）と同型のpedigree連携パターンで実装する位置づけ。これで
    自動車・スマートフォン両方の『原材料から完成品まで』が実データで実証される"
---

# ADR-2607999980: cloud-itonami supply-chain pedigree連携 — スマートフォンチェーン

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki

## Context

自動車側（isic-0710鉄鉱石採掘→isic-2410粗鋼→isic-2930自動車部品→isic-2910完成車、
ADR-2607999950/2607999960/2607999970）で4アクター・3ホップの実データチェーンが
完成・実証済み。本セッション冒頭の質問はスマートフォンと車の両方を問うていたため、
同じパターンをスマートフォンチェーンにも適用する:

- **isic-0729（非鉄金属鉱業、銅・リチウム・レアアース等）**: 本セッションで新規構築
  したcoordination-only actor（isic-0710と同型のscaffold）。物理シミュレーション無し、
  実運用記録データ（鉱石品位survey）が根拠——isic-0710と全く同じ状況。
- **isic-2610（半導体/電子部品fab）**: 本セッションでdigital-twin拡張済み
  （wire-bond pull-test、実CAD形状+実物理）。isic-2410（粗鋼）と構造的に近い
  （物理シミュレーション由来のtelemetryを持つ）。
- **isic-2630（通信機器＝スマホ本体組立）**: 並行fleetによりOCAラミネートプレスの
  実物理を既に獲得済み。ただし`commsdevice.facts`の証拠チェックリストは
  無線機器の型式認証（技適・FCC Part 15C・RED）のみで、部品/材料証明の概念は
  存在しない——isic-2910（完成車）が当初BOM概念を持たなかったのと同型の状況。

## Decision

isic-0710→isic-2410→isic-2930→isic-2910で確立済みのパターンをそのまま適用する:

1. **isic-0729に`nonferrousops.export`（新規）を追加**: `ironops.export/
   pedigree-for-production-record`と同型、実運用記録データ（鉱石品位）ベースの
   pedigree発行。
2. **isic-2610に上流鉱業pedigree受入+独立検証+自身のpedigree発行への
   `:pedigree/upstream`埋め込み**を追加（isic-2410がisic-0710に対して行った
   のと同型）——`fab.export`（既存または新規）に`pedigree-for-lot`相当を追加。
3. **isic-2630に、任意の`:upstream-component-pedigrees`（isic-2610由来、vector）
   フィールドを新規追加**（isic-2910の`:upstream-part-pedigrees`と同型）し、
   `commsdevice.governor`に独立検証HARD checkを追加。既存の無線機器型式認証
   checkは一切変更しない。
4. **可能であれば3ホップ・4アクター全ての統合テスト**（isic-0729→isic-2610→
   isic-2630）をisic-2630のtest scopeに書く。技術的に困難な場合は1〜2ホップの
   証明に留め、理由を開示する。
5. **ネットワーク呼び出しは一切しない。** 新規物理エンジン・CADカーネルは
   作らない。registry.edn更新はスコープ外。

## Consequences

(+) 自動車に続き、スマートフォンについても「原材料（非鉄金属鉱業）から
完成品（スマホ本体）まで」の実データチェーンが実証され、本セッション冒頭の質問に
両サプライチェーンとも具体的に回答できる状態になる。
(−) isic-2620（コンピュータ/周辺機器）はこのチェーンに含まれない
（本セッションで既にrobotics-simulationは追加済みだが、supply-chain
pedigreeへの組み込みは本ADRのスコープ外）。

## References

- https://github.com/kotoba-lang/pedigree
- https://github.com/cloud-itonami/cloud-itonami-isic-0729
- https://github.com/cloud-itonami/cloud-itonami-isic-2610
- https://github.com/cloud-itonami/cloud-itonami-isic-2630
- https://github.com/cloud-itonami/cloud-itonami-isic-0710（同型の直接参照実装）
- https://github.com/cloud-itonami/cloud-itonami-isic-2410（同型の直接参照実装）
