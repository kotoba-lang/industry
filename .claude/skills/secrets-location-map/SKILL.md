---
name: secrets-location-map
description: Index of where secrets (Backblaze B2, Cloudflare, kagi, 1Password/Keychain) live for this workspace, so you don't have to search 1Password from scratch every time. Never contains actual secret values, only vault/item/service references. Use when you need to find or reference a credential's storage location.
---

## 秘密情報の保管場所マップ（1Password / Keychain — 値は書かない、参照先だけ）

**このファイルに秘密情報の値そのものを書いてはいけない。** 書いてよいのは
「どの vault のどの item に何が入っているか」という参照先だけ（`op://` パスや
Keychain の service 名と同じ扱い）。実値は `op read` / `bin/kagi get` /
`security find-generic-password` で都度取得する。このセクションは「B2 の鍵は
どこ？」を毎回 1Password 内を検索し直す手間を省くための索引 — 見つけたら
追記していく（網羅は目指さない、都度育てる）。

- **Backblaze B2（複数の鍵が並存 — 用途で使い分ける。同じ鍵を使い回さない）**:
  - `com-junkawasaki.b2/annex`（1Password `gftdcojp` vault）— `manifest/repos.edn`
    の `:b2 :credentials` が参照する M365 archive 用（bucket:
    `gftdcojp-m365-annex`）。Keychain 側ミラーは `security add-generic-password
    -s b2:gftdcojp-m365-annex`（`scripts/b2-creds.cljs` が解決）。
  - `gftd.b2/*`（1Password `gftdcojp` vault、フィールド分割: `BUCKET_NAME` /
    `ENDPOINT` / `ENDPOINT_URL` / `REGION` / `APPLICATION_KEY_ID` /
    `ACCESS_KEY_ID` / `SECRET_ACCESS_KEY`）— bucket `ai-gftd-cdn` 専用。
    ⚠ **この item にかつて存在した `DATASETS_KEY_ID`/`DATASETS_APPLICATION_KEY`
    （bucket `ai-gftd-datasets` 用）は現存しない。** Keychain service `gftd.b2`
    の同名 account も無い。後述の `ai-gftd-datasets.b2_annex` を使うこと。
  - **bucket `ai-gftd-datasets` 専用キー — 正本は kagi、item
    `ai-gftd-datasets-b2-annex`（compartment `gftdcojp`）**。値は 1 つの JSON
    （`{"key-id","app-key","bucket","endpoint"}`）で、取り違え防止のため
    key-id と app-key を同一 item にまとめてある。取得は
    `orgs/kotoba-lang/kagi/bin/kagi get ai-gftd-datasets-b2-annex`。
    capabilities は listBuckets/listFiles/readFiles/writeFiles/deleteFiles で
    **このバケットのみにスコープ済み**（2026-07-25 に Master Key で発行）。
    - **Keychain ミラー**（非対話ローカル）: service `b2:ai-gftd-datasets`、
      account=key id / password=app key（`scripts/b2-creds.cljs` と同じ
      combined 形式）。
    - **1Password には入れていない。** `op` がこの環境で
      `account is not signed in` / `op item create` の authorization timeout に
      なり、非対話で書けなかったため。CLAUDE.md も新規 secret は kagi を正と
      する方針なので、kagi を正本として運用する（1Password に移したい場合は
      オーナーが手動で作成し、ここを更新する）。
    - **何がここにあるか**: SHIRO & PICO の制作資産 9.67GB / 297 objects
      （prefix `ghosthacker-shiropico/`: ep01-07 フルエピソード×11言語、
      ep08-12 ja、ep-scenes 178枚、bgm、panels、ep01 motion comic）。
      在庫と drift 検証は
      `orgs/gftdcojp/ai-gftd-ghosthacker-shiropico/tools/b2_catalog.py`
      （`--verify` は kagi/Keychain/env のどれからでも鍵を解決できる）。
      同じ実体は annex 化されて `gftdcojp-m365-annex` にも trusted copy がある
      （ADR-2607252000 ledger seq 86）。
    - **なぜこの項目が重要か**: この索引に `ai-gftd-datasets` の鍵が載って
      いなかったため、2026-07-25 の調査が「どの現存鍵からも到達できない」と
      誤判定し、**実際には無事だった ep01 の資産を一度 lost と結論した**
      （ADR-2607252000 → 同 ledger seq 86 で訂正）。資格情報が見つからない時に
      「データが無い」と結論する前に、必ずこの索引と下記 Master Key を確認する。
  - **`BACKBLAZE 260225 application keys`（1Password `gftdcojp` vault）— 1
    item に複数バケット分のスコープ付きキー + アカウント全体の
    Master Application Key が同居**:
    - `BACKBLAZE_CDN_BUCKET_KEY_ID`/`_KEY`/`_KEY_NAME` — `260225-ai-gftd-cdn`
    - `BACKBLAZE_QUICKWIT_BUCKET_KEY_ID`/`_KEY`/`_KEY_NAME` —
      `260225-ai-gftd-quickwit`
    - `BACKBLAZE_NATS_BUCKET_KEY_ID`/`_KEY`/`_KEY_NAME` +
      `BACKBLAZE_NATS_BUCKET_NAME`/`_S3_REGION`/`_S3_ENDPOINT` —
      `ai-gftd-nats`
    - **`260421-BACKBLAZE_MASTER_KEY_ID`/`260421-BACKBLAZE_MASTER_KEY`**
      （フィールド名先頭の `260421-` が item 内の識別プレフィックス）—
      **アカウント全体の Master Application Key**。`b2_create_bucket`/
      `b2_create_key`（新しい bucket や、その bucket だけにスコープした
      application key を作る）に使えるのはこれだけ — 上記の他のキーは全部
      特定 bucket にスコープ済みで、新規 bucket/key の発行はできない。
      新しい用途（新規プロジェクトの testnet 等）向けに B2 リソースを
      プログラム的に用意したい時は、まずこの Master Key の所在を確認する
      （毎回 1Password 内を探索し直さない）。
  - `Backblaze`（1Password `Private` vault、2 件）— 個人用途、org のプロジェクト
    には使わない。
