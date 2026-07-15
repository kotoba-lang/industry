---
id: adr-2607998500-cloud-itonami-isic-4741-parts-digital-twin
title: "ADR-2607998500: cloud-itonami-isic-4741（コンピュータ小売/ITAD）に部品完成段階の real digital-twin を拡張する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。digital-twin waveの最後、
    ADR-2607152000の6業種（isic-2930/2610/0810/2394/4211/4741）中、
    isic-4741を完了させればwave全体が完了する）
related:
  - 90-docs/adr/2607160000-cloud-itonami-isic-2930-parts-digital-twin-pilot.md
  - 90-docs/adr/2607995500-cloud-itonami-isic-0810-parts-digital-twin.md（free-fall物理という近い先例）
  - 90-docs/adr/2607152000-cloud-itonami-real-engineering-simulation-fleet-extension.md（isic-4741の物理point-testの先例、trade-in機器の落下衝撃試験）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-digital-twin
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-4741 が org-iso-10303（BREP）への実依存を追加し、
    techretail.cad/techretail.scene/techretail.motionplan をこのvertical の実digital-twin正本コードとする位置づけ"
---

# ADR-2607998500: cloud-itonami-isic-4741（コンピュータ小売/ITAD）に部品完成段階の real digital-twin を拡張する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

isic-2930・isic-2610・isic-0810・isic-2394・isic-4211に続き、ADR-2607152000で
物理point-test（trade-in-unit機器の標準試験高からの落下衝撃試験——isic-0810の
free-fall物理と近い形状）を得たisic-4741（コンピュータ小売/ITAD/リファービッシュ）を、
digital-twinパターンの最終業種として拡張する。これでADR-2607152000の6業種すべてが
このパターンでカバーされる。

## Decision

isic-0810（free-fall物理の先例、幾何/軌道不変性の実証的検証手法）を主な参照実装とし、
isic-2930/isic-2610/isic-2394のscene/motionplanパターンも踏襲する:
`techretail.cad`（新規、trade-in機器のBREP envelope）、既存物理namespace修正
（自由落下する機器本体がmoving body、着地面がstatic——実コードで確認）、
`techretail.scene`（新規）、`techretail.motionplan`（新規、既存missionステップの
waypoint化、適用できなければ正直にスコープを狭める）。新規物理エンジン・CADカーネル・
sibling design-libraryリポジトリは作らない。registry.edn更新は本ADRのスコープ外。

## Consequences

(+) ADR-2607152000の6業種すべて（isic-2930/2610/0810/2394/4211/4741）が
digital-twinパターン（実CAD形状+実物理+WebGPUシーン用データ+動線計画）でカバーされ、
このwaveが完了する。
(−) このwaveの範囲外の他業種（例: isic-2620/2630/2640等、物理point-testのみで
CAD形状を持たない業種）への拡張は別途のfollow-up判断とする。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-4741
- https://github.com/cloud-itonami/cloud-itonami-isic-0810（free-fall物理の直近先例）
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（最初のpilot）
