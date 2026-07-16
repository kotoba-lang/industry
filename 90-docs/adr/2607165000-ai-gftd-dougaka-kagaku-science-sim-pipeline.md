---
id: adr-2607165000-ai-gftd-dougaka-kagaku-science-sim-pipeline
title: "ADR-2607165000: ai-gftd-dougaka-kagaku — AI 科学解説チャンネル（sim-first・LLM非算術・人間factcheck）生成パイプライン（yukkuri ベース）"
status: accepted
doc_type: adr
topic: ai-gftd-dougaka-kagaku-science-sim-pipeline
authoritative: true
last_verified: 2026-07-16
authoritative_for:
  - ai-gftd-dougaka-kagaku（科学解説動画）のパイプライン設計（sim stage / claim provenance / factcheck hard gate + 人間レビュー無条件 hold / kami-engine 可視化 / madeForKids=false 公開）
  - 「LLM に算術をさせない」不変条件の機械強制方式（数値主張の出所 3 種: sim datom / 出典付き定数 / 人間確認済み引用）
  - kotoba-lang sim スタック（nagare/kudaki/fea/cae-solver/aero/echem/vphysics/num）のコンテンツ経由ベンチマーク運用（bench datom 台帳）
related:
  - 90-docs/adr/2607164500-ai-gftd-dougaka-kodomo-toddler-song-pipeline.md
  - 90-docs/adr/2607162200-aozora-creator-scheduled-publishing-integration.md
  - 90-docs/adr/2607131645-murakumo-direct-voice-api.md
  - 90-docs/adr/2607102200-kami-render-stack-deps-authority-rename.md
supersedes: []
superseded_by: []
---

# ADR-2607165000: ai-gftd-dougaka-kagaku — AI 科学解説チャンネル（sim-first）生成パイプライン

**Status**: accepted（設計 + scaffold。実装 Phase は Follow-ups）
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki（指示:「今の ai-gftd-yukkuri をベースに ai-gftd-dougaka-kagaku を設計。Mark Rober 型の大型実験そのものは AI 生成に向かないが『科学を面白く説明する』形式は向いている。もし月が地球に近づいたら / 動物の身体能力比較 / 身近な物の仕組み / 未来技術のシミュレーション / 3分で理解する数学・物理。幼児動画より広告単価を上げやすくオリジナリティも出しやすい。ただし事実確認は人間が行う必要がある — AI は計算を時々、堂々と間違える。また化学実験は全て kotoba-lang 3d cfd などを使ったシミュレーション・物理演算。これは kotoba-lang の 3d sim 系の性能を試すためでもあるチャンネル」）

## Context

**ジャンルの要件（Mark Rober / Kurzgesagt / Veritasium 型「科学を面白く」の共通項）**:

1. 1 本 = 1 つの具体的な問い（「もし月が半分の距離に来たら？」）。3〜10 分。
2. 大型実物実験は AI 生成に向かない — 代わりに**シミュレーションと可視化**が
   本チャンネルの「実験装置」になる。what-if（月接近）はそもそも実物実験不可能で、
   sim の方が原理的に強いジャンル。
3. 幼児向け（dougaka-kodomo）と違い madeForKids=false・通常広告 —
   **広告単価（CPM）が高く**、教育・科学系はブランド広告と相性が良い。
4. **正確性が生命線**: 科学解説の計算間違いはチャンネルの信頼を一撃で壊す。
   AI は計算を時々、堂々と間違える（オーナー指摘）— LLM の暗算を台本に
   直接書かせてはならない。事実確認の最終責任は人間が持つ。
5. 初期 series 5 本: もし月が地球に近づいたら / 動物の身体能力比較 /
   身近な物の仕組み / 未来技術のシミュレーション / 3分で理解する数学・物理。

**ベースにする ai-gftd-yukkuri の棚卸し（ADR-2607164500 の調査を流用）**:

- produce パイプラインは線形 stage orchestrator（`clj/src/yukkuri/graphs/
  produce.cljc`）+ pure-planner / `:exec` 分離 + renderer-mac ffmpeg fleet
  （dougaka contract、~65x realtime、限界費用 $0）+ D1 SSoT + B2 blob。
