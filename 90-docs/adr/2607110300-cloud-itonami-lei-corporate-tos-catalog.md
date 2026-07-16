---
id: adr-2607110300-cloud-itonami-lei-corporate-tos-catalog
title: "ADR-2607110300: cloud-itonami-lei-<LEI> — per-company Terms-of-Service catalog, LEI-keyed (real listed companies, full-text + provenance)"
status: accepted
doc_type: adr
topic: cloud-itonami-lei-corporate-tos-catalog
authoritative: true
last_verified: 2026-07-10
authoritative_for:
  - "実在する大手/上場企業のサービスの利用規約・契約文書を分析・記録するファミリーは cloud-itonami org 直下、`cloud-itonami-lei-<LEI コード>` という命名（1社=1リポジトリ、ISO 17442 LEI キー）で作る"
  - "GTIN はこの用途のキーとして使わない — ADR-2607031800 が既に「GTIN は商品1点の識別子であり法人を指す階層を持たない」として却下済み"
  - "LEI (ISO 17442) をこのファミリーの一意キーに採用する — matsurigoto (etzhayyim/root) の LEI/ISO 7064 MOD 97-10 実装を `cloud-itonami-M6910` が既に移植・再利用しており（ADR-2607031500）、社内に既に確立された「法人を指す」正しい識別子である"
  - "各リポジトリは Advisor⊣Governor の actuation actor ではなく、read-only の参照/アーカイブ actor — 企業に代わって何かを提案/実行するものではない"
  - "利用規約は契約文書であり公開・閲覧前提のため全文保存で良いが、必ず取得元URL・取得日時・sha256・前バージョンへの参照とセットで記録する（ADR-2607072300 の git-journal パターンを再利用）。商標・提携誤認を避けるため README に非提携の明記を必須とする"
  - "public / AGPL-3.0-or-later、west 非管理、RAD 未登録 — 既存の `cloud-itonami-isic-*`/`isco-*`/`gtin-*` ブループリント群と同じ慣例に揃える（本セッションでのオーナー判断: public で開始）"
related:
  - 90-docs/adr/2607031800-cloud-itonami-gtin-functional-blueprints.md
  - 90-docs/adr/2607031500-cloud-itonami-m6910-global-incorporation-actor.md
  - 90-docs/adr/2607072300-actor-public-data-git-journal-kotobase-index.md
  - 90-docs/adr/2607110200-kawaraban-r0-r1-cloud-itonami-isco-3521-media-broadcast.md
  - manifest/repos.edn
supersedes: []
superseded_by: []
---

# ADR-2607110300: cloud-itonami-lei — 実在企業のToS/契約文書カタログ、LEIキー方式

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ設計)

## Context

セッションでの要望: 今の大手企業のサービスのactorや利用規約を分析し、EDNで記録して
kotobase.net と各GitHubに保存したい。実在する上場企業ごとにrepoを作って整理したい。
どのorg（etzhayyim / cloud-itonami / kotoba-lang）に置くべきか。

調査の結果:

- `cloud-itonami-{isic,isco,cofog,unspsc}-<code>` は既に43業種超のOpen Business
  Blueprint群を持つが、これらは**業種/職種という分類コード単位の汎用テンプレート**
  であり、実在する特定企業を指すものではない。
- **GTINは既にこの用途向けに検討・却下済み**（ADR-2607031800）: GTINは商品1点を
  指す識別子で、法人を指す分類階層を持たないため「1リポジトリ=1GTIN」は成立しない。
  同ADRは代わりに「GTINに対する機能」（issuance/verification/catalog）で3リポジトリに
  分割する解を採用した——今回の「実在企業ごと」という要求には同じ理由でなお合わない。
- **LEI (ISO 17442, Legal Entity Identifier) は既に社内で実装済み**: `matsurigoto`
  (etzhayyim/root) が LEI 発行 + ISO 7064 MOD 97-10 チェックデジットのロジックを持ち、
  `cloud-itonami-M6910`（法人設立代行actor、ADR-2607031500）がそれをそのまま移植・
  再利用している。LEIはGLEIFが発行する法人向けのグローバル一意識別子で、多くの
  法域で上場企業に取得が事実上必須——「実在する法人」を指すキーとして
  isic/isco/cofog/unspsc と同じ「コードで衝突なく一意」という性質を保てる。
