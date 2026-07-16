# ADR-2607071200: local-murakumo — relay.gftd.ai は operator-run 分散推論 relay（main-2 fleet node）。恒久退役ではなく現在ダウン中 + `/infer/dispatch` の失敗系を明確化

**Status**: superseded by ADR-2607120000 (2026-07-11 — owner directive: "relay.gftd.ai is deprecated, prune it. Use murakumo-cloud instead.")
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/local-murakumo`

## Context

org-wide cleanup セッション中、`local-murakumo`（Cloudflare Worker、script
`local-murakumo`、稼働 custom domain `api.murakumo.cloud` / `app.itonami.cloud`）
の `wrangler.toml` を確認したところ、`RELAY_URL="https://relay.gftd.ai"` に
「live: relay on main-2 via Cloudflare Tunnel」という断定コメントが付いていた。
実際に確認すると:

```
curl -s -o /dev/null -w '%{http_code}' https://relay.gftd.ai/
→ 530
curl -s https://relay.gftd.ai/
→ error code: 1033   (Cloudflare Tunnel: コネクタ未接続)
```

同時期に、この org が依存していた Vultr Kubernetes cluster が 2026-06-24/25 に
完全削除されている（ADR-2606120930「murakumo fleet ステートレス k3s 移行 +
Vultr wind-down」）。relay.gftd.ai がこの同じ退役波の一部（＝恒久的に無くなった
インフラ）なのか、それとも独立した「運用者が再起動すれば戻る一時ダウン」なのかを
切り分ける必要があった。

### relay.gftd.ai の正体

`README.md` / `deploy/relay.md` / `docs/business.md` を読むと、
`local-murakumo` は [kotoba-lang/murakumo](https://github.com/kotoba-lang/murakumo)
（分散 Mac-mini 推論フリート、OSS terminal operator）の**外部公開 API**で、
`/infer/dispatch` はブラウザ/WASM ワーカー swarm へジョブを配る**分散推論
ディスパッチ**の一部。relay 自体は:

- `deploy/relay.md`: 「fleet node で `nbb murakumo infer relay 8091` を起動 →
  `cloudflared tunnel --url http://localhost:8091`（または named tunnel）で
  公開。ジョブは `POST /enqueue` で受け、`RELAY_URL` 経由で
  cloud 側の `/infer/dispatch` から転送される」
- LaunchAgent (`deploy/com.murakumo.relay.plist.tmpl`) は relay プロセス自体の
  自己回復（`KeepAlive`）のみを提供し、`cloudflared` トンネル自体の resident 化
  は記載が無い。
- `wrangler.toml` のコメントにより relay ホストは "main-2"（fleet node の一つ）
  であることが分かる。

つまり relay.gftd.ai は **Vultr VKE / k3s とは無関係の別インフラ**で、運用者
（Jun）が自分の fleet node 上で手動/LaunchAgent 起動するプロセス + Cloudflare
Tunnel。ADR-2606120930 の対象（`vpn.gftd.ai` / litellm 推論 `keiei-llm` / ipfs
origin / atproto PDS + pds-firehose-bridge / `mitama-udf` 系）に
relay.gftd.ai への言及は無い。**設計上、常時 100% up が保証されたマネージド
サービスではなく、端末のスリープ/再起動や `cloudflared` の未起動で切れうる
intermittent なコンポーネント。** よって「恒久的に廃止されたインフラ」ではなく
「現在ダウンしているが運用者の再起動で戻る」ケースと判定した。

### 呼び出し側の失敗系

`local_murakumo.worker/route` の `/infer/dispatch`（`src/local_murakumo/worker.cljs`）
は、relay が不通の時に何が起きるかを見た:

```clojure
;; before
(-> (js/fetch (str relay "/enqueue") ...)
    (.then (fn [r] (.json r)))
    (.then (fn [j] {:status 201 :body ...})))
```

relay.gftd.ai は Cloudflare の zone に proxied されているため、tunnel が
切れていても `js/fetch` は **reject せず**、Cloudflare が返す HTML エラー
ページ（HTTP 530, body `error code: 1033`）付きの `Response` で **resolve**
する。それを無条件に `.json()` していたため `SyntaxError` が投げられ、
fetch-handler のトップレベル `.catch`（`{:status 500 :body {:error (str e)}}`）
に飲み込まれて、**relay が落ちているという事実を隠す紛らわしい 500**
（実質「JSON parse に失敗した」としか見えないエラー）を返していた。ハング・
無限リトライ・サイレントな成功の詐称ではなかったが、「正直で明確な失敗」の
基準（本セッションの一貫方針）は満たしていなかった。

