---
id: adr-2607152600-saas-software-competitive-catalog
title: "ADR-2607152600: 世界の SaaS/software 製品を EDN で catalog 化する saas-catalog システムを新規に導入する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（「cloud-itonami で saas や software の情報で全世界的な product の list は
    edn で datomic, datascript query できるようにまとまっている?」→ 調査の結果、該当する
    ものは存在しない（cloud-itonami 自身の ISIC 産業分類 portfolio registry はあるが、外部の
    実在 SaaS/software 企業一覧ではなく、DataScript/Datomic 配線もされていない）ことを回答
    →「では coverage を向上」→ 対象範囲を確認する AskUserQuestion で「『世界の
    SaaS/software製品』競合カタログを新規に作る」を選択）
related:
  - orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn（cloud-itonami 自身の
    ISIC 産業分類 portfolio registry。本 ADR の catalog とは別物であることを明記する対比対象）
  - 90-docs/adr/2607131000-cloud-itonami-registry-repo-drift-scan.md（上記 registry の運用
    規律の先例）
  - 90-docs/adr/2607132300-kotoba-lang-uiux-design-quality-scoring.md（base datoms + append-only
    ledger という EDN 運用パターンの直接の先例、DataScript/Datomic transactable 形式も同型）
  - 90-docs/adr/2607021500-portfolio-seven-layer-business-model-lean-canvas.md（同パターンの
    さらに早い先例、BMC/Lean Loop）
  - 90-docs/business/canvas-ledger.edn（append-only event ledger、手編集禁止・追記のみの規律の先例）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: saas-software-competitive-catalog
authoritative: true
authoritative_for:
  - "90-docs/saas-catalog/saas-catalog.datoms.edn を世界のSaaS/software競合カタログの
    schema/category-catalog 正本とする位置づけ（DataScript/Datomic transactable EDN）"
  - "90-docs/saas-catalog/saas-catalog-ledger.edn を append-only 製品facts台帳の正本とする
    位置づけ（手編集禁止・追記のみ — 「coverage向上」は新規行の追記で行う）"
  - "このcatalogはcloud-itonami自身の製品portfolio（orgs/kotoba-lang/industry/registry.edn）
    とは別物であるという区別。新規に『世界のSaaS/software製品カタログ』を作る前に、この
    ADRと既存ledgerを必ず確認する、という再発防止規則（BMC/Lean Loop・design-quality と
    同型の教訓）"
  - "捏造ゼロの運用規律: 未検証の定量指標（売上/ARR/ユーザー数/評価額）はフィールド自体を
    持たず記載しない。事実は学習知識ベースのcurationであり live-verified ではないことを
    ledgerヘッダに明記する"
---

# ADR-2607152600: 世界の SaaS/software 製品を EDN で catalog 化する saas-catalog システムを新規に導入する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

「cloud-itonami で saas や software の情報で全世界的な product の list は edn で
datomic, datascript query できるようにまとまっている?」という質問を受け、調査した。

