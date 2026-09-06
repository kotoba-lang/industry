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

## hyakka (wiki.kotobase.net) identity seed — 2026-08-15 に消え、2026-08-24 に v2 へ再作成、**2026-08-27 に再び `no such item`**

### 2026-08-28: v1 seed は `vault.edn.bak` から復旧できた。v2 は依然オーナーのみ

- ✅ **旧 (v1) seed は失われていなかった。** `~/.kagi/vault.edn.bak`（08-15 12:06 =
  03:06 UTC、消失の直前）から `hyakka-kotobase-seed` を取り出せ、
  `did:key:z6MkuEj8…` を derive する。08-15 の調査が「復旧手段は尽きた」と結論した
  のは、その時点の `.bak` が 7/19 だったため —— **その後の kagi 操作が .bak を
  消失直前の vault で上書きしていた**。`hyakka-kotobase-seed-v1-orphan-recovered`
  として保全した（compartment `personal`）。v1 graph は orphan のままだが、
  読むことはできる状態に戻った。
- 🔴 **v2 seed は復旧できない。** cloud vault（`kagi pull`、最終 push 7/25）に無く、
  mint は 08-24。`vault.edn.bak` は v1 時代のもの。APFS local snapshot 0 件、
  Time Machine の destination 未設定、`kagi recovery` の share も無し。
  **オーナーによる再登録以外に経路が無い**（32 byte hex を stdin で）。
- ⚠ **v1 を `hyakka-kotobase-seed` に戻してはいけない。** 2026-08-28 に一度そうして
  しまい、次の resident tick（15 分後）が 296 ledger を orphan graph へ publish する
  ところだった。publish は失敗しない —— `:apex` は issuer DID の graph に書くので、
  **黙って別の ref へ分岐する**。
  - 誤りの形: `.bak` から取れた seed が 64-hex で、その DID が ADR-2607311100 に
    記録されていたことを「一致」と読んだ。**同じファイルの 4 行下が「旧 DID」と
    書いている。** 1 行目で止めたのが原因。
  - 現在 `hyakka-kotobase-seed` には非 64-hex の説明文字列を入れて塞いである
    （kagi に削除コマンドが無いため）。v2 復元時はそのまま上書きすればよい。
- 🛡 **構造的な防止を入れた。** `scripts/hyakka-knowledge-resident.cljs` が publish の
  前に `scripts/verify_identity.cljs` で **derive した DID を
  `did:key:z6MkwF7M3TPYUvdNP5NtWfr6aA2xtr7dsETCwx26fnVamjQo` と突き合わせる**。
  不一致なら publish せず理由を出して SKIP（公開カタログの deploy は独立して続く）。
  両方向で実測済み: v1 seed × v2 DID = 拒否 / v1 seed × v1 DID = 通過。

