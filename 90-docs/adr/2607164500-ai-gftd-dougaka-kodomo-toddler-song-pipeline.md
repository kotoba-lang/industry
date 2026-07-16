---
id: adr-2607164500-ai-gftd-dougaka-kodomo-toddler-song-pipeline
title: "ADR-2607164500: ai-gftd-dougaka-kodomo — 幼児向けオリジナルアニメ・知育ソング動画生成パイプライン（yukkuri ベース）"
status: accepted
doc_type: adr
topic: ai-gftd-dougaka-kodomo-toddler-song-pipeline
authoritative: true
last_verified: 2026-07-16
authoritative_for:
  - ai-gftd-dougaka-kodomo（幼児向け知育ソング動画）のパイプライン設計（song spec / 歌唱合成 / 拍同期ループアニメ / kids-safety hard gate / madeForKids 公開）
  - yukkuri アーキテクチャからの継承範囲と置換範囲の切り分け
  - kids-safety gate の決定論チェック方針（LLM 判定を gate に置かない）
related:
  - 90-docs/adr/2607170500-cloud-murakumo-seedance-video-api-integration.md
  - 90-docs/adr/2607155500-cloud-murakumo-self-hosted-video-character-consistency.md
  - 90-docs/adr/2607162200-aozora-creator-scheduled-publishing-integration.md
  - 90-docs/adr/2607131645-murakumo-direct-voice-api.md
supersedes: []
superseded_by: []
---

# ADR-2607164500: ai-gftd-dougaka-kodomo — 幼児向けオリジナルアニメ・知育ソング動画生成パイプライン

**Status**: accepted（設計 + scaffold。実装 Phase は Follow-ups）
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki（指示:「今の ai-gftd-yukkuri をベースに ai-gftd-dougaka-kodomo として、オリジナル幼児向けアニメ・知育ソング（Cocomelon、ChuChu TV、Pinkfong、Infobells、El Reino Infantil のような動画）の生成パイプラインを設計して」）

## Context

**ジャンルの要件（Cocomelon / ChuChu TV / Pinkfong / Infobells / El Reino Infantil の共通項）**:

1. 2〜4 分の**歌もの**（オリジナル曲 + パブリックドメイン童謡の編曲）。反復・
   call-and-response・ゆっくりした BPM が学習装置。
2. **固定キャラクターファミリー**が全動画に登場する — character consistency が
   このジャンルの生命線（視聴者は 0〜4 歳で「いつもの子」を認識して再生する）。
3. **多言語横展開**（ChuChu TV = 英/ヒンディー、El Reino Infantil = 西語圏最大）。
   同じ動画資産の言語別チャンネル展開がスケールの主レバー。
4. **コンピレーション**（30〜60 分連結）が視聴時間の主力。
5. **規制面が特殊**: COPPA / YouTube「子ども向け」フラグ（madeForKids=true、
   パーソナライズド広告不可・コメント無効）、光過敏対策、年齢適合語彙、
   既存幼児 IP への類似回避。

**ベースにする ai-gftd-yukkuri の棚卸し（本セッション、コード直読で確認）**:

- yukkuri は既に**マルチチャンネル**（cyber/anime/truecrime/pachinko の 4 編成を
  1 コードベースで運転）。パイプラインは `clj/src/yukkuri/graphs/produce.cljc` の
  線形 stage orchestrator: compose → generate_script → [voice ∥ visual ∥
  character ∥ bgm]（並列）→ compose_scene → render → score → review(gate) →
  translate → upload_youtube → audit。
- **pure-planner + `:exec` 分離**: base `.cljc` は外部 IO を一切せず
  `{:system :user :model-hint}` request spec と `ffmpeg_plan` 等の実行計画だけを
  返す。実 IO クライアント（VOICEVOX / ComfyUI / YouTube）は `:exec` alias。
- **render は「dougaka = ffmpeg assembler」**（ADR-2605312355）: 合成済み
  per-scene フレーム + per-line 音声を ffmpeg で連結・mux するだけ。実働は
  `renderer-mac/`（Mac mini fleet、h264_videotoolbox、9 台で実測 ~65x realtime、
  限界費用 $0）。Cloudflare Tunnel 経由・request/response contract は dougaka 互換。
- 声は VOICEVOX（四国めたん/ずんだもん、話し声）+ Kokoro fallback。公開
  description への `VOICEVOX:<話者>` クレジットは商用必須の不変条件。
