---
id: adr-2606272330-kagi-clj-pqc-vault
title: "ADR-2606272330: kagi-clj — 対量子(PQC)シークレット vault（1Password 代替）を kotoba 上に主権設計"
status: proposed
doc_type: adr
topic: security-vault
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - kagi-clj リポの暗号方針（hybrid PQC：鍵交換/署名/対称/KDF の選定）
  - vault item の鍵階層（unlock → VMK → compartment → DEK）と共有(share)エンベロープ
  - 平文/鍵をサーバに渡さない zero-knowledge ストレージ分割（sealed block ⟂ Datomic index）
  - vault の actor 化（StateGraph + AccessGovernor + 不変台帳、単一不変条件）
  - crypto provider seam（JVM=BouncyCastle / CLJS・WASM=kotoba-crypto Rust）
related:
  - orgs/com-junkawasaki/kagi-clj
  - orgs/gftdcojp/ai-gftd-itonami/src/itonami/cacao.clj
  - orgs/com-junkawasaki/kotoba/crates/kotoba-crypto
  - orgs/com-junkawasaki/kotoba/crates/kotoba-auth
  - orgs/gftdcojp/gftd-talent-actor
  - manifest/repos.edn（:kotoba / :b2）
supersedes: []
superseded_by: []
---

# ADR-2606272330: kagi-clj — 対量子(PQC)シークレット vault を kotoba 上に主権設計

**Status**: proposed（設計のみ。雛形リポ `orgs/com-junkawasaki/kagi-clj` を同時生成、west 登録は GitHub repo 作成後）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

1Password / Bitwarden 等は (1) ベンダ SaaS にデータ主権が握られ、(2) 暗号が古典前提
（ECDH/Ed25519/RSA）で **harvest-now-decrypt-later（HNDL）** に晒される。今いま暗号文を
収集しておき、CRQC（cryptographically-relevant quantum computer）が登場した時点で復号する
攻撃は、long-lived secret（API key, recovery code, 個人情報）を守る vault にとって最大の脅威で、
NIST は FIPS 203/204/205（2024 標準化）への移行を促している。

本ワークスペースには既に主権の素地がある:

- **kotoba** = 主権ストレージ/台帳。`kotoba-crypto`（AES-256-GCM / HKDF / X25519 / HPKE /
  passkey-rooted `key_tree` / `SealedBlockStore`）と `kotoba-auth`（CACAO/CAIP-74）が実装済み。
  ただし **暗号は全て古典で、PQC は未実装**（`policy.rs` に ML-KEM-768 の placeholder のみ）。
- **CACAO 自己発行**: actor は自分の Ed25519 鍵を持ち、その鍵由来 IPNS 名(`k51…`)が自分の
  graph。owner hand-off も共有 token も不要（`itonami/cacao.clj` が JVM 手本）。
- **actor パターン**: 知能ノードを封じ込め、独立 Governor が 書込/開示/作動/認証 を検閲し、
  全 commit/hold を append-only 台帳に積む（robotaxi / gftd-talent / itonami の同型 3 例）。

vault は「**全ての開示(reveal)・共有(share)・鍵操作(rotate/revoke)を検閲対象にする**」点で
actor パターンと完全に一致する。よって本 vault を、kotoba を SSoT に、PQC を加法的に重ねた
**主権 + 対量子 + governed** な 1Password 代替として設計する。

## Decision

リポ **`kagi-clj`**（鍵）を `orgs/com-junkawasaki/` に新設する。`*-clj`（library 位置づけ）
として PQC 暗号コア `kagi.crypto` を看板にしつつ、その上に governed vault actor を載せる。

### レイヤ構成

