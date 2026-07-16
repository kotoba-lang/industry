# ADR-2607141700: cloud-itonami — 国/自治体/業界団体ルールの compliance-fact 連邦化(datom + DataScript)

**Status**: accepted(設計 + Wave 0 のみ確定。Wave 0 の repo scaffold は本 ADR ではまだ実行しない — 次 action として残す)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki（+ Claude、オーナー承認のうえ設計）
**Scope**: `orgs/cloud-itonami/cloud-itonami-iso3166-<国>`(既存68repo、スコープ拡張)、
`orgs/cloud-itonami/cloud-itonami-municipality-*`(新設予定)、
`orgs/cloud-itonami/cloud-itonami-assoc-*`(新設予定)

## Context

オーナー依頼: 国の法案・自治体のルール・業界団体のルールを満たしているかを、
`cloud-itonami` 配下で組織ごとに repo 化し、Datomic/DataScript で query できる
ようにしたい。ADR-2607141600(自社 identity/activity 記録の置き場所)の続きとして、
「第三者組織の compliance 状態」を扱う軸を新設する。

### 監査（2026-07-14、3並列 agent で実施）: 既存資産の棚卸し

| 層 | 場所 | 中身 | 状態 |
|---|---|---|---|
| WHO(政府/団体の実在構造) | `etzhayyim/root/20-actors/ooyake`(実 repo は `etzhayyim/com-etzhayyim-ooyake`) | 世界政府 atlas。supranational→国→region→prefecture→municipality→省庁→局→窓口。~6,535 `:gov.unit` / ~190 法域、EAVT schema(`gov-atlas-ontology.kotoba.edn`)。**`registry/gov-units.capitals.edn` に世界各国の首都(実データ、Wikidata provenance 付き、約190件)がすでに存在** | R1(実データ検証済み) |
| WHAT(法源の在り処) | `etzhayyim/global-legislation-datoms` | 法令/判例/条約の一次情報 URL・license・provenance カタログ(全文非保持)。DataScript 実装済み(`data/datascript-tx.edn`)、`legislature`/`court`/`jurisdiction` は ooyake から機械的にミラー | 34 件厳選、稼働中 |
| 市場参入 compliance | `kotoba-lang/iso3166` + `cloud-itonami-iso3166-<国>`(188repo 中 68 が `:implemented`) | Compliance-Advisor LLM ⊣ Governor actor。ただし**調達/法人設立の登録要件限定**(`marketentry.facts/catalog`: owner-authority/legal-basis/national-spec/provenance/required-evidence) | 68/188 実装 |
| 法人 compliance 台帳 | `cloud-itonami-isic-8291` | Dossier-LLM ⊣ DisclosureGovernor。法人の公開事実(役員・UBO・公職者)台帳。12 業種(6910/6810/6499/6512/6419/6420/6621/6622/6511/6612/6920/6411)に接続済み | 実装済み |
| 再利用可能 fact primitive | `kotoba-lang/datom` | 軽量・依存ゼロの Datomic 同型 EAVT(`entity`/`eavt`/`log`)。複数 actor が共有 | 実装済み |
| ISIC vertical(業種) | `cloud-itonami-isic-*` | 646 registry entries 中 **119 が `:implemented`(実 `src/` あり)** | 119/646 実装 |
| 横断 query 層 | `net-kotobase`(分散 storage mesh 設計) | prolly-tree/IPLD で cross-repo `d/q` を目指す設計(ADR-2607051410) | **L0/L1 のみ、未接続** |

**欠落**: 自治体(municipality)単位の repo、業界団体(業態団体)単位の repo は
概念ごと存在しない。「組織 X が法規/条例/会則 Y を満たすか」を直接 query できる
fact 体系はどこにもない(iso3166 は「登録に何が要るか」止まり、8291 は
「その会社の実体は何か」止まり)。

### オーナー決定(本セッション、AskUserQuestion 経由)

