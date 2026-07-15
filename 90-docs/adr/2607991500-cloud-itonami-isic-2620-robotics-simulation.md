---
id: adr-2607991500-cloud-itonami-isic-2620-robotics-simulation
title: "ADR-2607991500: cloud-itonami-isic-2620（コンピュータ/周辺機器製造）に real physics-2d robotics-process-simulation を追加する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」の初回実行。スマートフォン供給網ギャップ
    調査（本セッション）で isic-2620/2630/2640 は robotics-process-simulation が無いと
    指摘 → 再確認したところ isic-2630（通信機器＝スマホ本体組立）は並行fleetにより
    直近で physics-2d 実物理（OCAラミネートプレス）を獲得済みと判明。残る isic-2620を
    同水準に引き上げる）
related:
  - 90-docs/adr/2607142800-cloud-itonami-robotics-process-simulation.md（原典パターン）
  - 90-docs/adr/2607152000-cloud-itonami-real-engineering-simulation-fleet-extension.md（isic-2930等への物理拡張の先例）
  - 90-docs/adr/2607160000-cloud-itonami-isic-2930-parts-digital-twin-pilot.md（同セッションの前段作業）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-digital-twin
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-2620（deviceassembly.robotics）が kotoba-lang/physics-2d への
    実git-coordinate依存を取り、connector/コネクタ嵌合力試験相当の real time-stepped
    物理シミュレーションを持つことの位置づけ"
---

# ADR-2607991500: cloud-itonami-isic-2620 に real physics-2d robotics-process-simulation を追加する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

本セッションで「スマートフォンの製造チェーン」を調査した際、isic-2620（コンピュータ/
周辺機器製造）・isic-2630（通信機器＝スマホ本体組立）・isic-2640（民生用電子機器）の
3業種は robotics-process-simulation を一切持たない（symbolicですら無い）ギャップだと
指摘した。直後に再確認したところ、isic-2630 は並行して走っている fleet により
`kotoba-lang/physics-2d` を使った実物理（ディスプレイモジュールのOCA光学接着ラミネート
プレス試験、と deps.edn のコメントに明記）を既に獲得していた——このギャップは自然に
埋まりつつある。残る isic-2620 を同水準に引き上げる。

isic-2620 の現状（`deviceassembly.robotics`）は ADR-2607142800 型の symbolic simulation
のみ: `thermal-margin-deviation-actual` という自己申告フィールドを静的比較するだけで、
実際のタイムステップ物理は無い。

## Decision

1. **`deviceassembly.robotics` に `kotoba-lang/physics-2d` への実依存を追加**し、
   コンピュータ/周辺機器製造にとって現実的で開示済みのQA試験手順を1つ、physics-2dの
   AABB衝突/接触モデルで再現する。**具体的な試験手順の選定は実装セッションが行い、
   docstringで根拠を開示する**（例: コネクタ嵌合力試験 [IEC 60512系、USB-C/HDMI等の
   実接続コネクタの挿抜力仕様]、筐体スナップフィット嵌合力試験など、isic-2930の
   weld/fastener pull-test・isic-2630のOCAラミネートプレスと同じ「タイムステップ物理で
   自然に表現できる、実在するQA工程」の選定規律に従う）。
2. **既存の`thermal-margin-deviation`ベースの検査は変更しない**（burn-in/EMCの
   symbolic checkは別の実世界検査であり、物理シミュレーションで置き換える対象ではない
   ——新しい物理検査を追加で持たせる、isic-2930/2630と同型の構成）。
3. **新規物理エンジン・CADカーネル・Rustは書かない。** `kotoba-lang/physics-2d`の
   再利用のみ。新規sibling design-libraryリポジトリも作らない。

## Consequences

(+) スマートフォン供給チェーンの残るrobotics-simulationギャップ（isic-2620/2640のうち
isic-2620）が閉じる。
(−) isic-2640（民生用電子機器）は本ADRのスコープ外——次のloop iterationのfollow-up
候補として残す。
(−) CAD形状/WebGPUレンダリング/動線計画（isic-2930で追加中のフルdigital-twin要素）は
本ADRのスコープ外——isic-2620はADR-2607152000型（物理point-testのみ）の水準に留める。

## Verification

実装commit（テスト green・`clojure -M:lint` clean、before/after test count）をこの
ADRの参照先リポジトリのcommit historyで確認できる。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-2620
- https://github.com/cloud-itonami/cloud-itonami-isic-2630（直近の physics-2d 統合の実例）
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（weld/fastener pull-test の実例）
- https://github.com/kotoba-lang/physics-2d