```text
        ┌────────────────────────────────────────────────────────────┐
  app   │  CLI / passkey-unlock / autofill(後段)                       │
        ├────────────────────────────────────────────────────────────┤
 actor  │  kagi.operation  StateGraph（1 op = 1 run）                  │
        │   intake→authn→advise→govern→decide─┬─reveal               │
        │                                     ├─write                 │
        │                                     ├─share / rotate        │
        │  kagi.governor  AccessGovernor      └─hold      → audit台帳  │
        │  kagi.phase     0→3 段階導入                                  │
        ├────────────────────────────────────────────────────────────┤
 model  │  kagi.vault   item/compartment/grant/version/ledger schema   │
        │  kagi.crypto  ★ hybrid PQC エンベロープ（本 ADR の核）        │
        │  kagi.identity Ed25519 did:key + ML-DSA 公開鍵（itonami 継承）│
        ├────────────────────────────────────────────────────────────┤
 store  │  :db-api seam → MemStore ≡ KotobaStore(Datomic XRPC, CACAO)  │
        │  暗号文 blob → kotoba SealedBlockStore（B2/IPFS cold）        │
        └────────────────────────────────────────────────────────────┘
```

### 1. 暗号方針（hybrid PQC — 古典を捨てず両方破られない限り安全）

| 用途 | 採用 | 根拠 |
|------|------|------|
| 鍵交換 KEM（DEK wrap / share） | **X25519 + ML-KEM-768**（FIPS 203, Cat.3） | 両方の shared secret を HKDF で結合（IETF hybrid）。HNDL 対策の本丸 |
| 署名（commit / CACAO / 台帳鎖） | **Ed25519 + ML-DSA-65**（FIPS 204, Cat.3） | Ed25519 = kotoba authority/IPNS は不変、ML-DSA を**加法的に併記** |
| 対称（item 封緘） | **AES-256-GCM** | Grover 後も 128-bit 相当で十分。kotoba `SealedBlockStore`/`seal_with_aad_nonce` 再利用（XChaCha20-Poly1305 は代替） |
| unlock KDF | **Argon2id**（m=256MiB, t=3, p=4 をデバイスで calibrate）+ **passkey PRF**（WebAuthn hmac-secret） | memory-hard。passkey はハード束縛 & phishing 耐性の第二 root |
| 復旧 | **Shamir k-of-n**（各 share を guardian の hybrid KEM へ encapsulate）+ 任意で **SLH-DSA**(FIPS 205) 署名の recovery manifest | hash-based の保守的バックアップ |

**hybrid combiner（robustly-binding）**:
`K = HKDF-SHA256(ikm = ss_x25519 ‖ ss_mlkem, info = "kagi/kem/v1" ‖ H(pk_x25519‖ct_x25519‖pk_mlkem‖ct_mlkem))`。
署名は連結（concatenated）で、`Ed25519 ∧ ML-DSA` の**両方が verify した時のみ**有効。domain-separate する。

### 2. 鍵階層

```text
unlock（いずれか / 複数を OR で wrap 多重保管）
  passphrase ─Argon2id→ KEK ┐
  passkey PRF ───────────→ KEK ├─ AES-256-GCM keywrap → [VMK の wrapped copy]
  recovery (Shamir/SLH-DSA) ┘
                                    │
                          Vault Master Key (VMK, 32B random)
                                    │ HKDF(info=compartment-id)
                          compartment key
                                    │
              per-item DEK (32B random) ──AES-256-GCM(AAD=item-cid)──→ 暗号文 block
```

VMK は unlock 方式ごとに **wrapped copy を複数保管**（passphrase 紛失でも passkey/recovery で開く）。
item 平文はサーバに出ない（client-side E2E）。

### 3. 共有(share)

ある item を別メンバーへ共有するとき、その **item DEK を受信者の hybrid KEM 公開鍵
(X25519+ML-KEM-768) へ encapsulate** し、grant エンティティとして graph に積む。受信者は自分の
秘密鍵で decapsulate。共有エンベロープが PQC なので HNDL に耐える。失効(revoke)は grant の
tombstone + 対象 DEK の rotate（再封緘 + 旧 CID unpin）。

### 4. ストレージ分割（zero-knowledge / 中継は untrusted）

