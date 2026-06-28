# ADR-2606272330: gftd.ai 生成スタジオ — murakumo を基盤に chat/voice/image/music/sfx/3D の生成 API + UI/UX を datom 化する

**Status**: closed
**Date**: 2026-06-27
**Closed**: 2026-06-28
**Scope**: `orgs/gftdcojp/cloud-murakumo`（生成関数の追加）/ `orgs/gftdcojp/ai-gftd-router`（製品面・新規）/ `orgs/gftdcojp/network-isekai` + `etzhayyim/kami-engine`（消費側）

## Context

gftd.ai 配下で **生成 AI のプロダクト面**を立てる必要がある。要求モダリティは
チャット・音声(TTS)・画像・音楽・効果音・3D モデル。これらの実消費者は 2 系統:

1. **isekai.network / kami engine** — ワールド/ゲーム IP。画像生成・3D モデル生成で
   アセットを起こし、`kami-engine`（`etzhayyim/kami-engine`、scene/render-IR。Rust
   `kami-render` が GPU アーム）でシーンを合成・描画する。storyboard→KAMI scene の橋は
   `ai-gftd-project-mangaka/clj/src/mangaka/kami.cljc` に既存。
2. **gftd.ai 直のクリエイター** — 人間がブラウザから chat/voice/image/music/sfx/3D を回す
   「クリエイティブ・スタジオ」。

GPU 基盤は **`cloud-murakumo`**（ADR-2606272300）に既にある: Modal 等価の分散 GPU
serverless を EDN datom 化し、auction 配置・autoscale/scale-to-zero・`gpu-seconds` 課金・
財務承認 gate を持つ。ただし現状は **vLLM serving（chat 用 LLM）だけ**で、image/3D/audio の
生成は語彙化されていない。

つまり gap は 2 つ:

1. **基盤の gap**: murakumo の `:fn/kind` は `:serve`/`:batch`/`:scheduled` のみ。拡散モデル・
   3D 生成・音声合成のような「リクエスト→成果物(artifact)」型の非 OpenAI ジョブが無い。
2. **製品の gap**: モダリティ横断の統一生成 API と、CID 由来の成果物ライブラリを持つ
   UI/UX（スタジオ）が無い。isekai/kami が同じ API を機械的に叩く XRPC 面も無い。

## Decision

`cloud-itonami`（business を datom 化）/ `cloud-murakumo`（Modal を datom 化）と同じ
設計原則で、**生成 AI を 3 層に分けて datom 化**する。

```
┌──────────────────────────────────────────────────────────────────┐
│ 消費層  isekai.network / kami-engine（ワールド・IP）             │
│         mangaka(storyboard→KAMI scene) / NPC voice / world BGM    │
│                    │ XRPC (ai.gftd.gen.*)  /  REST                │
├────────────────────┼─────────────────────────────────────────────┤
│ 製品層  gftd.ai 生成スタジオ  (gftdcojp/ai-gftd-router)             │
│         ・統一ジョブ API  POST /v1/gen → job → artifact(CID)     │
│         ・OpenAI 互換 /v1/chat/completions（既存 vLLM へ proxy） │
│         ・AT-proto lexicon ai.gftd.gen.*（itonami と同型）       │
│         ・studio.gftd.ai（ClojureScript/re-frame, CID ライブラリ）│
│                    │ 配置・実行・課金は全部 murakumo へ委譲       │
├────────────────────┼─────────────────────────────────────────────┤
│ 基盤層  cloud-murakumo（GPU serverless / Modal 等価）            │
│         :fn/kind :gen を追加（request→artifact 非同期ジョブ）    │
│         engine: vllm | comfy | trellis | audio | tts | kami-render│
│         auction 配置 / scale-to-zero / gpu-seconds 課金 / 承認gate │
└──────────────────────────────────────────────────────────────────┘
```

**原則**:

- **基盤は murakumo を置き換えず拡張**する。新しいのは「`:serve`（常駐 OpenAI endpoint）に
  対して `:gen`（リクエスト→成果物の非同期ジョブ）という `:fn/kind` を足す」ことだけ。
  配置(auction)・autoscale・課金(`gpu-seconds`)・承認 gate は **そのまま再利用**。
