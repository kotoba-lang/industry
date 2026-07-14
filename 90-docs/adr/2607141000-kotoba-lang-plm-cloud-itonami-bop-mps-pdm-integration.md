---
id: adr-2607141000-kotoba-lang-plm-cloud-itonami-bop-mps-pdm-integration
title: "ADR-2607141000: kotoba-lang/plm に BOP(routing)・MPS・PDM(版管理/release承認) を追加し、cloud-itonami governance facade と統合する — PTC(Windchill/Creo)等の外部コネクタは追加しない"
status: accepted
doc_type: adr
topic: kotoba-lang-plm-cloud-itonami-integration
authoritative: true
last_verified: 2026-07-14
authoritative_for:
  - kotoba.plm への BOP(routing master data)/MPS(time-phased demand)/PDM(doc・版管理)追加の設計決定
  - PTC(Windchill/Creo等)は外部コネクタとして追加せず、PDMに統合する自前実装のみとする判断
  - release承認ワークフローはcloud-itonami側のgovernance facadeに置き、kotoba-lang/plmはgovernance-freeのまま維持するorg taxonomy（ADR-2607106100）の踏襲
  - 既存 thread/release-item! ・ ops/release-item! は無変更のまま維持し、承認ゲート付きフローは別関数として追加するという後方互換方針
related:
  - 90-docs/adr/2607020100-kotoba-lang-dcs-plm-registration.md
  - 90-docs/adr/2607083500-cloud-itonami-vehicle-design-link.md
  - 90-docs/adr/2607106100-product-party-join-cloud-itonami-kotoba-lang.md
  - orgs/kotoba-lang/plm/src/kotoba/plm/schema.cljc
  - orgs/gftdcojp/cloud-itonami/src/cloud_itonami/mes.cljc
supersedes: []
superseded_by: []
---

# ADR-2607141000: kotoba-lang/plm BOP/MPS/PDM 統合

- Status: accepted (2026-07-14)
- Deciders: Jun Kawasaki

## Decision

`kotoba.plm` に3つのドメインを追加する:

- **BOP（Bill of Process / routing）** = work center + operation という
  routing **master data**（標準時間・段取時間・work center費率）と、それを
  `kotoba.plm.cost/rolled-cost` に取り込む追加吸収費用の roll-up。
  work-order/operation-sequenceの**実行トラッキング**（`cloud-itonami.mes`が
  「shop floor modelが育つまでprops-edn留め」と明記した対象）はスコープ外の
  まま——BOPはmaster data/原価計算の話、MESのprops-edn留保は実行トラッキングの
  話で別軸であり、本ADRはMES側の判断を変更しない。
- **MPS（Master Production Schedule）** = item×期間バケットの time-phased
  top-level demand。既存 `kotoba.plm.mrp` の明示的demand引数API
  （`plan`/`mrp-run!`）はそのまま残し、MPSは「demandの出どころ」を追加する
  だけの上乗せ（`plan-from-mps`/`mrp-run-from-mps!`が内部で既存関数へ委譲）。
- **PDM（Product Data Management）** = ①CAD/spec/drawing等のドキュメント/版
  添付（pure domain data、`plm.item/lifecycle`の既存state machineに相乗り）と
  ②release承認ワークフロー。②は**cloud-itonami側**のgovernance facade
  （activity→decision→effect→audit）に実装し、kotoba-lang/plm自体は
  governance-freeのpure domain engineのまま維持する
  （ADR-2607106100のorg taxonomy: kotoba-lang=libs / cloud-itonami=apps
  を踏襲）。

**PTC（PTC社のWindchill/Creo等）への統合は行わない。** PDMは外部ベンダー
SaaSへの接続コネクタとしてではなく、上記②の自前governance実装に統合する。
CAD側も既存の `kami-engine`/STEP系（ADR-2607083400/2607083500の非依存方針）
と接続しない。