- **kodomo と決定的に違う点: L/R 掛け合い解説フォーマットは本チャンネルに
  そのまま合う**（yukkuri は元々「解説者×質問者」の科学・雑学解説形式）。
  kodomo が置換した generate_script を kagaku は**継承**する — 最大の流用点。
- 置換・新設が必要なもの: (a) **sim stage が存在しない**（数値の出所が LLM 生成
  テキストのみ — 本ジャンルでは許容不可）、(b) 紙芝居視覚（sim 結果の 3D 可視化
  が要る）、(c) QA が advisory（factcheck hard gate + 人間承認が要る）。

**kotoba-lang sim スタックの棚卸し（本セッション、README 直読で確認）**:

- `nagare`（有限体積 非圧縮 NS、SIMPLE/PISO、pure `.cljc` zero-dep、
  OpenFOAM-class）/ `kudaki`（陽解法 構造動力学、LS-DYNA-class）/ `fea`
  （線形静解析）/ `kami-engine-cae-solver`（`cae.solver/solve` multimethod
  contract、`:lbm` 含む）/ `kami-engine-aero`（reduced-order Cd）/
  `kami-engine-echem`（PEM 燃料電池 ROM）/ `kami-engine-vphysics`（走行抵抗）/
  `num`（BLAS 層、WebGPU backend）。すべて portable `.cljc`・EDN case・datom
  結果 — **「sim 結果 = queryable data」の設計が claim provenance にそのまま接続**。
- オーナー指示によりこのチャンネルは **sim スタックの性能・適用範囲を実地で
  試すベンチ台を兼ねる** — 動画 1 本ごとに実ワークロードのベンチ記録が残る
  構造にする。

## Decision

新規 child repo **`orgs/gftdcojp/ai-gftd-dougaka-kagaku`**（private、gftdcojp 既定）
を起こし、yukkuri の produce 骨格 + L/R 掛け合いフォーマットを継承しつつ
sim stage / claim provenance / factcheck gate を追加する。scaffold（本 ADR と
同時に push 済み、pin `46e113b`）は pure-planner core のみ:
`kagaku.scenario`（episode spec + **数値主張の provenance 必須**検証）、
`kagaku.simcase`（sim case EDN 検証 + 結果⇔claim 整合 + bench datom 化）、
`kagaku.factcheck`（決定論 gate + 人間承認必須の publish 判定）、
`kagaku.pipeline`（stage-order + advance reducer）、`resources/series.edn`
（5 series topic カタログ）、`resources/constants.edn`（出典付き定数）、
`content/tsuki-half-distance.edn`（実例）。テストは nbb 第一経路
（26 tests / 46 assertions green で push 済み）。

### 1. 中心不変条件 — LLM に算術をさせない（機械強制）

台本中の**すべての数値主張（claim）**は出所（provenance）を 3 種のいずれかで
持ち、`kagaku.factcheck` の決定論 gate が機械強制する:

| 出所 | 実体 | 検証 |
|---|---|---|
| `:sim` | kotoba-lang solver 実行結果（datom） | 値・単位一致（相対誤差 ≤2%、単位は文字列完全一致 — 暗黙の次元換算をしない） |
| `:constant` | `resources/constants.edn`（CODATA/NASA 等の出典付き。追加は人間レビュー必須） | 完全一致 |
| `:citation` | research-brief の出典付き引用 | **人間が `:verified-by` を付けた引用台帳エントリのみ**通す |

LLM の役割は topic 選定・構成・語り・比喩・sim case の設計**まで**。数値は
sim / 定数 / 検証済み引用からの**転記**のみ許され、転記ミスも gate が弾く。

### 2. パイプライン（yukkuri produce と同型 + sim 帯を前置）

```
compose → research-brief → design-sim → run-sim → generate-script →
factcheck (HARD gate) → [synthesize-voice ∥ render-sim-visual ∥
generate-visual ∥ arrange-bgm] → compose-scene → render-video →
human-review (無条件 HOLD) → publish → audit
```

