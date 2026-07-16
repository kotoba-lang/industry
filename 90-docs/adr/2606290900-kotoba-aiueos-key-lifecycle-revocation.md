---
id: adr-2606290900-kotoba-aiueos-key-lifecycle-revocation
title: "ADR-2606290900: kotoba/aiueos の鍵ライフサイクルと失効レイヤを CID 不変性から分離する"
status: proposed
doc_type: adr
topic: kotoba-aiueos-security
authoritative: true
last_verified: 2026-06-29
authoritative_for:
  - aiueos signer registry を flat list から versioned/expiring trust store へ成熟させる
  - 失効(revocation)を CID 不変性とは別レイヤの registry として定義する
  - kotoba(custody epoch / CACAO exp)と aiueos(ed25519 signer)の鍵ライフサイクルを統一原則で揃える
  - decision engine が「現時点で有効か」を判定する手順(signature ∧ not-expired ∧ not-revoked)
related:
  - 90-docs/adr/2606290930-kotoba-aiueos-capability-bridge.md
  - orgs/kotoba-lang/aiueos/SECURITY.md
  - orgs/kotoba-lang/aiueos/src/policy.rs
  - orgs/kotoba-lang/aiueos/src/signing.rs
  - orgs/kotoba-lang/kotoba/crates/kotoba-auth
  - orgs/kotoba-lang/kotoba/crates/kotoba-custody
supersedes: []
superseded_by: []
---

# ADR-2606290900: 鍵ライフサイクルと失効レイヤを CID 不変性から分離する

**Status**: proposed
**Date**: 2026-06-29
**Deciders**: Jun Kawasaki

## Context

監査(2026-06-29)で、提案アーキテクチャの最重要不足点が確認された:

- **aiueos**: signer registry は `policy.signers: BTreeMap<String,String>`(id→hex pubkey)の
  **flat list**。expiry / revocation / rotation / chain が無く、SECURITY.md も
  「A compromised signer key can only be handled by editing the policy」と明記する。
- **kotoba**: custody の epoch rotation(`re_deal_shares_to_new_set`)と CACAO `exp` は
  あるが、配布済みの grant / signer / artifact を「いま信じない」と宣言する
  **live revocation registry** が無い。
- **content address の本質的制約**: CID は immutable なので、悪い hash を永遠に正しく
  参照できる。失効は CID だけでは表現できず、別レイヤが要る(「hash は記憶力が良すぎる」)。

## Decision

**信頼判定を `signature_valid ∧ not_expired ∧ not_revoked` の 3 条件に統一し、
`not_revoked` を CID 不変性とは独立した失効レイヤとして導入する。**

1. **signer registry を trust store へ昇格**(aiueos): flat list を、各エントリが
   `valid_from` / `valid_until` / `status(active|rotated|revoked)` / `rotated_to` を
   持つ versioned store に置き換える。policy 編集なしに失効を反映できる。

2. **revocation registry を新設**(kotoba 正本 / aiueos 参照): 失効対象を CID/鍵で指す
   append-only な warrant 列。target は grant-CID / signer-key-id / artifact-CID / manifest-CID
   のいずれか。entry は `{target, reason, issuer-did, issued-at, epoch}`。既存の
   kotoba PRE revocation warrant(GossipSub)と custody epoch を上位概念として束ねる。

3. **失効の意味論は「消去」ではなく「不採用」**: 配布済み ciphertext / artifact は
   消さない(消せない)。decision engine が参照時に registry を引き、revoked なら deny。
   kotoba-rad(ADR-2606280300)の「revocation = epoch rotation, not deletion」と同原則。

4. **expiry の既定**: CACAO は `exp` 必須化を推奨し、無い場合の fallback
   (`MAX_CACAO_AGE_SECS = 7d`)を維持。aiueos signer も `valid_until` 既定を設ける。

## Trust decision (normative)

```
trust(subject) :=
     signature_valid(subject)                 // ed25519 / EdDSA / CACAO chain
  && now ∈ [valid_from, valid_until)           // expiry
  && not revoked(subject.id | subject.cid)     // revocation registry
  && issuer_trusted_for(subject.resource)      // authority scoping
```

`issuer_trusted_for` は「誰でも graphA/write を署名できる」状態を禁じる(電子落書き防止)。

## Maturity

| Stage | Deliverable | Status |
|---|---|---|
| K0 | CACAO exp + custody epoch rotation | implemented (kotoba) |
| K1 | aiueos signer trust store (valid_from/until, status) | next |
| K2 | revocation registry (warrant 列, target=CID/key) | next |
| K3 | decision engine が 3 条件で判定(aiueos broker + kotoba auth) | after K1/K2 |
| K4 | rotation chain / delegation chain depth>2 + PQ 署名移行 | future |

## Consequences

- aiueos broker の `authenticate()` / `verify_one` が registry 参照を追加する分、
  ロード時 I/O が増える。registry は CID 固定でキャッシュ可能。
- 失効は「忘れる仕組み」なので append-only registry 自体の権威(誰が warrant を出せるか)を
  `issuer_trusted_for` で縛る必要がある。これは ADR-2606290930 の正規化ブリッジと連動。
- 既存 manifest 署名(`"{id}\n{wasm_sha256}"` over ed25519)は不変。trust store は
  その検証結果に対する有効性レイヤとして上に乗る。
