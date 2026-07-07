# ADR-2607062300: kotoba-lang P2P モデル蒸留 — $3k 予算での route③ + P2P パッケージング（GLM-5.2 圧縮案の棄却）

## Status

Proposed

## Context

### 出発点

「GLM-5.2 の知識・出力を kotoba-lang（edn/cljc）のみに集約し、モデルサイズを落としつつ
agentic coding 能力を残したモデルを作りたい。最終配備はローカル（理想は Apple M4 16GB
1枚）。murakumo.cloud では browser WASM / network-over の P2P 分散推論を想定する。」

### Stage 0 実測（2026-07-06、本 ADR の根拠となる実験）

kotoba-lang の実コードベースから 3 つの実タスク（`kotoba.kgraph` assert-entity /
`kotoba.did-adapter` resolve-did / `kotoba.lang.capability-cacao` parse-cap-uri）を切り出し、
正解実装をスタブ化した worktree 上で `clojure -M:test` による execution-based 採点を行った
（RAG コーパスから解答ファイル自体は除外。採点は対象 test namespace のみに限定し、
無関係な既存 test failure と分離）。

| 候補 | T1 kgraph | T2 did-adapter | T3 cap-uri |
|---|---|---|---|
| GLM-5.2 生（OpenRouter、744B/40B active） | FAIL（架空 API `dc/entity->datoms`） | FAIL（架空 `did/resolve`） | FAIL（`rest` shadowing 実行時エラー） |
| Qwen3.6-35B-A3B 無 RAG（local LM Studio） | FAIL（GLM と同一の架空 API） | FAIL | FAIL（括弧不整合） |
| **Qwen3.6-35B-A3B + BM25 RAG** | **PASS** | **PASS** | 惜敗（39 中 36 assertion pass、残りはドメイン知識と無関係な edge case バグ） |
| LLaDA-8B-Instruct（diffusion、未蒸留） | FAIL（構文崩壊） | — | — |

**中心的発見: 744B の teacher が、domain RAG を与えただけの 3B-active モデルに負ける。**
失敗モードも同型（両者とも同じ「もっともらしい架空 API 名」を幻覚）で、RAG が正しい API 名を
供給した時のみ正解する。knowledge は weight でなく retrieval に置けばよい、という
semi-parametric 仮説の直接の実証。

diffusion 側の副産物: LLaDA-8B は M4/32GB(MPS) 上で 6.7K token prompt が 20 分超で未完了
（diffusion は全系列を毎 step 再計算するため長 prompt と相性最悪）。Modal A100 では同一
prompt が 47.1s で完走。品質は未蒸留ゼロショットのため崩壊（想定内）。

### GLM-5.2 圧縮の理論限界（調査結果）

- GLM-5.2 構造: 78 層（3 dense + 75 MoE）、256 routed experts × top-8 + 1 shared/層、
  hidden 6144、DSA sparse attention、MTP 1 層、753B total / 40B active。
- pruning で削れない backbone（attention + embedding + dense 層 + shared expert）≈ 20-25B。
  4bit でも ~10-12.5GB → **M4 16GB（実効 12-13GB）には routed expert を 1 個も載せる前に
  ほぼ到達。16GB 目標は expert pruning の程度問題ではなくアーキテクチャ的に不可能。**
- ドメイン較正 REAP の「崖の手前」理論値 65-75% expert 削減でも ~200-280B（4bit で
  ~100-140GB）。フリート全体の実効容量（~150GB）と同水準。
- fleet 実容量: M4 mini 16GiB × 11 + M1 Max 32GiB（最大ノード 32GiB、これが全て。
  Mac Studio/Ultra 級は不存在 — オーナー確認済み 2026-07-06）。
- `mlx_lm.lora` はモデル全体を単一ノードの unified memory に要求。murakumo /
  kotoba-lang / mlx-lm のいずれにも「1 モデルの重みを複数ノードに分割した LoRA 学習」は
  存在しない（実装調査済み。`:mlx-ring` は generate 専用、`:training-steps` は課金台帳の
  キーのみ）。→ **GLM-5.2 系の LoRA 学習は pruning 度合いに関わらずフリートでは不可能。**
