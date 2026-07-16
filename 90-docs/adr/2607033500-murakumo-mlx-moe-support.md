# ADR-2607033500: murakumo を mlx-moe(mu-hashmi/mlx-moe)対応にする — cloud-murakumo(murakumo.cloud)+ kotoba-lang/murakumo に単体ノード MoE serving engine を追加

**Status**: accepted — 実装・テスト済み(kotoba-lang/murakumo・cloud-murakumo とも `nbb test` / `clojure -M:test` green)。**worktree 上のブランチに留まり、まだ push/PR/merge はしていない**（オーナー確認待ち）。
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

オーナー指示: 「https://github.com/mu-hashmi/mlx-moe を参考に murakumo.cloud,
kotoba-lang/murakumo を moe 対応にして」。

**用語の衝突に注意**: 「murakumo.cloud」は現状 2つの別物を指す。

1. **`gftdcojp/cloud-murakumo`**（本 ADR の主対象の1つ）— Modal 等価の分散 GPU
   serverless を EDN datom で記述する製品。ドメイン `murakumo.cloud` の公開 SPA
   もここが持つ（ADR-2606272330・2606272300）。vLLM serving(`:llm-serving` app、
   `:engine :vllm`)・生成スタジオ(`:gen`)・課金/監査を持つ。
2. **`kotoba-lang/murakumo`** 自身の README にも「murakumo.cloud —
   replacing Tailscale/WireGuard」という**別の**自前オーバーレイ機能がある
   （`murakumo.overlay`/`cloud.edn`、ADR-2607023100）。GPU/推論とは無関係。

本 ADR は (1) と、オーナーが挙げたもう一方の対象 **`kotoba-lang/murakumo` の
`infer`(fleet 分散推論、exo-style)サブシステム**を対象にする。(2) の overlay
機能には触れない。

`mu-hashmi/mlx-moe`（Apache-2.0、MIT? 要確認 — README に記載無し、要 follow-up）
は Apple Silicon 上で MLX を使い、MoE(Mixture-of-Experts)チェックポイントの
**ルータが選択した expert だけを都度 SSD からロードする**ことで、単体 Mac の
RAM を超えるモデルを走らせる(README 実測: 46GB の Qwen3-Coder-Next を 32GB Mac
で 19.1GB/8-23 tok/s)。OpenAI/Anthropic 互換 API server 付き。

`kotoba-lang/murakumo` は既に **exo-style 分散推論**(`murakumo.infer` +
pure cljc `plan.cljc`/`engine.cljc`)を持ち、fleet 全体でメモリ加重の
contiguous layer partition を切って pipeline-parallel ring を回す
(`:llamacpp-rpc`・`:mlx-ring` の2エンジン、1 GbE 前提で pipeline 固定 —
ADR-2605300000)。`cloud-murakumo` は別に、NVIDIA レンタル GPU
(`:h100`/`:h200`/…)上で vLLM を serve する GPU-serverless auction を持つ
(`resources/murakumo.edn` の `:llm-serving` app、`scheduler.cljc` の
`gpu-catalog`)。

mlx-moe が解く問題は上記どちらとも異なる: **ring もフリート全体の bin-pack も
要らない、1台の Mac で完結する MoE serving**。これは (a) `kotoba-lang/murakumo`
の infer サブシステムに3つめのエンジンとして、(b) `cloud-murakumo` の GPU
auction に「Apple Silicon 統合メモリを $0/h の bid 対象クラスとして追加」する
形で、どちらも**既存の抽象を再利用して**足すのが自然だと判断した。

## Decision

### kotoba-lang/murakumo — `murakumo.infer.moe`(新規 pure cljc)+ 3つめの engine

