---
id: adr-2607994500-cloud-itonami-isic-2651-instrumentation-coverage
title: "ADR-2607994500: cloud-itonami-isic-2651（計測/検査/航法/制御機器製造）coverage actor を新規に起こす"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。ISIC 2670（光学機器）は並行fleetにより
    既に:implementedへ昇格済みと確認 → 残るスマホ隣接業種のギャップとしてISIC 2651
    （計測/検査/航法/制御機器製造、repoすら未作成）を選定）
related:
  - 90-docs/adr/2607993500-cloud-itonami-isic-0729-nonferrous-ore-mining-coverage.md（本ADRが
    scaffold手順を直接踏襲する直近の先例）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-coverage
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-2651 の scope boundary（plant-operations coordination のみ、
    計測機器の直接製造・検査ライン制御権限・校正機関認証権限は対象外）の正本"
---

# ADR-2607994500: cloud-itonami-isic-2651（計測/検査/航法/制御機器製造）coverage actor を新規に起こす

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

`orgs/kotoba-lang/industry/registry.edn`上、ISIC 2651（Manufacture of measuring,
testing, navigating and control equipment）は`:maturity :spec`、`:repo`が
`gftdcojp/cloud-itonami-C2651`という未作成placeholderのまま。ISIC 2670（光学機器、
2026-07-16 ADR-2607162300で並行fleetが:implemented化済み）と同じ「スマホ/車の電子
サプライチェーンに隣接する計測・検査機器製造」カテゴリの残るギャップ。

## Decision

1. **`cloud-itonami-isic-2651`を新規に起こす**（`cloud-itonami-isic-2670`が
   直近確立した"plant-operations coordination"actorパターンを直接踏襲: 生産バッチ
   ロギング・校正/保守スケジューリング・欠陥/不適合フラグ・出荷調整のcoordination-only
   scope、計測機器ラインの直接制御権限・校正機関認証権限は永久に対象外）。
2. **`cloud-itonami-isic-0729`の scaffold 規律をそのまま踏襲**: 必ず
   `clojure -M:dev:test` を実際に実行しgreenを確認、`clojure -M:lint` clean、
   デモ(`clojure -M:dev:run`)実行確認、フレッシュcloneでの独立再検証を経てから
   push/mergeする。
3. **`orgs/kotoba-lang/industry/registry.edn`の2651 entry更新は実装セッションの
   スコープ外——別途、safe single-entry update（現HEADを直前に再取得してから書く）
   で行う。**

## Consequences

(+) スマホ電子部品隣接の計測/検査機器製造カバレッジが1業種追加される。
(−) 依然として計測機器の実製造ライン制御・校正機関としての認証発行は一切モデル化しない。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-0729（直近の直接参照scaffold実装）
- https://github.com/cloud-itonami/cloud-itonami-isic-2670（同カテゴリの直近先例）
