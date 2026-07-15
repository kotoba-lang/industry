---
id: adr-2607992500-cloud-itonami-isic-2610-parts-digital-twin
title: "ADR-2607992500: cloud-itonami-isic-2610（半導体/電子部品fab）に部品完成段階の real digital-twin を拡張する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続、"next" 指示。ADR-2607160000で
    isic-2930に導入した digital-twin パターン（実CAD形状+実物理+WebGPUシーン用データ+
    動線計画）の follow-up 対象として同ADRが明示的に isic-2610 を挙げていた）
related:
  - 90-docs/adr/2607160000-cloud-itonami-isic-2930-parts-digital-twin-pilot.md（本ADRが
    拡張するpilotパターンの正本。実装は`cloud-itonami-isic-2930` main
    merge `320b13758756a694829257fc5748f6e6ee538aec` で実証済み）
  - 90-docs/adr/2607151600-cloud-itonami-real-engineering-simulation-integration.md（vdesign.cad/scene/motionplanの原典）
  - 90-docs/adr/2607152000-cloud-itonami-real-engineering-simulation-fleet-extension.md（isic-2610のwire-bond pull-test物理の先例）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-digital-twin
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-2610（fab.simphysics）が org-iso-10303（BREP）への実依存を追加し、
    fab.cad/fab.scene/fab.motionplan をこのvertical の実digital-twin正本コードとする位置づけ"
---

# ADR-2607992500: cloud-itonami-isic-2610（半導体/電子部品fab）に部品完成段階の real digital-twin を拡張する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

ADR-2607160000でisic-2930（自動車部品）に導入したfull digital-twinパターン（実BREP形状 +
実物理 + WebGPUレンダリング用データ + 動線計画）は、`cloud-itonami-isic-2930` main
`320b13758756a694829257fc5748f6e6ee538aec`で実装・検証済み（テスト44→64件全green、
lint clean）——同ADR自身が「他の部品/中間製造業種（isic-2610 fab等）への拡張はスコープ外の
follow-up」と明記していた。

isic-2610（半導体/電子部品fab、スマートフォン供給チェーンの中核工程）の現状
（`fab.simphysics`）はisic-2930の改修前と同型: wire-bond pull-testの実物理タイムステップ
計算はあるが、`anchor-half-w-m`等のAABB寸法は固定定数——「CAD/BREPパイプライン無し」の
状態のまま。

## Decision

isic-2930で確立・検証済みのパターンをisic-2610へそのまま適用する:

1. **`fab.cad`（新規）**: `org-iso-10303`のBREP feature-treeで、lot固有のwire-bond
   specimen envelope（`autoparts.cad`の`envelope-solid`/`envelope-mesh`と同型）を構築。
   dims導出は`fab`の既存lotレコードに実在する寸法系フィールド（無ければ`autoparts.cad`が
   採用した「オプショナルなspecimen寸法フィールド、無指定時は既存固定定数を再現する
   デフォルト」という誠実な設計を踏襲）。
2. **`fab.simphysics`を修正**: 固定AABB定数の使用箇所を、利用可能な場合は`fab.cad`由来の
   実寸法に置き換える（静的な`:anchor`/`:wall`は固定のまま——isic-2930で「移動体のみ
   CAD由来、静的fixtureは固定」とした設計判断をそのまま踏襲）。
3. **`fab.scene`（新規）**: `fab.cad`のtessellated mesh（mm）+ `fab.simphysics`の
   trajectory（m）を`kami.webgpu.mesh`契約形状に変換（面法線計算・単位変換、
   `autoparts.scene`と同型）。
4. **`fab.motionplan`（新規）**: 既存のwire-bond mission手順（wafer-probe電気テスト→
   光学欠陥検査→wire-bond pull-test）を「駅」列としてwaypoint化（新規BOM/assembly-order
   システムは作らない、isic-2930のmotionplanと同じ規律）。
5. **新規物理エンジン・CADカーネル・sibling design-libraryリポジトリは作らない。**
   `org-iso-10303`（新規依存）・`kotoba-lang/physics-2d`（既存依存）・
   `kotoba-lang/webgpu`のcontract形状（データ形状のみ、ランタイム依存にはしない）
   の再利用のみ。

## Consequences

(+) 「部品完成」段階のdigital-twinパターンが2業種目（isic-2610）に拡張——
自動車部品（isic-2930）に続き、半導体/電子部品fab（isic-2610、スマホ供給チェーンの
中核）も実CAD形状+実物理+実レンダリング用データ+動線計画のフルセットを持つ。
(−) isic-2620/2640等、他のスマホ隣接業種のdigital-twin化は本ADRのスコープ外
（isic-2620は別途ADR-2607991500でphysics-onlyの水準まで進行中）。
(−) box近似・2D物理という同じ限定を継承する。

## Verification

実装commit（テスト green・`clojure -M:lint` clean、before/after test count、
`cloud-itonami-isic-2610`のmain merge SHA）をこのADRの参照先で確認できる。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-2610
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（実装済み参照パターン）
- https://github.com/kotoba-lang/org-iso-10303
