# 外部 API（生成モデル・RealtimeKit・Telnyx）

## ⚠ Telnyx の口座は 2 つある。鍵を取り違えない（2026-08-17）

| item | 口座 | 中身 |
|---|---|---|
| `telnyx-api-key` | **Gftd Japan株式会社** | 本番稼働中の US 番号 `+1 949 741 7223` を 1 本持つ |
| `telnyx-api-key-awai` | **AWAI Network, L.L.C.** | 2026-08-17 開設。番号 0 本 / 残高 $0.00 |

**見分け方は `GET /v2/phone_numbers` の件数**（実測: 旧 1 件 / 新 0 件）。どちらのキーも
HTTP 200 を返すので、認証が通ったことは口座の同定にならない。

`.mcp.json` の `telnyx` サーバは **`telnyx-api-key`（旧・Gftd Japan）** を読む。番号購入・
発信ができる鍵なので、向き先を変えるときは番号がどちらに付いているかを先に確認する。

### AWAI 口座の付随 secret

- **`telnyx-agent-inbox-key`**（kagi compartment `personal` / Keychain 同名）—
  Telnyx Agent Inbox `bot-telnyx-432cf07cce8c433dba3a94f8d90357bf@agentmail.to`
  （`account_id` = `inbox_432cf07c-ce8c-433d-ba3a-94f8d90357bf`）の `account_key`。
  **一度しか返らず、失うと受信箱ごと復旧できない。** サインイン用メールはここに届くので、
  口座のパスワードリセット経路そのもの。
- なぜ Agent Inbox を使ったか: **Telnyx は `j@awai.network` を拒否する**。原因は先方の
  ブロックリストが `.work$` という**ドット未エスケープの正規表現**で、`.` が任意の 1 文字に
  一致するため `awai.net**work**` を巻き込んでいる（`\.work$` が意図された形）。
  実測 2026-08-17、`.network` ドメインは 1 つも登録できない。Telnyx へ報告済み
  （Request ID `a64cc757-97a9-9dee-8e1a-8411379cf93d`）。修正されたら口座メールを
  `j@awai.network` へ移し、この受信箱への依存を切る。
- 取得スクリプト: `scripts/telnyx-agent-signup.cljs`（Agent Inbox の未文書 API 形と、
  受理された proof-of-work 構成 `sha256(challenge + ":" + nonce)` を記録してある）。

#### この受信箱の読み方（web UI は無い。API 専用・読み取り専用）

```bash
nbb scripts/telnyx-agent-signup.cljs inbox-list
nbb scripts/telnyx-agent-signup.cljs inbox-read '<message-id>'   # 角括弧ごと渡す
```

素で叩くなら 2 本だけ（実測 2026-08-17、これ以外は 404）:

```
GET https://agent-inbox.telnyx.com/v2/agent_inboxes/<account_id>/messages
GET https://agent-inbox.telnyx.com/v2/agent_inboxes/<account_id>/messages/<message_id>
Authorization: Bearer <account_key>
```

⚠ **送信・返信はできない。** `POST /messages`・`/threads`・受信箱メタデータは全て 404。
Telnyx から届くものを読むだけの箱で、この口座から人に返信する経路は無い。
`agentmail.to` に web UI は存在するが、この箱は Telnyx 経由で作られており
こちらは AgentMail 側の資格情報を持たないので、そこからは入れない。

## Telnyx API キー — kagi が正本、Keychain はキャッシュ（2026-08-15）

- **正本 = kagi item `telnyx-api-key`（compartment `personal`）。**
  `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get telnyx-api-key`
- **キャッシュ = macOS Keychain の service `telnyx-api-key`（account `telnyx`）。**
  `.mcp.json` の `telnyx` サーバの `headersHelper` が**接続のたびに**これを読み、
  `Authorization: Bearer …` を組み立てて `https://api.telnyx.com/v2/mcp` に渡す。
