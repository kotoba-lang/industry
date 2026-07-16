# ADR-2607164200: mangaka 多候補品質ループ + HF モデルレジストリ + character-LoRA 学習ハーネス（murakumo 接続）

- Status: accepted, implemented (mangaka 側)。fleet 側学習実行は ADR-2607155500 Phase 0 gate 待ち
- Date: 2026-07-16
- Deciders: owner (root@junkawasaki.com) 指示「設計実装、loop 実現. murakumo に接続, また huggingface の model を利用しつつ、自前の model も設計、学習するように」/ agent 実装
- Related: ADR-2607155500 (self-hosted video + character-consistency LoRA、Proposed), ADR-2607170500 (Seedance reference-to-video、cheaper-first 代替), ADR-2606272330 (gftd.ai 生成スタジオ), ADR-2607071100 (mangaka retirement wave3), RUNBOOK.md §3 (co-scientist loop 再導入義務)

## Context

app-aozora / manga.gftd.ai で公開中の 4 作品（ghosthacker / halfgram / zankyo /
yamainu）の生成系は、`ai-gftd-mangaka/clj`（JVM langgraph-clj）に移植済みだが:

1. **品質ループが単一候補**: `mangakaGeneratePanelLoop`（plan→render→score→revise、
   直近 main 着地）は 1 attempt = 1 render。Python 時代に設計された
   `composeScene3d` topology（`lg/scripts/demo_outputs/`）が持っていた
   **候補 fan-out + 8軸ルーブリック vision critique + DMN refine gate** は未移植
   （`langgraph.edn :backlog :three-d`）。
2. **murakumo 接続は render のみ**: `mangaka.comfy` は `MURAKUMO_COMFY_URL`
   （cloud-murakumo.engine/executor、gad の native ComfyUI）と `COMFYUI_URL`
   （comfy-openai-bridge gad:8189、2026-07-10 実証済み）の 2 経路を持つが、
   モデル選択は checkpoint ファイル名の素通しで、HF 由来モデルの正本
   （murakumo `infer.edn` の `:hf/repo` 規約）と繋がっていない。
3. **自前モデルはゼロ**: character-consistency LoRA は ADR-2607155500 Phase 1 に
   設計だけ存在（`:comfy-train` `:via :proc`、shiro 17 / pico 18 枚 @1024²、
   重み → DataLad+B2 CID）。`mangakaTrainCharacterLora` は backlog 名のみ。
   コマ間キャラ同一性は高品質漫画生成の必須要件。

langgraph-clj には Send 型 fan-out primitive が無い（Pregel は逐次・単一スレッド
前提）ため、候補 fan-out は「1 ノード内で N 候補を map」で表現する。

## Decision

**ai-gftd-mangaka/clj に以下を実装する**（NSID は registry + langgraph.edn の
lock-step 規約に従う）:

1. **`mangaka.models`** — モデルレジストリ（portable .cljc、純 EDN データ）。
   murakumo `infer.edn` の `:hf/repo` 規約をミラーした最小サブセット
   （`animagine-xl-4.0` = HF `cagliostrolab/animagine-xl-4.0`、fleet 常駐）+
   **自前 character-LoRA スロット**（`lora-ghosthacker-shiro` / `-pico`、
   `:model/status :planned→:queued→:trained`、`:model/lora-cid` が
   Higgsfield character-id 相当）。
