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

## Addendum (2026-07-14, /loop tick 4 — 国軸2件目(USA) + worktree隔離への移行)

`cloud-itonami-iso3166-usa` に `statute.facts` を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-usa/commit/98a83b6)):
実在3法令(Sarbanes-Oxley Act of 2002/15 U.S.C. Ch.98・FTC Act Section 5/
15 U.S.C. §45・Fair Labor Standards Act of 1938/29 U.S.C. Ch.8、いずれも
uscode.house.gov(下院法制局)の公式URLを複数の独立ソース(Cornell LII、
govinfo.gov、DOL.gov、Justia)で照合済み——uscode.house.gov自体への直接
WebFetchはconnection refusedで失敗したため、tick 1のe-Gov同様、複数
独立ソースの相互照合で代替)。28 tests/90 assertions green。
`statute/topic`横断query(`:labor`)がJPN(労働基準法)・USA(FLSA)の両方を
正しく返すことを確認——3リポジトリ以上に拡張しても cardinality-many
queryが機能し続けることを実証。

5リポジトリ・577 factが1つのDataScript dbに統合。

**worktree隔離へ移行**: 本tick開始時、`chore/pin-kototama-fence-gated`
ブランチ(前3 tickの作業ブランチ)が、私が触っていない間に`main`へ
切り替わっている事象を発見(reflogで確認: 他の並行セッションによる
`git checkout main`)。commit自体は無事だったが、CLAUDE.mdが警告する
「共有checkoutでの並行セッションによるブランチ切替」が実際に発生した
実例。これ以降、superproject側(90-docs/adr・scripts/)の編集は
`git worktree add`でsuperproject外(`/tmp/root-compliance-fact-federation`)
に切った専用worktree(`loop-compliance-fact-federation`ブランチ、
`chore/pin-kototama-fence-gated`のtipから分岐)で行う。子リポ
(`orgs/cloud-itonami/*`)側の編集は従来どおり共有checkoutパスで行うが、
これはstandalone plain-gitであり今回のbranch-switch問題とは無関係。

## Addendum (2026-07-14, /loop tick 5 — 業界団体軸2件目(sonpo))

`cloud-itonami-assoc-6512-jpn-sonpo` を新規 scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6512-jpn-sonpo)):
一般社団法人日本損害保険協会(GIAJ、Wikidata Q11508145、設立1946-01-18)。
実在2件の自主規制ルール(行動規範: 制定1991-10-17・最終改定2024-03-21／
独占禁止法遵守のための指針: 2026年1月)——今回は検索スニペットや
ページタイトルだけでなく、**実際にPDF本文をReadツールで読み込み**、
表紙に印字された制定・改定日付そのものを確認するという、これまでで
最も厳密な検証を行った。ISIC 6512(損害保険、cloud-itonami-isic-8291
接続済み12業種の1つ)に対応。4 tests/11 assertions green、clj-kondo 0/0。

`scripts/compliance-fact-query.cljs`の`SOURCES`に追加。6リポジトリ・
579 factを統合。`association-rule/isic`での横断query(zenginkyo=6419・
sonpo=6512)が両協会のルールを正しく返すことを確認——業界団体軸内での
複数団体比較が機能することを実証。

## Addendum (2026-07-14, /loop tick 6 — 国軸3件目(GBR))

`cloud-itonami-iso3166-gbr`に`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-gbr/commit/885408c)):
実在3法令(Companies Act 2006/2006 c.46・Data Protection Act 2018/
2018 c.12・Employment Rights Act 1996/1996 c.18、いずれも
legislation.gov.ukを直接WebFetchでタイトル・chapter番号を照合——
e-Gov・uscode.house.govと違い正常にレンダリングされ、これまでで
最も直接検証がしやすかった)。28 tests/90 assertions green。

7リポジトリ・582 factを統合。`:labor`トピックでJPN・USA・GBRの
3か国横断queryが正しく機能することを確認——2か国から3か国への
拡張でも federation の仕組みがそのまま機能することを実証。

現状: 国軸(JPN/USA/GBR、3 entries)・業界団体軸(zenginkyo/sonpo、
2 entries)・自治体軸(Tokyoのみ、1 entry)。

## Addendum (2026-07-14, /loop tick 7 — 業界団体軸3件目(JSDA) + worktree再作成の記録)

`cloud-itonami-assoc-6612-jpn-jsda`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6612-jpn-jsda)):
日本証券業協会(JSDA、Wikidata Q11509109、設立1973-07-01)。実在2件の
自主規制文書(定款／協会員の投資勧誘、顧客管理等に関する規則: 制定
1975-02-19・最終改定2026-06-16)、いずれもjsda.or.jpをWebFetchで
直接照合。ISIC 6612(証券仲介)に対応。4 tests/11 assertions green。

途中、米UK Finance(ukfinance.org.uk)・米ABA(aba.com)の2団体を
候補として調査したが、いずれもWebFetchが403 Forbiddenで拒否され
(bot対策と推測)、内容を直接検証できなかったため見送った——
.or.jp系サイト(zenginkyo/sonpo/jsda)は今のところ全て問題なく
fetchできている一方、米国の商業団体.com/.org.ukサイトは検証が
難しい傾向が見えてきた。次回以降、米国側の業界団体を追加する際は
別の検証経路(一次資料PDFの直接URLなど)を探す必要がある。

`scripts/compliance-fact-query.cljs`に追加。8リポジトリ・584 factを
統合。association×isicの集計queryが3団体(zenginkyo/6419・sonpo/6512・
jsda/6612)を正しく返すことを確認。

**worktree再作成**: 本tick開始時、`/tmp/root-compliance-fact-federation`
自体が(おそらくOSの/tmp定期clearにより)消失していたが、
`loop-compliance-fact-federation`ブランチ自体はローカルref・
リモート(tick 6でpush済み)の両方に無事残っており、データ損失なし。
`git worktree add`で同じtipから再作成して続行した——ブランチ切替
問題(tick 4)とは別の、worktreeという仕組み自体の一時ファイル性に
起因する事象。

## Addendum (2026-07-14, /loop tick 8 — 国軸4件目(DEU) + ooyakeバグの2件目確認)

`cloud-itonami-iso3166-deu`に`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-deu/commit/bf8c4f9)):
実在3法令(Aktiengesetz株式法・Bundesdatenschutzgesetz連邦データ保護法・
Kündigungsschutzgesetz解雇制限法、いずれもgesetze-im-internet.de
(連邦法務省)を直接WebFetchで照合——legislation.gov.ukと同様、
問題なくレンダリングされた)。28 tests/90 assertions green。

**ooyakeのP36バグ、2件目を確認・修正**: `cloud-itonami-iso3166-deu/
organization.edn`の`:hq`が"Bonn"(西ドイツ時代の首都、Q586)になって
いた——JPNの"Shigaraki Palace"(tick 4)と全く同型のバグ。Wikidataで
直接照合しBerlin(Q64、統一後1990-10-03の首都)に修正・push。
これでJPN・DEUの2か国で同型バグを確認したことになり、**ooyake本体の
capitals.eduが系統的な問題を抱えている可能性が高まった**(首都が
歴史的に変遷した国では同じ不具合が起きやすいと推測)。ooyake本体の
修正は引き続きスコープ外——検出のたびにこのADRで記録し、まとまった
件数になったらまとめて報告する方針とする。

9リポジトリ・587 factを統合。`:corporate-governance`トピックで
JPN・USA・GBR・DEUの4か国横断queryが正しく機能することを確認。

## Addendum (2026-07-14, /loop tick 9 — 国軸5件目(FRA))

`cloud-itonami-iso3166-fra`に`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-fra/commit/fa68ff6)):
実在3法令(Code de commerce商法典・Loi n° 78-17情報自由法(1978)・
Code du travail労働法典)、legifrance.gouv.frを引用。Code de commerceと
Loi 78-17は直接WebFetch照合、Code du travailはページが大きすぎて
(約3000頁)WebFetchのcontent-size上限を超過したため、同一ドメイン+
複数独立ソースでの相互照合(tick 1のe-Gov・tick 4のuscode.house.govと
同水準の確度)に留めた。28 tests/90 assertions green。

10リポジトリ・590 factを統合。`:data-protection`トピックでJPN・USA・
GBR・DEU・FRAの5か国横断queryが正しく機能することを確認。

現状: 国軸5件・業界団体軸3件・自治体軸1件(Tokyoのみ、引き続き最も薄い)。

## Addendum (2026-07-14, /loop tick 10 — 業界団体軸4件目(Bankenverband) — 初の非JPN団体)

`cloud-itonami-assoc-6419-deu-bankenverband`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-deu-bankenverband)):
Bundesverband deutscher Banken(BdB、Wikidata Q1009084、設立1951年)。
実在2件の文書(Statutes: 2024年4月改定・By-laws of the Deposit
Protection Fund: 2023年10月、後者はPDF本文をReadツールで直接確認)。
**意図的にISIC 6419(銀行業)——zenginkyo(JPN)と同じコード**を選び、
同一業種の国際比較を可能にした(業種を広げるのではなく、既存軸を
深める方向)。4 tests/11 assertions green。

11リポジトリ・592 factを統合。同一ISIC(6419)での国横断query
(`[:find ?country ?assoc ?title :where [?e "association-rule/isic"
"6419"] ...]`)がzenginkyo(JPN)とbankenverband(DEU)の両方を
正しく返すことを確認——業界団体軸で初めて「同業種を複数国で比較する」
という、当初のADRが目指していたユースケースを実証できた。

現状: 国軸5件・業界団体軸4件(JPN×3・DEU×1)・自治体軸1件
(Tokyoのみ、依然として最も薄い)。

## Addendum (2026-07-14, /loop tick 11 — 国軸6件目(CAN))

`cloud-itonami-iso3166-can`に`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-can/commit/c296be4)):
実在3法令(Canada Business Corporations Act・PIPEDA(個人情報保護)・
Canada Labour Code)、laws-lois.justice.gc.caを直接WebFetchで照合。
28 tests/90 assertions green。

シンガポール(sso.agc.gov.sg)を先に検討したが3候補URLすべてが
403 Forbiddenで拒否されたため見送り、カナダに切り替えた——
米系・シンガポール系の一部公式サイトでbot対策に阻まれる一方、
英連邦圏でもカナダの司法省サイトは問題なく機能した。

12リポジトリ・595 factを統合。`:labor`トピックでJPN・USA・GBR・DEU・
FRA・CANの6か国横断queryが正しく機能することを確認。

現状: 国軸6件・業界団体軸4件・自治体軸1件(Tokyoのみ、依然として
突出して薄い軸)。

## Addendum (2026-07-14, /loop tick 12 — 業界団体軸5件目(FINRA) — 米サイト初のfetch成功)

`cloud-itonami-assoc-6612-usa-finra`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6612-usa-finra)):
FINRA(Financial Industry Regulatory Authority、Wikidata Q387071、
現組織としての成立2007-07-26)。実在2件の文書(FINRA Rules/Manual・
By-Laws of the Corporation)、finra.orgを直接WebFetchで照合——
**このADR作業で初めて成功した米国業界団体サイト**(tick 7のABA・
tick 10のUK Financeはいずれも403で断念)。FINRAはSEC登録の
self-regulatory organizationであり純粋な業界団体(trade association)
ではなく準公的な位置づけであることが、fetch成功の違いに寄与した
可能性がある。4 tests/11 assertions green。

**意図的にISIC 6612(証券仲介)——jsda(JPN)と同じコード**を選び、
tick 10のzenginkyo/bankenverband(6419)に続く2件目の「同一業種
国際比較」ペアを作った。13リポジトリ・597 factを統合。ISIC 6612での
国横断queryがjsda(JPN)とfinra(USA)の両方を正しく返すことを確認。

現状: 国軸6件・業界団体軸5件(JPN×3・DEU×1・USA×1、うち2業種は
2か国で比較可能)・自治体軸1件(Tokyoのみ、依然として突出して薄い)。

## Addendum (2026-07-14, /loop tick 13 — 業界団体軸6件目(NAIC) — 3ペア目の同業種国際比較が完成)

`cloud-itonami-assoc-6512-usa-naic`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6512-usa-naic))：
NAIC(National Association of Insurance Commissioners、Wikidata
Q6970687、1871年設立)。実在2件(Model Laws - About・Unfair Trade
Practices Act (Model #880、Spring 2024版)、後者はPDF本文をReadツールで
直接確認)。4 tests/11 assertions green。

**NAICの性質を誤魔化さず記録**: NAICはFINRAのような直接的な
self-regulatory organizationではなく、州保険監督官で構成される
非政府の標準策定団体で、各州が任意採用する「モデル法」を発行する。
既存の`:self-regulatory-code`を使い回さず`:model-law`/
`:governance-program`という別kindを用意して区別した。

**意図的にsonpo(JPN)と同じISIC 6512(保険)**を選び、6419
(zenginkyo/bankenverband)・6612(jsda/finra)に続く**3組目の
同業種国際比較ペア**を完成させた——業界団体軸で使っている全3業種が
JPN+他国の両方でカバーされたことになる。14リポジトリ・599 factを
統合。

現状: 国軸6件・業界団体軸6件(3業種×2か国のペアが3組完成)・
自治体軸1件(Tokyoのみ)——今後の最も明確なギャップは自治体軸。

## Addendum (2026-07-15, /loop tick 14 — 国軸7件目(AUS))

`cloud-itonami-iso3166-aus`に`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-aus/commit/f83bdad)):
実在3法令(Corporations Act 2001・Privacy Act 1988・Fair Work Act
2009)、legislation.gov.au(Federal Register of Legislation)を直接
WebFetchで照合。28 tests/90 assertions green。

15リポジトリ・602 factを統合。`:corporate-governance`トピックで
JPN・USA・GBR・DEU・FRA・CAN・AUSの7か国横断queryが正しく機能する
ことを確認。

現状: 国軸7件・業界団体軸6件・自治体軸1件(Tokyoのみ)——14tickを
経て自治体軸が圧倒的に薄いままであることが明確になっている。

## Addendum (2026-07-15, /loop tick 15 — 業界団体軸7件目(JICPA) — 新規ISIC業種)

`cloud-itonami-assoc-6920-jpn-jicpa`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6920-jpn-jicpa)):
日本公認会計士協会(JICPA、Wikidata Q6158230、1949年設立)。実在2件
(倫理規則: 制定1966-12-01・最終改正2019-07-22、PDF本文をReadツールで
直接確認／自主規制の取り組み概要ページ)。4 tests/11 assertions green。

**新規ISIC業種**: これまでの6419(銀行)・6512(保険)・6612(証券)に
続き、初めてISIC 6920(会計・税務・監査)を追加——まだ他国とのペアは
無く、将来的な同業種国際比較の候補として残る。

16リポジトリ・604 factを統合。7団体すべてを(isic, country,
association)で集計するqueryが正しく機能することを確認。

現状: 国軸7件・業界団体軸7件(4業種×3か国)・自治体軸1件(Tokyoのみ)
——15tickを経て自治体軸が最も明確なギャップであり続けている。

## Addendum (2026-07-15, /loop tick 16 — 国軸8件目(KOR))

`cloud-itonami-iso3166-kor`に`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-kor/commit/84987cc)):
実在3法令(商法・個人情報保護法・労働基準法)、韓国法制研究院(KLRI)の
公式英訳ポータル(elaw.klri.re.kr)を直接WebFetchで照合。KLRI自身が
「この英訳は参考訳であり法的正本ではない(正本はlaw.go.kr)」と明記
しているため、`:official-klri-reference-translation`という専用の
provenance値で正直に記録し、英訳自体を法的正本であるかのように
扱わなかった(シンガポール調査時のsso.agc.gov.sg 403断念を踏まえ、
今回は英語公式ソースが存在する韓国を選定)。28 tests/90 assertions
green。

17リポジトリ・607 factを統合。`:data-protection`トピックでJPN・USA・
GBR・DEU・FRA・CAN・AUS・KORの8か国横断queryが正しく機能することを
確認。

現状: 国軸8件・業界団体軸7件・自治体軸1件(Tokyoのみ)——16tickを
経てなお自治体軸が突出して薄く、今後取り組むべき明確な対象。

## Addendum (2026-07-15, /loop tick 17 — 業界団体軸8件目(AICPA) — 4組目の同業種ペアが完成)

`cloud-itonami-assoc-6920-usa-aicpa`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6920-usa-aicpa))：
AICPA(American Institute of Certified Public Accountants、Wikidata
Q465177、1887年設立)。実在2件(Code of Professional Conduct: 発効
2014-12-15・2025年12月まで更新、PDF本文をReadツールで直接確認／
AICPA Bylaws and Implementing Resolutions of Councilダウンロード
ページ)。4 tests/11 assertions green。

**意図的にjicpa(JPN)と同じISIC 6920(会計・税務・監査)**を選び、
6419(zenginkyo/bankenverband)・6512(sonpo/naic)・6612(jsda/finra)に
続く**4組目の同業種国際比較ペア**を完成させた——業界団体軸で使って
いる4業種すべてがJPN+他国のペアで揃った。18リポジトリ・609 factを
統合。

現状: 国軸8件・業界団体軸8件(4業種×2か国のペアが4組すべて完成)・
自治体軸1件(Tokyoのみ)——17tickを経て自治体軸が圧倒的に薄いままで
あり、今後の最優先候補。

## Addendum (2026-07-15, /loop tick 18 — 業界団体軸9件目(FBF) — ISIC 6419が3か国に拡大)

`cloud-itonami-assoc-6419-fra-fbf`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-fra-fbf))：
Fédération Bancaire Française(FBF、Wikidata Q3091456、2000年11月設立)。
実在2件(Règles professionnelles・Normes professionnelles(2006〜2025年
の14件の拘束力ある基準))、fbf.frを直接WebFetchで照合。4 tests/11
assertions green。

**ISIC 6419(銀行業)が初めて3か国に拡大**: zenginkyo(JPN)・
bankenverband(DEU)に続きfbf(FRA)を追加し、単純なペアを超えた
初めての3か国比較が可能になった。19リポジトリ・611 factを統合。

**このtickで断念した国軸調査を記録**: 9か国目を狙って
インド(indiacode.nic.in、PDF直リンク含め全候補URLが403)・
アイルランド(irishstatutebook.ie、HTMLは403・PDFはsocket hang up)・
スイス(fedlex.admin.ch、e-Gov同様JSレンダリング必須)を試したが
いずれも検証できず断念し、業界団体軸に切り替えた——次回同じ国を
再挑戦する際は別の検証手段(複数ソース相互照合など)を検討する
必要があることをここに記録しておく。

現状: 国軸8件(変わらず)・業界団体軸9件・自治体軸1件(Tokyoのみ)。

## Addendum (2026-07-15, /loop tick 19 — 自治体軸2件目(Washington D.C.) — 長らく指摘してきたギャップに着手)

`cloud-itonami-municipality-usa-washington-dc`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-usa-washington-dc))：
これは本ADRのWave 0で当初から計画していた「JPN/Tokyo + USA/Washington
D.C.」の2件目そのもの——国軸・業界団体軸がそれぞれ8件・9件に伸びる中、
何tickも「最も薄い軸」と指摘し続けてきたが、ようやく着手した。

実在2件(Freedom of Information Act・Human Rights Law: 1977-12-13
制定)、いずれもcode.dccouncil.gov(DC議会法律図書館)を直接WebFetchで
照合。4 tests/10 assertions green。

**今回もニュージーランドで9か国目を試みたが断念**: legislation.govt.nz
(3候補すべて403)・nzlii.org(403)——tick 18に続き複数の政府ポータルで
本日WebFetchが不調気味だったため、無理に国軸を進めず、代わりに
code.dccouncil.gov(問題なくfetchできた)で自治体軸に着手する判断を
した。

20リポジトリ・613 factを統合。tokyo・washington-dcの両方で自治体
queryが正しく機能することを確認。

現状: 国軸8件・業界団体軸9件・**自治体軸2件**(ようやく複数化)——
3軸すべてで実データによる横断比較が可能になった。

## Addendum (2026-07-15, /loop tick 20 — 業界団体軸10件目(生命保険協会) — 生保/損保を区別)

`cloud-itonami-assoc-6511-jpn-seiho`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6511-jpn-seiho))：
一般社団法人生命保険協会(LIAJ、Wikidata Q11574460、1908年設立)。
実在2件(行動規範・指針・自主ガイドライン等 概要ページ／業務品質評価基準
ガイドライン（A版）: 2026年度版・作成日2026-02-26、PDF本文をReadツール
で直接確認)。4 tests/11 assertions green。

**新規ISIC区別**: ISIC 6511(生命保険)を、sonpoが既に担当している
ISIC 6512(損害保険)とは意図的に区別した——日本では生保と損保は別々の
業界団体が規制しており、この区別を統合せずそのまま保持した。

21リポジトリ・615 factを統合。6511と6512を区別するqueryがseiho(生保・
JPN)をsonpo(損保・JPN)・naic(損保・USA)から正しく分離することを確認。

現状: 国軸8件・業界団体軸10件(5業種)・自治体軸2件——20tickを経て
3軸すべてが実データ・個別検証済みで着実に成長している。

## Addendum (2026-07-15, /loop tick 21 — 国軸9件目(NLD))

`cloud-itonami-iso3166-nld`に`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-nld/commit/7e6f2d4)):
実在3法令(民法第2編(法人)・GDPR施行法・労働時間法)、
wetten.overheid.nl(オランダ政府法令ポータル)を直接WebFetchで照合
(最初に推測したBWBR番号は404となったため、WebSearchで正しいURLを
再取得してから照合)。28 tests/90 assertions green。

22リポジトリ・618 factを統合。`:labor`トピックで9か国全て
(JPN/USA/GBR/DEU/FRA/CAN/AUS/KOR/NLD)を横断取得できることを確認。

現状: 国軸9件・業界団体軸10件・自治体軸2件——21tickを経て3軸すべてが
実データ・個別検証済み・捏造なしで着実に成長を続けている。

## Addendum (2026-07-15, /loop tick 22 — 業界団体軸11件目(日弁連) — 法務業種を新規追加)

`cloud-itonami-assoc-6910-jpn-nichibenren`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6910-jpn-nichibenren))：
日本弁護士連合会(JFBA/日弁連、Wikidata Q11508095、1949-09-01設立、
弁護士法45〜50条に基づく強制加入団体)。実在2件(弁護士職務基本規程:
2004-11-10制定(会規第70号)・2021-06-11最終改正・英訳版2022年6月付、
PDF本文をReadツールで直接確認／JFBA組織概要ページ)。4 tests/11
assertions green。

**新規ISIC業種**: ISIC 6910(法務)を、jicpa/aicpaが担当する6920
(会計・税務・監査)とは区別して初めて追加した。

23リポジトリ・620 factを統合。isic別集計queryで業界団体軸が
6業種(6419×6・6512×4・6612×4・6920×4・6511×2・6910×2)を
カバーしていることを確認。

現状: 国軸9件・業界団体軸11件(6業種)・自治体軸2件——22tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 23 — 国軸10件目(ITA))

`cloud-itonami-iso3166-ita`に`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-ita/commit/7a28937)):
実在3法令(民法(1942年勅令第262号)・個人データ保護法典(2003年立法命令
第196号)・労働者憲章(1970年法律第300号))——normattiva.it(通常引用する
統合テキストポータル)は今回のクエリパターンで内部エラーを返したため、
同じく公式のgazzettaufficiale.it(官報原文ポータル)を直接WebFetchで
照合した。28 tests/90 assertions green。

24リポジトリ・623 factを統合。`:corporate-governance`トピックで10か国
(JPN/USA/GBR/DEU/FRA/CAN/AUS/KOR/NLD/ITA)全てを横断取得できることを
確認。

