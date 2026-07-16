---
id: adr-2607121400-kami-ongaku-eizo-commercial-grade-cljs-stack
title: "ADR-2607121400: kotoba-lang 音楽制作(ongaku)・映像制作(eizo) commercial-grade engine stack — cljs canonical"
status: accepted
doc_type: adr
topic: kotoba-lang-repo-boundaries
authoritative: true
last_verified: 2026-07-12
authoritative_for:
  - kotoba-lang 音楽制作 / 映像制作系統の canonical 依存レイヤと責任境界
  - repo-wide 音楽・映像実装で kami-ongaku-* / kami-eizo-* stack を必須にする規則と完了条件
  - 音楽・映像 DSP/codec/data-model 実装の言語優先順位（.cljc / cljs canonical）
  - kotoba-lang（engine/code）と gftdcojp（business/actor）の境界の再確認
related:
  - 90-docs/adr/2607102200-kami-render-stack-deps-authority-rename.md
  - 90-docs/adr/2607010930-clj-wgsl-migration.md
  - 90-docs/adr/2607023000-ka-creative-craft-libs-isco-decomposition.md
  - 90-docs/adr/2607031510-kotoba-lang-composer.md
  - 90-docs/adr/2606272200-utsushi-video-edn-filtergraph.md
  - 90-docs/adr/2607082500-kotoba-lang-kasane-utsushi-reverse-domain-decomposition.md
  - 90-docs/adr/2606282100-mangaka-render-commons-vs-sip-work-split.md
  - 90-docs/adr/2606282101-mangaka-multilingual-text-layer-cljc-hiccup-reader.md
  - 90-docs/adr/2607051600-yukkuri-real-production-stack-live.md
  - 90-docs/adr/2607100030-kotoba-kami-engine-host-imports.md
supersedes: []
superseded_by: []
---

# ADR-2607121400: kotoba-lang 音楽制作(ongaku)・映像制作(eizo) commercial-grade engine stack — cljs canonical

- **Status**: accepted (2026-07-12)
- **Deciders**: Jun Kawasaki
- **Scope**: kotoba-lang 配下の音楽制作 (DAW-equivalent) / 映像制作 (NLE-equivalent) engine 系統

## Context

2026-07-12 の調査（本 ADR の前段）で、kotoba-lang の 3D スタック（`kami-engine`
系統、ADR-2607102200 で repo-wide mandatory とされ WebGPU/WebGL2/WASM parity・
E2E テストまで整備済み）に対し、音楽・映像制作系統は明確にカバレッジ・成熟度が
低いことを確認した。

**音楽（最弱）**:

| repo | 実体 | 成熟度 |
|---|---|---|
| `composer` | 作曲リクエスト/track/stem/style の**データ契約のみ**（README に "no network, no I/O, no model call" と明記） | 契約のみ |
| `ongaku` | BGM カタログ選定 + ライセンス gating（DOVA/incompetech、生成ではない） | カタログのみ |
| `audio`（KAMI Audio） | mixer/voice-alloc・バイノーラル空間化・PCM16 WAV encode | DSP プリミティブのみ |
| `jp-hiroshiba-voicevox` | VOICEVOX TTS クライアント（作曲ではない。実運用済み） | 実運用（TTS のみ） |
| `musicxml` | 名前は記譜フォーマットだが実体は無関係（clj-wgsl migration scaffold に転用済み） | 名前が空き地化 |
| `ongakuka`（本体、ADR-2607023000, gftdcojp） | 150 LOC、server なしの compose/policy/catalog 純 lib。モデル呼び出しなし | 最小限 |

記譜/スコアエンジン・MIDI シーケンサー・ミキシングコンソール・プラグインホスト
（VST/AU 相当）・サンプラーは kotoba-lang 内に**一つも存在しない**。

**映像（低〜中）**:

| repo | 実体 | 成熟度 |
|---|---|---|
| `utsushi` | EDN container grammar + `defgraph` filtergraph + capability-gated codec（ADR-2606272200・ADR-2607082500 で 15 reverse-domain repo + ISOBMFF 統合まで拡張済み、H.264 は実 SPS/width/height パース） | 骨格は実証済みだが pixel decode（DCT/motion comp）は未配線 |
| `douga` | timeline→ffmpeg render-plan + command-vector builder（`ai-gftd-dougaka` から分離、400 LOC、I/O なし設計） | データ変換のみ |
| `anime` | アニメ制作ボキャブラリ（cut 階層/12 工程/レイヤー仕様、`ai-gftd-animeka` から分離） | スキーマのみ |
| `kami-mangaka-*` 系 | 漫画/コミック生成・閲覧パイプライン（ADR-2606282100/2606282101、**accepted・tests green**） | 非3D創作系で最も成熟 |
| yukkuri real-production stack（ADR-2607051600） | murakumo fleet の ComfyUI + VOICEVOX + douga/ffmpeg で実動画を実生成、**Accepted・実運用済み** | スライドショー/解説動画アセンブラとしては実証済み |

タイムライン/EDL データモデル・カラーグレーディング・H.264 以外の実トランス
コード層・ffmpeg コマンド生成を超えたコンポジティングは存在しない。

