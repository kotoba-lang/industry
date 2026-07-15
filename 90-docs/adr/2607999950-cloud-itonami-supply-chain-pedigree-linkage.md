---
id: adr-2607999950-cloud-itonami-supply-chain-pedigree-linkage
title: "ADR-2607999950: cloud-itonami の独立サイロvertical間に実データ連携（material pedigree）を導入する — isic-2410→isic-2930 pilot"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。本セッションで「各ISIC vertical actor
    は完全に独立したサイロで、部品actorの出荷データが完成車actorのBOMに流れ込むような
    supply chain全体のライブ連携が存在しない」ことを発見・報告 → AskUserQuestionで
    「supply-chain連携の設計に切り替える」を選択）
related:
  - 90-docs/adr/2607160000-cloud-itonami-isic-2930-parts-digital-twin-pilot.md
  - 90-docs/adr/2607999600-cloud-itonami-isic-2410-robotics-simulation.md（本ADRの上流側pilot対象、鋼材引張試験）
  - 90-docs/adr/2607142800-cloud-itonami-robotics-process-simulation.md（"pure data, no I/O, no network"という既存規律の直接の踏襲元）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-supply-chain-connectivity
authoritative: true
authoritative_for:
  - "kotoba-lang/pedigree を cloud-itonami vertical間の実データ連携（material pedigree
    /certificate-of-conformance相当）の正本contractライブラリとする位置づけ"
  - "isic-2410→isic-2930 を『上流actorのexportが下流actorのproposeへ実データとして
    渡り、下流governorが独立検証する』という連携パターンのpilotとする位置づけ。
    isic-2930→isic-2910 への拡張は本ADRのスコープ外のfollow-up"
  - "連携は常にオフライン・pure-data（EDN受け渡し）で行い、actor間の直接ネットワーク
    呼び出しは一切行わない、という不変条件の正本"
---

# ADR-2607999950: cloud-itonami の独立サイロvertical間に実データ連携（material pedigree）を導入する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

本セッションで、`cloud-itonami-isic-2910`（完成車組立）の実コードを確認したところ、
`cloud-itonami-isic-2930`（自動車部品）や`cloud-itonami-isic-2410`（粗鋼）への参照が
一切無いことが判明した。ISIC分類上の「上流/下流」関係は概念的な整理に過ぎず、
実行可能なデータグラフとして繋がっていない——個々のvertical actorが`:implemented`
であっても、真の意味でのサプライチェーンシミュレーションにはなっていない。

一方、`cloud-itonami`の全actorは一貫して「Pure data + pure functions -- no real
robot I/O, no network」という規律を守っており（`kotoba.robotics`の"policy, not
control"境界、各`*.robotics`namespaceのdocstringで繰り返し明記）、各`export.cljc`
（`autoparts.export`等）は「社会的/規制上のhand-off用のEDN/CSVパッケージを生成する
pure data transform、署名・提出は人間の行為」と定義されている。つまり
**ネットワーク越しのライブAPI呼び出しでactorを繋ぐことは、この規律に反する。**

## Decision

**「実データが受け渡される、しかし各関数はオフラインpure-dataのまま」という連携方式
を採用する** — 上流actorの`export`が生成するEDN record（material pedigree /
certificate-of-conformance相当）を、下流actorの`propose`系オペレーションが**引数として
受け取り**（ネットワークfetchではなくデータとして渡される。実際の受け渡しは人間/
orchestrationスクリプトが行う、demo/pipelineレベルの配線）、下流governorがその
pedigreeの主張（claims）を独立検証する。これは既存の"ground truth, not self-report"
disciplineを**サプライチェーンレベルに拡張**したもの。

1. **新規共有contractライブラリ `kotoba-lang/pedigree`** を作る（`kotoba-lang/robotics`
   と同型の、pure data schema + 検証関数のみの小さなライブラリ）:
   ```clojure
   {:pedigree/id "..."                      ; 発行ID
    :pedigree/subject-lot-id "..."          ; 対象ロット/バッチID
    :pedigree/issuing-actor "cloud-itonami-isic-2410"  ; 発行元actor（business-id）
    :pedigree/claims {:tensile-test-mpa 480.0 ...}      ; 検証済み主張（実測値のみ、自己申告文字列は不可）
    :pedigree/evidence-basis ["source citation" ...]
    :pedigree/issued-at "2026-07-15"}
   ```
   `pedigree/valid?`（schema形状検証）等の純粋関数のみ、I/O無し。
2. **Pilot: isic-2410 → isic-2930（鋼材引張試験 → 部品ロット）:**
   - `steelworks.export`（新規または既存export.cljc拡張）に、あるheatの実
     `:sim-tensile-strength-mpa`（ADR-2607999600で追加済みの実物理シミュレーション
     結果）から`pedigree/claims`を構築する`pedigree-for-heat`を追加。
   - `autoparts.facts`/`autoparts.registry`に、part-lot proposalが**任意で**
     `:upstream-pedigree`（isic-2410由来のpedigree EDN）を持てるフィールドを追加。
   - `autoparts.governor`に新しいHARD check
     `upstream-pedigree-claims-out-of-tolerance?`を追加: pedigreeが存在する場合、
     その`:claims`の`:tensile-test-mpa`がisic-2930自身の受入基準を満たすか独立検証
     する（pedigree無しの既存proposalは今まで通り通る——破壊的変更にしない）。
   - 実際にisic-2410のexportで生成した実pedigree EDNを、isic-2930のdemo/testで
     実際に使い、独立検証が機能することを実証する（フィクスチャではなく、
     isic-2410の実exportコードを呼び出した結果を使う）。
3. **isic-2930 → isic-2910（部品ロット → 完成車BOM）への拡張は本ADRのスコープ外**
   ——pilotの結果を見てfollow-up判断する（自動車完成車pilot→6業種拡張と同型の
   段階的ロールアウト）。
4. **新規物理エンジン・CADカーネルは作らない。** 新規に作るのは
   `kotoba-lang/pedigree`という小さなpure-dataライブラリのみ。
5. **registry.edn更新は本ADRのスコープ外。**

## Consequences

(+) cloud-itonamiのvertical間に、初めて「実データが実際に流れる」連携が生まれる
——ISIC分類上の隣接ではなく、実行可能な検証パスとして。
(+) 各actorの"pure data, no I/O, no network"という既存規律を一切破らない
——連携はデータ引数の受け渡しであり、ネットワークRPCではない。
(−) pilotは isic-2410→isic-2930 の1リンクのみ。isic-2930→isic-2910・他の
サプライチェーンは未対応、follow-up。
(−) 「実際の受け渡し」（isic-2410のexport出力をisic-2930のinputに渡す配線）は
demo/pipelineスクリプトレベルであり、まだ本番運用のデータパイプラインではない
——正直に開示する。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-2410
- https://github.com/cloud-itonami/cloud-itonami-isic-2930
- https://github.com/kotoba-lang/robotics（同型の小さなpure-data contractライブラリの先例）
