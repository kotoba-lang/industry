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