**組織境界の再確認（ADR-2607023000）**: creative -ka（mangaka/animeka/
ongakuka/syosetsuka/dougaka）は「コード = kotoba-lang、職能 = cloud-itonami-
isco、商売 = gftdcojp、配信 = manimani」に分解済み。`composer`/`ongaku` が
意図的に "no model call" なのはこの境界の結果であり欠陥ではない。**本 ADR は
この境界を変更しない** — kotoba-lang が持つべきは生成モデル呼び出しではなく
**エンジン層**（3D における `kami-engine` と同じ役割: DSP・codec・data-model・
timeline・notation・mixing の実装）である。

## Decision

### 1. Canonical 依存レイヤ（音楽: `ongaku` ドメイン）

```
L5 apps          (future) kami-app-daw
                   │
L4 hosts         wasm-webcomponent (browser AudioWorklet host, kotoba emit)
                 offline PCM host (kotoba wasm / .cljc, non-realtime bounce)
                   │
L3 authoring     kami-ongaku-project    … DAW セッション: track/bus/clip/automation/plugin-chain SSoT
                 kami-ongaku-sequencer  … MIDI相当イベント/パターン/ピアノロール IR、quantize/groove
                 kami-ongaku-notation   … 記譜 IR（pitch/duration/dynamics/articulation）、MusicXML互換import/export contract
                 kami-ongaku-plugin-host … VST/AU相当プラグイン contract + node-graph host（`comfyui` node-registry contract 形に互換。GPL-3.0 のため実装コードは自前、§7 Wave 3 訂正参照）
                   │
L2 render(=DSP) executor
                 audio (KAMI Audio 拡張)  … oscillator/envelope/filter/effects(reverb/delay/EQ/compressor)/mixer bus graph/PCM16 render（primary DSP executor、3D の `webgpu` に相当）
                 kami-ongaku-sampler     … サンプル再生（velocity layer/round-robin/key-map/streaming）
                   │
L1 raw browser API
                 org-w3-webaudio         … 生 Web Audio API（AudioContext/AudioWorklet/AudioNode）境界。`org-w3-webgpu` と同型
                   │
L0 contracts     composer                … 作曲リクエスト/track/stem/style データ契約（既存、変更なし — 生成モデル呼び出しは gftdcojp 側）
                 ongaku                  … BGM カタログ + license gate（既存、変更なし）
```

### 2. Canonical 依存レイヤ（映像: `eizo` ドメイン）

```
L5 apps          (future) kami-app-nle
                   │
L4 hosts         wasm-webcomponent (browser WebCodecs/WebGPU host)
                 offline transcode host (kotoba wasm / .cljc, non-realtime render)
                   │
L3 authoring     kami-eizo-timeline      … EDL/タイムライン data model: track/clip/transition/effect-stack/marker（NLE の欠落部分の本体）
                 kami-eizo-grade         … カラーグレーディング: primary wheels/curves/LUT(.cube) apply、waveform/vectorscope data model
                 kami-eizo-compositor    … node-based VFX compositing graph（keying/roto/tracking data model、`comfyui` node-registry contract 形に互換。GPL-3.0 のため実装コードは自前、§7 Wave 3 訂正参照）
                 douga                   … timeline→ffmpeg render-plan（既存。入力 IR を `kami-eizo-timeline` に統一）
                 anime                   … アニメ制作ボキャブラリ（既存、`kami-eizo-timeline` へのアダプタとして位置づけ）
                   │
L2 render(=codec) executor
                 utsushi                 … EDN container/filtergraph + codec 実装（既存、H.264 decode 完成 + encode path 追加）
                   │
L1 raw browser API
                 org-w3-webcodecs        … 生 WebCodecs API（VideoEncoder/VideoDecoder/AudioEncoder/AudioDecoder）境界。`org-w3-webgpu` と同型
                 webgpu / webgl          … compositor の GPU 合成に既存 3D スタックをそのまま再利用（第2 renderer を作らない）
                   │
L0 contracts     kami-mangaka-*          … 漫画/コミック（既存、成熟済み、変更なし）
```

### 2.1 Repo-wide mandatory 音楽・映像 path（3D の ADR-2607102200 §1.1 と同型）

3D と同じ強制境界を音楽・映像にも適用する。domain（DAW / NLE / notation /
mixing / compositing / grading）の違いによって別エンジンを作ってはならない。

| concern | 必須の authority | app が所有してよいもの |
|---|---|---|
| DSP / synthesis / mixing | `audio`（+ `kami-ongaku-sampler`/`kami-ongaku-plugin-host`） | UI fader/knob 表示、preset 選択 |
| notation / sequencing / session data | `kami-ongaku-notation` / `kami-ongaku-sequencer` / `kami-ongaku-project` | エディタの選択状態、undo stack UI |
| 音声 host（realtime） | browser: `wasm-webcomponent`（AudioWorklet）、offline: kotoba wasm | lifecycle orchestration |
| raw Web Audio API | `org-w3-webaudio` のみ。他 repo は経由呼び出し | — |
| codec / container / transcode | `utsushi` のみ | ffmpeg-equivalent オプション選択 UI |
| timeline / EDL / grading / compositing | `kami-eizo-timeline` / `kami-eizo-grade` / `kami-eizo-compositor` | タイムラインの UI 描画、スクラブ操作 |
| raw WebCodecs API | `org-w3-webcodecs` のみ | — |
| GPU 合成（グレーディング/コンポジット描画） | 既存 3D スタック（`webgpu` primary / `webgl` fallback）をそのまま使用 | — |
| UI chrome | `html` + `css`、共通 UI は `kotoba-ui` / `uikit` / `appkit` | mixer console/timeline panel/inspector |
| 生成モデル呼び出し（AI作曲/AI動画生成） | **kotoba-lang の範囲外**（ADR-2607023000 の境界通り gftdcojp 側） | `composer`/`ongaku` 契約を消費するだけ |