現状: 国軸10件・業界団体軸11件・自治体軸2件——23tickを経て3軸すべてが
実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 24 — 業界団体軸12件目(不動産協会) — 不動産業を新規追加)

`cloud-itonami-assoc-6810-jpn-recaj`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6810-jpn-recaj))：
一般社団法人不動産協会(RECAJ、Wikidata Q11361630、1963-03-04設立)。
実在2件(定款: PDF本文をReadツールで直接確認し、第1条に印字された
英文正式名称"The Real Estate Companies Association of Japan (RECAJ)"
そのものも確認／適正取引の推進に向けた自主行動計画: 2024-06-28)。
4 tests/11 assertions green。

**新規ISIC業種**: ISIC 6810(不動産業)を初めて追加した。

25リポジトリ・625 factを統合。業界団体軸は7つの異なるISICコード
(6419/6511/6512/6612/6810/6910/6920)をカバーするようになった。

現状: 国軸10件・業界団体軸12件(7業種)・自治体軸2件——24tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 25 — 国軸11件目(ESP))

`cloud-itonami-iso3166-esp`に`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-esp/commit/0ac534d)):
実在3法令(資本会社法統合テキスト(RDL 1/2010)・個人データ保護及び
デジタル権保障法(LO 3/2018)・労働者憲章統合テキスト(RDL 2/2015))、
boe.es(スペイン官報)を直接WebFetchで照合。28 tests/90 assertions
green。

26リポジトリ・628 factを統合。`:labor`トピックでこれまでの11か国全て
を横断取得できることを確認。

現状: 国軸11件・業界団体軸12件・自治体軸2件——25tickを経て3軸すべてが
実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 26 — 業界団体軸13件目(日本銀行) — 中央銀行業を新規追加)

`cloud-itonami-assoc-6411-jpn-boj`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6411-jpn-boj/commit/63e8d67))：
日本銀行(BOJ、Wikidata Q333101、日本銀行条例1882-06-27施行)。実在2件
(日本銀行業務方法書: 1998-04-01初制定・2026-04-01最終改正／日本銀行の
「独立性」と「透明性」――新日本銀行法の概要: 新日銀法(1997年改正・
1998-04-01施行)によるガバナンス枠組みの公式解説ページ)。両方とも
WebFetchで直接レンダリング確認(PDFフォールバック不要)。4 tests/11
assertions green。

**新規ISIC業種**: ISIC 6411(中央銀行業)を初めて追加した。NAICの
`:model-law`/`:governance-program`区分を踏襲し、法令解説ページ側は
`:governance-program`とした(BOJ自体が制定した規則ではなく、新日銀法が
定めたガバナンス枠組みの公式解説であるため)。

27リポジトリ・630 factを統合。業界団体軸は8つの異なるISICコード
(6411/6419/6511/6512/6612/6810/6910/6920)をカバーするようになった。
`"association-rule/topic" "governance"`での横断queryで13団体全ての
governanceトピックエントリ(recaj/bankenverband/finra/fbf/nichibenren/
aicpa/jsda/seiho/jicpa/naic/boj×2)を取得できることを確認。

現状: 国軸11件・業界団体軸13件(8業種)・自治体軸2件——26tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 27 — 自治体軸3件目(ロンドン))

`cloud-itonami-municipality-gbr-london`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-gbr-london/commit/d165137))：
london.gov.uk(The London Plan掲載元)はWebFetchで403を返したため
(このファミリーで他の政府ポータルが403/JS-onlyで落ちたのと同じ症状)、
検索スニペットからの捏造を避け、代わりにロンドンのもう一つの一次
ソース経路——議会が制定する"London Local Authorities Act"シリーズ
(legislation.gov.uk の UK Local Acts、`ukla`)——に切り替えた。実在2件
(London Local Authorities Act 2007: 2007 c. ii・Royal Assent
2007-07-19・Part 2公衆衛生と環境/Part 3ライセンシングの見出しまで
WebFetchで直接確認／London Local Authorities Act 2012: 2012 c. ii・
Royal Assent 2012-03-27・street trading/licensing改正内容を直接確認)。
UK Local Actは日本の条例やD.C. Official Codeと法的性質が異なる
(議会制定法だが地域限定適用)ため、既存の`:municipal-code`/`:ordinance`
を流用せず新たに`:kind :local-act`を導入した。4 tests/11 assertions
green。

28リポジトリ・632 factを統合。自治体軸は東京・Washington D.C.に続き
3件目となり、GBR(国軸で既にiso3166-gbrが存在)との継続性も確認できた。

現状: 国軸11件・業界団体軸13件(8業種)・自治体軸3件——27tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 28 — 国軸12件目(SWE))

`cloud-itonami-iso3166-swe`(既存の`marketentry`実装済みリポ)に
`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-swe/commit/bb596ed))：
実在3法令(Aktiebolagslag(2005:551) 会社法・Lag(2018:218) データ保護法・
Arbetsmiljölag(1977:1160) 労働環境法)——government.se(当初想定した
データ保護法引用元)はWebFetchで403を返したため、代わりに
riksdagen.se(スウェーデン議会・Svensk författningssamlingデータベース)
を3件とも直接WebFetchで照合した。既存のooyake由来`organization.edn`の
首都(Stockholm/Q1754)はP36バグの影響を受けていないことも確認済み。
28 tests/90 assertions green(既存marketentryテストと合算)。

29リポジトリ・635 factを統合。`:labor`トピックで12か国(JPN/USA/GBR/
DEU/FRA/CAN/AUS/KOR/NLD/ITA/ESP/SWE)全てを横断取得できることを確認。

現状: 国軸12件・業界団体軸13件(8業種)・自治体軸3件——28tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 29 — 業界団体軸14件目(CTIA) — 新規ISIC業種(無線通信))

`cloud-itonami-assoc-6120-usa-ctia`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6120-usa-ctia/commit/388fd19))：
CTIA - The Wireless Association(Wikidata Q5014574、1984年設立)。
www.ctia.orgはWebFetchでTLS証明書エラー(このファミリーで初めて見る
新しい失敗モード、既知の403/JS-onlyとは別種)を返したため、同団体の
文書配信サブドメインapi.ctia.orgに切り替え、実在2件を両方ともPDF本文を
Readツールで直接確認(Consumer Code for Wireless Service: URLパス自体に
含まれる2020年3月アップロードを引用／Smartphone Anti-Theft Voluntary
Commitment: PDF埋め込みメタデータの作成日2016-07-26を確認)。4 tests/
11 assertions green。

**新規ISIC業種**: ISIC 6120(無線通信業)を初めて追加した。

30リポジトリ・637 factを統合。`"association-rule/topic"
"consumer-protection"`での横断queryでCTIAの2件を含む9団体10件が
取得できることを確認。

**worktree/branch移行**: このtickのpush時、`loop-compliance-fact-federation`
ブランチ(旧`chore/pin-kototama-fence-gated`から分岐した長寿命branch)が
`west-pin-verify-guard`フックにブロックされた——west.ymlは本ADRの作業では
一度も触っていないが、branch分岐後にmain側で他の並行セッションが
数十リポジトリ分のpinを前進させており、その古いwest.ymlスナップショットを
そのまま抱えたbranchをpushすると「pin退行」の偽陽性として検出される。
west.ymlの大規模generatedファイルをローカルmerge/rebaseで戦うのは方針
違反(rebase禁止)のため、代わりに現在のorigin/mainから新規worktree
(`/tmp/root-compliance-fact-federation-2`、branch
`loop-compliance-fact-federation-2`)を切り、この3ファイル(script+ADR
md/edn)の最終内容だけをそのまま持ち越してpush(west.ymlはmain由来の
正しい状態のまま無変更)。以後のtickはこの新worktreeで継続する。

現状: 国軸12件・業界団体軸14件(9業種)・自治体軸3件——29tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 30 — 自治体軸4件目(トロント))

`cloud-itonami-municipality-can-toronto`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-can-toronto/commit/2bad815))：
実在2件、両方ともPDF本文(いずれもCity Clerk署名のcertified true copy)を
Readツールで直接確認(Toronto Municipal Code Chapter 545, Licensing:
現行版発効2025-01-01、Code自体の原始制定は検索で裏付けた2001-01-01施行を
併記／Chapter 67, Fair Wage: 2024-05-23市議会採択・By-law 498-2024が
2024-07-01施行、現行版発効2025-05-01——脚注のEditor's Noteで直接確認)。
4 tests/11 assertions green。

31リポジトリ・639 factを統合。`"ordinance/topic" "licensing"`での
自治体横断queryでlondon(2件)とtoronto(1件)を横断取得できることを確認。

現状: 国軸12件・業界団体軸14件(9業種)・自治体軸4件——30tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 31 — 国軸13件目(NOR))

`cloud-itonami-iso3166-nor`(既存の`marketentry`実装済みリポ)に
`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-nor/commit/583d19c))：
実在3法令(Lov om aksjeselskaper (aksjeloven) 会社法・LOV-1997-06-13-44・
1999-01-01施行／Act relating to the processing of personal data
(The Personal Data Act)・LOV-2018-06-15-38・2018-07-20施行／Working
Environment Act・LOV-2005-06-17-62・2006-01-01施行)——3件とも
lovdata.no(ノルウェー公式法令情報システム)を直接WebFetchで照合
(fedlex.admin.chのようなJS-onlyではなく直接レンダリングされた)。
既存organization.edn(ooyake由来)の首都(Oslo/Q585)もP36バグの影響
なしと確認済み。28 tests/90 assertions green。

32リポジトリ・642 factを統合。`:data-protection`トピックで13か国
(JPN/USA/GBR/DEU/FRA/CAN/AUS/KOR/NLD/ITA/ESP/SWE/NOR)全てを横断
取得できることを確認。

**push時のwest-pin-verify-guard再発と改善策**: 前tick(29)で新規worktree
切り直しをした直後にもかかわらず、このtickのpushも同じガードで
ブロックされた(occupation pinが1件、branch作成後にmain側で前進)——
新worktreeを切っても、次のtickまでにmainがさらに進めば同じ問題が
再発することが判明した。今回は worktree を切り直す代わりに、
`git fetch --deepen=50 origin main`(shallow cloneの`unrelated
histories`偽陽性をCLAUDE.mdの手順どおり解消)→ `git merge origin/main`
(west.ymlはconflictなくfast-forward的に取り込まれ、自分の3ファイルとは
一切衝突しない)で解決。以後のtickは**まずこのmerge手順を試し**、それでも
失敗する場合のみworktree切り直しにフォールバックする。

現状: 国軸13件・業界団体軸14件(9業種)・自治体軸4件——31tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 32 — 業界団体軸15件目(A4A) — 新規ISIC業種(旅客航空輸送))

`cloud-itonami-assoc-5110-usa-a4a`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-5110-usa-a4a/commit/8a69555))：
Airlines for America(A4A、旧Air Transport Association、Wikidata
Q408816、1936年設立)。実在2件を両方とも直接WebFetchで確認(Spec 2000:
A4AがATA e-Business Programと共同で維持する航空業界データ交換標準スイート
——コンプライアンス規則ではなく技術標準そのものなので新たに
`:kind :technical-standard`を導入して区別／History: airlines.org自身の
沿革ページ、1936年設立を直接確認)。4 tests/11 assertions green。

**新規ISIC業種**: ISIC 5110(旅客航空輸送業)を初めて追加した。

**push前にmainを事前同期**: 今回は編集開始前に`git fetch --deepen=50`+
`git merge origin/main`を先に実行(前tickで発見した手順を今回はpush
直前でなく着手前に適用)、push時のガードブロックを未然に回避できた。

34リポジトリ・644 factを統合。`"association-rule/topic"
"governance"`での横断queryでa4aを含む12団体が取得できることを確認。

現状: 国軸13件・業界団体軸15件(10業種)・自治体軸4件——32tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 33 — 国軸14件目(DNK) — 意図的に2件のみ)

`cloud-itonami-iso3166-dnk`(既存の`marketentry`実装済みリポ)に
`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-dnk/commit/efa7682))：
**通常の3件でなく意図的に2件のみ**——会社法(selskabsloven)の正本
retsinformation.dkは試した全URL形式(直接ページ・PDF版)でHTTP 403、
所管庁のerhvervsstyrelsen.dkも複数ページで403、businessindenmark.virk.dk
はJS-onlyで実質コンテンツなし。捏造せず、corporate-governanceトピックは
DNKについて空のまま残した。実在2件は個別に直接検証: データ保護法
(Databeskyttelsesloven、Act No. 502 of 23 May 2018)はDatatilsynet
(デンマークデータ保護庁)がホストする公式英訳PDFのヘッダーをReadツールで
直接確認(本文グリフはフォントサブセットの都合で文字化けしたが
ヘッダー・見出しは判読可能)／労働環境法(Arbejdsmiljøloven、
Consolidated Act no. 2062 of 16 November 2021)はat.dk(デンマーク労働
環境庁自身のサイト)を直接WebFetchで確認——同庁自身が"unofficial
version"と明記しているため、韓国KLRIと同様に専用の
`:official-agency-unofficial-translation` provenanceタグを使用。
28 tests/89 assertions green(2件のみのため他国より1 assertion少ない)。

36リポジトリ・646 factを統合。`:labor`トピックで14か国(JPN/USA/GBR/
DEU/FRA/CAN/AUS/KOR/NLD/ITA/ESP/SWE/NOR/DNK)全てを横断取得できる
ことを確認。

現状: 国軸14件・業界団体軸15件(10業種)・自治体軸4件——33tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 34 — 自治体軸5件目(ベルリン))

`cloud-itonami-municipality-deu-berlin`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-deu-berlin/commit/8a628df))：
gesetze.berlin.de(ベルリン州公式法令DB)は個別文書ページがJS-onlyで
WebFetchにシェルしか返さず、legacy jportalミラーはログイン壁——捏造せず、
ベルリン州データ保護・情報自由コミッショナー自身の文書再配布サイト
(datenschutz-berlin.de)を経由してPDF本文をReadツールで直接確認した。
実在2件: Berliner Informationsfreiheitsgesetz(IFG、GVBl. 1999, 561、
1999-10-15制定)——構造化されたPDF表紙1ページ目で日付・官報引用を
直接確認／Berliner Datenschutzgesetz(BlnDSG)——PDF前文(Vorwort)で
1978年7月の原始施行を直接確認、GDPR対応後の現行版(2018-06-13、
GVBl. S. 418)はWebSearchでの裏付けに留まる(直接一次ページでの
再検証はできず、その旨を明記)。4 tests/10 assertions green
(2件のみのため他自治体より1 assertion少ない)。

38リポジトリ・648 factを統合。`:data-protection`トピックでの自治体
横断queryでtokyoとberlinの2自治体を取得できることを確認。

現状: 国軸14件・業界団体軸15件(10業種)・自治体軸5件——34tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 35 — 業界団体軸16件目(EEI) — 新規ISIC業種(電力))

`cloud-itonami-assoc-3510-usa-eei`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-3510-usa-eei/commit/b02dd71))：
Edison Electric Institute(EEI、Wikidata Q5338374、1933年設立)。実在
2件を検証(Mutual Assistance Agreement: 会員電力会社間の災害復旧相互
応援協定、PDF本文をReadツールで直接確認／About EEI: 組織概要ページを
直接WebFetchで確認、1933年設立自体はWebSearch/Wikidata裏付け)。
4 tests/11 assertions green。

**新規ISIC業種**: ISIC 3510(発電・送電・配電業)を初めて追加した。

40リポジトリ・650 factを統合。`"association-rule/topic"
"governance"`での横断queryでeeiを含む13団体が取得できることを確認。

現状: 国軸14件・業界団体軸16件(11業種)・自治体軸5件——35tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 36 — 国軸15件目(FIN))

`cloud-itonami-iso3166-fin`(既存の`marketentry`実装済みリポ)に
`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-fin/commit/2dd722e))：
実在3法令(Limited Liability Companies Act (Osakeyhtiölaki)・624/2006・
2006-07-21発行／Data Protection Act (Tietosuojalaki)・1050/2018・
2018-12-05発行／Employment Contracts Act (Työsopimuslaki)・55/2001・
2001-01-26発行)——3件ともfinlex.fi(フィンランド法務省公式法令DB)の
英訳版を直接WebFetchで照合。各英訳ページ自体が「フィンランド語・
スウェーデン語版のみ法的拘束力を持つ」と明記しているため、専用の
`:official-finlex-reference-translation` provenanceタグを使用(韓国
KLRI・デンマークat.dkと同系統の規律)。既存organization.edn(ooyake
由来)の首都(Helsinki/Q1757)もP36バグの影響なしと確認済み。
28 tests/90 assertions green。

43リポジトリ・653 factを統合。`:corporate-governance`トピックでの
横断queryで14か国(前tickで意図的に空にしたDNKを除く全て)を取得
できることを確認。

現状: 国軸15件・業界団体軸16件(11業種)・自治体軸5件——36tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 37 — 業界団体軸17件目(VDA) — 新規ISIC業種(自動車製造))

`cloud-itonami-assoc-2910-deu-vda`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-2910-deu-vda/commit/0a40423))：
Verband der Automobilindustrie(VDA、ドイツ自動車工業会、1901年設立)。
実在2件を検証(Code of Conduct für Geschäftspartner: CLEPA(欧州自動車
部品工業会)と共同発行、Responsible Business Allianceコード・ドイツ
サプライチェーン・デューデリジェンス法に整合、PDF表紙をReadツールで
直接確認——本文中に明確な発行日が見当たらなかったため日付フィールドは
意図的に未設定／About VDA: 組織概要ページを直接WebFetchで確認、1901年
設立自体はWebSearchとVDA自身の「125 Jahre VDA」表記の裏付け)。
4 tests/11 assertions green。

**新規ISIC業種**: ISIC 2910(自動車製造業)を初めて追加した。DEU国は
これでbankenverband(6419)とvda(2910)の2業種で業界団体軸に登場する
ようになった。

45リポジトリ・655 factを統合。DEU国内での業界団体横断queryで
bankenverbandとvdaが別ISICコードで取得できることを確認。

現状: 国軸15件・業界団体軸17件(12業種)・自治体軸5件——37tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 38 — 自治体軸6件目(パリ))

`cloud-itonami-municipality-fra-paris`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-fra-paris/commit/41d00e2))：
実在2件、両方ともPDF本文をReadツールで直接確認: 商業用不動産の民泊
用途転用許可条件を定める市規則(パリ市議会2025-04-08〜11開催の
délibération 2025 DLH 106で採択、2025-04-18公表——文書自身のヘッダーに
会期・議決番号・公表日が明記)／Règlement des Terrasses et Étalages
(テラス・陳列規則、原型は2011-06-11の市長命令、以降複数回の改正を経て
現行版は2023-12-13時点——文書自身が全改正日を列挙)。4 tests/10
assertions green。

47リポジトリ・657 factを統合。`:licensing`トピックでの自治体横断query
でlondon・toronto・parisの3自治体を取得できることを確認。

現状: 国軸15件・業界団体軸17件(12業種)・自治体軸6件——38tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 39 — 国軸16件目(PRT))

`cloud-itonami-iso3166-prt`(既存の`marketentry`実装済みリポ)に
`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-prt/commit/2d6a600))：
実在3法令、全てDRE(Diário da República Eletrónico、ポルトガル公式電子
官報)の英訳版PDFをReadツールで直接確認(Commercial Companies Code
(Código das Sociedades Comerciais)・Decree-Law no. 262/86・1986-09-02
公布／Data Protection Act・Lei n.º 58/2019・2019-08-08公布(GDPR国内
執行法)／Labour Code (Código do Trabalho)・Law no. 7/2009・
2009-02-12公布)。diariodarepublica.pt自体のReact詳細ページはWebFetch
に空コンテンツを返した(JS-only)ため、代わりにfiles.dre.pt /
files.diariodarepublica.pt(同じ公式官報のPDFホスト)を使用——各PDF
自身のヘッダーに官報シリーズ・号数・発行日が明記されている。既存
organization.edn(ooyake由来)の首都(Lisbon/Q597)もP36バグの影響
なしと確認済み。28 tests/90 assertions green。

50リポジトリ・660 factを統合。`:corporate-governance`トピックでの
横断queryで15か国(DNKを除く全て)を取得できることを確認。

現状: 国軸16件・業界団体軸17件(12業種)・自治体軸6件——39tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 40 — 業界団体軸18件目(AHLA) — 新規ISIC業種(宿泊))

`cloud-itonami-assoc-5510-usa-ahla`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-5510-usa-ahla/commit/92bff0c))：
American Hotel & Lodging Association(AHLA、Wikidata Q19872202、1910年
設立)。実在2件を検証(5-Star Promise: 従業員の安全・セクハラ防止に関する
業界横断コミットメント、2018-09-06発表、文書本文をReadツールで直接確認
——本文自身に発表日と最終更新日(2019-10-04)が明記／About AHLA: 組織
概要ページを直接WebFetchで確認、1910年設立自体はWebSearch/Wikipedia
裏付け)。4 tests/11 assertions green。

**新規ISIC業種**: ISIC 5510(宿泊業)を初めて追加した。

**52リポジトリ・662 factを統合——tick 1開始から積み上げてきた
compliance-fact連邦化システムが52リポジトリの節目に到達。**
`"association-rule/topic" "governance"`での横断queryでahlaを含む
15団体が取得できることを確認。

現状: 国軸16件・業界団体軸18件(13業種)・自治体軸6件——40tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 41 — 自治体軸7件目(アムステルダム))

`cloud-itonami-municipality-nld-amsterdam`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-nld-amsterdam/commit/4792212))：
実在2件、両方ともlokaleregelgeving.overheid.nl(オランダ地方法令の
公式全国ポータル、CVDR番号でインデックス)を直接WebFetchで確認:
Algemene Plaatselijke Verordening 2008(一般地方条例、CVDR72510、
最初のバージョン=version 1で確認・2008-11-01施行)／Huisvestings-
verordening Amsterdam 2020(住宅条例、CVDR635633、2020年版で確認・
2020-01-01施行)——いずれも「現行版」という曖昧な主張ではなく特定の
日付付きバージョンを引用している点を明記。4 tests/10 assertions
green。

54リポジトリ・664 factを統合。`:short-term-rental`トピックでの
自治体横断queryでparis(民泊規則)とamsterdam(住宅条例)を横断取得
できることを確認——実世界でも類似の政策課題(観光用短期賃貸規制)を
異なる自治体が扱っている実例が federation query で表現できている。

現状: 国軸16件・業界団体軸18件(13業種)・自治体軸7件——41tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 42 — 国軸17件目(BEL) — 同tick内で2か国断念)

