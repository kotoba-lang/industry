---
id: adr-2607999999-cloud-itonami-isco-blueprint-implementation-batch
title: "ADR-2607999999: cloud-itonami ISCO occupation registry の5件の :blueprint を :implemented へ実装する"
status: accepted
date: 2026-07-16
deciders:
  - Jun Kawasaki（ADR-2607999995でISCO側の5件の:blueprintホールドアウトを検証し、
    全て空のscaffold（src/無し）だと判明——ユーザー指示「next」でゼロからの実装に着手）
related:
  - 90-docs/adr/2607999995-cloud-itonami-isco-blueprint-maturity-verification.md（本ADRの前段検証、5件とも未実装と確定した根拠）
  - 90-docs/adr/2607999990-cloud-itonami-isic-6611-cryptoexchange-maturity-verification.md（"必ず実測してから成熟度を判断する"規律の直接の踏襲元）
supersedes: []
superseded_by: []
last_verified: 2026-07-16
doc_type: adr
topic: cloud-itonami-occupation-actor-implementation
authoritative: true
authoritative_for:
  - "cloud-itonami-isco-{3331,5419,8343,9321,9329} の実装が、既存の:implementedな
    ISCO actor（cloud-itonami-isco-0110等）と同型の governed-actor pattern
    （LLM advisor ⊣ 独立Governor、langgraph、pure data/no I/O）に従うことの正本"
---

# ADR-2607999999: cloud-itonami ISCO occupation registry の5件の :blueprint を :implemented へ実装する

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki

## Context

ADR-2607999995の検証（実際に5リポジトリをcloneし`clojure -M:test`を実行）で、
`cloud-itonami-isco-{3331,5419,8343,9321,9329}`の5件は`src/`/`test/`が
一切存在しない空のscaffold（governance文書とblueprint.edn/deps.ednのみ）だと
確定した。各blueprint.ednには既に設計意図（domain・governor名・
required-technologies）が明記されており、いずれも**「独立事業者としてこの
職業を営む個人」をLLM advisor ⊣ 独立Governorで支援する"Independent Practice"型
actor**として設計済み:

- isco-3331: Independent Customs Clearing & Freight Forwarding Practice
  （`:customs-clearing-governor`、`[:robotics :identity :forms :audit-ledger]`）
- isco-5419: Independent Protective Services Practice
  （`:protective-services-governor`、`[:robotics :identity :audit-ledger]`）
- isco-8343: Independent Crane & Hoist Operations Practice
  （`:crane-operations-governor`、`[:robotics :telemetry :audit-ledger]`）
- isco-9321: Independent Packing & Fulfillment Practice
  （`:packing-fulfillment-governor`、`[:robotics :forms :audit-ledger]`）
- isco-9329: Independent Manufacturing Support Labour Practice
  （`:manufacturing-labour-governor`、`[:robotics :forms :audit-ledger]`）

既存の`:implemented`なISCO actor（`cloud-itonami-isco-0110`、Commissioned
Officer Administrative Assistant）を確認したところ、ISIC製造業actorより
軽量な構成（`src/<domain>/{actor,advisor,governor,store}.cljc`の4ファイル、
`kotoba-lang/langgraph`への単一pinned git-coordinate依存のみ）だった。

## Decision

1. **5件を`cloud-itonami-isco-0110`と同型の軽量パターンで実装する**:
   `<domain>.actor`（langgraph StateGraphでの orchestration）・
   `<domain>.advisor`（LLM advisor、mockと実LLM両対応、既存の
   `ironopsllm.cljc`等と同じ"deterministic mock + real-LLM切替"パターン）・
   `<domain>.governor`（独立検証、各blueprint.ednの`domain`に応じた
   HARD check——例: crane-operationsなら`:telemetry`必須要件を活かした
   吊り荷重超過チェック、customs-clearingなら通関書類の spec-basis チェック等）・
   `<domain>.store`（MemStore、pure data）。
2. **各occupationの実職務内容に即した、正直で現実的なscope boundary**を
   設定する（isic-0710の"coordination only、採掘権限は対象外"と同じ規律）:
   例えばisco-8343（クレーン操作）なら実際のクレーン制御権限は対象外
   （"policy, not control"）、isco-5419（警備）なら実力行使の権限は対象外、等。
3. **`kotoba-lang/langgraph`への単一pinned git-coordinate依存のみ**
   （isco-0110と同じ、`:local/root`不使用——独立事業者向けの軽量actorという
   scaleに合わせる）。新規技術ライブラリ・新規物理エンジンは作らない。
4. **各actorに実テストを書き、`clojure -M:test`で実際にgreenを確認してから
   完了と報告する。** isic-0710の教訓（推測での昇格）を絶対に繰り返さない。
5. **5件は完全に独立**——並行して実装してよい。
6. **`kotoba-lang/occupation`のregistry.edn更新**（`:maturity :blueprint`→
   `:implemented`、二重エンコード構造に注意した安全な更新、
   `test/kotoba/occupation_test.clj`の対応するcorroboration assertion更新も
   含む）は、各actorが実際にgreenになった後にのみ行う。

## Consequences

(+) ISCO registryの最後の未実装ホールドアウト5件が実装される——ISIC・ISCO
両方のregistryが「:spec（未着手）か:implemented（実測検証済み）のいずれか」
という状態に揃う。
(−) 5件とも新規スコープの職業ドメイン（通関・警備・クレーン操作・梱包・
製造支援労働）——既存の manufacturing/mining actorとは異なる governance
判断（実力行使権限・法的資格等）が必要になる場面があり、正直な
scope-boundary設定が重要。

## References

- https://github.com/cloud-itonami/cloud-itonami-isco-0110（実装済み参照パターン）
- https://github.com/cloud-itonami/cloud-itonami-isco-3331
- https://github.com/cloud-itonami/cloud-itonami-isco-5419
- https://github.com/cloud-itonami/cloud-itonami-isco-8343
- https://github.com/cloud-itonami/cloud-itonami-isco-9321
- https://github.com/cloud-itonami/cloud-itonami-isco-9329
