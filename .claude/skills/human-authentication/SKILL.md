---
name: human-authentication
description: first-party の human session・identity bootstrap・credential registration/replacement・account recovery を設計・実装・監査するときの正本。Web3 first（SIWE + ERC-191 / ERC-1271）と WebAuthn Passkey を正規手段とし、email / password / SMS / OAuth / SSO / operator override を authority にしない規則、nonce・domain・chain・expiry の server 側検証、recovery の 48 時間 delay、closed legacy route の 404/410、inventory 登録。「login」「認証」「passkey」「SIWE」「wallet 署名」「recovery」「credential」「SECURITY.md」で発火。正本は root SECURITY.md と manifest/human-authentication-policy.edn（ADR-2609070400）。CLAUDE.md の節から 2026-09-11 に切り出した（ADR-2609112300）。
---

# human-authentication — Web3 first、fail closed

**CLAUDE.md の「人間認証は Web3 first」節はここへ委譲している。** 規則の正本は
root `SECURITY.md` と `manifest/human-authentication-policy.edn`（ADR-2609070400）。
ここは CLAUDE.md に在った本文を逐語で保持する。

---

# CLAUDE.md に 2026-09-11 まで残っていた本文（逐語、ADR-2609112300）

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-11、ADR-2609112300。AGENTS.md の
読み込み上限 31,457 字に合わせて CLAUDE.md を不変条件だけに絞った）。CLAUDE.md 側には
skill を読まなくても効く規則だけが残っている。ここが理由・実測・罠の正本。

## 人間認証は Web3 first（repo-wide mandatory、2026-09-07、ADR-2609070400）

first-party の human session、identity bootstrap、credential registration/replacement、
account recovery には root `SECURITY.md` と `manifest/human-authentication-policy.edn` を適用する。
旧 ADR-2608302125 の「Passkey のみ」は当時の決定であり、現在の許可規則としては不適切。
オーナーの Web3 first 方針により、検証済みウォレット署名を正規の認証手段とする。

- Web3 first: SIWE + ERC-191 (EOA) / ERC-1271 (contract wallet) による署名認証を
  第一の選択肢とし、WebAuthn Passkey も正規の手段として維持する。各 product が提供する
  手段は inventory に明記する。方針変更だけで全 product の wallet 対応済みとはしない。
- wallet 接続、address、DID、client hint だけでは認証しない。server-issued single-use nonce、
  domain/origin/URI、chain、expiry、署名、atomic nonce consumption を server 側で検証する。
  ERC-1271 は指定 chain と現在の contract authority を確認し、検証不能なら fail closed。
- Passkey は exact RP ID / Origin、single-use challenge、replay protection、user verification を必須とする。
  SIWE の domain 検証を WebAuthn と同じ phishing resistance と呼ばない。
- Email、password、SMS/voice、OAuth/OIDC/SAML/social/enterprise SSO、support/operator/admin
  override を login、bootstrap、step-up、credential registration、recovery の authority にしない。
  approved authenticator が無い時は fail closed。設定・secret・incident から禁止経路を復活させない。
- login は送金・署名代行・governance の承認ではない。操作別の権限検査を維持する。
  wallet DID と Passkey DID を暗黙に統合せず、既存 identity への credential 追加は既存 owner の
  検証済み権限を要する。wallet login だけで別 identity の復旧はできない。
- recovery は session を直接発行せず、one-time offline secret + 48 時間以上の server-enforced
  delay + fresh approved authenticator による credential replacement とする。
  operator は freeze できるが identity を grant できず、delay を短縮できない。
  wallet 自体の外部 recovery は本サービスの identity recovery を代行・迂回しない。
- closed legacy route は 404/410 で ceremony・redirect・session/token/credential issuance を始めない。
  source / built artifact / live route の negative test に plausible legacy secret を含める。
- 新しい human-auth surface は deploy 前に inventory へ登録する。`:migration-gap` / `:unverified`
  を `:conformant` と読まず、方針採用・merge だけで全体の適合や本番稼働を claim しない。
- 外部仕様 mirror、protocol library、test fixture、認証後の notification/connectivity は
  human session / credential / recovery を発行しない限りこの authority 境界の対象外。

nested `SECURITY.md` はこの方針を強化・具体化できる。Passkey 専用 product も許すが、
wallet 認証を workspace 全体で禁止する根拠にはしない。federation product は別 hostname・
trust boundary・session namespace・threat model・ADR を持ち、既存 authority の fallback にしない。

