# ADR-2606271700: cloud-itonami — manimani 企業版として gftdcojp business activity を統合する

**Status**: closed  
**Date**: 2026-06-27  
**Closed**: 2026-06-27  
**Scope**: `orgs/gftdcojp/cloud-itonami`

## Context

`manimani` は個人の受信対応を queue、policy、ledger、agent loop に分解している。
gftdcojp には同じ構造で扱える業務が既に存在する。

- M365 archive: mail/calendar/docs/contracts/invoices/crm facts
- kyber-plm: PLM、ERP、MRP、MES production、GL、OCEL
- gftd-keiei-sim: HTR による経営仮説と意思決定
- litigation/legal repos: 証跡、期限、書面、外部専門家連携

これらが個別 repo / 個別語彙に分散しているため、「今の business activity 全体」を 1 つの
operating surface で扱えない。

## Decision

`cloud-itonami` を `manimani` の business 版として設計する。主体は gftdcojp であり、
企業が操作する app / UI / workflow なので配置は `orgs/gftdcojp/cloud-itonami` とする。

中核語彙は次の 4 つ。

- `:itonami.activity/*`: 企業活動。inbox、sales、contract、billing、legal、plm、erp、mes、keiei
- `:itonami.decision/*`: 人間または agent が下した判断
- `:itonami.effect/*`: 外部副作用。risk gate と承認状態を必ず持つ
- `:itonami.audit/*`: 実行結果、OCEL、証跡、source event

実装正本は `.cljc` とし、kotoba/datom log に保存する。Clojure/JVM、babashka、CLJS、
kotoba-clj/WASM のどこでも同じ業務モデルを使う。

kyber-plm は別操作面として残さず、`cloud-itonami.ops` / `cloud-itonami.plm` から呼び出す下位ドメイン
エンジンとして統合する。PLM/ERP/MES の不変条件は kyber が維持し、操作結果は
itonami store の activity/effect/audit へ同期する。

## Refactor From Kyber

kyber-plm は PLM/ERP 専門ドメインとして残す。`cloud-itonami` は kyber の entity/event を
上位語彙へ射影する。

| kyber-plm | cloud-itonami |
|---|---|
| item / BOM / ECO | `:plm` lane の artifact/activity |
| inventory / PO | `:erp` lane の artifact/activity |
| production completion / backflush / WIP | `:mes` lane の activity + financial effect |
| journal / cost rollup | financial risk の effect |
| OCEL event | audit |

これにより、ECO が契約、請求、在庫、GL、経営判断にどう影響したかを Datalog で横断できる。

## Manimani Compatibility

`manimani` から次を継承する。

- queue first UX
- policy selection
- approval inbox
- read-only auto / send-financial-destructive approval の risk gate
- append-only ledger
- agent investigate loop

ただし `cloud-itonami` では policy を個人メール方針ではなく business activity 方針へ一般化する。

## Consequences

- gftdcojp の日次 business activity は `cloud-itonami` の lane として統一される。
- kyber は外部操作面としては閉じ、`cloud-itonami.ops` / `cloud-itonami.plm` の下位互換エンジンとして維持できる。
- kotoba/clj substrate に寄せることで、企業 UI、agent、CLI、WASM host が同じモデルを共有できる。
- 既存データの破壊的移行はしない。kyber 由来の entity/event は itonami activity/effect/audit へ射影する。

## Business Coverage

初期実装では、gftdcojp の既存 facts を次の lane に正規化する。

| source | adapter | lane |
|---|---|---|
| `m365-archive/facts/messages.edn` | `cloud-itonami.m365/message->tx` | `:inbox` |
| `m365-archive/facts/events.edn` | `cloud-itonami.m365/event->tx` | `:inbox` |
| `m365-archive/facts/crm.edn` | `cloud-itonami.m365/crm->tx` | `:sales` |
| `m365-archive/facts/contract-terms.edn` | `cloud-itonami.m365/contract-term->tx` | `:contract` |
| `m365-archive/facts/invoice-terms.edn` | `cloud-itonami.m365/invoice-term->tx` | `:billing` |
| `m365-archive/facts/procedure-terms.edn` | `cloud-itonami.m365/procedure-term->tx` | `:procedure` |
| `m365-archive/facts/hr-terms.edn` | `cloud-itonami.m365/hr-term->tx` | `:employee` |
| `m365-archive/facts/people.edn` | `cloud-itonami.m365/person->tx` | `:employee` / `:sales` |
| `m365-archive/facts/projects.edn` | `cloud-itonami.m365/project->tx` | `:sales` |
| `m365-archive/facts/mail-projects.edn` | `cloud-itonami.m365/mail-project->tx` | `:sales` |
| `m365-archive/facts/ses-engineers.edn` | `cloud-itonami.m365/ses-engineer->tx` | `:sales` |
| `m365-archive/facts/ses-cases.edn` | `cloud-itonami.m365/ses-case->tx` | `:sales` |
| `m365-archive/facts/teams-messages.edn` | `cloud-itonami.m365/teams-message->tx` | `:inbox` |
| `m365-archive/facts/decisions.edn` | `cloud-itonami.m365/decision-fact->tx` | `:inbox` decision |
| `kyber-plm` Store graph | `cloud-itonami.migration/project-kyber-graph` | `:plm` / `:erp` |
| `kyber-plm.production/complete-production!` | `cloud-itonami.mes/kyber-completion->tx` | `:mes` |

Lane ごとの既定 owner/default policy は `cloud-itonami.operating/lane-catalog` に置く。
`:read-only` は自動実行、`:external-send` / `:financial` / `:destructive` は承認 inbox へ送る。

