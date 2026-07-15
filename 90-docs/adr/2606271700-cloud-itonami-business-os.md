# ADR-2606271700: cloud-itonami — manimani 企業版として gftdcojp business activity を統合する

**Status**: closed  
**Date**: 2026-06-27  
**Closed**: 2026-06-27  
**Scope**: `orgs/gftdcojp/cloud-itonami`

## Context

`manimani` は個人の受信対応を queue、policy、ledger、agent loop に分解している。
gftdcojp には同じ構造で扱える業務が既に存在する。

- M365 archive: mail/calendar/docs/contracts/invoices/crm facts
- kyber-plm: PLM、ERP、MRP、MES production、GL、OCEL の履歴語彙
- gftd-keiei-sim: HTR による経営仮説と意思決定の履歴設計
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

kyber-plm は別操作面として残さず、PLM/ERP/MES の語彙と不変条件を `cloud-itonami.ops` /
`cloud-itonami.plm` / `cloud-itonami.mes` へ吸収する。操作結果は itonami store の
activity/effect/audit へ同期する。

## Refactor From Kyber

kyber-plm の standalone source tree は retired とし、`cloud-itonami` は kyber 由来の entity/event を
上位語彙へ射影する compat/history model を持つ。

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
- kyber は外部操作面としては閉じ、`cloud-itonami.ops` / `cloud-itonami.plm` の compat/history 語彙として維持できる。
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
| kyber historical Store graph | `cloud-itonami.migration/project-kyber-graph` | `:plm` / `:erp` |
| kyber production completion event | `cloud-itonami.mes/kyber-completion->tx` | `:mes` |

Lane ごとの既定 owner/default policy は `cloud-itonami.operating/lane-catalog` に置く。
`:read-only` は自動実行、`:external-send` / `:financial` / `:destructive` は承認 inbox へ送る。

実データ検証（2026-06-27）:

```sh
clojure -M:ingest ../m365-archive/facts procedure-terms hr-terms people ses-engineers ses-cases
clojure -M:ingest report ../m365-archive/facts procedure-terms hr-terms people ses-engineers ses-cases
```

結果:

```edn
{:facts {:hr-terms 101, :people 5418, :procedure-terms 118, :ses-cases 2168, :ses-engineers 3862}
 :tx {:activities 11667, :actors 5526, :artifacts 219, :decisions 0, :effects 11667, :relations 0, :audits 0}}
```

`report` は facts ごとの `:available?`、件数、tx summary を返す。未取得 git-annex pointer は
全体を失敗させず、対象 pointer と `git annex get facts/<name>.edn` を返す。
2026-06-27 時点では社員・手続き・SES subset はすべて `:available? true`。一方、
`contract-att-terms` / `billing-att-terms` / `finance-terms` / `current-billing` /
`invoice-amounts-recovered` / `financials-*` / `trial-balance` / `chart-of-accounts` は
adapter 登録済みだが、この checkout では annex object 未取得のため `:available? false`。

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
runtime tool は `itonami_queue_summary` / `itonami_list_activities` / `itonami_plan_activity` /
`itonami_propose_effect`。queue summary は全社員・全手続き・sales/inbox を含む
operating queue を lane/state 別に集計し、次に進める activity を返す。