- **エンジンは差し替え可能なアダプタ**。`cloud-murakumo.vllm` が serve コマンドを EDN から
  生成したのと同じく、`comfy`/`trellis`/`audio`/`tts`/`kami-render` 各アダプタが EDN から
  起動コマンド/ジョブ仕様を生成する（Python decorator ではなく値）。
- **成果物は kotoba CID**。画像/音/glTF/動画は B2 の `:artifacts` volume に置き、git には
  載せない（CLAUDE.md「大容量バイナリは B2+DataLad」を継承）。API は CID で参照を返す。
- **製品面は AT-proto NSID lexicon**（`ai.gftd.gen.*`）。itonami が `ai.gftd.apps.itonami.*`
  を kotoba XRPC で公開したのと同型にし、isekai/kami が機械的に消費できる。
- **chat だけ同期ストリーミング**（OpenAI 互換 `/v1`、既存 vLLM serve へ proxy）。
  残り（voice/image/music/sfx/3D）は submit→subscribe(SSE)→artifact の非同期ジョブ。

正本: 生成関数の SSoT は `cloud-murakumo` の `resources/murakumo.edn`（`:generation` app を追加）。
製品面の lexicon/UI 正本は `gftdcojp/ai-gftd-router`。実行正本は `.cljc`。

## Existing assets — 再利用する（greenfield しない）

調査の結果、生成系はゼロからではなく**既存資産の GPU バックエンドを murakumo に寄せる**
作業である。本 ADR は新規モデル配線より「散在する生成パイプの実行面を `:gen` 関数に
統合する」ことを主眼にする。

- **kami-engine**（`com-junkawasaki/kami-engine`, 30+ Rust crates, wgpu WebGPU→WebGL2）:
  - `kami-cine` = **8 段ニューラル映像パイプ**（world-model → USD → neural-geom →
    neural-render → **diffusion** → EXR）。WIT contract あり。→ diffusion/neural-render
    段が murakumo `:gen`（`:engine :kami-render`/`:comfy`）の主消費者。
  - `kami-nerf`（NeRF）/ 3D Gaussian splat preview（`GsplatAdapter`）/ `kami-terrain` /
    `kami-vegetation` → **3D 生成の出力先**（TRELLIS/Hunyuan の glTF を取り込む）。
  - `kami-audio`（HRTF/3D パン）/ `kami-rt-binaural` / `kami-sound.js`（27 preset Web Audio）
    → **music/sfx 生成の出力先**（生成音を空間音響でワールドに配置）。
- **既存の画像生成**: ComfyUI gateway（shiropico worktree `ADR-0088-comfyui-image-generation-gateway`）
  と **animeka** 画像パイプ。→ `:engine :comfy` 関数として murakumo に正規化・集約し、
  gateway は gftd.ai `/v1/gen` の image backend にする（二重運用しない）。
- **isekai.network**（`gftdcojp/network-isekai`）: ブラウザで CLJ/EDN ゲームを play/fork
  （isekai.network on Cloudflare）。VRM dance・USD アセット・Datomic fork 履歴。
  `kami-app-isekai`（Plains biome voxel sandbox）が実ゲーム。→ 生成アセットの**最終消費地**。
- **langgraph-clj StateGraph**: chat/agent はここで動く既存ランタイム。`/v1/chat/completions`
  の上位オーケストレーションに使う（sealed-LLM actor 群と同じ流儀）。

→ よって `:gen` 関数の engine は「新モデルを足す」より **既存パイプ（kami-cine diffusion,
ComfyUI gateway, animeka, kami-render）の GPU 実行を murakumo の auction/課金/承認に載せ替える**
アダプタとして設計する。

## Core Vocabulary

### murakumo 側（基盤、`cloud-murakumo.schema` に追加）

