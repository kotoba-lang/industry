---
id: adr-2607113500-kotobase-net-ingestion-cloud-itonami-lei
title: "ADR-2607113500: kotobase.net ingestion fold job for cloud-itonami-lei-* — closes ADR-2607072300's open trigger-mechanism gap for this actor family"
status: accepted
doc_type: adr
topic: kotobase-net-ingestion-cloud-itonami-lei
authoritative: true
last_verified: 2026-07-11
implemented: 2026-07-11
implementation:
  script: scripts/kotobase-ingest-cloud-itonami-lei.cljs
  identity_did: "did:key:z6Mkh6mnYrfXNjDSoRQfRK9a2QanvaSAVAqDFK44Vpss5ZSA"
  graph_cid: "bafyreifq6p445b472n6qbutttfaa3fegq5bbjnnxcmrogi5y6ksw46qwty"
  db_name: "cloud-itonami-lei-catalog"
  endpoint: "https://backend.kotobase.net"
authoritative_for:
  - "For the cloud-itonami-lei-* actor family (ADR-2607110300), ADR-2607072300's open 'journal -> kotobase-peer fold job' item is closed by a pull-based batch fold job (`scripts/kotobase-ingest-cloud-itonami-lei.cljs`, real nbb+cljs, reusing `kotoba-lang/kotobase-client` verbatim), not a push-based webhook or a west-update hook"
  - "kotobase.net's real production XRPC tx_edn wire format is a vector of ENTITY MAPS `[{:db/id \"e\" :ns/attr v ...} ...]`, not `[:db/add e a v]` triples -- the latter is kotobase-peer's own in-process API shape (its README), not what kotobase-server's `handler.cljc/tx-edn->quads` accepts over the wire; this was empirically wrong on the first real attempt (`400 {\"message\":\"kotobase: unrecognized tx_edn item\"}`) before being corrected against the real server error message, not assumed from ADR prose"
  - "Idempotent re-ingestion is achieved by keyed cardinality-one upsert (same :db/id + same attrs re-asserted), empirically verified (re-transacting one company's data left its :eavt datom count unchanged, not doubled) -- not by any dedup logic in the script itself"
  - "All 105 cloud-itonami-lei-* companies existing at ingestion time are live-queryable in kotobase.net today via a real multi-clause Datalog join (verified: company LEI -> legal name + ToS source URL round-tripped through a real `ai.gftd.apps.kotobase.datomic.q` call)"
  - "The general 'every actor adopts the 80-data/public/*.journal.edn -> kotobase.net pattern' migration remains open, exactly as ADR-2607072300 itself scoped it -- this ADR closes the gap for ONE actor family, not the general case"
related:
  - 90-docs/adr/2607072300-actor-public-data-git-journal-kotobase-index.md
  - 90-docs/adr/2607110300-cloud-itonami-lei-corporate-tos-catalog.md
  - orgs/kotoba-lang/kotobase-client
  - orgs/kotoba-lang/kotobase-peer
  - orgs/gftdcojp/net-kotobase/kotobase-cf-wasm/DEPLOY.md
supersedes: []
superseded_by: []
---

# ADR-2607113500: kotobase.net ingestion fold job for cloud-itonami-lei-* actors

**Status**: accepted
**Date**: 2026-07-11
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

ADR-2607072300 が決めたのは「actorの公開データは自身のgit repo内のEDN quad-log
(`80-data/public/*.journal.edn`) が正本、kotobase.net はそこから畳み込む派生インデックス」
という一般設計であり、同ADR自身の `:not-decided` は「取り込みパイプライン
（journal → kotobase-peer fold job）のコードは書いていない」「trigger mechanism
（west update hook / GitHub webhook / kotobase.net pull）は未定」と明記していた。

本セッションで ADR-2607110300（cloud-itonami-lei-corporate-tos-catalog）により、
実在企業105社分の `80-data/public/tos.journal.edn`（実ToS全文 + 出典URL + 取得日時 +
sha256）を持つ公開リポジトリ群が既に存在する状態になった——これが
ADR-2607072300の欠けていたパイプラインを実装するための、具体的で現実の入力集合を
初めて提供した。

## Decision

### 1. トリガー機構: pull-based batch fold job（webhookでもwest hookでもない）

対象は「既に存在する105個の静的リポジトリ」であり、継続的に変化するライブストリーム
ではないため、最もシンプルで正しい選択は **pull型のバッチ fold job**——GitHub org を
enumerate し、各リポジトリの `blueprint.edn` + `tos.journal.edn` を Contents API
（`raw.githubusercontent.com`、認証不要、public repoのため）経由で取得し、
kotobase.net へ transact するスクリプト。webhook/west-update-hookは「継続的に増える
アクター」向けの将来の拡張として残す（本ADRでは実装しない、ADR-2607072300自身の
"not-decided" と同じ扱い）。