- **actorの公開データの置き場所は既に一般設計がある**（ADR-2607072300）: actorの
  公開データはリポジトリ自身の git 履歴内のEDN quad-log
  (`80-data/public/*.journal.edn`) が正本、kotobase.net/kotobase-peer はそこから
  畳み込む派生インデックス。ただしingestion job自体は未実装のまま。
- 利用規約は kawaraban が扱う報道コンテンツ（著作権上の配慮から見出し+リンク+280字
  要約に限定、ADR-2607110200）とは性質が異なる——契約書であり、公開・閲覧・遵守が
  前提の文書なので、全文保存の法的リスクは相対的に低い。ただし丸ごと転載である以上、
  出典・取得日時の記録なしに保存すると「なぜこの文言なのか」の追跡可能性を失い、
  商標・提携誤認のリスクも残る。

## Decision

### 1. リポジトリ命名とorg配置

- `cloud-itonami` org 直下、`cloud-itonami-lei-<LEIコード小文字>` という命名
  （例: `cloud-itonami-lei-<20文字のLEI>`）。1社=1リポジトリ。
- LEIはGLEIF（Global Legal Entity Identifier Foundation）の公式レジストリから
  取得した実在の値のみを使う——捏造禁止（isic系の spec-basis 引用と同じ規律）。
- `etzhayyim`（非営利・自己主権の宗教法人レイヤー、商業競合分析の管轄外）、
  `kotoba-lang`（言語/インフラ層、分析対象の企業コンテンツを置く場所ではない）
  ではなく `cloud-itonami`（gftdcojp の商用business-OS、isic/isco/gtin系と同じ
  「実在するビジネス領域を扱う」慣例が既にある）を採用。

### 2. actorの性質: read-only 参照/アーカイブ、actuationなし

- このファミリーは Advisor⊣Governor で企業に代わって何かを提案/実行する
  actuation actor **ではない**。特定企業のToSを収集・構造化するだけの
  参照/アーカイブ actor——`cloud-itonami-gtin-catalog`（自己ホスト可能な
  参照データサービス）と同じクラス。
- RAD identity 登録はしない（agency を持つ actor ではないため、
  `cloud-itonami-isco-3521` が RAD未登録なのと同じ理由）。
- west 非管理（isic/isco/gtin/M6910 と同じ慣例、standalone repo）。

### 3. データスキーマ（ADR-2607072300 の journal パターンを踏襲）

`80-data/public/tos.journal.edn` に EDN quad-log
`[<doc-id> :attr value <tx> :add]` 形式で記録:

- `:tos/full-text` — 公開されている全文をそのまま（要約・改変なし）
- `:tos/source-url` — 取得元URL（実際に公開されているページ）
- `:tos/retrieved-at` — 取得日時
- `:tos/sha256` — 取得したテキストのチェックサム（改変検知用）
- `:tos/doc-type` — `:terms-of-service` / `:privacy-policy` / `:cookie-policy` 等
- `:tos/supersedes` — 直前バージョンの doc-id（改定履歴のチェーン。ToS改定の
  追跡そのものがこのカタログの付加価値になる）

企業識別情報は `blueprint.edn`/README に:

- `:company/legal-name`, `:company/lei`, `:company/jurisdiction`,
  `:company/website`, `:company/ticker`（任意、上場先取引所の証券コード）——
  いずれもGLEIF/取引所開示等の公式ソースから引用し、推測で埋めない。

kotobase.netの役割はADR-2607072300から変更なし（派生・再構築可能なインデックス。
ingestion job は本ADRでも未実装のまま、次の実装ステップとして残す）。

### 4. ライセンス・帰属・商標配慮

