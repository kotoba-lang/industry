# kotobase — 読み戻せない secret と archive write token

## kotobase-graph-database の secret は Cloudflare の中にしか無い（2026-08-06 実測）

**Worker script `kotobase-cf-wasm-staging`（= 本番 `graph-database.kotobase.net` /
`backend.kotobase.net` を配信）が持つ 3 つの secret は、どこからも読み戻せない。**

| secret | 役割 | 可読な複製 |
|---|---|---|
| `KOTOBA_SEED` | 本番 operator Ed25519 seed。**IPNS head に署名する** | **無し** |
| `KOTOBASE_B2_KEY_ID` | B2 bucket 스코프鍵 | **無し** |
| `KOTOBASE_B2_APP_KEY` | 同上 | **無し** |

`env.testnet`（`kotobase-cf-wasm-testnet`）も同様に 4 件（上記 3 + `KOTOBASE_PROLLY_STANDALONE_CRYPTO_SEED`）
を持ち、`KOTOBA_SEED_TESTNET` も vault に無い。

**これが何を塞いでいるか。** Cloudflare の Worker secret は **per-script かつ
write-only** で、script 名を変えると別 script が生まれ secret はコピーされない。
つまり **`kotobase-cf-wasm-staging` を capability 名に改名できない**（runtime 名 +
間違った環境名で本番を配信し続ける）。`kotobase-graph-database/wrangler.jsonc` の
`env.testnet` R2 セクションが「That is what blocked kotobase-cf-wasm-staging from
becoming net-kotobase-engine」と書いているのはこの制約のこと。

**`KOTOBA_SEED` は単なる資格情報ではない。** これが変わると operator DID が変わり、
content-addressed graph の名前空間ごと変わる — 障害ではなく**データ面の同一性の変更**。
「動かなくなったら再発行すればいい」で済む種類のものではない。

**⚠ 2026-09-09 実測: 上の表の 3 件を「供給すればよい」と読まない。選択肢 2 は
本番では既に完了しており、B2 の 2 件は本番が捨てたものである。**

    GET https://datoms.kotobase.net/_health
      200  {ok true, missing [], keyring {ok true},
            object_store {mode "r2", ...}}

`control-plane/kotobase-graph-database`（= `kotobase-cf-wasm-staging`、
`datoms.kotobase.net` を配信）の production env は **`KOTOBASE_R2` →
`kotobase-graph-database-production` binding** を持ち、B2 vars は
`KOTOBASE_B2_RETIRED` 一本に退役済み。その `readiness` も R2 を知っている ——
storage mode が `:r2` で `KOTOBASE_B2_RETIRED=1`（または B2 credential が
一つも設定されていない）なら **B2 の 2 件を要求しない**。だから healthy。

一方 **`net-kotobase/engine`（= `kotobase-engine-candidate`、`backend.kotobase.net`）は
R2 移行前の fork**: 全 env が R2 binding を持たず B2 vars のままで、`readiness` は
B2 2 件を無条件に要求する。だから 503 で 3 件を欠落として挙げる。

**この 503 に 3 値を供給するのは誤った修正である。** 同じ operator DID /
content-addressed 名前空間に対して、incumbent が R2（`kotobase-graph-database-production`）
に、candidate が B2（`kotobase-cf-wasm-production`）に書く **split brain** を作る。

**candidate を healthy にする正しい順序**:
1. engine を incumbent の storage 移行に追いつかせる —— `KOTOBASE_R2` binding と、
   R2 を知る `readiness`（control-plane の `object-store/mode` + `KOTOBASE_B2_RETIRED`
   分岐）を移植する。**これで B2 の 2 件は構造的に消える**（binding は資格情報を要さない）。
2. 残るのは `KOTOBA_SEED` **1 件だけ**。しかも incumbent と**同一の値**でなければならない
   —— 別 seed は別 operator DID = 別グラフで、既存の head チェーンから切れる（下記）。
   incumbent の script secret は write-only なので、値はオーナーの手元にしか無い。
3. そもそも 2 つの木（engine と control-plane/kotobase-graph-database）が同じ worker の
   複製で、storage 移行と readiness の両方で乖離している。片方に追いつかせるより
   「どちらが正本か」を決める方が安い可能性がある —— これはオーナー判断。

**2026-09-09 に kagi を実測した結果**（識別子を狙い撃ち、列挙なし）:
`KOTOBASE_B2_KEY_ID` / `KOTOBASE_B2_APP_KEY` / `KOTOBA_SEED` は 3 件とも
`no such item`（exit 1）。B2 Master Key（`260421-BACKBLAZE_MASTER_KEY_ID` /
`_KEY`）も kagi には無く、1Password item のフィールドとして記録されている。

**agent は seed を推測・再生成して代替しないこと。** 新しい seed は別 DID = 別グラフで、
既存の head チェーンから切り離される。


## kotobase.net archive write token (2026-07-29)

- **`KOTOBASE_ARCHIVE_TOKEN`（kagi vault、compartment `net-kotobase`）** —
  `kotobase.net` の first-party content-addressed archive（`PUT /ipfs/:cid`、
  `kotobase.archive-put`）の write gate。Worker `net-kotobase` の同名 secret と同値。
  **この Worker secret は以前から設定されていたが値はどこにも保管されておらず
  読み戻せなかった**ため、2026-07-29 にオーナー承認のもと再発行した
  （workspace 全体を grep して consumer ゼロを確認した上で交換）。
  取得: `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get KOTOBASE_ARCHIVE_TOKEN`。