2. **`ai.gftd.mangaka.mangakaGeneratePanelQuality`**
   (`graphs/generate_panel_quality.cljc`) — 多候補品質ループ:
   `plan → render-candidates（1ノード内で N 候補、seed 展開）→ critique
   （候補ごとに vision critique、composeScene3d の 8 軸ルーブリック:
   composition / silhouette / characterRecognizability / framing /
   mangaShotGrammar / lightingDrama / actionClarity / emotionAlignment、
   0–1 → 集約 0–100）→ conditional router（DMN gate の CLJ 表現:
   threshold 到達 or round budget 消尽 → finish、それ以外 → refine が
   fix-hint を畳み込み seed を進めて render-candidates へ back-edge）`。
   全 round・全候補を `:rounds` に監査証跡として保持し、最良候補を
   `:panel/score` + `:panel/critique`（axes 込み pr-str EDN）で永続化。
   critique fn は注入可能（offline は nil-score = no signal、クラッシュ禁止）。
   `:character` 入力があり trained LoRA が registry に居れば spec に
   `:lora`（id + cid）を通す（render 経路への forwards-compatible passthrough）。
3. **`ai.gftd.mangaka.trainCharacterLora`**
   (`graphs/train_character_lora.cljc`) — 学習ハーネス（mangaka 側）:
   `plan（registry のキャラ LoRA スロット解決 + dataset manifest — shiropico
   character-refs を JVM で実在確認・画像数カウント）→ dispatch（cloud-murakumo
   `:gen.job` 形の envelope `{:modality :training :engine :comfy-train
   :model <base>}` を構築し、`MURAKUMO_DISPATCH_URL` 設定時は
   `/infer/dispatch` へ POST。未設定/非 2xx は `:queued-local` に degrade、
   never throw）→ register（`:model/*` datoms として store へ永続化）`。
   domain schema に `:model/*` 属性群、store に `save-model!`/`load-model` を追加。
4. **langgraph.edn**: 両 NSID を `:graphs` へ、`:training` backlog を空に。

**cloud-murakumo に fleet 側学習ランナーの骨格を追加する**
（`services/lora-train/`、ADR-2607155500 Phase 1 の `:comfy-train` `:via :proc`
実装骨格。diffusers+peft の SDXL LoRA trainer、ROCm/gad 前提、
comfy-openai-bridge と同じ standalone Python service 前例に従う）。
**実 GPU 学習の実行は本 ADR のスコープ外** — ADR-2607155500 Phase 0
（gad ROCm 実測 benchmark）が go/no-go gate のまま。組織の学習実績は
RunPod/Modal（`ai-gftd-weight-oka` 前例）であり、Phase 0 が fail なら
そちらへ fallback する。

**学習済み重みは git 履歴に入れない**: DataLad + git-annex + B2
（skill `large-binary-datalad`）。LoRA CID を `:model/lora-cid` として registry /
datoms に記録し、生成時は `:character-lora-cid` として渡す（ADR-2607155500 準拠）。

## Consequences

- (+) RUNBOOK §3 の「co-scientist loop を CLJ graph として再実装せよ」が
  多候補・多軸ルーブリック版で満たされる。visionScorePanel のスコアが
  初めて再生成に自動フィードバックされる（劇場メトリクス → fitness function 化）。
- (+) モデル選択が HF 正本（`:hf/repo`）と自前モデルのライフサイクル
  （planned/queued/trained + CID）を一元管理する registry 経由になる。
- (+) offline 完全動作（stub render + nil critique + queued-local dispatch）を
  維持 — GPU/API キー無しで全テストが走る repo 規約を破らない。
- (−) fleet 側 `:comfy-train` engine 本配線と実学習は未実行（Phase 0 gate）。
  halfgram/zankyo/yamainu の参照画像セットも未整備（R2 公開パネルからの
  組み立てが先行タスク）。
- (−) langgraph-clj に Send が無いため候補 fan-out はノード内逐次実行
  （wall-clock は候補数に線形）。並列化は langgraph-clj 側の将来課題。

## Alternatives Considered

- **Seedance reference-to-video だけで済ませる**（ADR-2607170500）: 一貫性が
  要件を満たせば安いが、fal.ai キー未 provision かつ静止画パネル用途には
  LoRA の方が直接的。両睨み（cheaper-first + fallback）の関係は維持。
- **composeScene3d を丸ごと移植**: 3D pose/simulate 系ノードは kami-engine
  統合が別スコープ（`:backlog :three-d` に残す）。本 ADR はルーブリック +
  refine gate という「品質ループの核」だけをパネル生成に先行適用する。
- **単一候補ループの threshold 調整のみ**: fan-out 無しでは
  「最良候補の選択」ができず、seed ガチャの分散を捨てることになる。

## Addendum 1 (2026-07-16) — co-scientist 生成ループ実装 / 学習は計画のみに凍結

オーナー指示「学習は計画だけで OK、優先度はあとで。さきに高品質生成 agent
loop のコード実装、co-scientist」を受けて:

1. **`ai.gftd.mangaka.mangakaCoscientistPanel`**
   (`clj/src/mangaka/graphs/coscientist_panel.cljc`) を実装。
   Generate→Reflect→Rank(Elo)→Evolve→Meta の完全な co-scientist ループ
   （パターン原典 ADR-2606141500、直系前例 isekai ADR-0007 /
   `90-docs/design-quality/coscientist.cljc`）:
   - Generate: 仮説人口 = ショット文法方向性。LLM 提案（complete-json）→
     offline は決定論的 6 方向ライブラリに degrade。
   - Reflect: 8 軸ルーブリック critique（generatePanelQuality と同一 judge）。
   - Rank: round-robin Elo（K=32, base 1200）。**判定は測定スコアのみ —
     LLM 討論をランキングの根拠にしない**（design-quality の
     "ranking is reproducible, never an LLM debate" 原則を踏襲）。
   - Evolve: elite 生存（再レンダリングなし）+ 変異（fix-hint 畳み込み +
     seed 前進）+ 交叉（親 2 体の方向性フラグメント合成）。系譜は
     :parents/:op で記録。
   - Meta: 世代横断の最良を永続化、:generations 監査証跡 + iteration
     doc（md）を返す。
   テスト 4 件追加（budget 消尽 / 収束 / 進化オペレータ / rank 決定性）、
   suite 108 tests 687 assertions green。gad 実機 E2E（実レンダリング 2 枚、
   Elo 1216/1184）確認済み。
2. **学習（trainCharacterLora / cloud-murakumo lora-train）は「計画のみ」に
   凍結** — ハーネスと trainer 骨格は本 ADR 本文のまま存置するが、
   Phase 0 benchmark・実学習・murakumo.edn 配線は優先度を下げ、
   着手はオーナーの再指示を待つ。

## Addendum 2 (2026-07-16) — LLM 経路を api.murakumo.cloud (fleet-first) に切替

オーナー指示「llm 稼働は anthropic じゃなくて api.murakumo.cloud を使って」を受けて:

- `mangaka.llm` の provider 解決を **murakumo 優先**に変更:
  `MURAKUMO_LLM_URL`（本番 `https://api.murakumo.cloud`）→ fleet の OpenAI 互換
  `/v1/chat/completions`（langchain.model/openai-model、`MURAKUMO_LLM_MODEL`
  default "default"、`MURAKUMO_SERVICE_TOKEN` optional Bearer）。
  `ANTHROPIC_API_KEY` は fleet URL 未設定時の fallback に降格。両方無しは
  従来どおり deterministic mock（offline 完全動作は不変）。
- backend（llama.cpp / gemma4-26b-a4b-q4、b9334）の **vision 対応を実測確認**
  （image_url data-URI に正答、2026-07-16）— chat / critique / vision-json /
  vision-score の全経路が自前 fleet で動く。
- 完全自前ホスト E2E（レンダリング = gad ComfyUI、critique =
  api.murakumo.cloud）: gen1 実スコア [72, 63] + 有意味な批評文、gen2 は
  elite 生存 + fix-hint 変異子。この実測で見つかった
  「population 2 × elite 2 で子が繁殖されない」罠を elite ≤ population−1
  cap で修正。suite 110 tests / 696 assertions green。
