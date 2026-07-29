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
  DNS 編集用の zone token は keychain `gftd.cf`（DNS read/write 確認済み
  2026-07-17）。
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

## marketplace 共有 identity seed (ADR-2607275000、2026-07-27)

- **`itonami-marketplace-kotobase-seed`（kagi vault、compartment `personal`）** —
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

## Murakumo generation caller gate (ADR-2607161750、2026-07-16)

- **`MURAKUMO_GENERATION_TOKEN_SECRET`（kagi vault、compartment `gftdcojp`）** —
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

## Murakumo chat gate secondary secret (ADR-2607171800 addendum 2、2026-07-16)

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