`cloud-itonami-iso3166-bel`(既存の`marketentry`実装済みリポ)に
`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-bel/commit/0daf42d))：
実在3法令、全てejustice.just.fgov.be(Moniteur belge / Justel、
ベルギー公式官報統合法データベース)を直接WebFetchで照合(Code des
sociétés et des associations・2019-03-23／データ保護法・2018-07-30／
Loi relative aux contrats de travail(労働契約法)・1978-07-03)。
28 tests/90 assertions green。

**同一tick内で2か国を断念した後にBELで着地**: オーストリア
(ris.bka.gv.at)は試した全URL形式(GeltendeFassung.wxe・
NormDokument.wxe・eli/bgbl・直接PDF)でHTTP 503を返す—JS-onlyでなく
bot対策ブロックと判断し断念。ポーランド(isap.sejm.gov.pl)は
CAPTCHA壁—本プロジェクトの安全床に従い突破を試みず断念。両方とも
捏造の代わりに率直に諦め、BELに切り替えて着地した。

57リポジトリ・667 factを統合。`:data-protection`トピックでの横断
queryで17か国全てを取得できることを確認。

現状: 国軸17件・業界団体軸18件(13業種)・自治体軸7件——42tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 43 — 業界団体軸19件目(PhRMA) — 新規ISIC業種(医薬品製造))

`cloud-itonami-assoc-2100-usa-phrma`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-2100-usa-phrma/commit/3f024a8))：
Pharmaceutical Research and Manufacturers of America(PhRMA、Wikidata
Q5683113、1958年設立)。実在2件を両方ともPDF本文をReadツールで直接
確認(Code on Interactions with Health Care Professionals: 前文自身が
PhRMAの正式名称を明記——最終改正日は文書内では未確認、2022-01-01
施行はWebSearch裏付けに留まる旨を明記／PhRMA Guiding Principles:
Direct to Consumer Advertisements about Prescription Medicines:
検証済みURL自体に組み込まれた"2018"を改正年として引用)。4 tests/
11 assertions green。

**新規ISIC業種**: ISIC 2100(医薬品製造業)を初めて追加した。

59リポジトリ・669 factを統合。`"association-rule/topic"
"member-conduct"`での横断queryでphrmaを含む7団体が取得できることを
確認。

現状: 国軸17件・業界団体軸19件(14業種)・自治体軸7件——43tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 44 — 自治体軸8件目(マドリード))

`cloud-itonami-municipality-esp-madrid`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-esp-madrid/commit/b2f8b07))：
madrid.es自身のPDF再配布はApache FOPのフォント埋め込み不具合で
実質白紙ページとして描画された(このファミリーで初めて見る失敗
モード——JS-onlyでも403でもない)ため、代わりにtransparencia.madrid.es
の「Huella normativa」HTMLページを直接WebFetchで確認: Ordenanza de
Movilidad Sostenible(持続可能なモビリティ条例、2018-10-05承認、
BOAM núm. 8.263掲載)／Ordenanza de Transparencia de la Ciudad de
Madrid(マドリード市透明性条例、2016-07-27承認、BOCM nº 196掲載)。
4 tests/10 assertions green。

61リポジトリ・671 factを統合。`:transparency`トピックでの自治体
横断queryでtokyo・washington-dc・berlin・madridの4自治体を取得
できることを確認。

現状: 国軸17件・業界団体軸19件(14業種)・自治体軸8件——44tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 45 — 国軸18件目(BRA) — P36バグ3件目発見・修正)

`cloud-itonami-iso3166-bra`(既存の`marketentry`実装済みリポ)に
`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-bra/commit/810d2a0))：
planalto.gov.br(通常の第一候補)は試した全URLでECONNRESETを返した
ため、同じく公式のlexml.gov.br(Rede LexML、ブラジル連邦立法メタデータ
ネットワーク)を直接WebFetchで照合し実在3法令を確認(Lei das Sociedades
por Ações・6.404/1976・1976-12-15制定／LGPD(一般データ保護法)・
13.709/2018・2018-08-14制定／CLT(労働法統合)・Decreto-Lei
5.452/1943・1943-05-01制定)。

**ooyake P36バグの3件目を発見・修正**: 既存organization.edn(ooyake
由来)の首都が「Rio de Janeiro」(Wikidata Q8678、1960年以前の旧首都)
になっていた——正しくは「Brasília」(Q2844、1960年遷都)。JPN
(commit 15252d7)・DEU(commit 95d48ec)と同じ系統のP36複数claim
誤取り込みバグで、今回もこのリポジトリのみdownstreamで修正し
ooyake自体には触れていない(範囲外)。

**共有checkoutでの既存WIP退避**: 着手前にorganization.edn に無関係な
未コミット差分(head-role表記の変更)を発見、`git stash push`で退避
(drop せず温存)してから作業、自分の変更のみコミット。28 tests/
90 assertions green。

64リポジトリ・674 factを統合。`:labor`トピックでの横断queryで
18か国全てを取得できることを確認。

現状: 国軸18件・業界団体軸19件(14業種)・自治体軸8件——45tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 46 — 業界団体軸20件目(NRF) — 新規ISIC業種(小売))

`cloud-itonami-assoc-4719-usa-nrf`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-4719-usa-nrf/commit/902705e))：
IT/ソフトウェア業種を狙いBSA | The Software Allianceを最初に試した
がbsa.orgは全ページで403を返したため捏造せず断念、National Retail
Federation(NRF、Wikidata Q6978097、1911年設立)に切り替え。実在2件を
両方とも直接WebFetchで確認(Five to Thrive: Loss Prevention: 小規模
小売業者向けの実践ガイド——会員拘束的なコミットメントではなく実務
ガイダンスなので新たに`:kind :best-practices-guide`を導入して区別
／About Us: 組織概要ページ、1911年設立自体はWebSearch/Wikipedia
裏付け、ページ自身は"over a century"としか述べていない)。4 tests/
11 assertions green。

**新規ISIC業種**: ISIC 4719(その他非専門店小売業)を初めて追加した。

66リポジトリ・676 factを統合。`"association-rule/topic"
"governance"`での横断queryでnrfを含む16団体が取得できることを確認。

現状: 国軸18件・業界団体軸20件(15業種)・自治体軸8件——46tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 47 — 自治体軸9件目(ソウル))

`cloud-itonami-municipality-kor-seoul`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-kor-seoul/commit/9d6f764))：
実在2件、両方ともlegal.seoul.go.kr(ソウル特別市自身の公式英訳法令
ポータル)を直接WebFetchで確認: Ordinance on Disclosure of
Administrative Information for Open City Administration(行政情報
公開条例、公布番号3792、2000-10-25制定)／Ordinance on Protection
of Personal Information(個人情報保護条例、最新の公布番号9487・
2025-01-03改正版を引用——原始制定日は未確認のため、date fieldは
last-revised-dateのみとし enacted-dateは推測せず未設定)。4 tests/
10 assertions green。

68リポジトリ・678 factを統合。`:data-protection`トピックでの自治体
横断queryでtokyo・berlin・seoulの3自治体を取得できることを確認。

現状: 国軸18件・業界団体軸20件(15業種)・自治体軸9件——47tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 48 — 国軸19件目(MEX))

`cloud-itonami-iso3166-mex`(既存の`marketentry`実装済みリポ)に
`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-mex/commit/5064cd9))：
通常の第一候補diputados.gob.mx(下院立法図書館)はIPレベルで
ECONNREFUSED——完全に到達不能で、JS-only/403/CAPTCHAとは異なる新しい
失敗種別。捏造せず、メキシコ政府の別公式ミラー(gob.mx自身のCMS
アップロードホスト、および税務当局wwwmat.sat.gob.mxが保持する下院
図書館ミラー)経由で実在3法令を確認: Ley General de Sociedades
Mercantiles(会社法、DOF 1934-08-04公布・最終改正DOF 2016-03-14)／
Ley Federal de Protección de Datos Personales en Posesión de los
Particulares(個人データ保護法、DOF 2010-07-05)／Ley Federal del
Trabajo(連邦労働法、DOF 1970-04-01公布・最終改正DOF 2015-06-12)。
いずれもPDF本文はフォントサブセットの都合で文字化けしたが、
ヘッダー(法令名・DOF発行日)は明瞭に判読できた(デンマークDPA・
ベルリンIFG/BlnDSGと同系統のPDF描画不具合)。28 tests/89 assertions
green。

71リポジトリ・681 factを統合。`:data-protection`トピックでの横断
queryで19か国全てを取得できることを確認。

現状: 国軸19件・業界団体軸20件(15業種)・自治体軸9件——48tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 49 — 業界団体軸21件目(AGC) — 新規ISIC業種(建設))

`cloud-itonami-assoc-4100-usa-agc`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-4100-usa-agc/commit/54fc188))：
Associated General Contractors of America(AGC、Wikidata Q21015616、
1918年設立)。実在2件を両方とも直接確認(2018 Construction Safety
Excellence Awards (CSEA): Safety Management Best Practices: Willis
Towers Watsonと共同発行、PDF表紙をReadツールで直接確認——会員拘束的
コミットメントではなく実務ガイダンスなので`:kind :best-practices-guide`
を使用／Our History(centennial): 組織概要ページを直接WebFetchで確認、
1918年設立自体がページ本文に直接明記されている)。4 tests/11
assertions green。

**新規ISIC業種**: ISIC 4100(建築工事業)を初めて追加した。

73リポジトリ・683 factを統合。`"association-rule/topic"
"governance"`での横断queryでagcを含む17団体が取得できることを確認。

現状: 国軸19件・業界団体軸21件(16業種)・自治体軸9件——49tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 50 — 自治体軸10件目(ローマ) — tick 50節目到達)

`cloud-itonami-municipality-ita-roma`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-ita-roma/commit/b07a6d3))：
実在2件、両方ともPDF本文をReadツールで直接確認: Nuovo Regolamento
per la disciplina dell'Albo Pretorio on line(オンライン公示板規則、
Giunta Capitolina決議第71号、2021-04-02承認——議事録抜粋PDFに
ジュンタ構成員の氏名が付随的に記載されていたが、決議番号・日付の
確認のためだけに読み、カタログには一切保存していない)／Regolamento
per l'esercizio delle attività commerciali e artigianali nel
territorio della città storica(歴史地区商業・手工業活動規則、
Assemblea Capitolina決議第109号、2023-05-30承認)。4 tests/10
assertions green。

**tick 50 節目到達**: 75リポジトリ・685 factを統合。`:transparency`
トピックでの自治体横断queryでtokyo・washington-dc・berlin・madrid・
seoul・romaの6自治体を取得できることを確認。国軸19・業界団体軸21
(16業種)・自治体軸10——50tickにわたり一度も捏造なく、実在URL・実在
日付・実在法令番号のみで積み上げてきた。

現状: 国軸19件・業界団体軸21件(16業種)・自治体軸10件——50tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 51 — 国軸20件目(CHL))

`cloud-itonami-iso3166-chl`(既存の`marketentry`実装済みリポ)に
`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-chl/commit/b6db667))：
bcn.cl/leychile.cl(通常の第一候補、チリ国会図書館の対話式法令閲覧
ページ)は試した全navegar URLでJS-only(「接続が遅いかブラウザが
非対応」)エラーを返した——このファミリーのe-Gov・fedlex.admin.chと
同系統の失敗モード。捏造せず、BCN自身のPDFエクスポートサービス
(nuevo.leychile.cl、今回は文字ベースで正常にレンダリング)と労働庁
(Dirección del Trabajo)自身のPDF再配布を使い実在3法令を確認: Ley
N° 18.046 sobre Sociedades Anónimas(会社法、1981-10-22公布)／
Ley N° 19.628 sobre Protección de la Vida Privada(個人データ保護法、
1999-08-28公布)／Código del Trabajo(労働法典、DFL N° 1、
2003-01-16公布・現行版2026年7月版)。28 tests/89 assertions green。

76リポジトリ・688 factを統合。`:corporate-governance`トピックでの
横断queryで19か国(DNKを除く全て)を取得できることを確認。

現状: 国軸20件・業界団体軸21件(16業種)・自治体軸10件——51tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 52 — 業界団体軸22件目(NAB) — 新規ISIC業種(放送))

`cloud-itonami-assoc-6020-usa-nab`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6020-usa-nab/commit/486b551))：
National Association of Broadcasters(NAB、Wikidata Q1759624、1923年
設立)。実在2件を検証(Political Broadcast Catechism (第16版):
政治広告に関する実務ガイド、PDF表紙をReadツールで直接確認——正確な
発行日はページ上で未確認のためWebSearch裏付けの2014年をlast-revised
として記録／Our Mission (Celebrating 100 Years): 組織概要ページを
直接WebFetchで確認、1923年設立(「16局が参加したシカゴでの最初の
組織会合」)自体がページ本文に直接明記されている)。4 tests/11
assertions green。

**新規ISIC業種**: ISIC 6020(テレビ番組制作・放送業)を初めて追加した。

78リポジトリ・690 factを統合。`"association-rule/topic"
"governance"`での横断queryでnabを含む18団体が取得できることを確認。

現状: 国軸20件・業界団体軸22件(17業種)・自治体軸10件——52tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 53 — 自治体軸11件目(シドニー))

`cloud-itonami-municipality-aus-sydney`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-aus-sydney/commit/6b4801c))：
検索で見つけた直接PDFリンクは全て404/403だったため、代わりに
cityofsydney.nsw.gov.au自身のHTMLポリシーページ(Published/Last
modified日付が明記されている)を直接WebFetchで確認: Code of Conduct
(2024-10-10公表、2024-10-21最終更新)／Local approvals policy for
construction-related temporary structures on and above roads
(道路上・道路上方の建設関連仮設構造物に関する地域承認方針、
2022-11-21公表、2025-11-11最終更新)。4 tests/10 assertions green。

80リポジトリ・692 factを統合。`:governance`トピックでの自治体横断
queryでsydneyを取得できることを確認(このタグを導入した最初の自治体)。

現状: 国軸20件・業界団体軸22件(17業種)・自治体軸11件——53tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 54 — 国軸21件目(ARG))

`cloud-itonami-iso3166-arg`(既存の`marketentry`実装済みリポ)に
`statute.facts`を追加・push
([commit](https://github.com/cloud-itonami/cloud-itonami-iso3166-arg/commit/e2c8db7))：
実在3法令、全てservicios.infoleg.gob.ar(InfoLeg、アルゼンチン法務・
人権省公式立法情報システム)を直接WebFetchで照合(bcn.cl/leychile.cl
のJS-onlyとは異なり直接レンダリングされた): Ley General de
Sociedades N° 19.550(会社法、1972-04-03制定・2018-06-18最終改正
(Ley 27.444))／Ley N° 25.326(Habeas Data、個人データ保護法、
2000-10-04制定)／Ley N° 20.744(労働契約法、1974-09-05制定)。
28 tests/90 assertions green。

82リポジトリ・695 factを統合。`:labor`トピックでの横断queryで21か国
全てを取得できることを確認。

現状: 国軸21件・業界団体軸22件(17業種)・自治体軸11件——54tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 55 — 業界団体軸23件目(AWWA) — 新規ISIC業種(上下水道))

`cloud-itonami-assoc-3600-usa-awwa`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-3600-usa-awwa/commit/ef987b3))：
American Water Works Association(AWWA、Wikidata Q4745366、
1881-03-29設立)。AWWAの技術規格(C100シリーズ等)はstore.awwa.orgで
販売される有料製品のため、代わりに無料公開ページ2件を検証:
AWWA Policy Statement on Distribution System Water Quality(配水
システム水質に関する方針声明、1975-01-26採択・2026-04-01最終改正——
文書自身が両日付を明記)／Who We Are(組織概要ページ、「1881年3月29日、
セントルイスのワシントン大学キャンパスに22名の水道事業管理者・技術者・
運営者が集まり…AWWA創設」という具体的設立日をページ本文で直接確認)。
4 tests/11 assertions green。

**新規ISIC業種**: ISIC 3600(上水の収集・処理・供給業)を初めて追加した。

84リポジトリ・697 factを統合。`"association-rule/topic"
"governance"`での横断queryでawwaを含む19団体が取得できることを確認。

現状: 国軸21件・業界団体軸23件(18業種)・自治体軸11件——55tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 56 — 自治体軸12件目(ブエノスアイレス))

`cloud-itonami-municipality-arg-buenos-aires`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-arg-buenos-aires/commit/9ca8426))：
ブエノスアイレス自治市(Ciudad Autónoma de Buenos Aires、Wikidata Q1486)。
自治市自身の公式官報データベース`boletinoficial.buenosaires.gob.ar`の
HTML norm ページ2件を直接WebFetch検証:
Ley 1472「Código Contravencional de la Ciudad Autónoma de Buenos Aires」
(治安条例、2004-10-28公布)／Ley 104「Ley de Acceso a la Información」
(情報公開法、1998-11-19可決・1998-12-17公布・1998-12-29掲載)。
4 tests/10 assertions green。

**却下した情報源2件（捏造せず正直に記録）**:
`documentosboletinoficial.buenosaires.gob.ar`の直接PDF（Ley 6017の
再公開）はフォントサブセット化による文字化けが今回のファミリーで
最悪——通常はヘッダーだけは読めるケースが多い中、法律名そのものが
判読不能だった。「Gobierno de la Ciudad Autónoma de Buenos Aires」の
ヘッダーのみ確認できたが本文は使わず、代わりに上記の
boletinoficial.buenosaires.gob.ar HTML norm ページに切り替えた。
`juristeca.jusbaires.gob.ar`は`connect ECONNREFUSED
45.182.81.155:443`でTCP接続自体が拒否され断念。

85リポジトリ・699 factを統合。`"ordinance/topic" "transparency"`での
横断queryでbuenos-airesがtokyo/washington-dc/berlin/madrid/seoul/roma
と並んで取得できることを確認。

現状: 国軸21件・業界団体軸23件(18業種)・自治体軸12件——56tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 57 — 国軸22件目(南アフリカ))

`cloud-itonami-iso3166-zaf`に`statute.facts`を新規追加・push
([commit 42cc6f6](https://github.com/cloud-itonami/cloud-itonami-iso3166-zaf/commit/42cc6f6))：
南アフリカ共和国。公式`justice.gov.za`/`gov.za`のPDF3件を検証。
Companies Act 71 of 2008（会社法、2009-04-08成立・2024-12-27最終改正、
justice.gov.za PDFがフルレンダリング——珍しく本文まで完全に読める強い
一次情報源）／Protection of Personal Information Act 4 of 2013・POPIA
（データ保護法、2013-11-19成立、同じくjustice.gov.za PDFが完全レンダ
リング）／Labour Relations Act 66 of 1995（労働関係法、gov.za PDFは
フォントサブセット化で本文が文字化けしたが、法律名「Labour Relations
Act, 1995」と大統領署名日「29 November 1995」はそれぞれ判読可能——
デンマークDatatilsynet・ベルリンIFG/BlnDSGと同じ「ヘッダー可読・本文
文字化け」ティア）。28 tests/90 assertions green（既存marketentry
スイートと合算）。

**却下した国**: アイルランド（`irishstatutebook.ie`がルートから
HTTP 403、ボット防御ブロックと判断）、ニュージーランド
（`legislation.govt.nz`も同様にHTTP 403）、シンガポール
（`sso.agc.gov.sg`も同様にHTTP 403）——3カ国連続で公式法令ポータルが
WebFetchを拒否したため断念し、南アフリカに切り替えた。

首都チェック: 既存のooyake由来organization.edn（Pretoria、Wikidata
Q3926）は史実上一貫して首都であり、JPN/DEU/BRAで確認されたP36
歴史的首都バグの対象外と確認。

86リポジトリ・702 factを統合。`"statute/topic" "data-protection"`での
横断queryでzafを含む22カ国すべてが取得できることを確認。

現状: 国軸22件・業界団体軸23件(18業種)・自治体軸12件——57tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 58 — 自治体軸13件目(ヘルシンキ))

`cloud-itonami-municipality-fin-helsinki`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-fin-helsinki/commit/d5ad42b))：
ヘルシンキ市。`hel.fi`の英語版HTMLページ2件を直接WebFetch検証:
Environmental Protection Regulations of the City of Helsinki（環境保護
規則、2018-07-15施行・2026-05-15改正）／Administrative Regulations and
Rules of Operation of the City of Helsinki（行政規則・運営規則、
2017-06-01施行）。4 tests/10 assertions green。

**却下した都市**: このtickはまずニューヨーク市を試みたが`nyc.gov`が
HTML・PDFとも一貫してHTTP 403（ボット防御ブロック）を返し断念。次に
ストックホルム市を試みたが、公式KFS PDF
（`kfs-2023-14-...ordningsforeskrifter-for-stockholms-kommun.pdf`）が
フォントサブセット化で文書名自体まで判読不能なほど文字化けし、HTML
ページ側も明確な制定日を示していなかったため断念——2都市連続の
dead-endを経てヘルシンキで着地した。

首都チェック: 既存のooyake由来organization.edn（Helsinki、Wikidata
Q1757）は史実上一貫して首都であり、P36歴史的首都バグの対象外と確認。

87リポジトリ・704 factを統合。`"ordinance/topic" "governance"`での
横断queryでhelsinkiがsydneyと並んで取得できることを確認。

現状: 国軸22件・業界団体軸23件(18業種)・自治体軸13件——58tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 59 — 業界団体軸24件目(ATA) — 新規ISIC業種(道路貨物輸送))

`cloud-itonami-assoc-4923-usa-ata`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-4923-usa-ata/commit/046054b))：
American Trucking Associations(ATA、Wikidata Q4745283)。`trucking.org`
の公式ページ2件を直接WebFetch検証: 「ATA: 90 Years and Rolling」
（1933-09-23設立、ワシントンD.C.での法人化を記事本文で直接確認）／
「Safety」ポリシーポジションページ（CSA・ELD・労働時間規制・薬物検査・
運転免許基準に関するATAの公式な規制・立法への立場表明）。後者を
きっかけに新しい`:kind`値`:policy-position`を導入——既存の
`:self-regulatory-code`（会員拘束的コミットメント）とも
`:governance-program`（組織プロフィール）とも異なる、団体の対外的な
規制・立法ポジション表明という第3のカテゴリ。このpolicy positionページ
自体には単一の制定日が明記されていなかったため、
`:association-rule/established-date`は捏造せず意図的に省略した。
4 tests/11 assertions green。

**新規ISIC業種**: ISIC 4923(道路貨物輸送業)を初めて追加した。

88リポジトリ・706 factを統合。`"association-rule/topic"
"governance"`での横断queryでataを含む20団体が取得できることを確認。

現状: 国軸22件・業界団体軸24件(19業種)・自治体軸13件——59tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 60 — 自治体軸14件目(コペンハーゲン))

`cloud-itonami-municipality-dnk-copenhagen`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-dnk-copenhagen/commit/eed9f19))：
コペンハーゲン市。`kk.dk`系の公式ページ/PDF2件を直接WebFetch検証:
Regulativ for Erhvervsaffald（事業系廃棄物規則、2024-09-01施行——
`kk.sites.itera.dk`（市の文書公開サブドメイン）ホストのPDFが表紙まで
完全に読める強い一次情報源）／Styrelsesvedtægt for Københavns Kommune
（統治憲章、kk.dk自身の「Sådan styres København」ページが現行憲章を
「pr. 25. juni 2026」と明記——制定日ではなく明記された基準日として
採用、捏造なし）。4 tests/11 assertions green。

首都チェック: コペンハーゲンは史実上一貫してデンマークの首都であり
（Wikidata Q1748）、P36歴史的首都バグの対象外——既存
`cloud-itonami-iso3166-dnk/organization.edn`には`:hq`フィールド自体が
未記載だったが、これは本tickのスコープ外（自治体リポの追加であり、
国リポの既存フィールド欠落を修正するタスクではない）と判断し、
手を加えなかった。

89リポジトリ・708 factを統合。`"ordinance/topic" "governance"`での
横断queryでcopenhagenがsydney/helsinkiと並んで取得できることを確認。

現状: 国軸22件・業界団体軸24件(19業種)・自治体軸14件——60tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 61 — 国軸23件目(コロンビア))