`cloud-itonami.kotoba` は JVM host-caps、`langchain.kotoba-db/kotoba-api`、schema install、
facts import、counts、store-backed mock ReAct を CLI として提供する。接続情報は
`KOTOBA_URL` / `KOTOBA_GRAPH` / `KOTOBA_TOKEN` または `KOTOBA_CACAO` + `KOTOBA_DID` から読む。
`cloud-itonami.company` は gftdcojp/gftdjapan の現在の手続き・社員 operating set
（`procedure-terms` / `hr-terms` / `people` / `ses-engineers` / `ses-cases`）を
coverage、local bulk import、mock React bootstrap する CLI として提供する。
同じ namespace の `business-manifest` / `import-business` は 24 supported m365 fact kind 全体を
inbox、CRM、project、contract、billing、finance、procedure、people、decision、SES の
business takeover surface として扱う。
全量 local 検証は `.bin` shard の `import-business-shards` を使う。
`messages` / `teams-messages` のような巨大 raw inbox は streaming reader で chunk shard 化する。
kotoba 側にも `preflight` / `import-company` / `import-business-core` / `import-business-raw` /
`import-business` / `mock-react-company` を置き、operating set と business 全体を kotoba
transaction 境界へ流せるようにする。巨大な `messages` / `teams-messages` は chunk 単位で
tx-data 化し、全量を一度に materialize しない。
`cloud-itonami.doctor` は company facts coverage、business manifest、business shard readiness、
local store counts、kotoba preflight、model preflight をまとめて readiness として返す。
`operate-store` は既存 company store に対して OpenAI-compatible OpenClaw smoke、
`xrpc-smoke-store`、approval dry-run、doctor readiness を一つの実行結果にまとめる
release-gate 用の入口。
`local-react-gate` はその store に残った checkpoint、effect status、pending approval を
読み返し、OpenClaw/XRPC/approval dry-run が実行済みで remaining 0 になったことを
単独で再監査する。
`business-local-gate` は all business facts、core/raw shard coverage、raw inbox shard、
business-core XRPC real-model approval gate をまとめて再監査する。
`operate-company` は import から作り直す検証用で、全量 facts では重い。
`runbook-company` は同じ手順を bash script として出力し、実 kotoba / real model の credential
設定後に core/all の `operate-live`、`live-evidence`、`completion-audit` を通すコマンドもコメントで含める。
`runbook-live` は credential 投入後の実行専用 bash を出力し、`KOTOBA_URL` / `KOTOBA_GRAPH`、
evidence target、kotoba auth、model key alias を fail-fast で確認してから `live-preflight-gate`、core/all の
`operate-live`、必要に応じた `resume-live`、`live-evidence`、`completion-gate` を順に実行する。
core/all の live evidence path は別ファイルを必須とし、`runbook-live` は同一 path を開始前に拒否する。
`completion-audit` も `:live-evidence-paths-distinct` を requirement として監査する。
`production-preflight` は実運用前に facts、business shards、local react、kotoba/model config を
一括確認し、kotoba business import、core/all live gate、real React loop の実行コマンドを返す。
同時に推奨 core/all `operate-live` の evidence target safety を `:live-evidence-targets` に返し、
既存 evidence file が別 target を指す場合は credential 投入前に確認できる。
同じ確認は `clojure -M:doctor live-targets [facts-dir] [live-evidence.edn] [live-all-evidence.edn] [thread-id]`
でも単独実行できる。shell で non-zero gate にしたい場合は `live-targets-gate` を使う。
Babashka では `nbb live-targets ...` と `nbb live-gate [facts-dir] [store.edn] [thread-id] [live-evidence.edn] [live-all-evidence.edn]`
を用意し、`live-gate` は evidence target、`live-preflight-gate`、`production-preflight` を runbook と同じ引数順で確認する。
`nbb live-env` は現在不足している env から shell の `export` skeleton と次の live command を返す。
`nbb live-env-script` は skeleton だけを plain text で出し、auth または model key が未入力のままなら
`nbb live-gate` 前に停止する。
`nbb live-env-evidence ... /tmp/cloud-itonami-live-env-evidence.edn` は secret 値を保存せず、
env の存在 boolean、preflight、missing env、advance plan の redacted snapshot を保存する。
`nbb live-env-evidence-report ...` / `nbb live-env-evidence-gate ...` は保存済み snapshot の
target、redaction、env presence、next action kind を検査する。
`nbb live-ready-loop ... /tmp/cloud-itonami-live-env-evidence.edn 5` は redacted env evidence を更新し、
その gate を通してから ready な live advance を blocking まで進める。
`nbb live-ready-loop-evidence ... /tmp/cloud-itonami-live-ready-loop.edn 5` は同じ loop 結果を
redacted evidence として保存する。
`nbb live-env-file-template-file /tmp/cloud-itonami-live.env` は secret 値を空欄にした
live credential file の雛形を作る。既存ファイルは上書きしない。
`nbb live-env-file-evidence /tmp/cloud-itonami-live.env /tmp/cloud-itonami-live-env-file.edn` は
live credential file の key presence と auth/model readiness だけを redacted EDN として保存する。
`nbb live-env-file-preflight /tmp/cloud-itonami-live.env /tmp/cloud-itonami-live-env-file-preflight.edn` は
env-file を直接 parse して Kotoba auth と model provider/url/key の redacted preflight を保存し、
`nbb live-env-file-preflight-gate ...` は live 実行可能でなければ non-zero exit する。
`nbb live-env-file-runbook-file ... /tmp/cloud-itonami-live-env-file-runbook.sh` は procedure/employee
takeover/react-loop gate を通してから env-file を source し、core/all live operate、post-run refresh、
completion gate まで進める bash を実行可能ファイルとして保存する。
`nbb procedure-employee-takeover ../m365-archive/facts /tmp/cloud-itonami-company.edn` は
`procedure-terms`、`hr-terms`、`people` の fact count と local store の `:procedure` / `:employee`
activity count を再読し、`procedure` activity、HR procedure、internal people registry、people actor の
expected/actual count が不足していないことを証跡化する。
`nbb procedure-employee-react-loop ...` は同じ store の lane 別 effect status、checkpoint、approval drain を
再読し、procedure/employee lane の done effect count が takeover expected count を満たすことを証跡化する。
`nbb live-launch-checklist ... /tmp/cloud-itonami-live-launch-checklist.edn` は target safety、
env-file readiness、env-file preflight readiness、procedure/employee takeover/react-loop readiness、runtime acceptance readiness、runbook file、execution packet、completion audit evidence をまとめた
go/no-go 証跡を保存し、既存の credential-gap 証跡があれば non-blocking diagnostic として同梱する。
`nbb live-launch-checklist-report ...` は env-file 起点の next action を返し、
`nbb live-launch-checklist-gate ...` は `:launchable? true` でなければ non-zero exit する。
`nbb live-launch-handoff ... /tmp/cloud-itonami-live-launch-handoff.edn` は checklist から不足 gate、
投入すべき env key、credential-gap 要約、次の operator action、refresh/gap/gate/launch コマンドを secret なしの redacted
handoff として保存する。handoff gate は launchable false でも handoff 自体の整合性があれば通る。
`nbb live-launch-rehearsal ... /tmp/cloud-itonami-live-launch-rehearsal.edn` は checklist、handoff、
execution packet、runbook file を突き合わせ、exact run order、target path、secret marker 不在、
handoff/checklist の不足 gate 一致を redacted 証跡化する。rehearsal gate は launchable false でも
実行前構造が整合していれば通る。
`nbb live-credential-gap ... /tmp/cloud-itonami-live-credential-gap.edn` は env-file evidence、
env-file preflight、launch rehearsal を突き合わせ、構造は ready で残りが credential だけであることを
redacted 証跡化する。
`nbb live-credential-handoff ... /tmp/cloud-itonami-live-credential-handoff.edn` は credential-gap、
launch-ready、launch-contract の redacted report を束ね、投入すべき env key、refresh command、
gate command、次の operator action を secret なしで作業票化する。handoff gate は credential 投入待ちとして
整合している場合だけ通る。
`nbb live-runtime-acceptance ... /tmp/cloud-itonami-live-runtime-acceptance.edn` は元 store をコピーし、
OpenClaw-compatible、Hermes-compatible、Kotoba XRPC、Kotoba XRPC real-operate の ReAct/tool-calling
経路を redacted smoke evidence として保存する。元の `/tmp/cloud-itonami-company.edn` は変更しない。
`nbb live-business-acceptance ... /tmp/cloud-itonami-live-business-acceptance.edn` は procedure/employee
takeover、procedure/employee react-loop、launch checklist、handoff、rehearsal、credential-gap、runtime acceptance、
execution packet をひとつの redacted acceptance 証跡に束ねる。acceptance gate は全手続き・全社員手続きの
count と local react-loop、runtime/model/Kotoba XRPC 推進経路が揃い、launch 前の残りが credential だけである状態を通す。
`nbb live-launch-ready ... /tmp/cloud-itonami-live-launch-ready.edn` は env-file evidence、preflight、
launch checklist、handoff、rehearsal、credential-gap、runtime acceptance、business acceptance、
execution packet、runbook を束ねた最終 go/no-go 証跡を保存する。launch-ready gate は credential も含めて
env-file/preflight/checklist が launchable になった場合だけ通る。
`nbb live-launch-cutover-dry-run ... /tmp/cloud-itonami-live-launch-cutover-dry-run.edn` は live write を実行せず、
runbook と execution packet の両方で final launch-ready gate、launch proof bundle gate、launch contract gate が最初の `operate-live` より前に置かれていることを
redacted 証跡化する。dry-run gate は構造検査なので credential 未投入でも通り、`ready-to-launch?` で実行可否を分ける。
`nbb live-launch-proof-bundle ... /tmp/cloud-itonami-live-launch-proof-bundle.edn` は procedure/employee takeover、
procedure/employee react-loop、runtime acceptance、business acceptance、final launch-ready、cutover dry-run、
completion audit をひとつの redacted 証跡に束ねる。bundle gate は証跡構造を検査し、`complete?` と `launchable?` で
実 live 完了可否を分ける。
`nbb live-launch-contract ... /tmp/cloud-itonami-live-launch-contract.edn` は final launch-ready、cutover dry-run、
launch proof bundle、execution packet、runbook file を束ね、credential 投入後に実行してよい `bash runbook` command を
redacted contract として保存する。contract gate は launch-ready / cutover / proof-bundle がすべて launchable の場合だけ通る。
`nbb live-readiness-audit ... /tmp/cloud-itonami-live-readiness-audit.edn` は live operating scope、
business acceptance、credential handoff、launch-ready、launch-contract を束ね、全手続き・全社員手続きの
取り込みと react-loop 推進証跡が揃っているか、現在の次 action が credential fill か launch contract 実行かを
redacted に保存する。audit gate は credential 未投入でも、全社 scope が証明済みで次 action が明確なら通る。
`nbb live-credential-resume ... /tmp/cloud-itonami-live-credential-resume.edn` は readiness audit と credential
handoff から、credential 投入後に実行する refresh、gate、launch command を redacted packet として保存する。
resume gate は packet が actionable で、secret marker を含まない場合だけ通る。
`nbb live-credential-resume-script-file ... /tmp/cloud-itonami-live-credential-resume.sh` は同じ packet から
実行可能な bash script を生成する。script gate は resume packet gate、refresh、launch-ready/contract gate、
runbook launch が含まれ、secret marker を含まないことを検査する。
`nbb live-execution-packet-gate ...` は credential 投入後に実行する command packet が
procedure/employee takeover/react-loop gate、live operating scope gate、runtime acceptance gate、final launch-ready gate、cutover dry-run gate、launch proof bundle gate、launch contract gate から始まる exact run order、必須コマンド、target、
redaction、secret marker 不在を満たすことを検査する。
`nbb live-status ...` は同じ引数順で full fact coverage を読まず、target safety、kotoba/model credential、
core/all live evidence の有無、next command、現在不足している `:missing-env`、実行順の `:next-actions`、
許可された `:action-kinds` を返す。
`nbb live-next ...` は `live-status` の最初の `:next-actions` command を plain text で返す。
`nbb live-next-executable ...` は credential 投入待ちでは credential resume script を生成して実行する command を返し、
ready 後は core/all `operate-live` command を返す。
`nbb live-next-action ...` は同じ action map を EDN で返すため、runbook/CI は kind、reason、
command、missing env を typed data として扱える。
`nbb live-advance-plan ...` は next action に対する gate、run、verify、after、blocking 状態を
EDN で返し、agent が credential 待ちから core/all live 実行、completion gate まで同じ形で進める。
`nbb live-advance-once ...` は next action が ready な場合だけ gate、run、verify を一段実行し、
credential 待ちなどの blocking 状態では non-zero で停止する。
`nbb live-advance-until-blocked ... 5` は ready な action を最大 step 数まで繰り返し、
次の blocking 状態を EDN として返す。
`nbb live-next-kind ...` は同じ action の kind だけを plain text で返す。
`nbb live-expect-next-kind fill-live-env ...` は現在の next action kind が期待値と違う場合に
non-zero で止めるため、runbook/CI から段階ずれを検出できる。
`nbb live-plan ...` は同じ引数順で target safety、kotoba/model credential、local readiness、
core/all live evidence の step status、next command、現在不足している `:missing-env` を返す。
`xrpc-smoke-company` は `langchain.kotoba-db/kotoba-api` の XRPC request/response 形を
local interpreter で受け、実 kotoba-server なしで company import と mock React を通す。
`xrpc-smoke-business` は同じ XRPC 境界で business import と queue-aware React loop を通す。
raw inbox まで含む `all` は重いので、release gate ではまず `core` scope で検証する。
`xrpc-smoke-store` は既存 file-backed store を local XRPC interpreter に attach し、
実データ store 上の q/pull/transact と queue-aware React loop を検証する。
`clojure -M:kotoba probe <url>` は kotoba server の `/health` を確認する。