- `src/murakumo/infer/moe.cljc`: 単体ノード mlx-moe プランナ。
  - `capacity-for-usable`: mlx-moe README のハードウェア表(32/48/64/128 GiB
    usable → capacity 208/320/432/512)をそのままステップ関数化。32 GiB 未満は
    honestly `nil`(未実測領域を推測しない)。
  - `expert-ratio`/`verdict`: README の「どのモデルが恩恵を受けるか」ヒューリ
    スティック(expert比 ≥10x + shared expert → `:recommended` 等)をデータ化。
  - `plan`: fleet 中で最も usable memory が大きい1ノードを選び、
    `murakumo.infer.plan/plan` と**同じ形**({:model :assignments
    :total-usable-bytes :fits?}、単一の `:head? true` assignment が全レイヤ
    span)を返す。これにより `infer.engine/commands`・`infer.credits/settle`・
    `plan/report` を**無改造で**再利用できる(mlx-moe run は「1 assignment の
    ring」として扱える)。
- `src/murakumo/infer/engine.cljc`: `mlx-moe-cmd` + `commands` の `:mlx-moe`
  ケースを追加(`mlx-moe serve <model> --host 0.0.0.0 --port … [--capacity …]
  [--kv-bits …] [--profile …]`)。
- `src/murakumo/infer.clj`: `cmd-plan`/`cmd-provision`/`cmd-serve`/
  `cmd-generate` を `:model/engine :mlx-moe` で分岐(`cmd-plan-moe`/
  `cmd-provision-moe`/`cmd-serve-moe`)。verb は既存と同じ(`plan`/`provision`/
  `serve`/`generate`)— CLI 表面を増やさない。mlx-moe に `up`/`down`/`ps` は
  無意味(ring worker が無い)なので、既存実装のまま(sole assignment が
  `:head?` なので `serving-workers` は空を返し、静かに no-op になる)。
- `infer.edn`: `qwen3-coder-next-mlx-moe` を登録(`:model/engine :mlx-moe`、
  `:model/mlx-repo "mlx-community/Qwen3-Coder-Next-4bit"`、experts/top-k/
  shared-expert は README の実測値、`:model/weight-bytes` は README の
  丸め値であって実ファイルサイズ実測ではないと明記)。
- `test/murakumo/infer_moe_test.cljc`: capacity tier・verdict・単体ノード選択・
  fits ゲート・`engine/commands` 互換性を検証(7 tests / 30 assertions)。
  `nbb.edn` の test task に登録。

### cloud-murakumo(murakumo.cloud)— `:engine :mlx-moe` を vLLM と並ぶ serve engine に

- `src/cloud_murakumo/mlx_moe.cljc`(新規): `vllm.cljc` と同型の
  `serve-args`/`serve-command`(defaults 引数は無視 — CUDA 前提値が無いため)。
- `src/cloud_murakumo/scheduler.cljc`: `gpu-catalog` に
  `:apple-unified-32/48/64/128`(vram-gb = 統合メモリ GB、price 0.0 — 自前
  ハードウェアはレンタルではないので $/h が乗らない、が auction の bid 対象
  にはなる)。
- `resources/murakumo.edn`: `:fleet :nodes` に `:asher`(kotoba-lang/murakumo
  の実 fleet ノード名、canary — 暫定 32GB クラス、要 `infer probe` 実測更新)、
  `:images :mlx-moe-apple`、`:apps :llm-serving :functions
  :qwen3-coder-next-moe`(`:engine :mlx-moe`、`:gpu/count 1` は「ノード
  まるごと1台」の意味で discrete GPU 枚数ではない)。
- `src/cloud_murakumo/cli.cljc`: `cmd-serve-cmd` を `:vllm` 決め打ちから
  `serve-command-by-engine` レジストリ map 経由の dispatch に一般化(vLLM の
  挙動は不変)。
- `src/cloud_murakumo/schema.cljc`: `:murakumo.fn/engine` docstring に
  `:mlx-moe` を追記。
- `src/cloud_murakumo/ui/views.cljc`: landing の「なぜ data-first か」に
  mlx-moe/Apple Silicon fleet の一文を追加(公開サイトが機能を反映)。
  `pricing`/`console`/`studio` は `gpu-catalog`/`spec/functions` から自動導出
  なので**無改造**で新エンジン/新ノードを表示する。
