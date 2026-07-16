# ADR-2607032700: murakumo-studio — kotoba-lang/inference をベースにしたローカル推論スタジオ（LM Studio 相当）を Tauri + kotoba-ui/appkit で構築する

**Status**: accepted（v1 実装着手。Phase 2/3 は follow-up）
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

`kotoba-lang` には推論の「言語基盤」（`inference`: GGUF 解析 + torch/num
CLJC contract、`num`: WGSL shader + backend dispatch protocol、`torch`:
neural graph EDN）と「fleet 基盤」（`murakumo`: Mac mini mesh 制御面 +
`murakumo.infer` 分散推論 planner/engine、ADR-2607022000）が存在するが、
**個人の手元で 1 モデルを選んでロード・チャットし、他アプリからも
OpenAI 互換 API で叩ける、LM Studio 相当の GUI ツールが存在しない**。

`kotoba-lang/inference` の現状を実装まで踏み込んで精査した結果（2026-07-03
時点）:

- `cljc/src/kotodama/inference/{core,runtime,ports}.cljc` は**契約のみ**
  （`ports.cljc` の `no-runtime` は全メソッドで `ex-info` を投げる設計）。
- 実際に動く数値検証は `verify/kotodama/verify/gemma4_e4b_num_smoke.clj`
  （JVM 専用、実 Ollama `gemma4:e4b` GGUF を `RandomAccessFile` で読む）にある
  `compose-gemma-block` / `real-gemma-block-backend` のみで、**1 token・
  固定 position・`full-layer-count` 既定 2（`blk.0`→`blk.1`）**まで検証済み。
  `forward` は `(:torch/layers graph)` を `loop` で threading する**汎用
  ループ**（`real-gemma-block-backend`）なので、layer 数を N=42 に上げる
  こと自体は安い。
- しかし以下は**未実装**（`gemma.cljc:694-714` の `required-direct-lowering-ops`
  が自己申告するチェックリストと一致）:
  - **トークナイザ/デトークナイザが org 全体に存在しない**
    （旧 Rust 実装は commit `b34eab8`「Remove Rust runtime from inference」
    で `tokenizer.rs` ごと削除され、後継が無い）。
  - **KV キャッシュ・自己回帰 decode loop が無い**（毎回 K/V を再計算する
    1-shot 計算のみ）。
  - **サンプリング（greedy/top-k/top-p）が無い**（golden 定数と logits の
    一致 assert のみで、次 token へのフィードバックが無い）。
  - `ops.cljc`（RMSNorm/RoPE/MLP/attention）は**素の host Clojure**で
    `num.core`/`num.protocol` を一切呼ばない。
  - `num` の `:wgsl` backend（`num/src/num/wgsl.cljc` 実 WGSL shader +
    `wgsl_backend.cljc` dispatch）は実在するが、それを叩く `IGpuDevice`
    （`navigator.gpu` 経由でブラウザ GPU を実際に叩く実装）は**org 内
    ゼロ件**。既存 `WgslBackend` は同期/blocking 前提で書かれており、
    ブラウザ WebGPU の非同期性とアーキテクチャ不整合（`wgsl_backend.cljc`
    docstring が自ら明記）。`inference/browser/` は旧 Rust/wasm-pack 成果物
    を import する stale JS のみで CLJS は 0 件。
  - `kotoba-ui`/`appkit`（kotoba-lang の default design system、
    ADR-2607022800）も **CLJS/shadow-cljs の実例が org 内に 1 つも無い**
    （`.cljc` のみ、reagent 経路は docstring 上の主張のみ）。

一方で `num.cpu`（`num/src/num/cpu.cljc`）は zero-dep 純 Clojure の CPU
backend で、contract test・JVM↔CLJS portability 検証（`14 passed, 0 failed`）
まで済んだ**実在し動く**参照実装である。

デスクトップアプリの prior art は org 内に Tauri 2 が 2 件
（`gftdcojp/manimani/tauri`, `gftdcojp/cloud-itonami/mobile`）— いずれも
Rust shell + CLJS(Scittle/shadow-cljs)/reagent frontend + `kotoba-ui.css`。
Electron 採用例は無い。

