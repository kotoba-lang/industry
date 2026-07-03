# ADR-2607032330: cloud-itonami-iso3166 — jurisdiction market-entry & procurement-compliance blueprints (fourth axis, orthogonal to ISIC/ISCO/COFOG/UNSPSC)

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

要望は「cloud-itonami として `cloud-itonami-iso3166-{countrycode}` を、各国
政府ごとの actor として設計してほしい」だった。既存の関連 actor を調査した
結果、以下が判明した:

- **`com-etzhayyim-ooyake`**(etzhayyim/root)は既に ISO 3166-1 の国ごとに
  `:gov.unit` レコード + DID (`did:web:etzhayyim.com:gov:<iso3>`) を持つ。
  しかし憲法的に **読み取り専用の civic-wayfinding mirror** であり、政府を
  名乗る・公式チャネルとして振る舞うことを明示的に禁じている(G3
  impersonation ban)。「政府のための actor」ではない。
- **`matsurigoto`**(etzhayyim/root)は COFOG backbone を主権統治の spine と
  して使う、文字通り「政府そのもの」の実行層だが、principal は
  etzhayyim 信徒共同体の自己統治か、採用した国家自身の実行のいずれかに
  限定される(G1 no-operator-master-key / G3 authority-bearing)。
- **`com-etzhayyim-toritsugi`**(etzhayyim/root)は同意した一個人会員の
  「自分自身の」手続きのみを案内・下書き・(gated)代行提出する非商用・
  donation-only の窓口。第三者の代行や商用サービスは構造的に対象外。
- **`legal-entity.etzhayyim.com`** は 194+ カ国の法人データを読み取り専用で
  集約するのみ。実行はしない。
- **`cloud-itonami-M6910`**(ADR-2607031500)は「法人になりたい顧客」を
  対象に、全世界どこでも法人設立を代行実行する actor。法域は
  `formation.facts/catalog` という **internal data**(現状10法域)であり、
  国ごとの repo ではない。
- **`cloud-itonami-cofog-{code}`**(ADR-2607031600)は「政府に発注/license
  される事業者」向けの、法域非依存な機能別テンプレート(消防検査・
  水道漏水検知等)。

上記のどれも、「**既に法人化した事業者が、特定の国の政府と実際に契約を
取り、公共調達に参入する**」ための、その国固有の参入要件(現地登録・
入札制度・現地ライセンス発給元・データ主権/現地化規制・公金請求の税務)を
埋めていない。M6910 は「法人になる」フェーズで終わり、COFOG系は「機能」を
法域非依存に汎用化するだけ。ISO 3166 の国コードは COFOG/ISCO/UNSPSC の
ような機能分類ではなく法域識別子であり、GTIN(ADR-2607031800)が直面した
「識別子であって分類taxonomyではない」問題と同型である。GTINはこれを
「コードでなく機能で割る」ことで解決した。ISO 3166 についても同様の
解決が必要で、かつ M6910 の「法域はデータ、事業者は1つ」という既存解と
衝突しない機能を選ぶ必要がある。

## Decision

### 1. `cloud-itonami-iso3166-{code}` — 独立事業者向け「対{country}政府
   市場参入・調達コンプライアンス」blueprint

新規 blueprint family を、既存 `cloud-itonami-{ISIC}` /
`cloud-itonami-isco-{code}` / `cloud-itonami-cofog-{code}` /
`cloud-itonami-unspsc-{segment}` と構造的に同一の形(README + blueprint.edn
+ docs/business-model.md + docs/operator-guide.md + AGPL-3.0-or-later +
CONTRIBUTING/SECURITY/GOVERNANCE/CODE_OF_CONDUCT)で新設する。各 repo は
「**既に法人化した独立事業者が、ある1カ国の政府調達に実際に参入し、
その国固有の規制を継続的に遵守する**」ための商用サービスを設計する
(現地公共調達ポータル登録、現地コンテンツ/ライセンス要件マッピング、
データ主権/現地化規制チェック、公金請求の税務・インボイス対応)。
政府そのものではなく、政府と契約する側。

