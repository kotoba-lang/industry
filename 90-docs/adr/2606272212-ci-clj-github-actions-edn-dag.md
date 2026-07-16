---
id: adr-2606272212-ci-clj-github-actions-edn-dag
title: "ADR-2606272212: ci-clj — GitHub Actions ワークフローを EDN/Clojure データとして扱い、job-DAG をプランニングする再利用ライブラリ。model(id-keyed DAG) + validate + yaml(already-parsed map の変換) + execute(wave プランナー)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - GitHub Actions ワークフローを Clojure で第一級の EDN データとして表現する正準モデルの定義
  - ci-clj の責務境界(モデル/検証/YAML データ I/O/wave プランナー)の設計
  - YAML テキストを直接パースしない戦略(host が解析済み map を渡す)の採用理由
  - CI 成果物の 3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/com-junkawasaki/ci-clj                          # 本 ADR のライブラリ
  - orgs/com-junkawasaki/bpmn-clj                        # 同型の再利用 kernel(先例)
  - orgs/com-junkawasaki/dmn-clj                         # 同型の再利用 kernel(先例)
  - 90-docs/adr/2606272200-bpmn-clj-edn-process-library  # 設計の原型となった ADR
supersedes: []
superseded_by: []
---

# ADR-2606272212: ci-clj — GitHub Actions ワークフローを EDN で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。YAML パーサ注入は host 側の責務）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

GitHub Actions ワークフロー(.github/workflows/\*.yml)を機械的に生成・検証・
分析する需要がある。既存のアプローチは (1) YAML テキストを直接 git に置き
手動管理するため diff が困難、(2) workflow の job 依存グラフ(DAG)を静的に
検証する標準ツールがなく、循環依存や参照切れが CI 実行時まで発覚しない、
(3) YAML パーサは各 host(JVM/CLJS/Node)ごとに異なり、dep を引くと
`.cljc` の可搬性が損なわれる。

本リポの方針(third-party dep ゼロ・portable `.cljc`・host-injected 戦略 —
bpmn-clj / dmn-clj の先例)に沿う、**GitHub Actions ワークフローを素の EDN
として扱う軽量ライブラリ**が無かった。

## Decision

`com-junkawasaki/ci-clj` を新設する。**third-party 実行時依存ゼロ**、全
namespace `.cljc`(JVM/CLJS/SCI)。責務を 4 層に分離する:

- **`ci.model`** — workflow-as-EDN の正準モデル。job は **id-keyed map**
  (O(1) 参照)、トポロジは各 job の `:ci/needs` が持ち document order に依存
  しない。threadable builder(`workflow`/`add-job`/`make-job`)と DAG query
  (`needs`/`dependents`/`topo-order`)。`topo-order` は Kahn アルゴリズムで
  id ソートにより決定的、循環時は `{:ci/cycle [...]}` sentinel を返す(throw
  しない)。
- **`ci.validate`** — 構造検証。`{:ci/severity :ci/code :ci/id :ci/msg}` の
  vector を返す純関数。error(dangling needs / DAG 循環 / step に `uses` と
  `run` の両立または欠如)と warn(`:ci/runs-on` 欠落)を分離、`valid?` は
  error 無しで真。
- **`ci.yaml`** — 既解析済み GitHub Actions YAML map ⇄ model。**YAML テキスト
  をパースしない** — host が `clj-yaml` / `snakeyaml` 等で解析した文字列キーの
  Clojure map を渡す。`needs` は nil/文字列/リストを常に vector に正規化。
  `to-data` で元の文字列キー形式に戻す(round-trip)。
- **`ci.execute`** — **純粋 wave プランナー**。`plan` は model を受け取り
  「波(wave)」の vector を返す。各 wave は前 wave 群で全依存が解決された
  job の id-ソート vector — 同一 wave 内の job は並列実行可能。DAG に循環が
  あれば `ci.model/topo-order` の sentinel をそのまま返す。ports 不要。

## Rationale

- **データ第一**: workflow が EDN なので生成・差分・バージョニング・Datomic
  格納が自明。YAML の不透明 blob を持たない。
- **依存ゼロ × 可搬**: bpmn-clj / dmn-clj と同じ方針。YAML テキストパーサを
  引かないことで CLJS/SCI でも動作し、dep が膨張しない。host は任意のパーサで
  解析した map を渡すだけ。
- **sentinel で throw しない**: `topo-order` と `plan` が例外を投げないことで
  呼び出し元が条件分岐でサニタリに扱える(`(map? (e/plan wf))` で判定)。
  bpmn-clj との差分: dmn-clj の `topo-order` は throw するが、CI ツール文脈では
  throw より sentinel の方が compose しやすい。
- **wave プランナーが CI の本質**: GitHub Actions は job の concurrency を
  needs に基づいて自動判断するが、外部ツールが「どの job が同時実行されるか」
  を静的に知る標準 API が無い。`plan` はその問いに純粋関数として答える。

## Consequences

- YAML テキストの読み書きは ci-clj の外(host 責務)。CI パイプライン自動生成の
  文脈では `to-data` の出力を host が `yaml/dump` に渡す二段構成になる。
- step の `with:` マップは `ci.yaml` で `:ci/with` として保持するが、`ci.validate`
  は内容を検証しない(構造外)。深い step 検証が必要なら拡張ポイントとして機能する。
- 最初の消費者は別 org(etzhayyim / gftdcojp)の actor が workflow EDN を組み立て
  `ci.yaml/to-data` → YAML 出力して CI に反映する(本ライブラリにドメイン pipeline
  は入れない)。

## Verification

`clojure -X:test` 緑(12 tests / 38 assertions)。from-data の変換と needs
正規化、DAG query(job/jobs/needs/dependents/topo-order)、wave プランナー
(直列・並列・独立ジョブ並列・循環)、validate(dangling needs / cycle / step
uses+run 両立 / step 両欠如 / runs-on 欠落 warn)を網羅。
