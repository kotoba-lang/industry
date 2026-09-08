# Cloudflare — アカウント資格情報と Worker/Pages secret

- **Cloudflare**: `wrangler` の OAuth ログインセッション（ブラウザ認証、
  `npx wrangler login`）が実質の認証手段 — このセッションで R2 バケット/KV
  namespace/Worker デプロイ/Custom Domain 追加まで一括して操作できる（1Password
  越しの API トークンではなく、ローカルの wrangler セッションで完結）。
  `CLOUDFLARE_API_TOKEN`（Zone Analytics Read 等の狭いスコープ）は用途別に
  `wrangler secret put` で個別プロジェクトへ投入するもので、これとは別物。
  - ⛔ **2026-09-08 実測: keychain `gftd.cf` / `API_TOKEN` は失効している。**
    R2 だけでなく `GET /accounts` でも `9109 Invalid access token` を返す。
    上の 2026-08-14 / 2026-08-25 の記述（DNS 26 レコード作成、R2 Data Catalog
    の 2 権限を持つ）は**当時は真だったが現在は通らない**。旧記述は復旧時の
    参照として残す。**この鍵を前提に計画を立てない。**
  - ✅ **2026-09-08 に動いた経路: `CLOUDFLARE_GLOBAL_API_KEY`（kagi、JSON の
    `email` / `global-api-key`）を `X-Auth-Email` + `X-Auth-Key` で送り、
    `POST /accounts/<acct>/r2/temp-access-credentials` で一時鍵を発行する。**
    `parentAccessKeyId` は kagi の `CLOUDFLARE_R2_ACCESS_KEY` の `access-key-id`
    （この item の `secret-access-key` 自体は依然 `SignatureDoesNotMatch`）。
    発行される鍵は **bucket 1 個・object 読み書きのみ・TTL 付き**なので、
    global key を直接使うより露出が小さい。実測: 19,193 object / 4.4 GB を
    `kotobase-graph-database-production` へ投入（ADR-2809081900）。
  - ⚠ **`rclone` はこの一時鍵で無言でハングする**（session token 経路、
    exit 124 = timeout）。**0 バイトかつ stderr 空**なので認証エラーに見えるが
    違う。**`aws` CLI を使う** —— `--region auto --endpoint-url <r2 endpoint>`
    を必ず付ける（region 未指定は `SignatureDoesNotMatch` になる、既出）。
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
  - ⚠ **2026-08-31 実測: この session からは `gftd.cf` が引けない。**
    `security find-generic-password -s gftd.cf -a API_TOKEN -w` も
    `-s gftd.cf -w` も NOT FOUND。旧記述は消さずに残す（下記）——
    2026-08-05「存在しない」→ 2026-08-14「PRESENT」→ 2026-08-31「引けない」と
    3 回振れており、**この item の在否をこの索引から読まないこと**。
    その日に引けるかどうかを、使う直前に自分で確かめる。
    2026-08-31 に Cloudflare を触った実作業（Workers の custom domain 付け替え、
    dispatch namespace への deploy）は **wrangler の OAuth session** で通した
    （`wrangler whoami` → account `cloud-kotoba` = 4da88288…、scope に
    `workers_scripts (write)` / `workers_routes (write)` があり、
    `PUT /accounts/{acc}/workers/domains` まで通る）。DNS ではなく Workers を
    触るならこちらが現役の経路。
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
  - ⚠ **2026-08-25 実測: 下記 5 件のうち `CLOUDFLARE_GLOBAL_API_KEY` と
    `CLOUDFLARE_R2_ACCOUNT_TOKEN` は kagi に無い**（`~/.kagi` と repo 既定の
    両 vault で `no such item`）。この節が「保管した・検証済み」と書いている
    ことと実態が食い違っている。**記述を前提に計画を立てない**（この skill 自身の
    注意 2 の 5 例目・6 例目）。R2 Data Catalog を触るには token 発行が要る。
  - **`CLOUDFLARE_GLOBAL_API_KEY`** — JSON（`email` / `global-api-key` / `account-id`）。
    **アカウント全体・全ゾーン、スコープを絞れない最強の資格情報。** 使うときは
    `X-Auth-Email` + `X-Auth-Key` ヘッダ（Bearer ではない）。DNS 編集はこれで通る。
  - **`CLOUDFLARE_API_TOKEN_AI_GFTD_CDN`** — bucket `ai-gftd-cdn` 用の API token。
  - **`CLOUDFLARE_R2_ACCESS_KEY`** — JSON（`access-key-id` / `secret-access-key` /
    `endpoint` / `bucket`）。S3 互換の R2 アクセス。

    ⚠ **2026-09-06 実測、3 点。この節の記述と実態がまた食い違っている。**

    1. **item は kagi に「在る」**（上の 2026-08-25 の「不在」は解消済み）。JSON の
       4 キーとも読める。**が `secret-access-key` は通らない** —— `--region auto` を
       明示しても `SignatureDoesNotMatch`（ListBuckets で再現、クライアント差ではない）。
       一方 **`access-key-id` は有効**で、temp credential の `parentAccessKeyId` として
       受理される。**鍵ペアの片側だけが古い。**
    2. **⚠⚠ `--region auto` を明示しないと、正しい鍵でも `SignatureDoesNotMatch` になる。**
       この機械の aws config は `ap-northeast-1` を入れており、SigV4 は region を
       署名スコープに含むので、**R2 は正しい鍵を失効しているように見せる**。
       実際 2026-09-06 に、これで一度「鍵が古い」と誤診しかけた（temp credential でも
       同じエラーが出て、region を明示した瞬間に両方の切り分けがついた）。
       `aws ... --region auto --endpoint-url <r2 endpoint>` を常に付ける。
    3. **動く経路は temp credential。** Keychain `gftd.cf` / `API_TOKEN` で
       `POST /accounts/<acct>/r2/temp-access-credentials`
       （`{bucket, parentAccessKeyId, permission:"object-read-write", ttlSeconds}`、
       最大 604800 = 7 日）を叩くと accessKeyId / secretAccessKey / sessionToken が返り、
       **S3 API がそのまま通る**（実測: 20 万バイトと 15.8 MB の PUT→GET がバイト一致）。
       **バケット 1 つ・オブジェクト読み書きのみに絞れるので、長期の口座鍵より安全。**
       同じ token で bucket 作成も通る（`etzhayyim-rasen-genome` を APAC に作成）。
    4. **`GET /user/tokens/verify` はこの token に `Invalid API Token` を返すが、
       R2 の呼び出しは通る。** account-scoped token に user 面の verify を当てても
       答えにならない —— **verify の失敗を「token が死んでいる」と読まない。**
  - **`CLOUDFLARE_R2_ACCOUNT_TOKEN`** — R2 Account Token。**2026-08-25 時点で不在**（上記）。
  - **`CLOUDFLARE_R2_DATA_CATALOG_TOKEN`** — R2 Data Catalog (Iceberg) 用。
    **2026-08-25 に発行して kagi に保管済み**（compartment `personal`）。同時に
    `CLOUDFLARE_R2_ACCESS_KEY`（S3 互換の access-key-id / secret-access-key /
    endpoint、JSON）も同じ発行で得たので保管した。
    ⚠ **どちらもローテーション必要** —— 発行時の値が会話ログに平文で通っている。
    必要な権限は 2 つ: **R2 Data Catalog: Edit** と **Workers R2 Storage: Edit**。
    ✅ **2026-08-25 解消: この item は要らなかった。既存の Keychain
    `gftd.cf` / `API_TOKEN` がこの 2 権限を持っている**（実測: 同じ鍵で
    `create_table` + `append` + 読み戻しが通り、`cloud_itonami.gleif_lei_joined`
    18,930 行と `gleif_lei_closure` 108,368 行を commit した）。
    この節は `gftd.cf` を「DNS を読み書きできる資格情報」としか書いていなかったので、
    R2 を触る用途では見落とされていた。**新しい token を発行する前にこれを試す。**
    kagi 側の `CLOUDFLARE_R2_DATA_CATALOG_TOKEN` は引き続き `no such item`
    （`~/.kagi` で実測、`CLOUDFLARE_R2_ACCESS_KEY` /
    `CLOUDFLARE_API_TOKEN_AI_GFTD_CDN` も同じく不在）。
    実測 2026-08-25: wrangler の OAuth session は catalog の *metadata* 面には
    通る（`GET /v1/config` 200、`create_namespace` 成功）が、**storage 面で 401**
    になり `create_table` が落ちる（catalog 側が R2 を list できない）。
    **metadata が通ることを「使える」と読まない** —— 面が 2 つある。
    使う側: `scripts/datalake-sync.py` が `CF_CATALOG_TOKEN` として読む
    （2026-08-26 に `lei-datalake-sync.py` から改名・一般化。表の一覧は
    `--spec` の JSON が持ち、LEI 用は `scripts/datalake-specs/lei.json`、
    watchlist 用は `scripts/watchlist-datalake-export.cljs` が出力の隣に書く）。
    ⚠ **R2 SQL (`wrangler r2 sql query`) はこの token では 80013 Unauthorized**
    （実測 2026-08-25）。同じ catalog の同じ表を DuckDB の iceberg extension は
    読めるので、**表が無いのではなく R2 SQL が別の権限を要る**。片方の面が
    拒否したことを「catalog が壊れている」と読まないこと。
    **同じ Keychain `gftd.cf`/`API_TOKEN` が第二の bucket でも実測済み**
    （2026-08-28）: `net-kotobase-datalake`（同アカウント、catalog
    namespace `net_kotobase`）— `net-kotobase/commoncrawl-actor` が
    committed page ごとに `net_kotobase.commoncrawl_page` へ 1 tick 1 batch
    append する（`scripts/iceberg_append.py`、`otent` の同名スクリプトを
    vendored）。**1 credential・1 account、bucket は用途/product ごとに分ける**
    のがこの workspace の形（cloud-itonami の観測データ = `cloud-itonami-datalake`、
    net-kotobase の crawl/search データ = `net-kotobase-datalake`）。
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
  - **kotobase-protocols-worker `S3_ISSUER_ROOT`**（tenant 向け s3 credential の導出根、
    ADR-2608182100、2026-08-18 生成）: Worker secret 投入済み（`wrangler secret list` で確認）、
    operator copy は macOS Keychain `service=cf:kotobase-protocols-worker` /
    `account=S3_ISSUER_ROOT`（64 hex）。**kagi には未登録** —— `kagi get` が
    OS-Keychain unlock prompt に非対話で応答できず timeout した（rc=124）。
    これは「無い」ではなく**未測定**であり、owner が対話で実行すれば登録できる。
    **この 1 値から全 tenant の secret が導出できる。** rotate すると発行済みの鍵が
    すべて同時に無効になるので、失効は原則 expiry で行い、rotation は緊急時に限る。
    **同じ値が apex Worker `net-kotobase` にも入っている**（2026-08-18）。片方が発行し
    もう片方が同じ root から検証するので、2 箇所に在るのはこの設計の帰結である
    （AWS SigV4 が共有秘密を要求する以上、この面では公開鍵署名に替えられない）。
    **rotate するときは 2 つ同時に**。片方だけ替えると、発行した鍵が検証側で全部落ちる。
    発行: tenant は `POST https://kotobase.net/api/s3-credentials`（CACAO 認証 +
    `:storage/pin` Grant）、operator は
    `S3_ISSUER_ROOT=... nbb protocols-worker/scripts/issue-s3-credential.cljs <did> [days]`
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
