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
- **Cloudflare**: `wrangler` の OAuth ログインセッション（ブラウザ認証、
  `npx wrangler login`）が実質の認証手段 — このセッションで R2 バケット/KV
  namespace/Worker デプロイ/Custom Domain 追加まで一括して操作できる（1Password
  越しの API トークンではなく、ローカルの wrangler セッションで完結）。
  `CLOUDFLARE_API_TOKEN`（Zone Analytics Read 等の狭いスコープ）は用途別に
  `wrangler secret put` で個別プロジェクトへ投入するもので、これとは別物。
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
