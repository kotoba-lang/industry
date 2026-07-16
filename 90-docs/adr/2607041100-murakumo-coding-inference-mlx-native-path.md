# ADR-2607041100: murakumo のコーディングモデル推論を MLX ネイティブ経路に寄せる — Ollama の NVFP4 カーネル欠落の実測と、kotoba-lang/inference への IModelRuntime MLX アダプタ追加

**Status**: accepted — 実装・テスト済み。**両リポジトリとも push 済み・PR オープン、まだ merge していない**（オーナー確認待ち）。
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki

## Context

発端はローカルベンチマーク作業（この Mac=M1 Max・32GB unified memory で最もコーディング
性能が高いモデルを比較する）だったが、そこから派生して murakumo/kotoba-lang 側の
アーキテクチャ調査・実装まで進んだ。次のセッションが同じ調査をやり直さずに済むよう、
経緯・実測値・依存関係を1本にまとめる。

### 1. ローカルコーディングモデル・ベンチマーク（cloud-murakumo）

HumanEval subset(20/164 問、pass@1、greedy decode)で 7 モデルを Ollama 経由で実測
(Apple M1 Max, 32GB, `/private/tmp/.../scratchpad/coderbench/` に harness と結果 JSON):

| model | pass-rate | tok/s | 備考 |
|---|---|---|---|
| qwen3-coder:30b-a3b-q4_K_M | 90% | 33.8 | **推奨**(MoE, 3B active) |
| qwen2.5-coder:32b-instruct-q4_K_M | 90% | 5.2 | dense 32B、帯域律速 |
| deepseek-coder-v2:16b-lite-instruct-q8_0 | 85% | 30.2 | MoE, 2.4B active |
| gemma4:26b-a4b-it-qat | 30% | 37.4 | 汎用QAT、コーディング特化ではない |
| gemma4:12b-it-qat | 20% | 20.1 | 同上 |
| qwen3.6:35b-a3b-coding-nvfp4 | 測定対象外 | **0.65** | 下記参照。ランキングから除外(理由付き) |
| qwen3.6:27b-coding-nvfp4 | 測定対象外 | **0.33** | 同上 |

この結果を `gftdcojp/cloud-murakumo-fleet`（旧 `com-junkawasaki/cloud-murakumo` —
**セッション中に repo が transfer/rename されているのを検知**。push は GitHub の
redirect でそのまま通ったが、ローカル remote URL は古いまま）に
「対応モデル」カード([ADR 該当なし、直近コミット参照])と同じ形の OpenRouter 風
leaderboard カードとして実装: `itonami/coding-benchmarks` /
`ranked-coding-benchmarks` / `excluded-coding-benchmarks`、`GET
/itonami/benchmark`。**PR #29 (gftdcojp/cloud-murakumo-fleet) — オープン、未マージ**。

**注意**: ユーザーが挙げた「qwen 3.7 27b」は実在しない — HuggingFace 上に
`RscriptSQwen/Qwen3.7-plus`(非公式 org、DL数0、重みファイル無し=空の squatter repo)
しか無い。実在する次世代は `Qwen/Qwen3.6-*`(公式 org、`Qwen3.6-27B` /
`Qwen3.6-35B-A3B` / それぞれの `-coding` fine-tune)なので、ベンチマークはこちらを使った。

### 2. NVFP4 が Ollama の MLX engine で異常に遅い問題(実測で原因特定)

`qwen3.6-*-coding-nvfp4`(公式 Qwen リリース、正規の NVFP4 quant)を Ollama で実行すると
0.33〜0.99 tok/s しか出ない(他モデルは20〜38 tok/s)。原因を段階的に実測で切り分けた:

- `ollama ps` で「100% GPU、フルロード」を確認 — **メモリ容量の問題ではない**
  (offload/paging で解決する類の問題ではない)。
- `OLLAMA_LLM_LIBRARY=cpu` を**サーバプロセス自体に**設定して再起動しても
  tok/s はほぼ変わらず(0.33→0.99)、サーバログは常に `starting mlx runner
  subprocess` / `MLX engine initialized ... device=gpu` — この環境変数は
  NVFP4/MLX-native モデルには効かない(GGUF/llama.cpp 側の GPU ライブラリ選択
  フラグであって、MLX runner に代替エンジンは無い)。
- 「MoE offload(active expert だけ GPU、非active は RAM/NVMe)」という一般論も
  当てはまらない: Apple Silicon の unified memory は GPU/CPU が同じ物理 RAM を
  ゼロコピーで共有するため、そもそも「PCIe 越しの転送コスト」という offload が
  解決する問題が存在しない(Nvidia の discrete GPU 前提の技術)。
