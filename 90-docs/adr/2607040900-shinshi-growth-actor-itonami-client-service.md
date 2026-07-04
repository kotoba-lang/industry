# ADR-2607040900: shinshi-growth-actor — itonami が club-shinshi に提供する growth-loop-as-a-service

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki

## Context

`club-shinshi`（`shinshi.club`、運営: **JK株式会社**）と itonami/`cloud-itonami`
（運営: **Gftd Japan株式会社**）は、ADR-2607021500 の 7 レイヤー体系において
別法人として整理されている。club-shinshi 自身の riskiest gate は「低手数料
creator 課金が ExoClick ad 収益を超える収益軸になるか」（gate = creator-gmv-jpy
が ad-revenue-jpy を上回るか、ADR-2607021900）。

`cloud-itonami` と `club-shinshi` の間には、本 ADR 時点で **既存の統合が一切ない**
（grep 確認: `cloud-itonami` 側に club-shinshi/shinshi への参照なし、`club-shinshi`
側にも itonami への参照なし）。既存 3 例の actor（`com-etzhayyim-kyoninka` =
robotaxi-actor、`gftd-talent-actor` = HR-LLM ⊣ PolicyGovernor、`cloud-itonami`
自身の ops-LLM ⊣ CertGovernor）と同型の containment パターンを、itonami が
club-shinshi 向けに運用する growth-loop actor にも適用したい。

## Decision

`gftdcojp/shinshi-growth-actor`（private）を新規 actor repo として起こす。
**growth-LLM ⊣ MarketingGovernor** パターン: growth-LLM は langgraph-clj
StateGraph の単一ノードに封じ込め、提案は必ず独立した MarketingGovernor を
経由してから SSoT（`growth.store`）にコミットされる。

MarketingGovernor の不変条件:

**HARD（人間でも上書き不可 → HOLD）**
1. **charter-clean** — 誇大な緊急性/希少性・隠れ手数料・誤解を招く自動更新の
   文言を検出したら拒否（キーワード/フレーズによるヒューリスティック床であり、
   完全な NLP 分類器ではない。実運用で見つかった事例で随時拡張する前提）。
2. **年齢確認/同意コピー不可侵** — age-verification/consent copy に触れる提案は
   常に hard violation（ExoClick/アダルトコンテンツ コンプライアンス）。
3. **未知 effect** — enum 外の `:effect` は hard violation（スキーマ逸脱を
   fail closed で拒否）。

**SOFT（人間が判断。ただし auto-commit には絶対乗らない）**
4. **クリエイター取り分保護** — `:ppv-terms-change`、または `:cites`/`:summary`/
   `:rationale` が creator GMV split の変更を示唆する提案は、confidence に
   関わらず常に escalate。
5. **high-stakes gate** — `#{:pricing-experiment :ad-spend-change
   :ppv-terms-change}` は常に人間承認。
6. **confidence floor** — low-stakes（`#{:content-experiment :marketing-copy
   :creator-outreach}`）は `:ok? true` かつ違反なし かつ `:confidence >= 0.7`
   のときのみ auto-commit 対象（Phase 3 のみ）。

**Phase 0→3 段階導入**（`growth.phase`）: Phase 0 = mock advisor + MemStore
のみ、ライブデータなし、実行なし。Phase 1 = club-shinshi の読み取り専用ライブ
メトリクス（未実装）。Phase 2 = 実 LLM をライブデータに対して稼働（人間承認は
維持、未実装）。Phase 3 = governor 承認済み low-stakes の auto-commit（未実装、
Phase 1/2 の個別検証後にのみ到達可能）。**本 ADR がカバーするのは Phase 0
（scaffold + mock advisor + ledger + governor + contract tests のみ。ライブ
データ・実行は一切なし）のみ**。

Store は `MemStore`（既定）/ `DatomicStore`（`langchain.db` の `:db-api` 駆動、
`{:q :transact! :db :pull :entid}` 契約）の2バックエンドを同一 `Store` protocol
で実装し、`MemStore ≡ DatomicStore` を contract test で保証。全レコードに
`:growth.tenant/id "club-shinshi"` を付与（スキーマレベルのタグのみ。完全な
テナントレジストリではない — 後述 Consequences）。

## Consequences

- (+) 既存3 actor と同型の containment（sealed LLM ⊣ 独立 governor ⊣ 不変台帳）
  が growth-loop ドメインにも拡張された。MemStore ≡ DatomicStore 契約テスト・
  no-actuation（悪意ある/OOD proposal → hold）テスト・high-stakes 常時 escalate
  テスト・dark-pattern 拒否テストで 13 tests / 44 assertions / 0 failures、
  clj-kondo clean。
- (−) **cloud-itonami のテナント/外部クライアントオンボーディングの gap**
  （`cloud-itonami/docs/adr/0009-open-business-registry-endpoint.md` が既に
  指摘: 参加者状態の永続ストアが未構築）を拡張しない限り、club-shinshi を
  「本物の」itonami ホスト型テナントにすることはできない。本 ADR はこの gap を
  解決しない — follow-up として残す。
- (−) **Phase 1 は cross-repo secrets-sharing 決定が前提**。club-shinshi 側の
  `ai-gftd-shinshi` 内部 D1 dispatch API（`read_metric` / `read_revenue_gate` /
  ExoClick publisher stats、`club-shinshi/60-apps/ai-gftd-project-shinshi/
  appview/ai-gftd-wasm-shinshi-sh1n5h1x/svelte/src/routes/_d1/+server.ts`）は
  `x-internal-trust` ヘッダーが `DISPATCHER_INTERNAL_SECRET` と一致することを
  要求する internal-only route。itonami がこの secret のコピーを持つべきか、
  club-shinshi 側が itonami 専用の scoped read-only credential を新設すべきかは
  未決定。本 ADR は決定しない（`growth.facts` はドキュメント化されたスタブの
  ままで、実装・配線は一切していない）。
- (+) 本 ADR は Phase 0 のみで完結する scaffold であり、実データ・実 secrets・
  実実行は一切含まない。

## References

- `90-docs/adr/2607021500-portfolio-seven-layer-business-model-lean-canvas.md`
  （club-shinshi/itonami が別法人であることの正本）
- `90-docs/adr/2607021900-portfolio-add-isekai-club-shinshi.md`（club-shinshi
  の riskiest gate = creator-GMV-exceeds-ad-revenue）
- `orgs/gftdcojp/ai-gftd-shinshi/docs/260613-bmc-lean.datoms.edn`（H1/H2 BMC
  hypothesis backlog、growth-LLM の `:cites` が参照する id）
- `orgs/gftdcojp/cloud-itonami/docs/adr/0002-org-repo-tenant-isolation.md` /
  `0009-open-business-registry-endpoint.md`（テナント分離モデルと外部オンボー
  ディング gap — 本 ADR では未解決のまま follow-up として残す）
- `orgs/gftdcojp/gftd-talent-actor`（HR-LLM ⊣ PolicyGovernor、本 actor の主たる
  テンプレート）/ `orgs/etzhayyim/com-etzhayyim-kyoninka`（robotaxi-actor）/
  `orgs/gftdcojp/cloud-itonami`（ops-LLM ⊣ CertGovernor）
- 新規 repo: https://github.com/gftdcojp/shinshi-growth-actor
