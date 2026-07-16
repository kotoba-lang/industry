---
id: adr-2607071500-ai-gftd-dougaka-keyframe-encode
title: "ADR-2607071500: ai-gftd-dougaka — ComfyUI keyframe 生成 + offline encode 実行レグ（minidrama 発注書 → 実 mp4）"
status: accepted
doc_type: adr
topic: ai-gftd-dougaka-keyframe-encode
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - ai-gftd-dougaka の keyframe 生成 facade（dougaka.comfy、comfyui.gateway config-map instance）と offline encode 実行レグ（dougaka.pipeline）
  - minidrama committed episode plan（発注書）→ 縦 720x1280 H.264+AAC mp4 + SRT への変換契約
  - dougaka エンジンの img2vid / voice / 字幕焼き込みの follow-up 位置づけ
related:
  - 90-docs/adr/2607071300-aozora-creator-actors-minidrama.md
  - 90-docs/adr/2607011900-genapp-clj-mangaka-animeka-commons.md
  - 90-docs/adr/2607071000-app-aozora-video-upload-r2-blob.md
supersedes: []
superseded_by: []
---

# ADR-2607071500: ai-gftd-dougaka — keyframe 生成 + offline encode

**Status**: accepted（実装済み。gftdcojp/ai-gftd-dougaka main `14a08541`）
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（指示: minidrama follow-up「dougaka エンジン起票」）

## Context（調査でわかった前提 — 当初想定の訂正 2 点）

1. **genapp-clj は存在しない**: ADR-2607011900 自身の同日 correction で
   `comfyui.gateway` / `langchain.jvm` / `langgraph-store` の 3 way に分割・
   `genapp-clj` は削除済み。「第 3 instance は genapp-clj + config map から」の
   現代訳は「共有 3 ライブラリ + per-app cfg map + 薄い facade」。
2. **ai-gftd-dougaka は既存 repo**: `ai-gftd-project-dougaka` から standalone
   split 済みで、timeline → ffmpeg render-plan の純関数群は既に
   `kotoba-lang/douga`（ADR-2607023000）として consume 中。ただし
   `dougaka.graphs.render` は **plan まで**（「mp4 encode/upload は意図的に
   in-process で走らせない」）で、(a) keyframe 生成レグと (b) encode 実行レグが
   無かった。本セッションは当初この既存 repo に気づかず重複 scaffold を
   作りかけ（未 push で発見・巻き戻し）、既存 repo への統合に転換した。

## Decision

既存 ai-gftd-dougaka（+ douga）に、欠けていた 2 レグだけを追加する:

1. **dougaka.comfy** — 縦 720x1280 keyframe facade。`comfyui.gateway` の
   config-map instance パターン（`{:node-type "DougakaKSampler" :default-width
   720 :default-height 1280 …}`、env `COMFY_POD_URL`→`COMFYUI_URL`）。gateway
   不達は placeholder に degrade（never throw、mangaka/animeka と同じ）。
2. **dougaka.pipeline**（JVM、offline 実行 — fleet/laptop の ffmpeg で走らせる。
   server は今後も plan のみ）:
   `minidrama committed episode plan（発注書、ADR-2607071300）→ shot ごとに
   keyframe（stub は deterministic lavfi frame に置換 — GPU 無しでも必ず
   再生可能な動画が出る）→ douga.ffmpeg cmd builders（silent-audio →
   scene-segment(-shortest で尺が正確) → concat）→ 縦 H.264+AAC mp4 +
   sidecar SRT（dougaka.subtitle、純関数）`。
3. **公開はエンジンの仕事ではない**: 出来た mp4 は minidrama actor が
   governor + phase/approval gate 越しに PDS uploadBlob →
   `app.aozora.embed.video` で `/videos` へ（ADR-2607071000/2607071300）。

### Rejected

- **独立した第 3 repo（重複 scaffold）**: 既存 ai-gftd-dougaka + douga と重複。
  作りかけたが未 push で破棄し統合（本 ADR の存在理由の半分はこの記録）。
- **エンジン内 img2vid を v0 に含める**: comfyui.nodes.diffusion の SVD/LTXV
  node 契約は host `:fn` binding 未実装（全 consumer 共通の gap）。R0 は
  keyframe-hold + douga 合成で「今日動く実動画」を優先。

## Verification (2026-07-07)

- 3-shot デモ発注書 → `720x1280 h264+aac`、duration 10.04s（plan=10s —
  douga の -shortest 方式で尺が正確）。ffprobe 確認。
- tests: 既存 7 + 新規 9 = 16 tests / 58 assertions green。新規ファイルは
  clj-kondo クリーン（既存 server.cljc 等の cljs 分岐警告は既存状態のまま）。

## Follow-ups

- img2vid: SVD/LTXV host `:fn` binding（comfyui 側、全 instance 共通の本丸）
- voice（VOICEVOX 等）→ douga の voice レグ実接続、字幕焼き込み
- minidrama :commit → pipeline 発注の自動化（現状は plan EDN の手渡し）
- blob/checkpoint facade（langgraph-store）は server 側で必要になった時に
