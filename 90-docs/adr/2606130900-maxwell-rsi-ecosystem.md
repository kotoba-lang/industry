---
id: adr-2606130900-maxwell-rsi-ecosystem
title: "ADR-2606130900: Maxwell RSi エコシステム — Recursive Self-Improvement ループ"
status: proposed
doc_type: adr
topic: maxwell-rsi-ecosystem
authoritative: true
last_verified: 2026-06-13
priority: 8.0
axis: ml
weight: 0.80
priority_note: "Maxwell (ADR-2606061000) に自己改善ループを付与する。Sakana AI RSi Lab / Darwin Gödel Machine の知見を Murakumo-only / kotoba-native に適合させる。"
authoritative_for:
  - maxwell-rsi-loop
  - 70-tools/scripts/rsi
depends_on:
  - "2606061000"   # Maxwell default LLM weight (R0 scaffold)
  - "2605215000"   # Murakumo-only inference (no commercial GPU)
  - "2605250400"   # gemma-coder-distill recipe (peft+trl EVO-X2 ROCm)
  - "2606120500"   # fleet Clojure refactor + gemma4 CPT 実測
  - "2605262130"   # kotoba storage substrate
  - "2605312345"   # kotoba Datom log = first-class canonical state
related:
  - "2605242400"   # smoke=destructive lesson
  - "2605192200"   # Charter Rider §2(a)-(h) scan
  - "2605241900"   # baien edge invariant (Maxwell は edge ではない)
supersedes: []
superseded_by: []
---

# ADR-2606130900: Maxwell RSi エコシステム — Recursive Self-Improvement ループ

**Status**: proposed  
**Date**: 2026-06-13  
**Deciders**: Jun Kawasaki

# Context

## 先行実測 (ADR-2606120500 まとめ)

| 手法 | held-out coverage |
|---|---|
| whole-file ゼロショット | 4% |
| stage0 関数単位分解 (bb gate) | 26% live unit |
| CPT-LoRA gold 30本 3epoch | base 20.4% vs CPT **18.5% (−1.9pp ノイズ)** |
| SFT-LoRA 139例 | base 20.4% vs SFT **18.5% (−1.9pp ノイズ)** |
| **e4b → 12b base スケール** | e4b 16.7% vs 12b **24.1% (+7.4pp)** |

**結論**: レバーは fine-tune 手法ではなく (1) base 規模、(2) コーパス規模 (→1000s)。

## Sakana AI RSi Lab (2026-06-13 参照)

- **Darwin Gödel Machine (DGM)**: agents がコードを evolutionary selection で書き換える
- **AI Scientist**: 構想→実験→査読を全自動
- **ShinkaEvolve**: ~150試行で複雑最適化を解く
- 共通原理: **計算規模でなく仕組みで競う** — 日本語として自然に etzhayyim に適合

## etzhayyim スタックへの写像

| Sakana RSi | etzhayyim |
|---|---|
| DGM — agent が自分のコードを書き換える | organism fleet が training data を生成 → improved Maxwell が次回の generator になる |
| AI Scientist — 実験→査読ループ | e7m bench micro が selection pressure、kotoba Datom log が実験記録 |
| Agent Native Model | Maxwell (Gemma 4 E4B fine-tune) — etzhayyim actor タスクで訓練 |
| 計算規模でなく仕組みで | Murakumo-only (EVO-X2 + Mac mini fleet)、no commercial GPU |

# Decision

## ループ構造 (7ステップ)

```
①HARVEST  fleet 12b → unit_refactor stage0 → charter scan + clj-kondo gate
          → validated pair → kotoba :maxwell.corpus/*
②CHECK    corpus delta ≥ TRAIN_TRIGGER_DELTA (デフォルト 100 pairs)?
③TRAIN    EVO-X2 SFT-LoRA (peft+trl, lora_B≠0 guard)
          → checkpoint + provenance → kotoba :maxwell.run/*
④EVAL     A/B held-out (base vs new adapter) → clj-kondo scoring
          → kotoba :maxwell.eval/*
⑤GATE     delta_pp ≥ 0.5pp? (ノイズ域 1.9pp を上回るまで deploy しない)
⑥DEPLOY   adapter merge → Ollama maxwell-N → fleet 全ノード swap
          → kotoba :maxwell.checkpoint/* + maxwell-models.jsonl updated
⑦FEEDBACK 改善された Maxwell が次回 ①HARVEST の generator になる (フィードバック)
```

## コンポーネント (70-tools/scripts/rsi/)