- 多言語は translate_video（SRT + per-line dub）/ produce_all_languages が既存。
- 持続化は Cloudflare D1 SSoT + B2 blob（ADR-2606041900 の pod→Worker→D1 経路）。
- **yukkuri 固有で置換が必要なもの**: (a) L/R 掛け合い解説フォーマット
  （generate_script の explainer/questioner スキーマ、compose_scene の
  二隅顔 + 紙芝居レイアウト）、(b) VOICEVOX **話し声**前提（歌唱ではない）、
  (c) BGM は ongakuka カタログ選定のみで**作曲 stage が存在しない**、
  (d) QA が advisory（幼児向けは hard gate が要る）。

**依存する直近の設計**:

- 動画生成: ADR-2607170500（cloud-murakumo `:seedance` engine、fal.ai queue API、
  reference-to-video で学習なし character-consistency の可能性。実 API key 検証待ち）
  / ADR-2607155500（自前 AnimateDiff + character LoRA。Phase 0 ベンチがゲート）。
  **どちらも今日時点で E2E 未検証** — 本パイプラインの主経路をこれらに賭けない。
- 音声: ADR-2607131645 の公開 voice API（`api.murakumo.cloud/v1/audio/speech`、
  VOICEVOX 0.25.2 常駐）は**話し声のみ**。VOICEVOX ENGINE 0.25 系はソング合成
  （`/sing_frame_audio_query`+`/frame_synthesis`、歌唱可能スタイル）を持つが
  API 未露出。
- 定期投稿: ADR-2607162200 の 4 層分離（cadence tick / production run /
  generation / publish gate、governor auto-publish + escalate-on-flag）。

## Decision

新規 child repo **`orgs/gftdcojp/ai-gftd-dougaka-kodomo`**（private、gftdcojp 既定）
を起こし、yukkuri の produce 骨格を継承しつつ以下を置換・追加する。
scaffold（本 ADR と同時に push 済み）は pure-planner core のみを含む:
`kodomo.song`（song spec 検証: 音域 C4〜D5 / 反復率 ≥0.3 / chorus 必須 /
BPM 70〜120）、`kodomo.safety`（kids-safety gate）、`kodomo.pipeline`
（stage-order + advance reducer）、`resources/characters.edn`（オリジナル
キャスト: メロ/ポポ/ミミ/ガオ）、`resources/curriculum.edn`（ABC/かず/いろ/
どうぶつ/生活習慣 の 8 topic 初期カタログ）。テストは nbb 第一経路
（runtime 優先順位規則に準拠、17 tests green で push 済み）。

### 1. パイプライン（yukkuri produce と同型の線形 stage + 並列 asset 帯）

```
compose → generate-song → [synthesize-vocals ∥ generate-visual ∥
generate-character ∥ arrange-music] → compose-scene → render-video →
score-safety (HARD gate) → localize → publish → audit
```

yukkuri との stage 対応と置換点:

| stage | 内容 | 実行系（`:exec` 側） |
|---|---|---|
| compose | curriculum topic 選定（priorityScore 方式は yukkuri topics.cljc 踏襲） | D1 |
| generate-song | **script でなく song spec**: 歌詞 + メロディ EDN（note events）+ storyboard + motion cue。LLM は spec 生成のみ、決定論検証（kodomo.song/validate）で弾く | murakumo text |
| synthesize-vocals | **歌唱合成**。第一候補 = VOICEVOX ソング API（下記 2） | murakumo voice |
| generate-visual | 背景・小物。SDXL/flux + **character LoRA**（ADR-2607155500 Phase 1） | ComfyUI (gad) |
| generate-character | キャラ表情/ポーズシート（reference sheets 準拠、LoRA 適用） | ComfyUI |
| arrange-music | **作曲/編曲 stage（yukkuri に無い新設）**: メロディ EDN → 伴奏。ongaku compose 選定 + murakumo music 生成のハイブリッド | ongakuka / murakumo music |
| compose-scene | **紙芝居でなく拍同期ループアニメ plan**: cutout layer（BG / キャラ / 小物）+ BPM 同期 motion cue（bounce/clap/hop/wheel-spin）を ffmpeg_plan の filter graph（loop + overlay + 拍タイミング）に落とす。口パクは音素タイミング（VOICEVOX が返す）→ 口形状スプライト切替 | 純データ（planner） |
| render-video | **renderer-mac fleet を無改造で再利用**（dougaka contract 互換のまま） | Mac mini fleet |
| score-safety | **kids-safety HARD gate**（下記 3）。yukkuri の advisory score と違い fail = :held で停止 | ffmpeg 計測 + gate |
| localize | translate_video / produce_all_languages 踏襲。歌詞は音節数維持の訳詞 → 言語別歌唱再合成。**言語別チャンネル**（El Reino Infantil 型） | murakumo text/voice |
| publish | YouTube Data API v3、**madeForKids=true 固定**。cadence は ADR-2607162200 の 4 層分離（Cloudflare cron tick → actor outer loop）を流用 | YouTube API |
| audit | 生成イベント台帳 append（yukkuri YkGeneration 同型） | D1 |