HermesAgent / OpenClaw は `cloud-itonami.runtime/real-model` で OpenAI-compatible tool-calling
endpoint として扱う。`ITO_MODEL_PROVIDER=openclaw|hermes|openai|anthropic`、
`ITO_MODEL_URL`、`ITO_MODEL`、`ITO_MODEL_API_KEY` で file-backed store 上の `real-react` を実行する。
`clojure -M:runtime preflight` は実行前に provider、endpoint、API key の不足を返す。
`real-react` は queue summary、activity plan、proposed effect の tool loop を resume まで進める。
外部副作用は proposed effect として残し、approval runner 側で承認・実行する。
`openclaw-smoke` / `hermes-smoke` は外部 credential なしで OpenAI-compatible endpoint stub を使い、
`real-model` と同じ HTTP/tool-calling 変換経路を検証する。
approval queue は `cloud-itonami.approval` CLI から `list` / `approve` / `reject` /
`execute` / `execute-dry-run` できる。

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
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/company.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/doctor.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/operating.cljc`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/agent.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/runtime.clj`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/approval.clj`
- `orgs/gftdcojp/cloud-itonami/test/cloud_itonami/*`

## Verification Notes

2026-06-27 の実装検証:

- `clojure -M:test`: 44 tests, 190 assertions, 0 failures, 0 errors
- `git annex get facts/procedure-terms.edn facts/hr-terms.edn facts/people.edn facts/ses-engineers.edn facts/ses-cases.edn`
  で社員・手続き・SES facts content を取得
- `clojure -M:ingest ../m365-archive/facts procedure-terms hr-terms people ses-engineers ses-cases`
  で `activities=11667, actors=5526, artifacts=219, effects=11667`
- `clojure -M:company coverage ../m365-archive/facts`
  で `procedure-terms` / `hr-terms` / `people` / `ses-engineers` / `ses-cases` がすべて
  `:available? true`
- `clojure -M:company import /tmp/cloud-itonami-company.edn ../m365-archive/facts`
  で local file-backed store に bulk import。unique upsert 後
  `activities=10938, actors=5511, artifacts=219, effects=10938, datoms=176717`
- `clojure -M:company business-manifest ../m365-archive/facts`
  で 24 supported kind を確認。現 checkout では operating set 5 kind は利用可能、
  annex 取得後の all-business は `:available-count 24`, `:missing-count 0`,
  `:fact-count 245367`
- `git annex get` で未取得だった 19 kind を取得し、`messages` / `teams-messages` を除く
  core business 22 kind を `/tmp/cloud-itonami-business-shards-core` に shard import。
  `facts=36646`, `shards=53`, `activities=33423`, `effects=33422`, `artifacts=4203`
- `messages` / `teams-messages` 2 kind は streaming reader で
  `/tmp/cloud-itonami-business-shards-raw` に shard import。
  `facts=208721`, `shards=210`, `activities=203114`, `effects=100162`, `artifacts=102952`
- `doctor` は core + raw の shard 合計 `263` 件、約 `360659404` bytes を検出し、
  `:business-shards/ready? true` と判定
- `/tmp/cloud-itonami-company.edn` に対して `clojure -M:runtime mock-react ... gftd-company-thread`、
  `clojure -M:approval list`、`approve`、`execute-dry-run` を実行し、
  `activities=10938, effects=10939, checkpoints=5, decisions=1, audits=2`
- `clojure -M:doctor company ../m365-archive/facts /tmp/cloud-itonami-company.edn`
  で `:ready/local-react? true`、`:ready/kotoba-react? false`、
  `:ready/missing [:kotoba-config :model-config]`。`:next-actions` で
  kotoba import、real OpenClaw/Hermes 実行、offline release gate の再現コマンドを返す
- fixture で `clojure -M:doctor operate-company ...` 相当を通し、company import、
  OpenAI-compatible OpenClaw smoke、kotoba XRPC smoke、doctor readiness が一つの結果にまとまることを確認
- fixture で `clojure -M:doctor operate-store ...` 相当を通し、OpenAI-compatible OpenClaw smoke、
  kotoba XRPC store smoke、approval dry-run、doctor readiness が一つの結果にまとまることを確認
- `clojure -M:doctor runbook-company ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-company`
  で bash runbook を出力し、offline release gate と実 credential 設定後のコマンドを確認
- `clojure -M:store import /tmp/cloud-itonami-procedure-hr.edn ../m365-archive/facts procedure-terms hr-terms`
  取得済み store で `activities=219, artifacts=219, actors=93, effects=219`
- `clojure -M:store counts /tmp/cloud-itonami-procedure-hr.edn`
  再オープン後も同じ counts を確認
- `clojure -M:runtime mock-react /tmp/cloud-itonami-procedure-hr.edn 手続きを進めて gftd-procedure-thread`
  で checkpoint 5 件と financial proposed effect 1 件を同じ store に追加
- fixture store で `clojure -M:runtime mock-react` -> `clojure -M:approval list` ->
  `approve` -> `execute-dry-run` を通し、`audits=2, decisions=1, checkpoints=5` を確認
- expanded fixture store で `procedure-terms + finance-terms + current-billing` を import し、
  `activities=3, artifacts=2, effects=4, checkpoints=5, decisions=1, audits=2` を確認
- `contract-att-terms` / `billing-att-terms` / `finance-terms` / `current-billing` /
  `invoice-amounts-recovered` / `financials-*` / `trial-balance` / `chart-of-accounts`
  の adapter と kind 登録を追加
- `clojure -M:ingest report ...` を追加し、取得済み facts は件数と tx summary、未取得 annex content は
  `Annex object is not available locally` と `git annex get facts/<name>.edn` の取得コマンドを返す
- `cloud-itonami.runtime-test` で OpenClaw/OpenAI-compatible model factory の request と Bearer auth を確認
- `clojure -M:kotoba` は `KOTOBA_URL is required` まで起動確認
- `clojure -M:kotoba preflight` は
  `{:ok? false, :missing [:KOTOBA_URL :KOTOBA_GRAPH]}` を返す
- `clojure -M:kotoba probe https://kotobase.net` は
  `{:ok? true, :status 200}` を返す
- `clojure -M:kotoba import-company ../m365-archive/facts 500` は現環境では
  `KOTOBA_URL is required` で停止する
- `clojure -M:kotoba xrpc-smoke-company ../m365-archive/facts kotoba-xrpc-company-thread`
  で `langchain.kotoba-db/kotoba-api` の XRPC 形を local interpreter に通し、
  company import と mock React を検証。`activities=10938, effects=10939, checkpoints=5` と
  proposed `:procedure.review` effect を確認
- `clojure -M:kotoba xrpc-real-smoke-company ../m365-archive/facts kotoba-xrpc-real-company-realdata 100000`
  で同じ XRPC 境界に OpenAI-compatible real-model adapter を接続し、実データ company operating set
  から `activities=10938`, `effects=10939`, `checkpoints=7` と proposed `:procedure.review` effect を確認
- `clojure -M:kotoba xrpc-real-operate-company ../m365-archive/facts kotoba-xrpc-real-operate-realdata 100000`
  で同じ XRPC + real-model 境界に approval dry-run を接続し、proposed effect 1 件を approve、
  dry-run handler で `:executed` に進め、remaining 0 件を確認
- core business 22 kind を `/tmp/cloud-itonami-business-core-real-operate.bin` に bulk snapshot 化し、
  `clojure -M:kotoba xrpc-real-operate-store /tmp/cloud-itonami-business-core-xrpc-real-operate.bin business-core-xrpc-real-operate-realdata`
  で XRPC + real-model + approval dry-run を通過。`activities=33384`、queue は
  `{:billing 4076, :contract 604, :employee 316, :inbox 6492, :procedure 118, :sales 21778}`、
  proposed effect 1 件を `:executed` に進め、remaining 0 件を確認
- fixture で `clojure -M:kotoba xrpc-smoke-business ...` 相当を通し、24 supported kind の
  business import、queue summary、queue-aware React loop、proposed `:procedure.review` effect を確認
- `cloud-itonami.kotoba-test` で schema install と facts import が injected kotoba `db-api`
  transaction に流れること、company operating set import も同じ境界へ流れることを確認
- `clojure -M:runtime preflight` は現環境で
  `{:provider :openai, :api-key? false, :missing [:ITO_MODEL_API_KEY]}` を返す
- `clojure -M:runtime openclaw-smoke /tmp/cloud-itonami-openclaw-smoke.edn ... openclaw-smoke-thread`
  で実データ company store 上の OpenAI-compatible `real-model` tool-calling 経路を通し、
  `activities=10938, effects=10939, checkpoints=5` と proposed `:procedure.review` effect を確認
- `cloud-itonami.runtime-test/real-react-file-store-resumes-through-tool-proposal` で
  `real-react` 本体が OpenAI-compatible model から queue summary、plan、effect proposal まで
  resume することを確認
- 2026-06-28 の queue-aware smoke では `/tmp/cloud-itonami-company.edn` のコピー上で
  queue summary `{:procedure 118, :employee 316, :sales 10504}` を読み、
  先頭 procedure activity
  `m365:procedure:MD5E-s10491--63b0bbc45e9b216160ef762629019221.docx` に
  proposed `:procedure.review` effect を追加できることを確認
- `clojure -M:kotoba xrpc-smoke-store /tmp/cloud-itonami-company-xrpc-store-smoke.edn xrpc-store-realdata`
  で実データ store を local XRPC interpreter に attach し、kotoba XRPC wire 形の
  q/pull/transact 経由で queue-aware React loop を通過。
  `activities=10938`, `effects=10940`, `checkpoints=12` と proposed `:procedure.review` effect を確認
- `clojure -M:doctor operate-store ../m365-archive/facts /tmp/cloud-itonami-company-doctor-operate-store-approval.edn doctor-operate-store-approval-realdata`
  で既存 store に対する OpenClaw-compatible smoke、XRPC store smoke、approval dry-run、
  doctor readiness を一括確認。proposed effect 2 件を approve し、dry-run handler で 2 件を
  `:executed` に進め、remaining は 0 件。`:ready/local-react? true`、missing は
  `[:kotoba-config :model-config]`
- `clojure -M:doctor completion-audit ../m365-archive/facts /tmp/cloud-itonami-company.edn /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  で objective の requirement を監査。`company-operating-facts` / `all-business-facts` /
  `local-company-store` / `business-shards` / `local-react-gates` / `local-all-business-gate`
  は `local-react-gate` と `business-local-gate` の store/shard 再監査込みで `:proven`。
  未証明は `:kotoba-config` / `:model-config` / `:live-kotoba-react-loop` /
  `:live-all-business-react-loop`
- `clojure -M:doctor business-local-gate ../m365-archive/facts /tmp/cloud-itonami-business-shards-core /tmp/cloud-itonami-business-shards-raw /tmp/cloud-itonami-business-core-xrpc-real-operate.bin`
  で all business 24 kind、core 22 kind coverage、raw 2 kind coverage、263 shard、
  business-core XRPC real operate store の checkpoint/effect/approval drain を確認。
  `:ok? true`、core operate store は `activities=33384`, `effects=33384`,
  `checkpoints=7`, `executed=1`, `pending-approvals=0`
- `clojure -M:doctor completion-gate ../m365-archive/facts /tmp/cloud-itonami-company.edn /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  は同じ監査を行い、`:complete? false` なら non-zero exit で止める。`runbook-live` の最後はこの gate を使う。
- `clojure -M:doctor live-env-template` は live credential に必要な env、model key alias、認証方式、core/all gate コマンドを返す。
  同じ出力の `:current :missing-env` は現在不足している required env、auth alternative、model API key alternative を返す。
- `clojure -M:doctor live-env-evidence ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn /tmp/cloud-itonami-live-env-evidence.edn`
  は live env の存在状態と次 advance を redacted evidence として保存する。
- `clojure -M:doctor live-env-evidence-report ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn /tmp/cloud-itonami-live-env-evidence.edn`
  は redacted env evidence の target と redaction を検査する。
- `clojure -M:doctor live-env-evidence-gate ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn /tmp/cloud-itonami-live-env-evidence.edn`
  は同じ検査を non-zero gate として実行する。
- `clojure -M:doctor live-ready-loop ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn /tmp/cloud-itonami-live-env-evidence.edn 5`
  は redacted env evidence を更新・検査してから、ready な live advance を blocking まで進める。
- `clojure -M:doctor live-ready-loop-evidence ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn /tmp/cloud-itonami-live-env-evidence.edn /tmp/cloud-itonami-live-ready-loop.edn 5`
  は ready loop の結果を redacted evidence として保存する。
- `clojure -M:doctor live-env-shell` は `:missing-env` から shell の `export` skeleton と
  `nbb live-gate` / `nbb live-runbook` コマンドを返す。
- `clojure -M:doctor live-env-shell-script` は同じ skeleton を plain text として返し、未入力 credential を shell で fail-fast する。
- `clojure -M:doctor live-preflight` は kotoba graph 設定、kotoba health、Hermes/OpenClaw model
  credential をまとめて確認し、live gate が実行可能かを返す。実 write を前提にするため、
  anonymous kotoba は `:kotoba-config` 未完了として扱い、`KOTOBA_TOKEN` または
  `KOTOBA_CACAO` + `KOTOBA_DID` を要求する。
- `clojure -M:doctor live-preflight-gate` は同じ検査を non-zero gate として実行する。
- `clojure -M:doctor live-status ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  は full completion audit を走らせず、target、credential、既存 evidence の現在地だけを返す。
- `clojure -M:doctor live-next ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  は `live-status` が示す次の command だけを plain text で返す。
- `clojure -M:doctor live-next-action ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  は `live-status` が示す次の action map を EDN で返す。
- `clojure -M:doctor live-advance-plan ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  は `live-status` が示す次 action を、実行前 gate、実行 command、検証 command、次の inspect command 付きで返す。
- `clojure -M:doctor live-advance-once ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  は `live-advance-plan` が ready な場合だけ一段進め、blocking な場合は停止する。
- `clojure -M:doctor live-advance-until-blocked ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn 5`
  は ready な live advance を繰り返し、blocking 状態または max step 到達で止める。
- `clojure -M:doctor live-next-kind ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  は `live-status` が示す次の action kind だけを plain text で返す。
- `clojure -M:doctor live-expect-next-kind fill-live-env ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  は `live-status` が示す next action kind が期待値と一致することを gate として検証する。
- `clojure -M:doctor live-plan ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  は live 実行前の plan として、target safety、credential、local readiness、core/all live evidence、
  completion gate の状態と次の command を返す。
- `clojure -M:doctor operate-live ../m365-archive/facts /tmp/cloud-itonami-live-operate.edn cloud-itonami-live-operate-core`
  を live credential 設定後の完了 gate とする。これは実 kotoba graph へ company operating set と
  business core を import し、実 Hermes/OpenClaw model の React loop と approval dry-run を実行して
  evidence EDN を保存する。`completion-audit` はこの evidence が存在し、company/business の activity と
  company operating kind import、business core import、React thread/checkpoint、
  proposed/approved/executed effect、approval drain を確認できるまで
  `:live-kotoba-react-loop` を未証明にする。`operate-live` は
  kotoba/model preflight が通らない限り graph 接続や import に進まず、credential 不足時の部分 write を拒否する。
  `completion-audit` から検査する live evidence は local coverage の company operating fact count 以上を
  import していることも要求し、kind ごとに 1 件だけ入った部分証跡を拒否する。
  live evidence ファイルが存在するときは business manifest の full count も読み、core/all scope の
  live business import が local manifest の aggregate fact count 以上であること、さらに import result の
  kind 別 `:facts` が manifest の kind 別 count 以上であることを要求する。
  `operate-live` が保存する evidence には実行時点の expected snapshot も含め、
  `live-evidence` 単体でも company/business の count 不足を検査できるようにする。不足は
  `:company :import :deficits` と `:business :import :deficits` に kind ごとの
  `:expected` / `:actual` / `:missing` として返す。あわせて `:next-actions` に
  `operate-live` 再実行、`import-company`、scope 別 business import の再実行コマンドを返す。
  同じ evidence の `:operation` には facts-dir、evidence path、company/business thread、batch/chunk size、
  scope、再現用 command を保存し、credential 投入後の実行設定を後から監査できるようにする。
  evidence には `:evidence-format :cloud-itonami.doctor/operate-live-v1` を保存し、
  `completion-audit` は facts-dir、kotoba graph、top-level thread-id と company/business runtime
  thread の整合も確認する。手製 EDN、別 graph、別 thread の証跡を core/all 完了証跡として
  流用しない。
  `operate-live` は既存 evidence file が別 `facts-dir` / `thread-id` / scope を指す場合、
  live preflight 前に上書きを拒否する。同一 target の deficits 対応 rerun は許可する。
  live 実行の途中失敗時は `:status :failed`、`:failed-phase`、`:error` と完了済み phase の
  partial evidence を保存する。`completion-audit` は `:status :completed` でない evidence を
  完了扱いにしない。`live-evidence` は `:recover-live-failure`、再実行、不足 import の
  `:next-actions` を返す。
  `resume-live` は failed evidence を読み、保存済み company phase があれば再利用して
  business phase から再開し、成功時に同じ evidence を `:status :completed` へ更新する。
  resume は evidence に保存された business scope が `core` / `raw` / `all` の場合だけ進み、
  unsupported scope は live preflight 前に拒否する。
  raw inbox まで含む全 business scope を live graph に載せる場合は
  `clojure -M:doctor operate-live ../m365-archive/facts /tmp/cloud-itonami-live-operate-all.edn cloud-itonami-live-operate-all all 1000`
  を使う。scope は `core` / `raw` / `all`。`completion-audit` は第4引数の all-scope evidence で
  `:live-all-business-react-loop` を監査する。この監査は evidence 内の `:business :scope` が `:all`
  であることも確認し、core evidence の流用を拒否する。
  core/all evidence path が同じ場合も証跡上書きの危険があるため完了扱いにしない。
- `clojure -M:doctor live-evidence /tmp/cloud-itonami-live-operate.edn` は保存済み live evidence を単独検査し、
  real server、kotoba/model preflight、company operating kind import、business core import、
  company/business activity、React thread/checkpoint、
  proposed/approved/executed effect、approval drain の判定を返す。
- `clojure -M:doctor runbook-live ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  は credential 設定後に core/all の live evidence と completion audit を一気通貫で実行する bash を出力する。
- `nbb live-gate ../m365-archive/facts /tmp/cloud-itonami-company.edn cloud-itonami-live-operate /tmp/cloud-itonami-live-operate.edn /tmp/cloud-itonami-live-operate-all.edn`
  はその直前に evidence target、kotoba/model credential、production readiness を fail-fast で確認する。

SES / people を含む会社 operating subset は local `langchain.db` file store に bulk snapshot として永続化し、
mock React loop と approval dry-run まで検証済み。この checkout では `KOTOBA_URL` / `KOTOBA_GRAPH`
が未設定のため、kotoba-backed store の実接続検証は接続設定後に行う。finance/billing 拡張 facts は一部 annex content 未取得のため、
取得後に実データ件数を再集計する。

## Closed Decision

`kyber-plm` は名称・操作面・standalone source tree としては閉じ、`cloud-itonami` の compat/history 語彙にする。
利用者・agent・UI は `cloud-itonami.ops` / `cloud-itonami.plm` を呼び、結果は itonami store の
activity/effect/audit に残す。kyber namespace は互換・内部実装としてのみ残す。
`nbb kyber-refactor-audit /tmp/cloud-itonami-kyber-refactor-audit.edn` は sample PLM/ERP/MES graph を
itonami artifact/relation/activity/effect/audit へ射影し、この決定を redacted gate として再検証する。

## Addendum (2026-07-12): プラットフォームとしての位置づけの訂正

本 ADR 本文（2026-06-27 時点）は cloud-itonami を「`manimani` の企業版として
**gftdcojp 単体**の business activity（M365/kyber-plm/keiei-sim/litigation）を
統合する業務 OS」として記述している。この記述は起票当時の decision としては
正しいが、**cloud-itonami 全体のスコープを代表するものではない**ため、以後の
会話・要約でこの ADR だけを根拠に cloud-itonami を「gftdcojp 内部業務基盤」や
「特定ゲーム(network-isekai)配下のブループリント層」と要約するのは誤り。

現時点(2026-07-12)での正しい位置づけ:

- **cloud-itonami は全世界・全産業を対象にした AI agent / robotics 化 SaaS
  platform である。** ISIC Rev.5 の全 21 section を coverage 済み(ADR-2607011000)。
  section ごとに `cloud-itonami-isic-*` vertical actor(繊維・金属・通信・宿泊・
  人材紹介・廃棄物・医薬品小売など 30 件超、`90-docs/adr/2607112*` 〜
  `2607121*` 一連)が発行されており、gftdcojp の内部業務はその中の1事例
  （自社テナントとしての self-hosting）に過ぎない。
- **サプライチェーンをコード化し、需要側・供給側いずれかに登録した組織/個人
  だけが情報を共有できる。** 登録は `itonami.cloud` の `{org}/{repo}` テナント
  への自己登録(CACAO/did:key 自己mint、中央承認・共有 token 不要)であり、
  ISIC/ISCO タグ付きで `/api/open-business` に動的掲載される
  (ADR-2607051621 Layer 1)。テナント間の scoped queue/effect/audit read は
  org/repo 単位で分離される(`docs/adr/0002-org-repo-tenant-isolation.md`)。
- **世の中の SaaS を OSS・分散型で再設計・実装したもの。** Layer 0(AGPLv3
  自己ホスト、義務は改変ソース公開のみ)/ Layer 1(ネットワーク登録)/
  Layer 2(オンチェーン protocol fee、`kotoba-lang/treasury` 経由)/
  Layer 3(デュアルライセンス)の4層モデルで、無償の自己ホストと有償参加を
  両立する(ADR-2607051621)。
- **物理領域作業は robot が行い、actor は action を提案し、独立 governor が
  gate する** robotics 前提が全 vertical の必須 capability(ADR-2607011000)。
  これは既存 actor 3 例(robotaxi-actor / gftd-talent-actor / cloud-itonami
  自身の ops-LLM⊣CertGovernor)と同型の「知能ノード封じ込め + 独立 governor
  + 不変台帳」パターンの物理作動版。

本文の「gftdcojp business activity 統合」という decision 自体は撤回しない
（実装済みで有効）。訂正するのは要約の粒度であり、本 addendum をもって
cloud-itonami の位置づけに関する記載の齟齬は closing する。