- **fal.ai API キー（hosted 生成モデル — Seedance 2.0 等の video / 3D / music / voice）**:
  - **正本 = kagi item `seedance-key`（compartment `personal`）**。取得は
    `orgs/kotoba-lang/kagi/bin/kagi get seedance-key`。
  - 使うときは値を直接扱わず
    `nbb scripts/provision-seedance-key.cljs run -- <cmd>`（`orgs/network-awai/cloud-murakumo`）
    経由にする。`check` / `verify`（**課金せずに** fal 側で有効性だけ確認）も同スクリプト。
  - **live の消費先**: gad の `/etc/murakumo-generation.env`（mode 600 root、
    `SEEDANCE_API_KEY=`）→ systemd `murakumo-generation.service`。ここに無いと
    hosted video model は `/healthz` の `videoModels` に出ず admission でも弾かれる
    （fail closed。ADR-2608031500）。
  - ⚠ **`resources/murakumo.edn` がかつて指していた `op://gftd/cloud-murakumo/SEEDANCE_API_KEY`
    は実在しない**（"gftd" という vault がこのアカウントに無い）。2026-08-03 に kagi 参照へ
    差し替え済み。古い `op://gftd/...` 形式の参照を見かけたら同様に疑うこと。
  - ⚠ **`op item get` はこの環境で無言でタイムアウトする**（rc=124、出力なし）。これを
    「該当なし」と読むと**不在の誤判定**になる。1Password を引くときは `op read`
    （`op://<vault>/<item>/<field>`、エラーメッセージが item/field の存在を区別して返す）を
    使い、`op vault list` で vault 名を先に確認する。非 ASCII 名の vault（`純真個人_*`）は
    `op://` 参照に使えないので **vault ID** で引く。
- **GoDaddy Domains API（DNS レコード書き込み）**:
  - **正本 = 1Password `gftdcojp` vault / item `gftd.godaddy`**
    - `op://gftdcojp/gftd.godaddy/GODADDY_API_KEY`
    - `op://gftdcojp/gftd.godaddy/GODADDY_API_SECRET`
    - `hostname=https://api.godaddy.com`
  - 用途: `gftd.co.jp` の DNS。**NS が `ns67/ns68.domaincontrol.com`
    ＝ GoDaddy がゾーンをホストしているので、keychain の Cloudflare zone
    token（`gftd.cf`）ではこのゾーンに書けない。**
  - 消費側: `cloud-itonami.dns-provider`（`GODADDY_API_KEY` /
    `GODADDY_API_SECRET` を env で受ける。secret に触れる唯一の場所）。
    検証ハーネス: `clojure -M:dns-verify gftd.co.jp [--execute]`。
  - **`op run` は使わない — interactive auth timeout に当たる**（本 repo 既知）。
    `op read` で環境変数に注入する。
  - **`op` の "account is not signed in" は行き止まりではない**（2026-07-26 実測）:
    `op signin --account my.1password.com --raw` が 1Password デスクトップ
    アプリ統合（Touch ID）で非対話に通り、その後 `op read` / `op item create
    --template <file>` が動く。**セッションは数分で切れる**ので、長い作業では
    `op whoami` で確認して signin し直す — 切れたまま走ると値が空になり、
    呼び出し側が黙って mock にフォールバックすることがある（実際に起きた）。
    item 作成時は値を argv に載せない（`ps` 露出）。テンプレート JSON を
    scratchpad に書いて `--template` で渡し、直後に削除する。
  - OTE（テスト）キーは `api.ote-godaddy.com` 専用で本番ゾーンには書けない。
    また GoDaddy は DNS 書き込み API を保有ドメイン数の少ないアカウントに
    対して 403 で拒否することがある。