オーナー確認（2026-07-03、AskUserQuestion）:
- **推論バックエンド方針**: 「kotoba-lang/inference の WebGPU 化を並行して
  前進させる」を選択。ただし上記の通り WebGPU 経路は tokenizer・GPU device
  層・decode loop・kotoba-ui の CLJS 初実績を含む数週間規模のグリーンフィー
  ルド作業であることが判明したため、再確認の AskUserQuestion を提示したが
  無応答（60秒タイムアウト）。**本 ADR は「推奨」として提示した折衷案
  （CPU/JVM backend で v1 を実動作させ、WebGPU はロードマップ化する）を
  採用する**。これは判断代行であり、オーナーの事後レビューを想定する。
- **org 配置**: `kotoba-lang`（`gftdcojp` ではなく）。「推論基盤の公式
  リファレンス UI」という platform 側の位置づけ。public repo。
- **v1 スコープ**: フル機能（モデル管理 + チャット + ローカルサーバ +
  モデル検索/ダウンロード + GPU offload 設定 + murakumo fleet 参加）。

## Decision

### 1. 新規 repo `kotoba-lang/murakumo-studio`（public）

west の `:api-single-entry` 経路で登録する（`manifest/west.yml` 手編集禁止）。
org 内の他 kotoba-lang repo と同じ `deps.edn` + `:local/root` monorepo
規約、および `kotoba-ui`/`appkit`/`inference`/`num`/`torch` の非monorepo
消費側は `io.github.kotoba-lang/<repo> {:git/sha ...}` pin。

### 2. アーキテクチャ: Tauri 2 shell + shadow-cljs/reagent frontend + JVM 推論 sidecar

```
┌─────────────────────────────────────────────┐
│ murakumo-studio (Tauri 2, Rust shell)        │
│  ┌─────────────────────────────────────┐    │
│  │ webview: CLJS (shadow-cljs) + reagent │    │
│  │  kotoba-ui.core + appkit.core         │────┼──▶ ローカル推論サーバ
│  │  (Model Manager / Chat / Settings)    │    │    (下記、localhost:*)
│  └─────────────────────────────────────┘    │
│  Rust: sidecar process 管理・GGUF/設定       │
│  ファイルI/O・HF/Ollama ダウンロード進捗      │
└─────────────────────────────────────────────┘
                     │ spawn/監視
                     ▼
┌─────────────────────────────────────────────┐
│ murakumo-studio-engine (Babashka/Clojure     │
│ sidecar, ローカルプロセス)                    │
│  kotodama.inference.ports/IModelRuntime を    │
│  CPU backend（num.cpu 経由）で実装。          │
│  N-layer autoregressive generate loop +       │
│  KV-cache + sampling + tokenizer（新規）。    │
│  HTTP: /v1/chat/completions (OpenAI互換)      │
│        /v1/models, /health                    │
└─────────────────────────────────────────────┘
```

- **frontend は org 初の CLJS/shadow-cljs 実例**として `kotoba-ui.core` /
  `appkit.core` を require する（現状 `.cljc`/reagent 経路は docstring
  のみで未検証だったため、murakumo-studio が最初の実地検証になる）。
- **推論はローカルサイドカー**として実装する。理由: `inference` の実装済み
  数値検証は JVM 専用（GGUF を `RandomAccessFile` で読む前提）であり、
  ブラウザ CLJS 側に移すには GPU device 層が必要（§3 参照、Phase 2）。
  v1 は Tauri の Rust shell がこの sidecar プロセスを spawn/監視し（
  `llama-server` を外部プロセスとして起動する既存 `murakumo.infer.engine`
  と同型パターン）、frontend は localhost HTTP のみを見る。
- **ローカルサーバは内部UIと外部公開が同一実装**: `/v1/chat/completions`
  はチャット画面からも他アプリからも同じ endpoint を叩く（LM Studio の
  "Local Server" タブ相当）。

### 3. 推論エンジン: `kotoba-lang/inference` を N-layer 対応に拡張する

`gemma4_e4b_num_smoke.clj` の `real-gemma-block-backend` ループを
`full-layer-count` 42（Gemma4 e4b の実 layer 数）に一般化し、以下を
**新規実装**する（`inference` 本体 `cljc/src/kotodama/inference/` 配下）:

- `kotodama.inference.tokenizer`（新規 namespace）: Gemma4 SentencePiece
  互換の tokenize/detokenize。GGUF ヘッダに埋め込まれた vocab を読む
  （llama.cpp の `tokenizer.ggml.*` metadata 規約に準拠）。
