---
id: adr-2606280300-kotoba-rad-git-sovereign-repo
title: "ADR-2606280300: kotoba-rad / kotoba-git を主権 repository layer として成熟させる"
status: proposed
doc_type: adr
topic: kotoba-rad
authoritative: true
last_verified: 2026-06-28
authoritative_for:
  - kotoba-git の成熟度を R0 byte-exact Git object bridge として固定する
  - kotoba-rad を Radicle protocol 互換ではなく CID+Datom+DID+Source Chain の主権 repository layer として定義する
  - private repository の秘匿境界を selective replication ではなく object encryption に置く
  - R1/R2/R3/R4 の実装成熟度ロードマップ
related:
  - orgs/com-junkawasaki/kotoba/docs/ADR-kotoba-rad-git-sovereign-repo.md
  - 90-docs/adr/2606271600-kotoba-stack-equivalences.md
  - orgs/com-junkawasaki/kotoba/crates/kotoba-git
  - orgs/com-junkawasaki/kotoba/crates/kotoba-dht
  - orgs/com-junkawasaki/kotoba/crates/kotoba-crypto
supersedes: []
superseded_by: []
---

# ADR-2606280300: kotoba-rad / kotoba-git を主権 repository layer として成熟させる

**Status**: proposed
**Date**: 2026-06-28
**Deciders**: Jun Kawasaki

## Decision

`kotoba-git` は R0 の Git object fidelity layer として固定する。Git framed bytes を CID block に
保存し、SHA-1 Git oid と CID の橋を Datom 化するのが責務で、repo authority は持たない。

`kotoba-rad` は R1 以降の主権 repository layer として定義する。Radicle protocol 実装ではなく、
repo identity、delegate、ref authorization、private grants、Source Chain、Warrant を
kotoba の CID+Datom+DHT 上で表現する。

## Maturity

| Stage | Name | Deliverable | Status |
|---|---|---|---|
| R0 | Git object bridge | byte-exact objects, refs, pack import, snapshot manifest | implemented in `kotoba-git` |
| R1 | Signed repo identity | RID, identity journal, delegate/ref validation | next |
| R2 | Private object store | encrypted Git object blocks, recipient grants, epoch rotation | next after R1 |
| R3 | P2P accountability | source-chain publication, warrants, replication/reputation policy | partial primitives exist |
| R4 | PQ-ready suite | hybrid KEM/signatures, algorithm agility, migration tooling | future |

## Security

Private repo secrecy is not based on peer allow-lists. Allow-lists are availability and routing
policy. Confidentiality is object encryption:

- replication key = `ciphertext-cid`
- verification metadata = `plaintext-cid` + Git oid, authority scoped
- access = capability datom + recipient set + epoch key
- revocation = epoch rotation, not deletion of already distributed ciphertext
- PQ target = hybrid KEM/signature metadata (`X25519+ML-KEM`, `Ed25519+ML-DSA/SLH-DSA`)

## Implementation Target

Primary design lives in:

`orgs/com-junkawasaki/kotoba/docs/ADR-kotoba-rad-git-sovereign-repo.md`

The first implementation should add either a new `kotoba-rad` crate or a `kotoba-git::rad`
module with:

- `RepoIdentity`
- `RepoRid`
- `RepoEvent`
- `Delegate`
- `RefPolicy`
- `RecipientGrant`
- `RadRepo::apply_event`
- `RadRepo::authorize_ref_update`

The implementation must reuse `kotoba-git` for all object materialization and `kotoba-dht`
for source-chain/warrant semantics.
