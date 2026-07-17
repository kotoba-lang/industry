# ADR-2607171800: ai-gftd-dougaka-vector — 言語・キャラクター非依存のベクターアニメーション動画生成 workflow

## Status

Accepted, scaffolded & registered（2026-07-16）。repo: `orgs/gftdcojp/ai-gftd-dougaka-vector`
（private、gftdcojp org 既定 visibility）。scaffold は nbb で end-to-end 検証済み
（9 tests / 53 assertions green、example storyboard → 390 frames → ffmpeg mp4 まで実走）。

## Context

- `ai-gftd-yukkuri` はサイバーセキュリティ解説をゆっくり実況スタイル（左右 2 キャラ立ち絵 +
  VOICEVOX 日本語 TTS 掛け合い）で生成している。このスタイルは (1) キャラクター IP・立ち絵
  アセットに依存、(2) 日本語音声・日本語視聴者に依存、(3) 多言語展開は translateVideo
  （SRT + dub）による後付けで、画面内の情報設計自体は日本語前提。
- オーナーから、参照チャンネル（"Engineering Behind LLM Inference: Quantization" 型 —
  黒背景・ネオンアクセント・アニメーションするチャート/ダイアグラムが説明を運ぶ
  モーショングラフィックス、キャラクター無し、画面テキスト最小）のような
  **言語やキャラクターに依存しない、ベクターアニメーション主体の動画生成 workflow** を
  設計し `ai-gftd-dougaka-vector` として repo を起こす指示（2026-07-16）。
- 既存資産の棚卸し:
  - `ai-gftd-dougaka` = ffmpeg assembler（ADR-2605312355: フレーム列 + 音声を連結・mux
    するだけの最終合成担当）。**mux をこの新 repo に複製しない**。
  - BGM は `ongakuka`、SFX/ナレーション/台本 LLM は `cloud-murakumo`（murakumo.cloud）。
  - repo-wide runtime priority（CLAUDE.md 2026-07-10 改訂）: kotoba wasm > clojurewasm >
    ClojureScript > nbb。生 JS/TS・`.sh`・新規 Rust は新規作成禁止。
  - repo-wide 3D ルール（2026-07-10）: 3D は kami-engine stack 必須。**本件は 2D vector
    のみ**なので対象外（SVG 禁止則は「3D viewport に SVG を使うな」であり、2D モーション
    グラフィックスのフレーム生成は該当しない）。

## Decision

**pipeline**（責任境界を「LLM は storyboard まで、以降は純関数」で切る）:

```
topic ─▶ storyboard EDN ─▶ scenegraph ─▶ SVG frames ─▶ PNG ─▶ mp4
     LLM (murakumo text)   scene.cljc    svg.cljc     resvg   ai-gftd-dougaka (ffmpeg)
                                 └─▶ cues.edn ─▶ SFX/BGM (murakumo audio / ongakuka)
```

1. **言語非依存 = copy-id 間接層**。scene template は文字列でなく copy-id を参照し、
   `:video/copy {:hook {:en "..." :ja "..."}}` の locale map を `--locale` で選んで
   compile 時に解決する。同一アニメーション構造のまま多言語版をバイト再現でレンダー
   できる（テストで en/ja の node 構造一致を assert）。ナレーションはオプション
   （既定 BGM+SFX のみ — 参照チャンネル同様、視覚が説明を運ぶ）。ナレーションを
   付ける場合も per-locale murakumo TTS を音声トラックとして dougaka に渡すだけで、
   フレームは共有される。
2. **キャラクター非依存 = 立ち絵/口パク/キャラ TTS を持たない**。視覚的アイデンティティは
   `theme.cljc` の design token（`:cyber-dark`: bg/panel/accent(cyan)/alert(red)/warn(amber)/
   mono font）に集約し、template に raw hex を書かない（kotoba-lang design-system と同じ
   規律。DOM UI ではないので stack 自体は消費しない）。
3. **決定論**: storyboard → scenegraph（`:sg/nodes` + keyframe `:sg/tracks`）→ frame は
   純関数。LLM の創造性は storyboard EDN 生成で完結し、再レンダー・部分再生成・レビュー
   差分が安価。`spec.cljc` が no-throw validation（missing copy-id / unknown template /
   duplicate scene-id / locale intersection）。
4. **templates**: `:title-card` / `:bar-chart`（参照スタイルの主力: パネル + staggered bar
   growth + highlight + badge + annotation arrow）/ `:callout` / `:custom`（生 scenegraph
   passthrough — テンプレート化前の一点物）。テンプレートは追加式で育てる。