- 🔴 **2026-08-27 04:5x UTC 実測: `hyakka-kotobase-seed` は live vault (`~/.kagi`) にも
  repo-local vault にも存在しない**（両方で `no such item`）。**同じ item が消えるのは 2 度目**
  で、この乖離クラス全体では 5 例目。
  - **「読めなかった」ではなく「無い」であることは確かめた**（この区別を飛ばすと
    復旧手順を誤る）: 同じ `KAGI_HOME=$HOME/.kagi` で `kagi whoami` は
    `did:key:z6MkeaQ3TzXk8H7ZyEcrQyTM1iNEkJ2gmq1BdXsauaPGBTC1` を返し、
    `unlock-status` は os-keychain wrap が 1 本ある状態を返す —— **vault は開いている**。
    総当たり列挙はしていない（安全床⑦、`kagi ls` は叩いていない）。
  - `~/.gftd/` にも seed ファイルは無い（あるのは `hyakka-archive/` だけ）。
  - **実害**: `npm run publish`（ledger → kotobase `hyakka` ref の projection）が
    起動できない。2026-08-27 に landed した kaiyaku corpus の 104 claim / 13 source は
    **Git と Worker catalog には在るが、datom 面には入っていない**。
    read 経路（`read_bisect` / `fold`）も同じ seed を要求するので、
    **ref が今どこまで進んでいるかも測れない = UNVERIFIED**。
  - **新しい seed を作って代替しないこと。** 別 seed = 別 DID = 別グラフで、
    `:apex` は graph scope == issuer DID を要求する（下の 2026-08-15 の記録と同じ理由）。
    復旧はオーナーによる再登録:
    `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi add hyakka-kotobase-seed`
    に v2 の 32 byte hex を流す。**v2 の tenant DID は
    `did:key:z6MkwF7M3TPYUvdNP5NtWfr6aA2xtr7dsETCwx26fnVamjQo`** なので、
    復元した seed からこの DID が derive されることを
    `scripts/verify_identity.cljs` で確認してから publish を再開する。
  - **projection がいつから止まっているかも測った**: この端末で publish 管理簿は
    `~/.gftd/worktrees/app-hyakka-resident/.resident/published.edn` の 1 本だけで、
    **最終更新 2026-08-15 12:00 / 収録 100 file**（最後に publish された ledger は
    2026-08-15T02:41）。main の ledger は 2026-08-27 時点で **362 file** なので、
    **datom 面は 12 日分・262 file 遅れている**。
    さらに、下の 2026-08-24 の記録が「退避済み」と書いている
    `~/.gftd/hyakka-publish-published-v2.edn` は**この端末に存在しない** ——
    290/291 file の re-publish が実際に完了したかどうかは、ここからは
    **UNVERIFIED**（別端末で行われた可能性は残る）。
  - 消失の原因は未特定。**2 度目である以上、次も起きるとみなす** —— seed を
    kagi だけに置く運用そのものが単一障害点で、`kagi push`（cloud 永続化）か
    別端末への `device grant` のどちらかを取るまでこの節は閉じない。

### 2026-08-24 の再作成（この時点では在った）

- ✅ **2026-08-24: `hyakka-kotobase-seed` は新しい 32-byte seed で再作成済み**（この端末の
  kagi、compartment `personal`、item 名は同じ）。**新 tenant DID =
  `did:key:z6MkwF7M3TPYUvdNP5NtWfr6aA2xtr7dsETCwx26fnVamjQo`**。旧 seed の復元経路は
  全て尽きた上での migration（vault.edn.bak は 7/19 で mint 7/31 より古い、kagi cloud
  同期の最終 push は 7/25、APFS/TimeMachine snapshot 無し、fleet ノードに vault 無し、
  vault ledger は item 名を持たない）。git の全 knowledge ledger（正本）を新 DID の
  graph へ re-publish した（2026-08-24 時点 290/291 file。残る jp-tetsuzuki seed 1 file と read は
  production backend の CPU 崖で保留 — graph が肥大し、fold の全経路が死んでいる:
  client fold は apex で Unauthorized、旧 engine の fold は folded:false の no-op、
  fold.cljs は退役 D1 で 410。可視化の unblock は engine main の silent-partial read
  退行の修正 → production deploy → bounded fold。ADR-2608170300 参照）。
  情報損失はゼロ、失ったのは旧 graph の identity 継続性のみ。
  publish 済み管理簿は `~/.gftd/hyakka-publish-published-v2.edn` に退避済み。
- **旧 DID `did:key:z6MkuEj8M1GKrAqW8ZsenbickiLvpfzJFLpNpxgyggcm1DKv` の graph は
  orphan**（append-only のまま残る。誰も書けない・resident の published.edn 追跡外）。
- ⚠ **resident（別ホストで稼働中）はこの端末の kagi を読めない** — vault は 7/25 から
  同期されておらず端末間で分岐している。resident の publish を復帰させるには、その
  ホストの kagi に同じ item を登録するか `kagi push`/`pull` の同期判断が要る
  （push は last-writer-wins なので相手 vault の内容確認が先）。

### 旧記録（2026-08-15 の消失時点、経緯として保存）

