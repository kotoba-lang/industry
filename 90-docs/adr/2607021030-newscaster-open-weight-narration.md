# ADR-2607021030: ai-gftd-newscaster — open-weight ナレーション（hume quality）+ 多言語

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ai-gftd-newscaster（ADR-2607020910）の生成動画は無音の字幕動画だった。オーナー要件:
「音声も生成、hume quality にしたい。**ただし Hume（API）自体は使わず open weight で**。
多言語にも対応したい」。

調査（2026-07-02）:

- **Hume 自身が TTS を open-source 化している**: **TADA**（github.com/HumeAI/tada、
  コード MIT / weights Llama 3.2 Community License）。`HumeAI/tada-1b`（英）と
  `HumeAI/tada-3b-ml`（多言語 — **日本語を含む 9 言語** + 英語）。LLM-based、
  reference audio + transcript による voice prompt、bf16 で ~9GB VRAM。
  「Hume を使わず hume quality」の最短解 = **Hume 純正の open weights**。
- 軽量代替: **Kokoro**（82M、Apache-2.0、ja を含む 9 言語、CPU で実時間超）。
  表現力は TADA/Octave に劣るがローカル確認・CI に現実的。
- newscaster の実レンダは ffmpeg セグメント方式（ADR-2607020910）なので、
  音声はセグメント単位で合成して尺を音声実時間で決めるのが自然。

## Decision

**Narrator port（第 4 の注入境界）を追加し、live 実装は「TTS gateway サーバ +
open-weight backend（TADA 主 / Kokoro 軽量）」にする。**

1. **Narrator port**（`newscaster.ports/Narrator`）: `(-narrate n channel segment lang)`
   → `{:path :duration-s :cid :voice} | nil`。mock は nil（無音 = 従来挙動）。
2. **TTS gateway**（`newscaster.tts/http-narrator` + `scripts/tts_server.py`）:
   契約は `POST $TTS_URL/tts {text, lang, voice}` → WAV bytes。サーバは
   `BACKEND=kokoro|tada` で open-weight モデルをローカル/pod で serve
   （murakumo の IMAGEGEN_URL / GFTD_LLM_URL と同じ gateway 流儀）。
   WAV 実尺は RIFF ヘッダから JVM 側で読む。音声 asset は sha256 cid で放送台帳へ。
3. **尺の同期**: セグメント mp4 は `-loop 1 -i slide.png -i seg.wav -af apad -t 尺`
   （尺 = max(音声実尺+0.5s, 3s)、音声なしは anullsrc）。全セグメント aac 48kHz
   stereo に正規化して `-c copy` concat（編成表の尺は上限目安に降格）。
4. **EditorialGovernor に HARD 追加**:
   - `:voice-consent` — narration の voice は channel `:persona :voices` に**登録済み**
     のもののみ（open-weight TTS は数秒でクローン可能 → 無断クローンを型で排除）。
   - `:unsupported-lang` — render-spec の lang は channel `:langs` 内のみ。
5. **多言語**: channel に `:langs`（既定 ["ja" "en"]）と per-lang voice registry。
   script segment は主言語 `:lines`/`:caption` + `:i18n {lang {:lines :caption}}`
   （kami.mangaka.text の locale map 流儀）。**cites はセグメント構造側にあるため
   全 locale で構造的に同一** = 翻訳で出典がすり替わらない。`:video/produce` は
   `:lang` を取り、per-lang に render（`out/<ep>/<lang>/episode.mp4`、episode は
   `:videos {lang video}` を蓄積）。YouTube は per-locale 動画 + `localizations`
   （multi-audio track は API allowlist のため follow-up）。

## Consequences

- (+) Hume API 非依存で「hume quality」経路（TADA = Hume 純正 open weights、ja 対応）。
  backend は gateway 契約の後ろで自由に差し替え（Kokoro / CosyVoice / 将来モデル）。
- (+) 音声つき・尺が音声に同期した mp4 が実生成される。無断 voice clone は
  governor が構造的に hold。多言語でも出典トレーサビリティが崩れない。
- (−) TADA 3B はローカル Mac では重い（bf16 ~9GB）。日常のローカル確認は Kokoro、
  TADA は GPU pod（murakumo fleet）での運用を想定。
- (−) TTS 品質のうち「演技指示」（Octave の acting instructions 相当）は TADA では
  reference audio 依存。persona ごとの reference 音源整備が follow-up。
- (−) YouTube multi-audio track は未対応（per-locale 動画で開始）。

## References

- ADR-2607020910（newscaster actor 本体）
- github.com/HumeAI/tada / HuggingFace `HumeAI/tada-3b-ml`（ja 含む 9+1 言語）
- hume.ai/blog/opensource-tada、hume.ai/blog/octave-2-launch（比較対象の商用 API）
- Kokoro-82M（Apache-2.0、hexgrad）— 軽量 fallback backend
- 本 ADR とペアの .edn