- llama.cpp RPC 分散推論も GLM-5.2 UD-IQ2_M で prompt eval 0.3 tok/s（SLA fail、
  ADR-2607040845）。数値上収まる REAP50 GGUF Q2_K ですら安定起動実績なし。

### コスト階層（調査済み）

- GLM-5.2 同等（Tier A: ~500B/24B、upcycle + 2-3T token 蒸留）: **all-in $1-3M** — 予算外。
- Tier B（~120B/6B）: all-in $150-300k — 予算外。
- teacher trajectory 蒸留（OpenRouter GLM-5.2、$0.69-1.4/M in・$2.16-4.4/M out）:
  200-1000 trajectory で **$36-370**。
- Modal GPU 実測単価: A100 $2.50/hr、H100 ~$4/hr、H200 $4.54/hr、B200 $6.25/hr。
- `nvidia/GLM-5.2-NVFP4`（465GB 実測、B200/B300 専用）: **inference-only**（vLLM/SGLang のみ、
  fine-tuning 系ツールチェーン非対応）。学習は `zai-org/GLM-5.2-FP8`（一次配布、756GB、
  Unsloth 公式サポート）が正、FP4 は最終配備時の再量子化にのみ意味がある。
  B200×4 での serving 実測ベンチは本 ADR 起票時点で進行中（Modal Volume
  `glm52-nvfp4-cache` にダウンロード中）。

### P2P browser WASM 分散推論の構造分析（murakumo.cloud 前提）

GLM-5.2 の構造を P2P 観点で分解すると: expert 1 個 ~19MB(4bit) は CID 配布単位として理想、
活性化 12KB/token/hop で帯域は無問題、**敵は 78 層の pipeline 深さ（RTT × 直列 hop）と
11GB の backbone**。最適構造の設計原則:

1. replicable backbone（int4 2-3GB 以下 → routing 全ローカル化）
2. fine-grained expert = CID 配布単位（4-20MB、`weight/block/{N}/ffn/*` datom 述語 +
   kotoba-store-web IndexedDB/OPFS がそのまま使える）
3. 浅く広く（24-32 層。pipeline hop = RTT 税を直接削減）
4. MLA 型 KV 圧縮（churn 時のセッション移住を CID checkpoint で軽量化）
5. MTP/speculative head（ローカル draft k=8-16 → swarm は 1 pipeline pass で一括検証。
   12 stage × RTT 40ms の 560ms/token が 70-90ms/token ≈ 11-14 tok/s に償却）
6. diffusion block 復号（代替経路: hop 数が token 数でなく step 数になる。
   Pregel BSP superstep と構造一致）
7. 決定論 int 演算 + expert-dropout 学習（異種 GPU 間 bit-exact → 抜き打ち再計算検証 +
   attestation staking。expert 未 fetch/ノード離脱時の graceful degradation）

NVFP4(e2m1+block scale) は WebGPU ネイティブ FP4 不在でも WGSL の 16 値 LUT デコードで
意味論を再現可能（memory-bound のため tensor core 非加速の損失は小）。

モダリティ別 3 パターン（text T1-T3 / image I1-I3 / video V1-V3 / voice S1-S3）の設計は
本 ADR の会話ログに詳細があるが、決定に効く要点は:
**image/voice の P2P 対応は学習不要のパッケージング問題**（I1: 2B 級 DiT full-local +
weight CDN、S1: 0.1-1B streaming TTS/ASR full-local + voice-pack LoRA 配布）であり、
**学習費が要るのは text と world model だけ**。world model（V3 用）は研究グレードで
$100k-1M+ のため本予算では対象外。

## Decision

**GLM-5.2 の weight 圧縮路線を棄却し、$3,000 予算で「route③: 小型 MoE への蒸留 +
P2P パッケージング」を採る。**

### 予算内訳（合計 ~$650-1,550、上限 $3,000 に対しバッファ ~2x）

