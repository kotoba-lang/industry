# identity seed / 署名鍵（Ed25519・X25519・secp256k1）

- **`gftd.kotobase/CLOUD_ITONAMI_LEI_INGEST_IDENTITY_SEED`（1Password
  `gftdcojp` vault）+ kagi `CLOUD_ITONAMI_LEI_INGEST_IDENTITY_SEED`
  （compartment `net-kotobase`）— 両方に保管済み** — ADR-2607113500
  （cloud-itonami-lei kotobase.net ingestion job）の自己主権 CACAO identity
  （Ed25519 seed, 32-byte hex）。ローカルミラーは
  `scripts/.kotobase-ingest-cloud-itonami-lei-identity.hex`
  （`scripts/.gitignore` 済み、git に一切コミットしない）。kagi 側は
  既存 vault（OS Keychain unlock、`references/kagi-vault.md`）にそのまま
  `bin/kagi add` で追記
  ——新規 vault や新規 master passphrase の生成は不要だった（オーナーへの
  「新規生成の許可」確認は、vault 未存在という誤った前提に基づいていたことが
  判明したため、実際には生成した passphrase は未使用のまま破棄した）。
- **cloud-itonami ops-repo の鍵(ADR-2607141700、kagi vault
  `orgs/kotoba-lang/kagi/.kagi/`、compartment `personal`、2026-07-14 mint)**:
  - `itonami-org-root` — org root Ed25519 seed(64 hex)。公開 did は
    `orgs/network-awai/cloud-itonami/resources/ops-identity.edn` にコミット済み。
  - `itonami-sales-head` / `itonami-billing-head` / `itonami-keiei-head` —
    部門長 seed(同形式)。
  - `itonami-sales-head-chain` / `itonami-billing-head-chain` /
    `itonami-keiei-head-chain` — 各部門長への CACAO 委任 chain(EDN vector、
    **expiry 90 日 ≈ 2026-10-12。失効前に `clojure -M:ops-send mint-chain
    kagi:itonami-org-root ...` で再 mint**)。merge 時は
    `bin/kagi get itonami-<dept>-head-chain > /tmp/chain.edn` で取り出す。

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

## 確認屋 / manimani per-user (ADR-2607141753、2026-07-14)

- **manimani admin token**(org 側 push/pull 用、wrangler secret `MANIMANI_ADMIN_TOKEN` と同値):
  macOS Keychain `security find-generic-password -a junkawasaki -s "manimani:admin-token" -w`
- **manimani user token (junkawasaki)**(個人 triage 用 interim Bearer):
  Keychain service `manimani:user-junkawasaki-token`
- **owner DID**(非機密・公開値だが参照用): Keychain service `gftdcojp:owner-did`
  = `did:key:z6MkmCrDjqsUiHM4bK6zVyzuYGitMGjCVRb1eTrF122mxrNU`
- **owner Ed25519 秘密鍵(seed)**: `orgs/network-awai/cloud-itonami/.junkawasaki/identity.edn`
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

## swap plane testnet key (ADR-2607261500、2026-07-26)

- **`SWAP_SEPOLIA_TESTNET_KEY`（kagi vault、compartment `personal`）** — Ethereum
  **Sepolia testnet 専用**の secp256k1 秘密鍵（32-byte hex）。address は
  `0xAd785580D9af6AC6e43D19eC5F5F666b6b016EA9`（公開値）。swap plane の on-chain
  検証（`erc20/bin/verify_onchain.cljs`）が `SEPOLIA_KEY` として読む。
  PoW faucet 由来の testnet 資金のみ保持（実価値なし）。**mainnet では絶対に使わない** —
  faucet 経由で公開された address であり、実資金用の鍵はこれとは別に発行する。
  取得: `bin/kagi get SWAP_SEPOLIA_TESTNET_KEY`。

## x402 Base payer (2026-08-14)

- **`X402_BASE_PAYER_KEY`（kagi vault、compartment `personal`、
  `KAGI_HOME=$HOME/.kagi`）** — Base **mainnet** 用の secp256k1 秘密鍵
  （32-byte hex、`0x` 無し）。address は
  `0xb201E44B2317aFFd4c2C20aa15a636cC35e8dd19`（公開値）。
  GLEIF joined-tier listing など family `payTo`
  `0xA00366234D29d4F882088048c0B2fa0dB7302D4E` への x402
  `transaction`-scheme dogfood 専用。**treasury ではない**（第2の収納先を
  作らない）。Safe owner `0xe255d685…` とも別。Sepolia の
  `SWAP_SEPOLIA_TESTNET_KEY` を mainnet に流用しないための新規発行。
  取得: `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get X402_BASE_PAYER_KEY`。
  発行時 Base 残高は ETH 0 / USDC 0 — 送金前にこの address へ gas ETH と
  少なくとも 0.001 USDC を入れる。

- **0x Swap API キーは存在しない**（2026-07-26 時点で確認済み）。`swap.aggregator` の
  `:zero-ex-v2` adapter が `:verified? false` のままなのはこれが理由 — キーを取得したら
  `swap/bin/verify_live.cljs` を実行して live 検証し、フラグを立てる。LI.FI 側は
  キー不要で live 検証済み。

## cloud-itonami-app IPNS latest (2026-08-14)

- **`cloud-itonami-app-latest`（kagi vault、compartment `personal`、
  `KAGI_HOME=$HOME/.kagi`）** — desktop app の `:kotoba.app/latest` 更新チャネル用
  Ed25519 seed（64 hex）。**艦隊共有 `itonami-fleet-kotobase-seed` ではない**
  （graph join 用の 1 本と、この app の latest 名は別の authority）。
  公開 IPNS 名は
  `k51qzi5uqu5dj6z20sjzztyay81591voe6yofukl0ylsmug9euf934z1g04erd`。
  取得: `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get cloud-itonami-app-latest`。
  env は `CLOUD_ITONAMI_APP_IPNS_SEED`。値は git に置かない。
