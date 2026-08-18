# Cloudflare — アカウント資格情報と Worker/Pages secret

- **Cloudflare**: `wrangler` の OAuth ログインセッション（ブラウザ認証、
  `npx wrangler login`）が実質の認証手段 — このセッションで R2 バケット/KV
  namespace/Worker デプロイ/Custom Domain 追加まで一括して操作できる（1Password
  越しの API トークンではなく、ローカルの wrangler セッションで完結）。
  `CLOUDFLARE_API_TOKEN`（Zone Analytics Read 等の狭いスコープ）は用途別に
  `wrangler secret put` で個別プロジェクトへ投入するもので、これとは別物。
  - ✅ **DNS を読み書きできる資格情報は keychain `gftd.cf` / `API_TOKEN`**
    （2026-08-14 実測。この鍵で 26 レコードを 6 ゾーンに作成、ADR-2608145000）。
    ```bash
    CF="$(security find-generic-password -s gftd.cf -w)"
    curl -sS "https://api.cloudflare.com/client/v4/zones/$ZID/dns_records" \
      -H "Authorization: Bearer $CF"
    ```
    ⚠ **`GET /user/tokens/verify` はこの token で `1000 Invalid API Token` を返すが、
    token は生きている。** verify は user-owned token 用のエンドポイントで、
    account-scoped token では権限不足になる。**verify の失敗を「鍵が死んでいる」と
    読まないこと** — 判定は実際に使う endpoint（`/zones/{id}/dns_records`）で行う。
    2026-08-14 にこれで 5 分溶かした。
  - ⚠ **2026-08-05 版のこの節は「`gftd.cf` は実測で存在しない」と書いていたが誤り。**
    2026-08-14 に `security find-generic-password -s gftd.cf` は PRESENT を返す。
    当時 `-a API_TOKEN` を付けずに引いたか、別ユーザ context で引いた可能性が高い。
    **「NOT FOUND だった」という過去の実測を、現在の不在の証拠として引用しない。**
  - ⚠ **kagi vault はこのセッションから読めない状態がある。** 2026-08-14 実測、
    documented かつ確実に存在する `itonami-mail-age` すら `no such item` を返した。
    つまり **kagi の `no such item` は不在の証拠にならない**（unlock されていない
    ときも同じ文字列を返す）。判定する前に、存在が確実な item で vault の
    到達性そのものを確かめること。
  - （旧記述、参考）kagi の `gftd.cf` / `CLOUDFLARE_DNS_TOKEN` / `CLOUDFLARE_API_TOKEN`
    は no such item。
    - **wrangler の OAuth token では DNS API に読みも書きも通らない。** scope 一覧に
      `zone (read)` はあるが `dns_records` は含まれず、`GET /zones/{id}/dns_records`
      も `POST` も **`10000 Authentication error`** を返す（同じ token で
      `GET /zones?name=` と Pages API は通るので、token 自体は生きている）。
    - **できること／できないこと**: Pages の **custom domain 追加は `pages (write)`
      で通る**（`POST /accounts/{acc}/pages/projects/{proj}/domains` が success）。
      ただし **DNS レコードは自動作成されず** `status: pending` のまま残る。
      つまり詰まるのは「DNS レコードを 1 本作る」ところだけ。
    - ✅ **2026-08-05 解消: オーナーが Global API Key を提供し、kagi に保管した。**
      下記 `CLOUDFLARE_GLOBAL_API_KEY` で DNS の読み書きができる（実測: この鍵で
      `CNAME cloud-itonami → cloud-itonami.pages.dev (proxied)` を作成、ADR-2608057000）。
    - **より良い形（未実施）**: Global Key はアカウント全体・全ゾーンに効き**スコープを
      絞れない**。`Zone.DNS: Edit` だけの token を発行して差し替えるべき。