- `kotodama.inference.decode`（新規）: KV キャッシュ付き自己回帰 loop。
  1 token ずつ `forward` → sample → 次 input という petals/llama.cpp 型の
  decode step。
- `kotodama.inference.sample`（新規）: greedy / temperature / top-k / top-p。
- 実行 backend は **CPU（`num.cpu` 経由）を既定**とし、
  `:kotodama/compute-backend` は `:num/cpu`（新規、現状 `runtime.cljc` の
  `supported-compute-backends` には無いので追加）を既定値にする。
  `:num/webgpu` は Phase 2 まで `runtime.cljc` の validate 時に明示的に
  "not yet implemented" で弾く（サイレントフォールバックしない）。

### 4. モデル管理

- ローカルスキャン: `~/.murakumo-studio/models/*.gguf` + 既存 Ollama blob
  store（`~/.ollama/models`）を read-only で検出・import。
- ダウンロード: Hugging Face Hub API（検索 + GGUF ファイル一覧 + resumable
  HTTP Range download）。Ollama library からの pull も選択肢として提供。
- モデルごとの設定 EDN（`~/.murakumo-studio/models/<id>.config.edn`）:
  context length・batch size・sampling defaults・**GPU offload 設定**
  （Phase 1 では `:compute-backend :num/cpu` 固定・thread 数のみ調整可、
  Phase 2 で `:num/webgpu` 選択肢と layer-split offload 設定を追加）。

### 5. murakumo fleet 参加

Settings に「Join murakumo fleet」トグルを追加し、ON で
`gftdcojp/cloud-murakumo-fleet` の `/infer/*` API
（`GET|PUT /infer/models/<id>`, `POST/GET /infer/runs`）に自ノードの
capacity（モデル一覧・空き VRAM/RAM 相当）を announce する。
v1 では**announce のみ**（read-only 台帳登録）とし、実際に
`murakumo.infer.engine`（`:llamacpp-rpc`/`:mlx-ring`）の worker として
compute を提供する経路は Phase 3（ADR-2607030400 の 3-tier 参加モデルの
native tier 相当）に送る。did:key/CACAO 自己 mint（CLAUDE.md
kotoba-server 節）は Phase 3 で導入し、v1 は匿名 announce とする。

### 6. Phased roadmap

| Phase | 内容 | 状態 |
|---|---|---|
| 1（本 ADR の実装対象） | repo scaffold・Model Manager・Chat UI・OpenAI互換ローカルサーバ・`inference` の N-layer CPU decode 拡張（tokenizer/KV-cache/sampling 新規実装）・fleet announce-only | 本セッションで着手 |
| 2 | `num` に非同期 `IGpuDevice`（`navigator.gpu`）実装、`wgsl_backend.cljc` の async 化、`ops.cljc` を `num.core` 経由に書き換え、`:num/webgpu` を Tauri webview 内で実 dispatch | follow-up（別 ADR/セッション） |
| 3 | murakumo fleet への compute worker 参加（`murakumo.infer.engine` adapter 呼び出し）、did:key/CACAO 自己 mint、credits 精算表示 | follow-up |

### 7. モデル選定の実測比較（2026-07-05 追記）

murakumo-studio の既定/推奨モデルを検討するため、LM Studio（Apple M4、物理RAM
32GB）でローカル実行可能な候補モデルを実際にロードし、同一の `.cljc` 実装課題
（`kotodama.inference.kv-cache` 相当の KV キャッシュ ring buffer + `clojure.test`）
を与えて実装能力を比較した。生成コードはモデル自身の自己申告テストを鵜呑みに
せず、独立に書いた `clojure.test` ハーネスで JVM 上で実際にコンパイル・実行して
検証した。

#### 7.1 候補と Artificial Analysis Intelligence Index（参考値、OpenRouter 各モデル
ページが参照する指標）

| モデル | Intelligence Index | Output tok/s | Params |
|---|---|---|---|
| MiniMax M2.7 | 38 | 51.3 | 230B/10B active MoE |
| Qwen3.6 35B A3B | 32 | 163.0 | 36B/3B active MoE |
| Gemma 4 26B A4B | 26 | N/A | 25.2B/3.8B active MoE |
| Nemotron 3 Nano Omni 30B A3B | 15 | 288.5 | 30B/3B active MoE |