| # | 項目 | 内容 | 予算 |
|---|---|---|---|
| 1 | teacher trajectory 蒸留 | GLM-5.2 (OpenRouter) で kotoba-lang 実タスク 500-1500 trajectory（think/tool-call/error-recovery 込みの full trace） | $300-600 |
| 2 | student QLoRA | Qwen3.6-35B-A3B に #1 を蒸留。M1 Max 32GB ローカル（$0）または Modal H100 短時間（$100-300） | $0-300 |
| 3 | draft モデル | 0.6-1B 級（Qwen3.6 系）に同 trajectory を蒸留 → T2 speculative decoding のローカル draft。M1 Max で完結 | $0-50 |
| 4 | expert-dropout + QAT annealing | #2 の最終 5-10% トークンで expert 10% ランダムマスク + int4 QAT を焼く（P2P 頑健化）。Modal 短時間 | $150-400 |
| 5 | P2P パッケージング | expert の CID チャンク化・WGSL int4(LUT) カーネル・OPFS/IndexedDB LRU キャッシュ・router 投機 prefetch（T1 パターン実装）。エンジニアリングのみ | $0 |
| 6 | voice S1 / image I1 | 既存 open checkpoint（F5-TTS 系 / 2B 級 DiT）の CID パッケージングのみ。学習なし | $0 |
| 7 | 評価 | Stage 0 の 3 タスクを 10-20 タスクへ拡張（実 ADR/PR/バグ修正履歴から抽出）、全候補比較 | $50-100 |
| 8 | GLM-5.2-NVFP4 B200 ベンチ | 進行中の実測を完了（logit 蒸留の将来単価の根拠として記録） | $50-100 |

### 構成（成果物のかたち）

- **knowledge 層**: kotoba-lang コーパスへの RAG（Stage 0 の BM25 で実証済み。
  embedding 版への強化は任意）。knowledge は weight に焼かず retrieval に置く。
- **skill 層**: 蒸留済み Qwen3.6-35B-A3B。backbone（~1.5-2B 非 expert 部、int4 ~1GB）は
  ブラウザ複製可能、expert 群は CID ページング（T1）。**M4 16GB 単騎では 2bit 級が必要に
  なるため、単騎配備は draft モデル（#3、int4 で ~0.5GB）+ RAG を「オフライン最小構成」
  とし、フル 35B は T1 の swarm/フリート hybrid で serve する。**