既存 `kotoba.plm.thread/release-item!` および `cloud-itonami.ops/release-item!`
は**無変更**で維持する（無条件releaseのAPIとして残る）。承認ゲート付きの
新フロー（`request-item-release!`/`approve-item-release!`）は別関数として
追加するのみで、既存呼び出し元・既存テストへの影響はゼロにする。

## Context

前段の調査で、`kotoba.plm`（`orgs/kotoba-lang/plm`）にはPLM/BOM(E/M)/MRP/
ERP(部分)が実装済みだが、BOP・MPS・PDM・PTCへの言及はコード・ADRいずれにも
存在しないことを確認した。`cloud-itonami.mes`（`orgs/gftdcojp/cloud-itonami`）
のdocstringには "deliberately keeps work-order/routing details in props-edn
until the shop floor model is rich enough to justify first-class attributes"
という明示的な先送り判断があるが、これは routing の**実行**面（MES work-order）
についての判断であり、routing の**master data**（BOP: どの item がどの
work center でどれだけの標準時間を要するか）はそもそも存在しなかった——
両者は別軸であり、本ADRは前者を変更せず後者のギャップのみを埋める。

`kotoba.plm.schema`（EAVT, Datomic Local互換）は `plm.*`(PLM core) /
`erp.*`(Kyber ERP) / `ocel.*`(監査) の3層グラフとして設計されており、
`plm.item/*`・`plm.bom/*`・`plm.eco/*` の命名規則・enum記法（`:db.type/keyword`
+ docstring列挙）・`:db/unique :db.unique/identity` の使い方は BOP/MPS/PDM
の属性にもそのまま適用できる、拡張に開かれた設計だった。

`cloud-itonami` 側は `cloud-itonami.ops` が `kotoba.plm.{db,erp,mrp,production,
thread}` に実際に依存し（Datomic Local, `:datomic`/`:test` alias）、
`release-item!`/`receive-goods!`/`release-eco!`/`run-mrp!` を
`{:domain <kyber結果> :itonami (sync-domain! ...)}` という統一形で
ラップしている——BOP/MPS/PDMの新規操作もこの既存パターンにそのまま
乗せられる。一方 `cloud-itonami.mes`/`cloud-itonami.vehicle-design-link` は
`cloud-itonami.activity` のみに依存する軽量な governance facade
（activity→decision/effect→audit を1本のtxにまとめる）という別パターンで、
PDMのrelease承認・BOPのrouting変更承認・MPSの確定承認はこちらの型に
自然に収まる。

`cloud-itonami.activity` の `policies` map と、それを integer-coded
kotoba-wasm kernel（`cloud_itonami/kernels/policy_risk.{kotoba,cljc}`）に
橋渡しする `keyword-policy->code` を実読して確認したところ、risk tier
（0=read-only/1=external-send/2=financial/3=destructive）は kotoba-wasm側
（`.kotoba`, `.wasm`）で既に完結しており、`keyword-policy->code` は
**host側(`.cljc`)のプレーンな`case`**でキーワードを既存のrisk codeへ
振り分けているだけ——新規policyの追加は既存familyの`case`節に
キーワードを足すだけで済み、`.kotoba`/`.wasm` の再コンパイルは不要と
確認した。

## Consequences

- `kotoba.plm.cost/rolled-cost` に4-arity版
  `(rolled-cost d iid asof {:include-process? true})` を追加するが、
  既存3-arity呼び出しは完全に無変更（process cost加算は明示オプトインのみ）
  ——既存 `rolled-cost-walks-mbom`（500M）テストは無変更で通過する。
- `kotoba.plm.production/complete-production!` は、parentにoperationが
  未定義の既存テストitemに対しては仕訳が完全に無変更（既存の
  WIPクローズ検証は無変更で通過する）。operationが定義された場合のみ
  「5100 Labor & Overhead Absorbed」科目への吸収仕訳が追加される。
- `cloud-itonami.ops/release-item!` 等の既存ラッパーは無変更。新規の
  承認ゲート付きフローは追加関数（`request-item-release!`/
  `approve-item-release!`等）としてのみ提供する。
