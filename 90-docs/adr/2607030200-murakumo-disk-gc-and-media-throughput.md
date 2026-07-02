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
| 動画 | svd_xt (SVD img2vid, 9.56GB) | 1 台 (asher 16GB) | **~307 s/step**（32フレ/20step ≈ 102分）— memory-bound、実用外 |
| 動画 | **ltxv-2b-0.9.1 (LTX DiT 5.72GB + T5 4.9GB)** | 1 台 (dan 16GB, ollama停止) | **~15.6 s/step**（72フレ/24step ≈ 6分）— **SVD比 ~20倍速、16GB で実用可** |
| 音声 | stable-audio-open | — | ComfyUI 音声ノード(EmptyLatentAudio/VAEDecodeAudio)は present、ただし **モデルが HF gated**（要トークン）で配布不可 |

**スケーリングの上限は checkpoint 保有ノード数**(現状 2 台が warm)。GC で空けた
ディスクへ checkpoint を再配布すれば線形に伸びる — これが GC と分散の接続点。

**動画は 16GB mini では memory-bound で実用外**: SVD_xt(9.3GB)は ollama 停止で
13GB 空けても、sampling 中に 2.4GB→1.2GB free まで swap し **1 step 307 秒**
(SDXL 画像 1 枚 45 秒に対し桁違い)。動画は ≥32GB unified memory のノード
(gad は 48GB だが Metal GPU 無し→CPU で更に遅い)か、小型動画モデル
(LTX-Video 等)か、Thunderbolt mesh が要る。**画像はメディア分散の sweet spot、
動画は次のハード世代待ち**というのが SVD での結論。ただし**モデル選択で
16GB でも動画が回る可能性**が残る（下記 LTX-Video 検証）。

### 追試: LTX-Video（軽量 DiT）を 16GB で

SVD が実用外だったのはモデルが 9.3GB UNet で 16GB を食い潰すため。対して
**LTX-Video 2B は DiT（transformer）で step が UNet より速く、distilled fp8 版は
4.46GB** と半分以下。16GB mini に 8GB 以上の headroom を残せるので swap を避けられる
可能性がある。方針:
- checkpoint: `Lightricks/LTX-Video` の distilled fp8 2B（4.46GB、要 ComfyUI-LTXVideo
  custom nodes）
### LTX 実測結果（2026-07-03、dan 16GB）

- **~15.6 s/step**（SVD の 307 s/step の **~20 倍速**）。72 フレーム/24 step で
  sampling ~6 分（SVD の ~100 分に対し桁違い）。DiT は UNet より per-step が軽い。
- **落とし穴1: LTX checkpoint は diffusion のみ**。text encoder を含まず、
  CLIPTextEncode が `clip=None` で即エラー。**T5-XXL(4.9GB)を CLIPLoader
  type=ltxv で別読み込み**が必須。→ 総メモリ 10.6GB(LTX 5.7 + T5 4.9)。
- **落とし穴2: 総 10.6GB は 16GB mini に対し ollama 常駐と両立不可**。
  ollama 停止で 12.3GB 空けて初めて回る（画像 SDXL は ollama 同居可、動画は不可）。
- 結論: **LTX が 16GB fleet の動画の答え**。SVD は同じ 16GB で実用外だったが、
  モデルアーキ(DiT)と量子化の選択で動画生成が回るようになった。
  fleet の LTX custom node は導入済み(EmptyLTXVLatentVideo/LTXVConditioning)。

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