**禁止事項**（3D と同型）:

- app 内の独自 DSP/codec/timeline データモデルの複製
- Tone.js / Howler.js / video.js 等サードパーティ製作編集ライブラリを第2 production engine として導入
- ffmpeg CLI ラップだけを "video engine" と称すること（`utsushi`/`kami-eizo-*` の EDN data model を経由しない生 shell-out を app に書かない）
- 生 `AudioContext` / `VideoDecoder` 等ブラウザ API bootstrap を app に複製

### 3. 言語優先順位（repo-wide runtime priority、CLAUDE.md と同一）

音楽・映像とも新規実装は **`kotoba wasm` > `clojurewasm` > `ClojureScript` >
`nbb` > (降格: JVM / bb)** の順。ただし DSP/codec は host import（AudioWorklet
port 接続、WebCodecs frame in/out、node-graph 実行状態）を多用するため、
現状 `kotoba wasm`/`clojurewasm` の import-free 制約に収まらない部分は
**ClojureScript（`.cljc` portable core + `.cljs` browser 境界）が第一級**になる
（CLAUDE.md の既存 fallback 規定どおり — kami-survivors の host-import 12個の
先例と同じ理由）。純粋関数として書ける部分（envelope 計算、curve 補間、
quantize、EDL diff 等 import なしのロジック）は `kotoba wasm` 対象として
切り出しを優先する。JVM/bb は最後の手段（既存 lib のみ、新規 app には使わない）。

### 4. 新規 repo ロースター

| repo | domain | layer | 状態 |
|---|---|---|---|
| `org-w3-webaudio` | ongaku | L1 | **Wave 2・完了・landed** |
| `kami-ongaku-notation` | ongaku | L3 | **Wave 1・完了・landed** |
| `kami-ongaku-sequencer` | ongaku | L3 | **Wave 1・完了・landed** |
| `kami-ongaku-project` | ongaku | L3 | **Wave 1・完了・landed** |
| `kami-ongaku-plugin-host` | ongaku | L3 | **Wave 3・完了・landed**（comfyui GPL依存を除去済み） |
| `kami-ongaku-sampler` | ongaku | L2 | **Wave 2・完了・landed** |
| `audio` | ongaku | L2 | **Wave 2・完了・landed**（DSP合成・effects chain・mixer bus 追加） |
| `composer` / `ongaku` | ongaku | L0 | 既存・変更なし |
| `org-w3-webcodecs` | eizo | L1 | **Wave 2・完了・landed** |
| `kami-eizo-timeline` | eizo | L3 | **Wave 1・完了・landed** |
| `kami-eizo-grade` | eizo | L3 | **Wave 3・完了・landed** |
| `kami-eizo-compositor` | eizo | L3 | **Wave 3・完了・landed** |
| `utsushi` | eizo | L2 | **Wave 2 encode実装 + Wave 4 配線完了**（`org-iso-h264` の encode 関数を `utsushi.codec` から呼び出し、encode→decode round-trip 検証済み。パラメータセット層のみ） |
| `douga` / `anime` | eizo | L3 | **Wave 4・完了・landed**（`kami-eizo-timeline` 入力 IR 追加。douga は既存 ad hoc shape と並存、anime は adapter 追加） |
| `kami-mangaka-*` | eizo | L0/L3 | 既存・変更なし |

### 5. プロ用途市販品との parity checklist（"commercial grade" の具体化）

| domain | 参照市販品 | 到達目標（機能単位） |
|---|---|---|
| notation | Sibelius / Dorico / MuseScore | 音高/リズム/強弱/アーティキュレーション記譜 IR、MusicXML import/export |
| sequencing | Logic Pro / Cubase / FL Studio | ピアノロール IR、quantize/groove、パターン/クリップ |
| mixing | Pro Tools / Waves / iZotope Neutron | チャンネルストリップ・バス・オートメーション・EQ/comp/reverb/delay |
| plugin host | VST3 / Audio Unit ホスト | node-graph プラグイン contract、レイテンシ補正 |
| sampling | Kontakt | velocity layer/round-robin/key-map/streaming |
| NLE timeline | Premiere Pro / DaVinci Resolve / Avid | track/clip/transition/effect-stack/marker EDL |
| grading | DaVinci Resolve Color page | primary wheels/curves/LUT(.cube)、waveform/vectorscope |
| compositing | Nuke / After Effects / Fusion | node-based keying/roto/tracking data model |
| codec/transcode | ffmpeg | H.264 decode 完成 + encode、AAC/Opus 配線 |

これらは AI 生成モデルの再現ではなく、**データモデルとアルゴリズムの実装**
（3D における mesh/scene/animation エンジンと同格）である点に注意。

### 6. 完了ゲート（3D §1.1 の「最低検証ゲート」と同型）

1. domain data model の round-trip assertion（notation/sequencer/timeline/
   project/grade の import→edit→export が実データで可逆）
2. `.cljc` portable core が JVM/cljs 双方でテスト green（`.kotoba` 化した
   部分は `kotoba wasm emit` 実バイナリで動作確認）
