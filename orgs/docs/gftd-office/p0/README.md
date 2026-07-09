# P0 — 組織前提オフィスの datom スキーマ + 変換ラッパ（参照実装）

doc a Phase 0 を「**組織前提 + アクセスDAG + W3C DID**」に拡張した参照実装。
datom ⇄ ドキュメント/組織/権限 の双方向変換を round-trip テストで実証する。

## 構成

| ファイル | 内容 |
|---|---|
| `office.cljc` | スキーマ登録(`schema`) + 変換関数（doc / org(+DID) / grant ⇔ datoms） |
| `office_test.clj` | round-trip テスト + 「所有=木 / アクセス=DAG」不変条件チェック |
| `bb.edn` | `bb test` タスク |

## 実行

```bash
cd docs/gftd-office/p0
bb test
# Ran 5 tests containing 10 assertions. 0 failures, 0 errors.
```

## 設計の要点（コードに反映済）

- **account = org ノード**（`:org/kind :org.kind/account`、"一人org"）。root/team/account の木。
- **所有=木**：`:org/parent` は単一（= W3C DID `controller`、Private graph の owner を一意化）。
- **アクセス=DAG**：`:grant/*`（reified capability ≒ CACAO）。同一 subject DID が複数 org からぶら下がれる
  （多重所属・ゲスト）。`access-is-a-dag` テストで検証。
- **W3C DID**：`:vm/*` が verification method。`:vm/rel` に `authentication` / `capabilityDelegation` /
  `capabilityInvocation` / `keyAgreement` を持つ（passkey・委任・E2E 鍵に対応）。
- **ブロック=エンティティ**：`:block/parent` + `:block/order`（fractional-index 文字列）で木構造。
  編集は retract+assert、本文 `:block/text` は実運用で `assertEncrypted`（signal:v1:）。

## 検証された不変条件

| テスト | 内容 |
|---|---|
| `doc-round-trip` | ネスト文書 ⇄ datoms が一致 |
| `org-round-trip` | org(+DID VM) ⇄ datoms が一致 |
| `grant-round-trip` | 権限付与 ⇄ datoms が一致（expires 任意） |
| `access-is-a-dag` | Alice が 2 org から付与を保持（多重所属） |
| `ownership-is-a-tree` | 非 root org の `:org/parent` はちょうど 1 |

## これは何でないか / 次

- 参照実装。エンティティ ID は**論理 ID**（実運用では kotoba `commit()` の content-addressed CID）。
- 暗号化・CACAO 署名・同期は含まない（文書 a/b 範囲）。
- 次：① この `schema`/変換を kotoba CLJS 層へ移植し `transact()`→`commitToIdb()` に接続（doc a Phase 1-2）、
  ② `:grant/*` を CACAO delegation に橋渡し（doc b Phase 0-1）。
