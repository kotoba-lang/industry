# ADR-2607022300: gftdcojp/gftdcojp を itonami.cloud の private tenant として登録し、itonami.cloud の repo storage を GitHub 依存から kotoba-git/kotoba-rad 主権層へ段階移行する

**Status**: accepted (Decision 1 implemented; Decision 2 remains future work per its own staged roadmap)
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

`orgs/gftdcojp/cloud-itonami`（ADR-2606271700 の gftdcojp business-os）は既に
`.cljc` 正本（`company.cljc` / `m365.cljc` / `kotoba.cljc` / `tenant.cljc` /
`store.cljc`）と `itonami/org/{org}/repo/{repo}` の multi-tenant データモデル
（ADR-0002 org-repo-tenant-isolation、cloud-itonami repo内 `docs/adr/`）を持ち、
`orgs/gftdcojp/m365-archive` の facts（mail/calendar/contract/invoice/CRM/HR 等）も
既に materialize 済みである。

しかし `gftdcojp`(org) / `gftdcojp`(repo) の組み合わせ（= gftd.co.jp 自身の事業体を表す
tenant、想定 route `itonami.cloud/gftdcojp/gftdcojp`）はまだ bootstrap されていない。
また調査の結果、次の2点が判明した。

1. ADR-0002 / README が説明する Pages Functions API
   （`functions/api/[[path]].js`、`ITONAMI_OPERATOR_TOKEN` bearer token による
   write 認可・read は sanitized state を認証なしで公開）は、
   `638bdc3 remove legacy JS runtime artifacts`（2026-07-01）で**削除済み**。
   現在の `itonami.cloud` は `public/index.html` 等の静的サイトのみで、
   ADR-0002 が記述するライブ API は存在しない。ADR-0002 自体は書き換えないが、
   本 ADR をもって「その API 層は現存しない」という addendum 的事実を記録する。
2. `company.cljc` の `import-business!` 系は m365-archive facts を
   ローカル file-backed store（`.bin`、DataScript 互換）に入れるところまでで、
   本番 kotoba store（`kotoba.cljc` の conn）や repo タグ（`:itonami.activity/repo`。
   `activity.cljc`/`store.cljc` は既に対応済み）への配線はまだ無い。

一方、gftd.co.jp の実データ（契約・請求・人事等、機密性の高い business facts）を
「認証なしで誰でも読める public cockpit」に載せることはできないため、gftdcojp/gftdcojp
は最初から private tenant として設計する必要がある。

さらにオーナーから、より大きな方向性として次の指示があった: **itonami.cloud 自体が
git storage 機能を持ち、kotoba-lang/git（= `kotoba-git`/`kotoba-rad`、
ADR-2606280300）を使って public/private の repo を持てるようにする。GitHub は
その public 版だけを公開する mirror 先に過ぎない。**

`kotoba-git`/`kotoba-rad` は ADR-2606280300 で既に成熟度ロードマップが定義されている:

| Stage | Deliverable | Status |
|---|---|---|
| R0 | byte-exact Git object bridge（objects/refs/pack import） | implemented (`kotoba-git`) |
| R1 | signed repo identity（RID/identity journal/delegate/ref validation） | next |
| R2 | private object store（encrypted Git object blocks、recipient grants、epoch rotation） | next after R1 |
| R3 | P2P accountability（source-chain publication、warrants） | partial primitives |
| R4 | PQ-ready suite | future |

private repo の秘匿境界は「誰に配るか」ではなく **object encryption**
（replication key = ciphertext-cid、access = capability datom + recipient set +
epoch key、revocation = epoch rotation）である、という設計方針も ADR-2606280300 で
既に固定済み。

## Decision

### 1. gftdcojp/gftdcojp tenant registration（cloud-itonami repo内、即実装可）

- `cloud-itonami.tenant/bootstrap-tx` を用いて `{:org "gftdcojp" :repo "gftdcojp"}`
  の org/repo/actor/member/permission tx を作り、`cloud-itonami.kotoba` の conn
  経由で本番 kotoba store へ transact する。
- `:itonami.repo/*` に `:itonami.repo/visibility`（`#{:public :private}`、
  既定 `:private`）を追加する。gftdcojp/gftdcojp は明示的に `:private` とする。
- 認証は `ITONAMI_OPERATOR_TOKEN` 共有 token 方式を採用せず、
  `ai-gftd-itonami/src/itonami/cacao.clj` に既にある **CACAO/did:key 自己発行モデル**
  （actor が自分の鍵を持ち、鍵由来 IPNS 名がその actor の graph の authority）に揃える。
  gftdcojp 自身も1 actor として鍵を持ち、`itonami.cloud/gftdcojp/gftdcojp` への
  read/write は CACAO セッションを必須にする（public sanitized read パスを設けない）。
- `company.cljc`/`m365.cljc` の ingestion 経路に `:repo [:itonami.repo/id
  "gftdcojp/gftdcojp"]` を通し、`orgs/gftdcojp/m365-archive/facts/*` の実データを
  kotoba へ流し込む。

### 2. itonami.cloud の repo storage を kotoba-git/kotoba-rad 主権層へ段階移行

