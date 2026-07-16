# ADR-2607120000: local-murakumo — relay.gftd.ai を恒久退役、`/infer/dispatch` は cloud-murakumo（GPU serverless）へ切替（ADR-2607071200 を上書き）

**Status**: accepted (implemented this session)
**Date**: 2026-07-11
**Deciders**: 河崎純真（Jun Kawasaki, CEO） — 明示的指示（本セッション）
**Scope**: `orgs/gftdcojp/local-murakumo`（`/infer/dispatch` ルート・`wrangler.toml`・
`docs/business.md`・`deploy/relay.md` 他 relay デプロイ資材）/ `orgs/gftdcojp/cloud-murakumo`（変更なし・調査のみ）
**Supersedes**: ADR-2607071200（`relay.gftd.ai は operator-run 分散推論 relay。恒久退役ではなく現在ダウン中`）

## Context

ADR-2607071200（2026-07-07）は「relay.gftd.ai は Vultr k3s 退役と無関係の独立
インフラで、運用者が fleet node（main-2）で `cloudflared`/relay プロセスを
再起動すれば復旧する、恒久退役ではなく一時ダウン」と判定し、`/infer/dispatch`
の relay 転送コード自体は維持したまま失敗系（502 明確化）だけを直した。

本セッション（2026-07-11）、オーナー（河崎純真 CEO）から明示的指示があった:

> relay.gftd.ai is deprecated, prune it. Use murakumo-cloud [cloud-murakumo]
> instead.

これは ADR-2607071200 の「relay.gftd.ai は再起動すれば戻る一時ダウンなので
恒久退役として扱わない」という判定を**意図的に上書き**するオーナー決定であり、
本 ADR はその決定を実装する。ADR-2607071200 の判定そのものを覆す・再検討する
ものではない（当時の事実認定は正しかった — relay は確かに独立に再起動可能な
インフラだった）。今回はオーナーがその独立インフラ自体を今後使わない方針に
切り替えた、という新しい決定。

### cloud-murakumo（切替先）の実態調査

`local-murakumo` と兄弟の `orgs/gftdcojp/cloud-murakumo` は、実レンタル
H100/H200/A100 GPU + ネイティブ Apple Silicon Mac-mini フリートを持つ
「Modal 等価」の GPU serverless サービスで、`resources/murakumo.edn`（SSoT）に
`:generation` app として image/model3d/autorig/motion/music/sfx/voice/
render/video の 6+ `:gen` 関数（`:fn/kind :gen`、engine=comfy/trellis/
unirig/skeleton/audio/tts/kami-render）を持つ。ADR-2606272330（gftd.ai 生成
スタジオ）がこの基盤を設計・実装した。

切替の実行可能性を検証するため、以下を読んだ:

- `src/cloud_murakumo/gateway.cljc`（`gen-dispatch`：modality→placement の
  純粋ルーティング）
- `src/cloud_murakumo/dispatch.cljc`（`plan-generation`/`submit-generation`：
  request→placement→（注入された `:execute` での）実行）
- `src/cloud_murakumo/cli.cljc`（`gen`/`dispatch` コマンドは **mock execute**
  で GPU 無しに動く CLI デモ。実 GPU 呼び出しは注入境界の外）
- `src/cloud_murakumo/worker.cljc`（gen ワーカ本体。`-main` は
  `--kotoba-url`/`--queue-file`/`--job-file` でキューを pull する**別プロセス**
  であり、HTTP リクエストに対して同期応答する HTTP サーバではない）
- `src/cloud_murakumo/site_worker.cljs`（cloud-murakumo の**唯一の公開
  Cloudflare Worker**、`murakumo.cloud`。ルートは `/api/v1`（OpenAI/Anthropic
  chat proxy）・`/x402/*`・`/api/store/checkout` のみ。`/v1/gen` やその他の
  生成ジョブ送信エンドポイントは**存在しない**）
- `orgs/gftdcojp/ai-gftd-router`（ADR-2606272330 が計画した製品層。
  `route.cljc`/`job.cljc`/`lexicon.cljc` は純データのみで、wrangler 設定も
  HTTP サーバ実装も無い）
- ADR-2606272330 本文（末尾）: 「未了（本 ADR の Decision 範囲外 / 次の着手）:
  **HTTP server 境界（kotoba `on-http` / ring。`route.cljc` は純データまで）**、
  `studio.gftd.ai`（CLJS/re-frame SPA）、実 GPU/kotoba mesh への transact」
  と明記— 基盤（murakumo.edn の spec・scheduler・ledger・engine アダプタ）は
  実装・テスト済みだが、**外部からジョブを送信できる HTTP エンドポイントは
  この ADR の範囲外のまま未着手**、と当時から明言されている。
