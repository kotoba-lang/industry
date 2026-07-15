---
id: adr-2607993500-cloud-itonami-isic-0729-nonferrous-ore-mining-coverage
title: "ADR-2607993500: cloud-itonami-isic-0729（非鉄金属鉱業＝銅・リチウム・レアアース等の採掘）coverage actor を新規に起こす"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。本セッションの当初の質問
    「スマートフォン・車は原材料から全て設計実装・シミュレーションできているか」の
    回答で、原材料採掘段階（ISIC 0710/0729）が唯一の未実装ギャップと確認済み——
    0710は実装を試みてコンパイルエラーでrevert済み、0729はリポジトリすら存在しない）
related:
  - 90-docs/adr/2607141920-cloud-itonami-isic-0710-iron-ore-mining-coverage.md（本ADRが
    scope/patternを直接踏襲する先例。coordination-onlyの境界設計）
  - orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn（0729 entry:
    :maturity :spec、:repo が gftdcojp/cloud-itonami-B0729という未作成placeholder）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-raw-materials
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-0729 の scope boundary（coordination-only、採掘/爆破/鉱山安全権限は
    永久に対象外）の正本"
---

# ADR-2607993500: cloud-itonami-isic-0729（非鉄金属鉱業）coverage actor を新規に起こす

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

本セッション冒頭で「スマートフォン・車は原材料から全てのサプライチェーンが設計実装・
シミュレーションできているか」と問われ調査した結果、両サプライチェーンに共通する
唯一かつ最上流のギャップが**原材料採掘段階**だと判明した:

- ISIC 0710（鉄鉱石採掘）: `orgs/kotoba-lang/industry/registry.edn`上 `:maturity :spec`。
  実装を試みたが「test file references a private var
  (governor/forbidden-operation-violations not public) -- compile error」で
  **revert済み**（ADR-2607141920、registryのinline comment参照）。
- ISIC 0729（非鉄金属鉱業＝銅・リチウム・ニッケル・コバルト・レアアース等）:
  `:maturity :spec`、`:repo`が`gftdcojp/cloud-itonami-B0729`という**未作成の
  placeholder URL**のまま。これはEV電池（リチウム/コバルト/ニッケル）・
  スマートフォンの半導体/電子部品（銅配線・レアアース磁石・タンタル等）の両方に
  直結する最上流の鉱物資源であり、ISIC 0710より両サプライチェーンへの関連度が高い。

## Decision

1. **`cloud-itonami-isic-0729`を新規に起こす**（`cloud-itonami` orgの正式命名規則、
   `cloud-itonami-isic-XXXX`。`gftdcojp/cloud-itonami-B0729`というplaceholder名は
   使わない——0710が`gftdcojp/cloud-itonami-B0710`から`cloud-itonami/
   cloud-itonami-isic-0710`へ移行した先例と同じ）。
2. **Scope boundary は0710と同型: COORDINATION ONLY。** 採掘（extraction）・
   発破（blasting）・鉱山安全権限（mine-safety-authority）の意思決定は
   永久に対象外、人間の専門家へ常にエスカレーション。提案可能な操作は0710の4種を
   直接移植する: 産出量/品位ロギング・設備保守スケジューリング・安全懸念フラグ
   （常時エスカレーション）・出荷調整。commodity固有の部分のみ非鉄金属鉱業向けに
   調整する（品位=grade、鉱石の種類=copper/lithium/rare-earth等のenum）。
3. **0710の失敗から学ぶ**: 実装は必ず `clojure -M:dev:test` を実際に実行しグリーンを
   確認してから`:implemented`を名乗る。private varをtestから参照する等の
   コンパイルエラーを事前にlint（`clojure -M:lint`）で検出する。
4. **`orgs/kotoba-lang/industry/registry.edn`の0729 entry更新は本ADR実装セッションの
   スコープ外——別途、safe single-entry update（現HEADを直前に再取得してから書く）
   で行う。** 並行fleetによる同ファイルへの高頻度な同時編集を踏まえ、衝突リスクを
   下げるため実装（新規repo構築）とregistry更新を分離する。

## Consequences

(+) 「原材料から全て」というサプライチェーン主張に、鉄鉱石に続き非鉄金属鉱業
（銅・リチウム・レアアース等、両サプライチェーンの真の上流）のcoordination-only actor
が加わる。
(−) 依然として「採掘そのもの」（extraction/blasting）は一切モデル化しない
——coordination-onlyの境界は0710と同じ、意図的な限定。
(−) registry.edn自体の更新は本ADRのスコープ外、別途実施。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-0710（直接の参照実装）
- 90-docs/adr/2607141920-cloud-itonami-isic-0710-iron-ore-mining-coverage.md
