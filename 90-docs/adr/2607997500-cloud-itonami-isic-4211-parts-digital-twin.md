---
id: adr-2607997500-cloud-itonami-isic-4211-parts-digital-twin
title: "ADR-2607997500: cloud-itonami-isic-4211（建設）に部品完成段階の real digital-twin を拡張する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。digital-twin waveを
    isic-2930→isic-2610→isic-0810→isic-2394（進行中）に続き第5業種へ）
related:
  - 90-docs/adr/2607160000-cloud-itonami-isic-2930-parts-digital-twin-pilot.md
  - 90-docs/adr/2607995500-cloud-itonami-isic-0810-parts-digital-twin.md
  - 90-docs/adr/2607996500-cloud-itonami-isic-2394-parts-digital-twin.md
  - 90-docs/adr/2607152000-cloud-itonami-real-engineering-simulation-fleet-extension.md（isic-4211の物理point-testの先例、コンクリート試験円柱圧縮強度試験）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-digital-twin
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-4211 が org-iso-10303（BREP）への実依存を追加し、
    construction.cad/construction.scene/construction.motionplan をこのvertical の実digital-twin正本コードとする位置づけ"
---

# ADR-2607997500: cloud-itonami-isic-4211（建設）に部品完成段階の real digital-twin を拡張する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

isic-2930・isic-2610・isic-0810に続き、ADR-2607152000で物理point-test（コンクリート
試験円柱の圧縮強度試験: press-platen剛体がspecimen剛体に制御速度で閉じる、isic-2394
と同型の"press"shape）を得たisic-4211（建設）を、digital-twinパターンの第5業種として
拡張する。ADR-2607152000がリストした6業種のうち、これで残るのはisic-4741
（コンピュータ小売、drop/shock試験）のみとなる。

## Decision

isic-2930/isic-2610/isic-0810/isic-2394で確立されたパターンをisic-4211へ適用する:
`construction.cad`（新規、円柱specimenのBREP envelope）、既存物理namespace修正
（press側とspecimen側どちらがmoving bodyか実コードで確認して適切な方をCAD由来寸法に
置換）、`construction.scene`（新規）、`construction.motionplan`（新規、既存mission
ステップのwaypoint化、適用できなければ正直にスコープを狭める）。新規物理エンジン・
CADカーネル・sibling design-libraryリポジトリは作らない。registry.edn更新は本ADR
のスコープ外。

## Consequences

(+) digital-twinパターンが5業種目（建設）に拡張、ADR-2607152000の6業種中5業種完了。
(−) 残る1業種（isic-4741 computer-retail）はfollow-up——完了すればwave全体が完了する。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-4211
- https://github.com/cloud-itonami/cloud-itonami-isic-2394（同じpress shapeの直近先例、進行中）
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（最初のpilot）
