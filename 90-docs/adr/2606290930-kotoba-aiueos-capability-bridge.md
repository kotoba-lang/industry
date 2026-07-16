---
id: adr-2606290930-kotoba-aiueos-capability-bridge
title: "ADR-2606290930: kotoba CID ↔ aiueos hash の resource 対応と Kotoba Grant 正規化ブリッジ"
status: proposed
doc_type: adr
topic: kotoba-aiueos-security
authoritative: true
last_verified: 2026-06-29
authoritative_for:
  - kotoba(CID-addressed)と aiueos(sha256-hex pinned)の artifact 識別を架橋する
  - CACAO resource(kotoba://...) と aiueos typed capability の対応表を定義する
  - 外部 CACAO/UCAN envelope を Kotoba Grant に正規化し aiueos local grant へ落とす
  - 正規化は積集合(external ∩ local-policy ∩ manifest ∩ surface ∩ limits)で行う
related:
  - 90-docs/adr/2606290900-kotoba-aiueos-key-lifecycle-revocation.md
  - orgs/kotoba-lang/kotoba/crates/kotoba-auth
  - orgs/kotoba-lang/kotoba/crates/kotoba-clj/src/policy.rs
  - orgs/kotoba-lang/aiueos/src/policy.rs
  - orgs/kotoba-lang/aiueos/src/broker.rs
  - orgs/kotoba-lang/aiueos/src/surface.rs
supersedes: []
superseded_by: []
---

# ADR-2606290930: CID ↔ hash resource 対応と Kotoba Grant 正規化ブリッジ

**Status**: proposed
**Date**: 2026-06-29
**Deciders**: Jun Kawasaki

## Context

監査(2026-06-29)で 2 つの不整合が確認された:

1. **artifact 識別が二系統**: kotoba は `KotobaCid`(sha2-256, dag-cbor)で
   content-addressed。aiueos は manifest `:aiueos/wasm-sha256`(lowercase hex)で
   **hash pin** だが CID ではない。両者を結ぶ対応が無い。
2. **resource↔capability の変換表が無い**: kotoba CACAO の resource は
   `kotoba://can/{cap}` / `kotoba://graph/{cid}` のような URI。aiueos の capability は
   `graph/read` / `topic/publish` / `net/fetch` 等の typed string。`resources:["https://x"]`
   を `net/fetch/*` に広げる、のような曖昧変換は穴になる。
3. **正規化ブリッジが暗黙**: 「外部 CACAO を直接 Wasm capability にしない。Kotoba Grant に
   正規化して policy で縮約する」という提案の中核が、2 層に別実装として散在し、
   明示的な単一経路が無い。

## Decision

**外部認可(CACAO/UCAN/VC)→ Kotoba Grant(typed, 正規化)→ aiueos local grant →
Wasm import table、という単一の変換経路を定義する。各段は積集合でのみ縮小する。**

1. **artifact 同一性の橋**: aiueos manifest に `:aiueos/wasm-cid`(任意)を追加し、
   `wasm-cid` ⇔ `wasm-sha256` の双方向確認を broker のロード時検証に組み込む
   (`KotobaCid::verifies(bytes)` と `sha256_hex(bytes)` の両立)。CID を正本、
   hex を後方互換として扱う。

2. **resource → capability 対応表(正規化テーブル)**: 変換は table-driven かつ
   **deny-by-default**。未知 resource は capability に化けない(loud deny)。

   | CACAO resource | aiueos capability | 制約の正規化 |
   |---|---|---|
   | `kotoba://can/graph-read` + `kotoba://graph/{cid}` | `graph/read` | resource=cid, max-rows |
   | `kotoba://can/graph-write` + `kotoba://graph/{cid}` | `graph/write` | resource=cid |
   | `kotoba://can/infer` + `kotoba://model/{cid}` | `llm/infer` | resource=cid, max-tokens |
   | `kotoba://can/net-fetch` + `https://host/path/*` | `net/fetch` | origin+prefix+methods allowlist |
   | `kotoba://can/topic-pub` + `topic/{system}/{id}` | `topic/publish` | system+topic 限定 |

   URL を `*` に丸める変換は禁止。origin+path-prefix+method の三点で必ず絞る。

3. **Kotoba Grant(正規化中間表現)**: external envelope とは別の typed grant。
   subject/audience/component/manifest-cid/wasm-cid/capabilities(typed)/limits/nbf/exp/parent/proof。
   ADR-2606290900 の失効・有効性判定はこの grant に対して行う。

4. **aiueos への materialize は積集合**:
   `effective = kotoba_grant ∩ aiueos_policy.grants ∩ manifest.imports ∩ surface.offered ∩ limits`。
   aiueos 既存の `verify_component`(import 解決 + effect/trust + DMA/IOMMU)はこの段の
   後半として再利用する。**足し算は禁止**(権限のキノコ増殖防止)。

## Pipeline (normative)

```
DID                         // who              (kotoba-auth)
  -> CACAO / UCAN-like       // who signed what  (envelope)
  -> Kotoba Grant            // what it means    (typed, CID-bound, attenuable)
  -> aiueos local grant      // allowed here     (intersection)
  -> Wasm import table       // what can happen  (aiueos:host gate)
  -> Audit receipt           // what happened    (CommitDag / EDN log)
```

委譲は縮小のみ: child.cap ⊆ parent.cap, child.resource ⊆ parent.resource,
child.exp ≤ parent.exp, child.aud = parent.aud。

## Maturity

| Stage | Deliverable | Status |
|---|---|---|
| B0 | kotoba CACAO/typed CapClass, aiueos verify_component(intersection) | implemented (両層に別実装) |
| B1 | `:aiueos/wasm-cid` 追加 + CID⇔hex 双方向検証 | next |
| B2 | resource→capability 正規化テーブル(deny-by-default) | next |
| B3 | Kotoba Grant 型 + 単一正規化経路(kotoba-auth → aiueos broker) | after B1/B2 |
| B4 | run receipt を CID-addressed DAG 化(source/compiler/policy/grant/input/output) | future |

## Consequences

- aiueos manifest schema に `:aiueos/wasm-cid` を増やすが任意キー。既存 `wasm-sha256` のみの
  manifest も受理(後方互換)。
- 正規化テーブルは単一の権威データ(EDN)とし、kotoba/aiueos 双方が同じ表を参照する。
  表自体の改変は ADR-2606290900 の signer 権威で守る。
- B4 の run receipt DAG は提案の「この出力はどの wasm/policy/grant/input から出たか」を
  検証可能にする。kotoba CommitDag(`author_sig`)と aiueos EDN 監査ログを繋ぐ。