| stage | 内容 | 実行系（`:exec` 側） |
|---|---|---|
| compose | series topic 選定（priorityScore、yukkuri topics.cljc 踏襲） | D1 |
| research-brief | LLM 資料調査 + 引用候補。**この段では数値主張を書かない** | murakumo text |
| design-sim | sim case EDN 設計。`kagaku.simcase/validate-cases`（solver kind / 規模 budget ≤2M cells）で決定論検証 | 純データ（planner） |
| run-sim | **cae.solver dispatch 実行**（nagare/kudaki/fea/aero/echem/vphysics/num）。結果 datom + **bench datom**（solver/メッシュ規模/steps/wall-ms/runtime）を記録 | cae.solver hosts |
| generate-script | L/R 掛け合い台本（yukkuri generate_script スキーマ**継承**）。数値は必ず provenance ref 付き | murakumo text |
| factcheck | **HARD gate 1（決定論）**: provenance 完全性 / sim 整合 / 定数出典 / 引用 verified / **シミュレーション明示** / metadata / 尺 (120–720s) / loudness。fail = :rejected | ffmpeg 計測 + gate |
| render-sim-visual | sim 結果 → **kami-engine render-IR → headless render**（WebGPU/WGSL first、repo-wide 3D 規則準拠。Three.js 等の第 2 エンジン禁止） | kami-engine headless |
| generate-visual / arrange-bgm / synthesize-voice | 補助図版（ComfyUI）/ BGM（ongakuka）/ VOICEVOX 話し声（murakumo `/v1/audio/speech`、ADR-2607131645） | murakumo engines |
| compose-scene / render-video | timeline plan → renderer-mac ffmpeg fleet（dougaka contract 無改造） | Mac mini fleet |
| human-review | **HARD gate 2（無条件 hold）**: 決定論 gate 全 green でも `:human-approved {:by :at}` が入るまで :held | オーナー |
| publish | YouTube Data API v3、**madeForKids=false**・通常広告 | YouTube API |
| audit | 生成イベント + bench 台帳 append | D1 |

### 3. 人間 factcheck を必須にする（auto-publish しない）

- ADR-2607162200 の governor auto-publish パターン（全 green → 自動公開）を
  本チャンネルには**適用しない**。オーナー要件「事実確認は人間が行う」を
  `kagaku.factcheck/publish-decision` が構造で強制: 決定論 gate 全 green
  **かつ** 明示承認があるときだけ :publish、なければ常に :held。
- 2026-07-10 の恒久承認（外部影響も agent 判断で可）に対する**意図的な
  self-restriction** — 科学解説の誤りは信頼毀損の回復コストが非対称に高い。
  kodomo の kids-safety escalate-on-flag と同じ理由構造で、こちらは
  「flag が無くても人間を通す」まで倒す。

### 4. 誠実性 — 全編シミュレーションの明示

実験映像はすべてシミュレーションであり、**実写実験と誤認させない**。
metadata `:sim-disclosure` フラグ + description 中の「シミュレーション」明記の
両方を factcheck gate の必須チェックにする（scaffold 実装済み）。VOICEVOX
クレジット必須は yukkuri と同一の不変条件（同じく gate が強制）。

### 5. sim スタックのベンチ台としての運用

- run-sim / render-sim-visual は `kagaku.simcase/bench-datoms` で
  `{:kagaku.bench/solver :cells :steps :wall-ms :runtime}` を repo-local
  append-only 台帳 `docs/sim-benchmark-ledger.edn` に残す（BMC canvas-ledger
  と同型の 1 行 1 EDN map、手編集禁止・追記のみ）。
- これが kotoba-lang sim 系（nagare/kudaki/cae-solver/num）の**実ワークロード
  性能トラッキング**になる — 動画制作のたびに solver 側の回帰・限界が数値で
  見え、発見した不具合・性能課題は各 solver repo へ issue/ADR で還流する。
- series と solver の対応: 月接近 = `:two-body-orbit`/`:tidal-scaling`
  （reduced-order、今日動く）、動物比較 = `:scaling-law`/`:explicit-dynamics`
  （kudaki）、身近な仕組み = `:fvm-simple`（nagare）/`:lbm`/`:linear-static`
  （fea）、未来技術 = `:reduced-order-aero`/`:rom-fc`/`:road-load`、
  3分数学・物理 = `:numeric-experiment`（num）。化学系の実験は echem ROM +
  CFD の範囲から始め、高忠実度化学 CFD は solver 側の将来 ADR に委ねる。

### 6. 継承するインフラ（変更なし）