#### 7.2 ハードウェア制約の発見

MiniMax M2.7（Q4_K_M, 138GB）は MoE の全 230B パラメータを常時保持する必要が
あり（推論時の active パラメータ数 10B とは無関係。どのトークンでどのエキスパート
が選ばれるか事前に分からないため、ルーティング対象の全エキスパートをメモリ
または mmap 経由でディスクに保持する必要がある）、**物理RAM 32GB のマシンでは
どの量子化でもロード不可能**と判明した。LM Studio のカタログが "Staff Pick" /
"Best Match" として提示していても、この総パラメータ量に基づくハードウェア
適合性までは保証されない点に注意（active params ベースの簡易判定に見える）。
オーナー指示によりダウンロード自体は検証目的で継続したが、実運用のデフォルト
モデル候補からは除外する。より大きな RAM を持つノード（murakumo fleet の他
ノード等）での追試は follow-up。

#### 7.3 実測結果（同一プロンプト、独立テストハーネスで検証）

- **Qwen3.6 35B A3B**: 主要実装（`create-cache`/`append!`/`window`/`cache-size`）
  は独立テスト 10 アサーション全合格。ring buffer の index 計算・`ex-info` に
  よるエラー契約・ホスト依存コードなしの完全な portability、すべて要件通り。
  ただし **reasoning token を極端に消費**する（prompt 556 tokens に対し
  completion 12000 tokens 中 11381 が reasoning。モデル自身が書いたテスト
  コードは token 上限で `finish_reason: length` となり途中で打ち切られたが、
  主要実装自体は先に完成していたため実害はなかった）。context window を
  既定の 8192 から 16384 に引き上げないと実用的な応答が得られなかった。
- **Gemma 4 26B A4B**: `finish_reason: stop` で完走（reasoning token 消費は
  Qwen ほどではない）。しかし `create-cache` が
  `(vec (repeat n-layers (atom [])))` という典型的な Clojure の罠を含む —
  `repeat` は引数を **1 回だけ評価**してから複製するため、**全レイヤーが
  同一の atom インスタンスを共有**してしまい、要件の核心である「レイヤー
  分離」が壊れている（独立テストで実際に再現: layer 1 への append がレイヤー
  0 の window/size を変化させた）。モデル自身が書いたテストコードは
  この bug とは無関係な構文エラーを2件含み（`doseq` の不正な束縛形式
  `(doseq [i 1 2 3 4 5] ...)`、`try`/`catch` の不正なネスト）**コンパイル
  すら通らない**ため、このレイヤー分離バグは自己テストでは検出されずに
  出荷されていたことになる。
- **Nemotron 3 Nano Omni 30B A3B**: `(ns kotodama.inference.kv-cache ...)` の
  閉じ括弧が欠落しており、名前空間そのものが **コンパイル不能**
  （`Syntax error reading source ... EOF while reading, starting at line 1`）。
  加えてソースを読む限り `append!`/`window`/`cache-size` は `(dec layer)` で
  1 始まりインデックスとして実装している一方、`validate-layer!` は 0 を
  含む 0 始まりの `layer` を有効値として通すため、**構文エラーが無かった
  としても `layer=0`（要件の主たる呼び出し方）で `nth` に `-1` を渡す
  致命的なオフバイワンが存在**した。tok/s は候補中最速（AA 実測値
  288.5 t/s）だが、今回の cljc 実装課題では実用に耐えない結果だった。
- **MiniMax M2.7**: §7.2 の通り 32GB 機ではロード不能のため未検証
  （follow-up）。

#### 7.4 結論・推奨

**Qwen3.6 35B A3B を murakumo-studio v1 の既定/推奨モデルとする。** 実測で
cljc 実装が唯一「独立検証済みで正しい」結果を出したため。ただし reasoning
token の消費が非常に大きいモデルであるため、Model Manager のデフォルト
`max_tokens`/context length は reasoning model を前提に十分大きく取る
設計にする（本検証でも context 8192→16384 への引き上げが必須だった）。
Gemma 4 26B A4B は応答速度・完走性では優れるが、**テストだけに頼ると見逃す
実装バグ（shared-atom 等）がある**点は Gemma 固有の弱点というより、コード
生成モデル全般の生成物を「自己申告テストのみで signed off しない」運用上の
教訓として明記する（murakumo-studio の Model Manager が将来コード生成用途の
model recommendation を出す場合、この検証パターン=独立テストハーネスでの
再現を標準手順にすべき）。

