---
id: adr-2608000000-session-closing-summary-saas-catalog-and-cloud-itonami-supply-chain
title: "ADR-2608000000: セッションclosing summary — SaaS競合カタログ・cloud-itonami自動車/スマホサプライチェーン実証・registry成熟度ホールドアウト解消"
status: accepted
date: 2026-07-16
deciders:
  - Jun Kawasaki（「closing」指示によりclose。design-quality ADR-2607132300の
    Closing summary節と同型のパターンを踏襲）
related:
  - 90-docs/adr/2607152600-saas-software-competitive-catalog.md
  - 90-docs/adr/2607160000-cloud-itonami-isic-2930-parts-digital-twin-pilot.md
  - 90-docs/adr/2607999950-cloud-itonami-supply-chain-pedigree-linkage.md
  - 90-docs/adr/2607999990-cloud-itonami-isic-6611-cryptoexchange-maturity-verification.md
  - 90-docs/adr/2607999999-cloud-itonami-isco-blueprint-implementation-batch.md
  - 90-docs/adr/2607132300-kotoba-lang-uiux-design-quality-scoring.md（"closing"指示での
    棚卸しパターンの直接の先例）
supersedes: []
superseded_by: []
last_verified: 2026-07-16
doc_type: adr
topic: session-closing-summary
authoritative: true
authoritative_for:
  - "本セッション（SaaS競合カタログ新設〜cloud-itonami自動車/スマホsupply-chain実証〜
    registry成熟度ホールドアウト解消）の最終棚卸しの正本。個々の判断・詳細根拠は
    related の各ADRを参照する"
---

# ADR-2608000000: セッションclosing summary

**Status**: accepted — closed（2026-07-16、「closing」指示によりclose）。
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki

## Context

本セッションは「cloud.itonamiでSaaS/softwareの世界的product listはEDNで
DataScript/Datomic query可能な形にまとまっているか」という質問から始まり、
そこから「では coverage を向上」→「製造業関係のcoverageは? スマホ・車は原材料から
全てのサプライチェーンが設計実装・シミュレーションできているか」という一連の
問いを経て、SaaS競合カタログの新設と、cloud-itonami自動車/スマートフォン
サプライチェーンの実データ連携実証、registry全体の成熟度ホールドアウト解消へと
発展した。design-quality ADR-2607132300が確立した"closing"指示での棚卸し
パターンをそのまま踏襲し、正直な最終状態を記録する。

## 正本ファイル（本セッションでauthoritativeとした成果）

- `90-docs/saas-catalog/saas-catalog.datoms.edn` + `saas-catalog-ledger.edn` —
  世界のSaaS/software製品カタログ（158製品/31カテゴリ、DataScript/Datomic
  transactable EDN）。
- `kotoba-lang/pedigree`（新規リポジトリ）— cloud-itonami vertical間の実データ
  連携（material pedigree / certificate-of-conformance）contractライブラリ。
- `cloud-itonami-isic-{2930,2610,0810,2394,4211,4741}` — digital-twin
  （実CAD形状+実物理+WebGPUシーン用データ+動線計画）拡張済み6業種。
- `cloud-itonami-isic-{2620,2811,2410,2211,2431,2013}` — robotics-process-
  simulation（実物理point-test）新規追加済み6業種。
- `cloud-itonami-isic-0710`（鉄鉱石採掘、コンパイルエラー修正＋昇格）・
  `cloud-itonami-isic-0729`（非鉄金属鉱業、新規構築）。
- `cloud-itonami-isic-{3331,5419,8343,9321,9329}`（ISCO occupation actor、
  ゼロから新規実装）。
