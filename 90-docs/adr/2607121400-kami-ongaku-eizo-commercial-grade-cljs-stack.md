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
                 kami-ongaku-plugin-host … VST/AU相当プラグイン contract + node-graph host（`comfyui` node-graph executor を再利用）
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
                 kami-eizo-compositor    … node-based VFX compositing graph（keying/roto/tracking data model、`comfyui` node-graph executor を再利用）
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
| `org-w3-webaudio` | ongaku | L1 | **新規**（Wave 2、未着手） |
| `kami-ongaku-notation` | ongaku | L3 | **Wave 1・完了・landed** |
| `kami-ongaku-sequencer` | ongaku | L3 | **Wave 1・完了・landed** |
| `kami-ongaku-project` | ongaku | L3 | **Wave 1・完了・landed** |
| `kami-ongaku-plugin-host` | ongaku | L3 | **新規**（Wave 3、未着手） |
| `kami-ongaku-sampler` | ongaku | L2 | **新規**（Wave 2、未着手） |
| `audio` | ongaku | L2 | 既存拡張（DSP合成・effects chain・mixer bus を追加、Wave 2 未着手） |
| `composer` / `ongaku` | ongaku | L0 | 既存・変更なし |
| `org-w3-webcodecs` | eizo | L1 | **新規**（Wave 2、未着手） |
| `kami-eizo-timeline` | eizo | L3 | **Wave 1・完了・landed** |
| `kami-eizo-grade` | eizo | L3 | **新規** |
| `kami-eizo-compositor` | eizo | L3 | **新規** |
| `utsushi` | eizo | L2 | 既存拡張（encode path 追加） |
| `douga` / `anime` | eizo | L3 | 既存・入力 IR を `kami-eizo-timeline` に統一 |
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
- **Wave 2（DSP/codec 実行層）**: `audio` 拡張（synth/effects/mixer bus）、
  `kami-ongaku-sampler`、`utsushi` の encode path、`org-w3-webaudio`、
  `org-w3-webcodecs`。
- **Wave 3（グレーディング/コンポジティング/プラグイン）**: `kami-eizo-grade`、
  `kami-eizo-compositor`、`kami-ongaku-plugin-host`（`comfyui` node-graph
  executor 再利用）。
- **Wave 4（app 統合）**: `douga`/`anime` を `kami-eizo-timeline` ベースへ
  再配線、将来の `kami-app-daw`/`kami-app-nle`（本 ADR の範囲外、別 ADR）。

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
