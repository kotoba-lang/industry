# ADR-2607022330: app-aozora — 鍵の移行/復元 3 経路（passkey-PRF バックアップ / QR デバイスリンク / opt-in リカバリーフレーズ）

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607021400 で aozora.app の self-sovereign 登録（Ed25519 actor key は端末
localStorage のみ、did:key が identity、CACAO 自己発行で createAccount /
createSession）が UI まで通ったが、**端末喪失 = アカウント喪失**が残課題だった
（同 ADR Consequences: 鍵エクスポート/インポート UI と passkey PRF ラップが
follow-up）。オーナー指示（2026-07-02）: 「パスキー PRF ラップ + 暗号文
バックアップ、QR デバイスリンクを含めて。リカバリーフレーズ export は希望する
ユーザーのみ」。設計比較（Signal = 鍵を E2E チャネルで運ぶ / WhatsApp = 鍵を
運ばず端末別鍵 + 署名済みデバイスリスト / iCloud キーチェーンのパスキー同期 =
E2EE プラットフォーム同期）を踏まえ、web だけで完結する 3 経路を実装した。

## Decision 1 — passkey-PRF ラップ + 暗号文バックアップ（既定で提案する経路）

- **暗号**: WebAuthn PRF 拡張の出力（credential 毎 32B、パスキー同期先の端末でも
  同一）→ HKDF-SHA256（salt=ランダム 32B、info `"aozora-key-backup-v1"`、
  non-extractable AES-256-GCM 鍵）→ seed(32B) を封緘。GCM タグにより誤鍵は
  **fail closed**（`yoro-ui.interop.prf-backup` / `key-crypto`）。
- **blob**（JSON、PDS 保存）: `{v 1, credId, prfSalt, hkdfSalt, iv, ct}`（全て
  base64url）。**サーバは平文を一度も見ない**。秘匿性は 256-bit PRF 出力に依存
  するため blob は public-read 扱い（復元はセッション成立**前**に必要なので
  認証必須にはできない。put は認証必須）。
- **PDS**: `app.aozora.key.putBackup`（POST、session did のみ）/
  `app.aozora.key.getBackup`（GET、did|handle）。kotobase datom entity
  `keybackup/<did>`（`:aozora.keyBackup/*`、再 assert で更新）。
- **フロー**: 有効化（/settings）= 既存ログインパスキーで PRF 評価を試行 →
  PRF 非対応なら**バックアップ専用パスキーを新規登録**（`extensions {prf {}}`、
  residentKey required）→ 評価 → 封緘 → putBackup。復元（auth modal「別の端末
  から復元 → パスキー」）= ハンドル入力 → getBackup → blob の credId で PRF 評価
  （UV required）→ 復号 → `actor-key/import-hex!` → CACAO createSession。
- **トラスト境界の変更（意図的）**: 復元可否がプラットフォームアカウント
  （iCloud / Google のパスキー同期、E2EE）に乗る。純端末内キーからの一段の譲歩
  であり、その代替として経路 3（フレーズ）を併設。

## Decision 2 — QR デバイスリンク（クロスエコシステム / 同期なし環境）

- **プロトコル**（`yoro-ui.interop.device-link`）: 新端末が X25519 一時鍵 +
  128-bit link id を生成し、`https://aozora.app/link-device#<b64url(ver‖lid‖npk)>`
  を QR 表示（**payload は #fragment のみ = どのサーバにも送信されない**）。
  旧端末はネイティブカメラで開く → /link-device ページが確認 + （登録済みなら）
  パスキー UV → 自分の一時鍵で ECDH → HKDF（salt=lid、info
  `"aozora-device-link-v1"`）→ AES-256-GCM で `{seedHex did handle repoKeyHex?}`
  を封緘 → `app.aozora.link.deposit`（認証: session Bearer **または** body の
  `cacao_b64` — カメラ起動の新規タブは sessionStorage が空のため、手渡す当の
  actor key の CACAO で DID を証明する）。新端末は `app.aozora.link.take` を
  2.5s ポーリング → 復号 → **SAS（6 桁、sha256("aozora-sas-v1"‖lid‖npk‖opk)）を
  両端末に表示 → 目視一致でインストール** → createSession。
- **リレーは盲目**: npk が out-of-band（QR）なので PDS は MITM 不能。deposit は
  first-write-wins + one-time take + TTL 10 分（`devicelink/<lid>` entity、
  `link-live?`）。QR を覗き見た攻撃者が先に deposit しても SAS 不一致で露見
  （UI は「一致しない場合は中止」を明示）。
- **デスクトップ間**（カメラ無し）: 同じ payload を 66 文字リンクコードとして
  コピーし、旧端末の /settings「別の端末を追加」に貼り付け（同一実装）。

## Decision 3 — リカバリーフレーズ（**opt-in のみ**）

- seed(32B) ⇄ BIP39 英語 24 語（@scure/bip39）。**export は /settings で希望者
  だけ**: 警告文 + パスキー UV ゲート後に一度だけ表示、どこにも保存しない。
  restore は auth modal「フレーズ」タブ。チェックサム不一致は nil（fail closed）。
