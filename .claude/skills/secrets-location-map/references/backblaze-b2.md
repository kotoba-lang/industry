# Backblaze B2

## ⚠ m365-archive は B2 認証情報の手前に GPG の壁がある（2026-08-15 実測）

**`gftdcojp-m365-annex` の annex は `encryption=hybrid` で、実際の B2 認証情報は
git-annex ブランチの中に GPG 鍵 `BDAE6794050EDB62` で暗号化されて入っている。**
このファイルが下に列挙している B2 の鍵を全部解決できても、**それだけでは 1 バイトも
取れない** —— `git annex enableremote b2` が
`gpg [--quiet --trust-model always --decrypt] exited 2` で落ちる。

実測した切り分け（次のセッションが同じ順番を辿り直さないように）:

| 確認 | 結果 |
|---|---|
| `scripts/b2-creds.cljs` の解決 | **成功**（`AWS_ACCESS_KEY_ID` 他 5 変数） |
| 必要な GPG 秘密鍵 `BDAE6794050EDB62` | **手元に在る** |
| pinentry-mac + gpg-agent（400 日キャッシュ） | 設定済み・起動中 |
| 非対話コンテキストでの `gpg --decrypt` | **45 秒でハング**（GUI プロンプトが出せない） |
| Keychain `service=GnuPG` / `account=<暗号化副鍵の keygrip>` | **項目は在るが値が 4 文字で、gpg が「誤ったパスフレーズ」と判定** |

**つまり Apple Keychain には有効なパスフレーズが入っていない。**
暗号化副鍵の keygrip は `9F0C4E9F761C84E00E3E1D2F8AE1AE345A4AA881`
（主鍵は `1BA2DB3551016BD80E908DBD6CF3F5489D3D57F5`、こちらは Keychain に項目なし）。

**このパスフレーズの所在はまだ記録されていない（2026-08-16 時点で未解決）。**
1Password の 7 vault にはアクセスできるが item 名が不明。
`identity-seeds.md` の規則どおり **agent は passphrase を推測しない・自分で
入力しない** ので、名前が判明するまでここは開かない。**分かったらここに追記する。**

解錠の手順（オーナーが 1 回だけ実行すれば 400 日キャッシュされる）:

```bash
cd orgs/gftdcojp/m365-archive && git annex enableremote b2
# pinentry-mac のダイアログで入力し「Save in Keychain」にチェック
```

⚠ **`nbb manifest/west_annex.cljs annex-get` を安易に使わない** —— 引数なしで
**全 datalad データセットを走査**し、`cloud-itonami-contact-pii` から順に
init/enableremote を始める。1 ファイルが欲しいだけなら
`git annex get <path>` を狙い撃ちで使う（実測 2026-08-15、これを踏んだ）。

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
      `orgs/com-junkawasaki/ghosthacker-shiropico/tools/b2_catalog.py`
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
