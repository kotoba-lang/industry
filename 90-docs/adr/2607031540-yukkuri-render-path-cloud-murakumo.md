# ADR-2607031540: ai-gftd-yukkuri のレンダリング起動経路を k8s(mitama-udf)から cloud-murakumo CLI + kotobase.net へ移行

Status: Accepted
Date: 2026-07-03

## Context

`ai-gftd-yukkuri` の `CLAUDE.md` / `docs/pachinko-youtube-runbook.md.edn` は、動画生成の
実行(LangGraph `produce` super-graph: compose → generate_script →
[synthesize_voice ‖ generate_visual ‖ generate_character ‖ generate_bgm] →
compose_scene → render_video → review_video → translate_video → upload_youtube)を
以下の経路で起動する前提のまま更新されていなかった:

```bash
kubectl config use-context vke-31d5f7dc-...
kubectl exec deploy/lg-yukkuri -n mitama-udf -- curl -fsS -X POST localhost:8000/runs \
  -d '{"assistant_id":"produce","input":{...}}'
```

本日(2026-07-03)、この経路を実際に検証したところ:

- `kubectl config get-contexts` → `vke-31d5f7dc-...` は登録されているが、
  `kubectl get deploy -n mitama-udf` は `dial tcp 127.0.0.1:6443: connect: connection refused`
  で失敗。**k8s クラスタそのものが到達不能**(オーナー確認: 「k8s pod は deprecated, prune」)。
- `docs/pachinko-youtube-runbook.md.edn` が引用する `comfyui.gftd.ai` /
  `murakumo.gftd.ai` は HTTP 200 を返すが、**オーナーいわく旧ドメインで、現在は
  `cloud-murakumo` = `murakumo.cloud` に統合済み**。

並行して `orgs/gftdcojp/cloud-murakumo`(ADR-2606272300 の実装、closed 2026-06-28)を
調査したところ、Modal 等価の分散 GPU serverless サービスとして既に稼働しており、
`README.md` の "Generation Backend" セクションが yukkuri の生成ニーズ(image/video/
voice/music/sfx/3d)をそのまま `:apps :generation` の `:gen` function として持っている
ことを確認した:

| yukkuri actor | cloud-murakumo `:gen` function | engine | 出力 |
|---|---|---|---|
| illustrator(背景/挿絵) | `:image` | `:comfy` | png |
| character/composer_scene の描画段 | `:render` | `:kami-render` | png/mp4 |
| voiceLeft/voiceRight(TTS) | `:voice` | `:tts` | wav |
| composer(BGM) | `:music` | `:audio` | wav |
| sfx | `:sfx` | `:audio` | wav |
| renderer(最終 mp4) | `:video` | `:kami-render` | mp4 |

## 実施した検証(read-only 〜 mock 実行、GPU 確保なし)

`orgs/gftdcojp/cloud-murakumo` で以下をローカル実行し、正常動作を確認した(すべて
`clj` 経由、GPU 予約を伴う `:deploy`/`:scale-up` の財務承認 gate には触れていない):

```bash
clj -M:doctor
# => {:fleet/nodes 5 :apps 4 :functions 11 :fleet/total-gpus 36 :ready? true}

clj -M:cli models
# => image(comfy: flux.1-dev/qwen-image/sdxl-turbo) / voice(tts: cosyvoice2/kokoro) /
#    video(kami-render: kami-video/svd/wan-video) / music・sfx(audio) / 3d(trellis) の
#    全カタログを確認

clj -M:cli gen voice cosyvoice2
# => node sora (l4 x1) へ placement、artifacts: bafy970457102.wav、
#    gpu-seconds=60 cost=$0.0133(financial gate)

clj -M:cli gen image flux.1-dev
# => node sora (l4 x1)、artifacts: bafy817139188.png、cost=$0.0133

clj -M:cli dispatch voice cosyvoice2 "アサーションは主張じゃなくて署名つきの封筒"
# => dispatch: :done、worker command / placement / artifact CID を出力
```

scheduler(auction placement)・ledger(gpu-seconds/cost 計上)・dispatch boundary は
すべて実コードで動作した。ただし `cmd-gen`/`cmd-dispatch` は CLI 内蔵の **mock
execute**(`cli.cljc` にインラインの `{:execute (fn [inv] {:outputs [...] ...})}`)を
使っており、実際の GPU 推論(ComfyUI/kami-render/TTS の実行)は行っていない。

