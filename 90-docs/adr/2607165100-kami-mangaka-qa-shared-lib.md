# ADR-2607165100: kami-mangaka-qa — Python 世代 QA 資産の可搬正本を kotoba-lang 共有 lib に

- Status: accepted, implemented
- Date: 2026-07-16
- Deciders: owner (root@junkawasaki.com) 指示「python 互換のもので作っていた有用なものは cljs にも持ってきて, kotoba-lang org の 共有lib に設計実装」/ agent 実装
- Related: ADR-2607164200 (+addendum 3 知覚レイヤ), ADR-2607012100 (expression patterns), ADR-2606282100/2101 (mangaka render commons)

## Context

退役 Python runtime（lg_mangaka）の v10 世代パイプラインには、CLJ 移植で
失われていた有用資産があった: jump_benchmark_qa の **QA 軸体系**（決定論
heuristic 13 軸 + Jump 誌基準 VLM 審査 8 軸、実サンプル
`mangaka-data/ghosthacker/resources/v10/v10-p00-score.json` = total 79.1
「mid-tier (人気作)」）、annotated page の gaze/readingPath overlay、そして
ADR-2607164200 addendum 3 で ai-gftd-mangaka 内に実装した知覚レイヤ
（VLM 顔カウント・コマ境界検出・吹き出し×顔の幾何 QA）。これらは
work-agnostic で、ai-gftd-mangaka の app 内に閉じる理由がない。

## Decision

**`kotoba-lang/kami-mangaka-qa`** を新設（public、`kami-mangaka-*` sibling、
純 `.cljc`・I/O ゼロ・deps は clojure のみ）:

- `kami.mangaka.qa.axes` — 軸レジストリ（:rubric8 / :heuristic 13 / :jump 8）
  + v10 score JSON 互換の正規化（`from-v10` / `->v10-key`、snake↔kebab）。
- `kami.mangaka.qa.perception` — VLM prompt + parser（顔カウント / 顔ボックス /
  コマ境界)。`vision-fn (fn [prompt image-b64] → map|nil)` 注入の
  host-capability 方式（langchain-clj 流）で cljs/nbb でもそのまま動く。
- `kami.mangaka.qa.geometry` — 純幾何（overlap / bubble-clearance /
  face-presence / **reading-order-violations**（右→左・上→下の読み順 QA、
  v10 の readingPath overlay に対応する検証側））。
- `kami.mangaka.qa.score` — 集約（0-1 rubric → 0-100、mean10、知覚軸 merge）。

**正直な範囲宣言**: Python 側のピクセル heuristic 実装（brightness_avg 等）と
v10 の重み付き合成は移植しない — 重み定義は退役 runtime と共に失われており、
**保存された total は素通しし、重みを捏造しない**。計測値は consumer が供給。

消費者第 1 号は `gftdcojp/ai-gftd-mangaka` — `mangaka.perception` を本 lib への
委譲 facade にリファクタし（prompt/parser/幾何の正本は lib 側へ）、品質ループの
`:facePresence` 軸と `detectFaces` NSID は挙動不変のまま lib を使う。

## Consequences

- (+) QA 軸・知覚 prompt・幾何が org 共有の SSoT になり、app-aozora / dougaka /
  animeka 等の他消費者が同じ判定基準を再利用できる。v10 アーティファクトが
  正規化経由で現行スタックから読める。
- (+) cljs 互換（JVM interop なし・I/O 注入）— browser/nbb の QA overlay
  実装（v10 の annotated page 相当の再現）への道が開く。
- (−) jump QA の**実行ループ**（n_samples 平均・verdict 生成・ピクセル
  heuristic 計測）は未移植 — backlog `:qa :jumpBenchmarkQa` は残る。
  overlay 描画（scored.png 相当）も未実装（consumer 側 follow-up）。

## Alternatives Considered

- **ai-gftd-mangaka 内に置いたまま**: 消費者が gftdcojp private repo に依存
  してしまい、kotoba-lang の公開 lib 群（expression/text/page）と非対称。
- **jump QA 実行ループまで一括移植**: ピクセル計測の実装が必要で、重み定義の
  復元（=捏造リスク）を伴う。軸の正本化を先行し、実行系は計測器が揃ってから。

## Addendum 1 (2026-07-16) — jump QA 実行ループ + annotated overlay の復活

本 ADR 本文で follow-up とした 2 点を実装:

1. **`kami.mangaka.qa.overlay`**（lib 側）— v10 世代 scored.png 相当の
   annotated ビューを**純 hiccup SVG**で再現（パネル枠・番号バッジ・読み順
   パス・顔/gaze ボックス・スコアヘッダ)。I/O/DOM ゼロ — cljs では
   component、nbb/JVM では同梱 `->svg-str` で静的 SVG。
2. **`ai.gftd.mangaka.jumpBenchmarkQa`**（consumer 側）— backlog `:qa` の
   退役 Python `score_jump_benchmark` を CLJ 化。軸の正本は lib の
   `:jump` 8 軸、n サンプル平均（default 3）+ verdict + distinct issues、
   `:panel/jump-qa` / `:page-render/jump-qa` datom に永続化。**v10 の重み
   合成は非移植** — `:total` は mean10×10 で `:method` に明記（捏造しない）。
   offline は applied false で datom を書かない。

Live 実測（公開 gh-arc0-1 p01、api.murakumo.cloud gemma4、n=3）: total 52.0、
編集者 verdict「Jump 掲載には動的インパクトと線の強弱が不足」+ 12 issues、
コマ検出 7/7（フルサイズ画像 — thumb では 3 だった。検出精度は入力解像度に
依存する実測知見)。旧 v10 の 77.4 は judge（gemma3:4b）も合成式も別物なので
直接比較不可。lib 6 tests / 42 assertions、mangaka 118 tests / 737 assertions
green。pin: kami-mangaka-qa → df897eb、ai-gftd-mangaka → 30c7b5f。