**M6910 との違い(フェーズ/規制領域が異なる)**: M6910 =「法人になる」
(会社法)。iso3166 系列 =「(法人化した後に)その国の政府と契約を取る」
(公共調達法)。クライアントのライフサイクル上、別フェーズ。

**COFOG系との違い(軸が直交する)**: COFOG系 =「機能」軸(法域非依存の
1機能テンプレート)。iso3166系列 =「法域参入」軸(機能非依存の
1国の参入要件)。事業者は両方を組み合わせて使う(例:
`cloud-itonami-cofog-04.5` の道路点検事業を `cloud-itonami-iso3166-jpn`
で日本向けに調達参入させる)。

**ooyake / matsurigoto / toritsugi / legal-entity との違い**: いずれも
「政府を代弁/僭称しない」「商用でない、または principal が政府自身/個人
会員に限定される」という一線があり、iso3166系列はそのどれでもない
**第三の principal**(政府に発注/入札される独立事業者)であることを
README で明示する。これは COFOG系が既に確立した principal 分離の慣行と
同一線上にある。

**Naming**: `iso3166-` infix を必須とする(`isco-`/`cofog-`/`unspsc-` の
先例通り)。code はISO 3166-1 alpha-3(小文字、例 `jpn`/`usa`/`deu`)。

### 2. `kotoba-lang/iso3166` — ISO 3166-1 alpha-3 registry、193/193
   現行UN加盟国フルカバレッジ

`kotoba-lang/cofog` / `kotoba-lang/unspsc` と同じ contract 形状
(`get-country`, `required-technologies`, `readiness`, `execution-plan`,
`maturity`, `maturity-summary`, `maturity-roadmap`)。COFOGと異なり
division/group 階層を持たない **フラット** な registry(国コードは
それ自体が事業機能taxonomyではないため、level によるblueprint適格性の
ゲートは不要)。

alpha-3コード + 英語/現地名は `com-etzhayyim-ooyake` の Wikidata検証済み
`gov-units.seed.edn` / `gov-units.g20.edn` / `gov-units.world-countries.edn`
(2026-06-03 一回限りのメンテナ pull、現行UN加盟国のみ、解体国家等は除外
済み)から **そのまま再利用**する(再導出しない)— `kotoba-lang/cofog` が
`matsurigoto` の COFOG backbone を verbatim 再利用したのと同じ reuse
discipline。

5カ国を `:maturity :blueprint` として pilot 公開する(継続の国/地域
バランスを取り、機能軸(COFOG等)のパイロットと地理的に重複しない
選定):

| ISO3166 | Country | Blueprint |
|---|---|---|
| JPN | Japan | Independent Public-Sector Market-Entry & Procurement Compliance Service — Japan |
| USA | United States of America | Independent Public-Sector Market-Entry & Procurement Compliance Service — United States |
| DEU | Federal Republic of Germany | Independent Public-Sector Market-Entry & Procurement Compliance Service — Germany |
| KEN | Kenya | Independent Public-Sector Market-Entry & Procurement Compliance Service — Kenya |
| IND | Republic of India | Independent Public-Sector Market-Entry & Procurement Compliance Service — India |

東アジア/北米/西欧/サブサハラアフリカ/南アジアの5地域、先進国/新興国、
コモンロー/大陸法の双方をカバーする選定。残り188カ国は `:maturity :spec`
(registry-only stub)として全193/193カバレッジを維持し、
`kotoba-industry` / `kotoba-occupation` / `kotoba-cofog` と同じ
maturity-roadmap 経路で将来昇格可能にする。

### 3. Robotics premise 対象外(digital-service exemption)

市場参入・調達コンプライアンスは物理領域作業を伴わないデータ/
コンプライアンスサービスであり、`cloud-itonami-6310`(HR SaaS)・
`cloud-itonami-gtin-*`・`cloud-itonami-M6910` と同じ exemption class。
`blueprint.edn` は `:itonami.blueprint/robotics false` を設定し、
`:required-technologies` は `[:identity :forms :dmn :bpmn :audit-ledger]`
(M6910 と同一のtechnology set — 両者とも規制対応のfiling/コンプライアンス
業務という同型ドメインのため)。