- **Resend（transactional email / smtp.kotobase.net）**:
  - **正本 = 1Password `gftdcojp` vault / item `gftd.resend`**
    - `op://gftdcojp/gftd.resend/credential`（API Credential の credential 欄）
    - フィールド: `username=API_KEY`、`hostname=https://api.resend.com`
    - 用途: Worker secret `RESEND_API_KEY`（`net-kotobase` / legacy mailer）、
      ローカル CLI、domain verify（`mail.kotobase.net` 等）
    - 投入スクリプト: `nbb scripts/provision-resend-1password.cljs`
      （Keychain `gftd.resend`/`API_KEY` → op item。`--update` で上書き）
  - **Keychain ミラー**（非対話ローカル）: `service=gftd.resend` /
    `account=API_KEY` — 1Password が biometric timeout のときのフォールバック。
    新規取得の正経路は op; keychain は mirror 扱い。
  - **関連ドメイン（Resend account 側）**: `email.gftd.ai`（受信可）、
    `mail.kotobase.net`（smtp.kotobase.net 用、domain id
    `a95782a1-dcef-4e3c-bf1b-71c865423253`）、`etzhayyim.com`、
    `mail.itonami.cloud`、`gftd.ai`、`email.gftd.co.jp`
  - **Worker 投入**: `op read op://gftdcojp/gftd.resend/credential | wrangler secret put RESEND_API_KEY --name net-kotobase`
  - **MAIL_INGEST_TOKEN**（`mail.ingestInbound` service-auth）: keychain
    `gftd.kotobase` / `MAIL_INGEST_TOKEN`（2026-07-17 生成・net-kotobase secret
    投入済み）。1Password へも載せる場合は item `gftd.kotobase` に同名フィールド。
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
    - **オーナー作業**: ダッシュボードで足すか、`Zone.DNS: Edit` を持つ API token を
      発行して kagi/Keychain に保管し、ここを更新する。実例（ADR-2608057000）:
      `CNAME cloud-itonami → cloud-itonami.pages.dev (proxied)`。
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
- **Radicle node passphrase（COB 署名鍵の unlock）**: kagi compartment `personal`
  - `radicle-node-passphrase` — **この Mac（main-2、alias `com-junkawasaki`、
    `did:key:z6Mkud1DguEntg5EBhsfiHNJJBs8Qiw39x5iRgSCfH3cAuin`）**の node 鍵。
    元は macOS Keychain の service `radicle` にあり、2026-07-26 に kagi へ写した。
  - `radicle-seed-gad-passphrase` — 常時稼働 seed **gad**（alias `seed-gad`、
    `did:key:z6MkmUNjE8mrWx7d1NVnYCWZFokoTMaPNxmubf8RpBfAu8Me`）の node 鍵。
    実体は gad の `~/.config/radicle-node.env`（0600、systemd の
    `EnvironmentFile=`）にあり、同日 kagi へ写した。
  - **なぜ要るか**: Radicle の COB（issue / patch）は**署名する**ので、node が
    動いているだけでは書けない。gad に ssh-agent は無いため passphrase 経路が
    唯一の unlock 手段で、fleet-ci の Radicle 反映（`scripts/fleet-ci/tick.cljs`
    の `:rad`）はこの item を読む。
  - ADR-2607252200 はこの置き場所を決めていたが**実際には置かれておらず**、
    両方とも `no such item` だった（ADR-2607259600 の Not done にも
    「secrets-location-map に未記載」として残っていた）。両方とも解消済み。
  - **alias `junkawasaki`（`did:key:z6MkpPKisDoVCDsunNZTtX1eEErH8tcdeNpXrVVfDRkhsWEk`）
    の passphrase は kagi にも Keychain にも無い（2026-07-29 確認）。**
    この identity は「到達できない別端末（25mbair）」ではなく、**この Mac の
    `~/.radicle` に現に存在する**（`rad self` が上記 DID を返す）。前の記述は
    到達不能を理由に未記載としていたが、端末の問題ではなく単に保管されていない。
    実測: `rad auth </dev/null` →
    `A passphrase is required to read your Radicle key`、
    `security find-generic-password -s radicle` → 該当なし、
    kagi の `radicle-node-passphrase` は**別 DID**（`z6Mkud1…`、main-2）のもの。
  - **これが実害を出している**: yabai の CT watch は毎時 GitHub と radicle の
    両方へ push するが、鍵が ssh-agent に無いため radicle 側は
    `Radicle key … is not registered; run rad auth` で失敗し続け、
    rad の main は `77be244` で GitHub `674fa52` から 4 commits 遅れている
    （ADR-2607283100）。**オーナーが `rad auth` を実行して passphrase を
    kagi compartment `personal` に `radicle-junkawasaki-passphrase` として
    保存すれば、以後 agent 側で非対話に unlock できる。** 安全床①により
    agent は passphrase を推測しない・自分で入力しない。
- **kagi（`kotoba-lang/kagi`）**: net-kotobase / kotoba-lang 系の新規プロジェクト
  向け secrets は、1Password ではなく **こちらを正**にしていく方針（自己主権
  vault、ADR-2606272330）。**実在する vault の実体は
  `orgs/kotoba-lang/kagi/.kagi/`**（`bin/kagi` が実行時に自身のリポジトリ
  ルートへ `cd` するため、どのディレクトリから叩いても常にここを見る —
  2026-07-10 のセッションでこれを見落として「vault が無い」と誤判定した
  実例があるので注記）。unlock は **OS Keychain（`kagi unlock-status` で
  確認可能、`:method :os-keychain`）が既定で通る**ため、通常は
  `KAGI_MASTER` を設定しなくても `bin/kagi add`/`bin/kagi get` がそのまま
  動く（passphrase はKeychainが使えない場合の recovery 経路として残っている
  のみ）。`bin/kagi ls` で一覧、`bin/kagi get <name>` で取得。1Password から
  個別 item を持ち込みたい時は `bin/kagi import onepassword <file.1pux>`。
  既存 item 例: `net-kotobase` compartment に `KOTOBA_SEED_PRODUCTION`/
  `KOTOBA_SEED_TESTNET`/`KOTOBASE_B2_*` 等。
- **`gftd.kotobase/CLOUD_ITONAMI_LEI_INGEST_IDENTITY_SEED`（1Password
  `gftdcojp` vault）+ kagi `CLOUD_ITONAMI_LEI_INGEST_IDENTITY_SEED`
  （compartment `net-kotobase`）— 両方に保管済み** — ADR-2607113500
  （cloud-itonami-lei kotobase.net ingestion job）の自己主権 CACAO identity
  （Ed25519 seed, 32-byte hex）。ローカルミラーは
  `scripts/.kotobase-ingest-cloud-itonami-lei-identity.hex`
  （`scripts/.gitignore` 済み、git に一切コミットしない）。kagi 側は
  上記の既存 vault（OS Keychain unlock）にそのまま `bin/kagi add` で追記
  ——新規 vault や新規 master passphrase の生成は不要だった（オーナーへの
  「新規生成の許可」確認は、vault 未存在という誤った前提に基づいていたことが
  判明したため、実際には生成した passphrase は未使用のまま破棄した）。
- **cloud-itonami ops-repo の鍵(ADR-2607141700、kagi vault
  `orgs/kotoba-lang/kagi/.kagi/`、compartment `personal`、2026-07-14 mint)**:
  - `itonami-org-root` — org root Ed25519 seed(64 hex)。公開 did は
    `orgs/gftdcojp/cloud-itonami/resources/ops-identity.edn` にコミット済み。
  - `itonami-sales-head` / `itonami-billing-head` / `itonami-keiei-head` —
    部門長 seed(同形式)。
  - `itonami-sales-head-chain` / `itonami-billing-head-chain` /
    `itonami-keiei-head-chain` — 各部門長への CACAO 委任 chain(EDN vector、
    **expiry 90 日 ≈ 2026-10-12。失効前に `clojure -M:ops-send mint-chain
    kagi:itonami-org-root ...` で再 mint**)。merge 時は
    `bin/kagi get itonami-<dept>-head-chain > /tmp/chain.edn` で取り出す。