- リポジトリ構造自体は既存 cloud-itonami 慣例に合わせ AGPL-3.0-or-later。
- `80-data/` 配下にアーカイブする第三者ToS本文の著作権は当該企業に帰属したまま
  であることを NOTICE ファイルに明記し、本プロジェクトの著作物として主張しない
  （出典URL・取得日時によるプロベナンス記録が根拠）。
- README冒頭に「本リポジトリは独立した第三者による分析/アーカイブであり、
  対象企業と提携・後援関係にない」旨の明記を必須とする。

### 5. 可視性

- public（本セッションでのオーナー判断。CLAUDE.md既定の "gftdcojp=private" は
  `cloud-itonami` orgそのものには適用されず、既存 isic/isco/gtin/M6910 が
  すべて public/AGPL であることとも整合する）。

### 6. 対象企業の選定

- 本ADRでは特定のパイロット企業を選定しない（パターン設計のみ）。
  最初の1社の実装（LEI照会→取得→journal作成→リポジトリ作成→push）は
  別途の実装ステップとする——ADR-2607072300 自身も「パイロットactor未選定」を
  未決事項として残した前例に倣う。

### 7. ロボティクス前提

- 純粋なデータ/ドキュメントサービスのため、`:itonami.blueprint/robotics false`
  （`cloud-itonami-6310`/`cloud-itonami-gtin-*` と同じ除外クラス）。

## Consequences

- (+) 「実在企業ごとに一意なリポジトリキー」を、ADR-2607031800 が残した
  GTINの不整合を蒸し返さずに解決する（LEIは法人を指すグローバル標準であり、
  既に社内実装がある）。
- (+) 全文保存＋出典/取得日時/sha256のプロベナンスにより、企業のToS改定を
  git履歴で追跡できるという新しい付加価値が生まれる。
- (+) 既存の cloud-itonami-* 慣例（public/AGPL/west非管理/ロボティクス除外）に
  そのまま乗るため、4つ目の異質な構造を作らずに済む。
- (-) kotobase.netへのingestion job は依然未実装（ADR-2607072300が既に残した
  ギャップのまま）。
- (-) 法的リスクはkawaraban型の報道コンテンツより低いが、ゼロではない
  （第三者の契約文書の全文複製である点は変わらない）。出典明記＋非提携明記で
  緩和するが排除はしない。
- (-) パイロット企業が未選定のため、このパターンは最初の実リポジトリが
  end-to-endで作られるまで実証されていない。

## Alternatives considered

- **GTINキー方式（`cloud-itonami-gtin-<code>`）** — 却下。ADR-2607031800が
  既に「商品識別子であり法人を指さない」として同じ理由で却下済み。
- **証券コード（ticker）キー方式** — 却下。取引所ごとに独自の採番であり
  グローバルに一様でない（非上場の子会社等はコードを持たない場合がある）のに対し、
  LEIは上場有無に関わらず法人を一意に指すISO単一標準であるため。
- **kawaraban型の要約/抜粋のみ保存** — 却下。ToSは全文が遵守対象の契約書であり、
  要約すると実際の条項（コンプライアンス上重要な文言）が失われる。報道コンテンツの
  著作権配慮とは前提が異なる。
- **etzhayyim または kotoba-lang への配置** — 却下。etzhayyimは非営利・自己主権の
  宗教法人レイヤーで商業競合分析の管轄外、kotoba-langは言語/インフラ層で
  分析対象の企業コンテンツを置く場所ではない。

## References

- ADR-2607031800（`cloud-itonami-gtin-functional-blueprints`）— GTINをこの用途の
  キーとして却下した先例。
- ADR-2607031500（`cloud-itonami-M6910`）— LEI/MOD-97-10実装（matsurigoto由来）の
  先例。
- ADR-2607072300（`actor-public-data-git-journal-kotobase-index`）— 本ファミリーが
  再利用するjournal/kotobase.netレイヤリング。
- ADR-2607110200（kawaraban R0→R1 / isco-3521）— 要約のみ保存する報道コンテンツとの
  対比事例。
