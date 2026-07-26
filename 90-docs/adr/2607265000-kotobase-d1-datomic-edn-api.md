# ADR-2607265000: D1-backed Kotobase は Datomic の EDN 文法をそのまま公開する

**Status**: accepted / implemented  
**Date**: 2026-07-26  
**Decider**: Jun Kawasaki

## Context

Kotobase の storage は、immutable CID blocks と conditional mutable refs の
provider-neutral contract に分離され、PostgreSQL、S3/R2、IPFS/IPNS、SQLite、
Cloudflare D1 を別 backend として選択する設計になった。

一方、D1 で実証されていたのは block 保存、ref CAS、CACAO authentication、
tenant/capability authorization までだった。`kotobase-engine.cljs` には
`transact!`、`datoms`、Datalog query、`pull` が存在したが、D1 binding を
`IBlockStore` / `IRefStore` として実装する adapter がなく、D1 上で Datomic-shaped
API を end-to-end に呼ぶことはできなかった。

また、LLM や利用者が backend ごとに別の JSON query DSL を覚える設計は避ける必要がある。
Kotobase が Datomic-shaped API を掲げるなら、transaction と query の入力は
Datomic と同じ EDN 文法・引数順にするべきである。ただし、Datomic 製品固有の
numeric basis-t、transactor、tempid、log API まで実装済みであるかのように装っては
ならない。

## Decision

### D1. `kotobase.datomic` を互換面とする

`kotobase-engine` に `kotobase.datomic` namespace を置き、次の引数順とEDN文法を
公開する。

```clojure
(require '[kotobase.datomic :as d])

(d/transact conn
  {:tx-data
   [{:db/id "alice"
     :person/name "Alice"
     :person/role "admin"}]})

(d/q '[:find ?e ?name
       :in $ ?role
       :where
       [?e :person/role ?role]
       [?e :person/name ?name]]
     (d/db conn)
     "admin")

(d/pull (d/db conn) [:person/name :person/role] "alice")

(d/datoms (d/db conn)
  {:index :eavt :components ["alice"]})
```

`d/q` はDatomicの vector/map query representationを受ける。relation、scalar
`.`、collection `...`、tuple、`:keys` / `:strs` / `:syms` result shapeを扱う。
engine が持つ join、`:in`、negation、`or` / `or-join`、whitelist済み
predicate/function、aggregate、rules を同じ query dataから利用する。

TransactionはDatomic Client形の `{:tx-data [...]}`、entity map、
`:db/add`、`:db/retract`、`:db/retractEntity` を受ける。

### D2. D1 は直接bindingするstorage providerとする

`kotobase-storage-d1` にClojureScriptの `D1Storage` を実装する。

- `IBlockStore`: immutable block put/get、batch semantics、CID collision検出
- `IRefStore`: ref read、genesis/update CAS
- `IBackendCapabilities`: D1の明示的なcapability set

Worker内で `env.DB` を直接adapterへ渡す。D1をPostgreSQLとして扱わず、
embedded SQLite adapterとも共有実装にしない。SQLの一部は似ていても、D1 binding、
remote execution、transaction/consistency boundaryが異なるためである。

Kotobase engineはtransactionを複数のimmutable IPLD blocksとして先に保存し、最後に
ref CASでpublishする。D1 providerもこの順序を守る。providerから読んだblockは
engineの既存CID検証を通らなければdecode・queryに使用されない。

### D3. Worker wire formatはEDNとする

Workerは次を公開する。

- `POST /v1/transact`
- `POST /v1/q`
- `POST /v1/pull`
- `POST /v1/datoms`
- `GET /v1/head`

request/response bodyは `application/edn` とする。keyword、symbol、set、query vector、
pull selectorをJSON風の独自表現へ変換しない。queryはEDN readerでdataとして読むだけで、
`eval`しない。function clauseはengineの固定whitelistだけを実行できる。

database refは `x-kotobase-ref` headerで指定する。これはquery dataではなくtransport
metadataである。

### D4. authentication、authorization、database visibilityを分離する

各requestは新しい署名済みCACAOを必要とする。

- authentication: Ed25519 `did:key` CACAO署名、issued-at、expiry、nonceを検証
- authorization: transaction/read capabilityとtenant-ref prefixをdeny-by-defaultで検証
- replay protection: D1のnonce tableへclaim
- evidence: credential本体を保存せず、sanitized authn/authz decisionを保存

認証が成立したことだけではdatabase accessを許可しない。capabilityとref scopeを別に
評価する。現行の `visible?` はref全体を読む権限が成立した後の構造的seamであり、
このADRはattribute/row-level policyを新たに定義しない。