3. WASM guest/host parity（host import を要する経路のみ）
4. 実ブラウザ E2E: 音楽は AudioWorklet 経由の実 PCM render（期待波形/RMS
   と比較）、映像は WebCodecs 経由の実 decode/encode round-trip
5. GitHub Pages load/interaction smoke test
6. WebCodecs/AudioWorklet unavailable 時の capability 判定 + 明示 degraded
   state（fake executor に切り替えない）
7. screenshot/静止画だけでの機能成立判定は禁止（3D と同一原則）

### 7. Phased roadmap

- **Wave 1（データモデル基盤）— 完了（2026-07-12）**: `kami-ongaku-notation` /
  `kami-ongaku-sequencer` / `kami-ongaku-project` / `kami-eizo-timeline` —
  他すべてが依存する SSoT。最優先。4 repo とも public kotoba-lang org に
  実装済み・テスト green・west manifest 登録済み（`manifest/repos.edn`
  extra-projects + `west.yml`、pin 検証 OK）:
  - https://github.com/kotoba-lang/kami-ongaku-notation — 記譜 IR + MusicXML
    import/export（exact-fraction `kami.ongaku.notation.rational` 型を追加、
    cljs にネイティブ ratio 型が無いことへの対処。25 tests / 134 assertions green）
  - https://github.com/kotoba-lang/kami-ongaku-sequencer — MIDI相当イベント/
    パターン IR + quantize/groove + SMF import/export（整数tickで exact 演算。
    18 tests / 60 assertions green）
  - https://github.com/kotoba-lang/kami-ongaku-project — DAW セッション SSoT
    （track/bus/automation/clip placement、notation・sequencer repo に git SHA
    pin で実依存。8 tests / 22 assertions green）
  - https://github.com/kotoba-lang/kami-eizo-timeline — EDL/timeline IR +
    SMPTE drop-frame timecode（14 tests / 122 assertions green）
- **Wave 2（DSP/codec 実行層）— 完了（2026-07-12）**: `audio` 拡張
  （synth/effects/mixer bus）、`kami-ongaku-sampler`、H.264 encode path、
  `org-w3-webaudio`、`org-w3-webcodecs`。全 5 項目 push・テスト green・
  west manifest 登録済み:
  - https://github.com/kotoba-lang/audio — oscillator(sine/square/saw/
    triangle)・ADSR envelope・one-pole filter・delay line + compressor・
    offline mixer bus graph（サイクル検出付き）。実バグ1件（delay-line の
    `long-array`/`double`型不一致）をテストで発見・修正。30 tests / 180
    assertions green（commit `cf2f56a4b41`）
  - https://github.com/kotoba-lang/kami-ongaku-sampler — key/velocity
    layer + round-robin + streaming lifecycle state machine。11 tests /
    40 assertions green
  - https://github.com/kotoba-lang/org-w3-webaudio — Web Audio API 境界層
    （`org-w3-webgpu` と同型）。4 tests / 17 assertions green
  - **H.264 encode は `utsushi` でなく `org-iso-h264` に実装**（agent が
    ブリーフィングの想定を修正: `utsushi.codec` は既に `org-iso-h264` へ
    NAL/SPS/PPS framing を委譲する設計だったため、境界を破らずそちらに
    実装）— https://github.com/kotoba-lang/org-iso-h264 に Exp-Golomb
    writer・RBSP escape・SPS/PPS encode・NAL/Annex-B bitstream writer を
    追加。24 tests / 143 assertions green（commit `a6ed8581fb83`）。
    slice header・macroblock/pixel/CAVLC/CABAC encode は未実装（decode側と
    同じスコープ限定）。**follow-up**: `utsushi.codec` を新 encode 関数に
    配線するタスクは未着手（Wave 3 候補）
  - https://github.com/kotoba-lang/org-w3-webcodecs — WebCodecs API 境界層
    （`org-w3-webgpu` と同型）+ AVC codec-string(`avc1.PPCCLL`) parse/
    format。5 tests / 118 assertions green
- **Wave 3（グレーディング/コンポジティング/プラグイン）— 完了（2026-07-12）**:
  `kami-eizo-grade`、`kami-eizo-compositor`、`kami-ongaku-plugin-host`。
  push・テスト green・west manifest 登録済み:
  - https://github.com/kotoba-lang/kami-eizo-grade — ASC CDL lift/gamma/
    gain + saturation、monotone cubic Hermite tone curve、Adobe `.cube`
    LUT parser + trilinear補間、waveform/vectorscope データ計算
    （`kami-eizo-timeline` の effect-instance 互換）。22 tests / 68
    assertions green（commit `f77f6bb`）
  - https://github.com/kotoba-lang/kami-eizo-compositor — chroma-key・
    roto(ray-casting point-in-polygon)・2D tracking・blend mode。
    24 tests / 58 assertions green（commit `2753b53`）
  - https://github.com/kotoba-lang/kami-ongaku-plugin-host — VST/AU相当
    plugin descriptor/instance contract、plugin delay compensation
    （並列パスのレイテンシ補正、実グラフアルゴリズム）、automation→
    parameter 解決。11 tests / 38 assertions green（commit `51ecb7f`）
  - **重要な訂正: `comfyui` node-graph executor の再利用は撤回**
    （2026-07-12 実装中に判明）。`kotoba-lang/comfyui` は **GPL-3.0**
    ライセンスであり、そこへの実 `deps.edn` 依存は組み合わせ成果物を
    GPL に引き込む。`kami-eizo-compositor` は最初からハード依存を避け、
    comfyui の node-registry contract 形（`:type`/`:inputs`/`:outputs`/
    `:fn`）に**互換なプレーンデータ**を生成するだけに留めた。
    `kami-ongaku-plugin-host` は当初 comfyui へのハード git 依存を
    誤って取り込んでいたため、follow-up タスクで検出・修正
    （registry/topo-sort/validate を自前実装に置き換え、テストは同一
    カバレッジを維持したまま green、Apache-2.0 LICENSE 追加）。
    **今後 comfyui を参照する場合は、この「データ形のみ互換・コード
    依存なし」パターンを踏襲する**（実行したいアプリ側が GPL 条項を
    別途受諾した上で自分で配線する）。§1 の依存レイヤ図・§2.1 の
    concern 表にある「`comfyui` node-graph executor 再利用」という
    表現は、実装としては「executor**コード**の再利用」ではなく
    「executor**が消費できるデータ形**への準拠」と読み替える。
  - ついでに LICENSE 欠落を修正: `kami-ongaku-sequencer`・
    `org-w3-webaudio`・`org-w3-webcodecs` に Apache-2.0 LICENSE を追加
    （Wave 1/2 の scaffold で漏れていた）
