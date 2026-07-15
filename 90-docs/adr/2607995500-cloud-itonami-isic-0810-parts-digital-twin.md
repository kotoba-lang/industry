---
id: adr-2607995500-cloud-itonami-isic-0810-parts-digital-twin
title: "ADR-2607995500: cloud-itonami-isic-0810（採石場運用）に部品完成段階の real digital-twin を拡張する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。ADR-2607160000/2607992500の
    digital-twin拡張waveをisic-2610に続き第3業種へ）
related:
  - 90-docs/adr/2607160000-cloud-itonami-isic-2930-parts-digital-twin-pilot.md
  - 90-docs/adr/2607992500-cloud-itonami-isic-2610-parts-digital-twin.md（直近実装済み、
    isic-2610 main merge d00d885b0d54948fe8030269cbdee3cc64bd4626）
  - 90-docs/adr/2607152000-cloud-itonami-real-engineering-simulation-fleet-extension.md（isic-0810の物理point-testの先例）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-digital-twin
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-0810（quarryops.simphysics相当）が org-iso-10303（BREP）への実依存を追加し、
    quarryops.cad/quarryops.scene/quarryops.motionplan をこのvertical の実digital-twin正本コードとする位置づけ"
---

# ADR-2607995500: cloud-itonami-isic-0810（採石場運用）に部品完成段階の real digital-twin を拡張する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

`cloud-itonami-isic-2930`（ADR-2607160000）・`cloud-itonami-isic-2610`
（ADR-2607992500、main `d00d885b0d5`）に続き、ADR-2607152000で同時に物理point-test
（bench-face loose-block stability check）を得た残る4業種（isic-0810 quarryops・
isic-2394 cement・isic-4211 construction・isic-4741 computer-retail）のうち、
isic-0810を第3業種として同パターンに拡張する。

## Decision

isic-2930/isic-2610で確立・2回検証済みのパターンをisic-0810へそのまま適用する:
`quarryops.cad`（新規、bench-face断片specimenのBREP envelope）、
`quarryops.simphysics`（既存の固定AABB定数を実CAD形状由来に置換、移動体のみ）、
`quarryops.scene`（新規、mesh+trajectory→kami.webgpu.mesh契約形状）、
`quarryops.motionplan`（新規、既存missionステップをwaypoint化）。新規物理エンジン・
CADカーネル・sibling design-libraryリポジトリは作らない。
`orgs/kotoba-lang/industry/registry.edn`更新は本ADRのスコープ外、別途safe
single-entry updateで行う。

## Consequences

(+) digital-twinパターンが3業種目（採石場運用）に拡張。
(−) 残る2業種（isic-2394 cement・isic-4211 construction・isic-4741computer-retail）
は本ADRのスコープ外、follow-up。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-0810
- https://github.com/cloud-itonami/cloud-itonami-isic-2610（実装済み直近参照パターン）
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（実装済み最初のpilot）
