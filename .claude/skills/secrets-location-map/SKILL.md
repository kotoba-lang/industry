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