- **Wave 4（app 統合）— 完了（2026-07-12）**: `douga`/`anime` を
  `kami-eizo-timeline` ベースへ再配線 + `utsushi.codec` の H.264 encode
  配線（Wave 2 の follow-up）。push・テスト green・west manifest 登録済み:
  - https://github.com/kotoba-lang/douga — `kami-eizo-timeline` EDL を
    受け取る新entry point `douga.eizo-timeline/render-plan` を追加（既存の
    ad hoc shape 版は yukkuri 実運用パイプライン ADR-2607051600 を壊さない
    ため**並存維持**、置き換えでなく追加）。video track の transition は
    未対応のため明示的に reject（サイレント無視ではなく `ex-info`）。
    11 tests / 40 assertions green（commit `8a06155`）
  - https://github.com/kotoba-lang/anime — `anime.timeline/cut-sequence->
    timeline` adapter を追加。12 工程ステージ・8層は timeline 上の意味を
    持たないため**意図的に非マッピング**（工程ボードは並列であり再生位置
    ではない、レイヤーはcutの逐次生産状態でありコンポジット同時レイヤー
    ではない）、retake cut のみ実際に marker 化。出力は
    `kami.eizo.timeline/validate-timeline` を通過確認済み。11 tests /
    85 assertions green（commit `00ec484f951c`）
  - `utsushi.codec` の H.264 encode 配線（Task #15、Wave 2 の follow-up）
    — https://github.com/kotoba-lang/utsushi の既存 decode 配線
    （avcC box → SPS parse）と対称的な encode path（SPS+PPS encode →
    avcC/avc1/stsd 構築）を追加。encode→decode round-trip で
    width/height/profile-idc/level-idc の一致を確認。副産物として
    `utsushi` の `org-iso-h264` pin が 7 commit 遅れていたのを発見・
    前進（`a6ed8581` の encode 関数を含む版に）。パラメータセット層
    （SPS+PPS）のみ、マクロブロック/画素/CAVLC/CABAC encode は未実装
    （`org-iso-h264` 自体のスコープ限定と同じ）。12 tests / 31
    assertions green（commit `bb7bf20282a6`）
  - 将来の `kami-app-daw`/`kami-app-nle` は本 ADR の範囲外（別 ADR）
- **Wave 5（実ブラウザ I/O 実証）— 完了（2026-07-12、当初計画外の追加 wave）**:
  Wave 1-4 は「アルゴリズムは正しいが実際に音も映像も出せない」状態だった
  （成熟度評価で音楽・映像とも総合2/5 — 90-docs/adr の成熟度評価参照）。
  この gap を埋めるため、ADR §6 の完了ゲート（実ブラウザ E2E・WASM host/guest
  parity）を実際に満たす wave を追加した。

  **戦略転換（重要）**: 当初「H.264 のマクロブロック/DCT/CAVLC/CABAC を
  `org-iso-h264` に自前実装する」方向を検討したが、これは x264/libavcodec
  相当の非現実的な工数であり却下。代わりに、3D が生 WebGPU を `org-w3-webgpu`
  経由でブラウザ実装に委譲するのと同じ考え方で、**ピクセルレベルの実
  codec 処理はブラウザ内蔵の WebCodecs（`VideoDecoder`/`VideoEncoder`）に
  委譲**し、`org-iso-h264`/`utsushi` は container/parameter-set 層に留める
  方針を確定した。この転換が Wave 5 全体を成立させた。

  - https://github.com/kotoba-lang/org-w3-webcodecs（commit `b14dc397e248`）
    — 実ヘッドレスChromiumで実H.264（`avc1.42001f`）encode→decode round-trip
    を実施、復号ピクセルが元色と誤差1〜4/255で一致。**これが Wave 5 全体の
    基盤**（以降の全タスクがこの実 codec I/O の上に構築された）
  - https://github.com/kotoba-lang/kami-eizo-timeline（commit `c0116940f19eb`）
    — `clip-at-frame` を追加し、3クリップのタイムラインを実際に
    WebCodecs 経由でレンダー。カット境界（frame 5, frame 10）がピクセル
    単位で正確に一致（フレーム精度の実証）
  - https://github.com/kotoba-lang/kami-eizo-grade（commit `a19d4ea81b03`）
    — ASC CDL grading を実デコード済みピクセルに適用。ブラウザ計算と
    オフライン(nbb)計算が完全一致、再encode→decode後も許容誤差内
  - https://github.com/kotoba-lang/kami-eizo-compositor（commit `2f6838662dc1`）
    — chroma-key + blend を実デコード済みピクセルに適用。key領域は
    alpha 0、subject領域は alpha 1 に正しく判定、ブラウザ/オフライン完全一致
  - https://github.com/kotoba-lang/org-w3-webaudio（commit `e554d853d640`）
    — **最難関だった課題を解決**: `kotoba-lang/audio` の sine+ADSR DSP を
    実ブラウザの `AudioWorkletProcessor` 内で実行し、キャプチャした PCM が
    オフライン参照実装と最大誤差 2.98e-8（実質完全一致）で一致することを
    実証。2つの実バグを特定・修正: ① `cljs.main` の既定ビルドが dev REPL
    接続用ブートストラップを同梱し、`AudioWorkletGlobalScope` に存在しない
    `document` に触れて無言で失敗（`:optimizations :advanced` で dead-code
    除去して解消）② `^:export` 境界越え呼び出しで Closure の `goog.global`
    判定（`this || self`）が `self` 未定義の worklet scope で例外
    （1行の `self` polyfill で解消）。また `audio`/`org-w3-webaudio` 双方の
    既存 offline テストスイートは無変更のまま green を維持
  - **副産物のバグ発見・修正**（全 Wave 5 タスク共通で見られたパターン:
    「実データ・実環境で動かして初めて見つかる」バグ）: `org-w3-webaudio`
    の `new-audio-context!` コンストラクタ呼び出しが Wave 2 から全ブラウザで
    例外を投げていた（JVM テストが無いため未検出だった）。`audioWorklet.
    addModule` は secure context 必須（`about:blank` 不可、`localhost` 可）
    という発見も得た
  - 各リポジトリとも `deps.edn` に `:e2e` alias（org-w3-webcodecs への
    pinned git dep）+ `test/e2e/`（nbb + Playwright、CLAUDE.md の nbb
    規約に準拠、生 `.mjs` は使わない）という共通パターンを確立し、以降の
    ドメイン（サンプラー・プラグインホスト・シーケンサー等）にも再利用可能
  - west manifest 登録済み（4 pin 前進、`50c96fd54650`）
- **Wave 6（残りドメインへの実証拡大）— 完了（2026-07-13）**: Wave 5 で
  確立した「実 org-w3-webaudio AudioWorklet 経由で実オーディオを検証する」
  パターンを、まだ 2/5 だった `kami-ongaku-sampler`・`kami-ongaku-plugin-host`
  に適用。
  - https://github.com/kotoba-lang/kami-ongaku-sampler（commit `6d2abf0a6f34`）
    — 実サンプルファイルの代わりに `audio` の実 oscillator を割り当てた
    代替サンプルマップで trigger/lookup を実証。14入力で実測周波数
    （440/445/220/225/494.4413Hz）がゼロ交差カウント法で期待値と4桁まで
    一致、round-robin 交互切替・velocity/key境界の正確な判定を確認。
    副産物: Closure `:advanced` の**別コンパイル単位間でのプロパティ名
    衝突**（`.decision` が送信側と受信側で別々に `.bc` 等へリネームされ
    `undefined` になる）という新種のバグを発見・`aget` 文字列キーアクセス
    で修正
  - https://github.com/kotoba-lang/kami-ongaku-plugin-host（commit `a85ee94e8dc2`）
    — PDC（plugin delay compensation）を実オーディオで実証。200サンプルの
    実遅延（`audio` の実 delay-line）を持つパスと持たないパスについて、
    補正なしでは実測200サンプルのズレ、`compute-pdc` の補正値適用後は
    実測0サンプル（ピーク位置・相互相関の両方で確認）— 数式が実際の
    ズレを解消することを実証（synthetic な整数ではなく実信号で）
  - west manifest 登録済み（2 pin 前進、`e773f1c98d7f`）
- **Wave 7（記譜・シーケンサーの実再生証明）— 完了（2026-07-13）**:
  - https://github.com/kotoba-lang/kami-ongaku-sequencer（commit `17c0ec4607b1`）
    — 5音パターンを SMF export→import 往復（バイト完全一致確認）させた後、
    実 `AudioWorkletProcessor` 内の**1つの連続バッファ**に5音を同時
    スケジュールしてレンダー。オンセット位置は許容200サンプルに対し実測
    6〜16サンプルのズレ、周波数は5音すべて期待値と4桁一致
  - https://github.com/kotoba-lang/kami-ongaku-notation（commit `0a61b700a331`）
    — 4音のフレーズを MusicXML export→import 往復させた後、実オーディオ
    レンダー。周波数4桁一致に加え、**`ff`のピーク振幅が`pp`の3.394倍
    （期待されるgain比と正確に一致）**— 強弱記号が実際の音量に反映される
    ことを実証。実バグ発見・修正: ADSR envelope 呼び出しで秒/サンプル
    単位を二重変換していた（音量が約1000倍小さくなっていた）
  - https://github.com/kotoba-lang/audio に Apache-2.0 LICENSE を追加
    （commit `03966413bfd9`。Wave 5-7 で複数repoの実依存先になったにも
    関わらず LICENSE が欠落していたのを Wave 7 中に発見）
  - west manifest 登録済み（3 pin 前進、`dc52c46c0cb3`）
  - 残る 2/5 領域: `kami-ongaku-project`（session全体の実レンダー未証明。
    notation/sequencer個別の実再生は証明済みなので、次はbusグラフ経由の
    複数トラック統合レンダーが自然な次ステップ）、`douga`/`anime`
    （`kami-eizo-timeline` 統合は済んだが実ffmpegレンダーとの結合証明は未）
- **Wave 8（session統合レンダー + 実ffmpeg実行証明）— 完了（2026-07-13）**:
  - https://github.com/kotoba-lang/kami-ongaku-project（commit `6a14e1814a89`）
    — notation/sequencer 実依存の複数トラックを実バスグラフ経由でミックス。
    drumsバス（gain 0.5 vs 1.0）のピーク比が**正確に2.0倍**、instruments
    バス（gain 1.0固定）は**正確に1.0倍**（無変化）を実測。オフライン
    計算とbit単位で一致（diff 5.79e-8）。これで音楽ドメインの主要5repo
    （notation/sequencer/sampler/plugin-host/project）すべてが実オーディオ
    実証済みになった
  - https://github.com/kotoba-lang/douga（commit `6c9799951c90`）— **初めて
    本物のffmpegバイナリを実行**（8.1.1、macOS/Homebrew、libx264+aac）。
    3シーン（赤/黄緑/青、24fps、ハードカットのみ）の EDL を douga の実
    コマンドビルダーに通し、生成されたコマンドをそのまま実行、ffprobe で
    長さ/解像度確認 + 9箇所のピクセルサンプリングでカット境界前後含め
    23/23チェック合格（色誤差は数/255のH.264圧縮由来のみ）。副産物として
    ffmpeg 8.1.1 の `crop` フィルタの罠（`exact=0`既定で1×1指定が暗黙に
    0×0に丸められエラーになる）を発見・回避
  - west manifest 登録済み（2 pin 前進、`14f5f94ce8f6`）
  - **音楽ドメイン(ongaku)は主要5領域すべてが実I/O実証済み**（notation/
    sequencer/sampler/plugin-host/project）。残るは `kami-ongaku-plugin-host`
    の実VSTロード相当（範囲外と明記済み）と `org-w3-webaudio` のリアルタイム
    インタラクティブ制御（現状はoffline/prerender proofのみ）
  - **映像ドメイン(eizo)の残課題**: `anime` の実ffmpegレンダー結合証明
    （`douga` で確立したパターンを流用可能）、`kami-eizo-timeline` の
    トランジション（dissolve/wipe）レンダー未実装、`douga` legacy
    scene/lines/assets path（実運用中のyukkuriパイプラインが使う経路）は
    今回のE2E対象外のまま
- **Wave 9（anime パイプライン統合 + dissolve トランジション）— 完了
  （2026-07-13）**:
  - https://github.com/kotoba-lang/anime（commit `9de3495420600`）—
    **`anime` → `kami-eizo-timeline` → `douga` → 実ffmpeg** の3repo
    パイプラインを初めて end-to-end 実証。4カット（うち1つ retake
    マーカー付き）を実際に動画化、カット境界前後含む9箇所のピクセル
    サンプリングで29/29チェック合格（誤差1〜3/255）。**重要な発見を
    隠さず橋渡し**: `anime` の adapter 出力（`:clip/source-id` が
    レンダー可能パスでない、`:douga/scene-index` 欠落）と `douga` が
    要求する形の間に実際のギャップがあり、`attach-douga-keys` という
    明示的なブリッジ関数として実装・README に正直に記録（隠蔽や
    設計変更での回避をしなかった）
  - https://github.com/kotoba-lang/douga（commit `1cc48e4de049`）—
    dissolve トランジションの実クロスフェードレンダーを実装
    （`xfade` フィルタ、`kami-eizo-timeline` の overlap 意味論と直接
    対応）。赤→青の実dissolveで25/50/75%地点のRGB値が単調に変化
    （R: 252→188→125→61→0、B: 0→61→125→190→253、G=0終始）—
    ハードカットでも壊れたフレームでもない本物のブレンドであることを
    ピクセルレベルで実証。17/17チェック合格。`:wipe` 等それ以外の
    transition種別は引き続き明示的に reject（サイレント無視ではない）
  - west manifest 登録済み（2 pin 前進、`3268044ed9be`）
  - **これで音楽・映像両ドメインとも、主要な実I/Oギャップはほぼ解消**。
    残るのは: `kami-eizo-timeline` の `:wipe` 等その他 transition 種別、
    複数 dissolve の連結、`douga` legacy scene/lines/assets path
    （実運用中の yukkuri パイプラインが使う経路。今回のE2E群は
    `kami-eizo-timeline` 経由の新 entry point のみを対象とした）、
    `kami-ongaku-plugin-host` の実VSTロード（範囲外と明記済み）、
    リアルタイム対話制御（現状は全て offline/prerender proof）
- **Wave 10（wipe トランジション + legacy path 実証）— 完了（2026-07-13）**:
  - https://github.com/kotoba-lang/douga（commit `4b0bb2d58a01`。同一
    repo 内で2タスクが直列着地）
    - **wipe トランジション実装** — `xfade` の `wipeleft` モードで実装。
      5 x座標 × 3時点のサンプリングで **dissolve との明確な対比**を実証:
      dissolve は1点が時間とともに連続的にR/Bブレンド、wipe は**シャープな
      2領域分割**が右→左へ実際に掃引（blue判定ピクセル数が1→2→4と単調
      増加）。16/16チェック合格。`{:dissolve :wipe}` 以外の種別は引き続き
      明示的 reject
    - **legacy scene/lines/assets path の実ffmpeg証明** — **実運用中の
      yukkuriパイプラインが実際に使う経路**（`kami-eizo-timeline` 経由の
      新 entry point ではない）を、本番コードを一切変更せず読み取り専用で
      検証。複数音声テイクの連結・無音シーン・bgmミックスを含む構成で
      実ffmpeg実行、34/34チェック合格。気になる挙動（duration が
      `-shortest` に完全依存する emergent な値であること、無音シーンの
      自動音声合成が無いこと）は**修正せず** README の「Known
      limitations」に事実として記録（本番への無断介入を避けた）
  - west manifest 登録済み（1 pin 前進、`2bd3232580e2`）
  - **音楽・映像とも、当面の主要な実I/Oギャップは解消**。残るのは:
    `:wipe`/`:dissolve` 以外の transition 種別、複数 transition の連結、
    `kami-ongaku-plugin-host` の実VSTロード（範囲外と明記済み）、
    リアルタイム対話制御（現状は全て offline/prerender proof）。これらは
    いずれも「自動化エージェントによるコード生成だけでは埋めにくい」
    性質のギャップ（実運用パイプラインとの調整判断、対話的性能検証等）
    であり、次に着手する場合は個別に方向性を確認するのが望ましい
- **Wave 11（複数トランジション連結）— 完了（2026-07-13）**:
  - https://github.com/kotoba-lang/douga（commit `0b540ce05c7a`）— 単発
    トランジション（2クリップ）の実証はWave 9-10で済んでいたが、3クリップ
    以上・複数トランジションの連結は未検証だった。新規 `xfade-chain-cmd`
    を実装し、3クリップ（dissolve→wipe）を1回のffmpeg実行で連結レンダー。
    **実バグを発見・修正**: 単純な逐次オフセット計算（各クリップの生の
    長さを使う）では2段目以降のoffsetが誤った値になる — 正しくは
    チェーンの累積出力長を基準にする必要がある（`D_k = D_(k-1) +
    dur(clip_k) - dur(transition_k)`）。5チェックポイント全てで実証:
    トランジション1前は純色、dissolve中は連続ブレンド、**2つの
    トランジション間の安定区間が正しく純色**（連結ロジック正しさの
    決め手）、wipe中は空間的sweep、トランジション2後は純色。26/26
    チェック合格。既存の単発dissolve/wipe/legacy path証明も非回帰確認
  - west manifest 登録済み（1 pin 前進、`ff279b87d3c6`）
  - 未対応: トランジション+per-scene音声/bgmミキシングの同時処理、
    `wipeleft`以外のwipe方向、`:slide`等その他transition種別、非線形
    トランジショントポロジー

## Consequences

- (+) 3D と同じレイヤ規律（authority 一元化・禁止事項明示）が音楽・映像にも
  適用され、app ごとの DSP/codec/timeline 複製を防げる
- (+) "commercial grade" の定義が市販品 parity checklist として具体化され、
  進捗判定が可能になる
- (+) ADR-2607023000 の org 境界（code=kotoba-lang / business=gftdcojp）を
  壊さずにエンジン層だけを厚くできる
- (−) 新規 repo が最大 10 個規模で増える（west manifest 登録・pin 管理の
  負荷増）
- (−) Wave 1-4 は複数セッションにまたがる規模。本 ADR 単独では実装は完了
  しない（roadmap 文書としての機能が主）

## Alternatives considered

- **既存 `audio`/`utsushi` に全機能を詰め込む**: 却下。3D で `kami-engine`
  monorepo が肥大化し分離コストを払った失敗（ADR-2607102200 参照）を繰り返す。
- **Tone.js/video.js 等 JS ライブラリを直接採用**: 却下。3D で Three.js/
  Babylon.js を禁止した理由と同型（第2 engine の併存、cljs data model との
  二重管理）。
- **生成モデル呼び出しを kotoba-lang に取り込む**: 却下。ADR-2607023000 の
  org 境界を破壊する。gftdcojp 側 actor が `composer`/`ongaku` 契約を消費する
  構造は維持する。
- **JVM/bb を第一級にする**: 却下。CLAUDE.md の repo-wide runtime priority
  に反する。

## Related

- ADR-2607102200 kami-engine 3D/WebGPU/WebGL2 依存・権限マップ（本 ADR のひな型）
- ADR-2607023000 creative -ka レイヤ分解（org 境界の根拠）
- ADR-2607031510 `kotoba-lang/composer`
- ADR-2606272200 utsushi video EDN filtergraph
- ADR-2607082500 kasane/utsushi reverse-domain 分解
- ADR-2606282100 / 2606282101 mangaka render commons / multilingual text layer
- ADR-2607051600 yukkuri real-production stack live
- ADR-2607100030 kotoba kami-engine host imports（cljs fallback 根拠）