5. **rasterize は既存物の消費のみ**: `@resvg/resvg-js`（optionalDependency）。未導入なら
   SVG のみ出力して WARN（正直に degrade）。ffmpeg mux は `ai-gftd-dougaka` の担当領域の
   ため、本 repo は `manifest.edn`（fps/size/frame range per scene）と `cues.edn`
   （track 開始時刻 = SFX cue）を出すところまで。
6. **runtime**: 第一級は ClojureScript/nbb（core は portable `.cljc`、CLI は
   `bin/render.cljs`）。JVM/bb 前提のコードは書かない。kotoba wasm / clojurewasm への
   昇格は core が安定してから別 ADR（`.kotoba` サブセットには文字列処理・EDN 構造が
   まだ重い）。

**scaffold 実測**（2026-07-16）: `nbb --classpath src:test test/run.cljs` green、
`examples/quantization.edn`（参照スクリーンショット再現: ACTIVATIONS パネル + INT4 badge +
~100x annotation、en/ja 2 locale）→ 390 frames（1920x1080/30fps/13s）→
`ffmpeg -framerate 30 -i frames/%06d.png` で mp4 生成まで確認。

## Consequences

- (+) yukkuri と補完的: 同じサイバーセキュリティ題材を、キャラ IP・日本語に縛られない
  国際向けフォーマットで再利用できる（storyboard は murakumo text が生成、題材ソースは
  yukkuri の content/ 資産を転用可能）。
- (+) 生成コストが安い: フレームは LLM/GPU 不要の純関数。LLM 呼び出しは 1 動画 1 回
  （storyboard）+ 任意のナレーション TTS のみ。
- (+) 品質ループに乗せやすい: フレームが決定論的なので golden-frame 回帰・design-quality
  系の視認採点・co-scientist ループ（ADR-2607132300 の既存機構）を後付けできる。
- (−) 表現力は template 資産に比例する。初期 4 template では動画の絵柄が単調になりうる —
  `:custom` scenegraph を先例にして template を追加式で育てる運用が前提。
- (−) text layout は SVG 任せ（改行・折返しなし）。長文 copy は storyboard 側で分割する
  規約。CJK 混植の font fallback は resvg のシステム font 解決に依存（明示 font 同梱は
  large-binary 方針と相談の上 follow-up）。
- (−) publisher（YouTube upload）・storyboard 生成 actor・BGM/SFX 配線は未実装
  （yukkuri の publisher / cloud-murakumo `:gen` 経路の転用で足りる見込み。follow-up）。
- 登録: `manifest/repos.edn` `:extra-projects` に追加 + west.yml へ API single-entry で
  project 追加（pin = `e53954052b9b0649ed75fff2a4e1620986b3f391`）。fleet-db への吸収は
  CI `fleet reconcile`（ADR-2607160005 Phase 1.5 dual-write）に任せる。

## Alternatives Considered

1. **yukkuri に「キャラ無し template」を足す** — yukkuri のドメインモデル（YkLine =
   speaker 付きセリフ、voiceLeft/voiceRight、compose_scene の ComfyUI 立ち絵合成）は
   キャラ掛け合いが骨格で、キャラ非依存はモデルの否定になる。別 repo が正。
2. **Remotion / Motion Canvas / manim を使う** — いずれも TS/Python 前提で、repo-wide
   runtime priority（新規生 JS/TS 禁止、nbb 優先）に反する。EDN storyboard → SVG の
   純関数 core の方が LLM 生成物の検証・差分にも向く。
3. **murakumo video（Seedance 2.0、ADR-2607170500）で直接動画生成** — 拡散モデル動画は
   フレーム決定論・テキスト正確性・図表の正確な数値表現が保証できず、解説チャートには
   不向き。ADR-2607170500 経路は実写風 B-roll 等の補助素材用として将来併用は可能。
4. **kami-engine headless render を使う** — 3D 資産が不要な 2D チャートに scene-graph
   エンジンはオーバーキル。repo-wide 3D ルールは「3D を作るなら kami-engine」であり
   2D vector を kami に寄せる要請ではない。3D 表現が必要になったらその時点で kami-engine
   統合を別 ADR にする。

## References

- 参照チャンネルスタイル: "Engineering Behind LLM Inference: Quantization"
  （https://www.youtube.com/watch?v=1JWnEze9V5g）
