# ADR-2607022500: microkernel capability revocation の事例調査、および EROS/KeyKOS indirection パターンで aiueos signer trust store を実装する（ADR-2606290900 K1/K3 の一部を実装完了）

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607022400 で unikernel（Solo5/Nanos/Unikraft）を調査し kototama の runtime 設計に
反映した。続けて microkernel（capability model・IPC・driver 分離）の事例調査を行った。
狙いは、aiueos の未解決ギャップである **ADR-2606290900**（signer registry が flat
list のままで、失効・ローテーション・有効期限を表現できない — SECURITY.md も
「A compromised signer key can only be handled by editing the policy」と明記する
既知の穴）に対し、具体的で CLJC で表現可能な revocation パターンを見つけることだった。

## Decision

### 1. microkernel 事例調査の結論

| 対象 | capability/revocation の仕組み | aiueos への適用性 |
|---|---|---|
| **seL4** | Capability Derivation Tree（CDT）+ `Revoke`（派生 capability を木構造で辿って破棄）。badged endpoint で細粒度の間接失効も可能。WasmEdge on seL4 という実例あり | 強力だが **kernel object graph をハードウェア capability slot で管理する live な仕組み**。CLJC の pure data で再現するには CDT エンジンをゼロから実装することになり過大 |
| **EROS/KeyKOS/CapROS** | **indirection/gatekeeper object** — 対象への直接 capability を渡す代わりに、転送する小さな不透明オブジェクト G への capability を渡す。失効 = G を無効化するだけで、対象自体は触らない | **採用**。純粋にデータで表現できるパターンで、追加の実行時機構を要さない |
| **Zircon (Fuchsia)** | Handle + rights bitmask。driver は DFv2 で component 化（aiueos の driver-as-component と近い） | rights は静的な減衰であって revocation 機構そのものではない。Wasm 統合の実例なし |
| **Genode** | 親子間の session routing（明示的な capability 配線、resource quota） | 階層的な capability 委譲の参考にはなるが、今回の revocation ギャップの直接解ではない（将来、grant の階層化をやるときの参考） |
| **L4 系（NOVA/Fiasco.OC）** | 高速同期 IPC を唯一のプリミティブにする設計哲学 | aiueos の非同期 pub/sub topic bus とは異なる設計選択だが、aiueos が hard real-time を狙っていない（ADR-0006）以上、優劣の問題ではない |

**EROS/KeyKOS の indirection/gatekeeper パターンを採用する。** seL4 の CDT+Revoke は
不採用— ハードウェア capability slot に紐づいた live なメカニズムであり、これを
「.kotoba/CLJC の pure data」で模倣しようとすると CDT エンジンごと再実装することに
なり、ADR-2607022200 の「意味論は CLJC の pure data であるべき」という方針と噛み合わない。
KeyKOS 型は最初から pure data パターンであり、「対象は触らず、間接層だけを無効化する」
という考え方は、aiueos の signer registry にそのまま適用できる: **signer の
public key エントリ自体は変更せず、エントリに status を持たせて「不採用（unadopt）」
を表現する** — ADR-2606290900 の「失効の意味論は消去ではなく不採用」という決定と
完全に一致する。

### 2. `aiueos.policy`/`aiueos.signing`/`aiueos.broker` に signer trust store を実装

ADR-2606290900 の trust 式 `signature_valid ∧ not_expired ∧ not_revoked ∧
issuer_trusted_for` のうち、**`not_expired` と `not_revoked` を実装した**
（`signature_valid` は既存の `aiueos.signing/verify`、`issuer_trusted_for` は
未実装 — Consequences 参照）。

- `aiueos.policy/signer-entry` — `:aiueos.policy/signers` registry の値を正規化。
  素の hex 文字列（既存の flat registry 形式）は `{:aiueos.signer/public-key hex
  :aiueos.signer/status :active}` として扱う（**完全後方互換** — 既存の全テスト・
  全 policy は無変更で動く）。構造化された map（`:aiueos.signer/public-key`
  `:aiueos.signer/status` `:aiueos.signer/valid-from` `:aiueos.signer/valid-until`
  `:aiueos.signer/rotated-to`）も受理する。
- `aiueos.policy/signer-status-ok?` — `:active` のみ true。`:revoked`/`:rotated`
  は false（rotation-chain の追跡＝ADR-2606290900 の K4 は未実装、明記のうえ据え置き）。
- `aiueos.policy/signer-in-window?` — `valid-from`/`valid-until` の範囲判定。
  `now` が nil の場合は判定をスキップ（**revocation status のチェックは無条件で
  効く** — clock が無い呼び出し元でも失効そのものは防げる）。
- `aiueos.policy/signer-trusted?` — 上記2つを合成した trust 判定。
- `aiueos.signing/verify` は registry 値が文字列でも構造化 map でも public key を
  抽出できるよう最小限の変更（`signer-public-key-hex` ヘルパー追加）のみ。
  暗号検証ロジック自体は無変更。
