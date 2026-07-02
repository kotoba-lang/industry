# ADR-2607030200: murakumo fleet の disk GC とメディア生成スループット評価

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

メディア生成優先(ADR-2607030030)で fleet を render farm 化したが、2 つの運用課題が出た:
(1) ノードのディスクが埋まる — GLM/Qwen の分散推論が残した **dead RPC tensor cache
15–22 GB/台** + 旧 HF ダウンロードで asher が 116 MiB free まで逼迫し生成不能に
(2) 「分散推論の生成速度」を測る手段が無かった。

## Decision

### disk GC (`murakumo.infer.gc` 純 cljc + `gc-op` bb)

キャッシュを分類し、各ノードが目標空き容量に達するまで**優先順**に回収する純ポリシー:

| class | 回収可能性 | 根拠 |
|---|---|---|
| `:rpc-cache` | 最優先 | 分散 run 終了で死ぬ。GGUF から prewarm で完全再生成可 |
| `:comfy-temp` | 次 | keep-days より古い temp/output |
| `:hf-stale` | LRU | 最新 hf-keep 個を残し古い順。HF から再取得可 |
| `:protected` | **絶対不可** | ollama モデル(owner)・active checkpoint・台帳・murakumo バイナリ |

目標達成で即停止(過剰削除なし)。dry-run 既定、`--apply` で実回収。6 policy tests。

**実測**: asher 0.1G→34.8G(dead RPC 15.4G + stale HF 19G)、zebulun 11.8G→29.8G を
回収してメディア生成をアンブロック。SVD checkpoint(protected)は無傷を確認。

### メディアスループット評価 (`murakumo.infer.bench` bb)

同一ジョブのバッチを投げ、スケジューラに分散させ、**1台 vs fleet の images/min**
(速度向上 = single-ms / fleet-ms)、動画の frames/s、音声の ×realtime を報告。

## 実測結果(2026-07-03)

| メディア | モデル | 構成 | スループット |
|---|---|---|---|
| 画像 | animagine-xl-4.0 (SDXL) | 1 台 (zebulun) | 1.33 imgs/min (~45s/枚) |
| 画像 | 同 | fleet 2 台 (zebulun+dan) | 1.75 imgs/min = **1.3x** |
| 動画 | svd_xt (SVD img2vid) | 1 台 (asher) | 本 ADR 末尾に実測 |
| 音声 | stable-audio-open | checkpoint 配布待ち | — |

**スケーリングの上限は checkpoint 保有ノード数**(現状 2 台が warm)。GC で空けた
ディスクへ checkpoint を再配布すれば線形に伸びる — これが GC と分散の接続点。

## 得られた教訓

- **16GB mini の RAM 制約はメディアにも効く**: SVD_xt(9.3GB)は常駐 ollama と同居
  できず、ollama 停止で 7GB→13.4GB に空けて初めて動く。GLM の RAM 則のメディア版。
- **契約は engine に一致させる、テスト同士でなく**: comfyui-clj の
  ImageOnlyCheckpointLoader 契約と murakumo の i2v ワークフローが「同じ誤り」
  (存在しない4番目の出力を参照)で一致していたため bridge テストは通ったが、
  実 ComfyUI が `tuple index out of range` で拒否。契約を実物の3出力に修正。

## Consequences

- fleet は自己保全する(GC が定期回収)。cron/lattice on-tick で自動化可能。
- スループットは「1台の速度 × warm ノード数」で予測でき、事業の容量計画
  (企業 fleet の台数見積り)に直結する。