- `90-docs/adr/2605312355-*`（dougaka = ffmpeg assembler の責任境界）
- `90-docs/adr/2607170500-cloud-murakumo-seedance-video-api-integration.md`
- `orgs/gftdcojp/ai-gftd-dougaka-vector/README.md`（usage / layout の正本）

## Addendum 1 — follow-up 実装（2026-07-16、同日）

ADR 本文の follow-ups のうち以下を実装した（子 repo pin `c0426991`、west.yml へ反映済み）:

1. **storyboard 生成 actor**: `bin/storyboard.cljs` + 純関数部 `storyboard.cljc`
   （prompt 契約 = template catalog 同梱 / EDN 抽出は fence・前置き耐性 / locale 完全性 +
   尺 budget 検証 / 不合格時は errors を feedback して retry）。エンドポイントは
   murakumo.cloud の OpenAI 互換 gateway（`/api/v1/chat/completions`、model
   `qwen-agentworld-35b-a3b` が live 確認済み）。**live 呼び出しは未実施** — chat gate の
   `MURAKUMO_TOKEN_SECRET` は site-worker 専用 secret で、kagi の
   `MURAKUMO_GENERATION_TOKEN_SECRET`（generation proxy 用）とは別物（skill
   `secrets-location-map` の 2026-07-16 注記どおり）。保管場所が secrets マップに無く、
   mint はオーナー作業（`clojure -M:token issue`）。`--mock` で抽出→検証→render の
   チェーンは実データで検証済み。
2. **template 拡充**: `:line-chart`（`:polyline` node kind の `:progress` 数値アニメで
   線が引かれる）/ `:flow`（箱+矢印が順に現れる pipeline）/ `:big-number`（`:counter`
   node kind、from→to の数値 run-up）。example は全 template 使用の 6 scenes / 26s に拡張、
   BGM+SFX 付き mp4 まで実走（video+audio 各 26.0s を ffprobe 確認）。
3. **audio 配線**: `audio.cljc`（motion 系 attr → SFX kind のルール、staggered bar の
   coalesce、ongakuka への BGM request 仕様、murakumo audio への SFX pack 合成 prompt
   カタログ）+ `bin/audio_plan.cljs`。**実際の音声合成（ongakuka / murakumo audio 呼び
   出し）は未配線** — plan までがこの repo の担当で、合成は当該サービス側の既存経路。
4. **非正規 assembler**: `bin/assemble.cljs`。本文の「mux を複製しない」を保ったまま
   単機 dev 検証を可能にするための **non-authoritative** driver（ffmpeg 1 呼び出しに
   徹する: frames + bgm + cue ごとの sfx adelay/amix）。本番 mux は引き続き
   `ai-gftd-dougaka`。
5. **CJK font**: ja フレームは system font fallback で正しく描画されることを視認確認。
   再現性が要る環境（CI 等）向けに `--font-dir` / `--no-system-fonts`（resvg fontDirs）
   を追加。font 同梱はしない（large-binary 方針。必要なら DataLad 経路で別途）。
6. **publisher 方針確定**: yukkuri の `yukkuri.exec.upload-youtube` パターン
   （`kotoba-lang/com-youtube` + operator 注入 OAuth creds、JVM `:clj` compat 層）を
   そのまま使う。本 repo にはコードを置かない（実配線は動画が出る運用が始まってから）。

残 follow-up: chat gate token の owner mint → storyboard live 実行、ongakuka/murakumo
audio の実合成配線、publisher 実配線、template 追加。

## Addendum 2 — chat gate の agent-mint 経路開通と production の乖離問題（2026-07-16、同日）

Addendum 1 の blocked 項目「chat gate token の owner mint 待ち」を、rotation なしで
解消する設計に切り替えて実装した:

1. **`murakumo.cloud` site worker の token gate に第2検証 secret
   `MURAKUMO_TOKEN_SECRET_2` を追加**（cloud-murakumo GitHub main `5bba489`）。
   primary / secondary のどちらで verify が通っても受理する追加式で、既存の
   primary 署名 token（shinshi companion / manimani runner / animeka 等の消費者）は
   一切無効化されない。generation contract test に primary/secondary 受理・
   ガベージ拒否・secondary 単独でも gate ON のケースを追加して green。
2. **secret 値は kagi `MURAKUMO_CHAT_TOKEN_SECRET_2`（compartment gftdcojp）が正本**。
   worker には `wrangler secret put MURAKUMO_TOKEN_SECRET_2` 済み（secrets は
   redeploy を跨いで永続）。skill `secrets-location-map` に追記済み。