- `cloud-itonami` の `deps.edn` に新規外部依存は追加しない
  （PTCコネクタを追加しない、というDecisionの直接の帰結）。
- `test/cloud_itonami/test_runner.cljc` は新規test namespaceの自動発見を
  しないため、新規3ファイルは `:require` と `test-names` の両方に
  明示登録する（ADR-2607083500が既に記録した既知の落とし穴）。

## Addendum（2026-07-14）— 実装PRとcloud-itonami側の既存CI break

実装を2本のPRに分けて起票した（いずれも未マージ、レビュー待ち）:

- `kotoba-lang/plm#1`（branch `feat/bop-mps-pdm`）: schema.cljc の
  `plm.wc/*`/`plm.op/*`/`plm.mps/*`/`plm.doc/*` 追加、`routing.cljc`/
  `mps.cljc`/`pdm.cljc` 新設、`cost.cljc`/`production.cljc`/`thread.cljc`/
  `mrp.cljc`/`erp.cljc` への後方互換な追加統合。`clojure -M:test`:
  20 tests / 73 assertions、regressionなし。
- `gftdcojp/cloud-itonami#399`（branch `feat/bop-mps-pdm-governance`）:
  `plm.cljc` re-export追加、`activity.cljc`/`kernels/policy_risk.cljc`
  への3 policy追加、`bop.cljc`/`mps.cljc`/`pdm.cljc` 新設、`ops.cljc`への
  追加ラッパー。新規テスト+関連既存テスト（ops/mes/plm/activity/
  policy-risk/vehicle-design-link）: 37 tests / 330 assertions、
  regressionなし。

**cloud-itonami側で、本ADRと無関係な既存CI breakを発見した**:
`pre-push` lefthook gate（`cloud-itonami.portable-cljs-test-runner`、
ClojureScript経由）が `main`（commit `b8a6def8`、直前の
「design-quality 100 on all pages」refresh）自体で既に失敗する
（`kotoba-ui.core/{grid,stack,app-shell,appearance-attr,theme-css}` が
`site/public_cockpit.cljc`・`site/local_shell.cljc`・`site/isco_1212.cljc`・
`site/marketplace.cljc` から未定義varとして参照される——pinned
`kotoba-ui` checkoutとの互換性が崩れたと見られる）。素のworktreeで
同じgateを実行し、本ADRの変更が一切無い状態でも同一の失敗を確認した。
本ADRのPRはこの既存break単体をバイパスするため `--no-verify`
（owner承認済み）でpushした——本ADRが持ち込んだ／修正した問題ではない。
別途調査・修正が必要（本ADRのスコープ外）。

## 却下案

- **PTC(Windchill/Creo)等への外部コネクタ追加**: 特定ベンダー製品への
  依存を持ち込むことになり、このモノレポの「自前実装・外部SaaS非依存」の
  一貫した設計哲学（ADR-2607083500の non-goals 等）に反するため不採用。
  PDMは自前実装に統合する。
- **release承認ワークフローをkotoba-lang/plm側（例: `thread/release-item!`
  自体にapproval precondition を追加）に実装**: ADR-2607106100の
  org taxonomy（kotoba-lang=governance-free pure libs、cloud-itonami=
  governance-only apps）を破ることになるため不採用。承認ロジックは
  cloud-itonami側のgovernance facadeに置く。
- **既存`thread/release-item!`/`ops/release-item!`自体を承認必須に変更**:
  既存の全呼び出し元（`demo.cljc`、既存テスト群）を壊すため不採用。
  無条件releaseのAPIとして維持し、承認ゲート付きフローは別関数として
  追加するに留める。
- **MES work-order execution trackingのfirst-class化**（`cloud-itonami.mes`
  のprops-edn留保を覆す）: 本ADRのスコープはBOP master data/原価であり、
  shop floor execution modelの成熟を待つという既存判断はそのまま維持する
  （混同を避けるため明記）。
