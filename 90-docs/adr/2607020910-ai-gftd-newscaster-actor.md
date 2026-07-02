# ADR-2607020910: ai-gftd-newscaster — AI ニュースチャンネル（B層）+ YouTube 動画生成 actor

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

「AI ニュースのチャンネルを設計し、AI ニュースを生成し、それを YouTube 動画として
作成・公開する actor」が必要。既存資産の実態調査（2026-07-02）:

- **ai-gftd-news**（news.gftd.ai）は **A 層 = 一次情報収集専用**。article は
  `art-<sha256(url)>` を entity ref とする decomposed datom graph、決定的スコアリング
  （`news.score/score-intel` — socialArbitrageScore/priority/credibility/bridgeScores）と
  権利ゲート（`news.policy/live-audio-policy-gate` — rightsPolicy → publishAllowed/blocked）
  を持つ。**生成・配信は B 層（media.gftd.ai 系）の責務**と明示されており、news 本体を
  拡張してチャンネル/動画をやるのは層違反。
- **ai-gftd-animeka** は work→episode→scene→cut の 12 工程グラフサーバ（XRPC ~30 NSID、
  `POST /xrpc/<nsid>` は EDN body 可）。構造管理・cutRunner/autopilot は完動だが
  **generation 層はスタブ**（ComfyUI facade `animeka.comfy/render-keyframe` と LLM facade
  `animeka.llm` は存在するが未結線、ffmpeg/TTS は backlog）。
- **kotoba-lang/kami-engine** は CLJ/EDN/WIT の資産・契約リポ。**今日 JVM ヘッドレスで
  動くのは 2D 経路**: `kami.mangaka.render/render!`（拡散 image-gen → PNG）、
  `kami.mangaka.text`（純データ・多言語テキスト層）、`kami.mangaka.page`（Java2D の
  caption-box/bubble/SFX 合成 = lower-thirds の原型）。3D headless backend
  （`kami.backend.host`）はスタブ、動画 encode は `gftd:kami-cine` WIT 契約のみ
  （実装は pod 側・deferred）。**ffmpeg はどこにも無い**。

actor の作法は本 workspace の同型 3 例 — robotaxi-actor（AR1⊣SafetyGovernor）/
gftd-talent-actor（HR-LLM⊣PolicyGovernor）/ ai-gftd-itonami（ops-LLM⊣CertGovernor）—
に従う: 封じ込めた知能ノードは proposal のみ、独立 governor が検閲、append-only 台帳、
Store/Advisor/Phase 注入、langgraph-clj StateGraph、1 run = 1 操作。

## Decision

**新規 actor repo `gftdcojp/ai-gftd-newscaster`（west path
`orgs/gftdcojp/ai-gftd-newscaster`）を起こし、anchor-LLM ⊣ EditorialGovernor 型の
B 層放送 actor として実装する。** ai-gftd-news は上流（A 層）のまま触らない。