- 結論: **Ollama の MLX engine に NVFP4(Nvidia Blackwell 向け block-scaled 4bit)の
  高速カーネルが無い**(pre-M5 Apple Silicon 限定の既知バグ、upstream
  `ollama/ollama#16127`)。Ollama 公式は "NVFP4 は Q4_K_M より ~20% 速い" と謳うが、
  それは M5/M5 Pro/M5 Max の新 GPU Neural Accelerator 前提で、M1〜M4 Max では
  再現しない。

### 3. 「murakumo で自前実装したら」の検討 → num vs inference のどちらが正しい拡張点か

- `kotoba-lang/num`(`num.protocol/IBackend`): GEMM/AXPY/SpMV レベルの生の線形代数
  seam。CPU(実装済み、oracle)・WGSL/WebGPU(実装済み、primary — wgpu が Apple では
  Metal MSL に lower される)。`:cuda`/`:metal`(MPS)/`:rocm` は**名前だけ用意された
  未実装スロット**。ここに NVFP4 dequant カーネルを書いても、attention/量子化/MoE
  routing を Clojure で再実装しないと LLM 推論に届かない — 割に合わない。
- `kotoba-lang/inference`(`kotodama.inference.ports/IModelRuntime`):
  `probe!/load!/generate!/forward!/dispose!` というモデルレベルの host-port。
  既に `kotodama.inference.ollama`(Ollama の HTTP API を叩くだけの薄い実装)が
  実例として存在 — 「外部の成熟した推論ランタイムを host-inject する」という
  パターンがこのリポジトリの `torch-clj` の設計思想(「host-injected... e.g.
  libpython-clj / real PyTorch binding」)とも一致する。
- 結論: **NVFP4 カーネルを自前実装するのではなく、MLX 自身のネイティブ量子化
  (`mlx_lm.convert` で作る 4bit)を使う経路を `kotodama.inference.mlx` として
  IModelRuntime に足す**のが正しい拡張規模。`mlx-community/Qwen3.6-35B-A3B-4bit`
  (4.9万DL)・`-27B-4bit`(1.6万DL)は実在し全 Apple Silicon 世代で高速に動くはずだが、
  coding 特化版(`-Coding`)の MLX-native 変換は**まだ誰も公開していない**
  (follow-up: 自分たちで `mlx_lm.convert -q` する)。

### 4. 共有 checkout の未コミット WIP(Rust ネイティブ撤去)の扱い

作業前に `orgs/kotoba-lang/inference` の共有 checkout に、旧 Rust ネイティブ推論
エンジン(`mlx_backend.rs` 含む全 `src/*.rs`・`Cargo.toml`)を削除する未コミット差分
(約8,300行)を発見。オーナーに中身を確認してもらった上で、`git stash push -u` で退避
→ 別 worktree(`origin/main` 起点)へ `git stash apply`。

**判明した事実**: `origin/main` は既に PR #5(`agent/murakumo-studio-quality-kvcache`)
でこの Rust 撤去を完了済みで、しかも stash より進んでいた(`browser/*.js` は
「explicit adapter declarations」として意図的に残す方針に変わっていた)。stash は
この時点で stale — README/ADR/`ports.cljc` の文言変更は既に upstream に反映済み、
`Cargo.toml`/`src/*.rs` も既に無い。**stash は再適用せず**(shared checkout 側にも
`stash@{0}` として温存済み、drop していない)、実際に必要だった修正だけを直接行った:

- `verify/maturity.edn` と `maturity.clj` の `required-gates` が
  `:rust-native-build`/`:rust-wasm-build`(`cargo check ...`)を**削除し忘れて
  参照し続けていた**ため、`clojure -M:verify-maturity-run` が `main` の現状で
  `could not find Cargo.toml` により失敗することを実測で確認 → 両ゲートを削除
  して PR #5 の Rust 撤去を完了させた。

## Decision

### kotoba-lang/inference — `kotodama.inference.mlx`(新規)+ maturity gate 修正

- `verify/maturity.edn` / `verify/kotodama/verify/maturity.clj`: 上記の
  dangling な `:rust-native-build`/`:rust-wasm-build` を削除。
- `cljc/src/kotodama/inference/mlx.cljc`(新規): `kotodama.inference.ollama` と
  同型の `IModelRuntime` host adapter。`:backend` オプション(`:mlx-lm` または
  `:mlx-moe`)で「どちらのプロセス規約で起動したか」だけラベル付けし、実際の
  wire protocol(`POST /v1/chat/completions`)は共通なので **1つのクライアントで
  両対応**:
  - `:mlx-lm` — Apple 公式 `mlx_lm.server`
  - `:mlx-moe` — `mu-hashmi/mlx-moe`(MIT, Python, pip install。単体ノードで
    router 選択済み expert だけを SSD から都度ロード。`kotoba-lang/murakumo` の
    `murakumo.infer.moe` が起動する — ADR-2607033500)