1. **自治体 pilot 範囲**: 複数国の主要都市も含める(最初からグローバルスコープ)。
2. **業界団体 pilot 範囲**: `cloud-itonami` で既に実装済みの ISIC 業種に合わせる。
3. **国レベル設計**: 既存 `cloud-itonami-iso3166-<国>` を一般法令 compliance 用に
   スコープ拡張する(別ファミリーを新設しない)。

## Decision

3 軸(国/自治体/業界団体)× 3 層(WHO/WHAT/compliance-fact)を、既存資産の
最大再利用で構成する。

### 1. 国レベル: 既存 `cloud-itonami-iso3166-<国>` をスコープ拡張

`marketentry.facts` 名前空間はそのまま維持し(既存 68repo の意味を壊さない)、
同一 repo に **`statute.facts`** 名前空間を追加する:

```
:statute/id, :statute/jurisdiction, :statute/title, :statute/url,
:statute/url-provenance, :statute/license, :statute/effective-date,
:statute/supersedes, :statute/kind(:law/:regulation/:ordinance-national)
```

`global-legislation-datoms` の34件厳選パターン(全文非保持・出典必須)をそのまま
踏襲。`etzhayyim/global-legislation-datoms` の `:legal-source/jurisdiction` と
`iso3166` の国コードは既に同じ ISO 3166-1 alpha-3 キーなので、**join は追加設計
不要**——`global-legislation-datoms` を国レベルの WHAT 正本として参照し、
`cloud-itonami-iso3166-<国>` 側は「その法源に対して当該国内の対象組織が
満たすべき義務」だけを compliance-fact として持つ。

### 2. 自治体レベル: `cloud-itonami-municipality-<国コード>-<都市slug>`(新設)

- **WHO 源泉**: 新規に atlas を作らず、`ooyake` の
  `registry/gov-units.capitals.edn`(実データ、Wikidata provenance)を正本として
  参照する。ADR-2607141600 と同じ規律で「実データがある範囲でしか repo を作らない」。
- **WHAT/compliance-fact スキーマ**: 国レベルと同型(`:ordinance/*`
  namespace、`global-legislation-datoms` パターンの複製)。
- **actor 形**: `cloud-itonami-iso3166-<国>` と同じ Compliance-Advisor ⊣
  Governor スケルトンを複製(fail-closed、`:compliance-fact/status` は
  Governor 承認なしに `:met` を確定しない)。

### 3. 業界団体レベル: `cloud-itonami-assoc-<ISICコード>-<国コード>-<団体slug>`(新設)

- スコープは **cloud-itonami で既に `:implemented` の119 ISIC vertical** に
  1:1 対応させる(オーナー決定どおり、業種を勝手に広げない)。
- 団体名・会則の実在確認(LEI/legislation-datoms と同じ捏造禁止規律)は
  **repo 作成時に個別に行う**——本 ADR では pilot 業種の選定のみ確定し、
  具体的な団体名は fabricate しない。
- `:association-rule/*` namespace、`global-legislation-datoms` と同型。

### 4. データ層・query 層

- 各 repo のローカル store は `kotoba-lang/datom` の EAVT fact を
  `global-legislation-datoms` と同じ `data/datascript-tx.edn`(DataScript
  `db-with` 直結可能な tx 形式)として保存する。
- cross-repo 横断 query は `net-kotobase`(ADR-2607051410)が L0/L1 止まりで
  未接続のため、**当面は複数 repo の `datascript-tx.edn` をローカルで読み込み
  `d/q` union する薄い federation スクリプト**(新規、`kotoba-lang` 側に
  reusable な形で置く)を暫定解とする。`net-kotobase` が進展したら、そちらへ
  移行する（本 ADR は移行を義務付けない、将来の選択肢として残すのみ）。

### 5. Wave 0（実データに根拠づけた最初のバッチ、repo 未作成・次 action）

**自治体 Wave 0**（`ooyake` capitals.edn に実データがあり、かつ iso3166 が
`:implemented` な国の首都から、地域分散させて6件）:

| 国 | 都市(ooyake実データ) | repo 名(予定) |
|---|---|---|
| JPN | Tokyo | `cloud-itonami-municipality-jpn-tokyo` |
| USA | Washington, D.C. | `cloud-itonami-municipality-usa-washington-dc` |
| GBR | London | `cloud-itonami-municipality-gbr-london` |
| DEU | Berlin | `cloud-itonami-municipality-deu-berlin` |
| SGP | Singapore | `cloud-itonami-municipality-sgp-singapore` |
| ZAF | (ooyake の P36 capital 値をそのまま採用、要確認) | `cloud-itonami-municipality-zaf-<capital>` |

**業界団体 Wave 0**（`cloud-itonami-isic-8291` に既に接続済みの12業種、
= 最も compliance 文脈が確立している vertical）:

`6910`(法務) `6810`(不動産) `6499`(VCファンド) `6512`(保険) `6419`(銀行)
`6420`(持株会社) `6621`(保険代理) `6622`(保険ブローカー) `6511`(生保)
`6612`(証券仲介) `6920`(会計・税務) `6411`(中央銀行)

各コードにつき実在団体1件を repo 作成時に特定・引用する(本 ADR では特定しない)。

残りの自治体(iso3166 `:implemented` 68 国 × capitals.edn 全件)・業界団体
(119 vertical 全件)は Wave 1 以降として、既存の wave rollout 慣例
(ADR-2607121000)に揃える。

## Addendum (2026-07-14, /loop tick 1 — 国レベル第一実装 + query 層の実接続)

国レベル(§1)を `cloud-itonami-iso3166-jpn`(`09f9fd3`)に実装・push 済み:
`src/statute/facts.cljc` に実在3法令(会社法 417AC0000000086・個人情報保護法
415AC0000000057・労働基準法 322AC0000000049、いずれも e-Gov 法令検索の実
lawid、WebSearch で複数ソース照合済み)、`schema/statute.edn` +
`data/datascript-tx.edn`、テスト4件(`clojure -M:test`: 33 tests/108
assertions green、既存 marketentry テストに影響なし)。

cross-repo query 層(§4)は当初案の「kotoba-lang 側に置く」を訂正し、
`scripts/compliance-fact-query.cljs`(このsuperproject直下、nbb)として実装
——理由: 既存の `scripts/labor-liberation-sd.cljs` / `manifest/edn-query.cljs`
が同じ「複数 EDN ソースを npm `datascript` にロードして `d/q` する」パターンを
既にこの階層で確立していたため、それに揃えた(kotoba-lang は OSS ライブラリ層で
あり、このsuperproject固有のcross-repo集約は tooling 層である `scripts/` が
適切)。実行確認:

```
$ nbb scripts/compliance-fact-query.cljs count
3	cloud-itonami-iso3166-jpn statute.facts
567	etzhayyim/global-legislation-datoms legal-source
570	TOTAL

$ nbb scripts/compliance-fact-query.cljs jurisdiction JPN
== JPN (statute.facts) ==
  jpn.companies-act  会社法 (Companies Act)  <https://laws.e-gov.go.jp/law/417AC0000000086>
  ...
== JPN (legal-source) ==
  e-Gov 法令 API  <https://laws.e-gov.go.jp/api/1/>
  ...
```

2つの別リポジトリ(cloud-itonami org / etzhayyim org)由来のfactを1つの
DataScript dbに union し、`d/q` で横断queryできることを実データで確認した
——これが本ADRの核心である「datomic/datascriptでqueryできるように」の
最初の実証。次tickは自治体Wave 0(§Wave 0表)へ進む。

共有 checkout 上で pre-existing な未コミット WIP(`blueprint.edn`/
`organization.edn`、自分のものではない)を発見。破棄せず
`git stash push -- blueprint.edn organization.edn`で退避のうえ
`origin/main` に fast-forward 同期してから本追加を commit した
(stash は温存、元セッションの棚卸し待ち)。

**push blocker**: superproject 側(`chore/pin-kototama-fence-gated`)の push は
既存(自分とは無関係)の `west.yml` pin 退行で PreToolUse hook にブロックされた
——数十リポジトリが behind 判定。原因調査・修正は本 ADR のスコープ外(大規模かつ
無関係)。ADR/scripts の commit はローカルに温存し push は保留。子リポ
(`cloud-itonami-iso3166-jpn`)は non-west standalone のため無関係に push 成功。