- **speculation**: draft(#3) ローカル + 35B 検証で WAN P2P の RTT 税を償却（T2）。
- 蒸留 trajectory・router activation プロファイル（hot expert 集合）は、将来 Tier B/A に
  進む場合の資産としてそのまま再利用可能（無駄にならない）。

### 明示的に予算外とするもの

- Tier A/B の本学習（$150k-3M）— 本 route の実測が不足を証明してから再検討
- video / world model の学習（V1-V3 は設計のみ保持）
- GLM-5.2 の追加 pruning・logit 蒸留の大規模実行（B200 ベンチで単価だけ確定させる）

## Consequences

- (+) $3,000 以内（見積り中央値 ~$1,000）で、Stage 0 で実証済みの勝ち筋
  （domain RAG + 小型 MoE）を製品化ラインに乗せられる。
- (+) 全学習が M1 Max ローカルで完結可能（Modal はオプションの高速化）。
- (+) trajectory・hot-expert プロファイル・評価 harness は Tier B/A へ昇格する場合の
  直接の資産になる（段階投資: $1k → $80k → $300k → $1-3M の各段で継続判断）。
- (+) image/voice は学習費ゼロで P2P swarm のモダリティ実証に使える。
- (-) M4 16GB 単騎でのフル 35B serving は不可能なまま（draft+RAG の縮退構成か、
  swarm/フリート hybrid が必要）。「16GB 1 枚で GLM-5.2 級」は本予算では物理的に未達。
- (-) GLM-5.2 の weight そのものは使わない（『本物の GLM-5.2 を持っている』という
  一貫性は放棄。ただし teacher としての知識は trajectory 経由で継承される）。
- (-) T1 expert-paging の実ブラウザ実証は WGSL/OPFS 実装工数に依存（コンピュートは
  無料だが人的工数は本 ADR のスコープ外）。

## Alternatives Considered

1. **GLM-5.2 expert pruning（REAP）+ 量子化で 16GB へ**: 棄却 — backbone ~20-25B だけで
   4bit ~10-12.5GB。expert ゼロでも 16GB 枠をほぼ使い切り、アーキテクチャ的に不可能。
2. **community pruned checkpoint（pipenetwork/GLM-5.2-REAP37-MLX-4bit 等）+ mlx-lm LoRA**:
   棄却 — 240-265GB に対しフリート最大ノード 32GiB。mlx-lm は単一ノード unified memory
   必須で、分散 LoRA はスタック全体に不存在。
3. **`zai-org/GLM-5.2-FP8` からのドメイン較正 pruning + Unsloth LoRA（Modal 8×B200）**:
   予算超過（$190-970/回、Tier 化すると $1-3M）として保留。手順・単価は本 ADR に記録済みで、
   route③ の実測が不足を示した場合の次段。
4. **LLaDA 系 diffusion student への蒸留**: 保留 — Modal A100 で推論は 47s/task まで
   短縮できたが、ローカル MPS では長 prompt 非実用。P2P 文脈では block 復号の hop 償却
   （設計原則 6）として理論的に有望なので、T3 パターンとして設計のみ保持。

## Execution record（2026-07-06〜07 実施。addendum）

route③ を一気通貫で実行した。**総費用 ~$35-40（予算 $3,000 の ~1.3%）**。

### 実施内容と成果物（scratchpad `kotoba-distill-stage0/`）

1. **ベンチマーク拡張（#7 完了）**: 3→**18 タスク**（easy 4 / medium 10 / hard 4、
   kotoba / kotoba-lang / datom-clj / did / ical-clj の 5 repo 横断）。全タスクを
   「stub で FAIL・原本で PASS」の両方向オラクル検証済み。prep 自動化
   （`prep_tasks.py`: 変則 splice をやめ、Clojure の last-defn-wins を利用した
   append-override 方式）。RAG は per-task で自答ファイルのみ除外する方式に変更。
2. **teacher trajectory 蒸留（#1 完了）**: GLM-5.2 (OpenRouter) の agentic loop
   （提案→実テスト→エラー feedback→修正、`gen_trajectory.py`）で 66 本生成、
   **40 本が実テスト PASS**（= 検証済み正解 trace）。variant: RAG+t0.2 / RAG+t0.8×2 /
   no-RAG(エラー回復)。**費用 $2.50**（見積 $300-600 の 1/100 — 実測で大幅過大見積り
   と判明。スケール余地大）。SFT データセット: train 37 / val 3（`sft/`）。
3. **student QLoRA（#2 完了、経路変更あり）**: **M4/32GB ローカル学習は不可能と実証**
   （Metal OOM — num-layers 2 / seq 4096 の最小構成でも backward が収まらない。
   本 ADR の「M1 Max ローカルで QLoRA」想定は 35B に対しては反証された）。
   Modal H200 (141GB, $4.54/hr) に切替え、bf16 + attention-only LoRA r=16 で
   **24 分・~$2.5**。eval loss 1.637→**1.399**（6 epoch 単調減少）。
   アダプタ 13.8MB（`adapters/kotoba-qlora-v1-final/`）。
4. **B200 ベンチ（#8 完了）**: GLM-5.2-NVFP4 を vLLM/4×B200 で実測 —
   28.9 tok/s（単一 stream、6.1K prompt）、RAG 付きで正解生成。
   **自前ホストは ~$0.5/trajectory 換算で OpenRouter($0.007) の ~70 倍** —
   「中規模バッチは API が正、自前は大規模 logit 蒸留時のみ」を実測で確定。
5. **効果測定（18 タスク単発 eval、vLLM/H200 で base と LoRA を同一サーバ比較）**:
   **base 7/18 vs LoRA 6/18 — v1 では蒸留効果は測定できず**（LoRA で +2
   [kgraph-query(hard) / datom-entity] / −3 [did-web-helpers / did-key-document /
   kgraph-get-objects]）。

### v1 で効果が出なかった原因（次版への改善点）

- 37 例 × 60 optimizer steps × attention-only r=16 は軽すぎる（容量・データ両方）。
- **形式不一致**: 訓練データの assistant 応答は GLM-5.2 の非 thinking 形式だが、
  Qwen3.6 は推論時に thinking を挟む — 蒸留データは thinking 込み形式で再生成するか、
  student の thinking を無効化して整合させるべき。
- 単発 eval n=18 はノイズが大きい（複数サンプル/topk 評価が必要）。

### 実務知見（再利用価値のあるもの）

- **bitsandbytes 4bit は Qwen3.6 MoE に効かない**: expert が grouped-GEMM 用の
  3D packed tensor（nn.Linear でない）ため素通りし bf16 のまま 72GB ロードされる
  （H100 OOM で発覚）。同アーキの量子化学習は別手段が要る。
- **reasoning model の code-eval は「最初の fence ブロック抽出」だと thinking 中の
  引用（テストコード等）を拾って誤採点する** — 最後の defn 含有ブロックを採る。
  `max_tokens` も thinking 分を見込む（4K では本回答が切れた）。
- vLLM `--enable-lora --lora-modules` で base/adapter を同一サーバから A/B 比較
  できる構成は評価に便利（`modal_serve_eval.py`）。
- scratchpad 内の git worktree は並行セッションの prune に巻き込まれうる —
  eval 中に消失し 10 run が落ちた。長時間ジョブの worktree は再作成前提で書く。

### 残作業（follow-up）

- v2 蒸留: trajectory を 10-20 倍にスケール（$25-50）+ thinking 形式整合 +
  epoch/rank 増で再学習 → 同 18 タスクで再評価。
- 効果確認後に P2P パッケージング（#5: expert CID 化・WGSL int4・OPFS）と
  voice/image パッケージング（#6）へ。

## References

- Stage 0 実験一式: scratchpad `kotoba-distill-stage0/`（BM25 index / run_model.py /
  run_llada.py / modal_llada.py / modal_glm52_nvfp4_bench.py / score2.sh / outputs/）
- `90-docs/adr/2607040845-glm52-current-network-benchmark.md`（フリートで GLM-5.2 serving
  SLA fail の実測）
- `90-docs/adr/2607022000-murakumo-exo-distributed-inference.md`（fleet 構成: M4×11 + M1 Max）
- `90-docs/adr/2607033500-murakumo-mlx-moe-support.md` /
  `2607041100-murakumo-coding-inference-mlx-native-path.md`
- `90-docs/adr/2605250005`（kotoba-llm 統一 weight 述語スキーム — expert CID 配布の土台）
- kotoba WebGPU inference 設計（WGSL MATMUL/RMS_NORM/ROPE/ATTENTION/SWIGLU、
  kotoba-store-web IndexedDB、kotoba-turn TURN relay、Pregel BSP）
- etzhayyim baien-moemoekyun 蒸留プログラム（eval-gated commit policy の先行例）
- モデル: `zai-org/GLM-5.2`（BF16 正本、MIT）/ `zai-org/GLM-5.2-FP8`（一次 FP8）/
  `nvidia/GLM-5.2-NVFP4`（inference-only、465GB 実測）/
  `0xSero/GLM-5.2-504B`・`GLM-5-REAP-50pct-FP8` / `pipenetwork/GLM-5.2-REAP37-MLX-4bit` /
  `GSAI-ML/LLaDA-8B-Instruct` / `qwen/qwen3.6-35b-a3b`
- West et al. "Symbolic Knowledge Distillation" / Distilling step-by-step / RETRO / Atlas
  （semi-parametric 系譜）