- 実機検証: `mlx-lm`(venv、Python 3.11、`transformers==5.1.0` に固定 — 最新の
  `mlx-lm 0.31.3` は `transformers>=5.0.0` を要求するが最新の `transformers
  5.13.0` 側 API 変更(`AutoTokenizer.register` の引数仕様変更)と非互換で起動
  クラッシュした)で `mlx_lm.server --model mlx-community/Qwen2.5-0.5B-Instruct-4bit`
  を実際に起動し、`probe!`/`load!`/`generate!` を実行して確認。**この過程で実バグを
  発見・修正**: Python の `json.dumps` は `"field": value`(コロン後にスペース)
  で出力するが、Ollama の Go `json.Marshal`(コンパクト `"field":value`)を前提に
  `kotodama.inference.ollama` からコピーした素朴な JSON 抽出関数はスペース無し
  決め打ちで、実サーバに対して `:kotodama/text` が黙って空文字列になっていた。
  `field-value-start`(空白許容の正規表現スキャン)に置き換えて修正・再検証済み。
- `cljc/test/kotodama/inference/mlx_test.cljc`(新規): `probe!`/`load!` の
  純粋な(I/O無し)形を検証。`generate!`/`forward!` は Ollama アダプタ同様、実サーバ
  に対する手動検証が正(HTTP を差し替え不能な設計のため)。
- README.md に "Local MLX host adapter" セクションを追加。
- `clojure -M:test`: 22 tests / 145 assertions / 0 failures。
  `clojure -M:verify-maturity`: pass。
- **PR #6 (kotoba-lang/inference) — オープン、未マージ**。

### まだやっていないこと(follow-up)

- `kotoba-lang/murakumo` / `cloud-murakumo` を実際にこの
  `kotodama.inference.mlx` 経由で呼ぶよう配線する(現状は生シェルコマンド
  文字列を組み立てるだけで、HTTP クライアントとしての抽象化を持たない)。
- `Qwen/Qwen3.6-27B-Coding` / `Qwen/Qwen3.6-35B-A3B-Coding` を
  `mlx_lm.convert -q` で MLX ネイティブ 4bit に変換して mlx-community 相当の
  ビルドを自分たちで用意する(現状 mlx-community には coding 特化版の
  MLX-native 変換が存在しない — 汎用 instruct 版の `-4bit` のみ存在)。

## Consequences

**Positive**
- NVFP4 カーネルの自前実装(狭く深い GPU カーネル工数)を避け、既存の
  `IModelRuntime` パターンを再利用しただけ — 新しい横断的な仕組みを作っていない。
- Ollama 依存のボトルネック(MLX engine の NVFP4 カーネル欠落)を、Ollama を
  待たずに回避できる経路(Apple 純正 mlx-lm / mu-hashmi/mlx-moe)を用意した。
- 今回の実測(HumanEval ベンチ表・NVFP4 tok/s・Ollama バージョン要件・JSON
  フォーマットの罠)と依存関係マップを1本にまとめたことで、次セッションが
  同じ調査(cargo check 失敗の原因追跡、NVFP4 が遅い理由の切り分け、num vs
  inference のどちらを拡張すべきかの判断)をやり直さずに済む。

**Negative / 制約(honest)**
- **まだ push/PR は出したが merge していない**(両リポジトリともオーナー確認待ち)。
- murakumo/cloud-murakumo 側の実配線は未着手 — この ADR の decision は
  `kotoba-lang/inference` 単体の変更のみ。
- coding 特化版の MLX-native 4bit 変換は未実施(汎用 instruct 版のみ実在)。
- `gftdcojp/cloud-murakumo-fleet` へのリポジトリ transfer/rename をセッション中に
  検知したが、ローカル git remote の URL 更新は行っていない(push は redirect で
  動作するが、次回 `gh` 操作等で気づきにくい形で残っている)。
- `qwen3.6-*-coding-nvfp4` はモデル自体のダウンロード・ollama 登録は残したまま
  (削除していない)— ディスク上には存在するが、ベンチマークからは除外扱い。

## References

- ollama/ollama#16127 (Poor performance of the nvfp4 models on MacBook Pro M3)
- https://github.com/mu-hashmi/mlx-moe
- ADR-2607033500(murakumo を mlx-moe 対応にする決定 — 本 ADR の前提知識)
- kotoba-lang/inference PR #6 (fix(maturity) + feat kotodama.inference.mlx)
- gftdcojp/cloud-murakumo-fleet PR #29 (coding-model benchmark leaderboard)
- ADR-2607023100(kotoba-lang/murakumo 自身の「murakumo.cloud」= overlay、
  本 ADR の murakumo.infer とは無関係な同名機能であることの注記)
