# ADR-2607141400: 共通化 — Merkle-sum を kotoba-lang 共有 lib に抽出 + west 登録、Store seam 重複所見

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki
**Scope**: 新規 `orgs/kotoba-lang/merkle-sum`、`manifest/west.yml` + `manifest/repos.edn`（登録）、
`orgs/cloud-itonami/cloud-itonami-isic-6611-cryptoexchange`（依存化）

## Context

ADR-2607141200（cryptoexchange）の実装過程で、共通化可能な部品を自前再実装して
いた／fleet に既存の共有 lib があることが監査（2026-07-14）で判明した。オーナー指示:
「共通化して効率化、複製についても適切に repo, west に登録」。

実測所見:
- **暗号プリミティブは既に kotoba-lang にある**: `kotoba-lang/crypto`
  (`kotoba.lang.crypto`、sha2/hmac/hkdf、host-injected digest 方式)、
  `kotoba-lang/btc-crypto`（`btc_crypto/tx` の bip143-sighash 等）、
  `kotoba-lang/eth-crypto`（eip712-digest / rlp / secp256k1）。cryptoexchange は
  SHA-256 を attest 内で直書きしていた。
- **Merkle-sum ツリーは fleet に前例ゼロ**（`mst`/`prolly-tree` は別種）。
  cryptoexchange.attest の実装が唯一。
- **actor Store seam（MemStore ≡ DatomicStore over langchain.db）は 263 repo が
  各自ハンドロール**（`DatomicStore` 参照 602 ファイル）。共有 lib に依存する
  cloud-itonami actor は 0 件。共有先 lib は存在しない。

## Decision

1. **Merkle-sum ツリーを共有 lib `kotoba-lang/merkle-sum` に抽出**（public,
   AGPL-3.0-or-later, zero-dep portable `.cljc`）。Maxwell 型 sum-tree + inclusion
   proof + verify（sum-shrinking 拒否・奇数ノード carry-up・cross-runtime pinned
   root）。**ハッシュ関数は注入**（`hash-hex : String -> hex`）で crypto 依存ゼロ
   （`kotoba.lang.crypto` と同じ host-injected 方式）。lib は node preimage
   `"node|lh|ls|rh|rs"` とアルゴリズムを所有し、leaf preimage は消費側（ドメイン）が
   持つ。CLJS(primary)+JVM(compat) 5 tests / 51 assertions green。

2. **cryptoexchange.attest を merkle-sum に依存化**。attest は PoR ドメイン部分
   （colon-free leaf preimage、liability-leaves、solvency-kernel 判定付き
   attestation）だけを保持。**挙動不変**（leaf/node preimage 同一のため pin 済み
   fixture root hash は変わらず、衛星 CLJS 71 / JVM 77 tests green）。

3. **west 登録**: `manifest/west.yml` に merkle-sum entry（pin `84e78e0` ==
   merkle-sum main tip、pure new entry）+ `manifest/repos.edn` `:extra-projects` に
   パス追加（再生成でも保持されるよう）。fresh worktree（origin/main）で 2 ファイル
   最小編集 → server-side merge（`8b452b7`）。pin 検証通過。

4. **SHA-256 は attest の注入 hasher（Node/JVM）を維持し、`kotoba.lang.crypto` へは
   寄せない**。理由: `kotoba.lang.crypto/hash` は CLJS で host digest 未注入だと
   throw する設計で、attest の primary gate（cljs.main --target node）では
   Node `crypto` 直呼びが実際に動く経路。merkle-sum の「hasher 注入」設計がクリーンな
   seam であり、crypto へ無理に寄せない。

5. **actor Store seam（263 複製）の一括移行はしない**（本 ADR のスコープ外）。理由:
   ①各 store は domain-shaped で機械的共通化が非自明、②build-actor skill が
   「各 actor が複製する」と明文化した既存規約、③263 repo の同時改変は
   fleet 全体の regression リスク。**別 ADR + 段階的 rollout**で扱うべき構造課題として
   記録する（推奨: 汎用の event-stream store seam を `kotoba-lang` lib に切り出し、
   新規 actor を先行 adopter に、既存は漸進移行）。cryptoexchange.store は今回
   触らない。

## Consequences

- (+) PoR/PoL を要する将来の actor は監査済み merkle-sum を再利用でき、複製が増えない。
- (+) 衛星の attest は 104 行 → 約 40 行に縮小（tree/proof/verify を lib へ）。
- (+) merkle-sum は hasher 注入なので kotoba-WASM 経路にもそのまま乗る。
- (−) Store seam の根本的重複（263）は未解決。最大の共通化余地だが、別スコープ。
- (−) `kotoba.lang.crypto` への統一は CLJS host-digest 注入設計のため見送り
  （attest の hasher は依然ローカル）。

## Artifacts

- https://github.com/kotoba-lang/merkle-sum （initial `84e78e0`）
- superproject west.yml/repos.edn `8b452b7`（merkle-sum 登録）
- 衛星 `6186588`（attest 依存化 + .cpcache untrack + .gitignore）
- 本 ADR とペアの `.edn`

## References

- ADR-2607141200（cryptoexchange — 抽出元 / 本 ADR の親）
- ADR-2607121200（safety kernel 規律）
- skill `build-actor`（Store seam 複製規約の出典）
- 既存共有 lib: `kotoba-lang/{crypto,btc-crypto,eth-crypto,mst,prolly-tree}`、
  `com-junkawasaki/{langgraph-clj,langchain-clj}`