- **Cloudflare の実キー（kagi、compartment `personal`、`KAGI_HOME=$HOME/.kagi`）**
  — 2026-08-05 にオーナーが提供、kagi へ保管（読み戻し・実 API 呼び出しまで検証済み）:
  - **`CLOUDFLARE_GLOBAL_API_KEY`** — JSON（`email` / `global-api-key` / `account-id`）。
    **アカウント全体・全ゾーン、スコープを絞れない最強の資格情報。** 使うときは
    `X-Auth-Email` + `X-Auth-Key` ヘッダ（Bearer ではない）。DNS 編集はこれで通る。
  - **`CLOUDFLARE_API_TOKEN_AI_GFTD_CDN`** — bucket `ai-gftd-cdn` 用の API token。
  - **`CLOUDFLARE_R2_ACCESS_KEY`** — JSON（`access-key-id` / `secret-access-key` /
    `endpoint` / `bucket`）。S3 互換の R2 アクセス。
  - **`CLOUDFLARE_R2_ACCOUNT_TOKEN`** — R2 Account Token。
  - **`CLAUDE_API_KEY_GFTDCOJP`** — Anthropic API キー（Cloudflare とは無関係だが
    同時に提供されたのでここに置いた）。
  - ⚠ **これら 5 件は 2026-08-05 の会話ログに平文で露出している。ローテーション必須。**
    ローテーション後はこの項目の値だけ差し替えればよい（item 名は変えない）。
  - **1Password には未登録** — `op` の CLI 統合がオフで、`op whoami` が
    `account is not signed in`、`op read` は無応答のままタイムアウトする。
    1Password アプリの 設定 → 開発者 → 「1Password CLI と連携」を有効にすれば
    非対話で書けるようになる。有効化したら登録してこの行を更新する。
  - **kotobase-protocols-worker `WRITE_TOKEN`**（s3/atproto/pinning の write 認可
    Bearer、ADR-2607174500）: Worker secret として投入済み（`wrangler secret list`
    で 2026-08-17 に存在を確認）。operator copy は macOS Keychain
    `service=cf:kotobase-protocols-worker` / `account=WRITE_TOKEN`（2026-07-17 生成）
    に**在り、s3 面では通る**（下記の訂正を読むこと）。
    **⚠ 訂正 2026-08-18: STALE ではない。面を取り違えていた。**（ADR-2608182100）
    同じ Keychain の値で **`s3.kotobase.net` は通る** —— PUT 200 / GET 200 /
    byte 一致 / DELETE 204、壊した token と token 無しは 401。つまりこの copy は
    現行の Worker で**有効**であり、下の 2026-08-17 の記録は
    **`pinning.kotobase.net` という別の面**に対する測定だった。
    今日その pinning 面を同じ token で叩くと **401 ですらなく timeout**
    （curl exit 28 / status 000）で、401 という記録自体ももう再現しない。
    **credential が古いことと、ある面が別の理由で失敗することを、同じ結論に
    畳まないこと。** credential を疑う前に、どの面で測ったかを書く。
    旧記述（下記）は復旧時の参照として残す。

    **旧記述（2026-08-17、pinning 面での測定）: この Keychain copy は STALE。** 取り出した値で
    `POST https://pinning.kotobase.net/pins` を `Authorization: Bearer <値>` で叩くと
    **401 `unauthorized: writes require a bearer token`**。Worker 側に `WRITE_TOKEN`
    は存在するので「未設定」ではなく**値の不一致**である。**なぜ食い違うかは未確定** —
    更新を伴わない rotation が素直な推測だが、推測でしかない。
    旧記述は上に残してある（復旧時の参照。索引の規則 #2）。
    **他の候補を当て推量で試していない**（live auth endpoint への変え撃ちは
    credential brute force であって debug ではない。安全床⑦）。
    復旧するには owner が現行値を供給して Keychain と kagi を更新するか、
    `wrangler secret put WRITE_TOKEN` で rotate する —— ただし rotate は
    既存 consumer（`git annex copy` 経路など）を同時に壊すので、単独で行わない。
  - **kotobase-protocols-worker `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY`**
    （s3.kotobase.net の AWS SigV4 write 認可、ADR-2607176000）: Worker secret
    投入済み（`wrangler secret list` で 2026-08-17 に両方の存在を確認）。
    operator copy は同 Keychain service `cf:kotobase-protocols-worker`
    の account `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY`（2026-07-17 生成）と
    記録されているが、**実測 2026-08-17: どちらの account も取得できない**
    （`security find-generic-password -s cf:kotobase-protocols-worker -a
    S3_ACCESS_KEY_ID -w` が失敗）。
    **同じ service の `WRITE_TOKEN` は同じコマンド形で取得できる**ので、
    keychain のロックや service 名の誤りではなく、**この 2 account が無い**。
    他の account 名を当て推量で試していない（安全床⑦）。
    旧記述は上に残す（復旧時の参照。索引の規則 #2）。
  - **net-babiniku 外部配信の operator gate**（ADR-2607253000）: Pages secret
    `LIVESTREAM_OPERATOR_SECRET`（project `net-babiniku`）の operator copy は
    macOS Keychain `service=net-babiniku:livestream-operator-secret` /
    `account=junkawasaki`（2026-07-25 生成）。同 project の
    `LIVESTREAM_WHIP_URL` は `http://127.0.0.1:8889/babiniku/whip` で**秘密ではない**
    （loopback）。**配信先の YouTube/Twitch stream key はここにもどこにも無い** —
    operator のマシンの `~/.babiniku/mediamtx-bridge.yml`（0600、`npm run
    bridge:config` が生成）だけに存在し、Worker にもブラウザにも repo にも入らない。

### ⚠ Workers for Platforms への secret 投入は `wrangler secret put` ではできない

**`wrangler secret put` に `--dispatch-namespace` は存在しない。** namespace 内の
user Worker には届かない。`.dev.vars` も dispatch-namespace deploy では**拾われ
ない**（実測: アップロードは成功するのに `did: null` のままだった）。

正しい経路は **`wrangler deploy --dispatch-namespace <ns> --secrets-file <file>`**。
成功すると binding が報告される（`env.KOTOBASE_SECRET_KEY ("(hidden)")`）ので、
その行が出ないときは入っていない。ファイルは `.env` 形式（`KEY=value`）、
`.gitignore` 必須、投入後に削除する。残る ~1,196 actor すべてでこの経路を使う。