## kaigi / Cloudflare RealtimeKit (2026-07-31)

- **`REALTIMEKIT_API_TOKEN`（kagi vault、compartment `personal`、`KAGI_HOME=$HOME/.kagi`）**
  — Cloudflare **Account API token**（`cfat_…`）。`Realtime Read` + `Realtime Admin` を
  ai-gftd-cloud account 全体に、**無期限・IP 制限なし**で発行したもの（token 名
  `260731-little-salad-2a4f`）。取得:
  `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get REALTIMEKIT_API_TOKEN`。
  - **用途**: `kaigi` Worker が RealtimeKit の meeting 作成と participant token の mint に
    使う（`kaigi.realtimekit`）。Worker secret 名も同じ `REALTIMEKIT_API_TOKEN`。
  - ⚠ **この token は発行時に会話ログへ平文で貼られている。** 用途が固まったら rotate する
    のが望ましい。権限も必要より広い（Realtime だけで足りるが account 全体・無期限）。
  - **1Password には未登録**（`op` が Touch ID 承認を要求し非対話で通らなかった。オーナーが
    `op signin` してから登録する）。登録したらこの行を更新する。

- **非機密の識別子**（secret ではないので参照先だけでなく値をここに書いてよい）:
  - `REALTIMEKIT_ACCOUNT_ID` = `4da88288dc30d9ee257f319d3c33ecf0`
  - `REALTIMEKIT_APP_ID` = `dbdc47c6-e97b-4573-919c-d47258bc40d3`（RealtimeKit app
    `260731`）。**SFU の app id とは別物** — この id を SFU API に投げると全ゼロ UUID と
    同じ `not_found` が返る（実測 2026-07-31）。SFU を使うなら別途 SFU app を作る。
  - `REALTIMEKIT_PRESET` — app 作成時に自動生成された preset 名を使う
    （`group_call_host` / `group_call_participant` / `group_call_guest` 等）。
    一覧: `GET /accounts/{acct}/realtime/kit/{app}/presets`。

## marketplace 共有 identity seed (ADR-2607275000、2026-07-27)

- ⚠ **`itonami-marketplace-kotobase-seed` は 2026-08-05 時点で kagi に存在しない**
  （`KAGI_HOME=$HOME/.kagi` の live vault・repo-local vault の**両方**で
  `no such item`、3 回再試行）。**下の記述は実態と乖離している** ——
  `MURAKUMO_GENERATION_TOKEN_SECRET` / `MURAKUMO_CHAT_TOKEN_SECRET_2` と同じ乖離が
  3 例目としてここでも起きた（保管されなかったか、後に失われた）。
  - **実害**: ADR-2800003200 Phase 2（tsukuru を marketplace ref に載せる）が
    ここで止まっている。**新しい seed を作っても代わりにならない** —— `:apex` は
    graph scope == issuer DID を要求するので、別 seed は別 DID = 別グラフになり、
    共有 ref に join できない（それが Phase 2 の目的そのもの）。実測: 使い捨て seed で
    deploy した `cloud-itonami-tsukuru` は kotobase.net に対して
    `q 401 Unauthenticated` を返した（未登録 DID）。
  - **7 worker 側の secret は生きている**（`did:key:z6Mkid37…` で `/health` 200、
    `/offers` `/orders` が読める）ので、**値は Cloudflare の中にだけ在って
    読み出せない**状態。復旧には ①オーナーが元の値を持っていれば kagi に入れ直す
    ②無ければ新 seed を発行して 8 worker 全部に入れ直す（＝既存 ref の DID が
    変わるので現行データの扱いを決める必要がある）のどちらか。
  - **`ACTOR_WRITE_TOKEN`（7 actor の write gate）もどこにも記録が無い。**
    live の cross-actor 検証（onboarding に applicant を作って credential を
    発行させる）はこれが無いと駆動できない。
  - **2026-08-05 実測の ref の中身**: `/offers` `/orders` とも `[]`。
    ADR-2607274000 が記録した live チェーン（merchant.riverside 等）は
    **現在の ref には残っていない**ので、seed が戻っても正の join を見るには
    チェーンを引き直す必要がある。

- （以下は復旧時の参照用。**上記のとおり item は現存しない。**）
  **`itonami-marketplace-kotobase-seed`（kagi vault、compartment `personal`）** —
  cloud-itonami の marketplace 7 actor（order / onboarding / listing / settlement /
  fulfillment / crossborder / returns）が**共有する** Ed25519 seed（base64 32 byte）。
  did は `did:key:z6Mkid37JoU81KWZCA5KbrX3t8Ji9dkH6azjrAKyg63XyvTm`（公開値）、
  graph/ref は `marketplace`。取得: `bin/kagi get itonami-marketplace-kotobase-seed`。
  - **なぜ 1 本を共有するか**: `:apex` は graph scope == issuer DID を要求するので、
    1 つの ref を共有する actor 群は 1 つの鍵を共有するしかない。actor ごとの帰属は
    鍵ではなく `marketplace.persist/stream-ctx` の per-actor 台帳が担保する。
  - **Cloudflare 側**: 7 worker の secret `KOTOBASE_SECRET_KEY` に投入済み
    （wrangler secret は読み出せないので、kagi が唯一の可読な複製）。
  - **1Password には入れていない。** kagi 側が push で同期し、PQC + 台帳 + 非対話
    読み出しを持つため。**代わりに 1Password に置くべきは kagi の recovery
    passphrase**（単一障害点を分ける）—— これはオーナー手動。