- `:murakumo.fn/kind :gen` — request→artifact の非同期ジョブ関数（`:serve` と対称）
- `:murakumo.fn/engine` — `#{:vllm :comfy :trellis :audio :tts :kami-render}`
- `:murakumo.fn/modality` — `#{:chat :voice :image :music :sfx :3d}`（製品面 routing 用のタグ）
- 既存の `:murakumo.run/*`（`:gpu-seconds` 課金）・`:murakumo.placement/*`・`:murakumo.effect/*`
  （承認 gate）・`:murakumo.volume/*`（B2/CID artifact）は **無改造で再利用**

### gftd.ai 側（製品、`ai-gftd-router` lexicon）

itonami の append-only datom 流儀を踏襲。ジョブは不変イベントとして残す。

- `:gen.job/id`        — ULID
- `:gen.job/modality`  — `#{:chat :voice :image :music :sfx :3d}`
- `:gen.job/model`     — モデル id（murakumo の fn へ解決）
- `:gen.job/input`     — `{:prompt … :refs [CID…] :params {…}}`
- `:gen.job/status`    — `#{:queued :running :done :failed}`
- `:gen.job/artifacts` — `[CID…]`（成果物。B2/kotoba。`media-type`/`provenance` 付き）
- `:gen.job/run`       — `:murakumo.run`（`gpu-seconds` → 課金へ）
- `:gen.job/actor`     — 発行主体（gftd.ai アカウント / isekai world / kami pipeline）

## Generation API（gftd.ai 製品面）

REST + SSE。最小面:

| Method / Path | 用途 |
|---|---|
| `POST /v1/chat/completions` | OpenAI 互換。既存 murakumo vLLM serve（minimax/kimi）へ proxy。ストリーミング同期 |
| `POST /v1/gen` | ジョブ生成 `{modality, model?, input, params}` → `{job}`。非同期 |
| `GET  /v1/gen/{id}` | ジョブ状態 poll |
| `GET  /v1/gen/{id}/events` | SSE 進捗（queue 位置 / % / プレビュー） |
| `GET  /v1/models` | モダリティ別の利用可能モデル一覧（`murakumo.edn` から射影） |
| `GET  /v1/artifacts/{cid}` | 成果物取得（Read 面 CID-over-HTTP / B2 署名 URL） |

AT-proto lexicon（`ai.gftd.gen.*`、itonami と同型 / kotoba XRPC で公開）:
`createJob` / `getJob` / `listJobs` / `listModels` / `job`(record)。
→ isekai/kami は SDK 無しで XRPC 直叩きできる。

**認証**: gftd.ai アカウント単位 api-key（murakumo `:endpoint {:auth :api-key}` を継承）。
**課金**: `:gen.job/run` → `:murakumo.run/gpu-seconds`。GPU 確保は既存 `:financial` gate を通る
（暴走生成による課金事故を fail-closed で防ぐ）。

## UI/UX（studio.gftd.ai — クリエイティブ・スタジオ）

シングルページのクリエイター向けスタジオ。CID 基盤＝**全成果物が不変・共有可能・再現可能**
（datom/CID 哲学とそのまま整合）。

**レイアウト**
- **左レール**: モダリティ切替 — チャット / 音声 / 画像 / 音楽 / 効果音 / 3D。加えて
  **パイプライン**（マルチモーダル連鎖）と **ライブラリ**。
- **中央**: プロンプト + パラメタ + 参照ドロップ（画像/音を CID で参照）。ライブキャンバス
  — 画像グリッド / 音声波形＋プレイヤー / 3D ビューア(`<model-viewer>`/three.js) / chat 履歴。
  進捗は SSE。
- **右レール**: **ライブラリ** — CID 由来の成果物履歴。参照として再投入可。provenance
  （使用モデル・`gpu-seconds`・推定コスト）を各成果物に表示。

**パイプライン（差別化点 / isekai・kami 連携）**
- **チャット→絵コンテ→KAMI scene**: mangaka 橋（`mangaka/kami.cljc`）で storyboard を
  KAMI scene/render-IR に落とし、`kami-render`（murakumo `:gen` 関数）で描画。