- 短期: 上記1は既存の kotoba/datom store（business activity/effect/audit の
  データモデル）を使う。これは「業務データ」であって「git repo そのもの」ではない
  ため、ADR-2606280300 の R1/R2 完成を待たずに着手できる。
- 中期: `kotoba-git`（R0、実装済み）を itonami.cloud の repo checkout 取得経路に
  使い始める（GitHub からの pull を kotoba-git 経由の object 取得に置き換える
  実験）。
- 長期: `kotoba-rad` R1（`RepoIdentity`/`RepoRid`/`RepoEvent`/`Delegate`/
  `RefPolicy`/`RecipientGrant`/`RadRepo::apply_event`/
  `RadRepo::authorize_ref_update`、ADR-2606280300 が既に型を指定済み）と R2
  （object encryption による private repo）を実装し、itonami.cloud の repo storage
  backend を GitHub 依存から kotoba-rad 主権層へ切り替える。GitHub は
  `:itonami.repo/visibility :public` の repo だけを対象にした **one-way publish
  mirror** に格下げする（source of truth ではなくなる）。

## Consequences

- (+) gftdcojp/gftdcojp の実 business data は最初から private tenant + CACAO 認証で
  設計され、旧 shared-token 方式や「public sanitized read」を経由しない。
- (+) ADR-2606280300 が既に定義した kotoba-rad ロードマップと接続され、
  「itonami.cloud の git storage 主権化」は新規発明ではなく既存決定の適用先が
  1つ増える形になる。
- (−) R1/R2 が未実装なため、「repo そのもの（git objects）を kotoba-rad で主権保持する」
  部分は本 ADR の時点ではまだ実現しない。当面は業務データ（activity/effect/audit）
  のみが private kotoba tenant に載り、git repo 本体は引き続き GitHub
  （`git@github.com:gftdcojp/cloud-itonami.git`）が実体を持つ。
- (−) ADR-0002 の Pages Functions API は現存しないため、gftdcojp/gftdcojp を
  実際に serve するには CACAO 認証込みの API 層を新規実装する必要がある
  （旧 JS 実装の単純復元ではない）。
- 既存データの破壊的移行はしない。m365-archive の生データ・ローカル `.bin` 検証
  ストアはそのまま残し、kotoba 本番 store への transact は追加のみ。

## Implementation status (2026-07-02)

Decision 1 is code-complete and tested, not yet run against production:

- Tenant registration (`cloud-itonami.tenant/bootstrap-tx` +
  `:itonami.repo/visibility` + `cloud-itonami.tenants.gftdcojp`) and CACAO
  auth (`cloud-itonami.auth`, `io.github.kotoba-lang/cacao`) landed via the
  `cacao-auth` merge (`a309147`).
- The remaining gap this ADR's `:known-gaps` flagged — "company.cljc
  ingestion does not yet tag activities with `:itonami.activity/repo`" — is
  closed: `cloud-itonami.facts/tag-repo` + `:repo` opt threaded through
  `facts/ingest-dir` / `kotoba/import-facts!` /
  `kotoba/import-kinds-streaming!` (`gftdcojp/cloud-itonami#9`).
  `cloud-itonami.tenants.gftdcojp/import-m365-facts!` and a
  `clojure -M:gftdcojp seed|import-m365` CLI wrap this for the gftdcojp
  tenant specifically.
- ADR-0002 addendum (Pages Functions API removed 2026-07-01) added, same PR.
- **Not yet done**: `seed!`/`import-m365-facts!` have not actually been run
  against a live `KOTOBA_URL`/`KOTOBA_GRAPH` — this ADR's tenant exists in
  code and tests (local `store/create-conn`), not yet as live production
  data. Running that needs production kotoba credentials this session did
  not have.

Decision 2 (kotoba-git/kotoba-rad sovereign storage) is untouched — still
entirely future work per its own R0→R4 staging (ADR-2606280300).

## Follow-up

- `orgs/gftdcojp/cloud-itonami/docs/adr/0002-org-repo-tenant-isolation.md` に
  「Pages Functions API 実体は 2026-07-01 に削除済み」の addendum を追記する
  （本 ADR からの参照のみで足りなければ）。 — done, see above.
- Run `clojure -M:gftdcojp seed` + `import-m365` against production
  `KOTOBA_URL`/`KOTOBA_GRAPH` once credentials are available.
- `kotoba-rad` R1 実装（`orgs/com-junkawasaki/kotoba/crates/kotoba-git` または
  新設 `kotoba-rad` crate）は別 ADR/作業として着手する。
- CACAO 認証込みの itonami.cloud API 層（Worker/Pages Functions）の設計・実装。

## References

- ADR-2606271700（cloud-itonami business-os）
- ADR-2606280300（kotoba-rad / kotoba-git 主権 repository layer）
- `orgs/gftdcojp/cloud-itonami/docs/adr/0001-cloudflare-pages-operator-cockpit.md`
- `orgs/gftdcojp/cloud-itonami/docs/adr/0002-org-repo-tenant-isolation.md`
- `orgs/gftdcojp/ai-gftd-itonami/src/itonami/cacao.clj`