- **なぜ 2 か所なのか（mirror ではなく projection）。** 実測 2026-08-15、
  `kagi get` は **3.8〜5.1 秒**、Keychain は **0.02 秒**。`headersHelper` の予算は
  **10 秒・キャッシュ無し・接続ごと・401 リトライごとに再実行**なので、kagi を
  ホットパスに置くと予算の 4〜5 割を毎回使い、負荷時に落ちる。
  **消して再構築できるか**の判定どおり、kagi = premise（custody / `kagi rotate` /
  `kagi device grant` / 監査台帳）、Keychain = projection（消しても kagi から
  作り直せる）。**Keychain 側を先に書き換えて kagi を放置しない** —— そうした
  瞬間に projection ではなく mirror になる。
- **キャッシュの再構築**（Keychain 項目を消した / 別マシンに移した / rotate した）:

  ```bash
  # 値は端末に出さず、隠しプロンプトに貼る（-w を最後に置くとプロンプトになる）
  security add-generic-password -a telnyx -s telnyx-api-key -U -w
  ```

  kagi 側から流し込む場合も、値を argv に置かないこと（`ps` に見える）。
- **rotate したら 2 か所とも更新する。** `kagi rotate telnyx-api-key` は kagi の
  DEK 再封緘であって Telnyx 側の鍵の再発行ではない —— Telnyx Portal で新しい
  キーを発行し、kagi に入れ直し、Keychain キャッシュを作り直す、が 1 組。
- ⚠ **このキーの権限が唯一の境界。** `api.telnyx.com/v2/mcp` は本番アカウントに
  対して番号購入・発信・AI assistant 作成ができる。読み取り中心の用途なら
  Portal 側で権限を絞ったキーを発行して、それをここに入れる。
- 🔴 **2026-08-15 に発行した初回キーは会話ログへ平文で貼られている。rotate 対象。**
  `REALTIMEKIT_API_TOKEN` と同じ経路（このファイル内の前例を参照）。rotate は
  Telnyx Portal で新キーを発行 → kagi を更新 → Keychain キャッシュを作り直す、
  の 3 手で 1 組。**`kagi rotate` は DEK の再封緘であって Telnyx 側の再発行では
  ないので、これだけでは終わらない。**
- **格納は argv を経由させない。** `security add-generic-password ... -w` は
  値を**2 回** stdin から読む（1 回だけ渡すと `passwords don't match` で失敗
  する。実測 2026-08-15）ので、`{ cat f; echo; cat f; echo; } | security …` の形。
  `-w "$VALUE"` は `ps` に見え、このマシンは並行セッションが多い。
  また **`security … -w` の出力は末尾に `\n` が付く** —— kagi へ複製するときは
  `tr -d '\n'`、`headersHelper` の `$(…)` は自動で落とすので対処不要（実測）。

### ⚠ `claude mcp list` の `✔ Connected` は鍵が通った証拠ではない（2026-08-15 実測）

**Telnyx の MCP は `initialize` / `tools/list` / `resources/list` / `resources/read`
を認証なしで公開しており、Bearer を要求するのは `tools/call` だけ。** したがって
**Keychain が空でも `✔ Connected` は出る**。実際にこの順で誤診した:

1. 鍵ゼロ件の状態で `claude mcp list` → `telnyx: ✔ Connected`
2. それを見て「helper が鍵を読めている」と報告した（**誤り**）
3. 最初の `tools/call` で `requires re-authorization (token expired)` になり切断

**鍵が通ったことの確認は、認証が要るエンドポイントを 1 本実際に叩くこと。**

```bash
K=$(security find-generic-password -s telnyx-api-key -w)
curl -sS -g -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $K" \
  'https://api.telnyx.com/v2/phone_numbers?page[size]=1'   # 200 なら有効
```

⚠ **`-g`（globoff）が要る** —— curl は URL 中の `[` を範囲指定と解釈して
`bad range in URL` で落ちる。これも「鍵の問題」と誤読しやすい。

**同じクラスの沈黙がこの環境の 1Password にもある**（`op vault list` は未サインイン
でも **exit 0 + 出力ゼロ**、`op signin` は非 TTY で**無言で何もしない**、
`op item get` は rc=124 で無言タイムアウト）。**「空の結果」を「無い」と読まないこと。**

## 生成モデル・RealtimeKit

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