- **暗号文 blob** → kotoba `SealedBlockStore`（content-addressed CID、cold = B2/IPFS）。
  app 層で既に E2E 暗号済みなので storage 層 sealing は二重防御（任意）。holder は untrusted relay。
- **index / metadata / ACL / grant / key-wrap envelope / version 履歴** → kotoba-server の
  **Datomic graph**（`ai.gftd.apps.kotobase.datomic.*`、CACAO-gated transact）。graph head は
  **鍵由来 IPNS = 主権**。title 等の機微フィールドは任意で暗号化。
- **監査台帳** → 同 graph の append-only entity 群。各 fact は `prev-hash` で**ハッシュ鎖**化し
  hybrid 署名して改竄検知可能にする。

サーバが見るのは暗号文 + 公開鍵素材のみ。サーバ侵害でも平文・鍵は漏れない。

### 5. actor 化と単一不変条件

`kagi.operation` は langgraph-clj StateGraph（1 op = 1 run、無限ループ無し）。3 例と同型:

| node | 役割 |
|------|------|
| `:intake` | 入口（pass-through） |
| `:authn` | CACAO + hybrid 署名検証（`kagi.identity`） |
| `:advise` | 知能ノード（risk/anomaly advisor）→ **proposal のみ**。書込権限なし |
| `:govern` | **AccessGovernor**（独立）→ verdict |
| `:decide` | phase gate を当てて commit / escalate / hold に分岐 |
| `:request-approval` | `interrupt-before`（break-glass / 高価値 reveal / 復旧承認） |
| `:reveal` `:write` `:share` `:rotate` | **副作用ノード（ここだけが store を変更/復号開示）** |
| `:hold` | 拒否 fact を台帳に積むのみ（SSoT 不変） |

> **単一不変条件**: 「**AccessGovernor が拒否する 開示(reveal)/書込(write)/共有(share)/
> 鍵操作(rotate/revoke)/認証 を kagi は決して行わない。**」
> グラフ位相で保証する — `:advise` から副作用ノードへ `:govern`/`:decide` を**迂回する辺が無い**。

AccessGovernor の検査軸（hard=即 hold / soft=escalate）:
RBAC（owner/member/viewer × item scope）、purpose（用途宣言と最小開示）、JIT-TTL（時限 reveal）、
consent、break-glass（緊急 + 必須監査）、rate/anomaly、device posture。

### 6. 注入境界（swap）

3 actor 同形で:

- **Store**: `:db-api` map `{:q :transact! :db :pull :entid}` 越し。`MemStore`（test/`.cljc`）
  ≡ `KotobaStore`（`langchain.kotoba-db/kotoba-api`、CACAO 自己発行）を **contract test で等価保証**。
- **Crypto provider**: `kagi.crypto/Provider` プロトコル。**JVM = `jvm-provider`**
  ＝ **JDK 24 標準**の ML-KEM-768(JEP 496/FIPS 203) と ML-DSA-65(JEP 497/FIPS 204)、
  Ed25519/X25519/AES-256-GCM/HMAC は JDK、**Argon2id だけ BouncyCastle** の低レベル
  `Argon2BytesGenerator`（JDK に無いため。`bcprov-jdk18on` を deps に保持）。
  **CLJS/WASM = kotoba-crypto Rust**（`ml-kem`/`ml-dsa` RustCrypto を `kotoba-crypto` に増設）。
  `:db-api` と同じく実装を差し替えてもコア不変。
  *(設計時は BC を一次想定したが、probe で BC 1.78.1 は pre-standard `DILITHIUM` のみ・
  ML-KEM 未提供と判明。JDK 24 が標準名で両者を final 提供するため JDK-native に確定。)*
- **Phase**: 0 read-only(shadow audit) → 1 self-vault → 2 team share → 3 supervised auto-rotation。

### 7. identity（既存を継承、PQC は加法）

