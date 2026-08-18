---
name: secrets-location-map
description: Index of where secrets (Backblaze B2, Cloudflare, kagi, 1Password/Keychain, Murakumo, identity seeds, Apple signing) live for this workspace, so you don't have to search 1Password from scratch every time. Never contains actual secret values, only vault/item/service references. Routes to a per-system file under references/ — read this index first, then open the one file you need. Use when you need to find or reference a credential's storage location.
---

# 秘密情報の保管場所マップ（値は書かない、参照先だけ）

**このファイル群に秘密情報の値そのものを書いてはいけない。** 書いてよいのは
「どの vault のどの item に何が入っているか」という参照先だけ（`op://` パスや
Keychain の service 名と同じ扱い）。実値は `op read` / `bin/kagi get` /
`security find-generic-password` で都度取得する。この索引は「B2 の鍵はどこ？」を
毎回 1Password 内を検索し直す手間を省くためのもの — 見つけたら追記していく
（網羅は目指さない、都度育てる）。

**引き方: この索引で系を特定 → `references/<系>.md` を 1 本だけ読む。**
全部読まない（1 件引くために 700 行を走査しない）。

## どの系にあるか

| 探しているもの | ファイル |
|---|---|
| B2 バケット鍵、**Master Application Key**（新規 bucket/key 発行はこれだけ） | `references/backblaze-b2.md` |
| Cloudflare アカウント鍵、`CLOUDFLARE_GLOBAL_API_KEY`、R2、wrangler OAuth の限界、kotobase-protocols-worker、net-babiniku、**Workers for Platforms への secret 投入方法** | `references/cloudflare.md` |
| kagi の vault 実体・unlock・**2 つある vault**・`kagi push/pull`・端末登録 | `references/kagi-vault.md` |
| `MURAKUMO_*` 全般（mk1 署名鍵 / ノード面 service token / generation caller gate / chat gate / critic / x402 ingest） | `references/murakumo.md` |
| Ed25519・X25519・secp256k1 の seed と委任 chain（艦隊共有 / marketplace / cloud-itonami ops / owner / Radicle / Sepolia） | `references/identity-seeds.md` |
| Resend、GoDaddy DNS、受信メール本文の age 鍵 | `references/email-and-dns.md` |
| kotobase の**読み戻せない** 3 secret、`KOTOBASE_ARCHIVE_TOKEN` | `references/kotobase.md` |
| fal.ai / Seedance、Cloudflare RealtimeKit（kaigi） | `references/third-party-api.md` |
| Apple 署名 / App Store Connect、Google Play | `references/mobile-publishing.md` |

item 名から引きたいときは
`grep -rl '<ITEM_NAME>' .Codex/skills/secrets-location-map/references/`。

## どのファイルを読む前にも効く 4 つ

**1. kagi は `KAGI_HOME=$HOME/.kagi` を付けて引く。** `bin/kagi` は自身の repo root へ
`cd` するので、既定では `orgs/kotoba-lang/kagi/.kagi/vault.edn`（古い方）を読む。
live vault は `~/.kagi/vault.edn`。**「無い」と判断する前に必ず付け直す。**

```bash
KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get <ITEM>
```

**2. ここに書いてあることは「実在する」の証明ではない。** このマップが item の
所在を記していたのに実際には `no such item` だった事例が、確認できただけで 4 件ある
（`MURAKUMO_GENERATION_TOKEN_SECRET` / `MURAKUMO_CHAT_TOKEN_SECRET_2` /
`itonami-marketplace-kotobase-seed` / `KOTOBA_SEED_*` と `KOTOBASE_B2_*` の 4 item）。
**記述を前提に計画を立てず、着手前に実際に引く。** 乖離を見つけたら、その場で
該当ファイルを「存在しない」と書き換える（消さずに、復旧時の参照として旧記述を残す）。

**3. 資格情報が見つからないことを「データが無い」と読み替えない。** 2026-07-25、
`ai-gftd-datasets` の鍵がこの索引に載っていなかったため「どの現存鍵からも到達
できない」と誤判定し、**無事だった ep01 の資産を一度 lost と結論した**
（ADR-2607252000 → ledger seq 86 で訂正）。鍵が見つからない時は、まずこの索引と
B2 Master Key を確認する。

**4. 総当たりで列挙しない（安全床⑦）。** `kagi ls` の全件・`op item list` の全件・
`security dump-keychain` は、無関係な credential の metadata を露出させる。
**既知の識別子で 1 件だけ狙い撃つ。** 識別子が分からなければ task 文脈から特定し、
それでも分からなければオーナーに聞く（service 名を変え撃ちしない）。

## この環境の 1Password / kagi の既知の癖

- **`op item get` は無言でタイムアウトする**（rc=124、出力なし）。これを「該当なし」と
  読むと**不在の誤判定**になる。`op read`（`op://<vault>/<item>/<field>`）を使う —
  こちらは item/field の存在を区別したエラーを返す。
- **`op run` は使わない**（interactive auth timeout）。`op read` で env に注入する。
- **"account is not signed in" は行き止まりではない**: `op signin --account
  my.1password.com --raw` が Touch ID 統合で非対話に通ることがある。**セッションは
  数分で切れる**ので長い作業では `op whoami` で確認する（切れたまま走ると値が空になり、
  呼び出し側が黙って mock にフォールバックすることがある — 実際に起きた）。
- 非 ASCII 名の vault（`純真個人_*`）は `op://` 参照に使えない。**vault ID** で引く。
- item 作成時は値を argv に載せない（`ps` 露出）。テンプレート JSON を scratchpad に
  書いて `--template` で渡し、直後に削除する。
- **新規 secret は kagi を正とする**（自己主権 vault、ADR-2606272330）。多くの item が
  「1Password には未登録」なのは方針であると同時に、この環境で `op` の CLI 統合が
  オフで非対話に書けないため。オーナーが有効化したら登録して該当行を更新する。
- **launchd 下では kagi が使えない**（Keychain unlock prompt を出せず timeout する）。
  常駐プロセスに渡す値は `~/.gftd/<name>`（mode 600）のファイル経路にする。
  **LaunchAgent plist は world-readable なので plist 本体に値を書かない。**