| ファイル | 役割 |
|---|---|
| `config.py` | 閾値・パス・fleet ノード等のチューナブル一覧 |
| `cid.py` | ipfs-parity CIDv1 (ibuki/rasen/shionome と同パターン) |
| `kotoba_bridge.py` | `:maxwell.*` datom を :8077 へ append-only 書き込み |
| `corpus.py` | 新ペアの intake: charter scan + clj-kondo gate + dedup + kotoba |
| `harvest.py` | fleet generator で Python → Clojure unit を生成・収穫 |
| `train.py` | EVO-X2 SFT-LoRA 呼び出し、run record → kotoba |
| `train_sft.py` | EVO 上で動く訓練スクリプト (peft+trl chat-format SFT) |
| `eval.py` | A/B eval: EVO 生成 + ローカル clj-kondo スコアリング |
| `deploy.py` | adapter merge → Ollama Modelfile → fleet push → kotoba |
| `loop.py` | RSi ループオーケストレータ (上記 7 ステップ統合) |

## Phase 設計

| Phase | 条件 | 内容 |
|---|---|---|
| **Phase 0 — corpus 蓄積** | corpus < 1000 | harvest を繰り返す。train は skip。改善された generator を待つ。 |
| **Phase 1 — M1 first checkpoint** | corpus ≥ 1000 かつ delta ≥ 100 | 初回 SFT-LoRA → e7m bench → deploy (≥0.5pp) |
| **Phase 2 — RSi ループ** | M1 deployed | Maxwell が generator → better output → larger corpus → M2, M3… |

## 実証済みの設計判断

以下は ADR-2606120500 の実測に基づく:

1. **SFT > CPT**: 検証済みペアでの SFT が CPT より corpus 効率がよい (同じ ~139 例でも CPT は causal loss のみ、SFT はチャット形式で直接 task 学習)。ただし 139 例では両者とも ~20% 天井 → corpus 規模が先決。
2. **12b → e4b 蒸留**: 12b fleet が生成したペアを e4b fine-tune に使う 2-stage 構成。12b が "teacher"、e4b が "student" (Maxwell の deploy target)。
3. **use_reentrant=False + language_model scope**: Gemma4 の vision-tower LoRA no-op 罠を回避済み (train_sft.py に guard 実装済み)。
4. **bb load gate = 真の quality signal**: clj-kondo 合格 ≠ 意味的同値。bb 実ロードが本物のゲート (unit_refactor から引き継ぎ)。現時点は clj-kondo のみだが bb gate は R1 で追加予定。

## Selection Pressure (etzhayyim 固有)

DGM は SWE-bench を fitness function にする。Maxwell RSi では:

1. **Charter Rider §2(a)-(h) scan** — 価値観 alignment が fitness の一部 (入口ゲート)
2. **clj-kondo + bb load** — functional correctness (品質ゲート)
3. **held-out A/B delta_pp ≥ 0.5pp** — 改善の確認 (deploy ゲート)
4. (R2 以降) **e7m bench micro delta** — LLM 汎用能力の退行防止

## Maxwell の立ち位置 (baien との関係を再確認)

ADR-2606061000 §D1 の 4-tier ladder は変わらない:

| Weight | Tier | RSi での役割 |
|---|---|---|
| **Maxwell** | server/fleet | RSi の target (e4b fine-tune, Murakumo fleet) |
| baien | edge | 別 trunk (BitNet ≤4B / ≤2GB); Maxwell RSi の影響を受けない |

## Datom Schema (append-only)

```edn
;; :maxwell.corpus/*
{:db/ident :maxwell.corpus/cid      :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.corpus/pair-id  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.corpus/actor    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.corpus/fn       :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.corpus/charter-scan :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.corpus/gate     :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.corpus/at       :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}

;; :maxwell.run/*
{:db/ident :maxwell.run/id           :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.run/corpus-cid   :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.run/base-model   :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.run/epochs       :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.run/lora-r       :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.run/corpus-size  :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.run/checkpoint-path :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.run/checkpoint-cid  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.run/lora-b-mass  :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.run/final-loss   :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.run/at           :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}

;; :maxwell.eval/*
{:db/ident :maxwell.eval/id         :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.eval/run-id     :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.eval/base-pp    :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.eval/new-pp     :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.eval/delta-pp   :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.eval/n-units    :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.eval/verdict    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.eval/at         :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}

;; :maxwell.checkpoint/*
{:db/ident :maxwell.checkpoint/id           :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.checkpoint/run-id       :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.checkpoint/ollama-model :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.checkpoint/hf-model     :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.checkpoint/base-pp      :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.checkpoint/deployed-pp  :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
{:db/ident :maxwell.checkpoint/at           :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
```

## ゲート