- `services/comfy-openai-bridge/README.md`: cloud-murakumo の Mac-mini
  フリート head node（`gad`, Tailscale `100.82.98.110:8189`）上で実際に動く
  OpenAI-images-compatible ComfyUI bridge（2026-07-10 に実画像生成で動作確認
  済み、real）が存在するが、Tailscale CGNAT アドレスは Cloudflare のエッジ
  から到達不能（`local-murakumo` 自身の `infer.murakumo.cloud` トンネルの
  コメントが同じ制約を明記）で、**内部（`clojure -M:dev` 等）からのみ**
  `COMFY_POD_URL` 経由で叩かれる想定。公開ドメイン/トンネルには未接続。

**結論**: cloud-murakumo 側に `/infer/dispatch` から同期 HTTP で呼べる、
今日時点で生きているジョブ送信エンドポイントは**存在しない**。基盤
（配置・課金・スケジューラ）は実装・テスト済みで、実行境界（`:execute` 注入）
も設計されているが、それを外部に晒す HTTP サーバ境界は ADR-2606272330 の
時点から一貫して「次の着手」のまま。

## Decision

1. **relay.gftd.ai への依存を完全に除去する**（オーナー指示のとおり "prune"）。
   `RELAY_URL` バインディング・関連ドキュメント・relay 専用デプロイ資材
   （`deploy/relay.md`、`deploy/com.murakumo.relay.plist.tmpl`、
   `deploy/com.murakumo.relay-tunnel.plist.tmpl`）を削除する。ADR-2607071200
   の「恒久退役として扱わない」判定は本 ADR で明示的に上書きする
   （`:adr/supersedes`）。
2. **`/infer/dispatch` は `CLOUD_MURAKUMO_URL`（新設の Worker binding）
   経由で cloud-murakumo へ転送する**よう `worker.cljs`/`routes.cljc` を
   書き換える。ジョブ形状も cloud-murakumo の実コード
   （`cloud_murakumo.dispatch/plan-generation`）に合わせて
   `{:kind :input}`（旧・relay 向け）から `{:modality :model? :prompt?
   :refs? :params?}`（cloud-murakumo の実引数）へ変える。`routes.cljc`
   （JVM 純粋 mirror）はこの新形状のバリデーション（`:modality` 必須 +
   `:prompt`/`:refs` のいずれか必須）に更新し、`routes_test.cljc` を
   合わせて更新する。
3. **`CLOUD_MURAKUMO_URL` は `wrangler.toml` で意図的に未設定のまま残す。**
   上記調査のとおり cloud-murakumo は今日時点で `/v1/gen` を公開していない
   ため、それらしい URL（例: `https://murakumo.cloud`）を設定すると、
   `murakumo.cloud` の `not_found_handling = "single-page-application"` に
   より **未知パスが 200 + SPA の `index.html` を返し**、ADR-2607071200 が
   relay.gftd.ai の HTML エラーページで踏んだのと同じ「盲目的 `.json()` が
   紛らわしい失敗に化ける」罠を再現しかねない。未設定のままにして
   `/infer/dispatch` を **503 `{:error "no CLOUD_MURAKUMO_URL configured"}`**
   で正直に失敗させる（`RELAY_URL`/`GATEWAY_URL` が未設定時に使っていたのと
   同じ既存イディオム）。cloud-murakumo が実際に `/v1/gen` を公開した時点で
   `CLOUD_MURAKUMO_URL` を設定するだけで動き出す設計にした。
4. **失敗系は `.text()` 先読み→`try/catch` での `JSON.parse`**（`worker.cljs`
   の `GATEWAY_URL`/`/v1/images/generations` ハンドラと同型）を採用する。
   これは ADR-2607071200 が relay 向けに採った `r.ok` チェックより一段階
   強く、「200 だが JSON でない」（=上記 SPA フォールバックのケース）も
   確実に 502 に落とせる。fetch 自体が reject する経路（DNS/TLS）も同じ
   502 系にまとめる。
5. **`/join/browser`（ブラウザタブが relay へ接続してジョブを引く機能）の
   コード中の `relay.gftd.ai` 参照はオーナー承認済みの正直な表示に修正**
   するが、機能自体（enroll + WebSocket dial）は削除しない。cloud-murakumo
   にはブラウザタブを GPU ノードとして扱う概念が無い（実 GPU/Mac-mini
   フリートのみ）ため、この tier に cloud-murakumo 側の代替は無い。
   dial は失敗するだけの無害な操作なので残し、`onerror` メッセージと
   ソースコメントを「一時ダウン、再起動で戻る」から「恒久退役、
   後継バックエンド未定」への正直な表現に修正した。ブラウザ tier 自体の
   要否（削除するか、cloud-murakumo 互換の新設計を持たせるか）は本 ADR の
   決定範囲外 — follow-up。

## Alternatives Considered