### 2. 実装: 実 nbb + 実 cljs、既存の `kotobase-client` をそのまま再利用

`scripts/kotobase-ingest-cloud-itonami-lei.cljs`（root CLAUDE.mdのruntime優先順位に
従い、フルのDatalogエンジンはcljsネイティブなホスト実行環境がまだ無いため
（`kotobase-cf-wasm/DEPLOY.md` 実測）、nbbで実cljsソースをそのまま動かすのが現状
正しい選択——`kotoba-lang/kotobase-client` の `kotobase.client`/`kotobase.cacao`/
`kotobase.cid` を **一切書き直さず** `--classpath` 経由でそのまま require
（nbb実行で動作確認済み、shadow-cljsのコンパイルステップ不要）。CACAO発行・
did:key導出・SIWEメッセージ構築・DAG-CBORエンコードは全て既存実装をそのまま使う
——認証まわりを新規に書かない。

### 3. アイデンティティ: 本ジョブ専用の新規 self-sovereign actor identity

新規 Ed25519 鍵を生成し、`did:key:z6Mkh6mnYrfXNjDSoRQfRK9a2QanvaSAVAqDFK44Vpss5ZSA`
として自己発行。秘密鍵は `scripts/.kotobase-ingest-cloud-itonami-lei-identity.hex`
（`scripts/.gitignore` で除外、gitに一切コミットしない——root CLAUDE.mdの秘密鍵
取り扱い規律と同じ）。グラフは この DID 由来の
`kotobase/db/<did>/cloud-itonami-lei-catalog`
（graph CID: `bafyreifq6p445b472n6qbutttfaa3fegq5bbjnnxcmrogi5y6ksw46qwty`）——
オペレータの許可リスト（`KOTOBASE_OPERATOR_DIDS`）は本番で空（open、誰でも
自分のgraphへtransact可能、`kotobase-cf-wasm/wrangler.jsonc` で実測確認）なので、
静的な事前登録は不要だった。**この鍵ファイルを失うと、再実行が新しい別グラフを
始めてしまい今回のデータへ継続できなくなる**——現状ローカルのみに存在し、
1Password/kagiへの複製は本ADRの範囲外のフォローアップとして明記する（正直な
未完了事項——他actorの秘密鍵管理の慣例に倣うなら本来はkagi/1Password登録が
望ましいが、本セッションでは行っていない）。

### 4. 発見した実際のwireフォーマット（ADRの想定と実装時の食い違い）

ADR-2607072300 のプロトタイプ的な記述は kotobase-peer の README 例
（`[:db/add e a v]` ベクタ形式）を踏襲していたが、**実際に本番 `backend.kotobase.net`
へ POST したところ `400 {"ok":false,"error":"InternalError"}`**（詳細メッセージ無し）
で失敗した。`kotoba-lang/kotobase-server` の `handler.cljc`（`tx-edn->quads`）を
直接読んだところ、`tx_edn` が受理するのは **エンティティmapのベクタ**
`[{:db/id "e" :ns/attr v ...} ...]` であり、ベクタ形式が許されるのは
`[:db/retract e a v]`（4要素）と `[:db/retractEntity e]`（2要素）の2種類のみ
——`[:db/add e a v]` は`tx-edn->quads`の`cond`に一致せず
`"kotobase: unrecognized tx_edn item"`で例外になる（`backend.kotobase.net`への
直接POSTで実際にこのメッセージを確認）。エンティティmap形式に修正した後、
transactは`{"ok":true,...}`で成功した。**この食い違いはコードを読んで実測で
確認したものであり、ADR文面の想定を鵜呑みにしなかった結果発見できた。**

### 5. 実行結果（実データ、実105社）

`gh api "orgs/cloud-itonami/repos?..." --paginate`（`gh repo list` のGraphQL経路は
本セッション中にレート制限を使い切ったため、REST経路に切替——別quotaで同じ結果を
取得できる）で列挙した **105社全リポジトリを実際にtransact、105/105成功、
失敗ゼロ**。各社: `:company/*`（blueprint.edn由来）+ 各ToS文書ごとの
`:tos/full-text`/`:tos/source-url`/`:tos/retrieved-at`/`:tos/sha256`/
`:tos/doc-type`（tos.journal.edn由来、`:tos/company`で親エンティティへ逆参照）。