## Addendum (2026-07-14, /loop tick 2 — 自治体 Wave 0 第一実装 + 実データ不整合の発見)

`cloud-itonami-municipality-jpn-tokyo` を新規 scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-jpn-tokyo)):
`src/ordinance/facts.cljc` に実在2条例(東京都情報公開条例 条例第五号
1999-03-19・個人情報の保護に関する法律施行条例 条例第一三〇号 2022-12-22、
いずれも reiki.metro.tokyo.lg.jp の実ページを WebFetch で直接照合し
タイトル/番号/日付を読み戻して検証済み)、`schema/ordinance.edn` +
`data/datascript-tx.edn`、テスト4件(`clojure -M:test`: 4 tests/11
assertions green、clj-kondo 0/0)。`scripts/compliance-fact-query.cljs`
の `SOURCES` に追加し `municipality <slug>` コマンドを新設。

**実データ不整合を発見・修正**: `cloud-itonami-iso3166-jpn/organization.edn`
の `:hq` が `com-etzhayyim-ooyake` の `gov-units.capitals.edn` 由来で
"Shigaraki Palace"(Q262438、日本の8世紀の都、742-745年)になっていた
——Wikidata の Japan(Q17)項目が複数の歴史的 P36(capital)claim を持ち、
ingestion が現在の首都でないものを拾ったバグ。両QIDをWikidataで直接
fetch・確認したうえで、この repo の `organization.edn` のみ Tokyo(Q1490)に
修正・push([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-jpn/commit/15252d7))。
**ooyake 本体(`etzhayyim/com-etzhayyim-ooyake`)のバグ自体は未修正** ——
日本だけの問題か、190か国中の他の国にも同型のP36 ingestion バグが
あるか未調査。R1(実データ検証済み)を称するデータセットで実在した
不整合であり、別セッションでの調査を推奨(本ADR/tickのスコープ外)。

**query script のバグを発見・修正**: `merged-schema` を素の `clj->js` で
JS化していたため、`:db/cardinality :db.cardinality/many` のような
schema config 値のkeywordが namespace を落として不正な形になり、
cardinality-many属性(`:statute/topic`/`:ordinance/topic`)がDataScript内で
展開されず単一の opaque array datom になっていた(=topic横断queryが
常に空を返す不具合)。実験で判明: npm `datascript` のJS wrapperは
schema **値**側のkeywordを **コロン付き文字列**("`:db.cardinality/many`")
として要求する(entity属性名・schemaキー自体は従来通りコロン無し裸文字列)。
`schema->js`/`schema-entry->js`/`->ds-schema-value` を追加して修正、
`q '[:find ?title :where [?e "ordinance/topic" "data-protection"] ...]'`
がJPN法令・Tokyo条例の両方から正しく結果を返すことを確認。

3リポジトリ(cloud-itonami-iso3166-jpn / global-legislation-datoms /
cloud-itonami-municipality-jpn-tokyo)、計572 factを1つのDataScript dbで
union、cardinality-many属性を含めて正しくqueryできることを実証。
次tickはWave 0の残り(USA/Washington D.C.、GBR/London 等)へ進む。

## Consequences

- (+) 3 軸すべてが既存の実データ(ooyake capitals/gov-units、118 実装済み ISIC、
  68 実装済み iso3166 国)を土台にでき、fabricate せずに具体的な Wave 0 を
  定義できた。
- (+) 国レベルは新ファミリーを作らず既存68repoの拡張で済むため、影響範囲を
  最小化しつつ一般法令 compliance に対応できる。
- (+) `global-legislation-datoms` の schema/provenance 規律をそのまま複製する
  ため、3つ目の異質な構造を発明していない。
- (−) cross-repo 横断 query は `net-kotobase` 未接続のため、当面は暫定 federation
  script で代替する(本格的な分散 query ではない)。