- 位置づけ: 端末全損 + プラットフォームアカウント喪失 + エコシステム跨ぎに耐える
  最終手段。既定 UI では出さない（オーナー指示どおり希望ユーザーのみ）。

## 共通の安全レール

- **復元ガード**: この端末に**別アカウントの鍵**が既にある場合は上書きせず中止
  （その鍵が他に存在しない可能性があるため。同一 did の再復元は許可）。
- seed を含む payload/blob の鍵材料は Uint8Array を module-local に保持し
  app-db に入れない。SAS/確認コードのみ表示用に db へ。
- 依存追加: `@scure/bip39`（audited）、`qrcode-generator`（純 JS QR 描画）。
  X25519/Ed25519 は既存 @noble/curves v1、AES-GCM/HKDF/SHA-256 は WebCrypto。

## Verification

- appview node tests 30/83 green（PRF wrap/unwrap roundtrip + 誤鍵 reject、
  link code/seal/open roundtrip + SAS 両端一致 + 改ざん reject、フレーズ
  roundtrip + 24×abandon checksum reject、/settings・/link-device ルート）。
- PDS suite 233/1019 green（keylink: blob/param validation、datom scan、
  TTL/one-time、未認証 deposit/putBackup の AuthRequired）。
- release build: SPA `:app` 253 files 0 warnings / PDS `:pds` 0 warnings。
- 本番 smoke: getBackup→BackupNotFound / take→LinkNotFound / 未認証 deposit→
  AuthRequired（デプロイ後に確認）。

## Consequences

- (+) 端末喪失 ≠ アカウント喪失（バックアップ有効化ユーザー）。同じ Apple/Google
  アカウントの新端末は「ハンドル + パスキー」だけで復元。Apple↔Android は QR
  リンク、全損はフレーズ（opt-in）。
- (+) no-server-key 哲学を維持（サーバに渡るのは常に暗号文のみ、リレーは盲目）。
- (−) PRF 経路の復元可否はプラットフォームのパスキー同期仕様に依存（PRF 対応:
  Safari 18+/Chrome 116+ 目安）。旧パスキーが PRF 非対応の場合は専用バックアップ
  パスキーが増える（credId は blob が指すので復元時の迷いはない）。
- (−) getBackup の public-read は「暗号文は公開情報」という設計判断（256-bit
  秘密鍵に依拠）。ハンドル列挙で backup 有無は観測可能。
- (−) deposit の CACAO 認証は既知 actor なら誰でも drop できる = lid capability
  と SAS が実防御（設計どおりだが SAS 目視をユーザーが省くリスクは残る）。
- follow-up: WhatsApp 型「端末別鍵 + DelegationChain」への移行（seed を運ばない
  多端末化、端末失効 UI）、バックアップ削除/ローテート UI、日本語 wordlist。

## Addendum（2026-07-02）— WebAuthn E2E で出荷ビルド固有の :advanced バグを検出・修正（app-aozora `b887802`）

オーナー指摘「WebAuthn は kotoba-lang/playwright で検証できるのでは?」を受け、CDP の
**WebAuthn virtual authenticator（ctap2 + `hasPrf`）**で PRF セレモニーを headless 実行。
これにより **node 単体テスト（`:none`）と `:simple` ビルドでは緑なのに、出荷の
`:advanced` ビルドでだけ PRF バックアップが nil を返す**実バグを検出した。

- **根因**: `getClientExtensionResults().prf` は新しい拡張出力で Closure の externs に
  無いため、`:advanced` が `(some-> ext .-prf .-results .-first)` のダッシュ経由読みを
  rename して nil 化していた（`rawId`/`type` 等は externs にあり無傷 → 発見が遅れた）。
- **修正**: `goog.object/getValueByKeys ext "prf" "results" "first"`（string-key は
  `:advanced` でも不変）。加えて signup のパスキー生成で `{:prf {}}` を要求し、ログイン
  パスキー自体でバックアップを封緘（常用経路は 2 枚目のパスキー不要）。`enable-backup!`
  は create 時の advisory な `enabled` フラグでなく**実 PRF 評価の結果**で判定、バックアップ
  用パスキーは `user.id = <did>#backup` で resident なログインパスキーを上書きしない。
- **検証**: `:advanced` の keytest ブリッジ（`yoro-ui.dev.keytest`、dev 専用・非出荷）で
  実 cljs の eval-prf/enable-backup/restore-from-blob を実 authenticator に対し駆動し、
  ログイン経路・フォールバック経路とも wrap→restore が seed 一致（`match:true`）。本番 UI
  E2E では リカバリーフレーズ経路の登録→表示→別端末復元が緑、PRF バックアップの有効化も緑。
  ハーネスは `60-apps/appview/cljs/e2e/`（`prf_roundtrip.clj` / `key_flows.clj`）に常設。
- **残課題（本機能外）**: getBackup の read と device-link の deposit（read+write）は
  kotobase `yoro-social` の full `:eavt` スキャン肥大による間欠 500（"Invalid array buffer
  length"）に律速され、E2E がこの窓で不安定。これは getAccount 等既存 API も同様に落ちる
  **アプリ全体の既存インフラ課題**で、当機能のバグではない。datom スキャンの
  ページング/インデックス化が別 follow-up。