- **`itonami-tsukuru-testgraph-seed` / `itonami-tsukuru-actor-write-token`
  （kagi、compartment `personal`、2026-08-05 発行）** — 上記が見つからなかったため、
  ADR-2800003200 Phase 2 の**機構検証用に作った使い捨て**の Ed25519 seed と write token。
  **共有 ref には繋がらない**（DID が違うので別グラフ。しかも kotobase.net に未登録で
  `q 401`）。Worker `cloud-itonami-tsukuru` からは検証後に削除済みで、
  現在この Worker は secret 0 本 = 設計どおり 503 で fail-closed。
  **共有 seed が復旧したらこの 2 つは用済みなので消してよい。**

## itonami fleet 共有 identity seed (2026-07-30)

- **`itonami-fleet-kotobase-seed`（kagi vault、compartment `personal`、
  `KAGI_HOME=$HOME/.kagi`）** — cloud-itonami 艦隊（~1,197 actor）が**共有する**
  Ed25519 seed（base64 32 byte）。did は
  `did:key:z6MkqTPSr5ZUnLdroq3wMWVk9dxmNEKCLxbhse9Ec3Te6y28`（公開値）。
  取得: `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get itonami-fleet-kotobase-seed`。
  - **なぜ艦隊で 1 本か（オーナー判断 2026-07-30）**: `:apex` は graph scope ==
    issuer DID を要求するので、**鍵の本数がそのまま Datalog で結合できる範囲**に
    なる。CLAUDE.md の kotobase 規則「一緒にクエリしたいものは同じ ref に置く」
    「黙ったシャーディングを禁じる」に従い 1 本。actor ごとに鍵を持つと 1,197 ref
    に割れ、セクター横断の問いが永久に立てられなくなる。
  - **代償（発見でなく明示）**: この 1 本が漏れれば艦隊全体に及ぶ。actor ごとの
    帰属は鍵ではなく `marketplace.persist/stream-ctx` の per-actor 台帳が担保する。
  - **marketplace 7 actor は別本**（`itonami-marketplace-kotobase-seed`、ref
    `marketplace`）のまま。統合するかは別判断で、今回は触っていない。
  - **投入先**: dispatch namespace `ai-gftd-repository-dispatch` の user Worker の
    `KOTOBASE_SECRET_KEY`。現時点で `cloud-itonami-isic-0111` のみ。

### ⚠ Workers for Platforms への secret 投入は `wrangler secret put` ではできない

**`wrangler secret put` に `--dispatch-namespace` は存在しない。** namespace 内の
user Worker には届かない。`.dev.vars` も dispatch-namespace deploy では**拾われ
ない**（実測: アップロードは成功するのに `did: null` のままだった）。

正しい経路は **`wrangler deploy --dispatch-namespace <ns> --secrets-file <file>`**。
成功すると binding が報告される（`env.KOTOBASE_SECRET_KEY ("(hidden)")`）ので、
その行が出ないときは入っていない。ファイルは `.env` 形式（`KEY=value`）、
`.gitignore` 必須、投入後に削除する。残る ~1,196 actor すべてでこの経路を使う。

## kagi の cloud 同期と端末登録（2026-07-27）

- **`kagi push` / `pull` / `sync` は 2026-07-27 まで一度も動いていなかった**
  （apex に対して常に 401）。原因は自前 `cacao.clj` の 3 つの乖離で、最大のものは
  `iat` にナノ秒が付くと apex の `parse-utc-seconds`
  （`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` のみ）に落ちること。
  修正済み（kagi `6c514cb` 系）。暗号化 vault は
  `kotobase/db/<did>/kagi-vault` に seq 付きで載る。
- **2 台目の登録は `kagi device request|grant|accept`** — master passphrase を
  新端末に渡さずに VMK を hybrid KEM（X25519 + ML-KEM-768）で受け渡す。
  `grant` は `--fingerprint` 必須（中間者防止。人間が読み上げる手順を省けない設計）。
  **`kagi device revoke` は access-list の変更であって、その端末が既に得た VMK の
  取り消しではない** —— 紛失端末は vault 侵害として扱い secret 自体を rotate する。

## swap plane testnet key (ADR-2607261500、2026-07-26)

- **`SWAP_SEPOLIA_TESTNET_KEY`（kagi vault、compartment `personal`）** — Ethereum
  **Sepolia testnet 専用**の secp256k1 秘密鍵（32-byte hex）。address は
  `0xAd785580D9af6AC6e43D19eC5F5F666b6b016EA9`（公開値）。swap plane の on-chain
  検証（`erc20/bin/verify_onchain.cljs`）が `SEPOLIA_KEY` として読む。
  PoW faucet 由来の testnet 資金のみ保持（実価値なし）。**mainnet では絶対に使わない** —
  faucet 経由で公開された address であり、実資金用の鍵はこれとは別に発行する。
  取得: `bin/kagi get SWAP_SEPOLIA_TESTNET_KEY`。
- **0x Swap API キーは存在しない**（2026-07-26 時点で確認済み）。`swap.aggregator` の
  `:zero-ex-v2` adapter が `:verified? false` のままなのはこれが理由 — キーを取得したら
  `swap/bin/verify_live.cljs` を実行して live 検証し、フラグを立てる。LI.FI 側は
  キー不要で live 検証済み。

## 確認屋 / manimani per-user (ADR-2607141753、2026-07-14)

- **manimani admin token**(org 側 push/pull 用、wrangler secret `MANIMANI_ADMIN_TOKEN` と同値):
  macOS Keychain `security find-generic-password -a junkawasaki -s "manimani:admin-token" -w`
- **manimani user token (junkawasaki)**(個人 triage 用 interim Bearer):
  Keychain service `manimani:user-junkawasaki-token`
- **owner DID**(非機密・公開値だが参照用): Keychain service `gftdcojp:owner-did`
  = `did:key:z6MkmCrDjqsUiHM4bK6zVyzuYGitMGjCVRb1eTrF122mxrNU`