- ⚠ **`hyakka-kotobase-seed` は 2026-08-15 13:00 UTC 時点で kagi に存在しない**
  （`KAGI_HOME=$HOME/.kagi` で `no such item`）。**同じ乖離の 4 例目**
  （`itonami-marketplace-kotobase-seed` / `MURAKUMO_GENERATION_TOKEN_SECRET` /
  `MURAKUMO_CHAT_TOKEN_SECRET_2` に続く）。
  - **他の 3 例と違い、これは「保管されなかった」ではない。** 同日 03:00 UTC の
    常駐 tick は Kotobase publish に成功して `.resident/published.edn` を書いており、
    その時点では読めていた。**03:00 と 12:56 の間に失われた**（原因未特定。
    vault の総当たり列挙はしていない —— 安全床⑦）。
  - **記録上の所在**: ADR-2607311100 と
    `scripts/hyakka-knowledge-resident.cljs` の `kotobase-seed` が
    ともに item 名 `hyakka-kotobase-seed`、compartment `personal` と書いている。
    名前の食い違いではない。
  - **新しい seed を作っても代わりにならない**（marketplace と同じ理由）。
    tenant DID は `did:key:z6MkuEj8M1GKrAqW8ZsenbickiLvpfzJFLpNpxgyggcm1DKv` で、
    `:apex` は graph scope == issuer DID を要求する。別 seed = 別 DID = 別グラフになり、
    既存の 630 claim / 147 source を持つ `hyakka` ref には書き込めない。
  - **実害**: 収集は動いている（ledger は main に着地、raw は B2 に上がっている）が、
    **datom 面への projection と Worker deploy がここで止まる**。2026-08-15 13:00 時点で
    未 projection の ledger 2 本（NVD CVE 5 件 + ELDEN RING + Tokyo Station を含む）。
    live の `/health` は 630 claims のまま。
  - **復旧に要るもの**: オーナーによる seed の復元（バックアップからの再登録）。
    `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi add hyakka-kotobase-seed`
    に元の 32 byte hex を流す。復元後は常駐 tick が未 projection 分を
    自動で追いつかせる（`unpublished-ledgers` が published.edn との差で拾う）。

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

## itonami.cloud app site IPNS (2026-08-14 公開名 / 2026-08-15 実測)

- **`itonami-site-ipns/app/shirohan`（kagi vault、compartment `personal`、
  `KAGI_HOME=$HOME/.kagi`）** — `app/shirohan` の IPNS 名用 Ed25519 seed。
  `sites.edn` の公開名は
  `k51qzi5uqu5dgxdm1x95y3dk2nzj9xnz7ntyv08jbrzfacpj53mncoebqe3m92`。
  **2026-08-15 実測: live vault で `no such item`。** 既知の別名
  (`itonami-site-ipns-app-shirohan` / `itonami-site-ipns/shirohan`) も無い。
  `~/.gftd` にも shirohan IPNS ファイルは 0。catalog の
  `.itonami/identity.edn` と `cloud-itonami-app-latest` は**別の k51**。
  この公開名を更新するには元の 32-byte が要る。新しい seed は別 k51 になり、
  代わりにならない。値は git に置かない。

## did:webvh apex root seeds (2026-09-02, control-plane ADR-2609021500)

- **macOS login Keychain, generic-password items `webvh-root-kotoba.cloud` /
  `webvh-root-kotobase.net` / `webvh-root-auth.kotoba.cloud`（account
  `authn`）** — 各 apex の did:webvh root の **唯一の seed**（32 byte、base64）。
  update-key の ladder（`update-0`, `update-1`, …）と 5 つの witness 鍵は、
  この seed から HKDF-SHA256 で導出される（`net-kotobase/control-plane`
  `authn/scripts/webvh_root.clj`）。取得は
  `security find-generic-password -s webvh-root-<domain> -a authn -w`。
  値は git にも Worker secret にも置かない —— Worker は署名済みの
  `did.jsonl` を配信するだけで、root を回転させるのはこの seed を持つ
  操作者だけ。
- **kagi vault にも同名で複製済み（`webvh-root-kotoba.cloud` /
  `webvh-root-kotobase.net` / `webvh-root-auth.kotoba.cloud`、compartment
  `personal`、`KAGI_HOME=$HOME/.kagi`、2026-09-02）。** 取得は
  `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get webvh-root-<domain>`。
  Keychain と kagi は**同じ 32 byte** で、どちらか片方が読めなくなっても
  もう片方から `webvh_root.clj` を回せる。
  - 同日の先行記述「kagi には未保管」は、`bin/kagi` が無応答に見えたため
    だった。実測では hang ではなく **1 コマンド約 2 分**（`unlock-status` で
    wall 113 s / CPU 38 s、load 100 超のこの端末で）。`timeout 60` では
    必ず切れるので、kagi を呼ぶときは `timeout 900` 以上を付ける。
  **seed を失うと、その apex の did:webvh は二度と更新できない**（SCID は
  残るが次の entry を署名できる鍵が無い）。