- D1 SSoT + pod→Worker→D1 書込 + B2 blob（ADR-2606041900 パターン、新規 DB）。
- renderer-mac fleet（dougaka contract）。generate_script の L/R スキーマ、
  translate/upload/analytics/kaizen loop 群はチャンネルパラメタライズのまま流用。
- NSID は `ai.gftd.apps.kagaku.*`。
- BMC/Lean は **standalone パターン**（repo-local `docs/bmc-lean-loop-log.md` +
  本 ADR。共有 base datoms への登録は人間レビュー事項なので routine では行わない）。

## Consequences

- (+) 数値の正確性が LLM の性能に依存しない構造 — 間違いうるのは sim case の
  **設計**（モデル化の妥当性）だけで、それは human-review が見る。転記ミス・
  暗算ミスのクラスは gate が構造的に殺す。
- (+) 今日動く経路（reduced-order sim + kami-engine 可視化 + renderer-mac +
  VOICEVOX）だけで最初の動画まで到達可能。月接近 series の主 solver は
  `:tidal-scaling`/`:two-body-orbit` で外部依存ゼロ。
- (+) 動画 1 本 = sim スタックの実地ベンチ 1 回。solver 側の品質向上と
  コンテンツ制作が同じループに乗る（オーナーの副目的を構造化）。
- (+) madeForKids=false・科学教育系で kodomo より CPM が高く、sim 可視化は
  実写素材に依存しないためオリジナリティの主張が明確。
- (−) 人間レビューが必須なので kodomo のような完全無人 cadence 運転はできない
  （スループット上限 = オーナーのレビュー帯域）。意図的なトレードオフ。
- (−) sim のモデル化誤り（「その sim は問いに答えていない」）は gate で
  検出できない — human-review の主眼をここに置く。episode spec の
  `:beats` に導出過程の可視化を必ず含め、レビュー可能性を上げる。
- (−) render-sim-visual（sim 結果 → kami-engine render-IR）は新規実装量が
  最大。Phase A は静的プロット + 簡易 3D シーンから始める。

## Follow-ups（実装 Phase）

- **Phase A（E2E 1 本、手動）**: `content/tsuki-half-distance.edn` を
  `:tidal-scaling`/`:two-body-orbit` の実 solver 実行（nbb）→ kami-engine
  簡易シーン → renderer-mac → factcheck gate → 人間レビュー → 限定公開。
  bench datom 台帳の初回 append をここで検証。
- **Phase B**: nagare/kudaki を使う series（身近な仕組み / 動物比較）の
  case テンプレート、render-sim-visual の render-IR 変換、D1 schema。
- **Phase C**: translate/localize 横展開、レビュー UI（human-review queue の
  appview）、solver 還流ループ（bench 台帳 → solver repo issue）の定型化。
- BMC standalone log（`docs/bmc-lean-loop-log.md`）の Iteration 1 起票。

## Alternatives Considered

1. **yukkuri リポジトリ内に 5 番目のチャンネルとして追加** — L/R フォーマットは
   共通だが、sim stage / claim provenance / 人間 hold は yukkuri の produce
   骨格への破壊的追加になり、稼働中 4 チャンネル（advisory gate + 自動運転
   前提）と gate 意味論が衝突する。別 repo + 共有ライブラリ参照が正しい境界。
   不採用（kodomo ADR の Alternative 1 と同判断）。
2. **LLM 台本の数値を LLM 自身に再検算させる（self-check / multi-judge）** —
   design-quality の実測（3-judge panel が具体的欠陥をひとつも指摘できなかった）
   と同じ失敗クラス。「計測されないメトリクス＝劇場」。決定論 provenance gate +
   人間レビューを採用し、LLM 判定は gate に置かない。不採用。
3. **実験を実写素材（stock footage / 既存実験動画の引用）で構成** — 権利処理と
   出所検証のコストが高く、オリジナリティも出ない。what-if 系はそもそも実写
   不可能。sim-first を採用（オーナー指示とも一致）。不採用。
4. **汎用物理エンジン（Blender sim / Houdini / Three.js physics）で可視化** —
   repo-wide 3D 規則（kami-engine stack 以外の第 2 エンジン禁止、
   ADR-2607102200）に反する上、sim スタックのベンチ台という副目的を満たさない。
   不採用。