- **owner Ed25519 秘密鍵(seed)**: `orgs/gftdcojp/cloud-itonami/.junkawasaki/identity.edn`
  (gitignored。cloud-itonami.identity/load-or-create-identity! が正)
  - `itonami-runner-bot` — **execute-only** runner bot Ed25519 seed
    (did `did:key:z6MkvmMJxqz4iA3R2wuJ7mFQfJ9qh4HCe2o7vvjeXoxvFWmq`)。
    receipt 署名専用。**merge chain は一切発行しない**(職務分掌の execute 側)。
    runner はこれを `ITONAMI_OPS_RUNNER_SEED` か `kagi:itonami-runner-bot`
    経由で読む。
  - `itonami-org-root-x25519` / `itonami-sales-head-x25519` — **X25519**
    recipient private keys(R2 private-replica、ADR-2606280300)。公開 pub は
    `resources/ops-identity.edn` の `:recipients`。署名用 Ed25519 とは別物
    (key-agreement 専用)。ops-repo の暗号化 replica bundle はこの pub 集合に
    seal され、priv で open する。recipient を rotate outするには reseal 時に
    その pub を外す。

## ⚠ kagi vault は 2 つある — `~/.kagi` が live（2026-07-29 実測）

**`bin/kagi` は自身の repo root へ `cd` するため、既定では
`orgs/kotoba-lang/kagi/.kagi/vault.edn`（2026-07-17 付・56KB の**古い方**）を読む。
実際に使われている vault は `~/.kagi/vault.edn`（2.1MB、日々更新）。**
下の項目を「無い」と判断する前に必ず `KAGI_HOME=$HOME/.kagi` を付けて引き直すこと:

```bash
KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get <ITEM>
```

実害: 2026-07-29、この差で murakumo generation secret を「消失」と誤判定しかけた
（結果的に live 側にも無く再発行したが、判定根拠としては不正確だった）。
**安全床⑦により、見つからない時に `kagi ls` で総当たり列挙してはいけない** —
既知の識別子で狙い撃ちし、それでも無ければオーナーに聞く。

## Murakumo generation caller gate (ADR-2607161750、2026-07-16 / 2026-07-29 再発行)

- ⚠ **2026-07-29 実測: 下記 2 item は `~/.kagi`（live vault）にも
  `orgs/kotoba-lang/kagi/.kagi`（旧 vault）にも存在しなかった** —
  `MURAKUMO_GENERATION_TOKEN_SECRET` / `MURAKUMO_CHAT_TOKEN_SECRET_2` とも
  `no such item`。ADR-2607161750 / ADR-2607171800 addendum 2 の「kagi 保管を正とする」
  という記述は**実態と乖離していた**（保管されなかったか、後に失われた）。
  オーナー承認（2026-07-29「kagi は再発行して ok」）のもとで再発行した。詳細は
  ADR-2607299960。
- **再発行後の現在地（すべて `KAGI_HOME=$HOME/.kagi` で引く）**:
  - `MURAKUMO_GENERATION_TOKEN_SECRET`（compartment `gftdcojp`）— 新規生成。
    **Worker `murakumo-generation-proxy` の `MURAKUMO_TOKEN_SECRET_2`（optional な
    第2検証スロット、未設定だった）へ投入**したので、**primary は無傷** =
    `net-babiniku` / `network-isekai` の既存 caller は壊れていない（rotation ではない）。
  - `MURAKUMO_DOUGAKA_GENERATION_TOKEN`（compartment `gftdcojp`）— 上記 secret で
    mint した subject=`dougaka` / scope=`generation` / ttl 90 日の実 token。
    dougaka pipeline が `MURAKUMO_GENERATION_TOKEN` として読む。
  - **`murakumo-generation-proxy` は 2026-07-15 版が動いていて `_2` を読まなかった**
    ため、main から rebuild して再 deploy 済み（version `9a183830`）。
- **どのホストを叩くか**: 実 API は **`generation.murakumo.cloud/api/v1/generation`**。
  同じ token は `murakumo.cloud` では 401（別 worker・別 secret）。job status と
  artifact も同じホストで引く（API が返す URL は `murakumo.cloud` を指すので
  ホストを差し替える必要がある）。
- 旧記述（参考、上記のとおり item は実在しなかった）:
  **`MURAKUMO_GENERATION_TOKEN_SECRET`（kagi vault、compartment `gftdcojp`）** —
  `generation.murakumo.cloud`（Worker `murakumo-generation-proxy`）の caller gate
  `MURAKUMO_TOKEN_SECRET` と、その caller（`net-babiniku` / `network-isekai` 両
  Pages の `MURAKUMO_CALLER_SECRET`）が共有する HMAC signing secret の正本。
  2026-07-16 に rotation 済み（旧値は babiniku の provision script が生成後即破棄
  していてどこにも保管されていなかった — この轍を踏まないため kagi 保管を正とする）。
  新しい caller を追加するときは rotation せず `bin/kagi get
  MURAKUMO_GENERATION_TOKEN_SECRET` の値を該当 Pages/Worker secret に設定する。
  generation-scope の長命 token を mint するには `cloud-murakumo` で
  `MURAKUMO_TOKEN_SECRET=$(kagi get …) clojure -M:token issue <sub> generation <ttl>`。
  ⚠ `murakumo.cloud` site Worker（chat/inference gate）と `api.murakumo.cloud`
  （local-murakumo、`MURAKUMO_PROXY_TOKEN` 系）は**別の secret** — この item では
  ローテーションも検証もできない。

## api.murakumo.cloud mk1 capability-token 署名鍵 (ADR-2608011400、2026-08-01)

- **`MURAKUMO_API_TOKEN_SECRET`（kagi vault、compartment `network-awai`）** —
  `api.murakumo.cloud`（Worker `local-murakumo`）が `/v1/messages` `/v1/embeddings`
  で検証する **mk1 capability token の署名鍵**。同じ値が Worker secret
  `MURAKUMO_TOKEN_SECRET` に入っている（wrangler で投入済み、redeploy を跨いで永続）。
  取得: `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get MURAKUMO_API_TOKEN_SECRET`。