- **画像→3D**: 画像をドロップ → TRELLIS で glTF（isekai アセット化）。
- **シーン作曲**: 3D シーン + プロンプト → BGM(music) + 環境音(sfx) → isekai ワールドへ。
- **キャラボイス**: テキスト + 声リファレンス → TTS で isekai NPC のセリフ生成。

**技術**: 本リポジトリは Clojure/cljc + kotoba WASM が主軸なので、UI も **ClojureScript
(reagent/re-frame)**。成果物は kotoba CID、配信は kotoba `on-http` component。チャットは
SSE ストリーム。デザインは dark・日本語ファースト・キーボード駆動。

## Model defaults（proposed。代替は Alternatives 参照）

scale-to-zero の `:gen` 関数として既存 fleet（asagi/kurenai=h100, midori=h200,
ai=a100-80, sora=l4）に同居。idle 0 replica。

| modality | 既定モデル | engine | gpu（目安） |
|---|---|---|---|
| chat | MiniMax-M2.7 / Kimi-K2.7（既存） | vllm（serve） | h100×4 / h200×8 |
| voice (TTS) | CosyVoice2(JA) + Kokoro(高速) | tts | l4×1 |
| image | FLUX.1-dev + Qwen-Image（+SDXL-Turbo 高速） | comfy | a100-40 / l4 ×1 |
| music | ACE-Step + Stable Audio Open | audio | a100×1 |
| sfx | Stable Audio Open（text→sfx） | audio | l4×1 |
| 3d | TRELLIS + Hunyuan3D-2.1 | trellis | a100-80×1 |
| scene render | kami-render（Rust GPU アーム） | kami-render | l4/a10×1 |

## murakumo.edn 追加（proposed パッチ）

`resources/murakumo.edn` に **`:generation` app と新 image を追加**するだけ。既存 schema/
scheduler/runtime は無改造。新規エンジンアダプタ ns（`comfy`/`trellis`/`audio`/`tts`/
`kami-render`）と汎用ジョブ runtime `cloud-murakumo.gen` を実装する。

```clojure
;; :images に追加
:comfy-cuda   {:base "nvidia/cuda:12.4.1-devel-ubuntu22.04"
               :pip ["comfyui" "diffusers" "transformers" "accelerate" "huggingface_hub"]
               :env {"HF_HUB_ENABLE_HF_TRANSFER" "1"}}
:trellis-cuda {:base "nvidia/cuda:12.4.1-devel-ubuntu22.04"
               :pip ["torch" "trimesh" "xatlas" "huggingface_hub"]}
:audio-cuda   {:base "nvidia/cuda:12.4.1-devel-ubuntu22.04"
               :pip ["torch" "audiocraft" "stable-audio-tools" "huggingface_hub"]}
:tts-cuda     {:base "nvidia/cuda:12.4.1-devel-ubuntu22.04"
               :pip ["torch" "cosyvoice" "kokoro" "huggingface_hub"]}

;; :apps に追加
:generation
{:app/desc "gftd.ai 生成スタジオの GPU バックエンド(image/3d/audio/tts。chat は :llm-serving)"
 :functions
 {:image {:fn/kind :gen :fn/engine :comfy   :fn/modality :image
          :gpu/min-vram 24 :gpu/count 1 :image :comfy-cuda
          :volumes [:hf-cache :artifacts] :scale {:min 0 :max 4 :target-concurrency 4}
          :gen {:models {"flux.1-dev" {} "qwen-image" {} "sdxl-turbo" {:fast true}}}}
  :model3d {:fn/kind :gen :fn/engine :trellis :fn/modality :3d
            :gpu/class :a100-80 :gpu/count 1 :image :trellis-cuda
            :volumes [:hf-cache :artifacts] :scale {:min 0 :max 2 :target-concurrency 2}
            :gen {:models {"trellis" {} "hunyuan3d-2.1" {}} :out [:gltf :glb]}}
  :music {:fn/kind :gen :fn/engine :audio :fn/modality :music
          :gpu/class :a100-80 :gpu/count 1 :image :audio-cuda
          :volumes [:hf-cache :artifacts] :scale {:min 0 :max 2 :target-concurrency 2}
          :gen {:models {"ace-step" {} "stable-audio-open" {}}}}
  :sfx {:fn/kind :gen :fn/engine :audio :fn/modality :sfx
        :gpu/min-vram 24 :gpu/count 1 :image :audio-cuda
        :volumes [:hf-cache :artifacts] :scale {:min 0 :max 4 :target-concurrency 6}
        :gen {:models {"stable-audio-open" {}}}}
  :voice {:fn/kind :gen :fn/engine :tts :fn/modality :voice
          :gpu/min-vram 24 :gpu/count 1 :image :tts-cuda
          :volumes [:hf-cache :artifacts] :scale {:min 0 :max 6 :target-concurrency 8}
          :gen {:models {"cosyvoice2" {:lang :ja} "kokoro" {:fast true}}}}
  :render {:fn/kind :gen :fn/engine :kami-render :fn/modality :3d
           :gpu/min-vram 24 :gpu/count 1 :image :trellis-cuda
           :volumes [:artifacts] :scale {:min 0 :max 4 :target-concurrency 4}
           :gen {:in :kami-scene-ir :out [:png :mp4]}}}}
```

