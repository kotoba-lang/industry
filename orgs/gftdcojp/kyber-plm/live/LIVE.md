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

## オペレータ資格の所在（K8s で確認, 2026-06-17）

1Password（gftdcojp 174 / Private 565 / Apple PM 364 項目）には operator seed は**無し**。
実体は etzhayyim の Vultr K8s クラスタにある:

- ns `kotoba` / secret **`kotoba-agent-identity`** … `KOTOBA_AGENT_DID`（公開: `did:key:z35dec6b49…` ＝本番 operator）/ `KOTOBA_AGENT_ED25519_HEX`（operator seed・秘匿）/ `KOTOBA_AGENT_X25519_HEX`
- ns `kotoba` / secret **`kotoba-internal-trust`** … pod が要求する `x-internal-trust`

検証で判明（pod 直叩き）:
- `x-internal-trust` ＋ operatorBearer（`{sub: operator_did}` 非署名, alg:none）は**受理**される。
- ただし private graph は `cacao_b64` 必須（operator でも）。

## 最終ブロッカー：kotoba 内の did:key 符号化不整合

同一 Ed25519 鍵に対し、kotoba のツール群が**異なる did:key 表現**を使う:

| 生成元 | operator 鍵の表現 |
|---|---|
| agent identity / `KOTOBA_AGENT_DID` / `KOTOBASE_OPERATOR_DID` | hex 形 `did:key:z35dec6b49…` |
| `kotoba cacao-sign` / `did-derive`（CACAO の iss・scope） | 標準 multibase 形 `did:key:z6Mki5YgG…` |
| kotoba-wasm `useIdentity`（probe の tenant did） | hex 形 `did:key:zceda…` |

このため `cacao-sign` が出す CACAO の iss/scope（標準形）が、サーバの owner/aud（hex 形）と
**文字列一致せず** private graph の scope 照合に通らない。加えて `datomic.transact` は
`kotoba://op/tx-create` リソースが必須だが公開 `cacao-sign` は出力しない。

→ 実書き込みには (a) did:key 表現を揃えた CACAO を operator seed で**手組み（DAG-CBOR 署名）**、
   または (b) kotoba 側ツールの did 表現統一フィックス、が必要。tenant の書き込み口は
   `kg.ingest`（quad）で別データモデル。いずれも kotoba プラットフォーム側の作業。

> 本番への高権限書き込み（operator マスター鍵での署名）はここで checkpoint。probe で作成した
> 空グラフ（`kyber-plm`）は append-only/tombstone 可で無害、データ未書き込み。

## ✅ 本番への実書き込み成功（tenant 経路, operator 鍵不使用）

`datomic.transact`（operator 専用・hex did バグでブロック）を避け、**tenant 書き込み口
`kg.ingest`** で本番 kotoba pod に PLM item を実書き込みできた（`live/kg_ingest.mjs`）:

```
POST kotoba-backend.gftd.ai/xrpc/com.etzhayyim.apps.kotobase.kg.ingest
→ {"ok":true,"subjectCid":"bafyreidqn5dhkho4pvsom2kbwjrgm2pjmcng2i4p4op43yxtilodn6wlgy","quadCount":4}
```

確定した本番 auth/スキーマ:
- **KG write は Bearer JWT 必須**（CACAO 不可）。pod は `sub == tenant_did` で authorize。
  edge BFF が trust 境界なので非署名 JWT（alg:none, `{sub: tenant_did}`）で可。
- claim 形は **`{pred, value}`**（lexicon の `{predicate, object}` ではない）。
- tenant did は**標準形 did:key で一貫**させれば operator hex-did バグを完全回避できる。
- `x-internal-trust`（secret `kotoba-internal-trust`）で pod 直叩き（edge をバイパス）。

### read-back は operator `kg.commit` 待ち
ingest は **hot Arrangement** に入る。SPARQL（`kg.query`）は **cold storage** を読むため、
封印前は 0 件（`{"ok":true,"results":[]}`）。`kotoba commit`（= `kg.commit`, **operator 操作**で
hot→cold を ProllyTree に封印）後に SPARQL で読める。commit は共有 hot 状態全体を封印する
operator mutation のため、本セッションでは checkpoint（未実行）。

> SPARQL の述語は絶対 IRI 必須・SELECT のみ・変数述語不可。kg 射影は
> `kg/id` `kg/type` `kg/label/en` `kg/claim/<pred>` `kg/relation/<pred>`（kotoba-server/src/kg.rs）。

## PLM ドメインを本番で動かすには

オペレータ資格が用意できれば、`kyber-plm.store/kotoba` の `post-fn` を以下の kotobase NSID へ
マップした実 HTTP transport に差すだけ（ドメインは backend 非依存なので無改変）:

- `transact` → `datomic.transact` `{graph, tx_edn, cacao_b64}`（operator CACAO: cap=datom:transact, res=kotoba://op/tx-create）
- `q`        → `datomic.q` `{graph, query_edn, cacao_b64}` → `rows_edn`
- `pull`     → `datomic.pull` `{graph, pattern_edn, entity}`
- graph は `datomic.createDatabase {db_name}` が返す CID（ただし operator 所有として登録要）。
