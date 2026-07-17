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

## Addendum 2 (2026-07-17) — 審査→改善→再審査の kaizen サイクル接続

オーナー指示「接続して」を受け、jumpBenchmarkQa と coscientist を双方向に接続:

1. **審査 → 改善（種まき）**: coscientist の入力に `:issues`（明示）/
   `:kaizen-from-panel`（当該 panel の `:panel/jump-qa` datom から issues を
   読む）を追加。`issue->directive`（決定論変換: "Lack of X"→"strong X"、
   "Static X"→"dynamic X"、"Weak X"→"strong X"、他は `fix:` 前置）で改善
   指令化し、**全候補の base spec に畳み込む** — 是正を共有しつつ方向性
   （composition/lighting）は従来どおり多様化。LLM generate 経路は
   augmented prompt 越しに弱点是正込みの方向性を提案する。
2. **改善 → 再審査（サイクル閉包）**: `:judge? true`（opt-in、judge-fn 注入
   可）で勝者を `jump-qa/judge-image`（public 化）で再審査し、新パネルの
   `:panel/jump-qa` に永続化 — **その panel がそのまま次イテレーションの
   `:kaizen-from-panel` 種になる**。chat agent の coscientist_panel tool にも
   `kaizen_from` / `judge` パラメータを追加（対話 agent からの kaizen 運転）。

tests +3（変換 / 種まき / サイクル閉包）、suite 121 tests / 754 assertions
green。pin: ai-gftd-mangaka → b138415。

## Addendum 3 (2026-07-17) — CLJ/EDN チェーン産ページの公開 + バージョン切替

オーナー指示「最新の clj, edn チェーンで作成したものを公開して。また この
バージョンごとに切り替えられるようにしたい」を実装:

1. **v11 公開** — 初の現行チェーン end-to-end ページを manga.gftd.ai に公開
   (`gh-arc0-1-v11`): storyboard datoms → 品質ループ 4 パネル (rubric
   76/73/77/67、facePresence 1.0×3 — splash のキャラ出現は引き続き LoRA
   領域の課題) → komawari 合成 (kami-mangaka-page、吹き出し/SFX/トーン込み
   B5) → **page-level jumpBenchmarkQa 70.0**。同一 judge (gemma4 n=3) で
   v10 公開 p01 は 52.0 — ステージ整合後の同条件比較で +18。R2 blobKey
   `gh-arc0-1-v11-p01` + D1 work/page 行。
2. **バージョン切替** — 正本は ai-gftd-mangaka repo 直下の `versions.edn`
   (append-only レジストリ)。D1 work props {versionGroup, versionLabel,
   pipeline, jumpQa} はその projection。reader (worker `versions-of` +
   render `version-nav`) が同 group の work 群から切替バーを `/work/<rkey>`
   HTML と `/api/work/<rkey>` `:versions` に描く。v10 側 work props にも
   versionGroup を追記 (45p の内容は不変)。version 未設定の既存作品は
   完全無変化 (reader tests 8/38 green)。reader deploy 済み (efacba55)。

閲覧: https://manga.gftd.ai/work/gh-arc0-1-v11 (バーで v10 ⇄ v11 切替)。
全 45p の v11 再生成は別バッチ (versions.edn の note に明記)。
pin: ai-gftd-mangaka → 4bae02e。

## Addendum 4 (2026-07-17) — storyboard 逆抽出（PNG → EDN 正本化）

45p の v11 全ページ化を阻む事実が確定した: **storyboard 源は退役 Python
パイプラインと共に失われている**（D1 は最終 PNG への参照のみ・panel 行なし、
mangaka-data は採点物と xdts のみ、page.json は 4p のレイアウトデモ）。
回答として「完成ページ画像から storyboard を復元する」経路を実装:

1. **lib**: `kami.mangaka.qa.recover` — page-script 抽出 prompt/parser
   （cast 照合で名前を ground、未知は "unknown"（捏造キャラを正本に混ぜ
   ない）、shot 語彙丸め）+ コマ矩形×脚本の読み順 zip（数の食い違いは
   `:mismatch` で明示 — silent truncation にしない）。
2. **graph**: `ai.gftd.mangaka.recoverStoryboard` — detect-panels →
   page-script 抽出 → storyboard doc 組み上げ（`:page/recovered-from` を
   焼き込み、**復元は推定であり原本ではない**）→ 永続化 + lint。offline は
   applied false で何も書かない。
3. **round-trip**: 復元 doc はそのまま doc-aware 生成（品質ループ /
   coscientist）の入力になる — これが「v10 公開 PNG → EDN 復元 → v11
   再生成 → 版切替公開」の全ページ化パイプラインの入口。

lib 8 tests / 55 assertions、mangaka 123 tests / 767 assertions green。
pin: kami-mangaka-qa → ccd30d2、ai-gftd-mangaka → 34ba751。

補足（同日調査）: superproject CI の慢性赤は **GitHub Actions の課金失敗**
（全ジョブが起動前に "account payments have failed" で死んでいる）— コード
起因ではない。pin 検証はローカル実行で green を確認済み。オーナーの
Billing 設定確認待ち。