見つかったもの: `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
（9,577行、ISIC Rev.4 4桁コード単位で約625エントリ、ADR-2607131000 が運用規律の正本）。
ISIC section J（ソフトウェア/IT/通信/情報サービス、58–63）のコードは一通りカバーされ、
特にSaaS寄りのもの（5820 Software publishing → CRM SaaS actor、6201 Computer
programming → Marketing automation SaaS actor、6202 Computer consultancy →
Customer-service-hub SaaS actor、6209 → IT helpdesk actor、6311 → Market-data SaaS
actor、6312 → Web-portal SaaS actor）は軒並み `:maturity :implemented`（実 actor あり）
だった。

しかし、これは質問の意図とは別物だと判明した:

1. **cloud-itonami自身が作っている製品portfolio**（ISIC産業分類コード単位で全625業種を
   カバーする自社actor群）であって、**実在する外部SaaS/software企業（Salesforce・
   HubSpot・Notion等）の一覧ではない。** コメント中の「Salesforce/HubSpot-class」は
   差別化のための参照点に過ぎず、competitive-intelligenceデータではなかった。
2. **DataScript/Datomicで直接query可能な形にはなっていない。** エントリに `:db/id` は
   無く、`datascript`/`datomic.api` も未使用 — plain EDN vectorを
   `clojure.edn/read-string` で読み、手書きのClojure関数（`kotoba.industry/by-id`等）で
   アクセスする方式だった。

この2点のギャップをユーザーに提示し（AskUserQuestion）、「『世界のSaaS/software製品』
競合カタログを新規に作る」という選択を得た。ゼロからの新規プロジェクトなので、この repo
に既に確立されている「base datoms（schema+catalog）+ append-only ledger（facts）」という
EDN運用パターン（design-quality: ADR-2607132300、BMC/Lean Loop: ADR-2607021500）を
そのまま踏襲する。

## Decision

1. **2ファイル構成**（design-quality/BMCと同型）:
   - `90-docs/saas-catalog/saas-catalog.datoms.edn` — `:db/ident`宣言付き schema
     （`:category/*`・`:product/*`属性）+ `:category/*` catalog（31カテゴリ、固定的な
     分類taxonomyなのでbase側に置く）。
   - `90-docs/saas-catalog/saas-catalog-ledger.edn` — append-only、1行1製品の EDN map
     （158件、seed batch `saas-catalog-seed-20260715`）。**「coverage向上」は今後
     このファイルへの行追記で行う**（既存行は手編集しない。事実が変わった場合
     ─買収・改名・終了等─ は古い行を書き換えず新しい`:product/status`/`:product/note`と
     新しい`:product/added-at`を持つ行を追記する）。
2. **カテゴリの一部（7/31）は cloud-itonami 自身のISIC actorへ意図的にリンクする**
   （`:category/isic` + `:category/cloud-itonami-actor`）: crm→5820、
   marketing-automation→6201、customer-service→6202、it-service-management→6209、
   data-hosting→6311、web-portal→6312、hcm-hris→6310。いずれも
   `orgs/kotoba-lang/industry/registry.edn` を直接grepして確認済みの実在entryのみ
   リンクし、未確認のカテゴリはリンクを空のままにする（推測でリンクを埋めない）。
   これにより「cloud-itonamiの各SaaS actorが、世界のどの実在カテゴリリーダーを
   ベンチマークにしているか」を横断queryできる。
3. **捏造ゼロの運用規律**（BMC/design-qualityと同じ原則）: schemaに売上/ARR/
   ユーザー数/評価額のフィールドを**そもそも作らない**。学習知識ベース（cutoff
   ~2026-01）からのcurationであり、書いた時点でlive-verifiedではないことを
   ledgerヘッダに明記し、外部向け/意思決定に使う前のWebSearchでのspot-check
   を推奨する注記を残す。founded年は「vendor/製品起源企業の創業年」と定義し、
   買収・改名で複雑な場合は`:product/note`で明示する（黙って1つの年を選ばない）。

## Coverage（初回seed、2026-07-15時点）

158製品 / 31カテゴリ（各カテゴリ最低1製品、`:product/category`が
`saas-catalog.datoms.edn`の`:category/id`に解決することを`bb`でparse検証済み）/
18カ国（USA・GBR・DEU・FRA・NLD・ESP・ITA・POL・AUS・ISR・IND・JPN・CHN・CAN・
NZL・EST・BEL・GRC）にHQを持つvendor。globalなSaaS市場の実態を反映してUSAが多数だが、
意図的に欧州・アジア太平洋・中国のリーダーも含めた。

## Limitations（正直に明記する — no silent caps）

- **網羅的ではない。** 著名なグローバル/地域カテゴリリーダーのcurated sampleであり、
  ロングテールの地域特化/垂直特化SaaSは意図的に今回未収録（今後のledger追記の対象）。
- **live-verifiedではない。** 全factは学習知識からのcurationで、記述時点で公式サイト/
  filingへの再照会はしていない。買収・改名・shutdown等、cutoff以降の変化を見落として
  いる可能性がある。
- **定量指標（売上/ARR/ユーザー数/評価額）は意図的に不採用。** 信頼できる出典なしに
  書くと捏造リスクが高いため、schema自体にフィールドを作らなかった。
- **カテゴリ⇄ISIC⇄cloud-itonami-actorのリンクは7/31のみ。** 残り24カテゴリは
  対応するISICコード/actorが無い、または未確認のため空欄のまま（推測で埋めていない）。

## Consequences

正: 「cloud-itonami の各SaaS actorが世界のどのカテゴリリーダーに対して競合的
位置づけにあるか」をDataScript/Datomicで直接queryできる（例クエリはdatoms.edn
ヘッダ参照）。design-quality/BMCと同じ運用規律（base+ledger、追記のみ、捏造ゼロ）
なので、この repo で既に確立されたcleanupフローや`--check`的な検証習慣がそのまま
適用できる。

負: 初回seedは1回のオーケストレータ curationであり、design-qualityのllm-judge層の
ような複数judgeでの相互検証は経ていない。長期的にはWebSearch/deep-researchで
再検証するfollow-upが必要。

## Alternatives Considered

`orgs/kotoba-lang/industry/registry.edn`を拡張して外部企業も同じファイルに混ぜる案は、
「cloud-itonami自身のportfolio」と「世界の競合カタログ」という意味論が全く異なる
（前者はmaturityを進めるべき自社actor、後者は観測対象の他社製品）ため採用しなかった
— 混在させると`maturity-of`等の既存Clojure関数のセマンティクスを壊す。deep-research
workflowで最初からWebSearch検証込みのカタログを作る案は、初回scopeとしては重すぎる
と判断し、まず学習知識でのseedを土台として作り、正直な限界の明記とfollow-upの
WebSearch検証を分離した。

## References

- 90-docs/saas-catalog/saas-catalog.datoms.edn
- 90-docs/saas-catalog/saas-catalog-ledger.edn
- orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn
- 90-docs/adr/2607132300-kotoba-lang-uiux-design-quality-scoring.md
- 90-docs/adr/2607021500-portfolio-seven-layer-business-model-lean-canvas.md
- 90-docs/adr/2607131000-cloud-itonami-registry-repo-drift-scan.md