- **新規発行**（2026-08-01）。この Worker には従来 mk1 の署名鍵が存在せず、
  共有の静的 `ANTHROPIC_PROXY_TOKEN` / `_2` しか無かった（本マップの
  「Murakumo chat gate secondary secret」節が『api.murakumo.cloud は別の secret』と
  注記しているとおり）。mk1 受理を足すにあたって新設したので、**rotation ではなく追加**
  —— 既存の共有トークン経路は一切変わらない。
- **鍵の mint**（CLI / MCP のどちらも同じ実装 `murakumo.apikey`）:
  ```
  cd orgs/kotoba-lang/murakumo
  MURAKUMO_TOKEN_SECRET=$(kagi get MURAKUMO_API_TOKEN_SECRET) \
    nbb --classpath src scripts/run-task.cljs token issue --sub <who> --scope chat --ttl 604800
  ```
  MCP: `claude mcp add murakumo -- nbb --classpath "src:../org-anthropic-mcp/src" scripts/mcp-server.cljs`
  → tool `murakumo.issue_api_key`（同じ env が要る）。
- **境界**: scope は `chat|image|all` のみ、TTL は 90 日上限。**失効リストは無い**
  （ステートレス検証なので期限が失効そのもの）—— 長命鍵を作らず再発行する。
- live 検証済み（2026-08-01、CLI 発行・MCP 発行のどちらも本番 `/v1/messages` で 200、
  scope 不足は 401）。

## api.murakumo.cloud ノード面 service token (ADR-2608031000、2026-08-03)

- **`LOCAL_MURAKUMO_SERVICE_TOKEN`（kagi vault、compartment `network-awai`、
  `KAGI_HOME=$HOME/.kagi`）** — Worker `local-murakumo`（= `api.murakumo.cloud`）の
  **write gate** `MURAKUMO_SERVICE_TOKEN` と同値。`/infer/runs` `/infer/spend`
  `/infer/queue*` と、新設の低速ティア ノード面 `/v1/slow/work*` `/v1/slow/workers/heartbeat`
  の Bearer。取得:
  `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get LOCAL_MURAKUMO_SERVICE_TOKEN`。
  - **2026-08-03 に新規発行（rotation ではない）。** 発行前この Worker には
    `MURAKUMO_SERVICE_TOKEN` が未設定で、`write-gate/service-authorized?` は
    **未設定を「全部許可」に倒さない**（fail-closed）ため、該当ルートは常に 401 を
    返していた＝既存 caller はいない。
  - ⚠ **`murakumo.cloud`（Worker `murakumo-cloud`、cloud-murakumo）の
    `MURAKUMO_SERVICE_TOKEN` とは別物。** 同名だが別 Worker・別値で、上の
    「murakumo x402 ingest service token」節のものは `/x402/ingest` 用。
    どちらかを rotate しても他方には効かない。
  - ⚠ **mk1 の署名鍵（`MURAKUMO_API_TOKEN_SECRET`、下記の別節）とも別物。** あちらは
    顧客面 `/v1/messages` `/v1/embeddings` `/v1/slow/messages` の capability token 検証、
    こちらはノード/フリート側の write gate。

## kotobase.net archive write token (2026-07-29)

- **`KOTOBASE_ARCHIVE_TOKEN`（kagi vault、compartment `net-kotobase`）** —
  `kotobase.net` の first-party content-addressed archive（`PUT /ipfs/:cid`、
  `kotobase.archive-put`）の write gate。Worker `net-kotobase` の同名 secret と同値。
  **この Worker secret は以前から設定されていたが値はどこにも保管されておらず
  読み戻せなかった**ため、2026-07-29 にオーナー承認のもと再発行した
  （workspace 全体を grep して consumer ゼロを確認した上で交換）。
  取得: `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get KOTOBASE_ARCHIVE_TOKEN`。
- **使い方**: raw CIDv1（`bafkrei…` = 本体バイト列の sha2-256）を自分で計算し、
  `PUT https://kotobase.net/ipfs/<cid>` に Bearer で置く。サーバが digest を再計算して
  不一致は 422 で弾く。読みは無認証の `GET /ipfs/<cid>`。**未設定だと 403（feature off）**。
  live 検証済み（2026-07-29、PUT 201 → GET 200、バイト一致）。
- 消費側: `dougaka.archive`（production artifact の保管、ADR-2607299960）。

## Murakumo chat gate secondary secret (ADR-2607171800 addendum 2、2026-07-16)

- ⚠ **2026-08-03 実測: `MURAKUMO_CHAT_TOKEN_SECRET_2` は live vault
  （`KAGI_HOME=$HOME/.kagi`）に存在しない** — `no such item`。下記の記述は実態と乖離して
  いる（2026-07-29 に `MURAKUMO_GENERATION_TOKEN_SECRET` / この item で起きた乖離のうち、
  generation 側だけが再発行され、chat 側は残ったまま）。**この item を前提にしたスクリプトは
  動かない。** `murakumo.cloud/api/v1/chat/completions` は 401 のまま。
  - **今日動く chat 経路は `api.murakumo.cloud/v1/chat/completions`**（OpenAI 互換、実測 200）。
    token は下記「api.murakumo.cloud mk1 capability-token 署名鍵」の
    `MURAKUMO_API_TOKEN_SECRET`（compartment `network-awai`、実在確認済み）から mint する:
    `cd orgs/kotoba-lang/murakumo && MURAKUMO_TOKEN_SECRET=$(kagi get MURAKUMO_API_TOKEN_SECRET)
     nbb --classpath src scripts/run-task.cljs token issue --sub <who> --scope chat --ttl 604800`
  - 実害: ADR-2608031500 の storyboard 生成でこれを踏んだ。`murakumo.cloud` gate を
    復旧したい場合は secret の再発行が要る（オーナー承認が要る操作）。
  - 以下は復旧時の参照用に残す。**現状は上記のとおり存在しない。**