視覚表現の段階戦略（**generative video に主経路を賭けない**）:

- **主経路 = 決定論 cutout ループアニメ**。合成済みフレーム + ffmpeg filter
  graph（renderer-mac）で完結し、今日動く。幼児ソングは同じ動きの反復が
  むしろ正で、ループアニメとの相性が良い。キャラ同一性はスプライト再利用で
  構造的に 100%。
- **強化レーン（optional）= seedance reference-to-video**（ADR-2607170500）を
  ヒーローショット（サビの 1 カット等）にのみ使う。API key 投入 + E2E 検証 +
  character-consistency 視覚チェック合格後に昇格。生成カットも score-safety を
  通らなければ捨てる。
- 3D 化する場合は repo-wide 規則どおり kami-engine stack のみ（Three.js 等の
  第 2 エンジン禁止）。初期スコープは 2D cutout で 3D を要求しない。

### 2. 歌唱合成 — VOICEVOX ソング経路を murakumo voice API に増設

- `local-murakumo` の公開 voice contract（ADR-2607131645、
  `POST /v1/audio/speech`）に **`POST /v1/audio/song`** を増設する:
  request = `{:score {:notes [{:pitch :C4 :beats 0.5 :lyric "ら"} ...]} :speaker n :bpm n}`、
  Worker が VOICEVOX の `/sing_frame_audio_query` → `/frame_synthesis` を
  シーケンスして `audio/wav` を返す（speech と同じ provenance headers）。
  常駐 engine は既存 0.25.2 のままで足りる（ソング API は同 engine に同梱）。
- 話しパート（数かぞえの掛け声、call-and-response の呼びかけ）は既存
  `/v1/audio/speech` をそのまま使う。
- VOICEVOX クレジット必須の不変条件は yukkuri と同一（safety gate の
  metadata チェックが機械強制する）。
- fallback: ソング API 増設が遅れる間は「speech + pitch-shift はしない」
  （品質が幼児向け水準に達しない）— 歌唱 stage を人間収録 or 保留にし、
  インスト + 話しパートのみの動画型（Infobells に近い型）から開始してよい。

### 3. kids-safety HARD gate（このパイプライン最大の新規要素）

- **gate は決定論チェックのみ**（scaffold の `kodomo.safety`）。LLM 判定を
  gate に置かない — design-quality の実測（3-judge LLM panel が具体的欠陥を
  1 つも指摘できなかった）を踏まえ、「計測されないメトリクス＝劇場」を回避:
  - loudness: integrated -20〜-12 LUFS / true peak ≤ -1.0 dBTP（ffmpeg loudnorm 計測）
  - 尺: 単発 60〜300s、コンピレーション 900〜3600s
  - 光過敏: フラッシュ ≤ 3 回/秒（フレーム間 luma delta 計測)
  - 語彙: curriculum lexicon 外の未知語率 ≤ 0.2
  - metadata: madeForKids=true 必須 / description 外部リンク禁止 / VOICEVOX クレジット
  - IP 類似: flags 非空なら fail（判定材料は画像 embedding / 人手レビューで
    :exec 側が付す）
- 運用は ADR-2607162200 の governor パターン: 全 green → auto-publish
  （2026-07-10 恒久承認の範囲）、1 つでも flag → **hold + owner escalate**
  （幼児向けの公開失敗は回復コストが非対称に高いので escalate 側に倒す）。

### 4. コンテンツ・IP 方針

- キャラクターは**オリジナルのみ**（characters.edn の 4 キャスト。既存幼児 IP
  への類似は gate 対象）。reference sheets（15〜20 枚/キャラ）を DataLad/B2 に
  置き（ADR-2607155500 と同じ扱い）、LoRA 学習（同 Phase 1）と seedance
  reference 入力（ADR-2607170500）の両方の入力にする。