## Risk Gate / 課金

- 生成ジョブの GPU 確保 = 既存 `:scale-up`/`:deploy` の `:financial` gate をそのまま通る。
- 製品面で **アカウント別クォータ**（`gpu-seconds` 上限）を追加し、超過は `:gen.job/status
  :failed` で fail-closed。暴走プロンプトループによる課金事故を防ぐ。
- 成果物の安全性（NSFW/権利）チェックは `:gen` ジョブの後段フィルタとして別 effect に置き、
  isekai/IP のガバナンス（itonami の CertGovernor 流の独立 governor）に接続可能にする。

## Transport / Placement reach

ADR-2606271700（2 平面）に従う。生成ジョブ実行は **Live 面**（libp2p QUIC, native fleet）、
成果物 artifact の配布は **Read 面**（CID-over-HTTP）。edge `:l4`(`sora`) は `:http` reach なので
高速・軽量モデル（sdxl-turbo / kokoro / sfx）の置き場に向く。

## Alternatives considered

- **Modal / Replicate / fal.ai に直接投げる**: 既に murakumo（Modal 等価）が auction 配置・
  課金・承認 gate を datom 化済みなので、外部 SaaS を足すと SSoT と課金監査が二重化する。
  ネイティブ fleet（H100/H200/A100）を使い切る方が原価で有利。→ 不採用（バースト時の
  edge spillover として将来 `:reach :http` の外部ノードを auction に乗せる余地は残す）。
- **モダリティごとに別 API/別ドメイン**: chat と image で別エンドポイントは UX/課金が割れる。
  → 統一 `POST /v1/gen` ジョブ + modality タグに集約（chat だけ OpenAI 互換で別口）。
- **UI を React/Next で別スタックに**: 本 monorepo は cljc + kotoba WASM が主軸。成果物 CID・
  XRPC・on-http component との整合を取るため ClojureScript/re-frame を採る。
- **モデル選定**: image=FLUX/Qwen, 3d=TRELLIS/Hunyuan, music=ACE-Step/Stable Audio,
  tts=CosyVoice2/Kokoro はいずれも 2026-01 時点の強い OSS。pin はライセンス確認後に確定。

## Consequences

- gftd.ai の生成 AI は **単一の operating surface**になる: 基盤は `murakumo.edn` + `clj -M:*`、
  製品は `ai.gftd.gen.*` lexicon + studio.gftd.ai。Modal decorator も SaaS API キーも増やさない。
- isekai.network / kami engine は **人間と同じ生成 API を XRPC で機械消費**できる（mangaka 橋・
  NPC voice・world BGM が全部同じジョブモデル）。
- 生成 1 回 = `:gen.job` + `:murakumo.run` の datom になるので、Datomic as-of / Datalog で
  「いつ・誰が・どのモデルで・何 `gpu-seconds`・いくら」を横断照会できる（itonami/murakumo と統一）。
- 成果物は CID 不変・B2 実体なので、git/clone を重くせず再現・共有・provenance が効く。
- 関心の分離: murakumo は GPU を、ai-gftd-router は製品/課金 UX を、isekai/kami は IP を持つ。