- **`MURAKUMO_CHAT_TOKEN_SECRET_2`（kagi vault、compartment `gftdcojp`）** —
  `murakumo.cloud` site Worker（chat/inference gate）の**第2検証 secret**
  `MURAKUMO_TOKEN_SECRET_2` の正本。primary の `MURAKUMO_TOKEN_SECRET` は
  write-only Wrangler secret でどこにも保管が無く再取得不能（上記注記のとおり）
  だったため、gate 側を「どちらかの secret で verify が通れば受理」に拡張し
  （cloud-murakumo GitHub main `5bba489`）、agent が mint できる第2系統として
  この kagi item を追加した。既存 primary 署名 token は無効化されない（rotation
  ではなく追加）。chat-scope token の mint:
  `cd orgs/gftdcojp/cloud-murakumo && MURAKUMO_TOKEN_SECRET=$(kagi get
  MURAKUMO_CHAT_TOKEN_SECRET_2) clojure -M:token issue <sub> chat <ttl>`。
  Worker secret `MURAKUMO_TOKEN_SECRET_2` は wrangler で投入済み（worker
  `murakumo-cloud`、redeploy を跨いで永続）。
  ✅ **2026-07-16 更新: production 反映済み**。当初は共有 checkout が GitHub main と
  乖離した de-facto production ライン（local 109 commits 先行 / main 5 遅れ）で、
  この gate 拡張コードがそこに無かったため実運用は primary のみ受理していた。
  オーナー指示でその 109 commits を main に着地・一本化し（cloud-murakumo main
  `a1423b2`、west pin も前進 `3bbdc32`）、統一 main から rebuild した dist を
  murakumo.cloud にデプロイ（worker version `a6d00f52`）。以後、実運用の chat gate は
  `MURAKUMO_CHAT_TOKEN_SECRET_2` で mint した token を受理する（live 200 確認済み・
  upstream gemma4-26b 応答）。**注意**: cloud-murakumo は複数の worktree/deploy 経路が
  あり（共有 checkout・`cloud-murakumo-production-release` worktree・他セッション）、
  いずれも今は unified main 上にあるが、古い dist からの再デプロイが混ざると一時的に
  401 に戻りうる — その場合は unified main から rebuild して再デプロイする。

## Murakumo playtest critic token (ADR-2607162100、2026-07-16)

- **`MURAKUMO_CRITIC_TOKEN`（kagi vault、compartment `gftdcojp`）** —
  `api.murakumo.cloud/v1/messages`（local-murakumo）の vision critic 用トークン。
  local-murakumo Worker の **secondary** 受理スロット `ANTHROPIC_PROXY_TOKEN_2`
  に設定済み（primary の `ANTHROPIC_PROXY_TOKEN`＝1Password `gftd.murakumo/
  ANTHROPIC_PROXY_TOKEN` はローテーションせず据え置き。primary は op interactive
  auth timeout で非対話取得不可、こちらは kagi から取得可能なのが要点）。
  playtest co-scientist の standing runner（`~/.gftd/run-playtest-coscientist.cljs`
  ＋ LaunchAgent `com.gftd.playtest-coscientist`）が `MURAKUMO_CLAUDE_TOKEN` として
  これを読む。新しい `/v1/messages` consumer も同じ secondary スロットで rotation
  なしにオンボードできる。

## murakumo x402 ingest service token (ADR-2608010000、2026-08-01)

- **`MURAKUMO_SERVICE_TOKEN`（kagi vault、compartment `gftdcojp`、
  `KAGI_HOME=$HOME/.kagi`）** — `murakumo.cloud` の `POST /x402/ingest`（フリートが
  検証済み USDC-transfer view を push する経路）の Bearer。Worker `murakumo-cloud`
  の同名 secret と同値。取得:
  `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get MURAKUMO_SERVICE_TOKEN`。
  - **2026-08-01 に新規発行した（ローテーションではない）。** 発行前は kagi にも
    Worker secret list にも存在せず、`/x402/ingest` は設計どおり 503 を返していた。
    消費者ゼロを `wrangler secret list` で確認した上で作成しているので、既存の
    caller を壊していない。
  - ⚠ **`cloud-murakumo` の `post-to-ledger!` も同名の env を読む**（`/infer/runs`
    への bearer）。未設定時は単にヘッダを付けない実装なので今回の追加で挙動は
    変わらないが、**将来この値を rotate するときは ingest と ledger POST の両方に
    効く**ことを忘れないこと。
  - **launchd 経路は kagi ではなくファイル**: `~/.gftd/murakumo-service-token`
    （mode 600）。**launchd 下では kagi が Keychain unlock prompt を出せずに
    timeout する**ため（fleet-ci が先に踏んだ壁、ADR-2607178000 §3 と同じ答え）。
    LaunchAgent plist は world-readable なので plist 本体には絶対に書かない。
    常駐: `com.gftd.murakumo-x402-ingest`（60秒間隔）。
  - 消費側: `orgs/network-awai/cloud-murakumo/scripts/x402-ingest.cljs`
    （env → `MURAKUMO_SERVICE_TOKEN_FILE` の順で解決。argv には載せない）。

- **`murakumo-base-rpc-url`（kagi、compartment `personal`）— まだ存在しない。**
  keyed Base RPC エンドポイント（`https://base-mainnet.g.alchemy.com/v2/<key>` 等）を
  置く先として `scripts/provision-rpc-key.cljs` が読む item 名。**アカウント登録と
  鍵発行はオーナー作業**（安全床①）。設定すると Worker secret `MURAKUMO_BASE_RPC`
  として最優先の RPC になる。未設定でも公開エンドポイントへフォールバックする。