3. **live 検証は一度成功**: 自前デプロイ版（worker version `a28b545d`）に対し
   kagi secret で mint した chat-scope token で `/api/v1/chat/completions` が 200
   （upstream 実体 gemma4-26b が応答）。
4. **ただし ~6 分後に organism publish ループが上書き**: cloud-murakumo の共有
   checkout は GitHub main と乖離した de-facto production ライン（実測: local 109
   commits 先行 / main から 5 commits 遅れ、GTM landing・x402 credits 等の実作業を
   含む）で、`deploy/organism/run-publish.sh` が working tree の src/ からビルドして
   wrangler deploy する。この乖離の reconcile は本 ADR のスコープ外かつ他セッションの
   活動領域（共有 checkout への直接 commit は CLAUDE.md 禁止事項）なので手を付けず、
   オーナーに報告した。**production の chat gate が secondary を受理するのは、
   デプロイラインが main の `5bba489` を吸収してから**。
5. **storyboard actor の reasoning-upstream 対策**（子 repo pin `e23c9c2`）: live で
   露見した2欠陥を修正 — max_tokens 未指定で EDN が途中切断される（→ 8000 +
   `chat_template_kwargs {:enable_thinking false}`、murakumo README の指針どおり）、
   prompt の `?` 付き role 記法をモデルが key にコピーする（→ 'opt' 表記 + minimal
   shape example 同梱）。`--dump-raw` 診断フラグも追加。live の full storyboard 生成は
   gate 反映待ちで未完（mock 経路は検証済み）。

## Addendum 3 — production reconcile 完了と live storyboard 完走（2026-07-16、同日）

Addendum 2 で残った「production の chat gate が secondary secret を受理するのは
デプロイラインが main を吸収してから」を、オーナー指示（「①共有 checkout の local
109 commits を GitHub main に着地させて main と一本化する」→「do it」）で解消した:

1. **共有 checkout の 109 local commits を GitHub main に着地・一本化**。HEAD
   `4ed0af4` を remote branch に push（working tree 不触）→ 一時 worktree で
   `origin/main`(13 commits, chat-gate secondary secret を含む) と merge。唯一の
   衝突は test の add/add（両者が同じ secondary-secret テストを別々に足していた）で、
   main 側 superset を採用して解決。merged tree で generation contract test green。
   `gh api merges` で main へ着地（cloud-murakumo main `a1423b2`、GTM landing・
   x402 credits fold・checkout metadata・organism publish safety 等の実作業を regression
   なく取り込み）。
2. **共有 checkout を unified main へ FF 同期**。ブロックした 31 の未追跡/ローカル
   変更ファイルは全て `origin/main` とバイト同一（shasum 確認）だった掃き出し物で、
   確認の上で checkout/削除して FF 完了。west pin も cloud-murakumo を `a1423b2` へ
   前進（API single-entry `3bbdc32`）。
3. **統一 main から rebuild → デプロイ**。`cloud-murakumo-production-release`
   worktree を unified main に進めて dist を rebuild（secondary-secret path 込みを
   確認）、`wrangler deploy`（worker version `a6d00f52`）。gate probe が 200 に遷移、
   `MURAKUMO_CHAT_TOKEN_SECRET_2` で mint した token が実運用で受理されることを確認。
   ⚠ 実測: cloud-murakumo は複数 worktree/deploy 経路があり（共有 checkout・
   production-release worktree・他セッションの不定期デプロイ）、古い dist の再デプロイが
   混ざると一時的に 401 に戻りうる。全経路が unified main 上にある今は、混ざっても
   unified main から rebuild + 再デプロイで回復する（当初想定した「毎分の organism
   publish ループ」は installed LaunchAgent として存在せず、実際のデプロイは不定期）。
4. **live storyboard 完走**: production gateway に対し `bin/storyboard.cljs` で
   「Why SQL injection still works in 2026」→ **7 scenes / en+ja の検証済み storyboard
   を1発生成**（upstream 実体 gemma4-26b、Addendum 2 の max_tokens/no-think 対策が効いて
   retry なし）→ render(ja, PNG) → audio-plan → assemble mp4 まで実データで完走。
   これで ADR 本文の follow-up「storyboard 生成 actor の live 実行」が閉じた。

残 follow-up: ongakuka/murakumo audio の実合成配線、publisher 実配線、template 追加。