## Consequences

- (+) `kotoba-lang/inference` を実際に「使う」最初のプロダクトができ、
  契約のみだった `ports.cljc`/`runtime.cljc` に対して現実の generate
  loop・tokenizer・sampling という**具体的な実装義務**が生まれる
  （bypass せず本体を前進させる、というオーナーの意図に沿う）。
- (+) `kotoba-ui`/`appkit` の org 初 CLJS/shadow-cljs 実地検証になる
  （ADR-2607022800 が残していた「実移行は follow-up」を最初に消化する
  プロダクト）。
- (+) fleet announce だけでも `murakumo` 経済（ADR-2607030030）と
  ローカルツールが最初から疎結合で繋がる。
- (−) v1 は **CPU decode のみ**で、GPU（Metal/CUDA/WebGPU）を使わない
  ため、小型モデル（Gemma4 e4b 級）以外は実用速度が出ない。LM Studio の
  GGUF 全般対応・広範な GPU offload とはこの点で明確に格差がある —
  本 ADR はこれを認め、Phase 2 で埋める前提を明記する。
- (−) tokenizer は Gemma4 専用実装から始まり、汎用 GGUF tokenizer では
  ない（他アーキテクチャのモデルは Phase 1 では非対応）。
- (−) 推論バックエンド方針の最終確認（AskUserQuestion）が無応答のため、
  CPU-first ハイブリッド案は Claude 側の推奨をそのまま採用した判断代行
  である。オーナーが「WebGPU を Phase 1 からブロッキング要件にすべき
  だった」と判断した場合は本 ADR を revise する。

## Alternatives Considered

- **llama.cpp/Ollama サブプロセスを `IModelRuntime` の実行系にする**:
  最速で「本物の」チャット体験を得られるが、`kotoba-lang/inference` 自体
  の前進に寄与しない。オーナーが明示的に「WebGPU化を並行して前進させる」
  を選択したため不採用（`inference` を bypass しない）。
- **WebGPU を Phase 1 からブロッキング要件にする**: 精査の結果、
  tokenizer 新規実装・非同期 GPU device 層・`ops.cljc` の `num` 経由への
  書き換え・kotoba-ui の CLJS 初実績、という複数リポにまたがる
  グリーンフィールド作業が確定し、本セッション内で動く成果物を作れない
  ため不採用。CPU backend を経由の足場として先に固め、GPU は差し替え
  可能なバックエンド選択として Phase 2 に残す。
- **Electron**: org 内に採用例が無く、Tauri 2 の prior art（manimani、
  cloud-itonami/mobile）と設計言語が揃わないため不採用。
- **`gftdcojp` org 配置**: オーナーが明示的に `kotoba-lang`（platform/
  reference UI 位置づけ）を選択したため不採用。

## References

- `orgs/kotoba-lang/inference/`（`cljc/src/kotodama/inference/`, `verify/`,
  `browser/`）
- `orgs/kotoba-lang/num/`（`src/num/{protocol,wgsl,wgsl_backend,cpu}.cljc`,
  `docs/adr/0001-architecture.md`）
- `orgs/kotoba-lang/torch/`（`src/torch/{core,ports}.cljc`）
- `orgs/kotoba-lang/kotoba-ui/`, `orgs/kotoba-lang/appkit/`
- `orgs/gftdcojp/manimani/tauri/`, `orgs/gftdcojp/cloud-itonami/mobile/`
  （Tauri 2 desktop/mobile prior art）
- `orgs/kotoba-lang/murakumo/`（fleet control plane、`murakumo.infer.*`）
- `orgs/gftdcojp/cloud-murakumo-fleet/`（`/infer/*` API）
- `90-docs/adr/2607022000-murakumo-exo-distributed-inference.md`
- `90-docs/adr/2607030030-murakumo-inference-economy-gtm.md`
- `90-docs/adr/2607030400-murakumo-web-wasm-participation.md`
- `90-docs/adr/2607031600-cloud-murakumo-gpu-fleet-requirements.md`
- `90-docs/adr/2607022800-kotoba-lang-default-uiux-appkit-uikit-interface-fundamentals.md`

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
