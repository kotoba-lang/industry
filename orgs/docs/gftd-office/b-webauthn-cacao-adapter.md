# (b) WebAuthn / passkey → CACAO 認証アダプタ

## 目的

パスワード・メール無しの **passkey（WebAuthn）で人間がログイン**でき、
**匿名 → passkey 昇格**ができ、かつ **ログインは任意**（公開グラフは未ログインで動く）を満たす。
さらに passkey を「**端末ローカルの E2E 暗号鍵（`signal:v1:` / storage DEK）の解錠ゲート**」として使い、
データ主権（Proton 的）を担保する。

## 重要な発見：kotoba には passkey ゲートが既にある

`crates/kotoba-auth/src/passkey.rs` に実装済：

```rust
pub struct PasskeyAssertion { credential_id, user_present, user_verified, rp_id_hash, issued_at_secs, nonce }
pub enum KeyOpKind {
    WebLogin,            // 60s, UserPresence
    SiweSign,            // 30s, UserVerified
    SignalKeyUnlock,     // 30s, UserVerified      ← E2E 鍵解錠
    StorageKeyDecrypt,   // 60s, UserPresence      ← ストレージ DEK 解錠
    AddDevice, RecoveryKeyAccess, EthereumTxLow/High, ...
}
pub struct PasskeyGate { policy, expected_rp_id_hash }
impl PasskeyGate { pub fn authorize(&self, op: KeyOpKind, a: &PasskeyAssertion) -> Result<Authorization, _> }
pub struct KeyHierarchy { eth_account, signal_identity_pub, storage_dek_cid, recovery_key_cid, passkey_credential_ids }
```

設計思想（passkey.rs コメント）：**「passkey はマスター秘密鍵ではなく、用途分離された各鍵への
アクセスをゲートするルート認証器」**。これは我々の主権モデルにそのまま使える。

> つまり「WebAuthn 未実装」は不正確。**ポリシーゲートは実装済**。
> 未実装なのは ① WebAuthn セレモニ検証（attestation/assertion の暗号検証）、
> ② enroll/auth の XRPC エンドポイント、③ credential_id → DID レジストリ、
> ④ ブラウザ側 CACAO 署名（WASM）。本書はこの 4 つを繋ぐアダプタ設計。

## 認可境界（passkey が満たすべきもの）

CACAO 検証の実体：

| 箇所 | 内容 |
|---|---|
| `kotoba-auth/src/cacao.rs:50` | `Cacao { h, p:CacaoPayload{iss,aud,issued_at,expiry,nonce,domain,resources}, s }` |
| `cacao.rs:146` `verify_signature()` | EdDSA(Ed25519) または eip191(secp256k1) 検証 → issuer DID 返す |
| `delegation.rs` `DelegationChain::verify(graph, cap)` | depth≤2、capability/graph スコープ減衰、署名検証 |
| `kotoba-server/src/graph_auth.rs:387` `check_read_access()` | 可視性 3 段（Public / Authenticated / Private） |
| `server.rs:1262` `KOTOBA_DEFAULT_VISIBILITY` | `public`/`authenticated`/`private`（既定 private） |
| `graph_auth.rs` NonceStore | CAIP-74 nonce 単回使用（DashMap, 7 日） |

可視性 3 段（ここが「ログイン任意」の仕組み）：

- **Public** … 認証不要。← **未ログインで使える「お試し」面**。`KOTOBA_DEFAULT_VISIBILITY=public` で公開デモ。
- **Authenticated** … `Authorization: Bearer <token>` があればよい（署名は edge BFF が信頼境界）。
- **Private** … CACAO delegation chain 必須、**issuer == owner DID**、nonce 単回。← 個人の主権データ。

passkey フローの出力は最終的に**「private グラフ用の有効な CACAO」**を生めればよい：

```rust
Cacao {
  h: CacaoHeader { t: "eip4361" },
  p: CacaoPayload {
    iss: <user_did>,                 // 例 did:key:z6Mk...（passkey に紐づくユーザー DID）
    aud: <node_operator_did>,
    issued_at: <now>, expiry: Some(<gate TTL>),
    nonce: <16B hex 単回>,
    domain: <rp_id = docs.gftd.ai>,
    resources: vec!["kotoba://op/datom:read","kotoba://op/datom:transact","kotoba://graph/<cid>"],
  },
  s: CacaoSig { t: "EdDSA", s: <Ed25519 sig over siwe_message()> },
}
```

## 推奨設計：Option C（ハイブリッド・クライアント署名）

サーバにユーザー秘密鍵を持たせない（単一障害点を避ける）。**ユーザー Ed25519 鍵はブラウザ側に保持**し、
passkey でその利用を解錠する。`kotoba-auth` は wasm32 にコンパイル可能なので CACAO 署名をブラウザで行える。

### 鍵の階層（主権の核）

```
passkey（WebAuthn, Secure Enclave / Keystore）   ← 端末から出ない・ルート認証器
   │ authorize(KeyOpKind::SignalKeyUnlock / StorageKeyDecrypt)
   ▼
ユーザー Ed25519 鍵（IndexedDB に at-rest 暗号化保存, 鍵ラップは passkey 派生 PRF）
   │ sign(siwe_message)                          │ unwrap
   ▼                                             ▼
CACAO（private グラフ書き込み認可）         signal:v1: / storage DEK（本文 E2E 暗号 = 文書 a の assertEncrypted）
```