**実 GPU バックエンドへの到達は今回できなかった**:
`resources/murakumo.edn` の fleet node(`asagi`/`kurenai`/`midori`/`ai`)は
`:reach :native`(mesh 内部専用)、`sora` のみ `:reach :http` だが、
`gateway.cljc` が想定するホスト名 `sora.edge.murakumo.cloud` は本環境から
名前解決できない(NXDOMAIN)。`murakumo.cloud` 自体は公開サイト(SPA)のみで、
`/health`・`/v1/models` にも同じ HTML シェルが返り、外部から叩ける JSON API は
確認できなかった。`clojure -M:worker --backend-url <url>` /
`COMFY_URL` / `KAMI_RENDER_URL` / `MURAKUMO_BACKEND_URL` のいずれかで実 URL を
与えれば実行できる設計だが、その URL を本環境からは入手できていない。

## Decision

1. **k8s(`mitama-udf` / `lg-yukkuri` deploy)経由の起動を廃止**として文書化する。
   `kubectl exec deploy/lg-yukkuri ...` はもう機能しない(クラスタ到達不能、
   2026-07-03 確認)。新規に同じ経路を前提にした手順を書かない。
2. **単発の生成ジョブは `cloud-murakumo` CLI を正経路とする**:
   `clj -M:cli gen <modality> [model]`(デモ/検証)、実運用は
   `clojure -M:worker --engine <comfy|tts|audio|kami-render|trellis> --modality <..>
   --backend-url <url or env>` で `cloud-murakumo.worker/default-execute` →
   `cloud-murakumo.executor/execute` が実 runtime(ComfyUI/kami-render/TTS/TRELLIS)
   を叩く。
3. **分散キュー/永続化は「kotobase CLI」という別バイナリではなく、
   `cloud-murakumo.queue-kotoba` アダプタ経由の kotobase.net XRPC 呼び出しを指す**
   (`ai.gftd.apps.kotobase.datomic.{transact,q}`)。実運用のワーカ起動は
   `clojure -M:worker --engine <engine> --kotoba-url https://kotobase.net
   --kotoba-graph <graph> --loop true` で、kotoba グラフからジョブを claim して
   実行し、`:done`/`:failed` を書き戻す。standalone な `kotobase` コマンドは
   存在しない(調査済み、`kotoba-lang/kotobase-client`・`kotobase-engine` に
   CLI 言及なし)。
4. `ai.gftd.apps.yukkuri.compose`/`render` 等の XRPC 自体(video/scene/line の
   record 作成)は変更しない——正面玄関は維持し、**その先の実生成呼び出し実装
   (illustrator/voiceLeft/voiceRight/composer/renderer アクター)を、旧・
   ComfyUI直叩き/VOICEVOX in-cluster から `cloud-murakumo` の `:gen` function
   呼び出しへ差し替える**、という移行の方向性を示す。実装の差し替え自体は
   本 ADR の範囲外(follow-up)。

## Consequences

- `orgs/gftdcojp/ai-gftd-yukkuri/CLAUDE.md` および
  `docs/pachinko-youtube-runbook.md.edn` の k8s 起動手順は **defunct** として
  注記する(本 ADR へのリンク)。
- 実際の音声/画像/動画アーティファクトを本セッションで生成することはできな
  かった(到達可能な実 GPU backend URL が無いため)。「レンダリングを試す」
  という目的に対しては、**新しい正しい起動経路(cloud-murakumo CLI)を実コード
  で検証し、mock 実行では成功した**ところまでが到達点。
- Follow-up(未着手、本 ADR の範囲外):
  - 実 backend URL(ComfyUI/kami-render/TTS worker)の運用者からの提供、または
    fleet 内部ネットワークからの実行。
  - yukkuri actor 実装(illustrator/voiceLeft/voiceRight/composer/renderer)を
    `cloud-murakumo.gen`/`worker` 呼び出しへ実際に差し替える実装 PR。
  - `gateway.cljc` の `*.edge.murakumo.cloud` DNS が未整備(NXDOMAIN)である点の
    運用側フォロー。
