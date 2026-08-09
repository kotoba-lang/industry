# モバイル公開 — Apple / Google Play

## Apple — App Store Connect / 署名（ADR-2608081200、2026-08-08 実測）

**「証明書が無い」「まだアップロードしていない」と結論する前にここを見ること。**
実際には有料アカウントが稼働しており、一度 App Review まで到達している
（`Spirit in Physics` 1.0 が REJECTED）。local-manimani ADR-0038 と
ADR-2608072000 gap #2 はこの点で実態より悲観的に書かれていた。

- **秘密は `.p8` ファイル 1 つだけ** — `~/.appstoreconnect/private_keys/AuthKey_62BW4Q57AB.p8`
  （mode 600、2026-02-05）。**kagi にも 1Password にも複製が無い** ——
  このマシンのこのファイルが唯一の複製で、失うと ASC API 経路が止まる
  （Web UI から新しいキーを発行し直すことは可能）。**owner 作業として kagi
  compartment `personal` に写すべき**（agent は `.p8` を読み出して別の場所に
  書く操作を安全床①の周辺として避ける）。
- **以下は識別子であって秘密ではない**（`REALTIMEKIT_ACCOUNT_ID` と同じ扱いで
  値を書いてよい）:
  - ASC API **Key ID** = `62BW4Q57AB`
  - ASC API **Issuer ID** = `69a6de81-326a-47e3-e053-5b8c7c11a4d1`
  - **Team ID** = `3A5CBTEBFP`（Jun Kawasaki, Individual）
  - **itc_team_id** = `90429800` / Apple ID = `jun784@gmail.com`
  - ⚠ **`U7W6HNNJCS`（GIFTED AGENT LLC）は使われていない。** local-manimani の
    ADR-0017 / ADR-0038 がこの team を書いているが、**その team の証明書は
    このマシンに無い**。
- **署名 identity はログイン Keychain にある**（`security find-identity -v -p codesigning`
  で名前だけ列挙できる。狙い撃ちの読み取りであって dump ではない）:
  DISTRIBUTION 2 枚（`2027-02-03` / `2027-02-05` 失効）+ DEVELOPMENT 3 枚。
  **Apple の distribution 証明書上限は 3 なので空きは 1 枚しかない。**
- ⛔ **`match(readonly: false)` / `fastlane create_certs` を安易に実行しない。**
  既存の distribution 証明書を revoke しうる。現在その証明書には
  spirit-in-physics の ACTIVE な provisioning profile 6 本が依存している。
  **新しいアプリを足すのに証明書の再発行は要らない** —— bundle ID と profile を
  足すだけでよい。
- **読み取り専用の確認**: `nbb scripts/asc-query.cljs '/v1/apps?limit=200'`
  （ES256 JWT を自前で mint する。key id / issuer id / `.p8` パスはスクリプト内で解決）。
- **fastlane の設定例**: `orgs/network-awai/deai/appview/deai-cgxi8oem/mobile/ios/fastlane/`
  （Appfile / Fastfile / Matchfile）。運用 runbook は
  `orgs/com-junkawasaki/spirit-in-physics/apple-ios-app-deploy-260206.md`（319 行）。

## Google Play — 何も無い（2026-08-08 実測）

Play Console アカウント、`PLAY_JSON_KEY_FILEPATH` のサービスアカウント JSON、
release keystore、`supply` レーン —— **ワークスペースにもマシンにも痕跡が無い**。
Android を出すには **owner による Play Console 登録（$25 + 本人確認）が先**で、
これは安全床①により agent が代行できない。登録できたらここに
サービスアカウント JSON と keystore の保管先を追記する。