## Verification Notes

2026-06-27 の実装検証（babashka / clojure 1.12、cloud-murakumo checkout）。基盤層
（murakumo.edn パッチ + `cloud-murakumo.gen` アダプタ + schema 拡張）を純データで検証:

- `murakumo.edn` に `:generation` app（image/model3d/music/sfx/voice/render）+ 4 image を追加。
  `spec/functions` で 10 fn 中 **6 gen fn** を認識。
- `cloud-murakumo.gen`: `resolve-model`（未指定→default、提供外は **silent fallback せず例外**）、
  `worker-command`（engine 別 entrypoint。`:render` は scene-IR 段なので `--models` を出さず
  `--in kami-scene-ir --out png,mp4`）、`job`（modality/model 検証 + 正規化）。
- 既存 scheduler/spec を **無改造**で gen 負荷を配置: `image=8 model3d=2 voice=16` →
  unscheduled 0、`min-vram 24` は最安 `l4`(sora)、`a100-80` strict は `ai` に bin-pack、
  util `{:ai 0.25 :sora 1.0}`、est **$10.00/h**。無負荷は scale-to-zero（demands 空）。
- テスト: `cloud-murakumo.scheduler-test` **7 tests / 17 assertions** 既存パス（regression 無し）。
  新規 `cloud-murakumo.gen-test` **5 tests / 19 assertions** パス。

製品層（ai-gftd-router lexicon / studio UI）と各 engine の実ワーカ（`worker.comfy` 等の Python
実装）・実 GPU/モデル pin は未着手（Open Questions 参照）。本 checkout は基盤の純データ
（spec/gen/scheduler）まで検証済み。

## Open Questions（次の意思決定）

1. ~~製品リポは新規 `gftdcojp/ai-gftd-router` で良いか~~ → **決定（2026-06-28）: `gftdcojp/ai-gftd-router`**
   （`ai-gftd-*` 命名に整合。subdomain は `studio.gftd.ai` / `gen.gftd.ai`）。
2. モデル pin の確定（ライセンス・日本語性能・VRAM 実測）。特に商用利用可否（FLUX dev,
   Stable Audio, CosyVoice2 のライセンス）。**未了**。
3. ~~アカウント/課金の主体~~ → **決定（2026-06-28）: gftd.ai 単独テナント**。isekai/kami は
   別テナントに割らず gftd.ai の actor として同一口座（課金は単一、provenance は actor タグ）。
4. ~~(a)/(b) どちらを先に着手するか~~ → **決定: (a) 基盤先行 → (b) 製品層**。両方着手済み（下記）。

## Verification — basis layer (a) implemented, 2026-06-28

Open Question #4 の (a)「murakumo.edn パッチ + アダプタ ns」を基盤層として実装・検証した
（製品リポ `ai-gftd-router` の lexicon/studio = (b) は Open Question #1/#3 の決定待ち）。

実装（`orgs/gftdcojp/cloud-murakumo/`、schema/scheduler/runtime は無改造で再利用）:

- `resources/murakumo.edn`: `:generation` app（image/model3d/music/sfx/voice/render の 6
  `:gen` 関数）+ `comfy/trellis/audio/tts` image を追加。
- `src/cloud_murakumo/gen.cljc`: engine アダプタ（`:comfy/:trellis/:audio/:tts/:kami-render`）
  の data-first ワーカ起動コマンド生成、model 解決（提供外は silent fallback せず例外）、
  `:gen.job` 正規化、`models-catalog`（/v1/models 射影。modality→複数 fn 可）、`out-kinds`
  （modality 既定出力）。
- `src/cloud_murakumo/ledger.cljc`: 実行台帳/課金。`open-run`/`close-run` で
  `:murakumo.run/gpu-seconds` と成果物 CID・コストを確定、`billing` でアカウント別 rollup、
  `quota-exceeded?` で fail-closed クォータ、`fleet-hourly-by-app` で app 別 $/h。