1. **チャンネルは data（`newscaster.channel`）**。channel entity =
   handle/title/lang/cadence/persona/segment format（cold-open → top-stories×N →
   one-more-thing → outro、各セグメント秒数）/visual（studio backdrop・lower-thirds
   スタイル = kami mangaka text 語彙）/YouTube メタ既定（category/tags/**AI 生成開示**）。
   既定チャンネル **「GFTD AI News」**（日次 AI ニュースダイジェスト、ja、約 3 分）を同梱。
2. **二流路の StateGraph（`newscaster.operation`）** — itonami と同型:
   - ingest（観測・常時 ON・LLM 無し）: `:article/ingest`（A 層 article の写像。
     provenance/rightsPolicy/score を保持）、`:channel/register`、`:asset/record`。
   - produce（assess 経路）: `:rundown/compose` → `:script/draft` → `:video/produce` →
     `:episode/publish`。anchor-LLM が proposal（rundown/script/render-spec/publish-meta）
     を返し、EditorialGovernor が検閲、phase gate、**publish は常に人間承認**
     （`interrupt-before`）。
3. **EditorialGovernor（放送考査）の HARD 不変条件**（人間でも上書き不可）:
   - **source-traceability** — rundown/script の全 item は store に ingest 済みの
     article id を引用（幻覚ニュースの構造的排除。cites ⊆ ingested）。
   - **rights-gate** — 引用 article の rightsPolicy が publishAllowed で無ければ hold
     （A 層 `news.policy` と同じ語彙）。
   - **disclosure** — publish proposal は `:disclosure :ai-generated`（YouTube の
     合成メディア開示）を含まねばならない。
   - **no-actuation** — LLM proposal の effect は `:proposal`/`:asset` のみ。外部公開
     （YouTube upload）は publish op の人間承認後に Publisher port だけが行う。
   SOFT: confidence floor → escalate。`:episode/publish` は常に high-stakes。
4. **Phase 0→3**: 0 = ingest-only / 1 = assisted（全 produce 人間承認）/
   2 = assisted-edit（rundown/script auto、video/publish 人間）/
   3 = supervised（rundown/script/video auto-commit、**publish は常に人間**）。
5. **注入 port（swap）**: Store（`MemStore` ‖ `DatomicStore`、langchain.db `:db-api`）/
   Advisor（mock ‖ `langchain.model` LLM）/ **NewsFeed**（mock 種記事 ‖ A 層 kotoba
   XRPC）/ **Renderer**（mock ‖ `newscaster.render` = Java2D 16:9 news-card
   （kami.mangaka.page の caption-box 流儀を 1280×720 へ再構成）+ 任意 IMAGEGEN_URL
   背景 + ffmpeg concat → mp4。ANIMEKA_URL 設定時は `newscaster.animeka` XRPC で
   work/episode/scene/cut 構造を animeka 側にも記録）/ **Publisher**（mock ‖
   `newscaster.youtube` YouTube Data API v3 resumable upload、承認後のみ）。
6. **台帳 = 放送台帳（append-only）**。ingest/rundown/script/render/publish/hold の
   全 disposition を積み、「いつ・どの記事を根拠に・誰が承認して・何を公開したか」を
   不変に残す（データ主権・出典トレーサビリティの核）。

## Consequences

- (+) A 層（収集）と B 層（編成・生成・配信）の層分離を保ったまま、AI ニュース
  チャンネル → 動画 → YouTube 公開が 1 actor で監査可能に通る。
- (+) 幻覚ニュース・権利違反・開示漏れが **governor の型で** 構造的に止まる。公開は
  常に人間の editorial sign-off。
- (+) Renderer が port なので、animeka の generation 層（ComfyUI/TTS/ffmpeg adapter）や
  kami-cine encode 契約が実装され次第、mock/Java2D 経路から差し替えるだけで昇格する。
- (−) 現時点の実レンダは 2D news-card + ffmpeg（kami-engine の今日動く経路）。
  3D スタジオ/アバター化は `kami.backend.host` 実装 or kami-live GpuRenderer 採用後の
  follow-up。TTS ナレーションも animeka の tts adapter 待ち（無音 or 字幕動画で開始）。
- (−) A 層からの実記事取り込み（kotoba XRPC NewsFeed）は creds/稼働 pod 前提。既定は
  mock 種記事で決定的に動く。

## References

- ai-gftd-itonami `docs/DESIGN.md`（同型 actor の手本 — ops-LLM⊣CertGovernor）
- `orgs/gftdcojp/ai-gftd-news`（A 層: schema/score/policy）、
  `orgs/gftdcojp/ai-gftd-animeka`（cut グラフサーバ）、
  `orgs/kotoba-lang/kami-engine`（kami-mangaka-{render,text,page}-clj、`wit/cine`）
- ADR-2606272330（ai-gftd-router — 新規 project 一気通貫登録の実例・恒久承認）
- 本 ADR とペアの .edn