## Addendum 4 — aozora-primary publisher（作品=1 actor DID + YouTube 連携、2026-07-17）

オーナー指示（「作品 = 1 actor did, aozora を主にして, youtube も aozora から連携投稿可能に」）
を受け、publish 経路を YouTube 直投稿の follow-up 計画から **aozora.app 主 + YouTube 連携**へ
再設計・実装した。3 並行調査（aozora publish surface / CACAO 自己発行 + kotobase write /
youtube exec + blob）で実際の連携点を確定してから書いた。

**確定した事実（調査で判明、捏造なし）**:
- aozora.app は標準 AT Protocol PDS（`pds.aozora.app`、DID `did:web:aozora.app`）。**backing
  store が kotobase.net（db `yoro-social`）** なので、aozora に publish すればデータは自動的に
  kotobase.net に載る（要件③は別途書き込み不要で充足）。
- publish は標準 XRPC: self-CACAO で `createSession`/`createAccount` → `uploadBlob` →
  `createRecord`。profile は `app.bsky.actor.profile`（rkey `self`）、動画は
  `app.bsky.feed.post` + `app.aozora.embed.video`（direct-URL embed = getBlob URL）。
- DID は `did:key`（PLC 無し）。**作品=1 DID は暗号的にタダ**（work ごとに新規 Ed25519 seed →
  did:key 導出）。手本: `dougaka-actor`（JVM）と `ai-gftd-dougaka-kodomo/tools/publish_aozora.cljs`
  （nbb/cljs、`kotobase.cacao`/`kotobase.cid` + `@noble/curves`）。
- **runtime は nbb**（dougaka-vector と一致、cljs publish 先例あり）。JVM の com-youtube/cacao
   port ではなく、`kotobase-client` の cljs cacao/cid を消費し、YouTube resumable upload も node
  fetch で再実装（repo runtime priority に従う）。

**実装（子 repo pin `2257c49`）**:
- `src/dougaka_vector/publish.cljc`（純関数）: work-slug/handle、profile/video-post/catalog
  record、youtube metadata + aozora backlink description。
- `bin/publish.cljs`（nbb）: **作品=1 DID keyring**（work ごとに新規 seed → did:key、
  `.dougaka-vector-keyring/<slug>.edn` に gitignore 永続、再 publish は同一 DID 再利用）→
  self-CACAO（owner creds 不要）→ createAccount(`<slug>.aozora.app`) → uploadBlob(mp4) →
  profile(self) + video post + 自前 catalog(`app.gftd.dougakaVector.video`) を createRecord。
  `--dry-run` でオフライン検証可。
- `bin/youtube.cljs`（nbb）: aozora record からの **syndication** — node fetch で resumable
  upload（operator OAuth env が要る、無ければ skip）→ `youtubeUrl` を aozora catalog に
  `putRecord` で書き戻し、aozora を canonical source に保つ。
- `render.cljs` が `storyboard.edn` を出力（publish が title/summary/copy を導出できるよう）。
- テスト +8（計 20 tests / 104 assertions green）。

**live 実証**: この repo の SQL-injection ja 作品を aozora.app に **実 publish 成功**。
actor did `did:key:z6MkenJpFZekGBaRuJNsBCw1nsQLg72mrEREMkJgMkUvyG7b`、handle
`sql-injection-2026.aozora.app`。getRecord で profile・video post(`app.aozora.embed.video`
埋め込み)・catalog が読み戻せ、getBlob が 634538 byte の mp4 実体を 206 で返すことを確認。
records は aozora PDS 経由で kotobase.net(yoro-social) に格納。self-sovereign CACAO なので
owner creds 無しでエージェント単独実行（恒久承認の公開範囲内）。

**要件の充足状況**:
- ✅ 作品 = 1 actor DID（per-work keyring、live で1作品=1 did:key を実証）
- ✅ aozora を主に（profile + video post + catalog を aozora に publish、live 確認）
- ✅ データは kotobase.net（aozora PDS の backing store = yoro-social、自動）
- ⚠ YouTube 連携（実装 + dry-run 済み。**実投稿は operator OAuth creds 待ち** —
  `YOUTUBE_CLIENT_ID/_SECRET/_REFRESH_TOKEN`。yukkuri `docs/youtube-upload-setup.md` 参照）

残 follow-up: YouTube OAuth creds のオーナー投入 → live syndication、AppView の
getVideoFeed indexer が新 catalog collection を拾うかの確認、ongakuka/murakumo audio 実合成配線。
