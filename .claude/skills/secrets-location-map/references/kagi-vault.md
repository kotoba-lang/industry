# kagi vault — 実体・unlock・2 つある vault・端末登録

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
  - **passkey PRF unlock 封筒（2026-09-02 時点で未登録）。** `kagi.unlock` は
    VMK を multi-wrap でき、`bin/kagi unlock-enable-passkey` が WebAuthn PRF
    （`prf.eval.first`）由来の鍵で VMK をもう 1 枚包む。手順: JVM 起動（この端末で
    約 2 分）→ 既定ブラウザが `http://localhost:<port>/#<token>` を開く →
    **120 秒以内に Touch ID** → `{:ok? true :enabled :passkey-prf}`。
    `kagi unlock-status` の wrap が `:os-keychain` + `:passkey-prf` の 2 枚になる。
    2026-09-02 に agent が起動したが、オーナー不在で 120 秒の窓を過ぎ
    `passkey registration timed out`（vault は無変更）。**登録はオーナーが
    端末に居るときに `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi
    unlock-enable-passkey` を回す。** kagi pin `1f3dba2`（PR #32）以降が要る
    —— それ以前は bridge が `127.0.0.1` を RP id にしていて Chromium が
    `SecurityError: This is an invalid domain` で拒否する。
    ⚠ 登録できても **CLI の unlock は passkey を使わない**（`unlock-vmk-auto` は
    `KAGI_MASTER` → OS Keychain → passphrase の順だけ。`unlock-with-passkey-prf`
    は関数として在るが `bin/kagi` から呼ばれていない）。passkey 封筒は今日の
    ところ「Keychain が壊れたときの第 2 の鍵」であって、日常の unlock 経路ではない。
  ⚠ **2026-08-06 実測: `KOTOBA_SEED_PRODUCTION` / `KOTOBA_SEED_TESTNET` /
  `KOTOBASE_B2_KEY_ID` / `KOTOBASE_B2_APP_KEY` は 4 件とも存在しない** —
  live vault（`KAGI_HOME=$HOME/.kagi`）と repo-local vault
  （`orgs/kotoba-lang/kagi/.kagi`）の**両方**で。ここにはかつて「既存 item 例:
  `net-kotobase` compartment に `KOTOBA_SEED_PRODUCTION`/`KOTOBA_SEED_TESTNET`/
  `KOTOBASE_B2_*` 等」と書いてあったが、**その記述を信じて計画を立てると詰まる**。
  `MURAKUMO_GENERATION_TOKEN_SECRET` / `MURAKUMO_CHAT_TOKEN_SECRET_2` /
  `itonami-marketplace-kotobase-seed` と同じ乖離の 4 例目。

  詳細は `references/kotobase.md`（「kotobase-graph-database の secret は
  Cloudflare の中にしか無い」節）。

## ⚠ kagi vault は 2 つある — `~/.kagi` が live（2026-07-29 実測）

**`bin/kagi` は自身の repo root へ `cd` するため、既定では
`orgs/kotoba-lang/kagi/.kagi/vault.edn`（2026-07-17 付・56KB の**古い方**）を読む。
実際に使われている vault は `~/.kagi/vault.edn`（日々更新）。**

⚠ **この行はかつて「2.1MB」と書いていたが、2026-08-15 の実測で 5.7MB だった。**
サイズも `kagi get` の所要時間も**育つ側の値**なので、ここに数値を書き足さない
（書いた瞬間に次の読み手が定数として引用する）。ホットパスに kagi を置くか
判断するときは、その場で測る:

```bash
/usr/bin/time -p env KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get <既知のitem> >/dev/null
```

**`kagi get` は秒オーダーであり、ミリ秒オーダーではない。** タイムアウトのある
呼び出し元（MCP の `headersHelper` は 10 秒・キャッシュ無し・接続ごとに再実行）
から直に叩く前に必ず測ること。実例は
`references/third-party-api.md` の Telnyx 節（kagi を正本、OS Keychain を
projection として分けた）。
**どの reference ファイルの item であれ**、「無い」と判断する前に必ず
`KAGI_HOME=$HOME/.kagi` を付けて引き直すこと:

```bash
KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get <ITEM>
```

実害: 2026-07-29、この差で murakumo generation secret を「消失」と誤判定しかけた
（結果的に live 側にも無く再発行したが、判定根拠としては不正確だった）。
**安全床⑦により、見つからない時に `kagi ls` で総当たり列挙してはいけない** —
既知の識別子で狙い撃ちし、それでも無ければオーナーに聞く。

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