`cloud-itonami-iso3166-col`に`statute.facts`を新規追加・push
([commit b7311ec](https://github.com/cloud-itonami/cloud-itonami-iso3166-col/commit/b7311ec))：
コロンビア共和国。まず通常の第一候補である`secretariasenado.gov.co`
（コロンビア上院の公式法令ポータル）を試みたがTCPレベルで完全に
到達不能（`connect ECONNREFUSED 200.7.106.227:443`、以前メキシコの
diputados.gob.mxで遭遇したのと同じ障害クラス）だったため断念し、
代わりに`funcionpublica.gov.co`（Departamento Administrativo de la
Función Pública、コロンビア政府機関の公式Gestor Normativo）の3件を
検証: Código de Comercio（商法典、Decreto 410 de 1971、1971-03-27制定）／
Ley Estatutaria 1581 de 2012（データ保護法、2012-10-17制定）／
Código Sustantivo del Trabajo（労働法典、Decreto 2663 de 1950、
1950-08-05制定）。いずれもページが完全にレンダリングされ、制定日・
施行日・官報番号まで明記されていた。28 tests/90 assertions green
（既存marketentryスイートと合算）。

首都チェック: 既存のooyake由来organization.edn（Bogotá、Wikidata
Q2841）は史実上一貫して首都であり、P36歴史的首都バグの対象外と確認。

90リポジトリ・711 factを統合。`"statute/topic" "labor"`での横断query
でcolを含む23カ国すべてが取得できることを確認。

現状: 国軸23件・業界団体軸24件(19業種)・自治体軸14件——61tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 62 — 自治体軸15件目(オスロ))

`cloud-itonami-municipality-nor-oslo`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-nor-oslo/commit/29230cb))：
オスロ市。`oslo.kommune.no`の公式HTMLページ2件を直接WebFetch検証:
Reglement for bystyret（市議会議事規則、2023-08-30可決・2023-10-25
施行）／Forskrift om serverings-, salgs- og skjenkebevillinger i Oslo
kommune（飲食店営業・酒類販売免許時間規則、2025-04-30可決）。両方とも
ページ本文に日付が明記され完全にレンダリングされた。4 tests/10
assertions green。

首都チェック: 既存のooyake由来organization.edn（Oslo、Wikidata Q585）
は史実上一貫して首都であり、P36歴史的首都バグの対象外と確認。

91リポジトリ・713 factを統合。`"ordinance/topic" "governance"`での
横断queryでosloがsydney/helsinki/copenhagenと並んで取得できることを
確認。

現状: 国軸23件・業界団体軸24件(19業種)・自治体軸15件——62tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-15, /loop tick 63 — 業界団体軸25件目(NRA) — 新規ISIC業種(飲食店))

`cloud-itonami-assoc-5610-usa-nra`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-5610-usa-nra/commit/a0412aa))：
National Restaurant Association（NRA、Wikidata Q6978094——全米ライフル
協会と同じ略称のため、docstring/READMEで明示的に区別を記載）。
`restaurant.org`/`servsafe.com`の公式ページ2件を直接WebFetch検証:
「Who We Are (Our History)」（1919-03-13、カンザスシティで最初の会合、
記事本文で直接確認）／「ServSafe (About Us)」（NRAの食品安全研修・
認証プログラム、公式ページ自体には開始年が明記されておらず、
二次情報源の1990年を捏造せず、`established-date`は意図的に省略）。
4 tests/11 assertions green。

**新規ISIC業種**: ISIC 5610(飲食店・移動体飲食サービス業)を初めて
追加した。

92リポジトリ・715 factを統合。`"association-rule/topic"
"governance"`での横断queryでnraを含む21団体が取得できることを確認。

現状: 国軸23件・業界団体軸25件(20業種)・自治体軸15件——63tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 64 — 自治体軸16件目(ブリュッセル))

`cloud-itonami-municipality-bel-brussels`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-bel-brussels/commit/c28cee8))：
ブリュッセル市。まずリスボン市を試みたが`lisboa.pt`がドメインルート
自体でHTTP 403を返しWebFetchを完全にブロックしたため断念——
今回のファミリーで初めての「ドメイン全体ブロック」パターン（個別
ページの403や文字化けPDFとは異なる）。代わりにブリュッセル市公式
`bruxelles.be`の「Règlements communaux」ページを直接WebFetch検証:
Règlement d'ordre intérieur du Conseil communal（市議会議事規則、
2018-01-22採択・2018-05-17掲示）／Code déontologique（倫理規程、
2013-10-21採択・2014-01-25掲示）。ページが完全にレンダリングされ、
両文書の採択日・掲示日が明記されていた。4 tests/10 assertions
green。

首都チェック: 既存のooyake由来organization.edn（Brussels、Wikidata
Q239）は史実上一貫して首都であり、P36歴史的首都バグの対象外と確認。

93リポジトリ・717 factを統合。`"ordinance/topic" "governance"`での
横断queryでbrusselsがsydney/helsinki/copenhagen/osloと並んで
取得できることを確認。

現状: 国軸23件・業界団体軸25件(20業種)・自治体軸16件——64tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 65 — 国軸24件目(ウルグアイ))

`cloud-itonami-iso3166-ury`に`statute.facts`を新規追加・push
([commit 842d5d8](https://github.com/cloud-itonami/cloud-itonami-iso3166-ury/commit/842d5d8))：
ウルグアイ東方共和国。まずペルーを試みたが4種類の情報源すべてが
使用不能だった: `gob.pe`はHTTP 418（意図的なbotブロックコード）、
`diariooficial.elperuano.pe`はTCP接続拒否、`spijweb.minjus.gob.pe`は
接続リセット、さらに独立した2つのPDFミラー（essalud.gob.pe /
docs.peru.justia.com）はいずれもタイトル文字すら判読不能な完全な
文字化け——このファミリーで最悪のPDF文字化け事例。ペルーを完全に
断念し、ウルグアイの公式`impo.com.uy`（IMPO、ウルグアイ公式情報
センター）で3件検証: Ley N.º 16.060（商事会社法、1989-09-04制定）／
Ley N.º 18.331（データ保護法、2008-08-11制定）／Ley N.º 5.350
（8時間労働法、1915-11-17制定）——いずれもページが完全にレンダリング
され日付が明記されていた。

このリポジトリは既存の`marketentry.facts`実装を持たない
blueprint-onlyの状態だったため、`statute.facts`がこのリポジトリ
初のコード実体となった（独自の`deps.edn`を新規作成）。4 tests/11
assertions green。誤って`.cpcache/`をstageしかけたため`.gitignore`
を追加して除外（過去複数リポで同様の混入があったことに気付いたが、
遡っての一斉修正は本tickのスコープ外と判断し見送った）。

首都チェック: 既存のooyake由来organization.edn（Montevideo、
Wikidata Q1335）は史実上一貫して首都であり、P36歴史的首都バグの
対象外と確認。

94リポジトリ・720 factを統合。`"statute/topic" "labor"`での横断query
でuryを含む24カ国すべてが取得できることを確認。

現状: 国軸24件・業界団体軸25件(20業種)・自治体軸16件——65tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 66 — 自治体軸17件目(サンティアゴ))

`cloud-itonami-municipality-chl-santiago`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-chl-santiago/commit/fc83ab0))：
サンティアゴ市（Comuna de Santiago）。`transparencia.munistgo.cl`の
PDFはTLS証明書エラー（"unable to verify the first certificate"——
このファミリーで初めての障害クラス）で使用不能だったため、代わりに
市公式`documentos.munistgo.cl`の「Decretos y Ordenanzas」一覧ページを
直接WebFetch検証: Reglamento N°971-2025（積極的透明性・公的情報
アクセス規則、2025-12-19）／Ordenanza N°130（廃止済み架空・地下配線
の撤去に関する条例、2026-05-13）。ページが完全にレンダリングされ
両文書の日付が明記されていた。4 tests/10 assertions green。

首都チェック: 既存のooyake由来organization.edn（Santiago、Wikidata
Q2887）は史実上一貫して首都であり、P36歴史的首都バグの対象外と確認。

95リポジトリ・722 factを統合。`"ordinance/topic" "transparency"`での
横断queryでsantiagoがtokyo/washington-dc/berlin/madrid/seoul/roma/
buenos-airesと並んで取得できることを確認。

現状: 国軸24件・業界団体軸25件(20業種)・自治体軸17件——66tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 67 — 業界団体軸26件目(ACC) — 新規ISIC業種(基礎化学品))

`cloud-itonami-assoc-2011-usa-acc`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-2011-usa-acc/commit/bb1cbc7))：
American Chemistry Council（ACC、Wikidata Q4743356、1872年設立時の
名称はManufacturing Chemist Association of the United States）。
`americanchemistry.com`の公式ページ2件を直接WebFetch検証:
「Our 150 Years History」（1872年設立、月日の記載はページ上に見当た
らずyear-onlyのまま採用し捏造せず）／「Responsible Care Overview」
（化学業界の代表的な自主規制安全・持続可能性プログラム、"Launched
in the U.S. in 1988"と本文に明記、こちらも月日は不記載でyear-only）。
4 tests/11 assertions green。

**新規ISIC業種**: ISIC 2011(基礎化学品製造業)を初めて追加した。

96リポジトリ・724 factを統合。`"association-rule/topic"
"governance"`での横断queryでaccを含む22団体が取得できることを確認。

現状: 国軸24件・業界団体軸26件(21業種)・自治体軸17件——67tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 68 — 自治体軸18件目(ボゴタ))

`cloud-itonami-municipality-col-bogota`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-col-bogota/commit/53d8aff))：
ボゴタ首都特別区。まずメキシコシティを試みたが`cdmx.gob.mx`系の
サブドメイン3つが同一IP（187.218.8.10）でいずれも接続拒否——ホスト
クラスタ全体が到達不能と判断し断念。次にボゴタの通常第一候補
`alcaldiabogota.gov.co`も接続拒否で断念。代わりに市公式
`secretariageneral.gov.co`の「acuerdos」一覧ページを直接WebFetch
検証: Acuerdo 001 de 2026（デジタル認証技術標準、2026-02-05発効）／
Acuerdo 002 de 2025（品質支出・地区近代化委員会内部規則、2025-10-28
発効）。ページが完全にレンダリングされ発効日・公布日が明記されて
いた。4 tests/10 assertions green。

首都チェック: 既存のooyake由来organization.edn（Bogotá、Wikidata
Q2841）は史実上一貫して首都であり、P36歴史的首都バグの対象外と確認。

97リポジトリ・726 factを統合。`"ordinance/topic" "governance"`での
横断queryでbogotaがsydney/helsinki/copenhagen/oslo/brusselsと並んで
取得できることを確認。

現状: 国軸24件・業界団体軸26件(21業種)・自治体軸18件——68tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 69 — 国軸25件目(コスタリカ))

`cloud-itonami-iso3166-cri`に`statute.facts`を新規追加・push
([commit 3065db3](https://github.com/cloud-itonami/cloud-itonami-iso3166-cri/commit/3065db3))：
コスタリカ共和国。公式`pgrweb.go.cr`（SCIJ、Sistema Costarricense de
Información Jurídica、Procuraduría General de la República運営）の
3件を直接WebFetch検証: Código de Comercio（商法典、Ley N.º 3284、
1964-04-30制定・2012-09-10最終改正版）／データ保護法（Ley N.º 8968、
2011-07-07制定）／Código de Trabajo（労働法典、Ley N.º 2、
1943-08-27制定・2026-04-07最終改正版）——いずれもページが完全に
レンダリングされ制定日・施行日・改正版バージョンが明記されていた。

ウルグアイと同様、このリポジトリも既存の`marketentry.facts`実装を
持たないblueprint-onlyの状態だったため、`statute.facts`がこの
リポジトリ初のコード実体となった（独自の`deps.edn`+`.gitignore`を
新規作成、前tickの教訓を活かし`.cpcache/`混入は今回発生せず）。
4 tests/11 assertions green。

首都チェック: 既存のooyake由来organization.edn（San José、Wikidata
Q3070）は史実上一貫して首都であり、P36歴史的首都バグの対象外と確認。

98リポジトリ・729 factを統合。`"statute/topic" "labor"`での横断query
でcriを含む25カ国すべてが取得できることを確認。

現状: 国軸25件・業界団体軸26件(21業種)・自治体軸18件——69tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 70 — 自治体軸19件目(サンホセ) — 70tick到達)

`cloud-itonami-municipality-cri-san-jose`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-cri-san-jose/commit/ee63aff))：
サンホセ市（Cantón Central、コスタリカ）。市公式`msj.go.cr`は
ドメイン全体が接続拒否（`ECONNREFUSED 196.40.1.83:443`）で完全に
到達不能だったため、直前のtickでコスタリカ国レベル統計に使った
`pgrweb.go.cr`（SCIJ、国の法令情報システムだが自治体条例も索引化
している）に切り替えて2件検証: Reglamento Autónomo de Organización
y Servicio de la Municipalidad de San José（組織・サービス自治規則、
1997-08-26制定・2009-03-03版）／Reglamento de Publicidad Exterior
（屋外広告規則、サンホセ市都市開発規則群の一部、1995-01-24原公布・
2023-12-21最終改正）。ページが完全にレンダリングされ制定日・改正
履歴が明記されていた。4 tests/10 assertions green。

首都チェック: 既存のooyake由来organization.edn（San José、Wikidata
Q3070）は史実上一貫して首都であり、P36歴史的首都バグの対象外と確認。

99リポジトリ・731 factを統合。`"ordinance/topic" "governance"`での
横断queryでsan-joseがsydney/helsinki/copenhagen/oslo/brussels/bogota
と並んで取得できることを確認。

現状: 国軸25件・業界団体軸26件(21業種)・自治体軸19件——70tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 71 — 業界団体軸27件目(AMA) — 新規ISIC業種(一般医療) — 100リポジトリ到達)

`cloud-itonami-assoc-8621-usa-ama`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-8621-usa-ama/commit/d8f6f08))：
American Medical Association（AMA、Wikidata Q465697）。まず
American Hospital Association（`aha.org`）を試みたが`/about/history`
`/125` `/about`のいずれもHTTP 403でWebFetchを完全にブロックしたため
断念。代わりに`ama-assn.org`の公式ページ2件を直接WebFetch検証:
「AMA History」（1847年設立、"An 1845 resolution to the New York
Medical Association by Dr. Nathan S. Davis...led to the establishment
of the American Medical Association (AMA) in 1847"と本文に明記、
月日は不記載でyear-only）／「Code of Medical Ethics」（AMA倫理綱領、
"first adopted at the AMA's founding meeting in 1847"と明記、こちらも
year-only——二次情報源では1847年5月7日フィラデルフィアという具体的
日付があるが、ama-assn.org自身では未確認のため採用せず）。4 tests/11
assertions green。

**新規ISIC業種**: ISIC 8621(一般医療業務)を初めて追加した。

**100リポジトリ到達**: このtickでcloud-itonami-compliance-fact-federation
全体が100リポジトリ・733 factに到達した——国軸25・業界団体軸27
(22業種)・自治体軸19、全71tickを通じて捏造ゼロを維持。

`"association-rule/topic" "governance"`での横断queryでamaを含む23
団体が取得できることを確認。

現状: 国軸25件・業界団体軸27件(22業種)・自治体軸19件——71tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 72 — 自治体軸20件目(サンパウロ))

`cloud-itonami-municipality-bra-sao-paulo`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-bra-sao-paulo/commit/984b9b9))：
サンパウロ市（ブラジル最大都市だが首都ではない——ブラジリアが首都。
トロント/シドニーと同様、非首都の主要都市として同列に扱う）。市公式
`legislacao.prefeitura.sp.gov.br`（Catálogo de Legislação Municipal）
のページ2件を直接WebFetch検証: Lei Orgânica do Município de São
Paulo（市基本法・組織法、1990-04-04制定）／Decreto N.º 53.623
（連邦情報公開法を市レベルで実施する政令、2012-12-12制定）。ページ
が完全にレンダリングされ両文書の日付が明記されていた。4 tests/10
assertions green。

101リポジトリ・735 factを統合。`"ordinance/topic" "transparency"`
での横断queryでsao-pauloがwashington-dc/madrid/berlin/roma/seoul/
tokyo/santiago/buenos-airesと並んで取得できることを確認。

現状: 国軸25件・業界団体軸27件(22業種)・自治体軸20件——72tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 73 — 国軸26件目(パナマ))

`cloud-itonami-iso3166-pan`に`statute.facts`を新規追加・push
([commit 2700b86](https://github.com/cloud-itonami/cloud-itonami-iso3166-pan/commit/2700b86))：
パナマ共和国。公式LEGISPAN（`s3-legispan.asamblea.gob.pa`、パナマ
国民議会自身の法令メタデータアーカイブ）の2件を検証: Ley N.º 2 de
1916（民法典・商法典ほか複数法典を一括承認するオムニバス法、
1916-08-22制定、Gaceta Oficial 2418に1916-09-07掲載）／Ley N.º 81
de 2019（データ保護法、2019-03-26制定、Gaceta Oficial 28743-Aに
2019-03-29掲載）。両PDFとも初回WebFetchでは判読不能なバイナリ
ストリームとして返ってきたが、保存済みPDFパスをReadツールで
再読み込みすると、LEGISPAN特有の構造化メタデータ表紙（Tipo de
Norma / Número / Año / Fecha / Titulo / Gaceta Oficial / Publicada
el）が毎回クリーンにレンダリングされる——このファミリーで見てきた
自由文形式の法令PDFとは異なる、強い一次情報源フォーマットだった。

**3件目を断念**: Código de Trabajo（労働法典、Decreto de Gabinete
252 de 1971）を試みたが、`infojuridica.procuraduria-admon.gob.pa`は
該当recordなし、`organojudicial.gob.pa`はHTTP 403、`mitradel.gob.pa`
（労働省自身のPDF）もHTTP 403——未検証のLEGISPAN S3 URLを推測で
構築することはせず、デンマークのstatute.factsと同じ方針で正直に
2件のみとした。4 tests/11 assertions green。

首都チェック: 既存のooyake由来organization.edn（Panama City、
Wikidata Q3306）は史実上一貫して首都であり、P36歴史的首都バグの
対象外と確認。

102リポジトリ・737 factを統合。`"statute/topic" "data-protection"`
での横断queryでpanを含む26カ国すべてが取得できることを確認。

現状: 国軸26件・業界団体軸27件(22業種)・自治体軸20件——73tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 74 — 自治体軸21件目(モンテビデオ))

`cloud-itonami-municipality-ury-montevideo`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-ury-montevideo/commit/25c4d17))：
モンテビデオ県（Intendencia de Montevideo、ウルグアイ）。県公式
`normativa.montevideo.gub.uy`（Normativa Departamental）のページ
2件を直接WebFetch検証: Resolución IM N.º 326/13（「Municipios」
統治階層新設に伴う規則呼称を「Municipal」から「Departamental」へ
変更、2013-01-21）／Determinación de la Ruina y del Grado de Riesgo
de la Edificación（建物倒壊・危険度判定に関する規則、Dto. JDM
34.353、2012-10-01）。ページが完全にレンダリングされ両文書の日付が
明記されていた。4 tests/10 assertions green。

103リポジトリ・739 factを統合。`"ordinance/topic" "governance"`での
横断queryでmontevideoがbrussels/helsinki/sao-paulo/oslo/sydney/
san-jose/bogota/copenhagenと並んで取得できることを確認。

現状: 国軸26件・業界団体軸27件(22業種)・自治体軸21件——74tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 75 — 業界団体軸28件目(GTIA) — 新規ISIC業種(コンピュータプログラミング))

`cloud-itonami-assoc-6201-usa-gtia`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-6201-usa-gtia/commit/235584b))：
Global Technology Industry Association（GTIA）。調査の過程で重要な
区別を発見した——GTIAは1982年にAssociation of Better Computer
Dealers（ABCD）として設立され1993年にCompTIAへ改称した非営利
membership association の直接の継続体だが、**2025年に「CompTIA」
ブランドと研修・認定事業（A+認定など）は売却され別の営利企業と
なった**——GTIAはその営利企業ではなく、A+認定等は所有していない。
このため本カタログはGTIA自身が公開した文書のみを引用し、A+認定関連
の資料は一切引用しない。`gtia.org`の公式ページ2件を直接WebFetch
検証: 「About Us」（1982年設立、月日不記載でyear-only、1993年
CompTIAへ改称・2025年GTIAとして分離の経緯を本文で直接確認）／
「Code of Conduct」（行動規範、ページには"Updated February 26,
2025"としか記載がなく元の制定日は不明のため、established-dateは
省略しlast-revised-dateのみ採用）。4 tests/11 assertions green。

**新規ISIC業種**: ISIC 6201(コンピュータプログラミング業務)を初めて
追加した。

104リポジトリ・741 factを統合。`"association-rule/topic"
"governance"`での横断queryでgtiaを含む24団体が取得できることを確認。

現状: 国軸26件・業界団体軸28件(23業種)・自治体軸21件——75tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 76 — 自治体軸22件目(ケープタウン))

`cloud-itonami-municipality-zaf-cape-town`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-zaf-cape-town/commit/d9abad6))：
ケープタウン市。まずパナマシティを試みたが`mupa.gob.pa`がドメイン
ルート自体でHTTP 403を返しWebFetchを完全にブロックしたため断念——
リスボン（lisboa.pt）と同じ「ドメイン全体ブロック」パターン。代わりに
市公式`capetown.gov.za`のページ2件を検証: City of Cape Town Municipal
Planning Amendment By-law, 2025（Western Cape州公報Extraordinary
第9117号、2025-08-08掲載——PDF自体はバイナリで判読不能だったが、
保存済みパスをReadツールで再読み込みすると公報表紙が完全にレンダ
リングされた）／City Ombudsman By-law, 2025（市の条例一覧ページで
タイトルと年のみ確認、具体的日付の記載なくyear-onlyのまま採用、
捏造なし）。4 tests/11 assertions green。

南アフリカは首都が3つ（プレトリア=行政首都・既に国レベルの`:hq`に
記録済み、ケープタウン=立法首都・国会所在地、ブルームフォンテーン=
司法首都）——本エントリはケープタウン市自体の政府として追加、
プレトリアの首都記録を置き換えるものではない旨をorganization.ednに
明記。

105リポジトリ・743 factを統合。`"ordinance/topic" "governance"`での
横断queryでcape-townがbrussels/montevideo/helsinki/sao-paulo/oslo/
sydney/san-jose/bogota/copenhagenと並んで取得できることを確認。

現状: 国軸26件・業界団体軸28件(23業種)・自治体軸22件——76tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 77 — 国軸27件目(エクアドル))

`cloud-itonami-iso3166-ecu`に`statute.facts`を新規追加・push
([commit bba9ff7](https://github.com/cloud-itonami/cloud-itonami-iso3166-ecu/commit/bba9ff7))：
エクアドル共和国。公式`gob.ec`（エクアドル政府公式手続きポータル）の
3件を直接WebFetch検証: Ley de Compañías（会社法、Registro Oficial
第312号、1999-11-05公布・1999-10-20署名）／Ley Orgánica de Protección
de Datos Personales（データ保護法、Registro Oficial第459号、
2021-05-26公布・2021-05-10署名）／Código de Trabajo（労働法典、
Registro Oficial第167号、2005-12-16公布・署名）——いずれもページが
完全にレンダリングされ公布日・署名日が明記されていた。

ウルグアイ・コスタリカ・パナマと同様、このリポジトリも既存の
`marketentry.facts`実装を持たないblueprint-onlyの状態だったため、
`statute.facts`がこのリポジトリ初のコード実体となった（独自の
`deps.edn`+`.gitignore`を新規作成）。4 tests/11 assertions green。

首都チェック: 既存のooyake由来organization.edn（Quito、Wikidata
Q2900）は史実上一貫して首都であり、P36歴史的首都バグの対象外と確認。

106リポジトリ・746 factを統合。`"statute/topic" "labor"`での横断
queryでecuを含む26カ国が取得できることを確認（panのみ:labor統計を
持たない——tick73で正直に2件のみとした既存の方針と整合）。

現状: 国軸27件・業界団体軸28件(23業種)・自治体軸22件——77tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 78 — 自治体軸23件目(キト))

`cloud-itonami-municipality-ecu-quito`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-ecu-quito/commit/66ba01e))：
キト首都圏（Distrito Metropolitano de Quito、エクアドル）。前tickで
国レベル統計に使った公式`gob.ec`（自治体・広域行政条例も索引化して
いる）で2件検証: Código Municipal para el Distrito Metropolitano de
Quito（市法典、Ordenanza Metropolitana N.º 072-2024、2024-05-30
公布）／Ordenanza Metropolitana N.º 090-2025（廃棄物総合管理補完
サービス料金、2025-04-09公布）。ページが完全にレンダリングされ
両文書の公布日が明記されていた。4 tests/10 assertions green。