## Decision

1. **relay.gftd.ai を恒久退役として扱わず、恒久退役コードパスは書かない。**
   `/infer/dispatch` フィーチャー自体（生きた分散推論ディスパッチ機能）は
   変更・削除しない。偽の代替 backend URL も作らない。
2. **`wrangler.toml` の断定コメントを現況ベースに修正**: 「live」ではなく
   「main-2 上の運用者プロセス。Vultr wind-down とは無関係。2026-07-07 時点
   ダウン中（HTTP 530/1033）、`cloudflared`/relay の再起動で復旧」と明記。
   `docs/business.md` の Live surfaces 節 / Configuration テーブルの
   `RELAY_URL` 行も同様に補足した。
3. **`worker.cljs` の `/infer/dispatch` の失敗系を明確化**: fetch 応答の
   `r.ok` を見て、非 2xx なら body を `.text()` で読み `502` +
   `{:error "relay unreachable or erroring" :relay <url>
   :upstream-status <n> :upstream-body <先頭300字>}` を返す。fetch 自体が
   reject する経路（DNS/TLS 障害）も明示的な `.catch` で同じ 502 系に
   まとめた。

```clojure
;; after
(-> (js/fetch (str relay "/enqueue") ...)
    (.then (fn [r]
             (if (.-ok r)
               (-> (.json r) (.then (fn [j] {:status 201 :body ...})))
               (-> (.text r) (.then (fn [t] {:status 502 :body {...}}))))))
    (.catch (fn [e] {:status 502 :body {:error "relay unreachable" ...}}))))
```

## Alternatives Considered

- **relay.gftd.ai を恒久退役として `RELAY_URL` を外し `/infer/dispatch` を
  無効化する**: 却下。Vultr k3s wind-down と違い、この relay の退役判断は
  一切記録されておらず、実際には運用者が fleet node 上で tunnel を再起動
  すれば復旧するインフラ。恒久ダウンと断定して機能ごと落とすのは事実誤認。
- **ダミーの代替 backend URL を `RELAY_URL` に設定する**: 却下。本セッション
  全体の方針（fake success を作らない）に反する。relay 不在を隠さず、
  明確な 502 で伝える方が正しい。
- **コメントだけ直し、コードの失敗系はそのまま（JSON parse 例外 → top-level
  catch → 500）にする**: 却下。この 500 は真因（relay が落ちている）を隠し
  Worker 自身のバグに見える。ADR の前提「正直で明確な失敗」を満たすため
  コードも合わせて直した。

## Verification

- `curl -s -o /dev/null -w '%{http_code}' https://relay.gftd.ai/` → `530`
  （本 ADR 起票直前に再確認）
- JVM test suite: `clojure -M:test` → **37 tests, 258 assertions, 0
  failures/errors**（既存の pure route 実装 `routes.cljc` は無変更・
  regression 無し）
- 本番ビルド: `rm -rf .shadow-cljs/builds && npx shadow-cljs release worker
  ui` → **成功**（`dist/worker.js` に新しい 502 分岐のコードが含まれることを
  確認）。既存の `xrpc.cljs` 由来の 2 件の infer-warning のみ（本変更とは
  無関係、既存）。
- `wrangler deploy` は実行していない（本番稼働中の Worker のため、デプロイ
  判断は PR レビュー後に別途行う）。

## Consequences

- (+) relay の実際の運用モデル（main-2 の運用者プロセス、Vultr とは無関係）
  が `wrangler.toml` / `docs/business.md` に明文化され、次に触る人が誤って
  「恒久退役」と誤認しない。
- (+) `/infer/dispatch` は relay 不通時に 502 + relay URL + upstream status
  を返すようになり、呼び出し側が「自分たちのバグ」ではなく「relay 側が
  落ちている」と正しく診断できる。
- (−) relay.gftd.ai 自体の復旧は本 PR のスコープ外 — main-2 の運用者
  （Jun）が実機で `cloudflared`/relay プロセスを再起動する必要がある。
  コード変更だけでは relay は上がらない。
- (−) `worker.cljs` は cljs 専用ファイルで JVM test suite ではカバーされ
  ない（`routes.cljc` の pure mirror のみテスト対象）。今回の 502 分岐に
  対する自動テストは無く、shadow-cljs release のビルド成功のみで検証した
  （cljs 側の unit test harness 自体が本リポジトリに未整備 — follow-up）。
