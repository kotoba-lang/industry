---
id: adr-2607996500-cloud-itonami-isic-2394-parts-digital-twin
title: "ADR-2607996500: cloud-itonami-isic-2394（セメント製造）に部品完成段階の real digital-twin を拡張する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。digital-twin waveを
    isic-2930→isic-2610→isic-0810（進行中）に続き第4業種へ）
related:
  - 90-docs/adr/2607160000-cloud-itonami-isic-2930-parts-digital-twin-pilot.md
  - 90-docs/adr/2607992500-cloud-itonami-isic-2610-parts-digital-twin.md
  - 90-docs/adr/2607995500-cloud-itonami-isic-0810-parts-digital-twin.md
  - 90-docs/adr/2607152000-cloud-itonami-real-engineering-simulation-fleet-extension.md（isic-2394の物理point-testの先例、28日圧縮強度試験）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-digital-twin
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-2394 が org-iso-10303（BREP）への実依存を追加し、
    cementmill.cad/cementmill.scene/cementmill.motionplan をこのvertical の実digital-twin正本コードとする位置づけ"
---

# ADR-2607996500: cloud-itonami-isic-2394（セメント製造）に部品完成段階の real digital-twin を拡張する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

isic-2930・isic-2610に続き、ADR-2607152000で物理point-test（28日圧縮強度試験:
press-platen剛体がcube-specimen剛体に制御速度で閉じる）を得たisic-2394（セメント
製造）を、digital-twinパターン（実CAD形状+実物理+WebGPUシーン用データ+動線計画）の
第4業種として拡張する。

## Decision

isic-2930/isic-2610/isic-0810で確立されたパターンをisic-2394へ適用する:
`cementmill.cad`（新規、cube-specimenのBREP envelope、圧縮試験用の立方体形状は
envelope box近似と自然に整合する）、既存物理namespace修正（press-platen=静的固定、
specimen側またはpress側どちらが「moving body」かを実コードで確認した上で適切な方を
CAD由来寸法に置換）、`cementmill.scene`（新規）、`cementmill.motionplan`（新規、
既存missionステップのwaypoint化。適用できない場合は正直にスコープを狭める）。
新規物理エンジン・CADカーネル・sibling design-libraryリポジトリは作らない。
registry.edn更新は本ADRのスコープ外。

## Consequences

(+) digital-twinパターンが4業種目（セメント製造）に拡張。
(−) 残る2業種（isic-4211 construction・isic-4741 computer-retail）はfollow-up。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-2394
- https://github.com/cloud-itonami/cloud-itonami-isic-0810（直近実装、free-fall物理という異なる設定での検証実例）
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（最初のpilot）