- ⚠ **2026-09-06 実測: `no such item` に戻っている。** `KAGI_HOME=$HOME/.kagi
  orgs/kotoba-lang/kagi/bin/kagi get KOTOBASE_ARCHIVE_TOKEN` が exit 1 /
  `no such item: KOTOBASE_ARCHIVE_TOKEN`。**同じ item で不在→在→不在が 3 回**なので、
  この項の可否は毎回その場で測ること（下の 2026-08-27 の記述は当時の実測として残す）。
  live Worker の secret 側が生きているかは別問題で、ここからは測れない。
  取りにいけない間は archive 面への publish はオーナー操作（安全床①、agent は
  token を再発行・推測しない）。
- **2026-08-27 実測: この item は kagi vault に在り、live Worker に受理される。**
  `kagi get KOTOBASE_ARCHIVE_TOKEN` が exit 0 で値を返し、その値で
  `cloud.itonami.app.bundle put` と `cloud.itonami.app.graph put` が
  **PUT 201 → GET 200 → バイト一致**まで通った
  （bundle `bafkreihqpy5ylnfb2lrsjei4z54gs5bbqnjejrfhhtc4h3ctm6ma2hstqm`）。
  下の 2026-08-21 の測定は解消済み。
- ⚠ **その 2026-08-21 の測定を、この節は 6 日間「取れない」として掲げていた** ——
  `no such item`（exit 1、2 回再現）と `..._2` の 401 は当時の実測として正しかったが、
  vault が直された後もここに残り、**取りにいけば取れるものを「オーナー操作が要る」と
  読ませていた**。日付を書いても、引用する側は日付を落とす。
  **この節の値は使う前にその場で測ること**（`kagi get` の exit code が答えで、
  `no such item` は stderr に出る）。当時の内容は `git log -p` に、経緯は
  ADR-2608147400 にある。
- **使い方**: raw CIDv1（`bafkrei…` = 本体バイト列の sha2-256）を自分で計算し、
  `PUT https://kotobase.net/ipfs/<cid>` に Bearer で置く。サーバが digest を再計算して
  不一致は 422 で弾く。読みは無認証の `GET /ipfs/<cid>`。**未設定だと 403（feature off）**。
  live 検証済み（2026-07-29、PUT 201 → GET 200、バイト一致）。
- 消費側: `dougaka.archive`（production artifact の保管、ADR-2607299960）。

## hyakka tenant + service account（Biscuit 移行対応、2026-08-29 provisioning、ADR-2608291500）

datom 面が Biscuit 必須（ADR-2608281200）になり CACAO 自己発行 transact が 401 になった件の対応として、
`auth.kotobase.net` に hyakka 用 tenant + editor service account を発行した。すべて kagi（compartment
`personal`、`KAGI_HOME=$HOME/.kagi`）にあり、**値はここに書かない — その場で `kagi get` する**。

| kagi item | 中身 | 用途 |
|---|---|---|
| `hyakka-authn-owner-seed` | 32-byte hex（Ed25519 seed） | tenant owner。この seed の CACAO で `/v1/cacao/session` を張り tenant/service-account を管理する。**custody-first で発行前に read-back 検証済み** |
| `hyakka-authn-service-token` | service-account bootstrap secret（opaque Bearer） | `POST /v1/biscuit/token`（`tenantId` + `dbName=hyakka` + `permissions`）で短命 Biscuit と交換する。**この token 自体は datom 面に直接使わない** |
| `hyakka-authn-tenant-id` | `t_1fb2227009234c0faf418b37`（**公開識別子、secret ではない**） | Biscuit 発行の `tenantId` |
| `hyakka-authn-tenant-graph` | `bafyreig6tog2…`（公開 CID、`canonical-graph(tenantDid, dbName)` の決定的導出） | transact/put の ref `kotobase/db/<graph>/hyakka` |
| `hyakka-authn-tenant-did` | `did:web:kotobase.net:tenant:t_1fb2227009234c0faf418b37`（公開） | Biscuit の holder / `x-kotobase-tenant-did` |

- **service account の role は `editor`**（`data:read` + `data:write` + `audit:read`）。
- **owner seed と service token を混同しない。** owner seed は「tenant を管理する権利」、service token は
  「その graph に書く権利」。datom 面への書き込み経路は必ず service token → Biscuit → tenant graph。
- **live 検証済み（2026-08-29）**: service token → `/v1/biscuit/token` 201 → `POST kotobase.net/api/transact`
  に `Authorization: Biscuit` で **transact 200**（同じ graph へ 3 回の mint が同一 CID を返し決定的も確認）。
- **consumer**: cloud 常駐 `itonami-grok-bots` の `HYAKKA_AUTHN_SERVICE_TOKEN`（Worker secret、
  `HYAKKA_AUTHN_TENANT_ID` は var）。ローカル常駐（`com.network-awai.hyakka-knowledge-ingest`）は
  同じ 3 item を env で読む経路へ移行予定（未完なら pending ledger は CACAO のまま 401）。
- **`/ipld/*` の block PUT は依然 CACAO（`HYAKKA_SEED`）で Biscuit 不要** —— 401 していたのは transact だけ。
  proof/record block は移行前から R2 に着地していた。