- (−) 自治体・業界団体とも、Wave 0 の6件・12件以降(自治体は数百〜数千規模、
  業界団体は119業種相当)は本 ADR ではスコープ外——Wave 1 以降で個別に
  実データ・実団体名を確認しながら拡張する。
- (−) 業界団体の実団体名・会則の実在確認は repo 作成時の個別作業として残る
  （本 ADR は「どの ISIC コードに対応させるか」の割当てのみ確定）。

## Alternatives considered

- **自治体・業界団体を1つの汎用 `cloud-itonami-org-registry` repo に集約** —
  却下。`cloud-itonami-iso3166-<国>`/`cloud-itonami-isic-<code>` は既に
  「1 repo = 1 実体」の慣例が確立しており、集約すると個別組織の可視性・
  ライフサイクル・governor 権限を分離できなくなる。
- **国レベルも別ファミリーとして新設** — 却下(オーナー決定)。既存68repoの
  「調達 compliance」との意味の重複が生まれず、1 repo に集約できる利点を優先。
- **Wave 0 をゼロから都市/団体を選定** — 却下。ooyake の実データ
  (capitals.edn)と、既に isic-8291 に接続済みの12業種という「既に実在確認・
  実装済み」の土台があるため、そこを再利用する方が捏造リスクがなく確実。

## Addendum (2026-07-14, /loop tick 3 — 業界団体 Wave 0 第一実装)

`cloud-itonami-assoc-6419-jpn-zenginkyo` を新規 scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-jpn-zenginkyo)):
一般社団法人全国銀行協会(Japanese Bankers Association、Wikidata Q11389610)。
`src/association/facts.cljc` に実在2件の自主規制ルール(Code of Conduct、
2005-11-22制定・2022-09-15最終改定・zenginkyo.or.jp/en/conduct/で改定履歴を
WebFetch照合済み／申し合わせ、zenginkyo.or.jp/agreement/)、
`schema/association-rule.edn` + `data/datascript-tx.edn`、テスト4件
(`clojure -M:test`: 4 tests/11 assertions green、clj-kondo 0/0)。
ISIC 6419(銀行、cloud-itonami-isic-8291接続済み12業種の1つ)に対応させた
——「既にcloud-itonamiで実装済みのISIC業種に合わせる」というowner決定
どおり、かつJPN(tick 1)と同じ法域で揃え、横断query結果に意味を持たせた。

`scripts/compliance-fact-query.cljs` の `SOURCES` に追加し
`association <slug>` コマンドを新設。4リポジトリ・574 factを1つの
DataScript dbでunionし、`association-rule/topic`(consumer-protection)
での横断queryが正しく1件(zenginkyo.agreements)を返すことを確認——
tick 2で修正した cardinality-many バグの再発なしを実証。

これで国(JPN statute)・自治体(Tokyo ordinance)・業界団体(zenginkyo
association-rule)の3軸すべてに実データが揃った。次tickは各軸の
2件目(例: Wave 0 municipality の USA/Washington D.C., 6511/6511生保等
zenginkyo以外の業種)に進む。

## References

- ADR-2607141600（`cloud-itonami-real-entity-record-placement` — 自社
  identity/activity 記録の置き場所、本 ADR の直前の決定）
- ADR-2607110300（`cloud-itonami-lei-corporate-tos-catalog` — LEI キー・
  捏造禁止規律の初出）
- ADR-2607032330（`cloud-itonami-iso3166-market-entry-compliance-blueprints`
  — iso3166 ファミリーの founding ADR）
- ADR-2607110400（`cloud-itonami-isic-8291` — compliance-intelligence dossier
  actor、12 vertical 接続）
- ADR-2607121000（`cloud-itonami` 全世界展開 5-wave rollout 計画）
- ADR-2607051410（`net-kotobase-distributed-storage-mesh-design` — cross-repo
  query の将来経路、L0/L1 止まり）
- `orgs/etzhayyim/root/90-docs/adr/2606021600-ooyake-world-government-atlas-tier-b-actor-r0.md`
  （ooyake 自体の設計、etzhayyim/root 側の ADR）