`kagi.identity` は `itonami/cacao.clj` を継承し Ed25519 did:key / 鍵由来 IPNS /
`load-or-create-identity!` をそのまま使う（**kotoba authority は不変**）。これに **ML-DSA-65 公開鍵を
graph に publish** し、vault commit と CACAO に hybrid 署名を併記する。秘密鍵（Ed25519 + ML-DSA +
KEM）は `.kagi/identity.edn` に置き **gitignore（git に絶対コミットしない）**。

## Threat model

| 脅威 | 対策 |
|------|------|
| HNDL（量子で将来復号） | at-rest DEK wrap と share を hybrid KEM(X25519+ML-KEM-768) で PQC 化 |
| 量子で署名偽造 | commit/CACAO/台帳鎖を Ed25519+ML-DSA-65 hybrid 署名 |
| 中継/holder 侵害 | client-side E2E、CID 検証、head は署名鍵の権威のみ |
| サーバ(kotobase) 侵害 | zero-knowledge：平文/鍵を渡さない。grant/envelope も暗号文 |
| 鍵紛失 | unlock 多重 wrap + Shamir k-of-n 復旧（guardian へ KEM encapsulate） |
| 内部不正/越権 | AccessGovernor（独立）+ JIT-TTL + ハッシュ鎖監査台帳 + break-glass 強制監査 |

## Phases（段階導入）

- **0 read-only**: 既存 secret を取り込み、reveal は shadow audit のみ（書込なし）。
- **1 self-vault**: 単一 owner の CRUD + rotate。auto-commit は policy-clean のみ。
- **2 team share**: hybrid KEM 共有 / 失効 / guardian 復旧。
- **3 supervised auto**: 定期 auto-rotation・期限切れ自動 revoke（高価値は escalate のまま）。

## Consequences

- **+** 主権（自分の鍵由来 graph）+ 対量子（hybrid）+ governed（全開示が検閲・監査）を 1 リポで両立。
- **+** kotoba 資産（SealedBlockStore/HKDF/CACAO/Datomic XRPC）と 3 actor 同形を最大流用。`:db-api`/
  provider seam で in-mem ↔ kotoba ↔ JVM/WASM を無改造 swap、contract test で等価保証。
- **−** PQC 鍵/署名はサイズ大（ML-KEM-768 pk≈1.2KB, ML-DSA-65 sig≈3.3KB）→ graph/帯域コスト増。
  index に直書きせず blob/参照化で緩和。
- **−** Rust 側 PQC（`kotoba-crypto` 増設）は RustCrypto `ml-kem`/`ml-dsa` の成熟待ち要素あり。
  当面は JVM(BouncyCastle) を一次実装、WASM は後追い。
- **−** Argon2id calibration / passkey PRF はデバイス依存。fallback と移行を設計で吸収。

## Non-goals

- kotoba の identity/authority を置換しない（Ed25519 did:key/IPNS は不変、PQC は加法的併記）。
- 独自暗号は作らない（NIST FIPS 203/204/205 + 既存 kotoba-crypto のみ）。
- browser autofill / TOTP / SSH-agent 連携は後段フェーズ。

## Gates / Verification

- `clojure -M:lint`（clj-kondo, errors fail） / `clojure -M:dev:test`。
- **contract test**: `MemStore ≡ KotobaStore`、Governor 不変条件（拒否 op は副作用ゼロ・台帳に 1 fact）、
  hybrid KEM の wrap→unwrap 往復、hybrid 署名の片方破損で reject、台帳ハッシュ鎖の連結検証。
- `.cljc` は `#?(:clj/:cljs)` で JVM/WASM 可搬。crypto は provider プロトコル越しのみ呼ぶ。

## west 登録

新 repo は GitHub API の単一 entry クリーン commit で `manifest/west.yml` に登録し、pin 前進も
API（手書き禁止＝再生成と byte 一致、**pin == repo HEAD を検証**）。`scripts/gen-west-manifest.bb`
は west.yml の既存 path 群から projects を採るため、登録には **GitHub repo `com-junkawasaki/kagi-clj`
の作成**が前提（未作成の間は west pin 保留）。本 ADR と同時にローカル雛形 + `git init` まで用意する。