- メロディは (a) オリジナル、(b) PD 童謡（Wheels on the Bus / ABC song /
  Old MacDonald 等）の**オリジナル編曲**のみ。商用幼児チャンネルの楽曲・
  映像の模倣は禁止。

### 5. 継承するインフラ（変更なし）

- D1 SSoT + pod→Worker→D1 書込 + B2 blob（ADR-2606041900 パターン、新規 DB）。
- renderer-mac fleet（dougaka contract）。
- NSID は `ai.gftd.apps.kodomo.*`、appview は yukkuri SvelteKit 型を後続 Phase で。
- BMC/Lean は **standalone パターン**（repo-local `docs/bmc-lean-loop-log.md` +
  本 ADR）。共有 base datoms への登録は人間レビュー事項なので routine では
  行わない（CLAUDE.md の BMC 節に準拠。`gftd products` 11 本への追加は
  オーナー判断の follow-up）。

## Consequences

- (+) 今日動く経路（ComfyUI 画像 + renderer-mac ffmpeg + VOICEVOX）だけで
  最初の動画まで到達可能。未検証の generative video（seedance / AnimateDiff）は
  強化レーンに隔離され、ブロッカーにならない。
- (+) yukkuri のマルチチャンネル資産（topics / translate / upload / analytics /
  ads / kaizen loop）をチャンネルパラメタライズのまま流用できる。
- (+) kids-safety gate が決定論なので、cron 無人運転（恒久承認範囲の
  auto-publish）に載せても「LLM が見逃す」型の事故クラスを構造的に持たない。
- (−) 歌唱合成の品質が最大の不確実性。VOICEVOX ソングの幼児ソング適性は
  未検証（Phase A で 1 曲 E2E 検証がゲート）。不合格なら Infobells 型
  （インスト + 話し声）へのスコープダウンか商用歌唱合成の検討が要る。
- (−) 拍同期ループアニメの ffmpeg filter graph は compose_scene の実装量が
  大きい（yukkuri の静的紙芝居より複雑）。ただし renderer-mac の contract は
  不変なので fleet 側の変更はない。
- (−) yukkuri の generate_script / compose_scene は流用できず新規実装
  （調査で特定済みの最大の書き換え点）。

## Follow-ups（実装 Phase）

- **Phase A（E2E 1 本、手動）**: curriculum 1 topic → song spec → VOICEVOX
  ソング API（local 直叩きで可）→ 静的シーン + ループ 1 種 → renderer-mac →
  safety gate → 限定公開 upload。歌唱品質の go/no-go をここで判定。
- **Phase B**: compose-scene の拍同期 motion cue 一式、arrange-music 配線、
  D1 schema + appview、`/v1/audio/song` の murakumo 公開 API 化。
- **Phase C**: cadence 定期投稿（ADR-2607162200 Phase B 相当）、localize
  横展開、コンピレーション、character LoRA / seedance レーンの昇格判定。
- reference sheets 作成（4 キャスト × 15〜20 枚）→ DataLad/B2 登録。
- BMC standalone log（`docs/bmc-lean-loop-log.md`）の Iteration 1 起票。

## Alternatives Considered

1. **yukkuri リポジトリ内に 5 番目のチャンネルとして追加**（新 repo を作らない）
   — content/channels.edn に kodomo channel を足すだけで多くが動く。しかし
   L/R 掛け合いスキーマと紙芝居レイアウトの置換が yukkuri 本体の破壊的変更に
   なり、稼働中 4 チャンネルの回帰リスクを取る。歌唱・safety gate・COPPA は
   ジャンル固有性が高く、別 repo + 共有ライブラリ参照の方が境界が正しい。不採用。
2. **generative video（seedance）を主経路にする** — Cocomelon 的なリッチさへの
   最短距離だが、E2E 未検証・コスト（720p 参照ありで $0.18/秒 ≒ 3 分動画 $32+）・
   カット間一貫性未確認・幼児向け safety の予測不能性で、無人 cron 運転の主経路
   には不適。強化レーンに格下げして採用。
3. **商用歌唱合成 SaaS（Suno 等）を最初から使う** — 品質は高いが、生成楽曲の
   商用利用条件・学習データ出自の法的グレーさが幼児向け（最も保守的であるべき
   ジャンル）と相性最悪。自前 VOICEVOX ソング経路を先に検証する。Phase A
   不合格時の再検討対象としてのみ温存。