- `aiueos.broker/authenticate`/`verify-one`/`verify-system`/`verify-admission`
  は任意の `now`（epoch 秒）を受ける多重アリティに拡張（省略時 = revocation の
  みチェック、expiry はスキップ）。**暗号的に正しい署名でも、signer が
  revoked/rotated/期限切れなら deny**（`:bad-signature` violation、既存の
  「bad signature は unsigned に降格しない」という原則を revocation にも
  同様に適用: revoked signer も unsigned に降格しない）。

全て既存 147 テスト（の前身、117テスト）を無変更のまま green に保ったうえで、
revocation/expiry 専用の新規テストを追加（`aiueos.policy-test` に9件、
`aiueos.broker-test` に4件、実際の ed25519 鍵ペアで「暗号的には有効な署名だが
signer が revoked/rotated/期限切れ」を再現）。

## Consequences

- (+) ADR-2606290900 の **K1（signer trust store）と K3（trust 判定式のうち
  not_expired/not_revoked 節）が実装完了**。SECURITY.md の既知の穴
  （"A compromised signer key can only be handled by editing the policy"）は
  もう正確ではない — policy 編集なしに signer 単位の失効が反映できるようになった。
- (+) 完全後方互換。既存の flat signer registry を使うあらゆる policy/manifest/
  test は無変更で動作する。
- (+) revocation status のチェックは clock 無しでも効く（EROS/KeyKOS パターンの
  「間接層を無効化するだけ」という単純さのおかげ）。
- (−) **K2（kotoba 正本の cross-system revocation registry、warrant 列、
  GossipSub 配信）は未実装**。今回実装したのは aiueos ローカルの signer trust
  store のみで、ADR-2606290900 が想定する「kotoba の custody epoch と束ねた
  グローバルな失効レイヤ」はまだ無い。
- (−) **`issuer_trusted_for`（誰でも任意の resource に署名できる状態を禁じる
  authority scoping）は未実装**。trust 式の4条件のうち3つを実装した状態。
- (−) **K4（rotation-chain の追跡、depth>2 の委譲、PQ署名移行）は未実装**、
  ADR-2606290900 のマチュリティ表どおり将来課題のまま。
- (−) `resources/aiueos/policy_contract.edn` は `:aiueos.policy/signer-statuses`
  として `#{:retired :revoked :compromised :suspended :active :expired}`
  という6状態を既に宣言していたが、本 ADR の実装は ADR-2606290900 の Decision
  本文が明示する3状態（`:active :rotated :revoked`）のみをサポートする。
  `validate-policy-contract` はこの EDN フィールドを「keyword set であること」
  としか検証しないため実害は無いが、**語彙の不一致を認識のうえ残す**
  （expired は valid-until の時刻判定から導出しており別 status にしていない。
  compromised/suspended/retired は未対応 — follow-up）。
- (−) 本 ADR は seL4 型（CDT+Revoke）を明示的に不採用とした。将来
  `high-assurance` プロファイルで形式検証済みの capability revocation が
  本当に必要になった場合は、この判断を再検討すること。

## References

- ADR-2606290900（本 ADR が K1/K3 の一部を実装完了させる対象）
- ADR-2607022400（unikernel 調査、Solo5 tender パターン。本 ADR はその続きの
  microkernel 調査）
- ADR-2607022200（三層アーキテクチャ。「意味論は CLJC pure data」という方針が
  seL4 CDT 不採用の判断根拠）
- `com-junkawasaki/orgs/kotoba-lang/aiueos-cljc-contract/src/aiueos/policy.cljc`
  （`signer-entry`/`signer-status-ok?`/`signer-in-window?`/`signer-trusted?`）
- `com-junkawasaki/orgs/kotoba-lang/aiueos-cljc-contract/src/aiueos/signing.cljc`
  （`signer-public-key-hex`）
- `com-junkawasaki/orgs/kotoba-lang/aiueos-cljc-contract/src/aiueos/broker.cljc`
  （`authenticate`/`verify-one`/`verify-system`/`verify-admission` の `now` 多重アリティ）
- seL4: https://sel4.systems ,
  https://sel4.systems/Info/Docs/seL4-manual-latest.pdf
- WasmEdge on seL4: https://github.com/second-state/wasmedge-seL4
- EROS/KeyKOS indirection: http://www.cap-lore.com/CapTheory/Segregate.html ,
  http://www.cap-lore.com/Agorics/Library/KeyKos/securityInKeyKOS.html
- Zircon: https://fuchsia.dev/fuchsia-src/concepts/kernel
- Genode: https://genode.org/documentation/genode-foundations/22.05/architecture/Recursive_system_structure.html
- L4 family: https://trustworthy.systems/publications/nicta_full_text/8988.pdf
