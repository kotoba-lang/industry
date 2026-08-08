# メール / DNS

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

## cloud-itonami 受信メール本文の age 鍵 (ADR-0021、2026-08-05)

- **`itonami-mail-age`（kagi vault、compartment `personal`、`KAGI_HOME=$HOME/.kagi`）
  — 正本。** 中身は**平文 EDN ではなく kagitaba item**（1Password 互換の item 形）で、
  `recipient`（公開値）と `identity`（`AGE-SECRET-KEY-1…`、concealed）の 2 フィールドを
  持つ。取得:
  `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get itonami-mail-age`。
  - **何を守っているか**: cloud-itonami-app が project に振り分けたメール本文は、
    project の Git リポジトリに **age 暗号文**として git-annex で入る
    （`mail/<yyyy>/<mm>/<id>.eml.age`）。**この identity を失うと、これまでに
    ファイルした本文を全て失う** —— 暗号文は Git にあり鍵は無い、というのが設計。
  - **app は identity を読まない。** 書く側は recipient（公開鍵）だけあれば足りるので、
    アプリは recipient のみ解決する。復号は人が実行する:
    ```bash
    KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get itonami-mail-age \
      | grep -o 'AGE-SECRET-KEY-[A-Z0-9]*' > /tmp/id
    age -d -i /tmp/id <project>/mail/2026/08/<id>.eml.age && rm /tmp/id
    ```
- **Keychain ミラー**（非対話ローカル、GUI プロセスから読める）:
  service `cloud-itonami-app.mail-age`、account `recipient` / `identity`。
  **app の解決順は env → recipients file → Keychain → kagi** で、Keychain が先なのは
  vault unlock を待たずに答えるから（メールをファイルする経路をブロックさせない）。
- **recipient は公開値なのでここに書いてよい**:
  `age1erny355hm8nrq5plls0fs2trl6gwrxp0vl8nfqkxkc5clh2gxfwqemlhg9`
  （これと違う recipient で封緘されていたら、手元の identity では開けない
  —— `GET /api/mail/projects` の `:sealing` がどの store から解決したかを返す）。
- ⛔ **1Password には未登録。** `op signin --account my.1password.com --raw` が
  exit 124（無応答タイムアウト）、`op whoami` は `account is not signed in`。
  このマシンで CLI 統合がオフのため非対話で書けない（本マップが他の item でも
  繰り返し記録している症状）。**オーナー作業**: 1Password アプリ →
  設定 → 開発者 → 「1Password CLI と連携」を有効化 →
  item `cloud-itonami mail age key`（category: Password、vault `gftdcojp`）を作り、
  `recipient` / `identity` フィールドに kagi の値を写す。登録したらこの行を更新する。