107リポジトリ・748 factを統合。`"ordinance/topic" "governance"`での
横断queryでquitoがbrussels/montevideo/helsinki/sao-paulo/oslo/sydney/
cape-town/san-jose/bogota/copenhagenと並んで取得できることを確認。

現状: 国軸27件・業界団体軸28件(23業種)・自治体軸23件——78tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 79 — 業界団体軸29件目(API) — 新規ISIC業種(原油採掘))

`cloud-itonami-assoc-0610-usa-api`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-0610-usa-api/commit/3be8ae3))：
American Petroleum Institute（API、Wikidata Q466043——ソフトウェアの
Application Programming Interfaceと同じ略称のため、docstring/README
で明示的に区別を記載）。`api.org`の公式ページ2件を直接WebFetch検証:
「About API」（1919-03-20設立、記事本文で直接確認）／「API's 100
Years of Standards」マイクロサイト（APIの最初の技術規格
「Specifications for Steel and Iron Pipe for Oil Country Tubular
Goods」、1924-10-20公開と本文で直接確認）。4 tests/11 assertions
green。

**新規ISIC業種**: ISIC 0610(原油採掘業)を初めて追加した。

108リポジトリ・750 factを統合。`"association-rule/topic"
"governance"`での横断queryでapiを含む25団体が取得できることを確認。

現状: 国軸27件・業界団体軸29件(24業種)・自治体軸23件——79tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 80 — 自治体軸24件目(イェーテボリ) — 80tick到達)

`cloud-itonami-municipality-swe-gothenburg`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-swe-gothenburg/commit/792a928))：
イェーテボリ市（ヨーテボリ、スウェーデン第2の都市）。以前のtickで
ストックホルムを断念（廃棄物規則PDFがタイトルすら判読不能なほど
文字化け）していたが、イェーテボリ公式`goteborg.se`のPDFは正常に
レンダリングされた。2件検証: Lokala ordningsföreskrifter för
Göteborgs kommun（地方公共秩序規則——タイトルと法的根拠(SFS
1993:1617/1993:1632)は保存済みPDF表紙を直接読んで確認したが、施行日
2025-01-01（コミューン議会は2024-10-04に決定）は原本PDFが10MB超で
直接WebFetch不能だったため、tick52のNAB Political Broadcast
Catechismと同じ方針でWebSearch裏付けの日付として明示的にタグ付け）／
Göteborgs Stadsmiljöpolicy（都市環境政策——タイトルと内容は直接
確認したが、ページ上で見えた唯一の日付は別文書(Översiktsplan för
Göteborg)からの引用部分に付随するものだったため、誤帰属を避けて
enacted-dateは意図的に省略）。4 tests/10 assertions green。

109リポジトリ・752 factを統合。`"ordinance/topic" "urban-planning"`
での横断queryでgothenburgがsan-jose/cape-townと並んで取得できる
ことを確認。

現状: 国軸27件・業界団体軸29件(24業種)・自治体軸24件——80tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 81 — 国軸28件目(パラグアイ))

`cloud-itonami-iso3166-pry`に`statute.facts`を新規追加・push
([commit c96ecb9](https://github.com/cloud-itonami/cloud-itonami-iso3166-pry/commit/c96ecb9))：
パラグアイ共和国。まずボリビアを試みたが4つの異なる公式政府ドメイン
がそれぞれ異なる障害で全滅した——
`economiayfinanzas.gob.bo`はTLS証明書エラー、
`gacetaoficialdebolivia.gob.bo`は2つの異なるURL（ルート含む）で
接続リセット、`asfi.gob.bo`は出力なしで失敗、`silep.gob.bo`はDNS
解決自体が失敗——一切引用せず完全に断念した。

代わりにパラグアイで2件検証: Código Civil（民法典、Ley N.º
1.183/85）——タイトルは`bacn.gov.py`（本来の一次情報源、Biblioteca y
Archivo Central del Congreso Nacional）がHTTP 403のため、代わりに
別の公式機関`conatel.gov.py`がミラーするPDF表紙を直接読んで確認、
制定日（1985-12-18公布・1985-12-23公布官報）は独立した2つの情報源で
裏付けられたWebSearch裏付け日付として明示的にタグ付け（前tickの
イェーテボリと同じ方針）／Ley N.º 7593/2025（データ保護法）——
`silpy.congreso.gov.py`（パラグアイ議会公式立法情報システム）で
タイトル・日付とも直接確認（可決2025-11-05・公布/公表2025-11-27）。
4 tests/11 assertions green。

ウルグアイ・コスタリカ・パナマ・エクアドルと同様、このリポジトリも
既存の`marketentry.facts`実装を持たないblueprint-onlyの状態だった
ため、`statute.facts`がこのリポジトリ初のコード実体となった。

首都チェック: 既存のooyake由来organization.edn（Asunción、Wikidata
Q2933）は史実上一貫して首都であり、P36歴史的首都バグの対象外と確認。

110リポジトリ・754 factを統合。`"statute/topic" "data-protection"`
での横断queryでpryを含む28カ国すべてが取得できることを確認。

現状: 国軸28件・業界団体軸29件(24業種)・自治体軸24件——81tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 82 — 自治体軸25件目(アスンシオン))

`cloud-itonami-municipality-pry-asuncion`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-pry-asuncion/commit/5681080))：
アスンシオン市（パラグアイ）。市公式`asuncion.gov.py`のページ2件を
検証: Ordenanza N.º 43/22（プラスチックストロー禁止条例——市の
記事は2022年と施行後6か月の猶予期間のみ明記、正確な採択日
2022-10-05はWebSearch裏付け）／Creación de la Dirección de
Transparencia y Anticorrupción（透明性・反汚職局の設立、2017-11-23
——市自身の発表記事で直接確認）。4 tests/10 assertions green。

111リポジトリ・756 factを統合。`"ordinance/topic" "transparency"`
での横断queryでasuncionがsao-paulo/washington-dc/madrid/berlin/roma/
seoul/tokyo/santiago/buenos-airesと並んで取得できることを確認。

現状: 国軸28件・業界団体軸29件(24業種)・自治体軸25件——82tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 83 — 自治体軸26件目(グアダラハラ))

`cloud-itonami-municipality-mex-guadalajara`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-mex-guadalajara/commit/303fb33))：
グアダラハラ市（メキシコ第2の都市、首都ではない——首都はメキシコ
シティ）。以前のtickでメキシコシティ（`cdmx.gob.mx`）はホスト
クラスタ全体が到達不能で断念していたが、グアダラハラ公式PDFは正常に
レンダリングされた。2件検証: Código de Gobierno Municipal de
Guadalajara（市統治法典——タイトルは直接確認したが、数百条にわたる
長大な法典で「transitorios」節への到達が非現実的だったため、原本の
制定日は見つからず意図的に省略）／Reglamento del Ayuntamiento de
Guadalajara（市議会規則——タイトル・承認日(2010-01-01)ともに公布
ヘッダーを直接読んで確認。ヘッダーには当時の市長個人名が偶然含まれて
いたが、日付特定のためだけに読み、カタログには一切保存していない）。
4 tests/10 assertions green。

112リポジトリ・758 factを統合。`"ordinance/topic" "governance"`での
横断queryでguadalajaraがbrussels/montevideo/quito/helsinki/sao-paulo/
oslo/asuncion/sydney/cape-town/san-jose/bogota/copenhagenと並んで
取得できることを確認。

現状: 国軸28件・業界団体軸29件(24業種)・自治体軸26件——83tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 84 — 国軸29件目(グアテマラ))

`cloud-itonami-iso3166-gtm`に`statute.facts`を新規追加・push
([commit 9974bb4](https://github.com/cloud-itonami/cloud-itonami-iso3166-gtm/commit/9974bb4))：
グアテマラ共和国。本来の一次情報源`congreso.gob.gt`がHTTP 403を
返したため、代わりに国際機関公式の法令データベース2件で検証:
Código de Comercio（商法典、Decreto N.º 2-70）——WIPO（世界知的所有権
機関）のWIPO Lex法令データベースで直接確認、1970-01-28採択・
1970-03-30施行／Ley de Acceso a la Información Pública（情報公開法、
Decreto N.º 57-2008）——CEPAL（国連ラテンアメリカ・カリブ経済委員会）
のObservatorio del Principio 10で直接確認、2008-10-23制定。

**データ保護法エントリなし**: グアテマラには2025年時点で包括的な
データ保護法が存在しないことをWebSearchで確認（3つの競合法案が
係属中のみ）——捏造せず正直にエントリを設けなかった。

ウルグアイ・コスタリカ・パナマ・エクアドル・パラグアイと同様、この
リポジトリも既存の`marketentry.facts`実装を持たないblueprint-only
の状態だったため、`statute.facts`がこのリポジトリ初のコード実体と
なった。4 tests/11 assertions green。

首都チェック: 既存のooyake由来organization.edn（Guatemala City、
Wikidata Q1555）は史実上一貫して首都であり、P36歴史的首都バグの
対象外と確認。

113リポジトリ・760 factを統合。`"statute/topic"
"corporate-governance"`での横断queryでgtmを含む28カ国が取得できる
ことを確認。

現状: 国軸29件・業界団体軸29件(24業種)・自治体軸26件——84tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 85 — 自治体軸27件目(リヨン)) — セキュリティ所見あり

`cloud-itonami-municipality-fra-lyon`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-municipality-fra-lyon/commit/8d70753))：
リヨン市（フランス第3の都市、首都ではない——首都パリは
`cloud-itonami-municipality-fra-paris`で既にカバー済み）。

**セキュリティ所見**: まずグアテマラシティ（`muniguate.com`）を試みた
が、公式ドメインが**まるごと**不審な類似ドメイン`munigate10.com`へ
301リダイレクトされることを確認した——ドメイン乗っ取り・期限切れ
放置の悪用の可能性が高いと判断し、**このリダイレクトには一切従わず**
そのドメインからのコンテンツは何も引用せずに断念した。

代わりにリヨン公式`lyon.fr`の議決（délibération）PDF2件を検証:
Délibération 2021/1164（社会住宅割当政策の承認、2021-09-30）／
Délibération 2021/725（リヨン市の恒久的テレワーク制度導入、
2021-05-27）。いずれも完全にレンダリングされ日付が明記されていた。
4 tests/10 assertions green。

114リポジトリ・762 factを統合。`"ordinance/topic" "governance"`での
横断queryでlyonがbrussels/montevideo/quito/helsinki/sao-paulo/oslo/
asuncion/sydney/cape-town/san-jose/guadalajara/bogota/copenhagenと
並んで取得できることを確認。

現状: 国軸29件・業界団体軸29件(24業種)・自治体軸27件——85tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 86 — 業界団体軸30件目(AFBF) — 新規ISIC業種(混合農業))