ポイント：WebAuthn の **PRF 拡張（`hmac-secret`）**で passkey から決定的なシークレットを引き出し、
それで IndexedDB 上のユーザー Ed25519 鍵 & DEK をラップする。
→ サーバは暗号文しか持たず、復号は passkey + 端末がないと不可能（= Proton 的ゼロ知識）。

### フロー 1：匿名利用（ログインなし）

- 起動時 `generateIdentity()` で端末ローカル DID を生成（または無 ID）。
- public グラフ + ローカル IndexedDB のみ。CACAO 不要。サーバ同期なし。
- **これが既定**。`KOTOBA_DEFAULT_VISIBILITY=public` の公開デモはここ。

### フロー 2：匿名 → passkey 昇格（enroll）

```
1. navigator.credentials.create()  （PRF 拡張を要求）
2. POST /xrpc/...auth.passkey_enroll  { attestation, clientDataJSON }
3. サーバ: WebAuthn attestation を検証 → credential_id 登録
4. クライアント: passkey PRF で既存のローカル Ed25519 鍵 & DEK をラップして IndexedDB へ
   （匿名時のローカルデータをそのまま昇格 = データ移行不要）
5. サーバ: passkey_registry に credential_id → user_did を登録、KeyHierarchy 保存（鍵実体は持たない）
```

匿名で作った文書がそのまま自分のものになる（再作成不要）が UX の肝。

### フロー 3：passkey ログイン → CACAO 署名

```
1. navigator.credentials.get()  → PasskeyAssertion（PRF 出力含む）
2. クライアント: PasskeyGate 相当のポリシーを満たすか確認（UV/UP, max-age）
   （サーバでも PasskeyGate::authorize(KeyOpKind::WebLogin, assertion) で二重チェック）
3. クライアント: PRF でユーザー Ed25519 鍵を unwrap
4. private グラフ書き込み時のみ: クライアントで Cacao を組み立て siwe_message() に Ed25519 署名
   → base64(cbor) を /xrpc/...datomic.transact の cacao_b64 に付与
5. サーバ: 既存 check_read_access / DelegationChain::verify で検証（変更不要）
6. CACAO は sessionStorage にキャッシュ、expiry で再署名
```

サーバ側に必要な新規:
- `auth.passkey_enroll` / `auth.passkey_auth` XRPC（WebAuthn attestation/assertion 検証。`webauthn-rs` 等）。
- `passkey_registry`: `credential_id → user_did`（+ rp_id_hash バインド）。
- challenge nonce 発行/検証（リプレイ防止。既存 NonceStore を流用）。

クライアント側に必要な新規:
- WebAuthn 呼び出し + PRF 取り扱い（JS）。
- `kotoba-auth` の wasm ビルドで `Cacao` 構築 + `siwe_message()` + Ed25519 署名を公開。
- IndexedDB 鍵ラップ/アンラップ。

### ログインを任意に保つ（壊さない原則）

- 既定は `public`。未ログインで読める/お試しできる面は不変。
- private グラフを使うユーザーだけ passkey + CACAO 経路に入る。
- XRPC サーフェスは `cacao_b64` を**任意クエリ param**として既に持つ → 後方互換で追加可能。

## 段階計画

| Phase | 内容 | 完了条件 |
|---|---|---|
| 0 | `kotoba-auth` の wasm ビルド + `Cacao` 構築/署名を JS へ export | ブラウザで有効な CACAO を生成し既存サーバが verify 通す |
| 1 | `auth.passkey_enroll` / `passkey_auth` XRPC + registry | passkey 登録/認証が成立（attestation/assertion 検証） |
| 2 | PRF で Ed25519 鍵 & DEK をラップ/アンラップ（IndexedDB） | 鍵がサーバに出ず passkey でのみ解錠できる |
| 3 | 匿名→passkey 昇格（ローカルデータ引継ぎ） | 匿名で作った doc が昇格後も自分のものとして残る |
| 4 | private グラフ同期（CACAO 付き transact） | 複数端末で自分の暗号データのみ同期 |
| 5 | リカバリ（RecoveryKeyAccess / AddDevice / Shamir t-of-N） | 端末紛失からの復旧フロー |

## 既知のリスク / 正直なギャップ

- **WebAuthn セレモニ検証は未実装** … attestation/assertion の暗号検証（`webauthn-rs` 等）をサーバに足す。
- **PRF 拡張の対応差** … 一部ブラウザ/認証器は `prf`/`hmac-secret` 非対応。
  フォールバックは「passkey で短命 JWT → サーバ管理の鍵ラップ」だが主権が下がる（トレードオフを明示）。
- **rp_id バインド** … `expected_rp_id_hash` を本番ドメインに固定しないとフィッシング耐性が落ちる。
- **owner==issuer 制約** … private グラフは issuer==owner。チーム共有は depth-2 delegation で owner が委任する設計に。
- **サーバ署名版（Option A）との違い** … 簡単だがサーバ鍵が単一障害点。主権を謳うなら Option C を採る。
- **リカバリと主権の緊張** … 完全クライアント保持は端末紛失で復旧不能。Shamir t-of-N カストディ
  （kotoba の sealed cold tier に存在）で「主権を保ったまま復旧可能」に。設計の山場。
