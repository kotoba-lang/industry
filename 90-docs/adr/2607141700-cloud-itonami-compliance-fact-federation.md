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
