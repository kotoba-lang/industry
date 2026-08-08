# Cloudflare — アカウント資格情報と Worker/Pages secret

- **Cloudflare**: `wrangler` の OAuth ログインセッション（ブラウザ認証、
  `npx wrangler login`）が実質の認証手段 — このセッションで R2 バケット/KV
  namespace/Worker デプロイ/Custom Domain 追加まで一括して操作できる（1Password
  越しの API トークンではなく、ローカルの wrangler セッションで完結）。
  `CLOUDFLARE_API_TOKEN`（Zone Analytics Read 等の狭いスコープ）は用途別に
  `wrangler secret put` で個別プロジェクトへ投入するもので、これとは別物。
  - ⚠ **DNS レコードを書ける資格情報は、2026-08-05 時点でこの環境から到達できない。**
    以前ここには「DNS 編集用の zone token は keychain `gftd.cf`（DNS read/write
    確認済み 2026-07-17）」と書いてあったが、**実測でその item は存在しない**
    （`security find-generic-password -s gftd.cf` → NOT FOUND、kagi の
    `gftd.cf` / `CLOUDFLARE_DNS_TOKEN` / `CLOUDFLARE_API_TOKEN` も no such item）。
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
  - **kotobase-protocols-worker `WRITE_TOKEN`**（s3/atproto/git.kotobase.net の
    write 認可 Bearer、ADR-2607174500）: Worker secret として投入済み。operator
    copy は macOS Keychain `service=cf:kotobase-protocols-worker` /
    `account=WRITE_TOKEN`（2026-07-17 生成）。
  - **kotobase-protocols-worker `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY`**
    （s3.kotobase.net の AWS SigV4 write 認可、ADR-2607176000）: Worker secret
    投入済み。operator copy は同 Keychain service `cf:kotobase-protocols-worker`
    の account `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY`（2026-07-17 生成）。
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