### 4. 実アクチュエーションの gate(M6910 パターンの継承)

`:filing/submit`(実際の公共調達ポータルへの登録提出)は governor の
`:auto` 集合に含まれず、常に人間による承認を要求する。虚偽の法域要件
主張・制裁/コンプライアンスhit は HARD hold として人間の承認では
上書きできない。この二重gate構造は M6910 の
`filing-submit-never-auto-at-any-phase` 不変条件をそのまま踏襲する。

## Consequences

- (+) 「機能」(ISIC/ISCO/COFOG/UNSPSC)と直交する「法域参入」軸が
  first-class blueprint pattern として確立され、両軸を組み合わせて
  実際に1カ国で1機能の事業を運営するために必要な残り2ピース
  (会社法対応=M6910、機能テンプレ=COFOG等)を補完する。
- (+) 193/193 の ISO 3166-1 alpha-3 フルカバレッジ、
  readiness/execution-plan/maturity-roadmap 検証可能(8 tests、
  421 assertions、全green)。
- (+) ooyake / matsurigoto / toritsugi / legal-entity / M6910 /
  cofog系の6つの隣接actorとの境界を `docs/cloud-itonami.md` に明示し、
  principal混同を防ぐ(COFOG系ADRが確立した境界明示の慣行を踏襲)。
- (+) 国名/コードは ooyake の検証済みデータを再利用しており、独自の
  taxonomy再導出によるエラーのリスクを避けている。
- (−) pilot 5カ国のみ実装。残り188カ国は `:spec` のまま — 各国の実際の
  公共調達ポータル/現地ライセンス要件は pilot 5カ国についても
  `docs/business-model.md` レベルの記述であり、実データソースへの
  citation付きの`formation.facts`相当のcatalog化は将来の実装
  (`:blueprint` → `:implemented` 昇格)フェーズの作業として残る
  — 本ADRは registry + blueprint 文書の骨格までを完了条件とする。
- (−) M6910 との境界(会社法 vs 公共調達法)は文書上明示したが、実際の
  運用では「同じ事業者が両方の blueprint を fork する」ケースが典型と
  なるため、将来的に両者の technology-stack/readiness 連携
  (M6910の`formation.facts`とiso3166の`execution-plan`を1つの
  operator consoleで束ねる)が課題として残る。
- superproject registration: `orgs/kotoba-lang/iso3166` を
  `manifest/repos.edn` / `manifest/west.yml` に追加。5件の
  `cloud-itonami-iso3166-*` blueprint repos は既存の `cloud-itonami-*`
  慣例通り standalone のまま(west管理しない)。

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166`
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{jpn,usa,deu,ken,ind}` (5 repos)

## References

- ADR-2607011000(cloud-itonami robotics premise + ISIC 21/21)— digital-
  service exemption class の先例。
- ADR-2607012000(cloud-itonami-isco occupation blueprints)
- ADR-2607031600(cloud-itonami-cofog government-function blueprints)—
  直交する「機能」軸、principal分離の慣行の直接の先例。
- ADR-2607031700(cloud-itonami-unspsc commodity-segment blueprints)
- ADR-2607031800(cloud-itonami-gtin functional blueprints)—「識別子で
  あって分類taxonomyでない」問題への先行解決、本ADRが同型の問題として
  参照。
- ADR-2607031500(cloud-itonami-M6910 global incorporation actor)—
  法域データの internal-data パターン、実アクチュエーションgateの
  直接の先例。
- ADR-2606301900(etzhayyim/root, ISCO/COFOG organism actors)—
  `com-etzhayyim-ooyake` の国別 `:gov.unit` レコード、G3
  impersonation ban の出典。
- `com-etzhayyim-ooyake/registry/gov-units.{seed,g20,world-countries}.edn`
  — alpha-3コード+国名データの再利用元。
- `com-etzhayyim-toritsugi` CLAUDE.md — 個人会員限定・非商用の
  citizen-side principal、本ADRが明示的に対象外とする境界の出典。