- `src/cloud_murakumo/gateway.cljc`: OpenAI 互換 routing（serve はモデル→endpoint round-robin）、
  `gen-dispatch`（gen は modality→placement）。
- CLI（`cloud-murakumo.cli`）: `models` / `gen <modality> [model]` / `bill` を追加。

検証（babashka v1.12.218 / clojure 1.12 / shadow-cljs 2.28.20）:

- 単体テスト **19 tests / 62 assertions、0 失敗**（`scheduler-test` + `gen-test` +
  `ledger-test`）。gen 関数の存在・model 解決・engine 別ワーカコマンド・scale-to-zero・
  既存 auction での gen 配置（無改造）・run gpu-seconds/CID/cost・billing rollup・
  quota fail-closed・serve/gen routing・app 別課金内訳。
- `clj -M:cli gen image sdxl-turbo` → `node sora (l4 x1)`、`worker.comfy` 起動コマンド、
  artifact `bafy….png`、`gpu-seconds=60 cost=$0.0133`（財務 gate）。
- `clj -M:cli gen 3d` → `node ai (a100-80 x1)` TRELLIS、`out gltf,glb`、`cost=$0.0567`。
- `clj -M:cli bill minimax-m27=80 image=12 voice=40 model3d=2` →
  `llm-serving $36.48/h` + `generation $20.20/h`。
- web: `studio.gftd.ai` の前身として `murakumo.cloud` の console に Studio タブ（modality 別
  モデルカタログ + app 別課金）を追加。`npm run release`（advanced）**127 files, 0 warnings**。
  生成系 `.cljc`（gen/ledger/gateway）が browser バンドルに同梱され、サイト表示 = CLI =
  本番計算が同一 `.cljc` で一致。

## Verification — product layer (b) scaffolded, 2026-06-28

Open Question #4 の (b)「製品層」を新規リポ **`gftdcojp/ai-gftd-router`** として
スキャフォルド・検証した（Open Question #1/#3 の決定を反映）。上流 `cloud-murakumo` を
`deps.edn` の `:local/root` で取り込み、`cloud-murakumo.gen`（modality→fn 射影・model 解決・
ジョブ正規化）を**再利用**する（itonami → langgraph-clj と同型）。

実装（`orgs/gftdcojp/ai-gftd-router/`）:

- `resources/router.edn`: SSoT。API 面（`:routes` = method+path→op）/ テナント
  （gftd.ai 単独、`gpu-seconds/day` クォータ）/ 上流 murakumo 束縛（chat=`:llm-serving`,
  gen=`:generation`）/ lexicon `ai.gftd.gen`。
- `src/ai_gftd_router/route.cljc`: 純ルーティング。`models`（/v1/models = gen カタログ +
  chat serve）/ `chat-upstream`（OpenAI 互換 proxy 先解決、提供外は例外）/ `gen-route`
  （modality→`:gen` fn→`:gen.job`）/ `dispatch`（router.edn の route 表で op 解決。`:id`/`:cid`
  path 変数照合）。
- `src/ai_gftd_router/job.cljc`: `:gen.job` ライフサイクル（queued→running→done|failed、
  純遷移）。`submit`（id 付与 + quota チェック）、`over-quota?`（fail-closed）、`done`
  （artifacts CID + gpu-seconds を `:murakumo.run` に束ね課金へ）、`->datoms`。
- `src/ai_gftd_router/lexicon.cljc` + `lexicons/ai/gftd/gen/*.json`: AT-proto NSID lexicon
  `ai.gftd.gen.*`（createJob/getJob/listJobs/listModels + job record）。`validate-create` で
  createJob 入力検証（modality/model、silent fallback しない）。
- `src/ai_gftd_router/cli.clj`: `models` / `route` / `doctor`。

検証（babashka / clojure 1.12、上流を classpath に同梱）:

- `route_test`: **7 tests / 27 assertions、0 失敗**（models 射影 / gen・chat dispatch /
  chat default / path 変数照合 / 未知ルート例外 / lexicon 検証 / quota fail-closed /
  done 時の `:murakumo.run` 束ね）。