- `cloud-itonami-isic-6611-cryptoexchange`（既存の大規模実装を実測検証の上、
  `:blueprint`→`:implemented`昇格）。
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` /
  `orgs/kotoba-lang/occupation/resources/kotoba/occupation/registry.edn` —
  上記の成熟度変更を反映（ISIC・ISCO ともに`:blueprint`ホールドアウト0件）。

## 実際に着地した成果（実測、fresh cloneで再検証済み）

### Supply-chain pedigree連携（本セッションの最大の成果）

自動車チェーン: **isic-0710（鉄鉱石採掘）→isic-2410（粗鋼）→isic-2930（自動車部品）
→isic-2910（完成車）**、4アクター・3ホップ。isic-0710は物理シミュレーションでなく
実運用記録データ（鉱石品位）が根拠という初のパターンも確立。isic-2910の実際の
governorが、2階層下（鉱石）の不正データを実際に検知・ブロックすることを、
本物のクロスリポジトリ呼び出し（fixtureでない）で実証。

スマホチェーン: **isic-0729（非鉄金属鉱業）→isic-2610（半導体fab）→isic-2630
（スマホ本体組立）**、3アクター・2ホップ。同水準の実証。

いずれも「pure data、no I/O、no network」という既存の全actor共通規律を一切
破らずに実現（連携はビルド時classpath依存＋通常の関数呼び出しであり、
ランタイムのネットワーク呼び出しは一切無い）。

### Digital-twin拡張（6業種）・robotics-simulation新規追加（6業種）

isic-2930の実装を最初のpilotとして確立したパターン（実BREP形状+実物理+WebGPU
シーン用データ+動線計画）を5業種へ拡張、さらに物理シミュレーションを一切
持たなかった6業種にsimulationを新規追加。各実装は独立にorg-iso-10303の
実際のcapability（円柱revolveが既に実装済みだった等）や、幾何/軌道不変性の
実際の挙動差異（floating-pointの際どいタイミング問題等）を検証しながら発見・
記録している。

### Registry成熟度ホールドアウトの完全解消

ISIC（648 entry）・ISCO（436 entry）いずれも、`:blueprint`（スコープ確定・
未実装）のまま残っていたentryが本セッション終了時点で0件になった。isic-6611
（暗号資産取引所）は実は既に大規模な実装が存在しており実測187テストで検証・
昇格、ISCO側5件は逆に完全に未着手だと判明したためゼロから実装・検証・昇格した。

## 見つけた実バグ（正直に記録する — no silent caps）

1. **`cloud-itonami-isic-0710`のgovernor_test.clj compile error**
   （private var直接参照）— **修正済み**。副産物として`ironopsllm.cljc`の
   `read-string`安全性問題も修正。
2. **`kotoba-lang/robotics`の`:local/root`推移的依存（html/css）がCI workflow
   でcheckoutされていない** — `cloud-itonami-isic-{2410,2620,2394,2630,2930,
   4741}`の6リポジトリで**修正済み**（ただしcloud-itonami orgはGitHub Actions
   自体が未有効化のため、実際のCI実行では未検証——各ci.yml冒頭コメントに明記済み）。
3. **`cloud-itonami-isco-0110`の`actor.cljc`が実在しない`graph/state-graph-
   builder`関数を呼んでおり、`run-request!`が明示的なstub**（一度もグラフを
   実行しない）だった — 本セッションでゼロから実装したISCO 5業種すべてが
   独立にこの同一欠陥を発見・修正しており、参照元自体（isco-0110）にも
   **還元して修正済み**。5業種以外の`:implemented` ISCO actor 5件のサンプル
   調査では同種の欠陥は見つからず、広範囲への伝播は無いと判断（**ただし211+件
   中5件のサンプルであり網羅的監査ではない**）。
4. **`orgs/kotoba-lang/industry/registry.edn`が並行fleet活動により単一行・
   コメント無し形式へ regression**（本セッションで書いた検証コメント含め、
   過去の全annotation/audit-trailが失われた）— 別の並行fleetセッションが
   commit `cc8665a`を原因として既に発見・「repo owner awareness」向けに
   記録済みであることを確認。**本セッションでは追加対処せず**（owner判断待ち）。

## 未着手のまま残っている項目（次回セッションへの引き継ぎ、優先順）

1. **supply-chain pedigreeの実データ受け渡しは、いまだtest/demoスクリプト
   レベル**——本番運用の自動データパイプラインではない（3件のpedigree ADR
   いずれも明記済み）。実際にactorが稼働する運用フローへ組み込むのは
   follow-up。
2. **isic-2620（コンピュータ/周辺機器）はスマホチェーンのpedigree連携に
   含まれていない**——robotics-simulationは追加済みだが、pedigree発行/
   受入は未実装。
3. **`kotoba-lang/industry`のregistry.edn format regression自体は未修正**
   （owner判断待ち、上記バグ4）。
4. **ISCO側の`state-graph-builder`欠陥の伝播範囲についてのサンプル調査（5件）は網羅的でない**
   ——211+216件全体の監査は行っていない。
5. **各actorのDatomicStoreバックエンドで一部フィールドが未persist**という
   同型の小さなgapが複数actor（steelworks等）で個別に開示されている——
   横断的にDatomicStore実装を揃える機会があるかもしれない。
6. **ISIC/ISCO双方とも数百件の`:spec`entryが未着手のまま残っている**
   （本セッションのスコープ外、並行fleetの継続作業次第）。

## Consequences

正: cloud-itonamiの「個々のverticalは独立サイロ」という本セッション自身が
発見した構造的ギャップに対し、実際に動くsupply-chain連携パターンを確立・
2チェーンで実証した。registryの成熟度データは（format regressionを除き）
実態と一致した状態になった。5つの独立実装が同一の参照テンプレート欠陥を
発見したことで、その欠陥の実在性が強く裏付けられ、参照元への還元修正まで
できた。

負: pedigree連携は依然としてtest/demoレベルであり本番運用ではない。
registry.edn自体のannotation historyは失われたまま。ISCO側の欠陥伝播調査は
サンプルのみで網羅的でない。

## References

related節の各ADR、および本文中で言及した個々のcommit/merge SHA（各リンク先
リポジトリのcommit historyで確認可能）。
