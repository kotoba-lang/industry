---
id: adr-2607083700-vdesign-cad-maturity-scoring
title: "ADR-2607083700: kami-engine-vehicle-designer に kotoba-lang/cad の実成熟度スコアリングを配線する — 承認は実在するgateのみカウントし、捏造しない"
status: accepted
doc_type: adr
topic: kotoba-lang-cad-cam-integration
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - vdesign.cad/maturity（kotoba-lang/cadのscore/coverage-assessment/co-sientist-reviewの実配線）の設計決定
  - real-approvalsが「実在するgateのみ」をカウントする方針
  - stage(kotoba.cad.core/stages上の位置)を「人間承認という最も偽装しにくい信号」でのみRelease(7)まで進める判断
related:
  - 90-docs/adr/2607083400-vdesign-cad-cam-bridge.md
  - orgs/kotoba-lang/kami-engine-vehicle-designer/src/vdesign/cad.cljc
supersedes: []
superseded_by: []
---

# ADR-2607083700: vdesign.cad maturity scoring

- Status: accepted (2026-07-08)
- Deciders: Jun Kawasaki

## Decision

`vdesign.cad/maturity` を新設し、`kotoba-lang/cad`（`kotoba.cad.core`）の
`score`/`coverage-assessment`/`co-sientist-review` を、このパイプライン
自身が生成した実artifactと実際に通過したgateから計算する。

- **`envelope-dxf`**: packaging envelopeの平面輪郭を実際に有効な
  ASCII DXF(R12 LINE entity)として出力する。fakeファイルではない。
- **`real-approvals`**: `#{:engineering/physics-closure
  :manufacturing/sim-verify :manufacturing/artifacts-classified
  :release/design-review}` のうち、対応する実信号（PhysicsGovernor
  収束・SimGovernor合格・アーティファクト分類に`:artifact/unknown`が
  無いこと・人間design-review承認）が**実際に発火した場合のみ**カウント
  する。捏造しない（失敗したsim-verifyは他のgateが通ってもカウントされ
  ないことをテストで固定）。
- **`stage`**: `kotoba.cad.core/stages`（8段階のordinal、per-stage
  completeness checkを持たない自己申告モデル）上、Toolpath(5、実際に
  toolpath/gcodeが生成された時点)から、Release(7)へは
  **人間承認(`:release/design-review`)が実際に得られた場合のみ**進める。
  Sketch/Constraint solve/Drawing/Tolerance review/Inspection の個別
  達成は追跡していないため、最も偽装しにくい信号（人間承認）だけを
  最終段階への通過条件にした。
- **`runner-results`**: `kotoba.cad.core/runner-plan` を
  `kotoba.cad.runner/dry-run`（`execute!`ではない）に通した結果。
  `execute!`は実在しないdxf-lint/step-audit/toolpath-checkバイナリを
  呼ぼうとするため使わない（そのnamespace自身のdocstringに明記）。

`vdesign.process/plan`が`{:verification :review}`を受け取れるよう拡張し、
StateGraphの`:process`ノードは既にその両方をstateから得られる位置に
いる（`:review`は human design-review interruptがresumeされて初めて
存在し、`:process`は`:verify`の条件分岐で`:passed?`が確認された後にしか
走らない）ため、追加の配線なしで自動的に実gateがmaturity計算に渡る。

## 結果（実測、BEV/FCEV両方で確認）

| | 承認未配線(process/plan designのみ呼んだ場合) | 実グラフをresumeまで実行 |
|---|---|---|
| approvals | 2/4（physics-closure, artifacts-classified） | 4/4 |
| stage | 5 (Toolpath) | 7 (Release) |
| score/overall | 74 | 100 |
| coverage/score | 98 | 100 |
| MRL | `:mrl/pilot-ready` | `:mrl/production-candidate` |
| blockers | なし | なし |

`:maturity`は`:CadMaturity` datomとして台帳に記録し、`:process-plan`
audit entryにも要約を追加した（従来は計算されるだけで台帳に一切残って
いなかった欠落）。

## 誠実な注記（最重要）

**このスコアはprocess/governanceの完成度であり、工学的能力の向上では
ない。** `kotoba-lang/brep`は依然box extrude以外未実装、`kotoba-lang/cnc`
は依然pocket/drill以外placeholderのままで、この作業によって変わって
いない。「production-candidate」というラベルは、実車が量産可能になった
ことを意味しない — kotoba.cad.coreの粗いheuristic上で、パイプラインが
自ら宣言する4つのgateを実際に通過し、実artifactを生成し、人間承認を
得た、という事実を反映しているに過ぎない。

## 却下案

- **runner-resultsを`execute!`で取得**: 対応する実行バイナリが存在せず、
  実行しても失敗するかno-opになるだけで、それを「エビデンス」として
  カウントするのはスコアの捏造にあたる。dry-runに留めた。
- **stage=7を常に主張**: 人間承認なしでReleaseを名乗るのは、
  kotoba.cad.coreのstageモデルが本来表現しようとしている「実際にどこまで
  進んだか」を偽ることになる。承認という最も強い実信号のみをgateにした。