`cloud-itonami-assoc-0150-usa-afbf`を新規scaffold・push
([初回commit](https://github.com/cloud-itonami/cloud-itonami-assoc-0150-usa-afbf/commit/9f6dfb2))：
American Farm Bureau Federation（AFBF、Wikidata Q4743741）。`fb.org`
の公式ページ2件を直接WebFetch検証: 「Who We Are」（1919-11-12、
シカゴで34州のFarm Bureauリーダーにより組織化と本文で直接確認）／
「What We Do」（AFBFの政策提言機能の説明——独自の日付は記載なし、
最近の大会ニュースリリースやポリシーブックPDFなど、独立した日付を
持つ2件目の文書を複数試みたがいずれもHTTP 404/403で断念したため、
established-dateは捏造せず意図的に省略）。4 tests/11 assertions
green。

**新規ISIC業種**: ISIC 0150(混合農業)を初めて追加した——AFBFは
多様な作物・畜産にまたがる連合体のため、ISIC Rev.4に存在しない
「一般農業」の代わりに最も近い代表分類として採用。

115リポジトリ・764 factを統合。`"association-rule/topic"
"governance"`での横断queryでafbfを含む26団体が取得できることを
確認。

現状: 国軸29件・業界団体軸30件(25業種)・自治体軸27件——86tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 87 — 国軸30件目(HND))

`cloud-itonami-iso3166-hnd`の`statute.facts`を新規scaffold・push
([commit 36c1a05](https://github.com/cloud-itonami/cloud-itonami-iso3166-hnd/commit/36c1a05))：
ホンジュラスの一般法2件、いずれもテキスト抽出失敗後にPDFを画像として
目視確認して直接検証:

- **Código de Comercio（Decreto N.º 73-50）** — BCH（ホンジュラス中央
  銀行）自身のPDFミラーはフォントサブセット化がひどく完全に判読不能
  （国章のみ識別可能）だったため、同一法令のWIPO Lexミラーを代わりに
  使用。WIPO Lexの本文自体が「Promulgación: 1 de mayo de 1950」と直接
  明記（RAEの二次citation「1950年2月17日（decree署名日）」より、一次
  資料自身の公布日を優先）。
- **Ley de Transparencia y Acceso a la Información Pública（Decreto
  Legislativo N.º 170-2006）** — Tribunal Superior de Cuentas（TSC、
  ホンジュラス公式会計検査機関）自身のミラーで直接確認。1ページ目に
  「Diario Oficial La Gaceta, 30 de diciembre de 2006」「Decreto
  Legislativo No. 170 – 2006」と判読可能に明記。

`cloud-itonami-iso3166-ury/-cri/-pan/-ecu/-pry/-gtm`と同様、この
リポジトリには既存の`marketentry.facts`実装が無かった（blueprint-only）
ため、`statute.facts`が初のコード資産——新規の自己完結的な`deps.edn`+
`.gitignore`から作成。4 tests/11 assertions green。

**capital-check**: 既存のooyake由来`organization.edn`はテグシガルパ
（Q3238）を正しく首都としている——1880年以来一貫しており、JPN/DEU/BRA
で見つかったP36史的首都バグの影響なしを確認。

116リポジトリ・766 factを統合。`"statute/topic" "transparency"`での
横断queryでHNDがGTMと共に取得できることを確認。

現状: 国軸30件・業界団体軸30件(25業種)・自治体軸27件——87tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 88 — 自治体軸28件目(New Delhi, IND) — 新規リポジトリ作成)

`cloud-itonami-municipality-ind-new-delhi`を**新規GitHubリポジトリとして**
scaffold・push（既存のblueprint拡張ではなく、LICENSE/README/blueprint.edn/
organization.ednを含む完全新規scaffold、
[commit ddda468](https://github.com/cloud-itonami/cloud-itonami-municipality-ind-new-delhi/commit/ddda468)）：

New Delhi Municipal Council（NDMC）——首都New Delhiの中心部（Lutyens'
Delhi、約42km²）のみを管轄する特別自治体で、より広いDelhi NCT自体は
別途Municipal Corporation of Delhi（MCD）が管轄（本カタログの対象外）。
米国Washington D.C.と同型の連邦特別区パターン。

ndmc.gov.in自身の生PDFはフォントサブセット化でヘッダーが判読不能
だったため、両エントリともndmc.gov.in自身のクリーンなHTMLページで
直接確認:

- **The New Delhi Municipal Council Act, 1994（Act No. 44 of 1994）**
  — `act.aspx`でタイトルを直接確認。「(44 of 1994)」という正確な
  citationは、別のbye-law文書自身の前文が"the New Delhi Municipal
  Council Act, 1994 (44 of 1994)"と逐語引用していることで独立に
  裏付けられた。1994年7月14日の裁可日はWebSearch裏付け（複数の
  独立引用ソースが一致、一次資料の日付欄を直接読んだものではない）。
- **The New Delhi Municipal Council（Licensing and Control of
  Plumbers）Bye-laws, 2006** — タイトルと前文全文を直接確認したが、
  本文中に正確な施行日の記載が無かったため`:ordinance/enacted-date`
  は意図的に省略。

4 tests/10 assertions green。117リポジトリ・768 factを統合。
`"ordinance/topic" "governance"`での横断queryでnew-delhiが14件の
他自治体と共に取得できることを確認。

**capital-check**: New Delhi（Q987）が現在のインド首都であり
（1911/1931年にカルカッタから遷都）、既存の`cloud-itonami-iso3166-ind`
organization.ednと一致——史的首都バグの影響なしを確認。

現状: 国軸30件・業界団体軸30件(25業種)・自治体軸28件——88tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 89 — 業界団体軸31件目(SMMT, GBR, ISIC 2910) — 国別multiplicity拡張)

`cloud-itonami-assoc-2910-gbr-smmt`を新規GitHubリポジトリとして
scaffold・push
([commit a3b66c7](https://github.com/cloud-itonami/cloud-itonami-assoc-2910-gbr-smmt/commit/a3b66c7))：

Society of Motor Manufacturers and Traders（SMMT、英国自動車工業会、
Wikidata Q7552564）。smmt.co.uk自身のHistoryページを直接WebFetch
検証:

- **SMMT設立（1902年7月22日）** — ページに明記「On 22 July 1902 the
  Society of Motor Manufacturers and Traders (SMMT) was created」
- **第1回SMMTモーターショー（Crystal Palace、1903年）** — 「January
  1903」とあり日にちの記載が無いため年のみで格納（既存の他エントリと
  同じ「年のみ正直記録」規律に合わせた）

ページは現President/Chief Executiveの氏名も記載していたが、統治構造の
説明確認のためだけに読み、氏名は一切保存していない。4 tests/11
assertions green。

**多様化の狙い**: 業界団体軸はこれまでUSA（30件中18件）・日本（同8件）
に大きく偏り、EU圏は3件（VDA/Bankenverband/FBF）、英国は0件だった——
SMMTが軸初の英国拠点団体。またISIC 2910（自動車製造）2件目
（ドイツVDAに次ぐ）——ISIC 6419（全銀協/Bankenverband/FBF）で既に
確立した「同一ISIC・複数国」パターンを踏襲。

118リポジトリ・770 factを統合。`"association-rule/isic" "2910"`での
横断queryで`[vda DEU]`と`[smmt GBR]`の両方が正しく取得できることを
確認。

**このtickの開始時同期で一時的な事象**: west-pin-verify-guardフックに
1回引っかかった（aiueos/compiler/kotoba-fleet-vcsの3リポが、並行
セッションのpin修正マージ中の過渡状態でmainより一時的に遅れていた）。
数分後にorigin/mainを再fetchしてmerge+pushをリトライしたところ問題
なく解消——本セッション序盤(tick約57)で一度見られたのと同じ、pin手動
修正不要の一過性事象パターン。

現状: 国軸30件・業界団体軸31件(25業種)・自治体軸28件——89tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 90 — 自治体軸29件目(Warsaw, POL) — 中東欧初、多ドメイン難航)

`cloud-itonami-municipality-pol-warsaw`を新規GitHubリポジトリとして
scaffold・push
([commit 3b67cba](https://github.com/cloud-itonami/cloud-itonami-municipality-pol-warsaw/commit/3b67cba))：

自治体軸で初の中東欧エントリ。ソース確保が難航——5つの異なる公式
ドメインで異なる失敗:

- **isap.sejm.gov.pl**（ポーランド国会・国家法令データベース）—
  `DocDetails.xsp`・`download.xsp`直リンクPDFの両方でCAPTCHA
  ページが表示された。CAPTCHAは絶対に突破しない方針のため
  サイト全体を断念。
- **bip.warszawa.pl / um.warszawa.pl / transport.um.warszawa.pl** —
  いずれもHTTP 403。
- **edziennik.mazowieckie.pl**（マゾフシェ県の公式官報）— 3つの
  異なる文書URLすべてでタイムアウト。
- **warszawa19115.pl** — 接続そのものを拒否（ECONNREFUSED）。

最終的に2つの生きたドメインから直接確認:

- **Ustawa z dnia 15 marca 2002 r. o ustroju miasta stołecznego
  Warszawy**（2002年3月15日ワルシャワ首都制度法、Dz.U. 2002 Nr 41
  poz. 361）— Kancelaria Sejmu（国会官房）発行の統合テキストPDFが
  `up.warszawa.pl`にミラーされており、表紙ページを目視確認（タイトル・
  官報citation・第1条本文すべて判読可能）。
- **Uchwała Nr XXXIX/1587/2026 Rady m.st. Warszawy**（2026年7月2日の
  市議会決議）— `eto.um.warszawa.pl`自身の決議詳細ページで直接確認。

4 tests/10 assertions green。119リポジトリ・772 factを統合。
`"ordinance/kind" "local-act"`での横断queryで`[london new-delhi
warsaw]`——創設憲章型の3自治体法がすべて正しく取得できることを確認。

**capital-check**: ワルシャワ（Q270）は1596年（クラクフから遷都）以来
一貫してポーランドの首都であり、既存の`cloud-itonami-iso3166-pol`
organization.ednと一致——史的首都バグの影響なしを確認。

現状: 国軸30件・業界団体軸31件(25業種)・自治体軸29件——90tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 91 — 国軸31件目(IND) — tick88のcapital-check再利用)

`cloud-itonami-iso3166-ind`の`statute.facts`を追加
([commit 7861e76](https://github.com/cloud-itonami/cloud-itonami-iso3166-ind/commit/7861e76))：

インドの一般法2件、いずれもmeity.gov.in・mca.gov.inがHTTP 403だったため、
prsindia.org（PRS Legislative Research、非政府だが高い信頼性を持つ
インドの立法調査機関）にミラーされている**官報（Gazette of India）の
実物そのもの**を直接確認:

- **The Companies Act, 2013（Act No. 18 of 2013）** — Gazette of
  India masthead、Ministry of Law and Justice発行者情報、タイトル、
  「29th August, 2013」の大統領裁可日、すべて判読可能。
- **The Digital Personal Data Protection Act, 2023（Act No. 22 of
  2023）** — Gazetteのmastheadと「August 11, 2023」の日付行は判読
  可能だったが、法令タイトル本文はフォント崩壊で判読不能。正確な
  Act番号citationはWebSearchツール自身のインデックス済みタイトル
  スニペットと、複数の独立引用ソース（Wikipedia・AO Shearman・
  Future of Privacy Forum・OneTrust）が一致することで裏付けた。

コンテンツ自体が官報の実物であることを明示するため、両エントリとも
`:official-gazette-of-india-prsindia-mirror`という、ホスト元
（非政府）と内容の公式性を区別するタグを使用。

このリポジトリは最近のLatAm系ブループリント専用リポジトリと異なり、
既存の`marketentry.facts`実装（langgraph依存）が既にあったため、
同一`deps.edn`の下に`statute.facts`を新規namespaceとして追加
（新規deps.edn/.gitignore不要）。4 tests/9 assertions green
（既存のmarketentry 24 tests/81 assertionsと合わせ計28 tests/90
assertions green）。

**capital-check**: tick88で既に検証済みのニューデリー（Q987）＝
インド（Q668）現行首都・P36史的首都バグなしの結果を再利用。

120リポジトリ・774 factを統合。jurisdiction IND queryで今回の2件が
既存のetzhayyim/global-legislation-datoms legal-source（Indian
Kanoon）と正しく共存していることを確認。`"statute/topic"
"data-protection"`での横断queryでINDが他28カ国と共に取得できることを
確認。

現状: 国軸31件・業界団体軸31件(25業種)・自治体軸29件——91tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 92 — 自治体軸30件目(Nairobi, KEN) — サブサハラアフリカ初、矛盾ソース明示的却下)

`cloud-itonami-municipality-ken-nairobi`を新規GitHubリポジトリとして
scaffold・push
([commit e90b671](https://github.com/cloud-itonami/cloud-itonami-municipality-ken-nairobi/commit/e90b671))：

自治体軸で南アフリカ・ケープタウンに次ぐ2件目、サブサハラアフリカ
初のエントリ。

- **nairobi.go.ke** — 試した全URLでドメイン全体のTLS証明書エラー
  （`unable to verify the first certificate`）。本セッション序盤の
  チリ`transparencia.munistgo.cl`・ボリビア
  `economiayfinanzas.gob.bo`と同じ失敗パターンのため断念。
- **kenyalaw.org**（ケニア国家法令報告機関）— サイト全体でHTTP
  403。加えてFinance Act 2013の**メタデータ自体がミラー間で矛盾**
  （あるソースは「Act No. 2 of 2013」9月citation、別ソース
  （new.kenyalaw.org自身のAkoma Ntosoページ）は「Act No. 1b of
  2013」1月citation、さらに別の引用は2014年3月施行日を主張）——
  どれか一つを恣意的に選ばず、その年のActを丸ごと不採用とし、代わりに
  クリーンに確認できたFinance Act 2023を採用。**本セッション初めて、
  取得失敗ではなくミラー間メタデータ矛盾を理由にソースを明示的却下**
  したケース。

最終的に2つの相互裏付けソースから直接確認:

- **Nairobi City County Solid Waste Management Act, 2015**（No. 5 of
  2015）— ecolex.org（FAO/UNEP/IUCN共同運営の国際環境法データベース）
  でタイトル・番号・Kenya Gazette Supplement公布日（2015年10月22日）
  を直接確認。
- **The Nairobi City County Finance Act, 2023**（No. 4 of 2023）—
  nairobiassembly.go.ke（nairobi.go.keとは別ドメイン）自身のKenya
  Gazette Supplement表紙・本文を目視確認、「Date of Assent: 13th
  October, 2023」を直接確認。

4 tests/11 assertions green。121リポジトリ・776 factを統合。
`"ordinance/topic" "waste-management"`での横断queryで
`[copenhagen quito nairobi]`が正しく取得できることを確認。

**capital-check**: ナイロビ（Q3870）は1907年（モンバサから遷都）以来、
1963年の独立を経て現在まで一貫してケニア（Q114）の首都であり、史的
首都バグの影響なしを確認。

現状: 国軸31件・業界団体軸31件(25業種)・自治体軸30件——92tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 93 — 業界団体軸32件目(ABA, AUS, ISIC 6419) — 銀行業4カ国目、2ソース断念を経て)

`cloud-itonami-assoc-6419-aus-aba`を新規GitHubリポジトリとして
scaffold・push
([commit e1452f3](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-aus-aba/commit/e1452f3))：

Australian Banking Association（ABA、豪州銀行協会、Wikidata
Q55604504）。ISIC 6419（銀行業）の4カ国目（日本Zenginkyo・ドイツ
Bankenverband・フランスFBFに次ぐ）——ISIC 2910（VDA/SMMT）で既に
確立した「同一ISIC・複数国」パターンを踏襲。

このtickは2つのソースを断念した後に確保:

- **NASSCOM**（nasscom.in、インドのソフトウェア業界団体——ISIC
  6201の新規国候補だった）— ルートページを含む試した全URLでHTTP
  406。本セッション初の「ドメイン全体406ブロック」失敗クラス
  （403/CAPTCHA/TLS証明書/DNS/timeout/redirectとは別種）。
- **Canadian Bankers Association**（cba.ca）— History・
  Milestonesページとも解決不能なHTTP 307リダイレクト
  （muniguate.comのケースと異なりリダイレクト先ホストが示されない）、
  代替の公的調査委員会PDFミラーもフォント崩壊でほぼ全文判読不能——
  断念。

最終的にABA自身の公式Historyページ（ausbanking.org.au）から2件を
直接確認:

- **1985年の再編** — 3団体（Australian Banking
  Association-Research Directorate・Australian Banking
  Association・Banking Education Service）合併＋Banks' Industrial
  Association統合により現ABAが成立、新定款制定。
- **1997年のミッション再定義** — 「政府・メディア・公衆に対する
  銀行業界の代弁者」としてのadvocacy機能へ焦点を絞り直し。

いずれも組織自身のページが正確な日にちを示していないため年のみで
記録。4 tests/11 assertions green。122リポジトリ・778 factを統合。
`"association-rule/isic" "6419"`での横断queryで`[zenginkyo JPN]`・
`[bankenverband DEU]`・`[fbf FRA]`・`[aba AUS]`の4件すべてが正しく
取得できることを確認。

現状: 国軸31件・業界団体軸32件(25業種)・自治体軸30件——93tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 94 — 国軸32件目(KEN) — tick92のナイロビcapital-check再利用、WebSearch日付誤りを訂正)

`cloud-itonami-iso3166-ken`の`statute.facts`を追加
([commit 30ddb88](https://github.com/cloud-itonami/cloud-itonami-iso3166-ken/commit/30ddb88))：

ケニアの一般法2件、いずれもnew.kenyalaw.org（Kenya Law公式法令
データベース——1tick前にナイロビFinance Act 2023でも使用した同一
ドメイン）で直接確認:

- **Companies Act**（No. 17 of 2015, Cap. 486）— 2015年9月11日裁可、
  2015年9月18日官報公布。
- **Data Protection Act**（No. 24 of 2019, Cap. 411C）— 一次資料
  自身が「Assented to on 8 November 2019」と「Commenced on 25
  November 2019」を明確に区別して記載。事前のWebSearch要約は両者を
  混同し裁可日を誤って11月25日としていたが、一次資料の直接確認で
  正しい裁可日（11月8日）に訂正。

ZAF/COL/INDと同様、既存の`marketentry.facts`実装がある同一
`deps.edn`の下に`statute.facts`を新規namespaceとして追加。4
tests/9 assertions green（既存marketentry 24 tests/81 assertions
と合わせ計28 tests/90 assertions green）。

**capital-check**: tick92で既に検証済みのナイロビ（Q3870、1907年
以来ケニア(Q114)の首都）・P36史的首都バグなしの結果を再利用。

123リポジトリ・780 factを統合。`"statute/topic"
"corporate-governance"`での横断queryでKENが他30カ国と共に取得できる
ことを確認。

現状: 国軸32件・業界団体軸32件(25業種)・自治体軸30件——94tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 95 — 自治体軸31件目(Bangkok, THA) — 東南アジア初、誤文書混入を検出・訂正)

`cloud-itonami-municipality-tha-bangkok`を新規GitHubリポジトリとして
scaffold・push
([commit fb9b1d6](https://github.com/cloud-itonami/cloud-itonami-municipality-tha-bangkok/commit/fb9b1d6))：

自治体軸で東南アジア初のエントリ。

- **Administrative Organisation of Bangkok Metropolitan
  Administration Act, 2528 BE**（1985年8月20日発布）—
  en.wikisource.org（王室官報テキストの逐語英訳を提供する
  Wikimediaプロジェクト）で直接確認。
- **Bangkok Metropolitan Council founding**（Declaration No. 335 of
  the Revolution Committee、1972年12月13日）— bmc.go.th
  （official.bangkok.go.thとは別の稼働ドメイン）自身のHistoryページ
  で直接確認。official.bangkok.go.th自身のAboutページはHTTP 403。

**誤文書混入の検出・訂正**: タイトル・URLとも「1985年原法」を指す
Wikisourceページ（`Translation:...BE_2528_(1985)/2007.08.01`）を
開いたところ、実際の本文は**1991年の改正法**（"Act (No 2), 2534
BE"）だった——本文自身の記載タイトル・日付を期待値と照合することで
発見し、URLが異なる正しい原法ページ（`/Translation:`接頭辞・
`/2007.08.01`接尾辞なし）に切り替えて再確認した。

4 tests/11 assertions green。124リポジトリ・782 factを統合。
`"ordinance/kind" "local-act"`での横断queryで`[london new-delhi
warsaw bangkok]`——創設憲章型の4自治体法がすべて正しく取得できる
ことを確認。

**capital-check**: バンコク（Q1861）は1782年以来一貫してタイ
（Q869）の首都であり、史的首都バグの影響なしを確認。

現状: 国軸32件・業界団体軸32件(25業種)・自治体軸31件——95tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 96 — 業界団体軸33件目(Norges Rederiforbund, NOR, ISIC 5010) — 新規業種・ノルウェー団体初)

`cloud-itonami-assoc-5010-nor-rederiforbundet`を新規GitHubリポジトリ
としてscaffold・push
([commit 9ede11f](https://github.com/cloud-itonami/cloud-itonami-assoc-5010-nor-rederiforbundet/commit/9ede11f))：

Norwegian Shipowners' Association（Norges Rederiforbund、Wikidata
Q7061257）。**ISIC 5010（海上・沿岸水運）の初エントリ**——航空（5110）・
トラック輸送（4923）とは異なる新規輸送業種コード。またノルウェー
初の業界団体軸エントリ（これまでノルウェーは自治体軸のOsloのみ）。

rederi.no自身の2ページを直接WebFetch検証:

- **Vår historie**（沿革）— 「Konstituerende møte ble avholdt 15.
  september 1909 og det ble enstemmig vedtatt å stifte Norges
  Rederforbund」（1909年9月15日の創立総会で満場一致設立決定）と
  明記。
- **About us** — Thor Heyerdahl International Maritime Award
  （1999年創設、卓越した技術革新・環境活動を表彰）を記述。

4 tests/11 assertions green。125リポジトリ・784 factを統合。
`"association-rule/isic" "5010"`での横断queryで`[rederiforbundet
NOR]`がこの新規ISICコードの唯一（初）のエントリとして正しく
取得できることを確認。さらにNOR国別横断query（association.facts /
ordinance.facts両schema）で、Rederiforbundetが既存のOslo自治体
条例（Reglement for bystyret / Forskrift om serverings-, salgs- og
skjenkebevillinger）と同一連合内に共存していることを確認。

現状: 国軸32件・業界団体軸33件(26業種)・自治体軸31件——96tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 97 — 国軸33件目(THA) — tick95のバンコクcapital-check再利用、民間ミラーを明示的却下)

`cloud-itonami-iso3166-tha`の`statute.facts`を追加
([commit 12a46df](https://github.com/cloud-itonami/cloud-itonami-iso3166-tha/commit/12a46df))：

タイの一般法2件:

- **Copyright Act B.E. 2537（1994年）** — WIPO Lex自身がホストする
  PDF本文を直接読み確認。「This Act may be cited as the Copyright
  Act, B.E. 2537」と明記、1994年12月9日制定、1995年3月21日施行。
- **Personal Data Protection Act B.E. 2562（2019年）** — mdes.go.th
  （タイ・デジタル経済社会省）の「Unofficial Translation」文書として
  真正性を確認（マストヘッド・Government Gazette参照は判読可能）
  したが、正確な公布日テキストはフォント崩壊で判読不能。2019年5月
  24日という日付は複数の独立法律事務所引用ソース（Norton Rose
  Fulbright・Tilleke & Gibbins・Digital Watch Observatory）が一致し、
  かつ判読可能だった官報公布日（5月27日）とも整合するため裏付けと
  して採用。

**民間ミラーの明示的却下**: 民商法典（Civil and Commercial Code）を
検索した際、faolex.fao.org自身のドメイン配下のURLが最初に見つかった
が、実際に開くとFAO自身のコンテンツではなく**samuiforsale.com
（不動産会社の私的サイト）からスクレイピングされたページ**で、
制定日の記載も無かったため却下——代わりにWIPO Lexの著作権法
citationを主要IP関連法として採用。

ZAF/COL/IND/KENと同様、既存の`marketentry.facts`実装がある同一
`deps.edn`の下に`statute.facts`を新規namespaceとして追加。新規
topicタグ`:intellectual-property`を初導入。4 tests/9 assertions
green（既存marketentry 24 tests/81 assertionsと合わせ計28
tests/90 assertions green）。

**capital-check**: tick95で既に検証済みのバンコク（Q1861、1782年
以来タイ(Q869)の首都）・P36史的首都バグなしの結果を再利用。

126リポジトリ・786 factを統合。jurisdiction THA queryで今回の2件が
既存のetzhayyim/global-legislation-datoms legal-sourceと正しく
共存していることを確認。`"statute/topic"
"intellectual-property"`での横断queryでTHAが新規topicの唯一（初）の
エントリとして正しく取得できることを確認。

現状: 国軸33件・業界団体軸33件(26業種)・自治体軸31件——97tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 98 — 自治体軸32件目(Abu Dhabi, ARE) — 中東初)

`cloud-itonami-municipality-are-abu-dhabi`を新規GitHubリポジトリと
してscaffold・push
([commit c7827cd](https://github.com/cloud-itonami/cloud-itonami-municipality-are-abu-dhabi/commit/c7827cd))：

自治体軸で中東初のエントリ。

- **Law No. (2) of 2012**（アブダビ首長国における公共の外観・健康・
  静穏の保持に関する法律）— Al Ain City Municipality（DMT傘下組織）
  自身の違反金PDF表紙で正確なcitationを直接確認（年のみ、日にちの
  記載なし）。
- **Law No. (30) of 2019**（Department of Municipalities and
  Transport設立法）— アブダビ政府自身のOfficial Gazette（第11版）
  目次で正確な法律タイトル・番号を直接確認、2019年11月30日付。

dmt.gov.ae自身の「About Us」沿革ページは歴代首長・法令の物語的記述
はあったが、ほとんどのマイルストーン（1969/2005/2007/2016/2017/
2019）に正確な法律番号・日付が無く使用不可——代わりに的を絞った
検索で2つの正確な日付付きPDFを別々に発見した。

4 tests/11 assertions green。127リポジトリ・788 factを統合。
`"ordinance/kind" "local-act"`での横断queryで`[london new-delhi
warsaw bangkok abu-dhabi]`——創設憲章型の5自治体法がすべて正しく
取得できることを確認。

**capital-check**: アブダビ（Q1519）は1971年の連邦建国以来一貫して
UAE（Q878）の首都であり、史的首都バグの影響なしを確認。

現状: 国軸33件・業界団体軸33件(26業種)・自治体軸32件——98tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 99 — 業界団体軸34件目(UBF, ARE, ISIC 6419) — 銀行業5カ国目、UAEリサーチの継続)

`cloud-itonami-assoc-6419-are-ubf`を新規GitHubリポジトリとして
scaffold・push
([commit 7af41bf](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-are-ubf/commit/7af41bf))：

UAE Banks Federation（UBF、UAE銀行連盟）。ISIC 6419（銀行業）の
5カ国目（日本Zenginkyo・ドイツBankenverband・フランスFBF・
オーストラリアABAに次ぐ）。本tick窓のUAEリサーチ
（[`cloud-itonami-municipality-are-abu-dhabi`](https://github.com/cloud-itonami/cloud-itonami-municipality-are-abu-dhabi)）
の流れを継続。

ubf.ae自身（**TLS証明書エラーの出るuaebf.aeではなく**）の2ページを
直接WebFetch検証:

- **ホームページ** — 「Established in 1982, UAE Banks Federation
  (UBF) is the sole representative body of the member banks and
  financial institutions operating in the UAE」と明記。
- **TASHARUKイニシアティブページ** — 「UBF launched TASHARUK - the
  first Information Sharing and Analysis Center (ISAC) in the United
  Arab Emirates (UAE) in 2017」と明記——加盟銀行向けサイバー脅威
  インテリジェンス共有プラットフォーム。

いずれも年のみで記録。UBF自身のWikidata Q-idは見つからず（UAE中央
銀行など関連団体のみヒット）——`:wikidata`は推測せず意図的に省略。

4 tests/11 assertions green。128リポジトリ・790 factを統合。
`"association-rule/isic" "6419"`での横断queryで5カ国すべて
（`[zenginkyo JPN]`・`[bankenverband DEU]`・`[fbf FRA]`・
`[aba AUS]`・`[ubf ARE]`）が正しく取得できることを確認。

現状: 国軸33件・業界団体軸34件(26業種)・自治体軸32件——99tickを経て
3軸すべてが実データ・個別検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-16, /loop tick 100 【100tick達成】 — 国軸34件目(ARE) — UAEが1tick窓内で3軸すべて完成、複数ソース日付矛盾を2件とも正直に処理)

`cloud-itonami-iso3166-are`の`statute.facts`を追加
([commit e60bec4](https://github.com/cloud-itonami/cloud-itonami-iso3166-are/commit/e60bec4))：

**本ループの100tick目という節目。** さらに本tickにより、UAEが
**国・自治体・業界団体の3軸すべてで実データ・個別検証済みの
エントリを、約4tickの窓の中で完成**させた（自治体: tick98の
`cloud-itonami-municipality-are-abu-dhabi`／業界団体: tick99の
`cloud-itonami-assoc-6419-are-ubf`／国: 本tick）。3軸とも同一
連合内で横断query可能なことを下記verified-runで実証。

UAEの一般法2件:

- **Federal Law No. 2 of 2015 on Commercial Companies** — WIPO Lex
  自身がホストするPDF本文を直接読み確認（2015年3月25日発布、
  3月31日官報公布、7月1日施行）。uaelegislation.gov.ae（連邦法令
  ポータル本家）は直リンクPDFも含め全URLでHTTP 403。WebSearchに
  よれば本法は後に（アクセス不能な）Federal Decree-Law No. 32 of
  2021により改正・置換された可能性があるが、その新法本文は直接
  確認できなかったため「独立に確認できた最終版」として本法を採用し、
  この限界を正直に記録。
- **Federal Decree-Law No. 45 of 2021 on the Protection of Personal
  Data** — 正確なタイトル・番号はMereller（UAEの専門法律事務所）
  発行の英日対訳法令翻訳メモで確認（非政府ソースのため`:official-`
  ではなく`:mereller-legal-translation-mirror`タグを使用）。制定・
  署名日について複数の独立引用ソースが**4通りの異なる矛盾する日付**
  （2021年9月20日／9月26日／11月27日／11月28日）を主張していた
  ため、いずれかを恣意的に選ばず、全ソースが矛盾なく一致していた
  施行日（2022年1月2日）を採用し、この矛盾自体を記録した。

ZAF/COL/IND/KEN/THAと同様、既存の`marketentry.facts`実装がある
同一`deps.edn`の下に`statute.facts`を新規namespaceとして追加。4
tests/9 assertions green（既存marketentry 24 tests/81 assertions
と合わせ計28 tests/90 assertions green）。

**capital-check**: tick98で既に検証済みのアブダビ（Q1519、1971年
連邦建国以来UAE(Q878)の首都）・P36史的首都バグなしの結果を再利用。

129リポジトリ・792 factを統合。country="ARE"での横断query
（statute.facts / ordinance.facts / association-rule.facts の
3スキーマそれぞれ）が全て実在の正しく属性付けされた結果を返す
ことを確認——UAEが単一軸のスタブではなく真の3軸横断連合カバレッジ
を持つことを実証した。

現状: 国軸34件・業界団体軸34件(26業種)・自治体軸32件——100tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 101 — 自治体軸33件目(Hanoi, VNM) — エルサレムは地政学的配慮からあえて見送り)

`cloud-itonami-municipality-vnm-hanoi`を新規GitHubリポジトリとして
scaffold・push
([commit 6b1354d](https://github.com/cloud-itonami/cloud-itonami-municipality-vnm-hanoi/commit/6b1354d))：

- **Law on the Capital（Luật Thủ đô、Law No. 39/2024/QH15）** —
  タイトル・番号・可決日（2024年6月28日、国会）を
  english.luatvietnam.vn（ベトナム官報テキストの民間法律翻訳配信
  サービス——tick100のMerellerと同等の位置づけ、政府ドメイン自体
  ではないが真正な官報内容を引用）で直接確認。
- **Decision 33/2023/QĐ-UBND**（文化・スポーツ分野の行政手続き
  再編）— english.hanoi.gov.vn自身のホームページ掲載一覧で直接確認、
  2023年12月20日付。

**プロセスに関する注記**: 次の自治体軸多様化ターゲットとして最初に
イスラエル（エルサレム）を検討した——既存のorganization.ednには
Wikidataに基づきエルサレム（Q1218）が首都として既に記載されていた
が、エルサレムの首都としての地位は国際的に係争中（多くの国が
国連決議に基づきエルサレムを首都と認めずテルアビブに大使館を
維持）であるため、ソース選定を通じて本プロジェクトが地政学的立場を
取っていると見なされることを避けるため、本tickではあえて見送り、
係争性の低いベトナム・ハノイに切り替えた——基盤となるWikidata首都
フィールド方式論自体は他の34カ国すべてと同一・中立であり、恒久的な
除外ではなく本tick限りの選択。

4 tests/11 assertions green。130リポジトリ・794 factを統合。
`"ordinance/kind" "local-act"`での横断queryで`[london new-delhi
warsaw bangkok abu-dhabi hanoi]`——創設憲章型の6自治体法がすべて
正しく取得できることを確認。

**capital-check**: ハノイ（Q1858）は1976年4月25日の国会決議
（南北統一後）以来現在の首都。ベトナムの首都は歴史的にハノイ
（1010年〜）とフエ（阮朝、19世紀初頭）の間を移動した経緯がある
ため史的首都バグの誤検出リスクがあったが、既存organization.ednの
ハノイ記載は1976年以降の**現行**首都と正しく一致しており、バグ
なしを確認（「歴史的に移動した」と「現在誤っている」を区別する
必要があった事例——JPN/DEU/BRAで実際にバグがあった事例とは異なる）。

現状: 国軸34件・業界団体軸34件(26業種)・自治体軸33件——101tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 102 — 業界団体軸35件目(VNBA, VNM, ISIC 6419) — 銀行業6カ国目、VITASはドメイン不通で断念)

`cloud-itonami-assoc-6419-vnm-vnba`を新規GitHubリポジトリとして
scaffold・push
([commit caeb59d](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-vnm-vnba/commit/caeb59d))：

Vietnam Banks Association（VNBA、ベトナム銀行協会）。ISIC 6419
（銀行業）の6カ国目（日本・ドイツ・フランス・オーストラリア・UAEに
次ぐ）。本tick窓のベトナムリサーチ
（[`cloud-itonami-municipality-vnm-hanoi`](https://github.com/cloud-itonami/cloud-itonami-municipality-vnm-hanoi)）
の流れを継続。

本tickは当初VITAS（ベトナム繊維・アパレル協会）を業種多様化の
候補として試みたが、公式ドメイン（vietnamtextile.org.vn）が
試した4つの異なるURL（トップページ・25周年記念記事・組織図
ページ・検索ページ）すべてで接続リセット（ECONNRESET）——ドメイン
全体不通として断念し、銀行業のVNBAに切り替えた。

vnba.org.vn自身のHistoryページから2件を直接確認:

- **1994年設立** — 「Vietnam Banks Association (VNBA) was
  established after the Prime Minister's approval on May 14, 1994」
  「On August 23, 1994, VNBA was officially launched after its 1st
  Congress」と明記。
- **1995年ASEAN Banking Association加盟** — 1995年9月29日、7番目の
  加盟団体として加入。

VNBA自身のWikidata Q-idは見つからず、推測せず正直に省略。

4 tests/11 assertions green。131リポジトリ・796 factを統合。
`"association-rule/isic" "6419"`での横断queryで6カ国すべて
（`[zenginkyo JPN]`・`[bankenverband DEU]`・`[fbf FRA]`・
`[aba AUS]`・`[ubf ARE]`・`[vnba VNM]`）が正しく取得できることを
確認。

現状: 国軸34件・業界団体軸35件(26業種)・自治体軸33件——102tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 103 — 国軸35件目(VNM) — ベトナムが3tick窓で3軸完成、新規:decree種別)

`cloud-itonami-iso3166-vnm`の`statute.facts`を追加
([commit 8013c64](https://github.com/cloud-itonami/cloud-itonami-iso3166-vnm/commit/8013c64))：

ベトナムの一般法2件、いずれもenglish.luatvietnam.vn（tick101の
ハノイ「Law on the Capital」でも使用した同一ソース）で直接確認:

- **Law on Enterprises**（Law No. 59/2020/QH14）— 第14期国会で2020年
  6月17日可決、大統領令No. 06/2020/L-CTNにより7月1日公布、2021年
  1月1日施行。
- **Decree No. 13/2023/ND-CP on Personal Data Protection** —
  2023年4月17日政府発布、7月1日施行。

**新規`:decree`種別を導入** — ベトナム法体系では国会可決の
「Luật（法律）」と行政府発布の「Nghị định（政令）」は明確に別種の
法令であり、後者を`:law`と誤分類すると立法上の位置づけを過大に
表現することになる（ベトナムには個人データ保護を専門に扱う国会
可決法はまだ存在せず、この政令のみ）ため`:decree`を新設し正確に
区別。

ZAF/COL/IND/KEN/THA/AREと同様、既存の`marketentry.facts`実装がある
同一`deps.edn`の下に`statute.facts`を新規namespaceとして追加。4
tests/9 assertions green（既存marketentry 24 tests/81 assertions
と合わせ計28 tests/90 assertions green）。

**3軸完成の節目**: 本tickにより**ベトナムが国・自治体・業界団体の
3軸すべてを、わずか3tickの窓**（自治体: tick101・団体: tick102・
国: 本tick）**で完成**——tick100のUAE（約4tick）に次ぐ2カ国目の
3軸完全達成、かつUAEより1tick早い。

**capital-check**: tick101で既に検証済みのハノイ（Q1858、1976年
統一後の国会決議以来）・P36史的首都バグなしの結果を再利用。

132リポジトリ・798 factを統合。`"statute/kind" "decree"`での
横断queryでVNMが新規statute種別の唯一（初）のエントリとして正しく
取得できることを確認。country="VNM"での3スキーマ横断query
（statute.facts / ordinance.facts / association-rule.facts）も
すべて実在の正しい結果を返し、真の3軸連合カバレッジを確認。

現状: 国軸35件・業界団体軸35件(26業種)・自治体軸33件——103tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 104 — 自治体軸34件目(Jakarta, IDN) — ヌサンタラ遷都という現在進行中の係争を事前確認)

`cloud-itonami-municipality-idn-jakarta`を新規GitHubリポジトリと
してscaffold・push
([commit b971c21](https://github.com/cloud-itonami/cloud-itonami-municipality-idn-jakarta/commit/b971c21))：

jdih.jakarta.go.id（ジャカルタ特別州自身の法令情報ネットワーク）
から2件を直接確認:

- **Governor Regulation Number 101 of 2017**（外国公館への地方税
  免除）— サイト自身の英訳PDF表紙で直接タイトル・番号確認。正確な
  日にちは確認したページでは見つからず、年のみ（2017年）で記録。
- **Regional Regulation Number 4 of 2019**（2013年廃棄物管理条例
  第3号の改正）— サイト自身のHTML規則詳細ページで確立日
  （2019年9月23日）・公布日（2019年9月26日）まで正確に直接確認。

**首都チェックの新パターン**: 着手前に、インドネシアの憲法裁判所が
現時点でジャカルタが依然として正式な首都であり、ヌサンタラ
（東カリマンタンの新首都計画）ではないと確認済みであることを
WebSearchで明示的に検証した。JPN/DEU/BRAの確定済み史的首都バグ
事例や、THA/VNMの「歴史的に移動したが現在は一貫している」事例とは
異なり、ヌサンタラ遷都は**現在進行中・係争中・未完了のプロセス**
（2025年の方針再分類で2028年までに「政治的首都」としてのみ目標化、
2024〜2026年で新首都向け国家予算が85%削減）——Wikidataの史的
一貫性だけでなく現在の報道・法的地位も確認する必要があった、
首都チェック規律の新たなバリエーション。

**回復手法の知見**: 2件目ではPDF表紙を目視確認する代わりに、
ポータル自身のHTML詳細ページ（jdih.jakarta.go.id/dokumen/
detail/3586）を取得したところ確立日・公布日まで正確に得られた
——1件目のPDF経由アプローチでは年精度しか得られなかったのと対照的。
今後インドネシアのJDIH系法令ポータルではまずHTML詳細ページ経路を
試す価値がある。

4 tests/11 assertions green。133リポジトリ・800 factを統合。
`"ordinance/topic" "taxation"`での横断queryで`[nairobi jakarta]`が
正しく取得できることを確認。

現状: 国軸35件・業界団体軸35件(26業種)・自治体軸34件——104tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 105 — 業界団体軸36件目(GAPKI, IDN, ISIC 0126) — 新規業種、インドネシアリサーチの継続)

`cloud-itonami-assoc-0126-idn-gapki`を新規GitHubリポジトリとして
scaffold・push
([commit 961033f](https://github.com/cloud-itonami/cloud-itonami-assoc-0126-idn-gapki/commit/961033f))：

Indonesian Palm Oil Association（GAPKI、インドネシアパーム油
生産者協会）。**ISIC 0126（油糧果実栽培）の初エントリ**——AFBF
（ISIC 0150、混合農業）とは別の農業系業種コード。本tick窓の
インドネシアリサーチ
（[`cloud-itonami-municipality-idn-jakarta`](https://github.com/cloud-itonami/cloud-itonami-municipality-idn-jakarta)）
の流れを継続。

gapki.id自身の2ページを直接WebFetch検証:

- **Historyページ** — 「The Indonesian Palm Oil Association (GAPKI)
  was established on 27 February 1981」と明記。
- **ニュース記事** — 「45 Tahun GAPKI untuk Negeri」（国のための
  GAPKI45年史）と題する1981年から現在までのインドネシアパーム油
  産業を記録した歴史書が、GAPKI創立45周年記念式典で
  「on Tuesday (29/04/2026)」披露されたと明記。

GAPKI自身のWikidata Q-idは見つからず、推測せず正直に省略。

4 tests/11 assertions green。134リポジトリ・802 factを統合。
`"association-rule/isic" "0126"`での横断queryで`[gapki IDN]`が
この新規ISICコードの唯一（初）のエントリとして正しく取得できる
ことを確認。

現状: 国軸35件・業界団体軸36件(27業種)・自治体軸34件——105tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 106 — 国軸36件目(IDN) — インドネシアが3tick窓で3軸完成、3カ国目の3軸達成)

`cloud-itonami-iso3166-idn`の`statute.facts`を追加
([commit 8e99d3b](https://github.com/cloud-itonami/cloud-itonami-iso3166-idn/commit/8e99d3b))：

インドネシアの一般法2件、複数ドメインの断念を経て
jdih.kemenkeu.go.id（財務省自身の法令情報ポータル）で直接確認:

- **peraturan.go.id**（国家法令ポータル本家）— ECONNREFUSED
- **peraturan.bpk.go.id**（会計検査院ミラー）— HTTP 403
- **OJK**（金融サービス庁）PDFミラー — フォント崩壊で完全に判読不能

最終的にjdih.kemenkeu.go.id（財務省、別の政府ドメイン）で:

- **UU No. 40 Tahun 2007（会社法/Perseroan Terbatas）** — 2007年
  8月16日制定。
- **UU No. 27 Tahun 2022（個人データ保護法/Pelindungan Data
  Pribadi）** — 2022年10月17日制定・公布。

ZAF/COL/IND/KEN/THA/ARE/VNMと同様、既存の`marketentry.facts`実装が
ある同一`deps.edn`の下に`statute.facts`を新規namespaceとして追加。
4 tests/9 assertions green（既存marketentry 24 tests/81 assertions
と合わせ計28 tests/90 assertions green）。

**3軸完成の節目**: 本tickにより**インドネシアが国・自治体・業界
団体の3軸すべてを3tickの窓**（自治体: tick104・団体: tick105・
国: 本tick）**で完成**——tick100のUAE（約4tick）・tick103の
ベトナム（3tick）に次ぐ**3カ国目**の3軸完全達成。

**capital-check**: tick104で既に検証済みの「ジャカルタは憲法裁判所
判断により依然インドネシアの首都（ヌサンタラ遷都は未完了）」という
結果を再利用。

135リポジトリ・804 factを統合。jurisdiction IDN queryで今回の2件が
既存legal-sourceと正しく共存していることを確認。country="IDN"での
3スキーマ横断query（statute.facts / ordinance.facts /
association-rule.facts）もすべて実在の正しい結果を返し、真の3軸
連合カバレッジを確認。

現状: 国軸36件・業界団体軸36件(27業種)・自治体軸34件——106tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 107 — 自治体軸35件目(Manila, PHL) — GitHubリポジトリ作成レート制限を尊重し次tickで再試行成功)

`cloud-itonami-municipality-phl-manila`を新規GitHubリポジトリと
してscaffold・push
([commit 0377201](https://github.com/cloud-itonami/cloud-itonami-municipality-phl-manila/commit/0377201))：

- **Republic Act No. 409**（マニラ市改正憲章）— タイトル・番号は
  lawphil.net（フィリピンの確立された法律データベース）で確認。
  1949年6月18日の承認日はOfficial Gazette自身のpermalink URL構造
  （officialgazette.gov.ph/1949/06/18/republic-act-no-409/——
  ページ自体はHTTP 403だが、Gazetteは実際の公布日に基づいてURLを
  構造化しており恣意的ではない）で裏付け。
- **City Ordinance No. 9107**（4Ps現金給付プログラムのカード不正
  利用禁止）— タイトル・番号・正確な日付（2025年4月11日）を
  citycouncilofmanila.com.ph自身のHTML一覧ページ（第12期市議会）で
  直接確認。

**外部レート制限の尊重**: 本tick中に`gh repo create`が2回
「You have created too many repositories, too quickly」でブロック
された——本セッションで短期間に30以上の新規リポジトリを作成した
ことによる、GitHub側の正当なセカンダリレート制限（本プロジェクトの
不具合ではない）。強制的な回避策やリトライの連打はせず、ローカル
commitを安全にディスク上に保持したまま、worktreeのADR・query
スクリプトは**未着手のまま**とし（実際にはpushされていないリポジトリ
をあたかも稼働中であるかのように反映して状態を偽ることを避けるため）、
「部分完了」として正直に報告した。次の定期起動（30分後）を自然な
再試行タイミングとして利用したところ、`gh repo create`は1回目の
試行であっさり成功——レート制限が時間経過で解消される性質のもので
あったことを確認した。

4 tests/11 assertions green。136リポジトリ・806 factを統合。
`"ordinance/kind" "local-act"`での横断queryで`[london new-delhi
warsaw bangkok abu-dhabi hanoi manila]`——創設憲章型の7自治体法が
すべて正しく取得できることを確認。

**capital-check**: マニラ（Q1461）は1948〜1976年にケソン市が一時
首都を務めた後、1976年の大統領令で首都地位を回復——既存の
organization.ednの記載はこの現行・1976年以降の状態と正しく一致
（本セッションのベトナム ハノイ/フエ、インドネシア ジャカルタ/
ヌサンタラの首都史チェックと同種の事例）。

現状: 国軸36件・業界団体軸36件(27業種)・自治体軸35件——107tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 108 — 業界団体軸37件目(BAP, PHL, ISIC 6419) — 銀行業7カ国目、WebSearch設立年矛盾を一次資料で解決)

`cloud-itonami-assoc-6419-phl-bap`を新規GitHubリポジトリとして
scaffold・push
([commit f06b69f](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-phl-bap/commit/f06b69f)、
今回はリポジトリ作成レート制限に引っかからず一発成功)：

Bankers Association of the Philippines（BAP、フィリピン銀行協会）。
ISIC 6419（銀行業）の7カ国目（日本・ドイツ・フランス・オーストラリア・
UAE・ベトナムに次ぐ）。本tick窓のフィリピンリサーチ
（[`cloud-itonami-municipality-phl-manila`](https://github.com/cloud-itonami/cloud-itonami-municipality-phl-manila)）
の流れを継続。

bap.org.ph自身のaboutus.htmlページから2件を直接確認:

- **1949年設立**（3月29日）— 「Established on March 29, 1949, the
  BAP was created to frame rules and regulations in cooperation with
  the Central Bank...」と明記。
- **1964年SEC法人化**（8月24日）— 「The BAP was officially
  incorporated as a duly Securities and Exchange Commission
  (SEC)-registered corporate entity on August 24, 1964」と明記。

**WebSearch設立年矛盾を解決**: 事前のWebSearch要約は複数の二次
ソース間で設立年が1947年と1949年で矛盾していたが、bap.org.ph自身の
一次資料ページを直接読むことで1949年に確定——本セッションで確立
された「複数ソース矛盾時は一次資料を優先する」規律の実践例
（tick92のケニアFinance Act 2013却下、tick94のインドDPDP Act日付
訂正と同型）。BAP自身のWikidata Q-idは見つからず、推測せず正直に
省略。

4 tests/11 assertions green。137リポジトリ・808 factを統合。
`"association-rule/isic" "6419"`での横断queryで7カ国すべて
（`[zenginkyo JPN]`・`[bankenverband DEU]`・`[fbf FRA]`・
`[aba AUS]`・`[ubf ARE]`・`[vnba VNM]`・`[bap PHL]`）が正しく
取得できることを確認。

現状: 国軸36件・業界団体軸37件(27業種)・自治体軸35件——108tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 109 — 国軸37件目(PHL) — フィリピンが3tick窓で3軸完成、4カ国目)

`cloud-itonami-iso3166-phl`の`statute.facts`を追加
([commit 5929d5c](https://github.com/cloud-itonami/cloud-itonami-iso3166-phl/commit/5929d5c))：

フィリピンの一般法2件、いずれもlawphil.net（tick107のマニラRA
409でも使用した確立された法律データベース）で確認:

- **Republic Act No. 11232**（フィリピン改正会社法）— タイトル・
  番号はページ自身で確認。privacy.gov.phが403で直接確認できな
  かったため、2019年2月20日の署名日は複数の独立ソース（ADB・Cruz
  Marcelo・AsiaLaw・IFLR・Official GazetteのURL埋め込み日付パター
  ン）が矛盾なく一致することで裏付け。
- **Republic Act No. 10173**（2012年データプライバシー法）—
  タイトル・番号・2012年8月15日の承認日すべてlawphil.netページ
  自身で直接確認。

**ID規約の自己訂正**: 当初statute IDを`phl-ra-11232-...`とハイフン
区切りで書いたが、tick途中で全ての姉妹リポジトリが
`idn.uu-40-2007-...`のようにドット区切りを使っていることに気づき、
commit前に`src/statute/facts.cljc`・`data/datascript-tx.edn`双方を
修正——国軸全体でID規約の一貫性を維持。

ZAF/COL/IND/KEN/THA/ARE/VNM/IDNと同様、既存の`marketentry.facts`
実装がある同一`deps.edn`の下に`statute.facts`を新規namespaceとして
追加。4 tests/9 assertions green（既存marketentry 24 tests/81
assertionsと合わせ計28 tests/90 assertions green）。

**3軸完成の節目**: 本tickによりフィリピンが国・自治体・業界団体の
3軸すべてを**3tickの窓**（自治体: tick107・団体: tick108・
国: 本tick）**で完成**——UAE（tick100）・ベトナム（tick103）・
インドネシア（tick106）に次ぐ**4カ国目**の3軸完全達成。

**capital-check**: tick107で既に検証済みの「マニラは1948-1976年の
ケソン市一時首都期間を経て1976年大統領令で首都地位回復」という
結果を再利用。

138リポジトリ・810 factを統合。jurisdiction PHL queryで今回の2件が
既存legal-sourceと正しく共存していることを確認。country="PHL"での
3スキーマ横断query（statute.facts / ordinance.facts /
association-rule.facts）もすべて実在の正しい結果を返し、真の3軸
連合カバレッジを確認。

現状: 国軸37件・業界団体軸37件(27業種)・自治体軸35件——109tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 110 — 自治体軸36件目(Cairo, EGY) — 北アフリカ初、未解決の首都移転を推測せず正直に記録)

`cloud-itonami-municipality-egy-cairo`を新規GitHubリポジトリとして
scaffold・push
([commit a89908d](https://github.com/cloud-itonami/cloud-itonami-municipality-egy-cairo/commit/a89908d))：

自治体軸で北アフリカ初のエントリ。

- **Law No. 43 of 1979**（地方行政制度法）— lawyeregypt.net
  （実際に取得・閲覧したエジプト法律情報サイト）でタイトル・番号・
  1979年6月20日の発布日を直接確認。ILO NATLEXへの試行は先にHTTP
  403だったため、そちらを未読のまま引用URLとして使うことは意図的に
  避けた。
- **Building Violations Reconciliation Law（Law No. 187 of
  2023）** — blogs.realestate.gov.eg（エジプト政府公式ドメイン、
  住宅省不動産プラットフォーム）で法律番号を直接確認。正確な
  日にちは正常にレンダリングされたページで独立確認できなかった
  ため年のみで記録。

**首都チェックの特殊事例**: エジプトの「新首都」（旧称New
Administrative Capital）は、tick104のインドネシア・ヌサンタラ
事例よりさらに進行している——2024年4月に政府所在地として開所式が
行われ、内閣・下院・中央銀行が既に移転済み（ヌサンタラは本セッション
時点で副大統領府の移転のみ）。2026年2月には「特別州」への格上げと
「メンフィス」への改称を提案する法案が係属中。カイロと新首都の
どちらが「正しい」かを推測せず、Wikidata自身の現行P36（首都）
プロパティが依然としてカイロを記載していることを明示的に確認・
追従し、この未解決の曖昧さをdocstring・organization.edn・READMEに
正直に記録した。

**未読ソースの規律**: ILO NATLEXがHTTP 403を返した後、（後に
lawyeregypt.netで直接確認できた日付とWebSearch要約の日付が実は
一致していたにもかかわらず）未読のソースを引用URLとして使うことを
意図的に避けた——本セッションの他の箇所で使ってきた「正常に
レンダリングされた代替が無い場合のWebSearch裏付けパターン」よりも
厳格な基準。

4 tests/11 assertions green。139リポジトリ・812 factを統合。
`"ordinance/kind" "local-act"`での横断queryで`[london new-delhi
warsaw bangkok abu-dhabi hanoi manila cairo]`——創設憲章型の8自治体
法がすべて正しく取得できることを確認。

現状: 国軸37件・業界団体軸37件(27業種)・自治体軸36件——110tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-16, /loop tick 111 — 国軸38件目(EGY) — エジプトは現時点で3軸中2軸のみ、正直に報告)

`cloud-itonami-iso3166-egy`の`statute.facts`を追加
([commit d65d9a7](https://github.com/cloud-itonami/cloud-itonami-iso3166-egy/commit/d65d9a7))：

エジプトの一般法2件:

- **Law No. 159 of 1981**（会社法）— lawyeregypt.netでタイトル・
  番号・日付を直接確認（1981年9月17日署名、10月1日官報第40号
  公布）。GAFI（投資庁）のPDFミラーはフォント崩壊で完全に判読
  不能だったため断念。
- **Law No. 151 of 2020**（個人データ保護法）— mcit.gov.eg
  （通信情報技術省）自身がホストする官報PDF本文を目視確認して
  直接確認。表紙は「第28号附録(h)、第63年、2020年7月15日」と判読
  可能に明記、本文は「共和国大統領府にて1441年ズー・アル＝カアダ
  22日（西暦2020年7月13日相当）発布」と記載——大統領の署名欄が
  偶然目に入ったが、恒常方針に従い一切保存していない。

ZAF/COL/IND/KEN/THA/ARE/VNM/IDN/PHLと同様、既存の`marketentry.facts`
実装がある同一`deps.edn`の下に`statute.facts`を新規namespaceとして
追加。4 tests/9 assertions green（既存marketentry 24 tests/81
assertionsと合わせ計28 tests/90 assertions green）。

**軸カバレッジの正直な報告**: 直近4カ国（UAE・ベトナム・
インドネシア・フィリピン）はいずれも短期間で3軸すべてを達成した
が、エジプトは現時点で3軸中2軸（国：本エントリ／自治体：tick110の
`cloud-itonami-municipality-egy-cairo`）のみで、業界団体軸の
エントリはまだ無い——虚偽の「3軸達成」を主張せず正直に記録する。

**capital-check**: tick110で既に検証・記録済みのエジプト新首都
移転の未解決の曖昧さ（Wikidata自身のP36は本tick時点でも依然
カイロを記載）を再利用し、恣意的に解決したり再検証し直したりは
しなかった。

140リポジトリ・814 factを統合。jurisdiction EGY queryで今回の2件が
既存legal-sourceと正しく共存していることを確認。country="EGY"での
ordinance.facts横断queryで既存のカイロ自治体2件と本tickの国エントリ
2件が正しく相互リンクしていることを確認。

現状: 国軸38件・業界団体軸37件(27業種)・自治体軸36件——111tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-17, /loop tick 112 — 業界団体軸38件目(AEB, ESP, ISIC 6419) — 銀行業8カ国目、2つの断念を経て着地)

`cloud-itonami-assoc-6419-esp-aeb`を新規GitHubリポジトリとして
scaffold・push
([commit c1f7d94](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-esp-aeb/commit/c1f7d94))：

Asociación Española de Banca（AEB、スペイン銀行協会）。ISIC 6419
（銀行業）の8カ国目。

本tickは2つの断念を経て着地:

- **Royal FloraHolland**（オランダ、花卉オークション協同組合——
  業種多様化のための本命候補）— royalfloraholland.comが履歴
  ページを含む全URLでHTTP 403、断念。
- **Federation of Egyptian Industries**（エジプトの業界団体軸の
  欠落を埋める狙いで、tick111で正直に指摘した空白を埋めるべく
  次点で試行）— WebSearch要約が設立年で1922年と1947年（設立法）
  という矛盾した2つの年を示し、fei.org.eg自身の"aboutus"ページも
  "about-fei"ページ（404）もどちらの日付も直接確認できなかった
  ため、どちらかを恣意的に選ばず断念。エジプトの業界団体軸の空白は
  今後のtickに持ち越し。

aebanca.es自身の「Our history」ページから2件を直接確認:

- **1977年設立**
- **1985年、スペインのEU加盟とAEBの欧州銀行連盟（European
  Banking Federation）への正式加盟**

いずれも年のみで記録。4 tests/11 assertions green。141リポジトリ・
816 factを統合。`"association-rule/isic" "6419"`での横断queryで
8カ国すべて（`[zenginkyo JPN]`・`[bankenverband DEU]`・
`[fbf FRA]`・`[aba AUS]`・`[ubf ARE]`・`[vnba VNM]`・
`[bap PHL]`・`[aeb ESP]`）が正しく取得できることを確認。

現状: 国軸38件・業界団体軸38件(27業種)・自治体軸36件——112tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-17, /loop tick 113 — 自治体軸37件目(Ankara, TUR) — トルコ初、公式ポータルHTTP 403を国レベル建制法2件で補完)

`cloud-itonami-municipality-tur-ankara`を新規GitHubリポジトリとして
scaffold・push
([commit f3c90d1](https://github.com/cloud-itonami/cloud-itonami-municipality-tur-ankara/commit/f3c90d1))：

トルコの首都アンカラ。1923年10月29日以来（イスタンブールから遷都）
安定した首都で、エジプト/インドネシアで見られたような未解決の
遷都問題は無し（Wikidata P36も正しくアンカラを示す）。

`ankara.bel.tr`（市自身の公式ポータル）は試した全URL
（`/meclis/kararlar`、市議会決定に関するニュースページ）で
HTTP 403、断念。代わりに、アンカラの大都市自治体（büyükşehir
belediyesi）地位を設立・現行統治するトルコの国レベル建制法2件を、
TBMM（トルコ大国民議会）アーカイブPDFの本文1ページ目を
Read-toolの保存パスfallbackで直接確認:

- **Kanun No. 3030**（1984年6月27日採択、1984年7月9日官報Sayı
  18453号公布）— イスタンブール・イズミルと共にアンカラに
  büyükşehir地位を初めて付与した原法。
- **Kanun No. 5216**（2004年7月10日採択、2004年7月23日官報Sayı
  25531号公布）— 3030を更新・現在も施行中のBüyükşehir Belediyesi
  Kanunu（大都市自治体法）。mevzuat.gov.tr自身のインタラクティブ
  ページは法番号・官報日付・号数は確認できたが正式タイトルは
  表示されなかったため、TBMMアーカイブPDFを正式タイトルの一次
  情報源として採用。

WebFetchは両PDFとも「illegible/binary」と報告（今session頻出の
フォントサブセット化パターン）、いずれもRead-toolの保存パス
fallback（`pages: "1"`）で画像レンダリングし直接読んで確認。

4 tests/11 assertions green。142リポジトリ・818 factを統合。
`municipality ankara`クエリで2件とも正しく取得、タイトル/番号の
横断query（`[?e "ordinance/municipality" "ankara"]`）でも一致確認。

現状: 国軸38件・業界団体軸38件(27業種)・自治体軸37件——自治体軸が
最薄という判断からトルコを新規開拓、3軸すべてが実データ・個別
検証済み・捏造なしで成長を継続している。

## Addendum (2026-07-17, /loop tick 114 — 国軸39件目(TUR) — トルコが国軸+自治体軸の2軸に到達、業界団体軸は未着手のまま正直に報告)

`cloud-itonami-iso3166-tur`に`statute.facts`を新規namespaceとして
追加・push
([commit 4781386](https://github.com/cloud-itonami/cloud-itonami-iso3166-tur/commit/4781386))：

tick113で検証済みの首都チェック（アンカラは1923年以来安定した
首都、未解決の遷都問題無し）を再利用。

- **Türk Ticaret Kanunu**（トルコ商法典、Kanun No. 6102）— 2011年
  1月13日採択。mevzuat.gov.tr自身のPDFミラーは法番号・日付欄が
  フォントサブセット化で判読不能な空白ボックスとして描画された
  ため、adalet.gov.tr（法務省、公式政府ドメイン）の別ミラーPDFを
  Read-toolの保存パスfallbackで直接読み、legibleに確認。
- **Kişisel Verilerin Korunması Kanunu**（個人データ保護法、KVKK、
  Kanun No. 6698）— 2016年3月24日採択、2016年4月7日官報Sayı
  29677号公布。adalet.gov.tr PDFはタイトルのみlegibleで法番号・
  日付欄はやはり判読不能だったため、mevzuat.gov.tr自身の`.doc`
  ミラーをWebFetchで直接取得（PDFのフォントサブセット化問題を
  形式変更で回避、legibleに全項目確認）。

4 tests/9 assertions green（既存24 marketentry tests/81
assertionsと合わせ全28 tests/90 assertions green）。142リポジトリ・
820 factを統合。`jurisdiction TUR`クエリと、`ordinance/country`
横断query（`[?e "ordinance/country" "TUR"]`）でtick113のAnkara
自治体2件が正しくクロスリンクされることを確認——トルコは今回で
国軸+自治体軸の2軸に到達したが、業界団体軸はまだ未着手であり、
これを正直に報告する（前回のエジプトと同型の「2軸のみ」パターン）。

現状: 国軸39件・業界団体軸38件(27業種)・自治体軸37件——114 tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。トルコの業界団体軸の空白は今後のtickに持ち越し。

## Addendum (2026-07-17, /loop tick 115 — 業界団体軸39件目(TBB, TUR, ISIC 6419) — トルコが3tick窓で3軸完成、5カ国目)

`cloud-itonami-assoc-6419-tur-tbb`を新規GitHubリポジトリとして
scaffold・push
([commit ce27e15](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-tur-tbb/commit/ce27e15))：

Türkiye Bankalar Birliği（TBB、トルコ銀行協会）。ISIC 6419
（銀行業）の9カ国目。tick114で正直に指摘したトルコの業界団体軸の
空白を埋める。

tbb.org.tr自身の複数ページから2件を直接確認:

- **1958年10月8日設立**（"Vision Mission Values"ページの
  "Kurulduğu 1958 yılından bu yana"という記述、および
  "Kilometre Taşları"（Milestones）ページの1958年見出し下
  "8 Ekim'de Kuruldu"という記述の両方で確認、後者のより精密な
  日付を採用）
- **2007年、個人顧客仲裁委員会（Bireysel Müşteriler Hakem
  Heyeti）制度の導入**（同Milestonesページの2007年見出し下で確認）

4 tests/12 assertions green。142リポジトリ・822 factを統合。

3軸横断query（`statute/jurisdiction`・`ordinance/country`・
`association-rule/country`いずれも`"TUR"`）で全て正しく取得を
確認——**トルコがtick113(自治体)→114(国)→115(業界団体)の
3tick窓で3軸完成、UAE(tick100)・ベトナム(tick103)・
インドネシア(tick106)・フィリピン(tick109)に続く5カ国目**。

現状: 国軸39件・業界団体軸39件(27業種)・自治体軸37件——115 tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-17, /loop tick 116 — 自治体軸38件目(Abuja, NGA) — アフリカ4件目、bill段階の候補法を断念し正式な官庁ドメインで補完)

`cloud-itonami-municipality-nga-abuja`を新規GitHubリポジトリとして
scaffold・push
([commit 4ed2f48](https://github.com/cloud-itonami/cloud-itonami-municipality-nga-abuja/commit/4ed2f48))：

ナイジェリアの首都アブジャ。1991年12月12日にラゴスから遷都、既に
完全に完了した歴史的移転で、エジプト/インドネシアのような未解決の
遷都問題は無し。アフリカ大陸ではナイロビ（tick92）・ケープタウン・
カイロ（tick110）に続く4件目。

`amacfct.org.ng`（Abuja Municipal Area Council自身のポータル）は
DNS失敗（ENOTFOUND）。候補として調べた「FCT Area Councils Service
Commissionを設立する2019年法（HB.975）」は、報道を確認したところ
まだ第二読会段階（bill段階）で正式な成立が確認できなかったため、
「Act」として引用することを断念。

代わりに2件を直接確認:

- **Federal Capital Territory Act**（1976年 Decree No. 6、1976年
  2月4日公布）— lawsofnigeria.placng.org自身がホストするPDF本文を
  Read-toolの保存パスfallbackで直接読み確認（WebFetchは
  illegible/binaryと報告）。アブジャを連邦首都特別区の所在地に
  指定した建制法。
- **Abuja Environmental Protection Board Act**（Act No. 10 of
  1997）— aepb.abj.gov.ng自身は account suspension で到達不能
  だったため、fcta.gov.ng（連邦首都特別区庁の公式政府ドメイン、
  生きて到達可能）で法番号を直接確認。日付は年のみ（正確な月日を
  legibleなページで確認できなかったため推測せず）。

4 tests/11 assertions green。143リポジトリ・824 factを統合。
`municipality abuja`クエリで2件とも正しく取得、タイトル/番号の
横断query（`[?e "ordinance/municipality" "abuja"]`）でも一致確認。

現状: 国軸39件・業界団体軸39件(27業種)・自治体軸38件——116 tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-17, /loop tick 117 — 国軸40件目(NGA) — ナイジェリアが国軸+自治体軸の2軸に到達、業界団体軸は未着手のまま正直に報告)

`cloud-itonami-iso3166-nga`に`statute.facts`を新規namespaceとして
追加・push
([commit e4766f3](https://github.com/cloud-itonami/cloud-itonami-iso3166-nga/commit/e4766f3))：

tick116で検証済みの首都チェック（アブジャは1991年12月12日以来
安定した首都、未解決の遷都問題無し）を再利用。

- **Companies and Allied Matters Act, 2020**（CAMA 2020）— 2020年
  8月7日成立。一次資料PDF2件が判読不能: lawsofnigeria.placng.orgの
  「C20.pdf」は調べたところ古いCap. C20法典整理版（2020年固有の
  日付表記なし）と判明、icrp.cac.gov.ng（法人業務委員会自身の
  公式ドメイン）のPDFはナイジェリア国章は視認できたものの
  日付/参照行がフォントサブセット化で判読不能だったため、
  Wikipediaの該当ページを直接WebFetchで読み確認（Mondaq・ICNLも
  同日付で一致）。
- **Nigeria Data Protection Act, 2023**（NDPA 2023）— 2023年6月12日
  署名成立。ndpc.gov.ng（ナイジェリアデータ保護委員会、規制当局
  自身の公式政府ドメイン）の"About Us"ページで直接確認
  （cert.gov.ng自身のPDFはHTTP 403で断念）。

4 tests/9 assertions green（既存24 marketentry tests/81
assertionsと合わせ全28 tests/90 assertions green）。143リポジトリ・
826 factを統合。`jurisdiction NGA`クエリと、`ordinance/country`
横断query（`[?e "ordinance/country" "NGA"]`）でtick116のAbuja
自治体2件が正しくクロスリンクされることを確認——ナイジェリアは
今回で国軸+自治体軸の2軸に到達したが、業界団体軸はまだ未着手で
あり、これを正直に報告する（前回のエジプト・トルコ(tick114時点)
と同型の「2軸のみ」パターン）。

現状: 国軸40件・業界団体軸39件(27業種)・自治体軸38件——117 tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。ナイジェリアの業界団体軸の空白は今後のtickに持ち越し。

## Addendum (2026-07-17, /loop tick 118 — 業界団体軸40件目(CIBN, NGA, ISIC 6419) — ナイジェリアが3tick窓で3軸完成、6カ国目)

`cloud-itonami-assoc-6419-nga-cibn`を新規GitHubリポジトリとして
scaffold・push
([commit 4642710](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-nga-cibn/commit/4642710))：

Chartered Institute of Bankers of Nigeria（CIBN）。ISIC 6419
（銀行業）の10カ国目。tick117で正直に指摘したナイジェリアの
業界団体軸の空白を埋める。

cibng.org自身の"Corporate Information"ページから2件を直接確認:

- **1963年11月28日設立**（Institute of Bankers, London のナイジェリア
  地方支部として発足）
- **1990年5月18日、Chartered Status取得**（Federal Government Act
  No. 12 of 1990による。原文引用: "the attainment of a Chartered
  Status, achieved on May 18th, 1990 by the Federal Government Act
  No. 12 of 1990" — 現在はCIBN Act No. 5 of 2007として再制定済み）

4 tests/11 assertions green。143リポジトリ・828 factを統合。

3軸横断query（`statute/jurisdiction`・`ordinance/country`・
`association-rule/country`いずれも`"NGA"`）で全て正しく取得を
確認——**ナイジェリアがtick116(自治体)→117(国)→118(業界団体)の
3tick窓で3軸完成、UAE(tick100)・ベトナム(tick103)・
インドネシア(tick106)・フィリピン(tick109)・トルコ(tick115)に
続く6カ国目**。

現状: 国軸40件・業界団体軸40件(27業種)・自治体軸38件——118 tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-17, /loop tick 119 — 自治体軸39件目(Riyadh, SAU) — 湾岸2件目、アラビア語フォントサブセット化を英語法律データベースで補完)

`cloud-itonami-municipality-sau-riyadh`を新規GitHubリポジトリとして
scaffold・push
([commit 9c4f903](https://github.com/cloud-itonami/cloud-itonami-municipality-sau-riyadh/commit/9c4f903))：

サウジアラビアの首都リヤド。安定した首都で未解決の遷都問題無し。
湾岸地域ではアブダビ（tick98）に続く2件目、サウジアラビアは
アラブ最大の経済であり3軸いずれも初めての参入。

`alriyadh.gov.sa`（市自身のポータル）はナビゲーションハブのみで
具体的な法番号・設立日の記載無し。`balady.gov.sa`（住宅・地方
自治省の公式ドメイン）自身がホストする全国法のPDFは、アラビア語
フォントサブセット化により本文が完全に判読不能な四角記号として
描画された（今session頻出のPDF判読不能パターンの新変種——今回は
ラテン文字・キリル文字・トルコ語ではなくアラビア語スクリプトに
影響）。

代わりに2件を直接確認:

- **Law of Municipalities and Villages**（Royal Decree No.
  M/5/1397、1977年2月10日/ヒジュラ暦1397年サファル21日）—
  Lexis Middle East法律データベース（今session既出のWIPO
  Lex/ECOLEXと同カテゴリの確立された法律データベース）で
  グレゴリオ暦・ヒジュラ暦両方を直接確認。
- **Cabinet Decision No. 717**（1974年6月20日、リヤド開発最高
  機構の設立、現Royal Commission for Riyadh City）— rcrc.gov.sa
  （同機構自身の公式ドメイン）の"Establishment and evolution"
  ページで原文を直接引用確認。

4 tests/11 assertions green。144リポジトリ・830 factを統合。
`municipality riyadh`クエリで2件とも正しく取得、タイトル/番号の
横断query（`[?e "ordinance/municipality" "riyadh"]`）でも一致確認。

現状: 国軸40件・業界団体軸40件(27業種)・自治体軸39件——119 tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-17, /loop tick 120 — 国軸41件目(SAU) — サウジアラビアが国軸+自治体軸の2軸に到達、法律データベース間の日付矛盾を一次資料で解決)

`cloud-itonami-iso3166-sau`に`statute.facts`を新規namespaceとして
追加・push
([commit b35b327](https://github.com/cloud-itonami/cloud-itonami-iso3166-sau/commit/b35b327))：

tick119で検証済みの首都チェック（リヤドは安定した首都、未解決の
遷都問題無し）を再利用。

- **Companies Law**（会社法、Royal Decree No. M/132）— 2022年6月
  30日（ヒジュラ暦1443年12月1日）発布。misa.gov.sa（投資省公式
  ドメイン）自身のPDFはアラビア語フォントサブセット化で判読不能
  （tick119のリヤド市法と同型の問題）。Lexis Middle Eastの
  ページは施行日（2023年1月19日）を発布日と混同していたため、
  それを鵜呑みにせず、gccbdi.org（湾岸協力理事会取締役機構）が
  ホストする英語版ミラーをRead-toolで直接読み、原文冒頭
  "Royal Decree No. [M/132] Dated 01/12/1443 AH" を確認して
  矛盾を解決。
- **Personal Data Protection Law**（個人データ保護法、Royal
  Decree No. M/19）— 2021年9月16日（ヒジュラ暦1443年2月9日）
  発布。SDAIA（サウジデータ・AI庁）自身の公式ドメインは全ての
  fetch試行がbot検出で"Request Rejected"となり断念。代わりに
  DLA Piperの"Data Protection Laws of the World"（今session
  既出のWIPO Lex/ECOLEX/Lexis Middle Eastと同カテゴリの専門法律
  リサーチ資料）で原文引用を直接確認、WebSearch要約にあった
  16日/17日の1日の食い違いも解消。

4 tests/9 assertions green（既存24 marketentry tests/81
assertionsと合わせ全28 tests/90 assertions green）。144リポジトリ・
832 factを統合。`jurisdiction SAU`クエリと、`ordinance/country`
横断query（`[?e "ordinance/country" "SAU"]`）でtick119のRiyadh
自治体2件が正しくクロスリンクされることを確認——サウジアラビアは
今回で国軸+自治体軸の2軸に到達したが、業界団体軸はまだ未着手で
あり、これを正直に報告する（前回のエジプト・トルコ・ナイジェリア
と同型の「2軸のみ」パターン）。

現状: 国軸41件・業界団体軸40件(27業種)・自治体軸39件——120 tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。サウジアラビアの業界団体軸の空白は今後のtickに持ち越し。

## Addendum (2026-07-17, /loop tick 121 — 業界団体軸41件目(FSC, SAU, ISIC 9411新規) — サウジアラビアが3tick窓で3軸完成、7カ国目)

`cloud-itonami-assoc-9411-sau-fsc`を新規GitHubリポジトリとして
scaffold・push
([commit 021fedb](https://github.com/cloud-itonami/cloud-itonami-assoc-9411-sau-fsc/commit/021fedb))：

Federation of Saudi Chambers（FSC、旧Council of Saudi Chambers、
2021年に改称）。tick120で正直に指摘したサウジアラビアの業界団体軸
の空白を埋める。**ISIC 9411**（企業・使用者・専門職団体活動）
という本catalog初のISICコードを新規導入——銀行業(6419)偏重から
商工会議所連合という異なる業種への多様化。

fsc.org.sa自身の"Establishment of FSC"ページから2件を直接確認:

- **1980年3月（ヒジュラ暦1400年4月30日）、Royal Decree # R/6に
  よる設立**（原文引用: "The Council was formed as per the Royal
  Decree # R/6 dated 30/04/1400 Hijri (March 1980) with its head
  office in Riyadh."）
- **1981年（ヒジュラ暦1401年）、事務局（General Secretariat）
  設立による実務開始**

FSC/CSC固有のWikidata Q-idは見つからず（tick108のBAPと同型の
パターンで、推測せず正直に省略）。

4 tests/11 assertions green。144リポジトリ・834 factを統合。

3軸横断query（`statute/jurisdiction`・`ordinance/country`・
`association-rule/country`いずれも`"SAU"`）で全て正しく取得を
確認——**サウジアラビアがtick119(自治体)→120(国)→121(業界団体)の
3tick窓で3軸完成、UAE(tick100)・ベトナム(tick103)・
インドネシア(tick106)・フィリピン(tick109)・トルコ(tick115)・
ナイジェリア(tick118)に続く7カ国目**。

現状: 国軸41件・業界団体軸41件(28業種)・自治体軸39件——121 tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-17, /loop tick 122 — 自治体軸40件目(Kuala Lumpur, MYS) — マレーシア初参入、安定した二都首都制を正直に記録)

`cloud-itonami-municipality-mys-kuala-lumpur`を新規GitHubリポジトリ
として scaffold・push
([commit b8f049e](https://github.com/cloud-itonami/cloud-itonami-municipality-mys-kuala-lumpur/commit/b8f049e))：

マレーシアの首都クアラルンプール——3軸いずれもマレーシア初参入、
ASEAN地域では5件目（バンコク・ハノイ・ジャカルタ・マニラに続く）。

マレーシアはエジプト/インドネシアのような未解決の遷都問題とは
異なり、**安定して定着した二都首都制**を採用: クアラルンプールが
連邦憲法第154条に基づく国家/王室の首都（国王・議会の所在地）で
あり続ける一方、プトラジャヤは1999年/2003年以来行政・司法の首都
として分離——20年以上安定しており、Wikidata P36も正しく
クアラルンプールを示す。

2件とも、マレーシア法律改訂委員（Commissioner of Law Revision,
Malaysia）が発行する公式"Laws of Malaysia"改訂版テキストを
simplymalaysia.wordpress.comでホストされたPDFから、Read-toolの
保存パスfallbackで直接読み確認:

- **Federal Capital Act 1960**（Act 190）— 1961年4月1日施行、
  クアラルンプールを連邦首都に指定した原法。
- **City of Kuala Lumpur Act 1971**（Act 59）— 1972年2月1日
  施行、市への昇格とDewan Bandaraya Kuala Lumpur（DBKL）への
  改称を規定。dbkl.gov.my自身の公式"Legislation List"ページで
  タイトル/法番号を先に確認した上で、一次資料で正確な日付を
  確認。

4 tests/11 assertions green。145リポジトリ・836 factを統合。
`municipality kuala-lumpur`クエリで2件とも正しく取得、
タイトル/番号の横断query（`[?e "ordinance/municipality"
"kuala-lumpur"]`）でも一致確認。

現状: 国軸41件・業界団体軸41件(28業種)・自治体軸40件——122 tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。

## Addendum (2026-07-17, /loop tick 123 — 国軸42件目(MYS) — マレーシアが国軸+自治体軸の2軸に到達、初のdeps.edn新規作成リポジトリ)

`cloud-itonami-iso3166-mys`に`statute.facts`を追加・push
([commit 7a26d1f](https://github.com/cloud-itonami/cloud-itonami-iso3166-mys/commit/7a26d1f))：

このリポジトリは他の姉妹リポジトリと異なり、既存の`marketentry.facts`
（`deps.edn`/`src`/`test`）が一切存在しない、blueprint+governance
文書のみのシェル状態だった——`statute.facts`をこのリポジトリの
**初の実装**として追加し、`deps.edn`も新規作成（langgraph依存は
不要なため最小構成）。

tick122で検証済みの首都チェック（マレーシアは安定した二都首都制）
を再利用。

- **Companies Act 2016**（Act 777）— Royal Assent 2016年8月31日、
  官報公布2016年9月15日。lom.agc.gov.my（マレーシア法務長官府の
  公式立法ポータル、"Laws of Malaysia"）自身のact-detailページで
  両日付を直接確認（本文施行は2017年1月31日、一部条項はさらに
  2018年3月/2019年3月に段階施行——:enacted-dateはRoyal Assent日を
  採用）。ssm.com.my（会社委員会公式ドメイン）自身のPDFは10MB
  制限超過、investmalaysia.gov.myのミラーはマレー語フォント
  サブセット化で判読不能だったため、lom.agc.gov.myのHTMLページを
  採用。
- **Personal Data Protection Act 2010**（Act 709）—
  investmalaysia.gov.myがホストする公式"Laws of Malaysia"改訂版
  PDFをRead-toolの保存パスfallbackで直接読み確認。Section 1(2)が
  施行日を大臣告示による後日決定に委ねており、改訂版原文の日付
  ブラケット表記"[15 November 2013, P.U. (B) 464/2013]"を確認
  ——2010年の成立日ではなく、実際に施行された2013年11月15日を
  :enacted-dateとして採用（法的効力発生日を優先）。

4 tests/11 assertions green（新規実装のため既存marketentry testsは
無し）。146リポジトリ・838 factを統合。`jurisdiction MYS`クエリと、
`ordinance/country`横断query（`[?e "ordinance/country" "MYS"]`）で
tick122のKuala Lumpur自治体2件が正しくクロスリンクされることを
確認——マレーシアは今回で国軸+自治体軸の2軸に到達したが、業界団体
軸はまだ未着手であり、これを正直に報告する。

現状: 国軸42件・業界団体軸41件(28業種)・自治体軸40件——123 tickを
経て3軸すべてが実データ・個別検証済み・捏造なしで成長を継続して
いる。マレーシアの業界団体軸の空白は今後のtickに持ち越し。

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