実データ検証（2026-06-27）:

```sh
clojure -M:ingest ../m365-archive/facts procedure-terms hr-terms people ses-engineers ses-cases
```

結果:

```edn
{:facts {:hr-terms 101, :people 5418, :procedure-terms 118, :ses-cases 2168, :ses-engineers 3862}
 :tx {:activities 11667, :actors 5526, :artifacts 219, :decisions 0, :effects 11667, :relations 0, :audits 0}}
```

## Agent Loop

`cloud-itonami.agent/create-business-agent` は `langgraph-clj` の `create-react-agent` を使う。
Hermes/OpenClaw 互換の tool-calling model を注入できる設計にし、既定で
`:interrupt-before #{:tools}` を設定する。これにより ReAct loop は:

1. activity を観測する
2. tool call を提案する
3. tool 実行前に HITL interrupt で停止する
4. 承認後 resume し、effect を `:proposed` として記録する
5. financial/external/destructive は approval inbox へ送る

test では `mock-hermes-model` で deterministic に検証する。

## Store And Approval Runner

`cloud-itonami.store` は `langchain.db/api` と同じ形に寄せた file-backed local store 境界を提供する。
同じ contract で `langgraph.checkpoint/datomic-checkpointer` と itonami facts を共有できるため、
本番では `langchain.kotoba-db` / kotoba-server XRPC に差し替える。

store import は actor/artifact -> activity -> relation/decision/effect/audit の順で tx-data を並べる。
また `:db.cardinality/many` の ref は `:db/add` に展開し、同一 batch 内の lookup-ref は tempid 化する。
これにより M365 facts や kyber projector の Datomic 風 tx-data を local store へ安全に取り込める。

`cloud-itonami.approval` は proposed effect を `:approved` / `:rejected` に進め、
approved effect だけを handler map で実行する。handler がない effect は fail closed で `:failed` になる。
承認・実行結果は decision/audit として残す。

`cloud-itonami.runtime` は itonami facts と langgraph checkpoint を同じ store conn で共有する。
これにより Hermes/OpenClaw 互換 tool-calling model は store 内の activity を読み、
proposed effect を同じ kotoba/datom log に残せる。

`cloud-itonami.kotoba` は JVM host-caps、`langchain.kotoba-db/kotoba-api`、schema install、
facts import、counts、store-backed mock ReAct を CLI として提供する。接続情報は
`KOTOBA_URL` / `KOTOBA_GRAPH` / `KOTOBA_TOKEN` または `KOTOBA_CACAO` + `KOTOBA_DID` から読む。

## Closure

本 ADR は 2026-06-27 時点で closing。決定した統合境界は実装済み。

最終的な正本 API:

- `cloud-itonami.plm`: item / BOM / ECO などの PLM authoring API
- `cloud-itonami.ops`: release、goods receipt、MRP、ECO release、production completion の PLM/ERP/MES 操作 API
- `cloud-itonami.mes`: work order / production / backflush を itonami activity/effect/audit へ射影
- `cloud-itonami.migration`: kyber graph 全体を itonami tx-data へ投影
- `cloud-itonami.kyber-ops`: 互換 shim。新規コードでは使わない

実装済みファイル:

- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/schema.cljc`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/bootstrap.cljc`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/activity.cljc`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/mes.cljc`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/plm.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/kyber.cljc`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/ops.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/kyber_ops.clj`（compat shim）
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/migration.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/m365.cljc`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/facts.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/store.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/kotoba.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/operating.cljc`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/agent.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/runtime.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/approval.clj`
- `orgs/gftdcojp/cloud-itonami/test/cloud_itonami/*`

## Verification Notes

2026-06-27 の実装検証:

- `clojure -M:test`: 19 tests, 68 assertions, 0 failures, 0 errors
- `git annex get facts/procedure-terms.edn facts/hr-terms.edn facts/people.edn facts/ses-engineers.edn facts/ses-cases.edn`
  で社員・手続き・SES facts content を取得
- `clojure -M:ingest ../m365-archive/facts procedure-terms hr-terms people ses-engineers ses-cases`
  で `activities=11667, actors=5526, artifacts=219, effects=11667`
- `clojure -M:store import /tmp/cloud-itonami-procedure-hr.edn ../m365-archive/facts procedure-terms hr-terms`
  取得済み store で `activities=219, artifacts=219, actors=93, effects=219`
- `clojure -M:store counts /tmp/cloud-itonami-procedure-hr.edn`
  再オープン後も同じ counts を確認
- `clojure -M:runtime mock-react /tmp/cloud-itonami-procedure-hr.edn 手続きを進めて gftd-procedure-thread`
  で checkpoint 5 件と financial proposed effect 1 件を同じ store に追加
- `clojure -M:kotoba` は `KOTOBA_URL is required` まで起動確認
- `cloud-itonami.kotoba-test` で schema install と facts import が injected kotoba `db-api`
  transaction に流れることを確認

SES / people を含む 11,667 activities の全 subset store import は local `langchain.db` では重い。
この checkout では `KOTOBA_URL` / `KOTOBA_GRAPH` が未設定のため、全量永続化は kotoba-backed
store の実接続設定後に再検証する。

## Closed Decision

`kyber-plm` は名称・操作面としては閉じ、`cloud-itonami` の下位ドメインエンジンにする。
利用者・agent・UI は `cloud-itonami.ops` / `cloud-itonami.plm` を呼び、結果は itonami store の
activity/effect/audit に残す。kyber namespace は互換・内部実装としてのみ残す。