- `doctor`: `{:routes 6, :tenant :gftd.ai, :quota-gpu-sec 86400, :upstream-fns 10,
  :gen-modalities [:3d :image :music :sfx :voice], :lexicon "ai.gftd.gen", :ready? true}`。
- `route POST /v1/gen 3d trellis …` → `:upstream/fn :model3d`, `:engine :trellis`,
  `:gen.job{:model "trellis" :status :queued}`。

未了（本 ADR の Decision 範囲外 / 次の着手）: HTTP server 境界（kotoba `on-http` / ring。
`route.cljc` は純データまで）、`studio.gftd.ai`（CLJS/re-frame SPA）、実 GPU/kotoba mesh への
transact、モデル pin のライセンス確定（Open Question #2）。

## Implementation progress（2026-06-28, post-close）

ADR close 後の実装着手 2 件。

**(1) エンジンを everything-clj 化**（per-engine Python module → 単一 clj worker）:

- `cloud-murakumo.engine`（cljc, 純粋）: `:gen.job` → GPU backend 呼び出し記述。
  `comfy-graph` は ComfyUI の txt2img ノードグラフを **clj データ**として構築
  （CheckpointLoader→CLIPTextEncode→KSampler→VAEDecode→SaveImage、params 既定込み）。
  `invocation` が engine 別（`:comfy`=HTTP /prompt, `:trellis`=proc+image ref 必須,
  `:audio`=proc（sfx 5s/music 30s 既定）, `:tts`=proc+voice-ref, `:kami-render`=HTTP /render）
  に `{:via :http|:proc :request :out}` を返す。GPU カーネルは各 runtime、グラフ/会計は clj。
- `cloud-murakumo.worker`（clj）: `run-job` は `execute`/`cid-of`/`run-id` を**注入**（itonami
  の DI 流儀）し、artifacts CID + `:murakumo.run/gpu-seconds` を確定。`-main` は
  `--engine` で dispatch（`gen/worker-args` は `clojure -M:worker -m cloud-murakumo.worker
  --engine …` を生成）。`:worker` alias 追加。
- 検証: `engine-test` + `gen-test` で **9 tests / 44 assertions、0 失敗**（comfy graph データ /
  engine 別 invocation / trellis の image ref 必須例外 / sfx・music 既定秒 / worker run-job の
  execute 注入による artifacts・gpu-seconds 確定 / parse-args）。worker `-main` は GPU 無しで
  backend 解決まで動作確認。

**(2) west manifest 登録**:

- `ai-gftd-router` を独立 git repo 化（`git init` + remote `gftdcojp/ai-gftd-router` +
  初期コミット `2bfe850`）し、`manifest/repos.edn` の `:extra-projects` に追加。
- `bb scripts/gen-west-manifest.bb` で `manifest/west.yml` 再生成（手書き禁止に準拠）。
  `ai-gftd-router`（remote gftdcojp / clone-depth 1 / groups [gftdcojp] / 自 HEAD pin）が追加。
  同時に他 5 project の pin が working HEAD へ前進（manifest 設計どおりの自動追従）。
  `--check` クリーン。

## Closure

2026-06-28 closing。3 層のうち基盤層(a: murakumo `:gen` + engine アダプタ + ledger/gateway)と
製品層の純データ部(b: `ai-gftd-router` の route/lexicon/cli)を実装・検証した。

- Open Question #1 → 製品リポは `gftdcojp/ai-gftd-router`（subdomain `studio.gftd.ai`）に確定。
- engine アダプタは everything-clj 化（per-engine Python module 廃止、単一 `cloud-murakumo.worker`
  が `--engine` で dispatch）。
- 検証: cloud-murakumo 側 **19 tests / 62 assertions**、ai-gftd-router 側 **7 tests / 27 assertions**、
  いずれも 0 失敗。`murakumo.cloud` の Studio タブに生成カタログ + 課金を表示(advanced build 0 warning)。
- 残る Open Question #2(モデル pin ライセンス)/ #3(課金テナント)と、HTTP server 境界・
  studio SPA・実ワーカ・実 transact・west 登録は運用フォローアップとして本 ADR の外。