- `test/cloud_murakumo/mlx_moe_test.cljc`(新規)+
  `test/cloud_murakumo/scheduler_test.cljc` に Apple Silicon 系 assertion を
  追加。

### 検証

```
kotoba-lang/murakumo:   nbb test        → 170 tests / 787 assertions / 0 failures
cloud-murakumo:         clojure -M:test → 56 tests / 232 assertions / 0 failures
cloud-murakumo:         clojure -M:doctor / -M:schedule qwen3-coder-next-moe=20 / -M:serve qwen3-coder-next-moe
  → auction が :asher(apple-unified-32)に $0.00/h で配置、
    `mlx-moe serve mlx-community/Qwen3-Coder-Next-4bit --host 0.0.0.0 --port 8080 --capacity 208 --kv-bits 8`
```

## Consequences

**Positive**
- 両リポジトリとも**既存の抽象を再利用**しただけ — `murakumo.infer.moe` は
  `plan.cljc` の出力形を踏襲、`cloud-murakumo` は既存 scheduler/spec/cli の
  拡張点(`gpu-catalog`・`serve-command-by-engine`)にしか触れていない。新しい
  横断的な仕組みを作っていない。
- 自前 Apple Silicon fleet が **$0/h** で GPU auction に bid できるようになり、
  ($/h が 0 の) 経済的に正しい placement(rented GPU より優先)が
  `scheduler.cljc` の既存 best-fit ロジックのままで成立する。
- mlx-moe は fleet 全体のメモリを合算する `:llamacpp-rpc`/`:mlx-ring` の ring
  が要らないので、MoE チェックポイントを cross-node で割る(1 GbE では
  高価な `:expert` 戦略、ADR-2605300000)必要がなくなる — 該当モデルに限り
  安い代替経路ができた。

**Negative / 制約(honest)**
- **まだ push/PR/merge していない**。両リポジトリとも `/tmp/root-*-moe` の
  一時 worktree 上のブランチに留まる。オーナー確認後に着地させる
  (`repos.edn :manifest-workflow` の子リポ経路 — plain git、都度確認不要と
  CLAUDE.md にあるが、本 ADR 作成時点では明示確認前のため実行を見送った)。
- **実 fleet で未検証**: `kotoba-lang/murakumo` の現行ミニは 16 GiB/台
  (infer.edn コメント)で mlx-moe の最小実測 tier(32 GiB usable)を満たさない
  — `infer plan qwen3-coder-next-mlx-moe` は現状のハードウェアでは honestly
  `DOES NOT FIT` を返す。32 GiB 以上のノードが fleet か `:infer/extra-nodes`
  に参加するまで、実機での動作は未検証。
- `cloud-murakumo` 側の `:asher` ノードの `:gpu/class`(32/48/64/128 のどれか)
  は暫定値 — `nbb murakumo infer probe` の実メモリで確定させる follow-up が
  要る(ADR 内コメントに明記済み)。
- mlx-moe 自体のライセンスを README で確認できていない(follow-up)。
- `resident-bytes-estimate`(murakumo.infer.moe)は capacity/experts の単純
  比例配分で、mlx-moe 自身の実測(KV cache/runtime overhead込み)よりやや
  低めに出る近似 — プランニング用の見積りであって課金の確定値ではない。

## References

- https://github.com/mu-hashmi/mlx-moe
- ADR-2605300000（fleet 1 GbE → pipeline-parallel 固定の判断、`choose-strategy`
  の `:pipeline`/`:tensor`/`:expert` 分岐の根拠）
- ADR-2606272300（cloud-murakumo GPU cloud 全体設計）
- ADR-2606272330（murakumo.cloud 公開サイト — cloud-murakumo の SPA）
- ADR-2607023100（kotoba-lang/murakumo 自身の「murakumo.cloud」= overlay、
  本 ADR とは無関係な同名機能であることの注記）
