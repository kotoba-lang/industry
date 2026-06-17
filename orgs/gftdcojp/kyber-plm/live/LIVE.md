# kyber-plm ライブ疎通（kotobase 分散 Datomic / CACAO）

`kotobase_probe.mjs` は自己署名 did:key Ed25519 **CACAO** を発行し、本番
`https://kotobase.gftd.ai` の分散 Datomic XRPC（`ai.gftd.apps.kotobase.datomic.*`）へ
実認証リクエストを投げる。

```bash
cd live
node kotobase_probe.mjs            # createDatabase + read（テナント CACAO 経路）
node kotobase_probe.mjs --write    # transact も試行
```

32-byte seed（秘密鍵）は `./.secrets/seed.hex`（**gitignore**）。

## 検証済みの結果（2026-06-17, kotobase.gftd.ai）

| 層 | 操作 | 結果 |
|---|---|---|
| エッジ Worker | `datomic.createDatabase` | **200 OK** — `x-kotoba-did` から決定的 graph CID を払い出す shim（pod 未経由・暗号検証なし）|
| pod（CACAO検証） | `datomic.q` / `transact`（JSON CACAO）| 502 `cacao parse: cbor parse: invalid type: string, expected map` |
| pod（CACAO検証） | 同上（**DAG-CBOR CACAO** by `kotoba cacao-sign`）| `cbor parse` 解消 → 次段へ |
| pod（aud） | `datomic.q`（aud=issuer）| 401 `cacao audience mismatch: expected <operator_did>` |
| pod（aud） | `datomic.q`（`--aud <operator_did>`）| aud 解消 → 次段へ |
| pod（scope/ownership）| `datomic.q` / `transact` | `graph scope mismatch` / owner-binding — **テナント did では到達不能** |

## 結論（重要）

1. **CACAO の発行と DAG-CBOR 形式は成立**。pod が要求するのは PoC 用 `cacao.mjs`（JSON）ではなく、
   公式 `kotoba cacao-sign --graph <CID> --capability <cap> --aud <operator_did> <seed>` が出力する
   **DAG-CBOR base64** CACAO。`cbor parse` と `aud` の壁はこれで通過する。

2. **本番 Datom グラフは単一オペレータ所有が設計**（ADR-2606161200）。pod の IPNS controller は
   オペレータ `did:key:z35dec…` 固定で、テナント did:key は——エッジ許可リストに載っても——
   自分名義で private グラフを read/write できない（owner-binding / aud==operator）。

3. **書き込み CACAO には `capability=datom:transact` ＋ resource `kotoba://op/tx-create` が必須**だが、
   公開 `kotoba cacao-sign` CLI は `kotoba://op/*` を出力しない（テスト専用 Rust ヘルパ
   `build_ed25519_cacao_for_operation_with_resources` のみ）。かつ対象グラフは operator 所有が前提。

→ **実 PLM 書き込みラウンドトリップにはオペレータ identity／ツール（etzhayyim 管理の pod operator
   seed もしくは operator 発行の delegation）が必須**。テナント CACAO 単体・エッジ許可リスト単体では
   到達できない、というのが本番の設計上の境界。

## 本番への変更と復旧（記録）

検証のため本番 Worker（`net-kotobase`）を一時的に:

- `KOTOBASE_OPERATOR_DIDS` に当 did を追加（→ 403 が pod まで到達することを確認）
- `parseUpstreamJson` に upstream 本文透過を追加（→ pod の生エラーを確定）

上記は検証完了後に **git で baseline へ revert し再デプロイ済み**（Version bdbf2da4、
`KOTOBASE_OPERATOR_DIDS` = `did:web:news.gftd.ai,did:web:media.gftd.ai` のみ）。
共有本番に非機能な使い捨て鍵の operator 付与・デバッグコードは残していない。

## PLM ドメインを本番で動かすには

オペレータ資格が用意できれば、`kyber-plm.store/kotoba` の `post-fn` を以下の kotobase NSID へ
マップした実 HTTP transport に差すだけ（ドメインは backend 非依存なので無改変）:

- `transact` → `datomic.transact` `{graph, tx_edn, cacao_b64}`（operator CACAO: cap=datom:transact, res=kotoba://op/tx-create）
- `q`        → `datomic.q` `{graph, query_edn, cacao_b64}` → `rows_edn`
- `pull`     → `datomic.pull` `{graph, pattern_edn, entity}`
- graph は `datomic.createDatabase {db_name}` が返す CID（ただし operator 所有として登録要）。