- **relay.gftd.ai を残しつつ cloud-murakumo も追加で提供する（並存）**:
  却下。オーナー指示は明示的に "prune it"（relay を除去せよ）であり、本
  セッションの決定はオーナーの優先度判断を再検討せず実装することが役割。
- **`CLOUD_MURAKUMO_URL` に `https://murakumo.cloud` を仮設定しておく**:
  却下。上記のとおり SPA フォールバックが 200+HTML を返し、"正直な失敗"
  の基準（本リポジトリの一貫方針）を満たさない。503「未設定」の方が
  「cloud-murakumo にまだこのエンドポイントが無い」という事実を正確に
  伝える。
- **cloud-murakumo 側に HTTP `/v1/gen` エンドポイントを本 ADR の一部として
  新規実装する**: 却下（スコープ外）。本タスクは `local-murakumo` 側の
  ルーティング切替とドキュメント/デプロイ資材の prune が範囲。
  cloud-murakumo の HTTP server 境界の実装は ADR-2606272330 が既に
  「次の着手」として認識している独立した作業であり、GPU 実行境界・
  課金・認可を伴う実装（雑に足すと二重課金/権限バグを生みやすい）を
  このセッションで急いで足すべきではない。follow-up として明記する。
- **`/join/browser` のブラウザ worker 機能を丸ごと削除する**: 却下（本 ADR の
  決定範囲外）。cloud-murakumo に代替が無いことは確認したが、この tier の
  製品判断（廃止するか、別バックエンドを設計するか）はオーナー確認なしに
  unilaterally 決めるべきではない。正直な失敗表示に留め、follow-up 化した。

## Verification

- JVM test suite: `clojure -M:test` → **37 tests, 267 assertions, 0
  failures/errors**（既存 `routes_test.cljc` の `dispatch-shape` を新形状
  （`:modality`/`:prompt`/`:refs`）に更新 + 追加ケース。他テストは無改造で
  regression 無し）。
- 本番ビルド: `rm -rf .shadow-cljs/builds && npx shadow-cljs release worker
  ui` → 成功（`dist/worker.js` に `CLOUD_MURAKUMO_URL`/`cloud-murakumo` を
  含む新コードが出力されていることを確認）。
- `wrangler deploy` は実行していない（本番稼働中の Worker のため、デプロイ
  判断は PR レビュー後に別途行う — ADR-2607071200 と同じ運用姿勢）。
- cloud-murakumo 側は調査のみで変更していない（`git status` clean を確認）。

## Consequences

- (+) オーナー指示どおり relay.gftd.ai への依存が完全に除去され、
  `wrangler.toml`/`docs/business.md`/`deploy/` にその痕跡（誤解を招く
  "live" 主張含む）が残らない。
- (+) `/infer/dispatch` の転送先が cloud-murakumo（org の長期 GPU 基盤）に
  統一され、二重の生成バックエンド（relay 経由のブラウザ swarm vs.
  cloud-murakumo の実 GPU フリート）を持たない設計になった。
- (+) 失敗系は依然として正直（503 未設定 / 502 到達不能・非 JSON 応答）。
  fake success は作らない、という本リポジトリの一貫方針を維持。
- (−) **`/infer/dispatch` は本 PR の時点では実際には機能しない**
  （cloud-murakumo に `/v1/gen` エンドポイントが無いため、常に 503 を返す）。
  これは relay が「ダウンしているが復旧可能」だった ADR-2607071200 の状況
  と異なり、「後継バックエンドがまだ存在しない」という、より根本的な
  ギャップ。cloud-murakumo が HTTP server 境界を実装するまで、この機能は
  ユーザー向けには停止したままになる — 明示的な follow-up が必要
  （下記）。
- (−) `/join/browser` のブラウザ worker tier は、enroll はできてもジョブを
  引く先が無い（relay 退役・cloud-murakumo に代替なし）ため、実質的に
  機能しない状態が続く。tier の要否は別途オーナー判断が必要。

## Follow-ups（本 ADR の決定範囲外）

1. **cloud-murakumo に `/v1/gen` の HTTP server 境界を実装する**
   （ADR-2606272330 が「次の着手」としていた作業。`cloud_murakumo.dispatch/
   submit-generation` または `enqueue-generation` を Cloudflare Worker
   （`site_worker.cljs` 拡張）または kotoba `on-http` component から呼べる
   ようにする）。これが無い限り `CLOUD_MURAKUMO_URL` は設定できない。
2. `/join/browser` のブラウザ worker tier の製品判断（廃止 / cloud-murakumo
   互換の新設計）。
3. `comfy-openai-bridge`（`gad:8189`、Tailscale 限定）を公開トンネル経由で
   晒すかどうかの判断 — cloud-murakumo の HTTP 境界実装時に、既存の
   `GATEWAY_URL`（`gateway.gftd.ai`、kotoba-lang/murakumo 系、無関係な別系統）
   と混同しないこと。
