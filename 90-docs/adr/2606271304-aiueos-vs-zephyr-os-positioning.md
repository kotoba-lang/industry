---
id: adr-2606271304-aiueos-vs-zephyr-os-positioning
title: "ADR-2606271304: aiueos の立ち位置 — Zephyr OS（RTOS）との特徴差で定義する"
status: active
doc_type: adr
topic: aiueos-positioning
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - aiueos と Zephyr OS の特徴差（設計思想・分離境界・記述方法・成熟度）の正準整理
  - aiueos を「RTOS の置き換え」ではなく「能力安全な Wasm-component OS / 別レイヤ・別ゴール」と位置づける判断
  - 比較の根拠とした aiueos の現状フェーズ（Phase 0 substrate、マイクロカーネル等は将来フェーズ）
related:
  - com-junkawasaki/aiueos/README.md
  - com-junkawasaki/aiueos/SECURITY.md
  - com-junkawasaki/aiueos/90-docs/adr/0001-aiueos-phase0-capability-os.md
  - com-junkawasaki/aiueos/90-docs/adr/0002-host-abi-topic-bus.md
  - com-junkawasaki/aiueos/90-docs/adr/0004-code-as-data-admit.md
supersedes: []
superseded_by: []
---

# ADR-2606271304: aiueos の立ち位置 — Zephyr OS（RTOS）との特徴差で定義する

**Status**: accepted
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

「aiueos と Zephyr OS は何が違うのか」という問いが繰り返し出る。両者とも "OS" と
名乗るため、同じカテゴリの競合のように誤読されやすいが、実際には **目的・分離境界・
記述方法・成熟度が異なるレイヤの別物**である。口頭・記憶ベースの曖昧さを避けるため、
両者の特徴差を ADR として固定し、aiueos の立ち位置を「Zephyr の置き換え」ではなく
「能力安全な Wasm-component OS」として明文化する。

比較の根拠（aiueos 側）は `com-junkawasaki/aiueos/README.md`（Phase 0 substrate 時点）。
Zephyr 側は公知の RTOS（Linux Foundation プロジェクト）としての一般事実に拠る。

## Decision

### 1. 一言でのコントラスト

| | **Zephyr OS** | **aiueos** |
|---|---|---|
| 正体 | 実機マイコン向けの小型 **RTOS（カーネル）** | OS を「意味づけされた capability component の graph」として扱う **能力安全な Wasm-component OS** |
| 主目的 | 限られたハード上でリアルタイム・省リソースに動かす | 信頼境界・能力制御で「壊れても封じ込められる」OS、AI 生成コードを第一級で扱う |
| 成熟度 | 本番採用多数の成熟プロジェクト | 設計実験＋初期実装（Phase 0 substrate） |

### 2. 設計思想の違い

**Zephyr OS** — 古典的 RTOS。中核は **スレッド + スケジューラ + IPC**
（mutex / semaphore / message queue）。C 言語が主、**Devicetree + Kconfig** で
ハードと構成を記述。アプリとカーネルを単一バイナリにリンクして実機へ焼く
（MMU 非前提の MCU でも動く）。関心事は **リアルタイム性・省メモリ・移植性**
（多数のボード対応）。保護は主にコンパイル時構成 + 任意の MPU ベース `User Mode` で、
既定は「同一アドレス空間で速く動かす」。

**aiueos** — OS を「プロセスの集合」ではなく **capability component のグラフ** と
みなす。すべてが **kotoba（EDN）= データ**（マニフェスト・ポリシー・デバイス
スキーマ・監査ログまで OS が推論する対象）。各コンポーネントは **Wasm** で隔離され、
**deny-by-default の能力**しか触れない。能力は規約ではなく **実行時にゲートが検査**
（無い能力を呼ぶと trap）。**AI 生成コードを第一級**で扱い（`:ai-generated` は
untrusted・ephemeral・network/secrets/persistence を既定で拒否、`aiueos admit` が
front door）、同じコンポーネントが Wasm エンジンのある所（edge / robotics / cloud /
browser / client）どこでも動く。

### 3. 具体的な対比

| 観点 | Zephyr | aiueos |
|---|---|---|
| 実行単位 | ネイティブ thread | Wasm component |
| 分離境界 | （任意の）MPU User Mode、基本は同一空間 | コンポーネント単位 + **個別トピック単位**の能力隔離 |
| セキュリティモデル | MPU/権限、構成時の絞り込み | **能力ベース（deny-by-default）+ 小さい TCB + append-only 監査** |
| 記述方法 | C + Devicetree + Kconfig | **EDN（kotoba）データ**、ポリシーリゾナが推論 |
| ドライバの DMA | ドライバはカーネル内信頼 | DMA effect は **IOMMU 必須をポリシーで強制**（ドライバを TCB から追い出す前提） |
| ロボティクス | 別途 micro-ROS 等が必要 | **pub/sub トピックバス（ROS topic 相当）を内蔵**、sensor→planner→actuator デモあり |
| AI 連携 | 想定外 | **設計の中心**（code-as-data、`--edn` でエージェントが全ライフサイクルを駆動） |
| 現状の実体 | 実機で動く完成 RTOS | ホスト OS 上の Phase 0 substrate（マイクロカーネル等は将来フェーズ） |

### 4. 立ち位置の結論

- **Zephyr** = 実機マイコンをリアルタイム・省リソース・高移植性で動かす実用 RTOS。
  境界は **性能寄り**。
- **aiueos** = OS の構造そのものを能力安全なデータグラフとして再定義し、AI が書いた
  コードでも封じ込められることを狙う実験的 Wasm-component OS。境界は
  **セキュリティ（封じ込め）寄り**。

両者は「同じ OS というカテゴリの競合」ではなく **別レイヤ・別ゴール**。aiueos は
Zephyr の置き換えを目指さない。aiueos の差別化要因は **能力安全・code-as-data・
AI ネイティブという設計モデルの新規性**にあり、Zephyr の差別化要因は **RTOS としての
成熟・実用性**にある。

## Consequences

- (+) 「aiueos は Zephyr の置き換え／競合か？」という誤読に対する正準回答ができる。
- (+) aiueos の設計判断（Wasm 隔離・deny-by-default 能力・IOMMU ゲート・トピックバス）の
  *理由* を、RTOS との対比という形で外部に説明できる。
- (+) ロードマップ上の将来フェーズ（マイクロカーネル・実デバイス ABI）を、Zephyr が
  既に持つ領域として位置づけられ、何が「未実装の既知領域」かが明確になる。
- (−) 本 ADR は aiueos Phase 0 substrate 時点のスナップショット。後続フェーズ
  （マイクロカーネル / 実ドライバ / スケジューラ）で実機リアルタイム領域に踏み込めば、
  Zephyr との比較軸（特に分離境界・成熟度）は更新が必要になる。
- (−) Zephyr 側は公知事実に拠るため、特定バージョン・特定構成（例: 最新の User Mode /
  MPU 構成）での厳密比較ではない。厳密なベンチ（リアルタイム性・フットプリント）が
  必要なら別途実測する。

## Notes

- aiueos の README は「これは無敵の主張ではなく封じ込めのアーキテクチャ」と明言
  （`com-junkawasaki/aiueos/SECURITY.md` 参照）。本 ADR の「セキュリティ寄り」は
  invulnerability ではなく containment を指す。
- 本 ADR を supersede する場合は、aiueos のフェーズ進行（マイクロカーネル稼働・
  実デバイス ABI）に合わせて分離境界・成熟度の行を更新すること。