**冪等性を実測確認**: ExxonMobilエンティティ（`lei:J3WHBG0MTS7O8ZVMDC91`）を
2回transactした後も `:eavt` のdatom数は6件のまま（12件へ倍増しない）——
`:db/id`をキーにしたcardinality-one upsertとして機能することを実際のserver応答で
確認した（理論上そうなるはず、ではなく実測）。

**読み出し確認（`q`, 実multi-clause Datalog join）**:
```
{:find [?name ?url] :where [[?e :company/lei "J3WHBG0MTS7O8ZVMDC91"]
                             [?e :company/legal-name ?name]
                             [?tos :tos/company ?e]
                             [?tos :tos/source-url ?url]]}
=> [["Exxon Mobil Corporation" "https://corporate.exxonmobil.com/global-legal-pages/terms-and-conditions"]]
```
実際の本番 `ai.gftd.apps.kotobase.datomic.q` エンドポイントへの実POSTで、
実際にjoinされた行が返ることを確認した。

## Consequences

- ADR-2607072300 の「取り込みパイプライン未実装」という欠落を、
  cloud-itonami-lei-* ファミリーについてのみ実際に埋めた——一般則
  （「全actorがこのpatternを採用する」移行）は依然として同ADRのまま未着手。
- `kotobase.net`（`https://backend.kotobase.net` 経由。apexの `https://kotobase.net`
  自体は `/`・`/_health` はマーケティングページを返す独自ルートを持ち、
  `/xrpc/*` のみ `KOTOBA_BACKEND_URL` 経由でproxyする構成——本スクリプトは
  proxyの不確実性を避けてbackend直叩きにした）が、実際に運用可能な
  書き込み/読み出し面であることを実データで証明した。
- 秘密鍵がローカルファイルのみに存在し、1Password/kagi等の永続化された
  vaultに未登録——**このセッション環境を離れると鍵を喪失するリスクがある**。
  次回このジョブを再実行する前に、鍵の永続化（他actorの慣例に倣うなら
  1Password `gftdcojp` vault か kagi）をフォローアップとして行うことを推奨する。
- 新規companyが `cloud-itonami-lei-*` ファミリーに追加されるたび
  （ADR-2607110300のロールアウトは継続中）、このスクリプトを再実行すれば
  追加分だけ取り込まれる（既存分は冪等に無変化）——ただし自動トリガーは無い
  （手動再実行、または将来のwebhook/west-update-hook拡張が必要、本ADRの
  スコープ外）。

## Alternatives considered

- **push型webhook（各cloud-itonami-lei-*リポジトリのpushで即時transact）** —
  却下（今回は見送り、将来検討）。105個の静的リポジトリに対して常時稼働の
  webhookリスナーを立てるインフラコストは、バッチジョブの手動/定期再実行に
  見合わない。継続的に成長するactorファミリーには適するため、
  ADR-2607072300の"not-decided"のまま将来のフォローアップとして残す。
- **kotobase-peer の直接組み込み（in-process `commit!`）** — 却下。
  `kotobase-peer` はサーバサイドのライブラリで、R2/B2バックエンド配線・
  CACAO検証・IPNS head署名は `kotobase-cf-wasm`/`kotobase-server` 側の責務
  ——このジョブはXRPCクライアントとして振る舞うのが正しい層で、
  エンジンを直接埋め込む理由がない。
- **shadow-cljsでコンパイルしたNode bundle** — 却下（今回は）。nbbで
  `kotobase-client` の実cljsソースをそのまま動かせることを実測確認できたため、
  ビルドステップを追加する理由がなかった。将来この方式が壊れた場合の
  フォールバックとして記録しておく。

## References

- ADR-2607072300（actor-public-data-git-journal-kotobase-index）— 本ADRが
  閉じる"not-decided"項目の出典。
- ADR-2607110300（cloud-itonami-lei-corporate-tos-catalog）— 取り込み対象の
  journal/blueprintスキーマの出典。
- `kotoba-lang/kotobase-client`（`src/kotobase/{client,cacao,cid}.cljs`）—
  本ジョブが再利用したCACAO/XRPCクライアント実装、無改変。
- `kotoba-lang/kotobase-server`（`src/kotobase/server/handler.cljc`）—
  実際のtx_edn wireフォーマット（`tx-edn->quads`）の一次情報源。
- `gftdcojp/net-kotobase/kotobase-cf-wasm/DEPLOY.md` — production cutover
  (2026-07-08) の実配線と `KOTOBASE_OPERATOR_DIDS` の空許可リスト設定の出典。