- **witness の seed は apex root seed から独立している（version 2 以降、
  2026-09-02、control-plane ADR-2609021700）。** 3-of-5 の 5 role それぞれが
  自分の 32 byte を持ち、置き場所が custodian を表す:

  | role | 置き場所 |
  |---|---|
  | security / legal | この端末の login Keychain `webvh-witness-<domain>-<role>`（account `authn`）+ kagi の同名 item（compartment `personal`） |
  | operations | fleet node **judah** `~/.gftd/webvh-witness/<domain>-operations.b64`（0600、FileVault） |
  | auditor | fleet node **simeon** `~/.gftd/webvh-witness/<domain>-auditor.b64` |
  | recovery | fleet node **levi** `~/.gftd/webvh-witness/<domain>-recovery.b64` |

  `<domain>` は `auth.kotoba.cloud` / `kotobase.net` / `kotoba.cloud` の 3 つ、
  計 15 seed。ノードの seed は**ノード上で生成し、ノードから出さない**（ssh で
  渡るのは enrollment 時の did:key と version ごとの proof だけ）。ノードの
  login keychain は ssh 越しに書けない（`Write permissions error`、実測）ので
  file 置き。署名はノード上の `authn/scripts/webvh_witness.cljs`（nbb、lib は
  `~/.gftd/webvh-witness/lib/` に rsync 済み。levi は `npx --yes nbb`）。
  **auditor の seed は witness proof だけでなく whois の第三者証明にも署名する**
  （2026-09-02、ADR-2609021700）。simeon 上の `webvh_attest.cljs` が log を
  公開 URL から取得・検証し、`DidLogAttestationCredential` をこの鍵で発行して
  `whois.vp` に載せている。**この鍵を rotate すると、過去の attestation は
  暗号的には有効なまま「文書が名指ししていない発行者」になる**
  （`webvh_root.clj verify` が `UNNAMED issuer` と報告する）。rotate するなら
  witness param を更新する version と、新しい鍵での attestation 再発行を 1 組で行う。

  **この端末が持つのは update key + witness 2 つで、閾値 3 に 1 つ足りない**
  —— 1 台の compromise では version を publish できない。1 ノードが死んだら
  その role は新 seed を別 host で作り直し、残り 3 + 新 1 で witness param を
  restate する version を出す。

## EVM deployer（Base）

- **`kotoba-cloud-registry-deployer-base-sepolia`（kagi vault、compartment
  `personal`、category `:password`）** — `DelegationRootRegistry`
  （ADR-2800011000）を Base Sepolia へ deploy する秘密鍵。
  - **address `0x1210bdA52642223b3AC58e50f0D2723506ecB22A`**（公開値。
    残高と nonce はここから引ける）
  - 使い方: `npm run deploy:registry -- --kagi
    kotoba-cloud-registry-deployer-base-sepolia`。script が `kagi get` を
    pipe で受けるので、値は argv にもシェル履歴にも載らない。
  - 生成 2026-09-06。`cast wallet new` → stdin で `kagi add`、往復確認
    （`kagi get` → `cast wallet address`）で address 一致を実測済み。
  - ⚠ **mainnet 用ではない。** Base mainnet へ出すなら別 item を作る ——
    testnet の鍵を本番に流用すると、faucet 経由で誰の手にも渡りうる
    生成履歴を持つ鍵が本番の controller になる。
  - **未 funding**（2026-09-06 時点で残高 0）。deploy には Base Sepolia の
    faucet が要る。

- **Basescan / Etherscan の API 鍵は未登録**（`BASESCAN_API_KEY` /
  `ETHERSCAN_API_KEY` とも unset、2026-09-06 実測）。
  `app_kotoba_cloud.chain-facts` はこれが無いと provenance
  （初出・資金源）を取得できず、guardian は仕様どおり全件 veto する。
  **鍵が無いことは「安全」ではなく「recovery が完了できない」を意味する。**