### D5. 「同じ文法」と「同じ製品」を区別する

互換を主張するのはEDN transaction/query文法とAPI引数順である。
次はDatomic製品互換の対象外である。

- numeric basis-t / transaction entity id
- immutable cached Datomic `Db` valueと同一のconnection lifecycle
- tempid allocation/resolution、lookup refs
- transaction functions、listeners、sync、log
- `index-range`、`seek-datoms`、`entity` / `touch`、`pull-many`
- Datomicの完全なscalar/schema semantics

Kotobaseのbasisとtransaction identityはCIDである。`d/transact` reportはnumeric tを
捏造せず、`:db-before` / `:db-after` にhead CIDを返す。

## Alternatives considered

### Backend固有JSON query DSL

却下。Datomic queryをJSON objectへ再発明すると、LLMと利用者が二つの文法を覚え、
symbol、set、list、rules、pull selectorの意味も失われる。

### D1 Workerにengineを複製実装する

却下。query、pull、IPLD、CID検証、CAS retryをJavaScriptで再実装すると、
PostgreSQL/SQLite/S3/IPFS backendとの意味論が分岐する。Workerは同じCLJS engineを
compileして利用する。

### D1をPostgreSQL backendとして扱う

却下。D1はSQLite系SQL semanticsでありPostgreSQLではない。Cloudflare上で
PostgreSQLを使う場合はHyperdrive等を介した `kotobase-storage-postgres` の責任である。

### D1とembedded SQLiteを一つのrepoにする

却下。schema vocabularyは共有できるが、JDBC/local-file transactionと
Worker/D1 remote bindingは運用・整合性・API boundaryが違う。

## Verification

### Provider-neutral engine

`kotobase-engine`:

```text
3 tests / 11 assertions
0 failures / 0 errors
```

Datomic vector query、keyword attributes、entity-map transaction、`:in $`、
scalar、collection、`:keys`、pull、datomsを検証した。

### Local D1

Wrangler/workerdと実local D1 fileにmigrationを適用し、以下をend-to-endで確認した。

- CACAO authn、wrong capability拒否、cross-tenant拒否、nonce replay拒否
- multi-block transactionとhead CAS
- relation query、`:in` query、scalar query、collection query、aggregate
- pull、EAVT、AVET
- retract後のquery反映
- persisted head
- stale CAS、CID collision拒否

### Remote Cloudflare D1

remote database `kotobase-d1-verification`
(`6f42fbb0-20ee-4187-9435-bd71b4e264bb`) に既存migrationが適用済みであることを
確認した。

最終artifactはWorker version
`5e7e9280-588e-4681-9889-ba344e8649f5` としてuploadし、そのversion preview URLで
localと同じE2E suiteを通過した。先行artifact
`3c345154-d05a-4dc3-90f8-f902c2475d74` もstable workers.dev URLで同じ
Datomic API suiteを通過した。

検証Workerはpublic abuseを避けるため終了後に削除した。D1 database、migration、
source、build/deploy scriptsは保持しており再deploy可能である。これはproduction
routeの常設・SLO・load qualificationを意味しない。

`npm audit` は0 vulnerabilities。最終shadow-cljs release buildは成功した。
upstream `io-multiformats` docstringの`@noble`をClosure CompilerがJSDoc tagとして
警告するが、build failureではなく本変更のruntime correctnessには影響しない。

## Consequences

- backendを変更してもapplication/LLMは同じDatomic EDN queryを使用できる。
- D1にもPostgreSQL/SQLite/S3/IPFSと同じstorage contractが適用される。
- authn/authzはengineへ混ぜず、Worker request boundaryでfail-closedに保たれる。
- D1のquery能力を過大評価せず、未実装のDatomic製品機能を明示できる。
- full graph hydrateはdataset sizeとWorker budgetに依存する。cold/index-pruned read、
  folding、load qualificationはproduction化前に別途必要である。
- `kotobase-engine`、`kotobase-storage`、`kotobase-storage-d1` は現時点でlocal
  independent reposかつ未publish/未commitであり、git coordinateの確定とwest登録は
  別のgraduation作業である。

## Related decisions

- ADR-2607032430: log-structured per-actor Kotobase engine
- ADR-2607032500: Kotoba / Kotobase と Clojure / Datomic の関係
- ADR-2607177000: per-DID CACAO authorization
- ADR-2607261300: Kotobase split dependency audit and repo-name hazards
- `260726-gftd-jk-luxury-kotobase-postgres-policy`: PostgreSQL production default
  policy。本ADRはこれをsupersedeせず、D1をsilent fallbackにも指定しない

