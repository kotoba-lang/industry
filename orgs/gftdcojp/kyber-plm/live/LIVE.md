# kyber-plm ライブ疎通（kotobase 分散 Datomic / CACAO）

`kotobase_probe.mjs` は自己署名 did:key Ed25519 **CACAO** を発行し、本番
`https://kotobase.gftd.ai` の分散 Datomic XRPC（`ai.gftd.apps.kotobase.datomic.*`）へ
実認証リクエストを投げる。CACAO は net-kotobase の `cacao.mjs` + kotoba-wasm を再利用し、
did:key 形式をサーバに一致させている。

```bash
cd live
node kotobase_probe.mjs            # createDatabase + read（テナント CACAO 経路）
node kotobase_probe.mjs --write    # transact も試行
KOTOBASE_OP_TOKEN=<jwt> node kotobase_probe.mjs --write   # オペレータ経路で書き込み
```

32-byte seed（秘密鍵）は `./.secrets/seed.hex`（**gitignore**）に永続化し再利用する。

## 検証済みの結果（2026-06-17, kotobase.gftd.ai）

| ステップ | メソッド | 結果 |
|---|---|---|
| 認証＋DB プロビジョニング | `datomic.createDatabase` | **200 OK** — 自 did 所有の graph CID を払い出し |
| Datom 書き込み | `datomic.transact` | **403** `Forbidden: operator-only Datom write` |
| 読み取り | `datomic.q` | 502（グラフ未マテリアライズ＝書き込みが無いため）|

結論:

- ✅ **CACAO は本番で完全に機能**。テナント did として認証が通り、
  `createDatabase` が `{ok:true, graph: bafyrei…, status:"created"}` を返す。
  （did 例: `did:key:zceda150…7e` / graph: `bafyreia73zs7r4zz66e2cciv5c4v7akrqjhayt5mavtukten42urloomcq`）
- ⛔ **Datom 書き込みはサーバ方針でオペレータ限定**（403）。これは認証情報の失敗ではなく
  認可ポリシー。テナント CACAO は createDatabase + read までが許可範囲。
- 認証の二経路（API-EXAMPLES.md）: オペレータ **Bearer JWT** と、自己主権 **CACAO + `x-kotoba-did`**。
  `x-kotoba-did` を付けるまでは 401、付与後は認可レイヤに到達（401→200/403）。

## PLM ドメインを kotobase 本番で動かすには

`kyber-plm.store/kotoba` の `post-fn` を、kotobase NSID 契約へマップした実 HTTP transport に差す:

- `transact` → `POST /xrpc/ai.gftd.apps.kotobase.datomic.transact` `{graph, tx_edn}`
- `q`        → `POST /xrpc/ai.gftd.apps.kotobase.datomic.q` `{graph, query_edn}` → `rows_edn`
- `pull`     → `POST /xrpc/ai.gftd.apps.kotobase.datomic.pull` `{graph, pattern_edn, entity}`
- graph は `datomic.createDatabase {db_name}` が返す canonical CID。

**書き込みにオペレータ資格情報（`KOTOBASE_OP_TOKEN` の Bearer JWT）が必要**。これが用意できれば
`store/kotoba` に実 post-fn を差し、ドメイン全体（release/受入/ECO/製造完了）を本番グラフ上で
ラウンドトリップできる（コードは backend 非依存なので無改変）。