| Gate | 内容 |
|---|---|
| G1 (Charter scan) | corpus 追加時、全テキストを §2(a)-(h) scanner に通す |
| G2 (clj-kondo) | Clojure 出力の lint ゲート |
| G3 (bb load) | R1 予定 — bb 実ロードによる実行確認 (より強い functional gate) |
| G4 (lora_B≠0) | 訓練完了時、adapter が no-op でないことを確認 |
| G5 (A/B delta) | 0.5pp 未満の改善は deploy しない (noise guard) |
| G6 (--confirm-deploy) | fleet push は明示的フラグ必須 (事故防止) |

# Phase 0 実績 (2026-06-13 session close)

## corpus 蓄積

| batch | files | raw pairs | new (post-dedup) | corpus total |
|---|---|---|---|---|
| wave1 (旧 collect_corpus) | hakoniwa/hoshimori | — | 27 | 27 |
| batch1 (unit_refactor fleet) | 50 files (asobi/hokorobi/hoshimori/他) | 99 | 98 | **125** |

Target: 1000 pairs (train trigger delta=100 ごとに訓練発火)

## インフラ

| 項目 | 状態 |
|---|---|
| fleet SSH (naphtali/dan/他 9ノード) | **Tailscale IP (100.x.x.x) で全接続確認** |
| fleet Ollama (naphtali) | `gemma4:12b-it-qat` / `gemma4:e4b-it-qat` 稼働中 |
| EVO-X2 (gad) | **オフライン** — Tailscale 未接続, LAN 192.168.1.16 timeout |
| 訓練実績 | **ゼロ** — gad 復帰まで corpus 蓄積のみ |

## 対応済み

- `~/.ssh/config` fleet HostName を LAN IP → Tailscale IP に更新 (全ノード `ssh <name>` で接続可)
- batch2 dedup: unit_refactor が同ファイルを再処理 → `_pair_cid` が正しく弾いた (正常動作)
- `tests/test_rsi.py` 20 tests green

## 残タスク (Phase 0 完了まで)

1. gad を Tailscale に接続 (`tailscale up`) → `~/.ssh/config` に gad エントリ追記
2. harvest batch 3〜 を継続 (`discover_unharvested_py` で未処理ファイルを取得)
3. corpus ≥ 1000 → `loop.py run` で Phase 1 (M1 training) 発火

# Consequences

## Positive

- Sakana AI RSi Lab の研究成果 (DGM/AI Scientist) を Murakumo-only + kotoba-native に適合させた初の実装
- corpus 蓄積 → 訓練 → 評価 → deploy の全ステップが kotoba Datom log に記録される (tamper-evident)
- 改善された Maxwell が次の harvest generator になる (フィードバックループ)
- Charter alignment が selection pressure の一部 (世界観と技術の一致)

## Negative / 正直な限界

- **現状 corpus 125例**: Phase 1 (M1) は corpus ≥ 1000 待ち。Phase 0 が先 (27 wave1 + 98 batch1)
- **bb gate は R0 では clj-kondo のみ**: 意味的正確性は G3 (bb load) まで保証されない
- **deploy_threshold 0.5pp は conservative**: ノイズ域 (±2pp) に対して余裕は小さい。1pp 以上が望ましいが、まず動かす
- **12b fleet → e4b Maxwell の capability gap**: 12b の teacher と e4b の student の間の能力差は蒸留損失になる。e4b の天井は 12b を超えられない
- **EVO-X2 (gad) offline**: Tailscale 未接続。`tailscale up` 後に `~/.ssh/config` へ gad エントリ追記が必要 (現 LAN IP 192.168.1.16 は timeout)

# Alternatives Considered

- **e4b RSi せず 12b を fleet に deploy**: 12b は fleet のメモリ/レイテンシ制約に当たる可能性。e4b の server tier を維持した上で 12b を teacher に使う 2-stage が妥当
- **RLHF / preference data**: human preference labels がない。functional correctness (bb gate) を reward signal に使う、ReST-EM 的アプローチを採用
- **Unsloth 使用**: gfx1151 ROCm で FAIL 確認済み (ADR-2605250400) — peft+trl direct を継続

# References

- `70-tools/scripts/rsi/` — RSi ループ実装 (このADR)
- `70-tools/scripts/rsi/tests/test_rsi.py` — 20 tests green
- ADR-2606061000 (Maxwell R0 — naming + registry scaffold)
- ADR-2606120500 (fleet Clojure refactor + CPT/SFT 実測)
- ADR-2605250400 (gemma-coder-distill recipe / EVO-X2 ROCm)
- ADR-2605215000 (Murakumo-only inference)
- ADR-2605262130 (kotoba storage substrate)
- Sakana AI RSi Lab: https://sakana.ai/rsi-lab-jp/
- Darwin Gödel Machine (Sakana AI, 2025)
